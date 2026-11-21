package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionTraceTest {
    @Test void namesPsgPhaseCarryAcrossAnUnchangedClip(@TempDir Path directory) throws Exception {
        var before = java.nio.file.Files.createDirectory(directory.resolve("before"));
        var after = java.nio.file.Files.createDirectory(directory.resolve("after"));
        var inputs = new ReconstructionStages.Inputs("source.mid", "mix.wav", "index.json", "Fixture",
                30, false, false, false, false, Map.of(), null, 7.5, null, null);
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs,
                List.of(new ReconstructionStructure.Bar("b1", 0, 1, false)),
                new MidiScore(List.of(), List.of(), List.of()), Map.of(), List.of(), List.of(), List.of(), null, null);
        var late = new Sequence("late", "Late", "PSG", 10, null, 127,
                List.of(new NoteEvent("later", 0, 10, 64)));
        for (Path work : List.of(before, after)) {
            var early = new Sequence("early", "Early", "PSG", 5, null, 127,
                    List.of(new NoteEvent("changed", 0, 5, work.equals(before) ? 60 : 67)));
            var project = new StudioProject(4, "Phase carry", "S3K", 15, 0, List.of(), List.of(early, late),
                    List.of(new Clip("a", "early", "PSG1", 0, 5), new Clip("b", "late", "PSG1", 42, 10)));
            ReconstructionArtifacts.write(work.resolve("score.json"), score);
            ProjectFiles.save(work.resolve("arrangement.smps.json"), project, null);
            WaveFile.render(ProjectCompiler.compileWithAssets(project), work.resolve("render.wav"), 60);
        }
        var diff = ReconstructionTrace.diff(before, after, "0,.5");
        assertEquals(0, diff.getAsJsonObject("compiledTimeline").get("changedEvents").getAsInt());
        var pcm = diff.getAsJsonObject("renderedPcm");
        assertTrue(pcm.get("changedFrames").getAsLong() > 0);
        var lanes = pcm.getAsJsonArray("psgTail");
        assertNotNull(lanes, pcm::toString);
        assertEquals(1, lanes.size());
        var psg = lanes.get(0).getAsJsonObject();
        assertEquals("PSG1", psg.get("lane").getAsString());
        assertEquals("tone oscillator phase", psg.get("cause").getAsString());
        assertEquals(pcm.get("changedFrames"), psg.get("changedFrames"));
        assertEquals(pcm.get("firstFrame"), psg.get("firstFrame"));
    }

    @Test void groupsSwapsOutsideTheSpanAndSuggestsPinsAtTheFirstDivergence() {
        var before = new java.util.LinkedHashMap<String, ReconstructionTrace.Row>();
        var after = new java.util.LinkedHashMap<String, ReconstructionTrace.Row>();
        for (int i = 0; i < 8; i++) {
            String id = "note-" + i;
            String lane = i % 2 == 0 ? "FM3" : "FM4";
            String swapped = lane.equals("FM3") ? "FM4" : "FM3";
            before.put(id, placed(id, lane, i));
            after.put(id, placed(id, i == 5 ? lane : swapped, i));
        }
        var diff = ReconstructionTrace.diff(before, after, new ReconstructionTrace.Span(0.0, 1.5));
        var groups = diff.getAsJsonArray("lanePermutations");
        assertEquals(2, groups.size());
        var first = groups.get(0).getAsJsonObject();
        assertEquals(3, first.get("noteCount").getAsInt());
        assertEquals(2.0, first.get("startSeconds").getAsDouble());
        assertEquals(4.5, first.get("endSeconds").getAsDouble());
        assertEquals(JsonParser.parseString("{\"FM3\":\"FM4\",\"FM4\":\"FM3\"}"), first.get("lanes"));
        assertEquals(JsonParser.parseString("[\"note-2\",\"note-3\"]"), first.get("firstNoteIds"));
        var pin = ReconstructionEdits.parse(first.getAsJsonArray("pins").get(0).getAsJsonObject(), "pin");
        assertEquals(List.of("note-2"), pin.noteIds());
        assertEquals("FM3", pin.lane());
    }

    @Test void droppedHatReportsBusyIntervalsAndOnlySuggestsLanesFreeForTheWholeTone() {
        var clock = new ReconstructionTimeMap(null, null, 1);
        var hat = drumRow("hat", Role.drums, null, 16, 20, "psg-noise-lane-busy", "tone", clock);
        var tone = drumRow("tone", Role.harmony, "PSG3", 10, 28, null, null, clock);
        var later = drumRow("late", Role.harmony, "FM1", 26, 30, null, null, clock);
        var boundary = drumRow("boundary", Role.harmony, "PSG1", 28, 32, null, null, clock);
        var all = Map.of("tone", tone, "hat", hat, "late", later, "boundary", boundary);
        var review = DrumEvidence.review(List.of(hat), all, 0, 1, clock, "PSG3", List.of(), false);
        var hit = review.droppedHits().getFirst();
        assertEquals("hat", hit.noteId());
        assertEquals("PSG3", hit.lane());
        var blocker = hit.competitors().getFirst();
        assertEquals("tone", blocker.noteId());
        assertEquals(10, blocker.busyInterval().startTick());
        assertEquals(28, blocker.busyInterval().endTick());
        var lanes = blocker.pinLaneEdits().stream().map(DrumEvidence.PinLane::lane).toList();
        assertEquals(List.of("FM2", "FM3", "FM4", "FM5", "PSG1", "PSG2"), lanes);
        assertEquals(List.of("tone"), blocker.pinLaneEdits().getFirst().noteIds());
    }

    private static ReconstructionTrace.Row drumRow(String id, Role role, String lane, int from, int to, String reason,
                                                    String competitor, ReconstructionTimeMap clock) {
        int pitch = role == Role.drums ? 42 : 60;
        var note = new Note(id, 0, from / 60.0, to / 60.0, pitch, 90, "midi", 1);
        var timing = ReconstructionTrace.ticks(from, to, clock);
        var at = new ReconstructionTrace.NoteAt(note, timing);
        var placed = new ReconstructionTrace.Allocation(at, role, List.of(), lane, lane == null ? null : timing,
                lane == null ? ReconstructionAllocation.Outcome.dropped : ReconstructionAllocation.Outcome.kept,
                reason, null, competitor, null, null, List.of());
        return new ReconstructionTrace.Row(id, 0, "Fixture", pitch, timing, timing, null, at, null, null,
                new ReconstructionTrace.Roles(at, role, null, null, null, List.of()), placed, List.of(),
                lane == null ? ReconstructionTrace.Status.dropped : ReconstructionTrace.Status.kept,
                "allocate", reason, null, null, null, null, null);
    }

    private static ReconstructionTrace.Row placed(String id, String lane, int start) {
        var note = new Note(id, 0, start, start + .5, 60, 90, "midi", 1);
        var timing = new ReconstructionTrace.Timing(start, start + .5, .5, start, start + .5, .5,
                start * 60L, start * 60L + 30, 30);
        var at = new ReconstructionTrace.NoteAt(note, timing);
        var allocation = new ReconstructionTrace.Allocation(at, Role.harmony, List.of("FM3", "FM4"), lane,
                timing, ReconstructionAllocation.Outcome.kept, null, null, null, null, null, List.of());
        return new ReconstructionTrace.Row(id, 0, "Synth", 60, timing, timing, null, at, null, null, null,
                allocation, List.of(), ReconstructionTrace.Status.kept, "allocate", "kept", null, null, null, null, null);
    }

    @Test void samePitchAttacksHaveDistinctStableIdsInOneCluster(@TempDir Path work) throws Exception {
        var raw = new MidiScore.Note(0, 65, 96, 500_000, 600_000);
        var key = ReconstructionStages.midiNoteKey(raw);
        var alternate = ReconstructionStages.noteInstance(key, 2);
        var source = new MidiScore(List.of(new MidiScore.Part(0, "Keys", 0, false)), List.of(raw, raw), List.of());
        var inputs = new ReconstructionStages.Inputs("source.mid", "mix.wav", "index.json", "Fixture",
                30, false, false, false, false, Map.of(), null, 7.5, null, null);
        var written = List.of(new Note(key, 0, .5, .6, 65, 96, "midi", 1),
                new Note(alternate, 0, .5, .6, 65, 96, "midi", 1));
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs, List.of(new ReconstructionStructure.Bar("b1", 0, 1, false)), source, Map.of(0, 2),
                written, List.of(), List.of(), null, null);
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
        var trace = ReconstructionTrace.trace(work, "--by-onset", "--start", "0.5", "--end", "0.6", "--part", "0");
        var clusters = trace.getAsJsonArray("clusters");
        assertEquals(1, clusters.size());
        var notes = clusters.get(0).getAsJsonObject().getAsJsonArray("notes");
        assertEquals(List.of(key, alternate), notes.asList().stream()
                .map(row -> row.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(trace, ReconstructionTrace.trace(work, "--by-onset"));
    }

    @Test void tracesMergesTruncationAndUnpropagatedScoreEdits(@TempDir Path work) throws Exception {
        var held = new Note("held", 0, .1, .8, 68, 96, "midi", 1);
        var winner = new Note("winner", 0, .5, .7, 70, 96, "midi", 1);
        var loser = new Note("loser", 0, .5, .7, 71, 80, "midi", 1);
        var chord = new Note("chord", 0, .2, .3, 60, 96, "midi", 1);
        var merged = new Note("merged", 0, .3, .4, 60, 96, "midi", 1);
        var pending = new Note("pending", 0, .9, .95, 63, 96, "audio-refine", 1);
        var parts = List.of(new MidiScore.Part(0, "Synth", 0, false));
        var notes = List.of(held, chord, merged, winner, loser);
        var bars = List.of(new ReconstructionStructure.Bar("b1", 0, 1, false));
        var inputs = new ReconstructionStages.Inputs("source.mid", "mix.wav", "index.json", "Fixture",
                30, false, false, false, false, Map.of(), null, 7.5, null, null);
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs, bars,
                new MidiScore(parts, List.of(), List.of()), Map.of(0, 0),
                List.of(held, chord, merged, winner, loser, pending), List.of(), List.of(), null, null);
        var structure = new ReconstructionStructure.Result(1, bars,
                List.of(new ReconstructionStructure.Section("s1", List.of("b1"))), List.of(), List.of(), notes);
        var roles = new ReconstructionRoles.Result(1, 7.5, List.of(
                new ReconstructionRoles.Decision(held, Role.melody, "roleplan#1", null),
                new ReconstructionRoles.Decision(chord.withTiming(.2, .4), Role.harmony, "sustained-inner-tone", null),
                new ReconstructionRoles.Decision(merged, Role.drop, "rapid-inner-tone-reattack", "chord"),
                new ReconstructionRoles.Decision(winner, Role.melody, null, null),
                new ReconstructionRoles.Decision(loser, Role.melody, null, null)), null);
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
        ReconstructionArtifacts.write(work.resolve("structure.json"), structure);
        ReconstructionArtifacts.write(work.resolve("roles.json"), roles);
        ReconstructionArtifacts.write(work.resolve("allocation.json"),
                ReconstructionAllocation.allocate(roles, parts, 60, false));

        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"reconstruct", "trace", work.toString(), "--json"},
                new PrintStream(output), new PrintStream(errors)), errors.toString());
        var traces = JsonParser.parseString(output.toString()).getAsJsonObject().getAsJsonArray("notes");
        var byId = new java.util.HashMap<String, com.google.gson.JsonObject>();
        traces.forEach(row -> byId.put(row.getAsJsonObject().get("id").getAsString(), row.getAsJsonObject()));
        assertEquals("shortened", byId.get("held").get("status").getAsString());
        assertEquals("winner", byId.get("held").getAsJsonObject("allocation").get("competitorNoteId").getAsString());
        assertEquals("merged", byId.get("merged").get("status").getAsString());
        assertEquals("chord", byId.get("merged").getAsJsonObject("roles").get("mergedInto").getAsString());
        assertEquals("structure", byId.get("pending").get("stage").getAsString());
        assertEquals("winner", byId.get("loser").getAsJsonObject("allocation").get("competitorNoteId").getAsString());
        assertEquals("melody-lane-busy", byId.get("loser").get("reason").getAsString());

        var filtered = ReconstructionTrace.trace(work, "--lane", "FM1", "--status", "shortened",
                "--start", ".25", "--pitch-min", "68", "--pitch-max", "69").getAsJsonArray("notes");
        assertEquals(1, filtered.size());
        assertEquals("held", filtered.get(0).getAsJsonObject().get("id").getAsString());
        assertThrows(IllegalArgumentException.class, () -> ReconstructionTrace.trace(work, "--pitch", "68", "--pitch-max", "70"));

        Path candidate = java.nio.file.Files.createDirectory(work.resolve("candidate"));
        for (String name : List.of("score.json", "structure.json", "roles.json"))
            java.nio.file.Files.copy(work.resolve(name), candidate.resolve(name));
        var repair = new ReconstructionRoles.Result(1, 7.5,
                roles.notes().stream().filter(choice -> !choice.note().id().equals("winner")).toList(), null);
        ReconstructionArtifacts.write(candidate.resolve("roles.json"), repair);
        ReconstructionArtifacts.write(candidate.resolve("allocation.json"),
                ReconstructionAllocation.allocate(repair, parts, 60, false));
        var voice = DemoProject.create().voices().getFirst();
        var first = new NoteEvent("first", 0, 5, 60);
        var later = new NoteEvent("later", 42, 5, 64);
        var initial = new Sequence("all", "FM", "FM", 47, voice.id(), 64, List.of(first, later));
        var early = new Sequence("early", "FM", "FM", 5, voice.id(), 64, List.of(first));
        var late = new Sequence("late", "FM", "FM", 5, voice.id(), 64,
                List.of(new NoteEvent("later", 0, 5, 64)));
        var oneClip = new StudioProject(4, "Before", "S3K", 15, 0,
                List.of(voice), List.of(initial), List.of(new Clip("a", "all", "FM1", 0, 47)));
        var split = new StudioProject(4, "After", "S3K", 15, 0, List.of(voice),
                List.of(early, late), List.of(new Clip("a", "early", "FM1", 0, 5),
                        new Clip("b", "late", "FM1", 42, 5)));
        ProjectFiles.save(work.resolve("arrangement.smps.json"), oneClip, null);
        ProjectFiles.save(candidate.resolve("arrangement.smps.json"), split, null);
        var timeline = ReconstructionTrace.diff(work, candidate, "0,.5").getAsJsonObject("compiledTimeline");
        assertTrue(timeline.get("changedEvents").getAsInt() > 0, timeline::toString);
        assertEquals(42, timeline.get("firstTick").getAsInt());
        assertEquals(JsonParser.parseString("[\"FM1\"]"), timeline.get("lanes"));
        var changes = ReconstructionTrace.diff(work, candidate, ".8,.9").getAsJsonArray("changes").asList();
        var recovered = changes.stream().map(row -> row.getAsJsonObject())
                .filter(row -> row.get("id").getAsString().equals("loser")).findFirst().orElseThrow();
        assertTrue(recovered.get("outsideSpan").getAsBoolean());
        assertEquals("dropped", recovered.getAsJsonObject("old").get("status").getAsString());
        assertEquals("kept", recovered.getAsJsonObject("new").get("status").getAsString());
    }
}
