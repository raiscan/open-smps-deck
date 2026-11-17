package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.openggf.audio.synth.ChipWriteObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DacPlaybackTest {
    @TempDir Path directory;

    @Test void compiledTriggersMatchHandwrittenStreamAndRestDoesNotCutOffTheSample() throws Exception {
        byte[] slowPcm = new byte[5_000];
        Arrays.fill(slowPcm, (byte) 0x34);
        slowPcm[0] = 0x33;
        var slow = DacSample.of("slow", "Slow", 0, slowPcm);
        var fast = DacSample.of("fast", "Fast", 1, bytes(0, 255, 1, 254));
        var project = project(140, List.of(slow, fast), List.of(
                new SampleTrigger("start", 0, "slow"), new SampleTrigger("replace", 130, "fast")));
        byte[] nativeBank = bank(0x81, 127, 0x80, 3, 0x82, 10, 0xF2);
        var compiled = ProjectCompiler.compileWithAssets(project);
        assertArrayEquals(nativeBank, compiled.song());
        var actual = observe(compiled, 145);
        var expected = observe(new CompiledProject(nativeBank, List.of(), List.of(slow, fast)), 145);
        assertEquals(expected.writes, actual.writes);
        assertEquals(expected.dac, actual.dac);
        assertArrayEquals(expected.pcm, actual.pcm);
        assertFalse(Arrays.equals(observe(new CompiledProject(bank(0xF2), List.of()), 145).pcm, actual.pcm));

        assertEquals(0x33, actual.dac.getFirst().value);
        assertTrue(actual.dac.stream().anyMatch(write -> write.frame == 128 && write.value == 0x34),
                "the previous hit must continue during the explicit 0x80 rest");
        assertEquals(List.of(0, 255, 1, 254), actual.dac.stream().filter(write -> write.frame >= 130)
                .map(DacWrite::value).toList(), "the replacement must cut off the first sample and play every byte");
        assertTrue(actual.writes.contains(new EnableWrite(0, 0x80)));
        assertTrue(actual.writes.stream().anyMatch(write -> write.frame >= 130 && write.frame < 133 && write.value == 0),
                "natural completion must disable DAC before the track stop");
        assertEquals(1_237, actual.dac.get(2).cycle - actual.dac.get(1).cycle, 1);
        var fastWrites = actual.dac.subList(actual.dac.size() - 4, actual.dac.size());
        assertEquals(53, fastWrites.get(2).cycle - fastWrites.get(1).cycle, 1);
    }

    @Test void cliAutomaticallyIncludesSlowTailAndHonorsExplicitFrames() throws Exception {
        byte[] pcm = new byte[2_500];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        var slow = DacSample.of("slow", "Slow", 0, pcm);
        var project = project(1, List.of(slow), List.of(new SampleTrigger("hit", 0, "slow")));
        Path source = Files.writeString(directory.resolve("tail.json"), ProjectFormat.write(project));
        Path full = directory.resolve("full.wav"), partial = directory.resolve("partial.wav");
        assertEquals(0, cli("render-project", source.toString(), full.toString()));
        assertEquals(0, cli("render-project", source.toString(), partial.toString(), "--frames", "3"));
        int presentations = ProjectRenderTiming.presentationCount(project);
        assertTrue(presentations > 120, "natural tail must extend the historical 120-presentation default");
        var compiled = ProjectCompiler.compileWithAssets(project);
        var observation = observe(compiled, presentations);
        assertEquals(pcm.length, observation.dac.size(), "the stop must leave room for the complete sample");
        assertTrue(observation.dac.getLast().frame > 120);
        byte[] fullWave = Files.readAllBytes(full);
        assertArrayEquals(observation.pcm, Arrays.copyOfRange(fullWave, 44, fullWave.length));
        byte[] partialWave = Files.readAllBytes(partial);
        assertEquals(44 + 3 * 800 * 4, partialWave.length);
        assertArrayEquals(Arrays.copyOfRange(fullWave, 44, partialWave.length),
                Arrays.copyOfRange(partialWave, 44, partialWave.length));
        Path raw = directory.resolve("incomplete.bin");
        assertEquals(2, cli("compile-project", source.toString(), raw.toString()));
        assertFalse(Files.exists(raw));
    }

    @Test void unresolvedMalformedOrInvalidDacAssetsFailBeforeOutput() throws Exception {
        var sample = DacSample.of("hit", "Hit", 255, bytes(1));
        Path output = directory.resolve("invalid.wav");
        for (var invalid : List.of(
                new CompiledProject(bank(0x81, 1, 0xF2), List.of()),
                new CompiledProject(bank(0x82, 1, 0xF2), List.of(), List.of(sample)),
                new CompiledProject(bank(0xF2), List.of(), List.of(new DacSample("bad", "Unused invalid", 1, "AQ"))),
                new CompiledProject(bank(0xF2), List.of(), List.of(sample, sample)),
                new CompiledProject(bank(0xF2), List.of(), List.of(new DacSample("bad", "Bad rate", 256, "AQ=="))))) {
            assertThrows(IllegalArgumentException.class, () -> WaveFile.render(invalid, output, 1));
            assertFalse(Files.exists(output));
        }
        for (int[] track : new int[][]{{0x81}, {0x81, 0, 0xF2}, {0x80, 128, 0xF2},
                {0x81, 1, 1, 0xF2}, {0xE7, 0x81, 1, 0xF2}, {0xFA, 0xF2}, {0x81, 1}}) {
            assertThrows(IllegalArgumentException.class, () -> S3kRenderer.render(
                    new CompiledProject(bank(track), List.of(), List.of(sample)), 1, pcm -> fail("must validate first")));
        }
        Path raw = Files.write(directory.resolve("needs-assets.bin"), bank(0x81, 1, 0xF2));
        assertEquals(2, cli("render", raw.toString(), output.toString()));
        assertFalse(Files.exists(output));
    }

    private static StudioProject project(int length, List<DacSample> samples, List<SampleTrigger> triggers) {
        var sequence = new Sequence("drums", "Drums", "DAC", length, null, 127, List.of(), List.of(),
                "RESET", null, null, triggers);
        return new StudioProject(4, "DAC playback fixture", "S3K", 24, 0, List.of(), List.of(sequence),
                List.of(new Clip("clip", "drums", "DAC", 0, length)), List.of(), samples);
    }

    private static byte[] bank(int... track) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(bytes(0, 0, 1, 0, 1, 0, 10, 0, 0, 0));
        output.writeBytes(bytes(track));
        return output.toByteArray();
    }

    private static byte[] bytes(int... values) {
        var output = new ByteArrayOutputStream();
        for (int value : values) output.write(value);
        return output.toByteArray();
    }

    private static int cli(String... arguments) {
        try (var diagnostics = new PrintStream(new ByteArrayOutputStream())) {
            return Main.run(arguments, diagnostics, diagnostics);
        }
    }

    private static Observation observe(CompiledProject song, int presentations) throws Exception {
        var pcm = new ByteArrayOutputStream();
        List<EnableWrite> writes = new ArrayList<>();
        List<DacWrite> dac = new ArrayList<>();
        int[] frame = {0};
        var observer = new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                if (port == 0 && register == 0x2B) writes.add(new EnableWrite(frame[0], value));
            }
            @Override public void onPsgWrite(int value) {}
            @Override public boolean observesPhysicalWrites() { return true; }
            @Override public void onYm2612BusWrite(long cycle, int busPort, int value, PhysicalWriteOrigin origin) {
                if (busPort == 1 && origin == PhysicalWriteOrigin.DAC_STREAM) {
                    dac.add(new DacWrite(frame[0], cycle, value));
                }
            }
        };
        S3kRenderer.render(song, presentations, samples -> {
            for (short sample : samples) {
                pcm.write(sample);
                pcm.write(sample >>> 8);
            }
            frame[0]++;
        }, observer);
        return new Observation(writes, dac, pcm.toByteArray());
    }

    private record EnableWrite(int frame, int value) {}
    private record DacWrite(int frame, long cycle, int value) {}
    private record Observation(List<EnableWrite> writes, List<DacWrite> dac, byte[] pcm) {}
}
