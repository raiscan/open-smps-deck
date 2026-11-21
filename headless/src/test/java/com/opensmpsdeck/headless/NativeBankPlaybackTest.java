package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.EnvelopeProgram;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class NativeBankPlaybackTest {
    @TempDir Path directory;
    private static final int HEADER = 0x20;
    private static final int BASE = 0x8000;
    private static final int PREVIEW = 16;
    private static final Voice VOICE = new Voice("voice", "Original voice", List.of(
            7, 1, 2, 3, 4, 31, 31, 31, 31, 0, 0, 0, 0, 0, 0, 0, 0,
            15, 15, 15, 15, 0x9C, 0xA4, 0xAC, 0xB4));

    @Test void exportIncludesNativeLoopAndRepeatsTheRequestedNumberOfPasses() throws Exception {
        var project = restoreFixture(new int[]{0xF2}, new int[]{0xEF, 0, 0xA1, 2, 0xA5, 2, 0xF6, 0x42, 0x80});
        var playback = NativeExportLength.measure(ProjectCompiler.compileWithAssets(project), 1);
        assertNotNull(playback.loop());
        assertEquals(4, playback.loop().period());
        var vgm = directory.resolve("loop.vgm");
        ProjectExport.write(project, null, vgm, new ProjectExport.Options(ProjectExport.Format.VGM,
                null, null, null, null, false, false), (done, total, message) -> {});
        var info = VgmFile.read(vgm, (at, command, register, value) -> {});
        assertTrue(info.loopSample() >= 0 && info.loopSample() < info.samples());
        var wav = directory.resolve("three-loops.wav");
        ProjectExport.write(project, null, wav, new ProjectExport.Options(ProjectExport.Format.WAV,
                null, null, null, 3, false, false), (done, total, message) -> {});
        assertEquals(44 + (playback.loop().start() + 12) * 3200L, Files.size(wav));
    }

    @Test void preservesPreheaderCallsCountedLoopHeaderStateAndTempoChangesInPlayback() throws Exception {
        var normal = observe(ProjectCompiler.compileWithAssets(project(0x80)));
        var unweighted = observe(ProjectCompiler.compileWithAssets(project(0)));
        assertEquals(List.of(0, 2, 4), unweighted.fm1Attacks());
        assertEquals(3, normal.fm1Attacks().size());
        assertEquals(0, normal.fm1Attacks().getFirst());
        assertTrue(normal.fm1Attacks().getLast() > unweighted.fm1Attacks().getLast());
        assertTrue(normal.hasAudio());
        assertTrue(normal.writes().stream().anyMatch(write -> write.register() == 0x40 && write.value() == 0x21),
                "The global voice carrier TL must incorporate header attenuation");
        assertTrue(normal.writes().stream().anyMatch(write -> write.register() == 0xB4 && write.value() == 0x80),
                "The preheader subroutine must execute");
    }

    @Test void declaredSeventhFmSlotWithPsgDoesNotProduceFm6Attacks() throws Exception {
        byte[] bank = new byte[0x64];
        put(bank, HEADER, 0, 0, 7, 1, 1, 0);
        for (int index = 0; index < 6; index++) put(bank, HEADER + 6 + index * 4, 0x55, 0x80, 0, 0);
        put(bank, HEADER + 30, 0x59, 0x80, 0, 0);
        put(bank, HEADER + 34, 0x56, 0x80, 0, 0, 0, 0);
        put(bank, 0x55, 0xF2, 0x91, 2, 0xF2, 0xEF, 0, 0xBC, 2, 0xF2);
        var source = new StudioProject(5, "Inactive FM header", "S3K", 24, 0,
                List.of(VOICE), List.of(), List.of(), List.of(), List.of(),
                NativeProgram.of(BASE, HEADER, 0, PREVIEW, bank, List.of(), List.of()));
        var compiled = ProjectCompiler.compileWithAssets(source);
        var observation = observe(compiled);
        assertTrue(observation.hasAudio(), "PSG root must survive the extra FM declaration");
        assertTrue(observation.writes().stream().noneMatch(write ->
                write.register() == 0x28 && (write.value() & 0xF7) == 0xF6));
        assertArrayEquals(bank, S3kBinary.validateAndCopy(compiled));
    }

    @Test void unusedChannelCommandsPreserveStandalonePlayback() throws Exception {
        byte[] bank = new byte[0x60];
        put(bank, HEADER, 0, 0, 2, 1, 1, 0,
                0x40, 0x80, 0, 0, 0x41, 0x80, 0, 0, 0x4A, 0x80, 0, 0, 0, 0);
        put(bank, 0x40, 0xF2, 0xF5, 0xFF, 0xE2, 1, 0xEF, 0, 0xB1, 6, 0xF2,
                0xE5, 1, 9, 0xE6, 3, 0x91, 6, 0xF2);
        var program = NativeProgram.of(BASE, HEADER, 0, PREVIEW, bank, List.of(), List.of());
        var withCommands = observe(new CompiledProject(bank, List.of(), List.of(), program, List.of(VOICE)));
        bank[HEADER + 10] = 0x45;
        bank[HEADER + 14] = 0x4F;
        var withoutCommands = observe(new CompiledProject(bank, List.of(), List.of(), program.withBank(bank), List.of(VOICE)));

        assertTrue(withCommands.hasAudio());
        assertEquals(withoutCommands, withCommands);
    }

    @Test void packagesExactBankAndAllBindingsAndCliUsesNativePreview() throws Exception {
        StudioProject project = project(0x80);
        Path source = directory.resolve("native.json");
        Files.writeString(source, ProjectFormat.write(project));
        Path archive = directory.resolve("native.smps.zip");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(stdout);
        PrintStream err = new PrintStream(stderr);
        assertEquals(0, Main.run(new String[]{"inspect-project", source.toString()}, out, err), stderr.toString());
        String inspection = stdout.toString();
        assertTrue(inspection.contains("8008") && inspection.contains("F8 08 80") && inspection.contains("FF 00 80"));
        assertEquals(2, Main.run(new String[]{"compile-project", source.toString(), directory.resolve("bare.bin").toString()}, out, err));
        assertEquals(0, Main.run(new String[]{"compile-package", source.toString(), archive.toString()}, out, err), stderr.toString());
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            byte[] track = read(zip, "track.bin");
            assertArrayEquals(project.nativeProgram().bank(), track);
            var manifest = JsonParser.parseString(new String(read(zip, "manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(3, manifest.get("schemaVersion").getAsInt());
            assertEquals(BASE, manifest.get("bankBase").getAsInt());
            assertEquals(HEADER, manifest.get("headerOffset").getAsInt());
            assertEquals(0, manifest.get("localVoiceCount").getAsInt());
            assertEquals(PREVIEW, manifest.get("previewFrames").getAsInt());
            var global = manifest.getAsJsonObject("globalVoices");
            assertEquals(25, read(zip, global.get("path").getAsString()).length);
            assertEquals("voice", global.getAsJsonArray("voices").get(0).getAsJsonObject().get("id").getAsString());
            var envelopeSlots = manifest.getAsJsonArray("envelopeBindings");
            assertEquals(2, envelopeSlots.size());
            assertEquals(23, envelopeSlots.get(1).getAsJsonObject().get("slot").getAsInt());
            var dac = manifest.getAsJsonObject("dac");
            assertEquals(2, dac.getAsJsonArray("samples").size());
            var dacBindings = dac.getAsJsonArray("bindings");
            assertEquals(0x86, dacBindings.get(0).getAsJsonObject().get("note").getAsInt());
            assertEquals(0x89, dacBindings.get(1).getAsJsonObject().get("note").getAsInt());
            assertEquals(0, dacBindings.get(1).getAsJsonObject().get("sampleId").getAsInt());
            assertEquals(3, dacBindings.get(1).getAsJsonObject().get("rate").getAsInt());
            assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0xA0}, read(zip, "dac/000.pcm"));
        }
        Path wav = directory.resolve("native.wav");
        assertEquals(0, Main.run(new String[]{"render-project", source.toString(), wav.toString()}, out, err), stderr.toString());
        assertEquals(44L + PREVIEW * 3200L, Files.size(wav));
        Path shortWav = directory.resolve("short.wav");
        assertEquals(0, Main.run(new String[]{"render-project", source.toString(), shortWav.toString(), "--frames", "2"}, out, err));
        assertEquals(44L + 2 * 3200L, Files.size(shortWav));
    }

    @Test void rendersRestoreCueWithExplicitPreviousProjectWithoutIncludingWarmup() throws Exception {
        var previous = previousMusicProject();
        var cue = restoreCueProject();
        Path previousPath = directory.resolve("previous.json");
        Path cuePath = directory.resolve("cue.json");
        Path wav = directory.resolve("cue.wav");
        Files.writeString(previousPath, ProjectFormat.write(previous));
        Files.writeString(cuePath, ProjectFormat.write(cue));
        var inspection = new ByteArrayOutputStream();
        try (var out = new PrintStream(inspection)) {
            assertEquals(0, Main.run(new String[]{"inspect-project", cuePath.toString()}, out, out));
        }
        assertTrue(inspection.toString().contains("Restore previous song"), inspection::toString);
        assertTrue(inspection.toString().contains("Playback requires"), inspection::toString);
        var errors = new ByteArrayOutputStream();
        var err = new PrintStream(errors);
        assertEquals(2, Main.run(new String[]{"render-project", cuePath.toString(), wav.toString()}, err, err));
        assertTrue(errors.toString().contains("requires session context"));
        assertFalse(Files.exists(wav));

        errors.reset();
        assertEquals(2, Main.run(new String[]{"render-project", cuePath.toString(), wav.toString(),
                "--previous-project", previousPath.toString()}, err, err));
        assertTrue(errors.toString().contains("must be used together"));
        assertFalse(Files.exists(wav));

        errors.reset();
        assertEquals(0, Main.run(new String[]{"render-project", cuePath.toString(), wav.toString(),
                "--previous-project", previousPath.toString(), "--previous-frames", "1"}, err, err), errors::toString);
        assertEquals(44L + PREVIEW * 3200L, Files.size(wav));
    }

    @Test void restoreDefersToNextPresentationAndResumesSavedCursorWithAttenuatedVoice() throws Exception {
        var previous = ProjectCompiler.compileWithAssets(previousMusicProject());
        var cue = ProjectCompiler.compileWithAssets(restoreCueProject());
        var uninterrupted = observe(previous);
        var resumed = observe((sink, observer) ->
                S3kRenderer.renderWithPreviousMusic(previous, 1, cue, PREVIEW, sink, observer));

        var firstNote = uninterrupted.fm1FrequencyWrites(0);
        var secondNote = uninterrupted.fm1FrequencyWrites(1);
        assertFalse(secondNote.isEmpty());
        assertNotEquals(firstNote, secondNote);
        assertTrue(resumed.fm1FrequencyWrites(0).isEmpty(), "Warm-up writes must be excluded");
        assertTrue(resumed.fm1FrequencyWrites(1).isEmpty());
        assertTrue(resumed.fm1FrequencyWrites(2).isEmpty(), "The restore flag only queues the request");
        assertEquals(secondNote, resumed.fm1FrequencyWrites(3), "Resume at the saved cursor");
        assertTrue(resumed.writes().contains(new Write(3, 0x40, 0x61)),
                "The restored voice includes the 0x40 attenuation");
        assertTrue(resumed.hasAudio());
    }

    private static StudioProject previousMusicProject() {
        return restoreFixture(new int[]{0xF2}, new int[]{0xEF, 0, 0xA1, 1, 0xB1, 16, 0xF2});
    }

    private static StudioProject restoreCueProject() {
        return restoreFixture(new int[]{0x80, 2, 0xE2, 0xFF, 0xF2}, new int[]{0xF2});
    }

    private static StudioProject restoreFixture(int[] dac, int[] fm1) {
        byte[] bank = new byte[0x50];
        put(bank, HEADER, 0, 0, 2, 0, 1, 0, 0x30, 0x80, 0, 0, 0x40, 0x80, 0, 5);
        put(bank, 0x30, dac);
        put(bank, 0x40, fm1);
        return new StudioProject(5, "Restore fixture", "S3K", 24, 0, List.of(VOICE), List.of(), List.of(),
                List.of(), List.of(), NativeProgram.of(BASE, HEADER, 0, PREVIEW, bank, List.of(), List.of()));
    }

    @Test void preservesAnUnsupportedNativeBankWithoutPublishingAudio() throws Exception {
        StudioProject original = project(0x80);
        byte[] bytes = original.nativeProgram().bank();
        put(bytes, 0x30, 0xFF, 1, 0x55, 0xF2);
        // Move the FM root away from the DAC command's operand and stop.
        put(bytes, HEADER + 10, 0x40, 0x80, 1, 5);
        put(bytes, 0x40, 0xF2);
        var source = new StudioProject(5, original.name(), "S3K", 24, 0, original.voices(),
                List.of(), List.of(), original.envelopes(), original.samples(),
                original.nativeProgram().withBank(bytes));
        Path packagePath = directory.resolve("archived.zip");
        ProjectPackage.write(source, packagePath);
        try (var zip = new ZipFile(packagePath.toFile())) {
            assertArrayEquals(bytes, read(zip, "track.bin"));
        }
        Path wave = directory.resolve("unsupported.wav");
        var compiled = ProjectCompiler.compileWithAssets(source);
        assertThrows(IllegalArgumentException.class, () -> WaveFile.render(compiled, wave, PREVIEW));
        assertFalse(Files.exists(wave));
    }

    @Test void archivesRawModulationAssetsOutsidePlaybackProfile() throws Exception {
        StudioProject original = project(0x80);
        var raw = new Envelope("negative", "Raw modulation", "MODULATION", List.of(1, 0xFF, 0x82, 2));
        var program = NativeProgram.of(BASE, HEADER, 0, PREVIEW, original.nativeProgram().bank(),
                List.of(new NativeProgram.EnvelopeSlot("negative", 11)), List.of());
        var project = new StudioProject(5, original.name(), "S3K", 24, 0, original.voices(),
                List.of(), List.of(), List.of(raw), List.of(), program);
        Path archive = directory.resolve("raw-envelope.zip");
        ProjectPackage.write(project, archive);
        try (var zip = new ZipFile(archive.toFile())) {
            assertArrayEquals(new byte[]{1, (byte) 0xFF, (byte) 0x82, 2}, read(zip, "mod/asset-001.bin"));
        }
        Path wave = directory.resolve("raw-envelope.wav");
        var compiled = ProjectCompiler.compileWithAssets(project);
        assertFalse(NativeBankAnalysis.inspect(compiled).events().isEmpty());
        byte[] bytes = original.nativeProgram().bank();
        put(bytes, 0x30, 0xF2);
        put(bytes, 0x32, 0xF4, 11, 0xF2);
        var used = new CompiledProject(bytes, List.of(raw), List.of(), program.withBank(bytes), original.voices());
        var error = assertThrows(IllegalArgumentException.class, () -> WaveFile.render(used, wave, PREVIEW));
        assertTrue(error.getMessage().contains("modulation envelope 11: Envelope has a zero-time control cycle"), error::getMessage);
        assertFalse(Files.exists(wave));
        put(bytes, 0x34, 0xFA, 0xF2);
        var safe = new Envelope("negative", "Valid modulation", "MODULATION", List.of(1, 0xFF, 0x82, 0));
        var active = new CompiledProject(bytes, List.of(safe), List.of(), program.withBank(bytes), original.voices());
        assertTrue(NativeBankAnalysis.inspect(active).events().stream()
                .anyMatch(event -> event.meaning().equals("Disable custom modulation")));
    }

    @Test void rejectsPsgControlsAfterARestThatAHeldEnvelopeSwitchCouldReach() {
        assertThrows(IllegalArgumentException.class, () -> EnvelopeProgram.validateNativePlayback(
                Envelope.PSG_VOLUME, List.of(0x81, 0x82, 1)));
        assertDoesNotThrow(() -> EnvelopeProgram.validateNativePlayback(
                Envelope.PSG_VOLUME, List.of(0x30, 0x80)));
    }

    private static byte[] read(ZipFile zip, String path) throws Exception {
        try (var stream = zip.getInputStream(zip.getEntry(path))) {
            return stream.readAllBytes();
        }
    }

    private static StudioProject project(int tempoChange) {
        byte[] bank = new byte[0x50];
        put(bank, 8, 0xE0, 0x80, 0xB1, 2, 0xF9);
        put(bank, HEADER, 0, 0, 2, 0, 1, 0,
                0x30, 0x80, 0, 0, 0x32, 0x80, 1, 5);
        put(bank, 0x30, 0xF2);
        put(bank, 0x32, 0xEF, 0, 0xF8, 8, 0x80, 0xFF, 0, tempoChange,
                0xF7, 0, 2, 0x34, 0x80, 0xB5, 2, 0xF2);
        var psg = new Envelope("volume", "Original envelope", "PSG_VOLUME", List.of(0, 2, 0x83));
        var sample = DacSample.of("sample", "Pulse", 1, new byte[]{(byte) 0x80, (byte) 0xA0});
        var spare = DacSample.of("spare", "Spare pulse", 2, new byte[]{(byte) 0x80, (byte) 0x70});
        var nativeProgram = NativeProgram.of(BASE, HEADER, 0, PREVIEW, bank,
                List.of(new NativeProgram.EnvelopeSlot("volume", 17), new NativeProgram.EnvelopeSlot("volume", 23)),
                List.of(new NativeProgram.DacSlot(0x86, "sample", 1), new NativeProgram.DacSlot(0x89, "sample", 3)));
        return new StudioProject(5, "Original bank", "S3K", 24, 0, List.of(VOICE), List.of(), List.of(),
                List.of(psg), List.of(sample, spare), nativeProgram);
    }

    private static void put(byte[] bank, int offset, int... values) {
        for (int value : values) bank[offset++] = (byte) value;
    }

    private static Observation observe(CompiledProject project) throws Exception {
        return observe((sink, observer) -> S3kRenderer.render(project, PREVIEW, sink, observer));
    }

    @FunctionalInterface
    private interface ObservedRender {
        void render(S3kRenderer.PcmSink sink, ChipWriteObserver observer) throws java.io.IOException;
    }

    private static Observation observe(ObservedRender render) throws Exception {
        var writes = new ArrayList<Write>();
        var pcmFrames = new ArrayList<Integer>();
        int[] frame = {0};
        boolean[] hasAudio = {false};
        render.render(pcm -> {
            pcmFrames.add(Arrays.hashCode(pcm));
            for (short sample : pcm) if (sample != 0) hasAudio[0] = true;
            frame[0]++;
        }, new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.add(new Write(frame[0], register, value));
            }
            @Override public void onPsgWrite(int value) {
                writes.add(new Write(frame[0], -1, value));
            }
        });
        return new Observation(writes, pcmFrames, hasAudio[0]);
    }

    private record Write(int frame, int register, int value) {}
    private record Observation(List<Write> writes, List<Integer> pcmFrames, boolean hasAudio) {
        List<Integer> fm1FrequencyWrites(int frame) {
            return writes.stream().filter(write -> write.frame() == frame &&
                            (write.register() == 0xA0 || write.register() == 0xA4))
                    .map(Write::value).toList();
        }

        List<Integer> fm1Attacks() {
            return writes.stream().filter(write -> write.register() == 0x28 && write.value() == 0xF0)
                    .map(Write::frame).toList();
        }
    }
}
