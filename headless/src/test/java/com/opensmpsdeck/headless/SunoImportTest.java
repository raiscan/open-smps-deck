package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class SunoImportTest {
    @TempDir Path root;

    @Test void reportsMissingInputsThroughTheCli() throws Exception {
        var folder = Files.createDirectory(root.resolve("empty"));
        var work = root.resolve("report");
        var out = new ByteArrayOutputStream();
        int exit = Main.run(new String[]{"reconstruct", "import", folder.toString(), work.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(2, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("✗"));
        assertTrue(Files.readString(work.resolve("import.json")).contains("missing_mix_and_midi"));
    }

    @Test void separatesAndImportsAudioWithoutMidi() throws Exception {
        Path mix = root.resolve("original mix.wav");
        wav(mix, 8000, 1, 1, t -> tone(t, 0));
        Path script = root.resolve("mock separator.sh");
        Files.writeString(script, """
                set -eu
                test "$3" = rocm
                mkdir "$2/stems"
                cp "$1" "$2/mix.wav"
                for stem in bass drums other vocals guitar piano; do
                    cp "$1" "$2/stems/$stem.wav"
                done
                cat > "$2/separation.json" <<'JSON'
                {"tool":"mock","model":"mock","version":"1",
                 "checkpointSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                 "stemOrder":["bass","drums","other","vocals","guitar","piano"],"settings":{}}
                JSON
                """);
        Path config = root.resolve("separators.json");
        Files.writeString(config, new com.google.gson.Gson().toJson(Map.of("mock",
                List.of("sh", script.toString(), "{input}", "{outputDir}", "{device}"))));
        Path separated = root.resolve("separated audio");
        var messages = new ByteArrayOutputStream();
        var output = new PrintStream(messages, true, StandardCharsets.UTF_8);
        assertEquals(0, Main.run(new String[]{"reconstruct", "separate", mix.toString(), separated.toString(),
                "--separator", "mock", "--separator-config", config.toString(), "--device", "rocm"}, output, output),
                () -> messages.toString(StandardCharsets.UTF_8));
        Path work = root.resolve("import work");
        assertEquals(0, Main.run(new String[]{"reconstruct", "import", separated.toString(), work.toString(),
                "--run"}, output, output), () -> messages.toString(StandardCharsets.UTF_8));
        var report = JsonParser.parseString(Files.readString(work.resolve("import.json"))).getAsJsonObject();
        assertEquals("warning", report.get("status").getAsString());
        assertFalse(report.has("runRequest"));
        var families = new java.util.TreeMap<String, String>();
        for (var value : report.getAsJsonArray("files")) {
            var file = value.getAsJsonObject();
            if (!file.get("role").getAsString().equals("stem")) continue;
            families.put(file.get("file").getAsString(), file.get("family").getAsString());
            if (file.get("family").getAsString().equals("vocals"))
                assertTrue(file.getAsJsonObject("evidence").get("tonalLeadCandidate").getAsBoolean());
        }
        assertEquals(Map.of("stems/bass.wav", "bass", "stems/drums.wav", "drums",
                "stems/other.wav", "other", "stems/vocals.wav", "vocals",
                "stems/guitar.wav", "guitar", "stems/piano.wav", "keyboard"), families);
        assertTrue(messages.toString(StandardCharsets.UTF_8).contains("no MIDI: needs audio-only transcription"));
    }

    @Test void audioCanOverruleStemLabelsAndManifestCanIdentifyOpaqueNames() throws Exception {
        Path source = fixture();
        Path stemDir = Files.createDirectory(source.resolve("Stems"));
        Files.writeString(stemDir.resolve("stems.json"), """
                {"take1":{"synth":"opaque-file"},"take2_not_downloaded":{"vocals":"not-present"}}
                """);
        wav(stemDir.resolve("opaque-file.wav"), 16000, 1, 4.5, t -> tone(t, 0));
        wav(source.resolve("Title (Bass).wav"), 8000, 1, 4.5, t -> tone(t, 1));
        wav(source.resolve("Title (Vocals).wav"), 8000, 1, 4.5, t -> 0);

        var report = inspect(source);
        assertTrue(report.canRun(), report::toString);
        assertEquals(1, file(report, "Title (Bass).wav").part());
        assertEquals("audio", file(report, "Title (Bass).wav").decidedBy());
        assertEquals(0, file(report, "Stems/opaque-file.wav").part());
        assertEquals("synth", file(report, "Stems/opaque-file.wav").family());
        assertEquals("Stems/stems.json", file(report, "Stems/opaque-file.wav").evidence().get("metadata"));
        assertTrue(file(report, "Stems/opaque-file.wav").issues().stream()
                .anyMatch(issue -> issue.code().equals("audio_format_reconciled")));
        assertTrue(file(report, "Title (Vocals).wav").issues().stream().anyMatch(issue -> issue.code().equals("vocals_present")));
        assertNull(file(report, "Title (Vocals).wav").part());
        assertEquals(1, report.leadPart());
        assertTrue(report.runArguments().contains("1=" + source.resolve("Title (Bass).wav")));
        assertTrue(report.runCommand().contains("'1=" + source.resolve("Title (Bass).wav") + "'"));
        assertEquals(report.json(), JsonParser.parseString(Files.readString(root.resolve("work/import.json"))));
        assertEquals(ReconstructionCli.audioHash(source.resolve("Title (Bass).wav")), file(report, "Title (Bass).wav").sha256());
    }

    @Test void prefersASynthLabelWhenTheKeyboardAudioScoresSlightlyHigher() throws Exception {
        Path source = fixture();
        wav(source.resolve("keyboard.wav"), 8000, 1, 4.5, t -> tone(t - .003, 1));
        wav(source.resolve("synth.wav"), 8000, 1, 4.5, t -> tone(t, 1));

        var report = inspect(source);
        var keyboard = file(report, "keyboard.wav");
        var synth = file(report, "synth.wav");
        double gap = audioScore(keyboard, 1) - audioScore(synth, 1);
        assertTrue(gap > 0 && gap < .05, () -> "Audio gap " + gap);
        assertEquals(1, synth.part());
        assertNull(keyboard.part());
    }

    @Test void reportsASecondStemCorrelatingWithTheSamePartAndKeepsOneReference() throws Exception {
        Path source = fixture();
        wav(source.resolve("keyboard.wav"), 8000, 1, 4.5, t -> tone(t, 1) * .5);
        wav(source.resolve("synth.wav"), 8000, 1, 4.5, t -> tone(t, 1));

        var report = inspect(source);
        assertEquals(Map.of(1, source.resolve("synth.wav").toString()), report.runRequest().inputs().stems());
        var keyboard = file(report, "keyboard.wav");
        assertNull(keyboard.part());
        assertTrue(keyboard.issues().stream().anyMatch(issue -> issue.code().equals("also_correlates_with_part")));
        var out = new ByteArrayOutputStream();
        report.print(new PrintStream(out, true, StandardCharsets.UTF_8));
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("Winning score"));
        assertTrue(printed.contains("Runner-up stem keyboard.wav"));
        assertTrue(printed.contains("--assign keyboard.wav=1"));
    }

    @Test void neverSuggestsADrumStemAsAMelodicReference() throws Exception {
        Path source = fixture();
        wav(source.resolve("drums.wav"), 8000, 1, 4.5, t -> tone(t, 1) * .5);
        wav(source.resolve("synth.wav"), 8000, 1, 4.5, t -> tone(t, 1));

        var report = inspect(source);
        assertEquals(1, file(report, "synth.wav").part());
        var drums = file(report, "drums.wav");
        assertTrue(audioScore(drums, 1) >= .30);
        assertNull(drums.part());
        assertFalse(drums.evidence().containsKey("sharedPart"));
    }

    private static double audioScore(SunoImport.FileResult file, int part) {
        return ((List<?>) file.evidence().get("candidates")).stream()
                .map(SunoImport.Candidate.class::cast).filter(candidate -> candidate.part() == part)
                .findFirst().orElseThrow().audioScore();
    }

    @Test void manualAssignmentAndExclusionTakePriority() throws Exception {
        Path source = fixture();
        wav(source.resolve("bass.wav"), 8000, 1, 4.5, t -> tone(t, 0));
        Files.writeString(source.resolve("vocals.wav"), "deliberately invalid");
        var report = SunoImport.inspect(source, root.resolve("work"), null, Map.of("bass.wav", 1),
                Set.of("vocals.wav"), (a, b, c) -> {});
        assertTrue(report.canRun(), report::toString);
        assertEquals("excluded", file(report, "vocals.wav").code());
        assertEquals("override", file(report, "bass.wav").decidedBy());
        assertEquals(1, file(report, "bass.wav").part());
        assertEquals(0, report.gaps().getFirst().part());
        assertTrue(report.gaps().getFirst().message().contains("use the mix"));
    }

    @Test void importQueuesOnlyUnmappedTonalStems() throws Exception {
        Path source = fixture();
        for (String family : List.of("bass", "synth", "keyboard", "vocals", "fx", "percussion"))
            wav(source.resolve(family + ".wav"), 16000, 1, 4.5,
                    t -> family.equals("bass") ? tone(t, 0) : tone(t, 1));
        var assign = Map.of("bass.wav", 0, "synth.wav", 1);
        var report = SunoImport.inspect(source, root.resolve("work"), null, assign, Set.of(),
                "fixture", (a, b, c) -> {});
        assertTrue(report.canRun(), report::toString);
        assertEquals("stem_without_part", file(report, "keyboard.wav").code());
        assertEquals(Map.of(source.resolve("keyboard.wav").toString(), "keyboard"),
                report.runRequest().options().get("transcribeUnmapped"));
        assertTrue(report.runArguments().contains("keyboard=" + source.resolve("keyboard.wav")));
    }

    @Test void detectsShiftAgainstTheWaveformSum() throws Exception {
        Path source = fixture();
        wav(source.resolve("bass.wav"), 8000, 1, 4.5, t -> tone(t - .15, 0));
        wav(source.resolve("synth.wav"), 8000, 1, 4.5, t -> tone(t - .15, 1));
        var report = inspect(source);
        assertEquals(SunoImport.Status.error, report.status());
        assertEquals("start_offset", file(report, "mix.wav").code());
        assertEquals(.15, report.alignment().offsetSeconds(), .001);
        assertFalse(report.canRun());
    }

    @Test void reportsTruncationDurationAndUnsupportedChannelCount() throws Exception {
        Path source = fixture();
        Path midi = source.resolve("suno.mid");
        byte[] data = Files.readAllBytes(midi);
        Files.write(midi, Arrays.copyOf(data, data.length - 1));
        Path broken = source.resolve("bass.wav");
        wav(broken, 8000, 1, 4.5, t -> tone(t, 0));
        data = Files.readAllBytes(broken);
        Files.write(broken, Arrays.copyOf(data, data.length - 1));
        wav(source.resolve("synth.wav"), 8000, 3, 4.5, t -> tone(t, 1));
        wav(source.resolve("brass.wav"), 8000, 1, 1, t -> tone(t, 0));

        var report = inspect(source);
        assertEquals("invalid_midi", file(report, "suno.mid").code());
        assertEquals("invalid_wav", file(report, "bass.wav").code());
        assertEquals("unsupported_audio_format", file(report, "synth.wav").code());
        assertEquals("duration_mismatch", file(report, "brass.wav").code());
        assertFalse(report.canRun());
    }

    @Test void cliCanStartReconstructionWithCheckedInputs() throws Exception {
        Path source = fixture();
        wav(source.resolve("bass.wav"), 8000, 1, 4.5, t -> tone(t, 0));
        var patch = com.opensmpsdeck.project.DemoProject.create().voices().getFirst();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        Path catalogue = root.resolve("instruments.json");
        builder.build().save(catalogue);
        Path work = root.resolve("work");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = Main.run(new String[]{"reconstruct", "import", source.toString(), work.toString(),
                        "--catalogue", catalogue.toString(), "--assign", "bass.wav=0", "--run"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, exit, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(work.resolve("reconstruction/arrangement.smps.json")));
        var manifest = Files.readString(work.resolve("reconstruction/manifest.json"));
        assertTrue(manifest.contains("bass.wav"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Finished assemble"));
    }

    @Test void ignoresLinksAndRefusesWritingIntoSource() throws Exception {
        Path source = fixture();
        Path target = Files.writeString(root.resolve("outside.wav"), "outside the download");
        Files.createSymbolicLink(source.resolve("vocals.wav"), target);
        var report = inspect(source);
        assertEquals("ignored_non_file", file(report, "vocals.wav").code());
        assertNull(file(report, "vocals.wav").sha256());
        assertThrows(IllegalArgumentException.class, () ->
                SunoImport.inspect(source, source.resolve("output"), null, Map.of(), Set.of(), (a, b, c) -> {}));
        assertFalse(Files.exists(source.resolve("output")));
    }

    private Path fixture() throws Exception {
        var source = Files.createDirectory(root.resolve("download"));
        midi(source.resolve("suno.mid"));
        wav(source.resolve("mix.wav"), 8000, 1, 4.5, t -> tone(t, 0) + tone(t, 1));
        return source;
    }

    private SunoImport.Report inspect(Path source) throws Exception {
        return SunoImport.inspect(source, root.resolve("work"), null, Map.of(), Set.of(), (a, b, c) -> {});
    }

    private static SunoImport.FileResult file(SunoImport.Report report, String name) {
        return report.files().stream().filter(file -> file.file().equals(name)).findFirst().orElseThrow();
    }

    private static void midi(Path file) throws Exception {
        var sequence = new Sequence(Sequence.PPQ, 480);
        int[] programs = {33, 81};
        for (int part = 0; part < programs.length; part++) {
            var track = sequence.createTrack();
            track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, part, programs[part], 0), 0));
            for (int i = 0; i < 4; i++) {
                int pitch = (part == 0 ? 45 : 69) + i;
                int tick = (part == 0 ? 120 : 600) + 960 * i;
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, part, pitch, 96), tick));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, part, pitch, 0), tick + 300));
            }
        }
        MidiSystem.write(sequence, 1, file.toFile());
    }

    private static double tone(double time, int part) {
        time -= part == 0 ? .125 : .625;
        int note = (int) Math.floor(time);
        double elapsed = time - note;
        if (note < 0 || note >= 4 || elapsed >= .3125) return 0;
        int pitch = (part == 0 ? 45 : 69) + note;
        double hz = 440 * Math.pow(2, (pitch - 69) / 12.0);
        return .35 * Math.sin(Math.TAU * hz * elapsed) * Math.min(1, Math.min(elapsed, .3125 - elapsed) / .008);
    }

    private static void wav(Path file, int rate, int channels, double seconds, DoubleUnaryOperator wave) throws Exception {
        int frames = (int) (rate * seconds);
        int bytes = frames * 2 * channels;
        var data = ByteBuffer.allocate(bytes + 44).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bytes + 36);
        data.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16);
        data.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) (32767 * wave.applyAsDouble(frame / (double) rate));
            for (int channel = 0; channel < channels; channel++) data.putShort(sample);
        }
        Files.write(file, data.array());
    }
}
