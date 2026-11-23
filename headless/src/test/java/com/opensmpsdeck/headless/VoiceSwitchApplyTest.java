package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The apply step against a real work directory: it publishes a clean rebuild or leaves everything as found. */
class VoiceSwitchApplyTest {
    @Test void anAppliedSwitchSurvivesReloadAndAFailedOneLeavesNoTrace(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("fixture.mid"), wav = dir.resolve("mix.wav"), index = dir.resolve("catalogue.json");
        var source = new Sequence(Sequence.PPQ, 480);
        var track = source.createTrack();
        for (int[] event : new int[][]{{60, 0, 420}, {64, 480, 900}, {67, 960, 1380}}) {
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, event[0], 90), event[1]));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, event[0], 0), event[2]));
        }
        MidiSystem.write(source, 0, midi.toFile());
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        var bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String first = builder.variant(builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "First", bytes), "First",
                InstrumentCatalogue.Recipe.plain(), ReconstructionCatalogueFixture.stock());
        bytes[1] = (byte) (bytes[1] ^ 3);
        String second = builder.variant(builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Second", bytes), "Second",
                InstrumentCatalogue.Recipe.plain(), ReconstructionCatalogueFixture.stock());
        var catalogue = builder.build();
        catalogue.save(index);
        WaveFile.render(ProjectCompiler.compileWithAssets(CataloguePlayback.matchProbe(catalogue, first, 68, 24)), wav, 180);
        Path work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), index.toString(), work.toString(),
                "--seconds", "3", "--stem", "0=" + wav);
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        String note = allocation.notes().stream().filter(entry -> entry.lane() != null && entry.lane().startsWith("FM"))
                .findFirst().orElseThrow().note().id();
        var edit = JsonParser.parseString("""
                {"id":"voice-switch-test","op":"noteVoicing","author":"agent","reason":"fixture",
                 "laneType":"FM","gain":0.9}
                """).getAsJsonObject();
        edit.addProperty("instrument", second);
        var ids = new JsonArray();
        ids.add(note);
        edit.add("noteIds", ids);
        var edits = new JsonArray();
        edits.add(edit);
        Path journal = work.resolve(ReconstructionEdits.FILE), report = work.resolve("report.json");
        int limit = ReconstructionStages.readScore(work.resolve("score.json")).profile().bankLimitBytes();
        int bank = bankBytes(report);

        // A bank that cannot fit: the edit is dropped, and the journal and build are as they were.
        var refused = VoiceSwitchReview.apply(work, edits, 1);
        assertTrue(refused.getAsJsonArray("edits").isEmpty());
        assertTrue(refused.getAsJsonArray("dropped").get(0).getAsJsonObject().get("dropped").getAsString()
                .contains("compiled bank exceeds"));
        assertFalse(Files.exists(journal));
        assertEquals(bank, bankBytes(report));

        // A clean rebuild is published, with the edit applied and recorded.
        var applied = VoiceSwitchReview.apply(work, edits, limit);
        assertEquals(1, applied.getAsJsonArray("edits").size());
        assertFalse(applied.has("dropped"));
        assertTrue(ReconstructionEdits.read(work).intents().stream().anyMatch(intent -> intent.id().equals("voice-switch-test")));
        assertTrue(JsonParser.parseString(Files.readString(report)).getAsJsonObject().getAsJsonArray("editResults")
                .toString().contains("\"voice-switch-test\""));
        byte[] published = Files.readAllBytes(journal);

        // Applying the same edit again fails on its duplicate ID and must not undo the first.
        var repeated = VoiceSwitchReview.apply(work, edits, limit);
        assertTrue(repeated.getAsJsonArray("edits").isEmpty());
        assertArrayEquals(published, Files.readAllBytes(journal));
        assertTrue(JsonParser.parseString(Files.readString(report)).getAsJsonObject().getAsJsonArray("editResults")
                .toString().contains("\"voice-switch-test\""));
    }

    private static int bankBytes(Path report) throws Exception {
        return JsonParser.parseString(Files.readString(report)).getAsJsonObject().get("bankBytes").getAsInt();
    }

    private static void cli(String... args) throws Exception {
        var stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(new ByteArrayOutputStream()); var err = new PrintStream(stderr)) {
            assertEquals(0, Main.run(args, out, err), stderr.toString());
        }
    }
}
