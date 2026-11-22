package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;

import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.Sequence;
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

import static org.junit.jupiter.api.Assertions.*;

class NativeCommandsPlaybackTest {
    @TempDir Path directory;
    private static final int[] VOICE = {7, 1, 2, 3, 4, 31, 31, 31, 31,
            0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15, 32, 40, 48, 56};
    private static final int[] FM_RESET = {0xEF, 0, 0xE4, 127, 0xE0, 0xC0, 0xED, 0x40,
            0xE1, 0, 0xE8, 0, 0xFA};
    private static final int[] PSG_RESET = {0xE4, 127, 0xED, 0x40, 0xE1, 0, 0xE8, 0, 0xFA};

    @Test void midnotePanVolumeAndTransposeMatchNativeTimingWithoutAnotherAttackOrPitchRead() throws Exception {
        StudioProject project = project("FM", 12, List.of(new NoteEvent("note", 0, 12, 60)),
                List.of(command("pan", 6, NativeCommand.PAN, 0x80),
                        command("transpose", 6, NativeCommand.TRANSPOSE, 0x4C),
                        command("volume", 6, NativeCommand.VOLUME, 0x50)), "RESET", 12);
        byte[] expected = bank(concat(FM_RESET, new int[]{0xB1, 6, 0xE0, 0x80, 0xED, 0x4C,
                0xE4, 0x50, 0xE7, 6, 0xF2}));
        Observation actual = equivalent(project, expected, 20);

        assertTrue(actual.writes().contains(new Write(6, 0, 0xB4, 0x80)), "pan must execute at tick six");
        assertTrue(actual.writes().stream().anyMatch(w -> w.frame() == 6 && w.register() == 0x40),
                "absolute volume must update carrier TL at the same tick");
        assertEquals(1, keyOns(actual), "command continuations must not retrigger the FM envelope");
        assertEquals(frequency(actual, 5, 0xA0), frequency(actual, 6, 0xA0),
                "held duration must retain pitch despite a changed transpose");
        assertEquals(frequency(actual, 5, 0xA4), frequency(actual, 6, 0xA4));

        // These native alternatives are deliberately wrong. The comparisons
        // establish that order-only traces or a still-audible note cannot pass.
        Observation retriggered = observe(bank(concat(FM_RESET, new int[]{0xB1, 6,
                0xE0, 0x80, 0xED, 0x4C, 0xE4, 0x50, 6, 0xF2})), 20);
        assertEquals(2, keyOns(retriggered));
        Observation repitched = observe(bank(concat(FM_RESET, new int[]{0xB1, 6,
                0xE0, 0x80, 0xED, 0x4C, 0xE4, 0x50, 0xE7, 0xB1, 6, 0xF2})), 20);
        assertNotEquals(frequency(actual, 6, 0xA4), frequency(repitched, 6, 0xA4));
    }

    @Test void authoredSameTickOrderReachesTheChipInThatOrder() throws Exception {
        StudioProject project = project("FM", 6, List.of(new NoteEvent("note", 0, 6, 60)),
                List.of(command("left", 0, NativeCommand.PAN, 0x80),
                        command("right", 0, NativeCommand.PAN, 0x40)), "RESET", 6);
        Observation actual = equivalent(project, bank(concat(FM_RESET,
                new int[]{0xE0, 0x80, 0xE0, 0x40, 0xB1, 6, 0xF2})), 12);
        List<Integer> pans = actual.writes().stream().filter(w -> w.frame() == 0 && w.register() == 0xB4)
                .map(Write::value).toList();
        assertEquals(List.of(0x80, 0x40), pans.subList(pans.size() - 2, pans.size()));
    }

    @Test void modulationSetupAtAttackAndOffDuringSustainMatchNativeState() throws Exception {
        StudioProject project = project("FM", 12, List.of(new NoteEvent("note", 0, 12, 60)),
                List.of(command("mod", 0, NativeCommand.MODULATION, 1, 1, 2, 6),
                        command("off", 6, NativeCommand.MODULATION_OFF)), "RESET", 12);
        Observation actual = equivalent(project, bank(concat(FM_RESET,
                new int[]{0xF0, 1, 1, 2, 6, 0xB1, 6, 0xFA, 0xE7, 6, 0xF2})), 20);
        assertEquals(1, keyOns(actual));
        assertTrue(actual.writes().stream().filter(w -> w.register() == 0xA0 && w.frame() < 6)
                .map(Write::value).distinct().count() > 1, "modulation must alter frequency before off");
    }

    @Test void f0VibratoMatchesTheDocumentedRateAndDepth() throws Exception {
        var project = project("FM", 36, List.of(new NoteEvent("note", 0, 36, 60)),
                List.of(command("mod", 0, NativeCommand.MODULATION, 1, 1, 8, 6)), "RESET", 36);
        var actual = observe(ProjectCompiler.compile(project), 36);
        var fnums = new ArrayList<Integer>();
        for (int frame = 0; frame < 24; frame++)
            fnums.add((frequency(actual, frame, 0xA4) & 7) * 256 + frequency(actual, frame, 0xA0));
        assertEquals(644 + 24, java.util.Collections.max(fnums));
        assertEquals(644 - 24, java.util.Collections.min(fnums));
        assertEquals(fnums.subList(0, 12), fnums.subList(12, 24));
        assertEquals(1, keyOns(actual));
    }

    @Test void toneThreePeriodicNoiseSoundsAtTheRequestedPitch() throws Exception {
        int pitch = 60;
        assertTrue(ReconstructionPitchedNoise.playable(pitch));
        assertFalse(ReconstructionPitchedNoise.playable(12));
        assertFalse(ReconstructionPitchedNoise.playable(106));
        var sequence = new Sequence("noise", "Periodic noise", "PSG", 60, null, 127,
                List.of(new NoteEvent("clock", 0, 60, ReconstructionCymbals.PITCH)),
                List.of(command("noise-mode", 0, NativeCommand.PSG_NOISE, ReconstructionPitchedNoise.COMMAND),
                        command("period", 0, NativeCommand.DETUNE,
                                ReconstructionPitchedNoise.detune(pitch) & 255)), "RESET");
        var project = new StudioProject(4, "Periodic noise tuning", "S3K", 24, 0, List.of(),
                List.of(sequence), List.of(new Clip("clip", "noise", "PSG3", 0, 60)), TargetGame.S3K, null);
        var pcm = java.nio.ByteBuffer.wrap(observe(ProjectCompiler.compile(project), 60).pcm())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        double best = -1;
        int bestLag = 0;
        for (int lag = 165; lag <= 200; lag++) {
            double cross = 0, a2 = 0, b2 = 0;
            for (int i = 4_800; i < 24_000; i++) {
                double a = pcm.getShort(i * 4);
                double b = pcm.getShort((i + lag) * 4);
                cross += a * b;
                a2 += a * a;
                b2 += b * b;
            }
            double correlation = cross / Math.sqrt(a2 * b2);
            if (correlation > best) {
                best = correlation;
                bestLag = lag;
            }
        }
        assertTrue(best > .90);
        assertEquals(48_000.0 / 261.626, bestLag, 5);
    }

    @Test void psgModulationAndVolumeKeepTheirNativeContinuationBehavior() throws Exception {
        StudioProject project = project("PSG", 12, List.of(new NoteEvent("note", 0, 12, 60)),
                List.of(command("mod", 0, NativeCommand.MODULATION, 1, 1, 2, 6),
                        command("quieter", 3, NativeCommand.PSG_VOLUME, 1),
                        command("off", 6, NativeCommand.MODULATION_OFF)), "RESET", 12);
        equivalent(project, psgBank(concat(PSG_RESET, new int[]{0xF0, 1, 1, 2, 6,
                0x99, 3, 0xEC, 1, 0xE7, 3, 0xFA, 0xE7, 6, 0xF2})), 20);
    }

    @Test void detuneAndFmAttenuationApplyDuringTheSustainAtTheirOwnTicks() throws Exception {
        StudioProject project = project("FM", 12, List.of(new NoteEvent("note", 0, 12, 60)),
                List.of(command("down", 3, NativeCommand.DETUNE, 0xFF),
                        command("quieter", 6, NativeCommand.FM_VOLUME, 2),
                        command("neutral", 9, NativeCommand.DETUNE, 0)), "RESET", 12);
        Observation actual = equivalent(project, bank(concat(FM_RESET,
                new int[]{0xB1, 3, 0xE1, 0xFF, 0xE7, 3, 0xE6, 2, 0xE7, 3,
                        0xE1, 0, 0xE7, 3, 0xF2})), 20);
        assertEquals(1, keyOns(actual));
        assertNotEquals(frequency(actual, 2, 0xA0), frequency(actual, 3, 0xA0));
        assertEquals(frequency(actual, 2, 0xA0), frequency(actual, 9, 0xA0));
        assertTrue(actual.writes().stream().anyMatch(w -> w.frame() == 6 && w.register() == 0x40));
    }

    @Test void eventsBeyondDurationByteBoundariesKeepOneNativeAttack() throws Exception {
        StudioProject project = project("FM", 300, List.of(new NoteEvent("note", 0, 300, 60)),
                List.of(command("pan", 130, NativeCommand.PAN, 0x80)), "RESET", 300);
        Observation actual = equivalent(project, bank(concat(FM_RESET,
                new int[]{0xB1, 127, 0xE7, 3, 0xE0, 0x80, 0xE7, 127, 0xE7, 43, 0xF2})), 305);
        assertEquals(1, keyOns(actual));
        assertTrue(actual.writes().contains(new Write(130, 0, 0xB4, 0x80)));
    }

    @Test void resetAndCarryDifferBetweenRepeatsButIndependentClipsAlwaysReset() throws Exception {
        for (String state : List.of("RESET", "CARRY")) {
            StudioProject source = project("FM", 4, List.of(new NoteEvent("note", 0, 4, 60)),
                    List.of(command("up", 0, NativeCommand.TRANSPOSE_ADD, 1)), state, 8);
            StudioProject project = new StudioProject(2, source.name(), "S3K", 24, 0, source.voices(),
                    source.sequences(), List.of(source.clips().getFirst(), new Clip("independent", "sequence", "FM1", 8, 4)), TargetGame.S3K, null);
            int[] repeat = {0xFB, 1, 0xB1, 4};
            byte[] expected = bank(concat(FM_RESET, repeat, state.equals("RESET") ? FM_RESET : new int[0],
                    repeat, FM_RESET, repeat, new int[]{0xF2}));
            Observation actual = equivalent(project, expected, 20);
            assertEquals(3, keyOns(actual));
            assertEquals(frequency(actual, 0, 0xA0), frequency(actual, 8, 0xA0),
                    "independent clip must restore initial state under " + state);
            if (state.equals("CARRY")) assertNotEquals(frequency(actual, 0, 0xA0), frequency(actual, 4, 0xA0));
            else assertEquals(frequency(actual, 0, 0xA0), frequency(actual, 4, 0xA0));
        }
    }

    @Test void silentCommandSourcesAndPartialRepeatsExecuteOnlyIncludedEvents() throws Exception {
        StudioProject silence = project("FM", 12, List.of(),
                List.of(command("pan", 4, NativeCommand.PAN, 0x80)), "RESET", 12);
        Observation silent = equivalent(silence, bank(concat(FM_RESET,
                new int[]{0x80, 4, 0xE0, 0x80, 0x80, 8, 0xF2})), 20);
        assertEquals(0, keyOns(silent));
        assertTrue(silent.writes().contains(new Write(4, 0, 0xB4, 0x80)));

        StudioProject trimmed = project("FM", 8, List.of(new NoteEvent("note", 0, 8, 60)),
                List.of(command("early", 2, NativeCommand.PAN, 0x80),
                        command("late", 7, NativeCommand.PAN, 0x40)), "RESET", 11);
        Observation actual = equivalent(trimmed, bank(concat(FM_RESET,
                new int[]{0xB1, 2, 0xE0, 0x80, 0xE7, 5, 0xE0, 0x40, 0xE7, 1}, FM_RESET,
                new int[]{0xB1, 2, 0xE0, 0x80, 0xE7, 1, 0xF2})), 20);
        assertTrue(actual.writes().contains(new Write(7, 0, 0xB4, 0x40)));
        // EF at the next reset can legitimately resend the previous pan before
        // E0 restores stereo. The trimmed event itself would occur at tick 15.
        assertFalse(actual.writes().stream().anyMatch(w -> w.frame() == 15 && w.register() == 0xB4 && w.value() == 0x40),
                "late event must not leak from the trimmed repeat");
    }

    @Test void v2SourceCliValidatesCompilesAndRendersTheSameNativeFixture() throws Exception {
        StudioProject project = project("FM", 12, List.of(new NoteEvent("note", 0, 12, 60)),
                List.of(command("mod", 0, NativeCommand.MODULATION, 1, 1, 2, 6),
                        command("off", 6, NativeCommand.MODULATION_OFF)), "RESET", 12);
        String json = ProjectFormat.write(project);
        Path source = Files.writeString(directory.resolve("commands.json"), json);
        Path binary = directory.resolve("commands.bin"), wave = directory.resolve("commands.wav");
        assertEquals(0, run("validate-project", source.toString()).exit());
        Result compiled = run("compile-project", source.toString(), binary.toString());
        assertEquals(0, compiled.exit(), compiled.err());
        byte[] expected = bank(concat(FM_RESET,
                new int[]{0xF0, 1, 1, 2, 6, 0xB1, 6, 0xFA, 0xE7, 6, 0xF2}));
        assertArrayEquals(expected, Files.readAllBytes(binary));
        Result rendered = run("render-project", source.toString(), wave.toString(), "--frames", "20");
        assertEquals(0, rendered.exit(), rendered.err());
        byte[] wav = Files.readAllBytes(wave);
        assertArrayEquals(observe(expected, 20).pcm(), Arrays.copyOfRange(wav, 44, wav.length));
        assertEquals(json, Files.readString(source));

        Path invalid = Files.writeString(directory.resolve("unknown.json"), json.replace("\"MODULATION\"", "\"RAW_ESCAPE\""));
        Path absent = directory.resolve("must-not-exist.wav");
        assertEquals(2, run("render-project", invalid.toString(), absent.toString(), "--frames", "20").exit());
        assertFalse(Files.exists(absent));
    }

    @Test void finiteModulationConsumesFourRawOperandsAndOffConsumesNone() throws Exception {
        byte[] song = bank(0xEF, 0, 0xE4, 127, 0xF0, 0xE0, 0xFA, 0xF2, 0xFF,
                0xB1, 6, 0xFA, 0xE7, 6, 0xF2);
        assertArrayEquals(song, S3kBinary.validateAndCopy(song));
        int[] presentations = {0};
        S3kRenderer.render(song, 16, pcm -> presentations[0]++);
        assertEquals(16, presentations[0]);
    }

    @Test void everyTruncatedModulationOperandFailsBeforeRendering() {
        for (int operands = 0; operands < 4; operands++) {
            int[] track = new int[operands + 1];
            track[0] = 0xF0;
            Arrays.fill(track, 1, track.length, 1);
            byte[] song = bank(track);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> S3kRenderer.render(song, 1, ignored -> fail("validation must precede PCM")));
            assertTrue(failure.getMessage().contains("truncated"), failure.getMessage());
        }
    }

    @Test void modulationOffCannotHideMissingStopsOrUnsupportedCommands() {
        for (int[] track : new int[][]{{0xFA}, {0xFA, 0xFF, 0, 1, 0xF2},
                {0xFA, 0xF6, 0, 0, 0xF2}, {0xFA, 0xEA, 0x81, 0xF2}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> S3kRenderer.render(bank(track), 1, ignored -> fail("unsupported stream must fail")));
        }
    }

    /** Independently authored base-zero DAC + FM1 bank, including its native voice. */
    private static byte[] bank(int... fmTrack) {
        var output = new ByteArrayOutputStream();
        int voiceAt = 15 + fmTrack.length;
        output.write(voiceAt); output.write(voiceAt >>> 8);
        output.writeBytes(new byte[]{2, 0, 1, 0, 14, 0, 0, 0, 15, 0, 0, 0, (byte) 0xF2});
        for (int value : fmTrack) output.write(value);
        for (int value : VOICE) output.write(value);
        return output.toByteArray();
    }

    private static byte[] psgBank(int... track) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{0, 0, 1, 1, 1, 0, 16, 0, 0, 0, 17, 0, 0, 0, 0, 0, (byte) 0xF2});
        for (int value : track) output.write(value);
        return output.toByteArray();
    }

    private static StudioProject project(String type, int length, List<NoteEvent> notes,
                                         List<CommandEvent> commands, String repeatState, int clipLength) {
        List<Voice> voices = type.equals("FM")
                ? List.of(new Voice("voice", "Original test patch", Arrays.stream(VOICE).boxed().toList())) : List.of();
        var sequence = new Sequence("sequence", "Commands", type, length, type.equals("FM") ? "voice" : null,
                127, notes, commands, repeatState);
        return new StudioProject(2, "Native command fixture", "S3K", 24, 0, voices,
                List.of(sequence), List.of(new Clip("clip", "sequence", type + "1", 0, clipLength)), TargetGame.S3K, null);
    }

    private static CommandEvent command(String id, int tick, NativeCommand command, int... arguments) {
        return new CommandEvent(id, tick, command, Arrays.stream(arguments).boxed().toList());
    }

    private static int[] concat(int[]... parts) {
        return Arrays.stream(parts).flatMapToInt(Arrays::stream).toArray();
    }

    private static Observation equivalent(StudioProject project, byte[] handwritten, int presentations) throws Exception {
        Observation expected = observe(handwritten, presentations);
        Observation actual = observe(ProjectCompiler.compile(project), presentations);
        assertEquals(expected.writes(), actual.writes(), "presentation-stamped native chip writes");
        assertArrayEquals(expected.pcm(), actual.pcm(), "native PCM through production activation");
        return actual;
    }

    private static Observation observe(byte[] song, int presentations) throws Exception {
        var pcm = new ByteArrayOutputStream();
        List<Write> writes = new ArrayList<>();
        int[] frame = {0};
        var observer = new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.add(new Write(frame[0], port, register, value));
            }
            @Override public void onPsgWrite(int value) { writes.add(new Write(frame[0], -1, -1, value)); }
        };
        S3kRenderer.render(song, presentations, samples -> {
            for (short sample : samples) { pcm.write(sample); pcm.write(sample >>> 8); }
            frame[0]++;
        }, observer);
        return new Observation(writes, pcm.toByteArray());
    }

    private static long keyOns(Observation observation) {
        return observation.writes().stream().filter(w -> w.port() == 0 && w.register() == 0x28 && w.value() == 0xF0).count();
    }

    private static int frequency(Observation observation, int frame, int register) {
        return observation.writes().stream().filter(w -> w.frame() == frame && w.port() == 0 && w.register() == register)
                .mapToInt(Write::value).reduce((first, last) -> last).orElseThrow();
    }

    private static Result run(String... arguments) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int exit = Main.run(arguments, new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Result(exit, errors.toString(StandardCharsets.UTF_8));
    }

    private record Write(int frame, int port, int register, int value) {}
    private record Observation(List<Write> writes, byte[] pcm) {}
    private record Result(int exit, String err) {}
}
