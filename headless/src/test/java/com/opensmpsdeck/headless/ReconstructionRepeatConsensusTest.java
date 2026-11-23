package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionRepeatConsensusTest {
    @TempDir Path directory;

    private ReconstructionStages.Score fixture(Path catalogue) throws Exception {
        var bars = new ArrayList<ReconstructionStructure.Bar>();
        var scoreNotes = new ArrayList<Note>();
        var performed = new ArrayList<Note>();
        int[] pitches = {60, 64, 67, 69};
        for (int bar = 0; bar < 12; bar++) {
            double start = bar * 2;
            bars.add(new ReconstructionStructure.Bar("b" + (bar + 1), start, start + 2, true));
            for (int slot = 0; slot < pitches.length; slot++) {
                double time = start + .2 + slot * .36 + (bar == 8 ? .06 : 0);
                var note = new Note("note-" + bar + "-" + slot, 0, time, time + .20,
                        pitches[slot], 96, "audio-transcribed", 1);
                if (slot != 3 || bar < 8) scoreNotes.add(note);
                if (slot != 3 || bar < 10) performed.add(note);
                if (slot == 3 && bar == 11)
                    performed.add(new Note("variation", 0, time, time + .20, 57, 96, "audio-transcribed", 1));
            }
        }
        Path wave = directory.resolve("stem.wav");
        WaveFile.write(wave, 24 * 60, sink -> {
            for (int frame = 0; frame < 24 * 60; frame++) {
                var samples = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
                double frameStart = frame / 60.0;
                var sounding = performed.stream().filter(n -> n.end() > frameStart && n.start() < frameStart + 1.0 / 60).toList();
                for (int i = 0; i < samples.length; i += 2) {
                    double time = frameStart + i / (2.0 * S3kRenderer.SAMPLE_RATE);
                    double value = 0;
                    for (var note : sounding) if (time >= note.start() && time < note.end()) {
                        double amplitude = 6500;
                        if (note.pitch() == 69 && note.start() > 16) amplitude = note.start() < 18 ? 850 : 650;
                        double phase = Math.TAU * EvidenceSpectrum.hz(note.pitch()) * time;
                        value += amplitude * Math.sin(phase);
                        if (note.id().equals("variation")) value += 1400 * Math.sin(2 * phase);
                    }
                    samples[i] = samples[i + 1] = (short) value;
                }
                sink.accept(samples);
            }
        });
        var provenance = new ReconstructionAudioOnly.Provenance("audio-only", null,
                new ReconstructionAudioOnly.Detector("synthetic", "1", List.of(), null),
                Map.of(0, new ReconstructionAudioOnly.Stem("synth", "lead", "lead")), List.of(), null);
        var raw = scoreNotes.stream().map(n -> new ExternalTranscribers.RawNote(n.start(), n.end(), n.pitch(), n.velocity())).toList();
        var transcriber = new ExternalTranscribers.PartNotes(
                new ExternalTranscribers.Evidence("synthetic", "lead", null, null, "0".repeat(64), null,
                        Map.of(), raw.size(), true), raw);
        var input = new ReconstructionStages.Inputs(null, wave.toString(), catalogue.toString(), "Repeat fixture", 30,
                false, false, false, false, Map.of(0, wave.toString()), null, 0, null, false,
                Map.of(0, transcriber), 0, null, null, null, provenance);
        var source = new MidiScore(List.of(new MidiScore.Part(0, "synth", 80, false)), List.of(), List.of());
        return new ReconstructionStages.Score(VERSION, 24_000_000, 1440, 60, input, bars,
                source, Map.of(), scoreNotes, List.of(), List.of(), null, null);

    }

    private static JsonArray journal(String fill, String lanes) {
        return JsonParser.parseString("""
                [{"id":"repeat-synth","reason":"Check the synth pattern","op":"repeatPolicy","part":0,
                  "fill":"%s","lanes":"%s"}]
                """.formatted(fill, lanes)).getAsJsonArray();
    }

    @Test void sensitivitySeparatesWeakAttacksAndPreservesSilenceAndHarmonics() throws Exception {
        var score = fixture(directory.resolve("unused.json"));
        var base = ReconstructionStages.structure(score);
        assertNull(base.repeatNotes());
        assertEquals(base, evaluate(score, "off", "off"));
        assertTrue(fills(evaluate(score, "low", "off")).isEmpty());
        var medium = evaluate(score, "medium", "off");
        assertEquals(1, fills(medium).size(), () -> "Unexpected medium fills: " + fills(medium));
        var fill = fills(medium).getFirst();
        assertEquals(69, fill.pitch());
        assertEquals(17.34, fill.start(), .025);
        var evidence = medium.repeatNotes().get(fill.id());
        assertEquals(8, evidence.votes().size());
        assertNotNull(evidence.stemDb());
        assertNotNull(evidence.reason());
        assertFalse(evidence.keepsLanes());
        var high = evaluate(score, "high", "off");
        assertEquals(List.of("repeat-consensus-b9-note-0-3", "repeat-consensus-b10-note-0-3"),
                fills(high).stream().map(Note::id).toList());
        assertEquals(high, evaluate(score, "high", "off"));

        for (var lanes : List.of("on", "strict")) {
            var pattern = evaluate(score, "off", lanes);
            assertTrue(fills(pattern).isEmpty());
            assertTrue(pattern.repeatNotes().get("note-0-0").keepsLanes());
            assertEquals(lanes.equals("strict"), pattern.repeatNotes().get("note-0-0").strict());
        }
    }

    @Test void journalReplaysThroughStitchAndLivePreviewAndCanBeUndone() throws Exception {
        var catalogue = directory.resolve("catalogue.json");
        ReconstructionEditsTest.writeAudio(catalogue, directory.resolve("probe.wav"), true);
        var score = fixture(catalogue);
        var work = Files.createDirectory(directory.resolve("work"));
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
        ReconstructionStages.Progress quiet = (done, total, message) -> {};
        ReconstructionStages.runForReview("run", new String[]{"--from", "structure", work.toString()}, quiet);

        var revision = ReconstructionReview.revision(work);
        var base = TargetPlayback.prepare(ReconstructionReview.arrangement(work).project());
        var edits = journal("medium", "on");
        assertEquals("structure", ReconstructionReview.firstStage(work, revision.entries(), edits));
        var output = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.PrintStream(output)) {
            assertEquals(0, Main.run(new String[]{"reconstruct", "edit", work.toString(), "add", "--dry-run", "--verbose",
                    edits.get(0).toString()}, out, out), output::toString);
        }
        var dryRun = JsonParser.parseString(output.toString()).getAsJsonObject();
        assertEquals("structure", dryRun.get("from").getAsString());
        assertEquals("applied", dryRun.getAsJsonArray("outcomes").get(0).getAsJsonObject().get("status").getAsString());
        var live = ReconstructionReview.liveRebuild(work, revision, revision.entries(), edits, quiet);
        assertEquals(revision, ReconstructionReview.revision(work));
        assertEquals(1, live.allocation().notes().stream()
                .filter(row -> row.note().source().equals(ReconstructionRepeatConsensus.SOURCE)).count());

        ReconstructionBranches.create(work, "pattern", "test");
        ReconstructionBranches.edit(work, "pattern", edits, List.of(), Set.of());
        var pick = new ReconstructionBranches.Selection(Set.of(0), 16.0, 18.0, Set.of(), Set.of());
        var plan = ReconstructionBranches.planStitch(work,
                List.of(new ReconstructionBranches.SourceSelection("pattern", pick, Set.of())), quiet);
        assertEquals(1, plan.selectedEdits());
        assertTrue(plan.directConflicts().isEmpty());
        assertEquals("applied", plan.outcomes().get("repeat-synth").getFirst().status());
        assertEquals("pattern", plan.journal().get(0).getAsJsonObject().getAsJsonObject("provenance")
                .get("branch").getAsString());

        ReconstructionReview.apply(work, revision, plan.journal(), quiet);
        var applied = ReconstructionReview.revision(work);
        assertEquals(plan.journal(), applied.entries());
        assertEquals(ReconstructionRepeatPolicy.Fill.medium, ReconstructionRepeatPolicy.forPart(applied.entries(), 0).fill());
        assertEquals(ReconstructionRepeatPolicy.OFF, ReconstructionRepeatPolicy.forPart(applied.entries(), 1));
        assertEquals(live.allocation(),
                ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class));
        assertArrayEquals(live.playback().compiled().song(),
                TargetPlayback.prepare(ReconstructionReview.arrangement(work).project()).compiled().song());

        var undo = ReconstructionReview.liveRebuild(work, applied, applied.entries(), revision.entries(), quiet);
        assertTrue(undo.allocation().notes().stream().noneMatch(row -> row.note().source().equals(ReconstructionRepeatConsensus.SOURCE)));
        assertArrayEquals(base.compiled().song(), undo.playback().compiled().song());
        ReconstructionReview.apply(work, applied, revision.entries(), quiet);
        assertTrue(ReconstructionReview.revision(work).entries().isEmpty());
    }

    @Test void strictCanClaimAPatternLaneFromAnotherPartsOptionalOctave() {
        var role = ReconstructionRoles.Role.harmony;
        var notes = List.of(
                new Note("first", 0, .2, .5, 67, 96, "audio-transcribed", 1),
                new Note("prime", 1, 1.7, 1.95, 60, 96, "audio-transcribed", 1),
                new Note("root", 1, 2, 2.5, 48, 96, "audio-transcribed", 1),
                new Note("octave", 1, 2, 2.5, 60, 96, "audio-transcribed", 1),
                new Note("second", 0, 2.2, 2.5, 67, 96, "audio-transcribed", 1),
                new Note("blocker", 1, 4, 4.5, 48, 96, "audio-transcribed", 1),
                new Note("third", 0, 4.2, 4.5, 67, 96, "audio-transcribed", 1),
                new Note("fourth", 0, 5.2, 5.5, 67, 96, "audio-transcribed", 1));
        var decisions = notes.stream().map(note -> new ReconstructionRoles.Decision(note, role, null, null)).toList();
        var parts = List.of(new MidiScore.Part(0, "Pattern", 80, false), new MidiScore.Part(1, "Layer", 80, false));
        var pins = Map.of("root", new ReconstructionEdits.Pin("FM4", "pin-root", 0),
                "blocker", new ReconstructionEdits.Pin("FM3", "pin-blocker", 1),
                "prime", new ReconstructionEdits.Pin("FM3", "pin-prime", 2));
        for (var lanes : ReconstructionRepeatPolicy.Lanes.values()) {
            var pattern = new ReconstructionRepeatConsensus.Member("first", true, null, null, null, lanes);
            var repeats = Map.of("first", pattern, "second", pattern, "third", pattern, "fourth", pattern);
            var roles = new ReconstructionRoles.Result(VERSION, 7.5, decisions,
                    ReconstructionTimeMap.TimeBase.source, null, pins, repeats);
            var allocation = ReconstructionAllocation.allocate(roles, parts, 360, true);
            var byId = allocation.notes().stream().collect(java.util.stream.Collectors.toMap(row -> row.note().id(), row -> row));
            assertEquals("FM3", byId.get("first").lane());
            assertEquals("FM4", byId.get("root").lane());
            assertEquals("FM3", byId.get("blocker").lane());
            assertNotEquals("FM3", byId.get("third").lane(), "An explicit pin wins over the preferred lane");
            if (lanes == ReconstructionRepeatPolicy.Lanes.off) assertNotEquals("FM3", byId.get("fourth").lane());
            else assertEquals("FM3", byId.get("fourth").lane());
            if (lanes == ReconstructionRepeatPolicy.Lanes.strict) {
                assertEquals("FM3", byId.get("second").lane());
                assertEquals("yielded-to-repeat-pattern", byId.get("octave").reason());
            } else {
                assertEquals("FM5", byId.get("second").lane());
                assertEquals(ReconstructionAllocation.Outcome.kept, byId.get("octave").outcome());
            }
        }
    }

    private static ReconstructionStructure.Result evaluate(ReconstructionStages.Score score, String fill, String lanes)
            throws Exception {
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var result = ReconstructionStages.structure(score, List.of(), ReconstructionReview.parse(journal(fill, lanes)), outcomes);
        assertEquals(List.of(ReconstructionEdits.Status.applied), outcomes.stream().map(ReconstructionEdits.Outcome::status).toList());
        ReconstructionChecks.structure(result, score);
        return result;
    }

    private static List<Note> fills(ReconstructionStructure.Result structure) {
        return structure.notes().stream().filter(note -> ReconstructionRepeatConsensus.SOURCE.equals(note.source())).toList();
    }
}
