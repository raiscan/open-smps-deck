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
        fixtureConfig(config);
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
        var manifest = ReconstructionArtifacts.read(work.resolve("manifest.json"), ReconstructionManifest.Manifest.class);
        var file = manifest.externalNotes().get(0).file();
        assertEquals("[[0.2, 1.1, 78], [1.1, 1.2, 80]]", Files.readString(work.resolve(file.snapshot())));
        assertEquals(evidence.outputSha256(), file.sha256());

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

    @Test void addsAnUnmappedPartAndRestoresItFromSavedNotes() throws Exception {
        Path work = directory.resolve("added");
        Path config = directory.resolve("transcribers.json");
        fixtureConfig(config);
        Path stem = directory.resolve("keyboard.wav");
        WaveFile.write(stem, 60, sink -> {
            var silence = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
            for (int i = 0; i < 60; i++) sink.accept(silence);
        });
        run(0, fresh(work, "--transcriber-config", config.toString(), "--transcriber", "fixture",
                "--transcribe-unmapped", "keyboard=" + stem, "--transcriber-device", "cuda:0"));

        var scorePath = work.resolve("score.json");
        var score = ReconstructionStages.readScore(scorePath);
        assertEquals(List.of(72), pitches(score, 0));
        assertEquals(List.of(82), pitches(score, 3));
        assertEquals("keyboard", score.source().parts().getLast().name());
        assertEquals("audio-transcribed", score.notes().getLast().source());
        assertTrue(score.reductions().stream().anyMatch(r -> "stem-midi-doubling".equals(r.reason())));
        var saved = ReconstructionArtifacts.read(work.resolve("manifest.json"), ReconstructionManifest.Manifest.class);
        var provenance = saved.externalNotes().get(3).provenance();
        assertEquals(true, provenance.addedStem());
        assertEquals("fixture", provenance.tool());
        assertEquals(ReconstructionCli.audioHash(stem), provenance.inputSha256());
        assertEquals(2, provenance.noteCount());
        var trace = ReconstructionTrace.read(work, score);
        assertEquals("stem-midi-doubling", trace.get("transcriber-3-0").reason());
        assertEquals("audio-transcribed", trace.get("transcriber-3-1").source().note().note().source());

        String before = Files.readString(scorePath);
        Files.delete(config);
        assertTrue(run(2, List.of("reconstruct", "run", "--from", "transcribe", work.toString()))
                .contains("index.json"));
        assertEquals(before, Files.readString(scorePath));
    }

    private static void fixtureConfig(Path config) throws Exception {
        // Launch the fixture with Java to exercise argv substitution without a shell or Python.
        var fixture = TranscriberFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of(fixture).toString(), TranscriberFixture.class.getName(),
                "{input}", "{output}", "{role}", "{device}");
        Files.writeString(config, new Gson().toJson(Map.of("fixture", command)));
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

    @Test void rerunFindsRelocatedInputsByHashAndUsesOriginalNoteBytes() throws Exception {
        var catalogue = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String asset = catalogue.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        catalogue.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        Path index = directory.resolve("index.json");
        catalogue.build().save(index);

        Path notes = directory.resolve("import.json");
        byte[] external = "[ [0.2, 0.6, 78] ]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(notes, external);
        Path work = directory.resolve("original");
        var args = fresh(work, "--seconds", ".8", "--replace-notes", "0=" + notes, "--fm-only");
        args.set(1, "run");
        run(0, args);
        var manifest = ReconstructionArtifacts.readTree(work.resolve("manifest.json")).getAsJsonObject();
        assertTrue(manifest.getAsJsonObject("tool").get("commit").getAsString().matches("[a-f0-9]{40}"));
        String snapshot = manifest.getAsJsonObject("externalNotes").getAsJsonObject("0")
                .getAsJsonObject("file").get("snapshot").getAsString();
        assertArrayEquals(external, Files.readAllBytes(work.resolve(snapshot)));
        Files.delete(notes);

        Path inputs = Files.createDirectory(directory.resolve("relocated"));
        Files.move(midi, inputs.resolve("renamed.midi"));
        Files.writeString(midi, "changed file");
        Files.move(wav, inputs.resolve("renamed.wav"));
        var rerun = directory.resolve("rerun");
        var replay = new ArrayList<>(List.of("reconstruct", "rerun", work.toString(), rerun.toString()));
        String missing = run(2, replay);
        assertTrue(missing.contains("source.mid") && missing.contains("mix with spaces.wav"), missing);
        assertFalse(Files.exists(rerun));
        replay.addAll(List.of("--search", inputs.toString()));
        run(0, replay);
        assertEquals(-1, Files.mismatch(work.resolve("render.wav"), rerun.resolve("render.wav")));
        var score = ReconstructionStages.readScore(rerun.resolve("score.json"));
        assertEquals(List.of(78), pitches(score, 0));
        assertEquals("import", score.inputs().externalNotes().get(0).provenance().tool());
    }

    @Test void audioOnlyRendersAndReplaysWithoutMidiOrTranscriberConfig() throws Exception {
        var catalogue = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String asset = catalogue.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        catalogue.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        byte[] kick = new byte[256];
        java.util.Arrays.fill(kick, (byte) 0x80);
        kick[0] = (byte) 0xff;
        String sample = catalogue.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Kick", kick);
        catalogue.variant(sample, "Kick", new InstrumentCatalogue.Recipe(0x14, null, 127, 60,
                null, null, List.of(), List.of()), null);
        Path index = directory.resolve("index.json");
        catalogue.build().save(index);

        Path config = directory.resolve("transcribers.json");
        fixtureConfig(config);
        Path drum = directory.resolve("drum.wav");
        Path vocal = directory.resolve("vocals.wav");
        WaveFile.write(vocal, 60, sink -> {
            for (int frame = 0; frame < 60; frame++) {
                var block = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
                for (int i = 0; frame >= 12 && frame < 47 && i < block.length / 2; i++) {
                    double t = frame / 60.0 + i / (double) S3kRenderer.SAMPLE_RATE;
                    block[2 * i] = block[2 * i + 1] = (short) (14000 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(78) * t));
                }
                sink.accept(block);
            }
        });
        Path quiet = directory.resolve("quiet.wav");
        WaveFile.write(quiet, 60, sink -> {
            var silence = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
            for (int i = 0; i < 60; i++) sink.accept(silence);
        });
        WaveFile.write(drum, 60, sink -> {
            for (int frame = 0; frame < 60; frame++) {
                var block = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
                for (int i = 0; i < block.length / 2 && frame >= 12 && frame < 20; i++) {
                    double t = (frame - 12) / 60.0 + i / (double) S3kRenderer.SAMPLE_RATE;
                    block[2 * i] = block[2 * i + 1] = (short) (18000 * Math.sin(Math.TAU * 60 * t) * Math.exp(-24 * t));
                }
                sink.accept(block);
            }
        });
        Files.delete(midi);
        var work = directory.resolve("audio-only");
        var args = List.of("reconstruct", "run", "--audio-only", wav.toString(), index.toString(), work.toString(),
                "--stem", "vocals=" + vocal, "--stem", "drums=" + drum, "--stem", "other=" + quiet,
                "--tempo", "105", "--bar-offset", "0.125", "--transcriber-config", config.toString(),
                "--transcriber-device", "cuda:0", "--fm-only");
        run(0, args);

        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        assertNull(score.inputs().midi());
        assertEquals("audio-only", score.inputs().audioOnly().source());
        assertEquals("fixture", score.inputs().audioOnly().transcriber());
        assertEquals("spectral", score.inputs().audioOnly().drumDetector().name());
        assertEquals(List.of("other"), score.inputs().audioOnly().skippedStems());
        assertEquals(1, score.inputs().leadPart());
        assertEquals("vocals", score.inputs().externalNotes().get(1).provenance().role());
        assertEquals(List.of(78), pitches(score, 1));
        assertEquals(List.of(36), pitches(score, 0));
        assertEquals(105, score.tempo().selected().bpm());
        assertEquals(.125, score.tempo().selected().barOffset());
        var manifest = ReconstructionArtifacts.read(work.resolve("manifest.json"), ReconstructionManifest.Manifest.class);
        assertFalse(manifest.inputs().containsKey("midi"));
        assertEquals(score.inputs().audioOnly(), manifest.options().audioOnly());

        Files.delete(config);
        var replay = directory.resolve("replay");
        run(0, List.of("reconstruct", "rerun", work.toString(), replay.toString()));
        assertEquals(-1, Files.mismatch(work.resolve("render.wav"), replay.resolve("render.wav")));
        assertEquals(-1, Files.mismatch(work.resolve("score.json"), replay.resolve("score.json")));
        var missing = new ArrayList<>(args);
        missing.set(5, directory.resolve("missing-config").toString());
        assertTrue(run(2, missing).contains("needs a configured transcriber"));
    }

    @Test void selectsRoleDefaultsAndOverridesIncludingAnExternalDrumDetector() throws Exception {
        Path config = directory.resolve("transcribers.json");
        Path fixture = Path.of(TranscriberFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", fixture.toString(), TranscriberFixture.class.getName(), "{input}", "{output}", "{role}", "{device}");
        Files.writeString(config, new Gson().toJson(Map.of("keys", command, "voice", command, "override", command,
                "defaults", Map.of("vocals", "voice", "keyboard", "keys", "drums", "drum-model"),
                "drumTranscribers", Map.of("drum-model", command))));
        Path keyboard = Files.copy(wav, directory.resolve("keyboard.wav"));
        Path drum = Files.copy(wav, directory.resolve("drums.wav"));
        Path work = directory.resolve("role-routing");
        run(0, List.of("reconstruct", "transcribe", "--audio-only", wav.toString(),
                directory.resolve("index.json").toString(), work.toString(), "--stem", "vocals=" + wav,
                "--stem", "keyboard=" + keyboard, "--stem", "drums=" + drum, "--transcriber-config", config.toString(),
                "--transcriber-for", "vocals=override,voice", "--transcriber-device", "cuda:0", "--tempo", "110"));
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var provenance = score.inputs().externalNotes().get(1).provenance();
        assertEquals("override,voice", provenance.tool());
        assertEquals("2", provenance.metadata().get("vocalFirstNoteCount"));
        assertEquals("override", provenance.metadata().get("vocalSelection"));
        assertTrue(provenance.metadata().get("vocalComparison").contains("fusion"));
        assertEquals("keys", score.inputs().externalNotes().get(2).provenance().tool());
        var detector = score.inputs().audioOnly().drumDetector();
        assertEquals("drum-model", detector.name());
        assertEquals("example", detector.metadata().get("model"));
        assertEquals(List.of(35, 38), pitches(score, 0));
        assertEquals(List.of(99, 85), detector.hits().stream().map(MidiScore.Note::velocity).toList());
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
            if (!Files.isRegularFile(Path.of(args[0])) || !List.of("lead", "keyboard", "vocals", "drums").contains(args[2]) || !args[3].equals("cuda:0"))
                throw new IllegalArgumentException("Invalid substituted arguments");
            String notes = switch (args[2]) {
                case "drums" -> "[[0.12, 35, 99], [0.4, 38, 85], [1.2, 42, 89]]";
                case "keyboard" -> "[[0.01, 0.38, 72], [0.2, 0.8, 82]]";
                default -> "[[0.2, 1.1, 78], [1.1, 1.2, 80]]";
            };
            Files.writeString(Path.of(args[1]), notes);
            Files.writeString(Path.of(args[1] + ".meta.json"), "{\"model\":\"example\"}");
        }
    }
}
