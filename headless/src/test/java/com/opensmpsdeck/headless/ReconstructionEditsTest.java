package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionEditsTest {
    @Test void rejectsAmbiguousSelectorsAndUnorderedBendPoints() {
        var ambiguous = json("""
                {"id":"bad","reason":"evidence","op":"setRole","noteIds":["n"],
                 "span":[1,2],"part":0,"role":"melody"}
                """);
        var error = assertThrows(IllegalArgumentException.class, () -> ReconstructionEdits.parse(ambiguous, "edits[0]"));
        assertTrue(error.getMessage().contains("noteIds"));

        var unordered = json("""
                {"id":"bad","reason":"evidence","op":"bend","noteId":"n","points":[[0.2,0],[0.1,2]]}
                """);
        error = assertThrows(IllegalArgumentException.class, () -> ReconstructionEdits.parse(unordered, "edits[0]"));
        assertTrue(error.getMessage().contains("points[1]"));
    }

    @Test void unmergeRestoresTheEntireChainAndCanSelectByPitchAndSourceSpan() {
        var anchor = note("anchor", .10, .20, 60);
        var attack = note("attack", .20, .28, 60);
        var last = note("last", .28, .32, 60);
        var unrelated = note("unrelated", .25, .35, 64);
        var parts = List.of(new MidiScore.Part(0, "Synth", 0, false));
        var bars = List.of(new ReconstructionStructure.Bar("b0", 0, 1, false));
        var written = List.of(anchor, attack, last, unrelated);
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs(), bars,
                new MidiScore(parts, List.of(), List.of()), Map.of(), written, List.of(), List.of(), null, null);
        var structure = new ReconstructionStructure.Result(1, bars, List.of(), List.of(), List.of(), written);
        var roles = new ReconstructionRoles.Result(1, 7.5, List.of(
                new ReconstructionRoles.Decision(anchor.withTiming(.10, .32), Role.harmony, "sustained-inner-tone", null),
                new ReconstructionRoles.Decision(attack, Role.drop, "rapid-inner-tone-reattack", "anchor"),
                new ReconstructionRoles.Decision(last, Role.drop, "rapid-inner-tone-reattack", "anchor"),
                new ReconstructionRoles.Decision(unrelated, Role.counter, null, null)), null);
        var edit = intent("""
                {"op":"unmerge","id":"attacks","reason":"separate transients","part":0,"span":[0.19,0.26],
                 "pitchMin":60,"pitchMax":60}
                """);
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var actual = new ReconstructionEdits(List.of(edit)).applyRoles(score, structure, roles, List.of(), outcomes);
        assertEquals(List.of("anchor", "attack", "last"), outcomes.getFirst().noteIds());
        for (var decision : actual.notes()) {
            var original = written.stream().filter(n -> n.id().equals(decision.note().id())).findFirst().orElseThrow();
            assertEquals(original, decision.note());
            assertNull(decision.mergedInto());
            if (original != unrelated) {
                assertEquals(Role.harmony, decision.role());
                assertEquals("edit:attacks", decision.reason());
            }
        }
    }

    @Test void intentsSurviveTranscriptionAndManifestReplayAndReportOrphans(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("theme.mid");
        writeMidi(midi, true);
        Path catalogue = dir.resolve("catalogue.json");
        Path wav = dir.resolve("mix.wav");
        writeAudio(catalogue, wav);
        Path work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(), "--seconds", "2");

        String kept = "n-0-500000-64";
        String removed = "n-0-1250000-62";
        String competitor = "n-0-500000-67";
        cli("reconstruct", "edit", work.toString(), "add", """
                {"id":"answer","reason":"answer attack","op":"insertNote","part":0,"start":0.85,"end":1.2,
                 "pitch":68,"velocity":87,"role":"melody","lane":"FM1"}
                """);
        add(work, """
                {"id":"timing","reason":"lead phrasing","op":"setNote","noteId":"%s","start":0.51,"end":0.83,"pitch":65}
                """.formatted(kept), false);
        add(work, """
                {"id":"deletion","reason":"false detection","op":"deleteNote","noteId":"%s"}
                """.formatted(removed), false);
        add(work, """
                {"id":"melody","reason":"lead phrase","op":"setRole","span":[0.5,0.6],"part":0,
                 "pitchMin":65,"pitchMax":65,"role":"melody","lane":"FM1"}
                """, false);
        add(work, """
                {"id":"opponent","reason":"alternate placement","op":"pinLane","noteIds":["%s"],"lane":"FM1"}
                """.formatted(competitor), false);
        // These two onsets quantize to nearby presentations. The earlier pin occupies the lane through the retime.
        var rerun = add(work, """
                {"id":"scoop","reason":"pitch contour","op":"bend","noteId":"edit-answer",
                 "points":[[0.85,-2],[0.92,0],[1.2,0]]}
                """, true);
        assertEquals("transcribe", rerun.get("from").getAsString());
        assertTrue(rerun.getAsJsonObject("traceDiff").get("changedNotes").getAsInt() >= 4);
        assertTrue(rerun.getAsJsonObject("traceDiff").get("outsideSpanChanges").getAsInt() > 0);

        var trace = ReconstructionTrace.read(work);
        var answer = trace.get("edit-answer");
        assertEquals("edit", answer.source().provenance());
        assertEquals(87, answer.score().note().velocity());
        assertEquals("FM1", answer.allocation().lane());
        assertEquals("edit:scoop", answer.voiced().getFirst().bend().reason());
        assertEquals("edit:deletion", trace.get(removed).reason());
        assertNull(trace.get(removed).score());
        var changed = trace.get(kept);
        assertEquals(.51, changed.score().note().start());
        assertEquals(.83, changed.roles().effective().note().end());
        assertNull(changed.roles().preLegatoEnd());
        assertEquals(65, changed.score().note().pitch());
        assertEquals(Role.melody, changed.roles().role());
        assertEquals(competitor, changed.allocation().competitorNoteId());
        assertTrue(changed.editReasons().contains("edit:melody"));
        assertTrue(trace.containsKey("n-0-0-60"));

        Path replay = dir.resolve("replay");
        cli("reconstruct", "rerun", work.toString(), replay.toString());
        var replayTrace = ReconstructionTrace.diff(work, replay, null);
        assertEquals(0, replayTrace.getAsJsonArray("changes").size(), replayTrace.toString());
        cli("reconstruct", "run", "--from", "transcribe", work.toString());
        assertEquals(0, ReconstructionTrace.diff(work, replay, null).getAsJsonArray("changes").size());

        writeMidi(midi, false);
        String output = cli("reconstruct", "run", "--from", "transcribe", work.toString());
        assertTrue(output.contains("edit:deletion orphaned"), output);
        var report = ReconstructionArtifacts.readTree(work.resolve("report.json")).getAsJsonObject();
        assertTrue(report.getAsJsonArray("editResults").asList().stream().map(e -> e.getAsJsonObject()).anyMatch(
                e -> e.get("id").getAsString().equals("deletion") && e.get("status").getAsString().equals("orphaned")));

        var cleared = ReconstructionEditCommand.run(work, "remove", null, "answer", true, (a, b, c) -> {});
        assertFalse(ReconstructionTrace.read(work).containsKey("edit-answer"));
        assertTrue(cleared.toString().contains("\"id\":\"scoop\",\"op\":\"bend\",\"status\":\"orphaned\""));
    }

    private static Note note(String id, double start, double end, int pitch) {
        return new Note(id, 0, start, end, pitch, 90, "midi", 1);
    }

    private static ReconstructionStages.Inputs inputs() {
        return new ReconstructionStages.Inputs("theme.mid", "mix.wav", "catalogue.json", "Test", 30,
                false, false, false, false, Map.of(), null, 7.5, null, null);
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static ReconstructionEdits.Intent intent(String value) {
        return ReconstructionEdits.parse(json(value), "edit");
    }

    private static JsonObject add(Path work, String value, boolean rerun) throws Exception {
        return ReconstructionEditCommand.run(work, "add", json(value), null, rerun, (a, b, c) -> {});
    }

    private static String cli(String... args) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(stdout); var err = new PrintStream(stderr)) {
            assertEquals(0, Main.run(args, out, err), stderr.toString());
        }
        return stdout.toString();
    }

    private static void writeMidi(Path midi, boolean includeRemoved) throws Exception {
        var source = new Sequence(Sequence.PPQ, 480);
        var track = source.createTrack();
        for (int[] event : new int[][]{{60, 0, 420}, {64, 480, 720}, {67, 480, 770}, {62, 1200, 1450}}) {
            if (event[0] == 62 && !includeRemoved) continue;
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, event[0], 90), event[1]));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, event[0], 0), event[2]));
        }
        MidiSystem.write(source, 0, midi.toFile());
    }

    private static void writeAudio(Path index, Path wav) throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        var bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        String variant = builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();
        catalogue.save(index);
        WaveFile.render(ProjectCompiler.compileWithAssets(CataloguePlayback.matchProbe(catalogue, variant, 68, 24)), wav, 120);
    }
}
