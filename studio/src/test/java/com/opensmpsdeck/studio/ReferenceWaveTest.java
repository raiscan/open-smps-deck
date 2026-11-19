package com.opensmpsdeck.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceWaveTest {
    @TempDir Path directory;

    @Test void pcm16MonoInterpolatesAndUsesTheSameSampleInBothChannels() throws Exception {
        var wave = ReferenceWave.read(wav(directory.resolve("mono.wav"), 16, 1, 24_000, -16384, 16384));
        assertEquals(2 / 24_000.0, wave.duration());
        assertEquals(-0.5, wave.sample(0, 0), 1e-6);
        assertEquals(0, wave.sample(1 / 48_000.0, 1), 1e-6);
        assertEquals(0.5, wave.sample(1 / 24_000.0, 1), 1e-6);
        assertEquals(0, wave.sample(-1, 0), 1e-6);
        assertEquals(0, wave.sample(wave.duration(), 0), 1e-6);
    }

    @Test void pcm24StereoRetainsBothChannelsAndPeaks() throws Exception {
        var wave = ReferenceWave.read(wav(directory.resolve("stereo.wav"), 24, 2, 96_000,
                -8388608, 4194304, 0, 8388607));
        assertEquals(-1, wave.sample(0, 0), 1e-6);
        assertEquals(0.5, wave.sample(0, 1), 1e-6);
        var peaks = wave.overview(30);
        assertEquals(-1, peaks.minimum()[0], 1e-6);
        assertEquals(1, peaks.maximum()[0], 1e-6);
    }

    static Path wav(Path path, int bits, int channels, int rate, int... samples) throws Exception {
        int size = samples.length * (bits / 8);
        var header = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + size);
        header.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16);
        header.putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * bits / 8);
        header.putShort((short) (channels * bits / 8)).putShort((short) bits);
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(size);
        for (int sample : samples) {
            for (int shift = 0; shift < bits; shift += 8) header.put((byte) (sample >> shift));
        }
        return Files.write(path, header.array());
    }
}
