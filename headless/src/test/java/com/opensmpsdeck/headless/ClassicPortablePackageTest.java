package com.opensmpsdeck.headless;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ClassicPortablePackageTest {
    private static final byte[] S1_VOICE = bytes("07 01 02 03 04 1f 1e 1d 1c 00 01 02 03 04 03 02 01 0f 1f 2f 3f 81 82 83 84");
    private static final byte[] S2_VOICE = bytes("07 01 03 02 04 1f 1d 1e 1c 00 02 01 03 04 02 03 01 0f 2f 1f 3f 81 83 82 84");
    private static final byte[] PSG = bytes("01 02 80");
    private static final byte[] PCM = bytes("80 90 82 7d 7a 80");

    @TempDir Path directory;

    @Test void portableExportDefaultsToTheProjectTargetAndHonorsAnExplicitDriver() throws Exception {
        Path source = directory.resolve("target.smps.json");
        Files.writeString(source, ProjectFormat.write(DemoProject.create().withTarget(TargetGame.S2)));
        Path portable = directory.resolve("target.smps.zip");
        var errors = new java.io.ByteArrayOutputStream();
        try (var err = new java.io.PrintStream(errors)) {
            assertEquals(0, Main.run(new String[]{"export", source.toString(), portable.toString(),
                    "--format", "portable"}, System.out, err), errors.toString());
            assertEquals("s2", PortablePackages.inspect(portable).get("driver").getAsString());
            Path override = directory.resolve("override.smps.zip");
            assertEquals(0, Main.run(new String[]{"export", source.toString(), override.toString(),
                    "--format", "portable", "--driver", "s1"}, System.out, err), errors.toString());
            assertEquals("s1", PortablePackages.inspect(override).get("driver").getAsString());
        }
    }

    @Test void allTargetsReportTypedBankOverflow() {
        var demo = DemoProject.create();
        int duration = 127 * 40_000;
        var voice = demo.voices().getFirst();
        var sequence = new Sequence("s", "Held note", "FM", duration, voice.id(), 100,
                List.of(new NoteEvent("held", 0, duration, 60)));
        var project = new StudioProject(2, "Oversize", "S3K", 24, 0, List.of(voice), List.of(sequence),
                List.of(new Clip("clip", sequence.id(), "FM1", 0, duration)));
        for (var game : TargetGame.values()) {
            var failure = assertThrows(ProjectCompiler.BankLimitException.class,
                    () -> ReconstructionCli.compileTarget(project.withTarget(game)), game.id());
            assertTrue(failure.compiledBytes() > failure.limitBytes());
            assertEquals(32_768, failure.limitBytes());
        }
    }

    @Test void testInGameDefaultsToProjectTargetAndAcceptsAnExplicitDriver() {
        var demo = DemoProject.create();
        assertEquals(RomGame.S3K, TestInGame.game(demo));
        assertEquals(RomGame.S1, TestInGame.game(demo.withTarget(TargetGame.S1)));
        var project = demo.withTarget(TargetGame.S2);
        assertEquals(RomGame.S2, RomPatchInput.compile(project, null).game());
        assertEquals(RomGame.S3K, RomPatchInput.compile(project, RomGame.S3K).game());
    }

    @Test void targetAssemblyNamesTheUnsupportedEnvelope() {
        var base = DemoProject.create().withTarget(TargetGame.S2);
        var project = new StudioProject(3, base.name(), base.mode(), base.ticksPerBeat(), base.tempo(),
                base.voices(), base.sequences(), base.clips(),
                List.of(new Envelope("psg-closed-cymbal", "Cymbal", Envelope.PSG_VOLUME, List.of(0, 0x83))))
                .withTarget(base.target());
        var error = assertThrows(IllegalArgumentException.class, () -> ReconstructionCli.compileTarget(project));
        assertTrue(error.getMessage().contains("s2: PSG envelope 'psg-closed-cymbal' uses S3K control byte 83"),
                error::getMessage);
    }

    @Test void rendersConvertedTempoAndEquivalentFmVoiceLoads() throws Exception {
        for (int tempo : List.of(64, 31)) {
            var project = timingProject(tempo);
            var s3k = render(project, S3kPackageDriver.INSTANCE);
            var reference = noteOns(s3k, false);
            assertTrue(reference.size() > 400);
            assertEquals(reference, noteOns(s3k, true));
            var voices = operatorWrites(s3k);
            assertEquals(3 * 24, voices.size());
            assertEquals(3, voices.stream().filter(write -> write.register() == 0x30)
                    .map(Write::value).distinct().count());

            for (var driver : ClassicPackageDriver.values()) {
                var observed = render(project, driver);
                assertEquals(voices, operatorWrites(observed), driver.id());
                var fm = noteOns(observed, false);
                assertEquals(fm, noteOns(observed, true), driver.id() + " PSG");
                if (tempo == 64) {
                    assertTrue(reference.equals(fm), driver.id() + " exact-phase note count " + fm.size());
                } else {
                    // At 0x1F S1 uses 8 (224/256), S2 uses 225 (225/256).
                    double sourceRate = 225 / 256.0;
                    double targetRate = driver == ClassicPackageDriver.S1 ? 224 / 256.0 : sourceRate;
                    assertTrue(Math.abs(reference.size() - fm.size())
                            <= 768 * Math.abs(sourceRate - targetRate) + 1);
                    for (int i = 0; i < Math.min(fm.size(), reference.size()); i++) {
                        // Each observed frame is quantized by the native tick phase.
                        double expectedDrift = (i + 1) * (1 / targetRate - 1 / sourceRate);
                        assertEquals(expectedDrift, fm.get(i) - reference.get(i), 2.0,
                                driver.id() + " note " + i);
                    }
                }
            }
        }
    }

    private static StudioProject timingProject(int tempo) {
        var voices = new ArrayList<Voice>();
        for (int i = 0; i < 3; i++) {
            byte[] voice = S1_VOICE.clone();
            for (int offset = 1; offset < 25; offset++) voice[offset] += i;
            // Use literal TL values for this zero-attenuation test. S1 may
            // expose the carrier marker on the bus, where YM2612 ignores it.
            for (int offset = 21; offset < 25; offset++) voice[offset] &= 0x7F;
            voices.add(new Voice("voice" + i, "Voice " + i, StockAssets.unsigned(voice)));
        }
        // One attack per tick and two explicit FM voice changes. Alternating
        // pitches distinguish PSG attacks from repeated writes of the same pitch.
        var notes = java.util.stream.IntStream.range(0, 768)
                .mapToObj(tick -> new NoteEvent("n" + tick, tick, 1, 60 + tick % 2)).toList();
        var fm = new Sequence("fm", "FM", "FM", 768, "voice0", 127, notes,
                List.of(new CommandEvent("v1", 160, NativeCommand.FM_VOICE, List.of(), "voice1"),
                        new CommandEvent("v2", 320, NativeCommand.FM_VOICE, List.of(), "voice2")), "RESET");
        var psg = new Sequence("psg", "PSG", "PSG", 768, null, 127, notes);
        return new StudioProject(4, "Tempo and voice fixture", "S3K", 24, tempo, voices, List.of(fm, psg),
                List.of(new Clip("fm-c", "fm", "FM1", 0, 768), new Clip("psg-c", "psg", "PSG1", 0, 768)));
    }

    private static Observation render(StudioProject project, PackageDriver driver) throws Exception {
        var options = new PortableOptions(driver.id(), false, null, null, null, List.of(), List.of());
        var payload = PortablePackageWriter.build(project, options);
        var compiled = PortablePackageReader.assemble(archive(payload), List.of());
        var observation = new Observation();
        if (driver instanceof ClassicPackageDriver classic)
            classic.render(compiled, 768, observation::capture, observation);
        else
            S3kRenderer.render(compiled, 768, observation::capture, observation);
        return observation;
    }

    private static List<Integer> noteOns(Observation observation, boolean psg) {
        var frames = new ArrayList<Integer>();
        int previousPitch = -1;
        for (var write : observation.writes) {
            if (psg && write.register() == -1 && (write.value() & 0xF0) == 0x80
                    && previousPitch != write.value()) {
                previousPitch = write.value();
                frames.add(write.frame());
            }
            if (!psg && write.register() == 0x28 && (write.value() & 0xF7) == 0xF0)
                frames.add(write.frame());
        }
        return frames;
    }

    private static List<Write> operatorWrites(Observation observation) {
        // Compare each upload by register address, independent of upload order.
        var uploads = new ArrayList<Write>();
        var registers = new java.util.TreeMap<Integer, Write>();
        int frame = -1;
        for (var write : observation.writes) {
            int register = write.register();
            if (write.port() != 0 || register < 0x30 || register > 0x8C || (register & 3) != 0)
                continue;
            if (write.frame() != frame) {
                if (registers.size() == 24) uploads.addAll(registers.values());
                registers.clear();
                frame = write.frame();
            }
            registers.put(register, new Write(0, 0, register, write.value()));
        }
        if (registers.size() == 24) uploads.addAll(registers.values());
        return uploads;
    }

    @Test void arrangementMatchesIndependentS1AndS2Programs() throws Exception {
        for (var driver : ClassicPackageDriver.values()) {
            var payload = PortablePackageWriter.build(project(NativeCommand.DETUNE, 60), options(driver));
            var assembled = PortablePackageReader.assemble(archive(payload), List.of());
            boolean s1 = driver == ClassicPackageDriver.S1;
            byte[] header = bytes(s1
                    ? "00 35 02 01 01 02 00 14 00 00 00 17 00 00 00 29 00 00 00 00"
                    : "35 00 02 01 01 40 14 00 00 00 17 00 00 00 29 00 00 00 00 00");
            byte[] tracks = bytes("""
                    81 06 f2
                    e6 06 ef 00 e0 c0 e1 00 e8 00 f4 b1 02 e1 01 e7 04 f2
                    ec 01 f5 01 e1 00 e8 00 f4 8d 04 f2
                    """);
            byte[] golden = java.nio.ByteBuffer.allocate(78).put(header).put(tracks)
                    .put(s1 ? S1_VOICE : S2_VOICE).array();
            assertEquals(List.of(0, 6, 10, 14), payload.manifest().bank().relocations());
            assertArrayEquals(golden, assembled.song(), driver.id());

            var actual = new Observation();
            driver.preflight(assembled);
            driver.render(assembled, 16, actual::capture, actual);
            var expected = new Observation();
            var dac = new DacData(Map.of(1, PCM), Map.of(129, new DacData.DacEntry(1, 6)), s1 ? 301 : 295);
            var data = s1 ? new Sonic1SmpsData(golden) : new Sonic2SmpsData(golden);
            if (data instanceof Sonic1SmpsData first) first.setPsgEnvelopes(new byte[][]{PSG});
            if (data instanceof Sonic2SmpsData second) second.setPsgEnvelopes(Map.of(1, PSG));
            S3kRenderer.renderPackageMusic(driver.id(), data, dac, driver.config, driver.policy, 16, expected::capture, expected);
            assertTrue(actual.audio.stream().anyMatch(frame -> {
                for (short sample : frame) if (sample != 0) return true;
                return false;
            }));
            assertTrue(actual.writes.stream().anyMatch(write -> write.register() == 0x30));
            assertTrue(actual.writes.stream().anyMatch(write -> write.register() == -1));
            assertEquals(expected.writes, actual.writes);
            for (int i = 0; i < 16; i++) assertArrayEquals(expected.audio.get(i), actual.audio.get(i));
        }
    }

    @Test void deterministicArchiveRendersAndModChecksSelectedMusicList() throws Exception {
        for (var driver : ClassicPackageDriver.values()) {
            String id = driver.id();
            var project = project(NativeCommand.DETUNE, 60);
            Path first = directory.resolve(id + ".smps.zip");
            Path second = directory.resolve(id + "-other.smps.zip");
            var payload = PortablePackageWriter.write(project, first, options(driver));
            PortablePackageWriter.write(project, second, options(driver));
            assertEquals(-1, Files.mismatch(first, second));
            assertEquals(driver == ClassicPackageDriver.S1 ? 2 : 48, PortablePackages.inspect(first).getAsJsonObject("driverOptions").get("speedUpTempo").getAsInt());
            var report = PortablePackages.validate(first, List.of(), directory.resolve(id + ".wav"), 16);
            assertTrue(report.get("playbackValidated").getAsBoolean(), report.toString());
            assertTrue(report.get("valid").getAsBoolean(), report.toString());
            Path jar = directory.resolve(id + ".jar");
            ModPackageWriter.write(payload, jar, List.of(129));
            try (var zip = new ZipFile(jar.toFile())) {
                var manifest = zip.getEntry("META-INF/openggf-mod.yaml");
                assertTrue(new String(zip.getInputStream(manifest).readAllBytes(), StandardCharsets.UTF_8)
                        .contains("baseGame: " + id + "\n"));
            }
            assertThrows(IllegalArgumentException.class, () -> ModPackageWriter.write(payload, jar, List.of(13)));
        }
    }

    @Test void stockVoiceEnvelopeAndDacResolveThroughSyntheticSources() throws Exception {
        for (var driver : ClassicPackageDriver.values()) {
            byte[] voiceBytes = driver == ClassicPackageDriver.S1 ? S1_VOICE : S2_VOICE;
            var fm = StockTable.ref(driver.id(), "local-fm", 129, 0, StockTable.hash(voiceBytes));
            var psg = StockTable.ref(driver.id(), "psg", null, 1, StockTable.hash(PSG));
            var dac = StockTable.ref(driver.id(), "dac", null, 129, StockTable.hash(PCM));
            var project = project(NativeCommand.DETUNE, 60);
            project = project.withAssets(List.of(project.voices().getFirst().withOrigin(
                            AssetOrigin.stock(fm))),
                    List.of(project.envelopes().getFirst().withOrigin(AssetOrigin.stock(psg))),
                    List.of(project.samples().getFirst().withOrigin(AssetOrigin.stock(dac))));
            var payload = PortablePackageWriter.build(project, options(driver));
            var symbols = Map.of(fm, voiceBytes, psg, PSG, dac, PCM);
            assertEquals(List.of(driver.id()), payload.manifest().requires().games());
            assertEquals(1, payload.manifest().bank().fills().size());
            assertFalse(payload.manifest().provenance().containsRomDerivedData());
            assertArrayEquals(PortablePackageWriter.build(project(NativeCommand.DETUNE, 60), options(driver))
                            .entries().get("track.bin"),
                    PortablePackageReader.assemble(archive(payload), List.of(symbols::get)).song());
            var source = ProjectFormat.read(new String(payload.entries().get("source.smps.json"), StandardCharsets.UTF_8));
            assertNull(source.voices().getFirst().bytes());
            var restored = StockAssets.resolveProject(source, List.of(symbols::get));
            assertEquals(project.voices().getFirst().bytes(), restored.voices().getFirst().bytes());
            assertArrayEquals(PCM, restored.samples().getFirst().pcm());
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    PortablePackageReader.assemble(archive(payload), List.of(ref -> new byte[]{1})));
            assertTrue(failure.getMessage().contains("expected " + fm.sha256() + ", actual"));
        }
    }

    @Test void s2ProjectVoicePatchesUseEditableOperatorOffsets() throws Exception {
        var driver = ClassicPackageDriver.S2;
        var ref = StockTable.ref(driver.id(), "local-fm", 129, 0, StockTable.hash(S2_VOICE));
        var project = project(NativeCommand.DETUNE, 60);
        byte[] editable = S1_VOICE.clone();
        editable[2] = 0x0A;
        var original = project.voices().getFirst();
        var voice = new Voice(original.id(), original.name(), StockAssets.unsigned(editable),
                AssetOrigin.stock(ref).withPatch(S1_VOICE, editable));
        project = project.withAssets(List.of(voice), project.envelopes(), project.samples());
        var payload = PortablePackageWriter.build(project, options(driver));
        var asset = payload.manifest().assets().getFirst();
        assertEquals(List.of(new AssetOrigin.PatchByte(3, 0x0A)), asset.transforms().getFirst().bytes());
        StockAssets stock = candidate -> candidate.equals(ref) ? S2_VOICE : null;
        var assembled = PortablePackageReader.assemble(archive(payload), List.of(stock));
        assertEquals(0x0A, assembled.song()[0x35 + 3] & 0xFF);
        var source = ProjectFormat.read(new String(payload.entries().get("source.smps.json"), StandardCharsets.UTF_8));
        assertEquals(voice, StockAssets.resolveProject(source, List.of(stock)).voices().getFirst());
    }

    @Test void rejectsUnsupportedCommandPitchAndSonicCd() {
        for (var driver : ClassicPackageDriver.values()) {
            assertFailure(driver.id() + ": SSG_EG", () ->
                    PortablePackageWriter.build(project(NativeCommand.SSG_EG, 60), options(driver)));
            assertFailure(driver.id() + ": PSG1 note 'p' pitch 47", () ->
                    PortablePackageWriter.build(project(NativeCommand.DETUNE, 47), options(driver)));
        }
        assertFailure("Sonic CD FM has no music loader", () -> PackageDriver.supported("scd"));
        var bank = NativeProgram.of(0, 0, 0, 30,
                bytes("00 00 01 00 01 80 0a 00 00 00 f2"), List.of(), List.of());
        var nativeProject = new StudioProject(5, "Native fixture", "S3K", 24, 128,
                List.of(), List.of(), List.of(), List.of(), List.of(), bank);
        assertFailure("s1: native v5 banks are S3K-only", () ->
                PortablePackageWriter.build(nativeProject, options(ClassicPackageDriver.S1)));
        assertFailure("s2: native v5 banks are S3K-only", () ->
                PortablePackageWriter.build(nativeProject, options(ClassicPackageDriver.S2)));
    }

    private static PortableOptions options(ClassicPackageDriver driver) {
        return new PortableOptions(driver.id(), true, null, null, null, List.of(), List.of(), 208);
    }

    static StudioProject project(NativeCommand command, int psgPitch) {
        var fm = new Sequence("fm", "FM", "FM", 6, "v", 121, List.of(new NoteEvent("n", 0, 6, 60)),
                List.of(new CommandEvent("change", 2, command, command == NativeCommand.DETUNE ? List.of(1) : command.defaultArguments())), "RESET");
        var psg = new Sequence("psg", "PSG", "PSG", 4, null, 112, List.of(new NoteEvent("p", 0, 4, psgPitch)),
                List.of(), "RESET", "env", null);
        var dac = new Sequence("dac", "DAC", "DAC", 6, null, 127, List.of(), List.of(), "RESET", null, null,
                List.of(new SampleTrigger("drum", 0, "sample")));
        return new StudioProject(4, "Dialect fixture", "S3K", 24, 192,
                List.of(new Voice("v", "Voice", StockAssets.unsigned(S1_VOICE))), List.of(fm, psg, dac),
                List.of(new Clip("fm-c", "fm", "FM1", 0, 6), new Clip("psg-c", "psg", "PSG1", 0, 4),
                        new Clip("dac-c", "dac", "DAC", 0, 6)),
                List.of(new Envelope("env", "Envelope", Envelope.PSG_VOLUME, StockAssets.unsigned(PSG))),
                List.of(DacSample.of("sample", "PCM", 6, PCM)));
    }

    private static PortablePackageReader.Package archive(PortablePackageWriter.Payload payload) {
        return new PortablePackageReader.Package(payload.manifest(), payload.entries());
    }

    private static byte[] bytes(String hex) { return HexFormat.of().parseHex(hex.replaceAll("\\s+", "")); }

    private static void assertFailure(String message, org.junit.jupiter.api.function.Executable exercise) {
        var failure = assertThrows(IllegalArgumentException.class, exercise);
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private record Write(int frame, int port, int register, int value) {}
    private static final class Observation implements ChipWriteObserver {
        final List<Write> writes = new ArrayList<>();
        final List<short[]> audio = new ArrayList<>();
        void capture(short[] pcm) { audio.add(pcm.clone()); }
        @Override public void onYm2612Write(int port, int register, int value) {
            writes.add(new Write(audio.size(), port, register, value));
        }
        @Override public void onPsgWrite(int value) {
            writes.add(new Write(audio.size(), -1, -1, value));
        }
    }
}
