package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExternalTranscribersTest {
    @TempDir Path directory;
    private Path midi;
    private Path wav;

    @BeforeEach void inputs() throws Exception {
        midi = directory.resolve("source.mid");
        wav = directory.resolve("mix with spaces.wav");
        var sequence = new Sequence(Sequence.PPQ, 480);
        addPart(sequence, 0, 72);
        addPart(sequence, 1, 45);
        addPart(sequence, 9, 36);
        MidiSystem.write(sequence, 1, midi.toFile());
        WaveFile.render(ProjectCompiler.compileWithAssets(DemoProject.create()), wav, 60);
    }

    @Test void runsConfiguredCommandAndReplaysItsSnapshotWithoutTheConfig() throws Exception {
        Path work = directory.resolve("external");
        Path config = directory.resolve("transcribers.json");
        // Launch the fixture with Java to exercise argv substitution without a shell or Python.
        var fixture = TranscriberFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of(fixture).toString(), TranscriberFixture.class.getName(),
                "{input}", "{output}", "{role}", "{device}");
        Files.writeString(config, new Gson().toJson(Map.of("fixture", command)));
        run(0, fresh(work, "--stem", "0=" + wav, "--transcriber-config", config.toString(),
                "--transcriber", "fixture", "--transcribe-part", "0=lead", "--transcriber-device", "cuda:0"));
        Path scorePath = work.resolve("score.json");
        var score = ReconstructionStages.readScore(scorePath);
        assertEquals(List.of(78), pitches(score, 0));
        assertEquals(List.of(45), pitches(score, 1));
        assertEquals(List.of(36), pitches(score, 2));
        var lead = score.notes().stream().filter(note -> note.part() == 0).findFirst().orElseThrow();
        assertEquals("transcriber", lead.source());
        assertEquals(1, lead.end());
        var evidence = score.inputs().externalNotes().get(0).provenance();
        assertEquals("fixture", evidence.tool());
        assertEquals("cuda:0", evidence.device());
        assertEquals(ReconstructionCli.audioHash(wav), evidence.inputSha256());
        assertEquals("example", evidence.metadata().get("model"));
        assertEquals(2, evidence.noteCount());

        run(0, List.of("reconstruct", "structure", work.toString()));
        var structure = ReconstructionStages.readStructure(work.resolve("structure.json"), score);
        assertTrue(structure.notes().stream().anyMatch(note -> note.id().equals(lead.id())));
        String saved = Files.readString(scorePath);
        Files.delete(config);
        // Resume a chain through score and structure, then stop at the deliberately missing catalogue.
        assertTrue(run(2, List.of("reconstruct", "run", "--from", "transcribe", work.toString()))
                .contains("index.json"));
        assertEquals(saved, Files.readString(scorePath));
    }

    @Test void importsMidiOrJsonAndRejectsDrumReplacement() throws Exception {
        Path work = directory.resolve("imported");
        Path replacement = directory.resolve("replacement.mid");
        var sequence = new Sequence(Sequence.PPQ, 480);
        addPart(sequence, 0, 79);
        MidiSystem.write(sequence, 0, replacement.toFile());
        Path addition = directory.resolve("addition.json");
        Files.writeString(addition, "[[0.25, 0.5, 52]]");
        run(0, fresh(work, "--replace-notes", "0=" + replacement, "--add-notes", "1=" + addition));
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        assertEquals(List.of(79), pitches(score, 0));
        assertEquals(List.of(45, 52), pitches(score, 1));
        assertEquals(List.of(36), pitches(score, 2));
        assertEquals(85, score.notes().stream().filter(note -> note.part() == 0).findFirst().orElseThrow().velocity());

        Path rejected = directory.resolve("rejected");
        assertTrue(run(2, fresh(rejected, "--replace-notes", "2=" + addition)).contains("melodic MIDI part"));
        assertFalse(Files.exists(rejected));
    }

    @Test void missingExecutableAbortsAndNamesTheTranscriber() throws Exception {
        Path work = directory.resolve("failed");
        Path config = directory.resolve("transcribers.json");
        Files.writeString(config, new Gson().toJson(Map.of("missing",
                List.of(directory.resolve("absent").toString(), "{input}", "{output}"))));
        String error = run(2, fresh(work, "--stem", "0=" + wav, "--transcriber", "missing",
                "--transcribe-part", "0=lead", "--transcriber-config", config.toString()));
        assertTrue(error.contains("Transcriber missing"), error);
        assertFalse(Files.exists(work));
    }

    private List<String> fresh(Path work, String... options) {
        var args = new ArrayList<>(List.of("reconstruct", "transcribe", midi.toString(), wav.toString(),
                directory.resolve("index.json").toString(), work.toString(), "--tempo", "120"));
        args.addAll(List.of(options));
        return args;
    }

    private static String run(int status, List<String> args) {
        var errors = new ByteArrayOutputStream();
        try (var output = new PrintStream(java.io.OutputStream.nullOutputStream());
             var log = new PrintStream(errors)) {
            assertEquals(status, Main.run(args.toArray(String[]::new), output, log), errors::toString);
        }
        return errors.toString();
    }

    private static List<Integer> pitches(ReconstructionStages.Score score, int part) {
        return score.notes().stream().filter(note -> note.part() == part)
                .map(ReconstructionStages.Note::pitch).toList();
    }

    private static void addPart(Sequence sequence, int channel, int pitch) throws Exception {
        var track = sequence.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, 85), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), 384));
    }

    public static class TranscriberFixture {
        public static void main(String[] args) throws Exception {
            if (!Files.isRegularFile(Path.of(args[0])) || !args[2].equals("lead") || !args[3].equals("cuda:0"))
                throw new IllegalArgumentException("Invalid substituted arguments");
            Files.writeString(Path.of(args[1]), "[[0.2, 1.1, 78], [1.1, 1.2, 80]]");
            Files.writeString(Path.of(args[1] + ".meta.json"), "{\"model\":\"example\"}");
        }
    }
}
