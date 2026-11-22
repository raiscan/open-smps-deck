package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.opensmpsdeck.project.ProjectCompiler.Encoding.COMPACT;
import static com.opensmpsdeck.project.ProjectCompiler.Encoding.LINEAR;
import static org.junit.jupiter.api.Assertions.*;

class CompactPlaybackTest {
    @Test void countedLoopsAndSharedSubroutinesPreservePcmAndChipWrites() throws Exception {
        var notes = new ArrayList<NoteEvent>();
        int[] pitches = {60, 61, 64, 60, 61, 64, 60, 61, 64, 72, 62, 63, 66, 68, 70, 75, 62, 63, 66, 68, 70};
        for (int i = 0; i < pitches.length; i++) notes.add(new NoteEvent("n" + i, 4 * i, 4, pitches[i]));
        var commands = List.of(new CommandEvent("pan", 2, NativeCommand.PAN, List.of(0x40)),
                new CommandEvent("mod", 4, NativeCommand.MODULATION, List.of(1, 1, 1, 4)));
        var voice = DemoProject.create().voices().getFirst();
        var fm = new Sequence("fm", "FM", "FM", 84, voice.id(), 100, notes, commands, "RESET");
        var dac = new Sequence("dac", "DAC", "DAC", 12, null, 127, List.of(), List.of(), "RESET", null, null,
                List.of(new SampleTrigger("hit", 0, "kick"), new SampleTrigger("other", 7, "kick")));
        var sample = DacSample.of("kick", "Kick", 35, new byte[]{-128, -90, -70, -100, -128, 60, 100, -128});
        var project = new StudioProject(4, "Compact fixture", "S3K", 24, 0, List.of(voice), List.of(fm, dac),
                List.of(new Clip("tones", "fm", "FM1", 0, 84), new Clip("drums", "dac", "DAC", 0, 84)),
                List.of(), List.of(sample));
        byte[] bank = ProjectCompiler.compile(project, COMPACT);
        int fmStart = word(bank, 10), voices = word(bank, 0);
        assertTrue(contains(bank, word(bank, 6), fmStart, 0xF7), "DAC loop");
        assertTrue(contains(bank, fmStart, voices, 0xF7), "FM loop");
        int firstCall = find(bank, fmStart, voices, 0xF8);
        int nextCall = find(bank, firstCall + 3, voices, 0xF8);
        assertEquals(word(bank, firstCall + 1), word(bank, nextCall + 1), "calls share a target");
        int target = word(bank, firstCall + 1);
        assertEquals(0xF2, Byte.toUnsignedInt(bank[target - 1]), "subroutine follows main stop");

        var badCall = bank.clone();
        badCall[firstCall + 1]++; // Into the subroutine's first duration.
        var wrongTarget = new CompiledProject(badCall, List.of(), List.of(sample));
        var targetFailure = assertThrows(IllegalArgumentException.class, () -> S3kBinary.validateAndCopy(wrongTarget));
        assertTrue(targetFailure.getMessage().contains("call target"));
        var badReturn = bank.clone();
        badReturn[target] = (byte) 0xF8; // A nested call is outside the compiler's standalone layout.
        var invalid = new CompiledProject(badReturn, List.of(), List.of(sample));
        var failure = assertThrows(IllegalArgumentException.class, () -> S3kBinary.validateAndCopy(invalid));
        assertTrue(failure.getMessage().contains("nested control flow"));

        var reused = bank.clone();
        reused[target + 1] = (byte) 0xE7; // The first note would reuse the caller's duration.
        var implicitDuration = new CompiledProject(reused, List.of(), List.of(sample));
        var durationFailure = assertThrows(IllegalArgumentException.class, () -> S3kBinary.validateAndCopy(implicitDuration));
        assertTrue(durationFailure.getMessage().contains("explicit durations"));
        assertEquals(0xF9, Byte.toUnsignedInt(bank[voices - 1]), "subroutine ends in return");
        assertTrue(bank.length < ProjectCompiler.compile(project, LINEAR).length);
        equivalent(project);
    }

    @Test void largeRepeatCountsUseIndependentLoopsAndCompleteAllIterations() throws Exception {
        var psg = new Sequence("s", "PSG", "PSG", 1, null, 96, List.of(new NoteEvent("n", 0, 1, 60)),
                List.of(), "CARRY");
        var project = new StudioProject(2, "Long repeat", "S3K", 24, 0, List.of(), List.of(psg),
                List.of(new Clip("clip", "s", "PSG1", 0, 9_000)));
        byte[] bank = ProjectCompiler.compile(project, COMPACT);
        int loopCount = 0;
        for (int i = word(bank, 10); i < bank.length; i++) {
            if (Byte.toUnsignedInt(bank[i]) != 0xF7) continue;
            assertEquals(0, bank[i + 1], "one counter slot");
            assertTrue(Byte.toUnsignedInt(bank[i + 2]) >= 2, "no infinite or empty count");
            assertTrue(word(bank, i + 3) < i, "backward bank-relative target");
            loopCount++;
            i += 4;
        }
        assertTrue(loopCount > 1);
        // Compare incrementally to avoid retaining two full long renders.
        assertEquals(fingerprint(project, LINEAR), fingerprint(project, COMPACT));
    }

    @Test void allRepositoryExamplesPreservePcmAndChipWrites() throws Exception {
        try (var files = Files.list(Path.of("..", "examples"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".smps.json")).sorted().toList())
                equivalent(ProjectFiles.load(file).project());
        }
    }

    private static void equivalent(StudioProject project) throws Exception {
        var linear = ProjectCompiler.compileWithAssets(project, LINEAR);
        var compact = ProjectCompiler.compileWithAssets(project, COMPACT);
        assertArrayEquals(compact.song(), ProjectCompiler.compile(project, COMPACT));
        var pcm = new ArrayList<short[]>();
        var writes = new Writes();
        int frames = ProjectRenderTiming.presentationCount(project);
        S3kRenderer.render(linear, frames, buffer -> pcm.add(buffer.clone()), writes);
        var compactWrites = new Writes();
        int[] frame = {0};
        S3kRenderer.render(compact, frames, buffer ->
                assertArrayEquals(pcm.get(frame[0]++), buffer, project.name()), compactWrites);
        assertEquals(frames, frame[0]);
        assertArrayEquals(writes.toByteArray(), compactWrites.toByteArray(), project.name());
        assertArrayEquals(writes.physical.digest(), compactWrites.physical.digest(), project.name());
    }

    private static String fingerprint(StudioProject project, ProjectCompiler.Encoding encoding) throws Exception {
        var hash = java.security.MessageDigest.getInstance("SHA-256");
        var writes = new Writes();
        var buffer = java.nio.ByteBuffer.allocate(3200).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project, encoding),
                ProjectRenderTiming.presentationCount(project), pcm -> {
                    buffer.asShortBuffer().put(pcm);
                    hash.update(buffer.array());
                }, writes);
        hash.update(writes.toByteArray());
        return java.util.HexFormat.of().formatHex(hash.digest(writes.physical.digest()));
    }

    private static final class Writes extends java.io.ByteArrayOutputStream implements ChipWriteObserver {
        private final java.security.MessageDigest physical;
        private final java.nio.ByteBuffer event = java.nio.ByteBuffer.allocate(12);

        Writes() throws java.security.NoSuchAlgorithmException {
            physical = java.security.MessageDigest.getInstance("SHA-256");
        }

        @Override public void onYm2612Write(int port, int register, int value) {
            write(port);
            write(register);
            write(value);
        }

        @Override public void onPsgWrite(int value) {
            write(2);
            write(value);
        }

        @Override public boolean observesPhysicalWrites() { return true; }

        @Override public void onYm2612BusWrite(long cycle, int port, int value, PhysicalWriteOrigin origin) {
            physicalWrite(0, cycle, port, value, origin.ordinal());
        }

        @Override public void onPsgBusWrite(long tick, int value) {
            physicalWrite(1, tick, 0, value, 0);
        }

        private void physicalWrite(int chip, long clock, int port, int value, int origin) {
            event.clear();
            event.put((byte) chip).putLong(clock).put((byte) port).put((byte) value).put((byte) origin);
            physical.update(event.array());
        }
    }

    private static int word(byte[] data, int at) {
        return Byte.toUnsignedInt(data[at]) | Byte.toUnsignedInt(data[at + 1]) << 8;
    }

    private static boolean contains(byte[] data, int start, int end, int value) {
        return java.util.stream.IntStream.range(start, end).anyMatch(i -> Byte.toUnsignedInt(data[i]) == value);
    }

    private static int find(byte[] data, int start, int end, int value) {
        return java.util.stream.IntStream.range(start, end).filter(i -> Byte.toUnsignedInt(data[i]) == value)
                .findFirst().orElseThrow();
    }
}
