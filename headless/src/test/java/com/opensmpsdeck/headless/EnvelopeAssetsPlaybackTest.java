package com.opensmpsdeck.headless;

import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.session.*;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.FmCoreSelection;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import com.opensmpsdeck.project.*;
import com.opensmpsdeck.project.Sequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeAssetsPlaybackTest {
    @TempDir Path directory;
    private static final int[] VOICE = {7, 1, 2, 3, 4, 31, 31, 31, 31,
            0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15, 0xA0, 0xA8, 0xB0, 0xB8};

    @Test void psgVolumeEnvelopeMatchesAnIndependentNativeBankAndExplicitTable() throws Exception {
        var envelope = envelope("psg", "PSG_VOLUME", 0, 2, 4, 6, 0x83);
        var project = project("PSG", 12, List.of(new NoteEvent("first", 0, 6, 60), new NoteEvent("second", 8, 4, 64)),
                List.of(), "RESET", "psg", null, List.of(envelope), 12);
        byte[] expected = psgBank(concat(psgReset(1, 0), new int[]{0x99, 6, 0x80, 2, 0x9D, 4, 0xF2}));
        Capture actual = equivalent(project, expected, Map.of(1, bytes(0, 2, 4, 6, 0x83)), Map.of(), 18);
        assertTrue(actual.writes.contains(new Write(1, -1, -1, 0x92)));
        assertTrue(actual.writes.contains(new Write(4, -1, -1, 0x9F)), "83 must silence the PSG track");
        assertTrue(actual.writes.contains(new Write(9, -1, -1, 0x92)), "an attacked note restarts its envelope");
    }

    @Test void resetVolumeEnvelopeHasAProductiveLoopRatherThanAHold() throws Exception {
        var project = project("PSG", 8, List.of(new NoteEvent("note", 0, 8, 60)), List.of(), "RESET",
                "psg", null, List.of(envelope("psg", "PSG_VOLUME", 0, 2, 0x80)), 8);
        Capture actual = equivalent(project, psgBank(concat(psgReset(1, 0), new int[]{0x99, 8, 0xF2})),
                Map.of(1, bytes(0, 2, 0x80)), Map.of(), 12);
        assertTrue(actual.writes.contains(new Write(1, -1, -1, 0x92)));
        assertTrue(actual.writes.contains(new Write(2, -1, -1, 0x90)));
    }

    @Test void fmAndPsgModulationEnvelopesMatchIndependentNativeTables() throws Exception {
        for (String type : List.of("FM", "PSG")) {
            var project = project(type, 10, List.of(new NoteEvent("note", 0, 10, 60)), List.of(), "RESET",
                    null, "mod", List.of(envelope("mod", "MODULATION", 0, 2, 4, 0x81)), 10);
            byte[] expected = type.equals("FM") ? fmBank(VOICE, concat(fmReset(127, 1), new int[]{0xB1, 10, 0xF2}))
                    : psgBank(concat(psgReset(0, 1), new int[]{0x99, 10, 0xF2}));
            Capture actual = equivalent(project, expected, Map.of(), Map.of(1, bytes(0, 2, 4, 0x81)), 16);
            if (type.equals("FM")) {
                assertNotEquals(lastFm(actual, 0, 0xA0), lastFm(actual, 1, 0xA0));
                assertEquals(lastFm(actual, 0, 0xA0) + 4, lastFm(actual, 2, 0xA0));
                assertFalse(actual.writes.stream().anyMatch(write -> write.port() == 0
                                && write.register() == 0xA0 && write.frame() >= 3 && write.frame() < 10),
                        "81 sustains the last frequency without rewriting it");
            }
        }
    }

    @Test void resetAndCarryUseNativeEnvelopeStateAndIndependentClipsClearBothSelections() throws Exception {
        Capture reset = null, carry = null;
        for (String state : List.of("RESET", "CARRY")) {
            var source = project("PSG", 4, List.of(new NoteEvent("note", 0, 4, 60)),
                    List.of(new CommandEvent("off", 2, NativeCommand.MODULATION_OFF, List.of())), state,
                    "psg", "mod", List.of(envelope("psg", "PSG_VOLUME", 0, 2, 4, 0x83),
                            envelope("mod", "MODULATION", 0, 2, 4, 0x81)), 8);
            var plain = new Sequence("plain", "No envelopes", "PSG", 4, null, 127,
                    List.of(new NoteEvent("plain-note", 0, 4, 60)), List.of(), "RESET", null, null);
            var project = new StudioProject(3, source.name(), "S3K", 24, 0, List.of(),
                    List.of(source.sequences().getFirst(), plain),
                    List.of(source.clips().getFirst(), new Clip("plain-clip", "plain", "PSG1", 8, 4)), source.envelopes(), TargetGame.S3K, null);
            int[] phrase = {0x99, 2, 0xFA, 0xF4, 0, 0xE7, 2};
            byte[] expected = psgBank(concat(psgReset(1, 1), phrase,
                    state.equals("RESET") ? psgReset(1, 1) : new int[0], phrase,
                    psgReset(0, 0), new int[]{0x99, 4, 0xF2}));
            Capture actual = equivalent(project, expected, Map.of(1, bytes(0, 2, 4, 0x83)),
                    Map.of(1, bytes(0, 2, 4, 0x81)), 18);
            assertTrue(actual.writes.contains(new Write(8, -1, -1, 0x90)), "the plain clip must clear inherited volume envelope");
            if (state.equals("RESET")) reset = actual; else carry = actual;
        }
        assertNotEquals(reset.writes, carry.writes, "RESET restores the selected envelope; CARRY retains the clear");
        assertFalse(Arrays.equals(reset.pcm.toByteArray(), carry.pcm.toByteArray()));
    }

    @Test void resourceMapsAreInstalledBeforeHeaderEnvelopeStateIsCaptured() throws Exception {
        byte[] song = psgBank(0x99, 8, 0xF2);
        song[14] = 1; song[15] = 1; // PSG header modulation and volume-envelope IDs.
        var compiled = new CompiledProject(song, List.of(envelope("psg", "PSG_VOLUME", 0, 2, 0x83),
                envelope("mod", "MODULATION", 0, 2, 0x81)));
        Capture actual = observe(compiled, 12);
        Capture expected = reference(song, Map.of(1, bytes(0, 2, 0x83)), Map.of(1, bytes(0, 2, 0x81)), 12);
        assertEquals(expected.writes, actual.writes);
        assertArrayEquals(expected.pcm.toByteArray(), actual.pcm.toByteArray());
        assertTrue(actual.writes.contains(new Write(1, -1, -1, 0x92)));
    }

    @Test void nativeVoiceBytesReachTheirRegistersAndTlBitSevenControlsTrackVolume() throws Exception {
        int[] voiceBytes = VOICE.clone();
        voiceBytes[2] = 0x27; // Second stored operator is register +08, not +04.
        voiceBytes[22] = 0x28; // Clear its native track-volume flag without changing attenuation.
        var source = project("FM", 6, List.of(new NoteEvent("note", 0, 6, 60)), List.of(), "RESET",
                null, null, List.of(), 6);
        var voice = new Voice("voice", "Native flags", Arrays.stream(voiceBytes).boxed().toList());
        var sequence = new Sequence("sequence", "Voice", "FM", 6, "voice", 100, source.sequences().getFirst().notes(),
                List.of(), "RESET", null, null);
        var project = new StudioProject(3, source.name(), "S3K", 24, 0, List.of(voice), List.of(sequence), source.clips(), List.of(), TargetGame.S3K, null);
        Capture actual = equivalent(project, fmBank(voiceBytes, concat(fmReset(100, 0), new int[]{0xB1, 6, 0xF2})),
                Map.of(), Map.of(), 12);
        assertEquals(1, lastFm(actual, 0, 0x30));
        assertEquals(0x27, lastFm(actual, 0, 0x38));
        assertEquals(3, lastFm(actual, 0, 0x34));
        assertEquals(4, lastFm(actual, 0, 0x3C));
        assertEquals(0x20 + 27, lastFm(actual, 0, 0x40), "flagged TL receives volume 127-100");
        assertEquals(0x28, lastFm(actual, 0, 0x48), "unflagged TL remains unchanged");
        byte[] bank = ProjectCompiler.compile(project);
        int voiceAt = (bank[0] & 255) | (bank[1] & 255) << 8;
        assertArrayEquals(bytes(voiceBytes), Arrays.copyOfRange(bank, voiceAt, voiceAt + 25));
    }

    @Test void missingReferencesAndTruncatedSelectionsFailBeforePcmOrWaveCreation() throws Exception {
        for (byte[] song : List.of(fmBank(VOICE, 0xF4, 1, 0xF2), psgBank(0xF5, 1, 0xF2),
                fmBank(VOICE, 0xF4), psgBank(0xF5), fmBank(VOICE, 0xF5, 0, 0xF2))) {
            assertThrows(IllegalArgumentException.class,
                    () -> S3kRenderer.render(song, 1, ignored -> fail("validation must precede PCM")));
        }
        var missing = new CompiledProject(psgBank(0xF5, 1, 0xF2), List.of());
        Path absent = directory.resolve("invalid.wav");
        assertThrows(IllegalArgumentException.class, () -> WaveFile.render(missing, absent, 4));
        assertFalse(Files.exists(absent));
        Path existing = Files.writeString(directory.resolve("keep.wav"), "keep me");
        assertThrows(IllegalArgumentException.class, () -> WaveFile.render(missing, existing, 4));
        assertEquals("keep me", Files.readString(existing));
    }

    @Test void unsafeOrUnsupportedEnvelopeProgramsAndIndicesAreRejectedAtTheRawBoundary() {
        for (List<Integer> program : List.of(List.of(0x80), List.of(0, 0x82, 0), List.of(0, 0x84, 1, 0x81),
                List.of(0, 0xFF, 0x81), List.of(0), List.<Integer>of())) {
            assertThrows(IllegalArgumentException.class, () -> S3kBinary.validateAndCopy(psgBank(0xF5, 1, 0xF2),
                    Map.of(1, program), Map.of()));
            assertThrows(IllegalArgumentException.class, () -> S3kBinary.validateAndCopy(fmBank(VOICE, 0xF4, 1, 0xF2),
                    Map.of(), Map.of(1, program)));
        }
        assertThrows(IllegalArgumentException.class, () -> S3kBinary.validateAndCopy(psgBank(0xF2), Map.of(0, List.of(0, 0x81)), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> S3kBinary.validateAndCopy(fmBank(VOICE, 0xF2), Map.of(), Map.of(128, List.of(0, 0x81))));
    }

    @Test void publicCompiledInputValidatesUnknownUnusedAndOversizedDeclarationsBeforePcm() throws Exception {
        List<List<Envelope>> invalid = new ArrayList<>();
        invalid.add(List.of(envelope("unknown", "OTHER", 0, 0x81)));
        invalid.add(List.of(new Envelope("blank-name", " ", "PSG_VOLUME", List.of(0, 0x83))));
        invalid.add(List.of(envelope("same", "PSG_VOLUME", 0, 0x83), envelope("same", "MODULATION", 0, 0x81)));
        invalid.add(List.of(envelope("unsafe-unused", "PSG_VOLUME", 0x80)));
        for (String type : List.of("PSG_VOLUME", "MODULATION")) {
            List<Envelope> tooMany = new ArrayList<>();
            for (int index = 0; index <= (type.equals("PSG_VOLUME") ? 255 : 127); index++)
                tooMany.add(envelope("envelope-" + index, type, 0, 0x81));
            invalid.add(tooMany);
        }
        for (List<Envelope> declarations : invalid) {
            var project = new CompiledProject(psgBank(0xF2), declarations);
            assertThrows(IllegalArgumentException.class,
                    () -> S3kRenderer.render(project, 1, ignored -> fail("every declaration must validate before PCM")));
            Path absent = directory.resolve("invalid-" + invalid.indexOf(declarations) + ".wav");
            assertThrows(IllegalArgumentException.class, () -> WaveFile.render(project, absent, 1));
            assertFalse(Files.exists(absent));
        }
    }

    @Test void publicCompiledInputOwnsItsBytesAndEnvelopeTablesAreImmutable() throws Exception {
        byte[] raw = psgBank(concat(psgReset(1, 0), new int[]{0x99, 6, 0xF2}));
        List<Integer> values = new ArrayList<>(List.of(0, 2, 0x83));
        List<Envelope> assets = new ArrayList<>(List.of(new Envelope("psg", "PSG", "PSG_VOLUME", values)));
        var project = new CompiledProject(raw, assets);
        raw[17] = (byte) 0xFF; project.song()[17] = (byte) 0xFF;
        values.set(0, 15); assets.clear();
        assertEquals(List.of(0, 2, 0x83), project.psgEnvelopes().get(1));
        assertThrows(UnsupportedOperationException.class, () -> project.psgEnvelopes().put(2, List.of(0, 0x83)));
        assertThrows(UnsupportedOperationException.class, () -> project.psgEnvelopes().get(1).set(0, 15));
        Capture actual = observe(project, 10);
        Capture expected = reference(project.song(), Map.of(1, bytes(0, 2, 0x83)), Map.of(), 10);
        assertEquals(expected.writes, actual.writes);
        assertArrayEquals(expected.pcm.toByteArray(), actual.pcm.toByteArray());
    }

    @Test void v3SourceCliRendersAssetsAndRawBinaryCannotLoseItsDependencies() throws Exception {
        var project = project("PSG", 8, List.of(new NoteEvent("note", 0, 8, 60)), List.of(), "RESET", "psg", null,
                List.of(envelope("psg", "PSG_VOLUME", 0, 2, 4, 0x83)), 8);
        String json = ProjectFormat.write(project);
        Path input = Files.writeString(directory.resolve("assets.json"), json);
        assertEquals(0, run("validate-project", input.toString()).exit());
        Path wave = directory.resolve("assets.wav");
        Result rendered = run("render-project", input.toString(), wave.toString(), "--frames", "12");
        assertEquals(0, rendered.exit(), rendered.err());
        byte[] wav = Files.readAllBytes(wave);
        assertArrayEquals(observe(ProjectCompiler.compileWithAssets(project), 12).pcm.toByteArray(), Arrays.copyOfRange(wav, 44, wav.length));
        Path refused = directory.resolve("incomplete.bin");
        Result incomplete = run("compile-project", input.toString(), refused.toString());
        assertEquals(2, incomplete.exit()); assertTrue(incomplete.err().contains("compile-package"), incomplete.err());
        assertFalse(Files.exists(refused));
        Path raw = Files.write(directory.resolve("raw.bin"), ProjectCompiler.compile(project));
        Path rawWave = directory.resolve("raw.wav");
        assertEquals(2, run("render", raw.toString(), rawWave.toString(), "--frames", "12").exit());
        assertFalse(Files.exists(rawWave));
        Path archive = directory.resolve("assets.smps.zip");
        Result packaged = run("compile-package", input.toString(), archive.toString());
        assertEquals(0, packaged.exit(), packaged.err()); assertTrue(Files.size(archive) > 0);
        assertEquals(json, Files.readString(input));
    }

    static StudioProject project(String type, int length, List<NoteEvent> notes, List<CommandEvent> commands, String repeat,
                                 String psgEnvelope, String modulationEnvelope, List<Envelope> envelopes, int clipLength) {
        var sequence = new Sequence("sequence", "Envelope fixture", type, length, type.equals("FM") ? "voice" : null,
                127, notes, commands, repeat, psgEnvelope, modulationEnvelope);
        List<Voice> voices = type.equals("FM") ? List.of(new Voice("voice", "Original native patch", Arrays.stream(VOICE).boxed().toList())) : List.of();
        return new StudioProject(3, "Asset fixture", "S3K", 24, 0, voices, List.of(sequence),
                List.of(new Clip("clip", "sequence", type + "1", 0, clipLength)), envelopes, TargetGame.S3K, null);
    }

    static Envelope envelope(String id, String type, int... values) {
        return new Envelope(id, "Envelope " + id, type, Arrays.stream(values).boxed().toList());
    }

    private static int[] fmReset(int volume, int modulation) {
        return new int[]{0xEF, 0, 0xE4, volume, 0xE0, 0xC0, 0xED, 0x40, 0xE1, 0, 0xE8, 0, 0xFA, 0xF4, modulation};
    }

    private static int[] psgReset(int volume, int modulation) {
        return new int[]{0xE4, 127, 0xED, 0x40, 0xE1, 0, 0xE8, 0, 0xFA, 0xF5, volume, 0xF4, modulation};
    }

    private static byte[] psgBank(int... track) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{0, 0, 1, 1, 1, 0, 16, 0, 0, 0, 17, 0, 0, 0, 0, 0, (byte) 0xF2});
        output.writeBytes(bytes(track)); return output.toByteArray();
    }

    private static byte[] fmBank(int[] voice, int... track) {
        var output = new ByteArrayOutputStream();
        int voiceAt = 15 + track.length;
        output.write(voiceAt); output.write(voiceAt >>> 8);
        output.writeBytes(new byte[]{2, 0, 1, 0, 14, 0, 0, 0, 15, 0, 0, 0, (byte) 0xF2});
        output.writeBytes(bytes(track)); output.writeBytes(bytes(voice)); return output.toByteArray();
    }

    private static int[] concat(int[]... parts) { return Arrays.stream(parts).flatMapToInt(Arrays::stream).toArray(); }
    private static byte[] bytes(int... values) { byte[] bytes = new byte[values.length]; for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i]; return bytes; }
    private static int lastFm(Capture capture, int frame, int register) {
        return capture.writes.stream().filter(w -> w.frame() == frame && w.port() == 0 && w.register() == register)
                .mapToInt(Write::value).reduce((first, last) -> last).orElseThrow();
    }

    private static Capture equivalent(StudioProject project, byte[] song, Map<Integer, byte[]> psg, Map<Integer, byte[]> mod,
                                      int presentations) throws Exception {
        Capture expected = reference(song, psg, mod, presentations);
        Capture actual = observe(ProjectCompiler.compileWithAssets(project), presentations);
        assertEquals(expected.writes, actual.writes, "presentation-stamped native writes with independent resource maps");
        assertArrayEquals(expected.pcm.toByteArray(), actual.pcm.toByteArray(), "PCM with independent resource maps");
        return actual;
    }

    private static Capture observe(CompiledProject project, int presentations) throws IOException {
        Capture capture = new Capture();
        S3kRenderer.render(project, presentations, capture::accept, capture);
        return capture;
    }

    /** The reference independently supplies hand-authored banks and native tables to the pinned production session. */
    private static Capture reference(byte[] song, Map<Integer, byte[]> psg, Map<Integer, byte[]> mod, int presentations) throws Exception {
        var data = new Sonic3kSmpsData(song.clone(), 0);
        data.setPsgEnvelopes(psg); data.setModEnvelopes(mod);
        var dac = new DacData(Map.of(), Map.of(), 297);
        var config = Sonic3kSmpsSequencerConfig.create(new com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler());
        var source = SmpsSourceDescriptor.from(data);
        MusicRestoreSink restore = () -> { throw new IllegalStateException("reference has no previous music"); };
        var detached = new SmpsDriver();
        var sequencer = new SmpsSequencer(data, dac, detached, detached, restore, config, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        var incoming = new SmpsDriverSnapshot.SequencerEntry(false, source, SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE,
                null, data, dac, restore, config, sequencer.captureSnapshot());
        var prepared = new PreparedSmpsMusicActivation(new SmpsMusicActivation(source, data.getChannels(), data.getPsgChannels()),
                incoming, SmpsLogicalTransitionPolicies.forConfig(config), new SmpsDacSelection(source, dac));
        var settings = SmpsHardwareFrameBudget.presentationSettings(48_000, 60, false, false, FmCoreSelection.ACCURATE, null);
        var policy = Sonic3kSmpsPhysicalPolicy.INSTANCE;
        Capture capture = new Capture();
        try (var session = new SmpsDriverSession(settings, policy, capture,
                new SmpsSessionProfileFingerprint("s3k", 0, policy.identity(), settings), SmpsDriverSessionConfiguration.DEFAULT)) {
            session.install(); session.queueActivation(prepared);
            short[] pcm = new short[1600];
            for (int frame = 0; frame < presentations; frame++) {
                session.serviceForward(); session.advanceDacIdleLoop(pcm, 800); capture.accept(pcm);
            }
        }
        return capture;
    }

    private static Result run(String... arguments) {
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
        int exit = Main.run(arguments, new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Result(exit, errors.toString(StandardCharsets.UTF_8));
    }

    private static final class Capture implements ChipWriteObserver {
        private final List<Write> writes = new ArrayList<>();
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private int frame;
        @Override public void onYm2612Write(int port, int register, int value) { writes.add(new Write(frame, port, register, value)); }
        @Override public void onPsgWrite(int value) { writes.add(new Write(frame, -1, -1, value)); }
        private void accept(short[] samples) {
            for (short sample : samples) { pcm.write(sample); pcm.write(sample >>> 8); }
            frame++;
        }
    }
    private record Write(int frame, int port, int register, int value) {}
    private record Result(int exit, String err) {}
}
