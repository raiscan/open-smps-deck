package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NoteEvent;
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

    private static ReconstructionTrace.Row placed(String id, String lane, int start) {
        var note = new Note(id, 0, start, start + .5, 60, 90, "midi", 1);
        var timing = new ReconstructionTrace.Timing(start, start + .5, .5, start, start + .5, .5,
                start * 60L, start * 60L + 30, 30);
        var at = new ReconstructionTrace.NoteAt(note, timing);
        var allocation = new ReconstructionTrace.Allocation(at, Role.harmony, List.of("FM3", "FM4"), lane,
                timing, ReconstructionAllocation.Outcome.kept, null, null, null, null, null, List.of());
        return new ReconstructionTrace.Row(id, 0, "Synth", 60, timing, timing, null, at, null, null, null,
                allocation, List.of(), ReconstructionTrace.Status.kept, "allocate", "kept", null, null);
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
