package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceAudioTest {
    @TempDir Path folder;

    @Test void seeksOnlyTheRequestedFloatFramesAndAveragesStereo() throws Exception {
        int rate = 8_000;
        var data = littleEndian(rate * 8 * 6);
        data.putFloat(Float.NaN); // A different part of the stem need not be decoded.
        data.position(rate * 8 * 4);
        data.putFloat(1.75f).putFloat(0.75f).putFloat(-0.75f).putFloat(0.25f);
        var stem = wave("stem.wav", 3, 2, rate, 32, data.array(), false);

        var requiresSlice = assertThrows(IOException.class, () -> ReferenceAudio.read(stem, 0, null));
        assertTrue(requiresSlice.getMessage().contains("--start and --duration"));
        var audio = ReferenceAudio.read(stem, 4, 2.0 / rate);
        assertEquals(rate, audio.sampleRate());
        assertArrayEquals(new float[]{1.25f, -0.25f}, audio.samples());
        assertThrows(IOException.class, () -> ReferenceAudio.read(stem, 5, 2.0));
    }

    @Test void rejectsSelectedNonfiniteFloatAndInvalidArguments() throws Exception {
        byte[] malformed = littleEndian(4).putFloat(Float.POSITIVE_INFINITY).array();
        var file = wave("bad-float.wav", 3, 1, 44_100, 32, malformed, true);
        var invalid = assertThrows(IOException.class, () -> ReferenceAudio.read(file, 0, null));
        assertTrue(invalid.getMessage().contains("nonfinite"));
        assertThrows(IllegalArgumentException.class, () -> ReferenceAudio.read(file, Double.NaN, null));
        assertThrows(IllegalArgumentException.class, () -> ReferenceAudio.read(file, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ReferenceAudio.read(file, 0, 3.01));
    }

    @Test void preservesHighPrecisionAndSignedPcmAtEverySupportedWidth() throws Exception {
        var pcm16 = littleEndian(4).putShort((short) -32768).putShort((short) 1).array();
        assertArrayEquals(new float[]{-1f, 1f / 32768},
                ReferenceAudio.read(wave("16.wav", 1, 1, 48_000, 16, pcm16, false), 0, null).samples());

        byte[] pcm24 = {0, 0, (byte) 0x80, 1, 0, 0};
        assertArrayEquals(new float[]{-1f, 1f / 8388608},
                ReferenceAudio.read(wave("24.wav", 1, 1, 48_000, 24, pcm24, true), 0, null).samples());

        var pcm32 = littleEndian(8).putInt(Integer.MIN_VALUE).putInt(1).array();
        assertArrayEquals(new float[]{-1f, (float) (1 / 2147483648.0)},
                ReferenceAudio.read(wave("32.wav", 1, 1, 48_000, 32, pcm32, false), 0, null).samples());
    }

    @Test void rejectsTruncatedDataAndMisalignedSampleFrames() throws Exception {
        var truncated = wave("truncated.wav", 1, 1, 48_000, 16, new byte[2], false);
        Files.write(truncated, java.util.Arrays.copyOf(Files.readAllBytes(truncated), 45));
        assertThrows(IOException.class, () -> ReferenceAudio.read(truncated, 0, null));
        var misaligned = wave("misaligned.wav", 1, 2, 48_000, 16, new byte[2], false);
        assertThrows(IOException.class, () -> ReferenceAudio.read(misaligned, 0, null));
    }

    private Path wave(String name, int format, int channels, int rate, int bits, byte[] data,
                      boolean extensible) throws IOException {
        int alignment = channels * bits / 8;
        int formatSize = extensible ? 40 : 16;
        int fileSize = 12 + 8 + formatSize + 8 + data.length + (data.length & 1);
        var wave = littleEndian(fileSize);
        tag(wave, "RIFF").putInt(fileSize - 8);
        tag(wave, "WAVE");
        tag(wave, "fmt ").putInt(formatSize).putShort((short) (extensible ? 0xFFFE : format))
                .putShort((short) channels).putInt(rate).putInt(rate * alignment)
                .putShort((short) alignment).putShort((short) bits);
        if (extensible) {
            wave.putShort((short) 22).putShort((short) bits).putInt(channels == 1 ? 4 : 3).putInt(format)
                    .put(new byte[]{0, 0, 0x10, 0, (byte) 0x80, 0, 0, (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71});
        }
        tag(wave, "data").putInt(data.length).put(data);
        return Files.write(folder.resolve(name), wave.array());
    }

    private static ByteBuffer littleEndian(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer tag(ByteBuffer data, String tag) {
        return data.put(tag.getBytes(StandardCharsets.US_ASCII));
    }
}
