package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.DacSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class DacSampleReaderTest {
    private static final byte[] NATIVE_MONO = hex("""
            52494646 28000000 57415645
            666d7420 10000000 0100 0100 f82f0000 f82f0000 0100 0800
            64617461 03000000 00 80 ff 00
            """);
    @TempDir Path directory;

    @Test void unsignedMonoAtTheNativeRateKeepsAllBytesAndIgnoresPadding() throws IOException {
        Path path = Files.write(directory.resolve("Kick.WAV"), NATIVE_MONO);
        DacSample sample = DacSampleReader.read(path, "kick");
        assertEquals("Kick", sample.name());
        assertEquals(0x0c, sample.rate());
        assertArrayEquals(hex("00 80 ff"), sample.pcm());
    }

    @Test void sixteenBitStereoMixesTheChannelsIncludingOppositeAndExtremeValues() throws IOException {
        byte[] stereo = hex("""
                52494646 30000000 57415645
                666d7420 10000000 0100 0200 f82f0000 e0bf0000 0400 1000
                64617461 0c000000 0080 ff7f 0080 0080 ff7f ff7f
                """);
        assertArrayEquals(hex("80 00 ff"), DacSampleReader.decodeWav("s", "Stereo", stereo).pcm());
    }

    @Test void resamplingChoosesTheClosestNativeRateAndKeepsDurationWithinHalfAFrame() throws IOException {
        byte[] source = hex("""
                52494646 28000000 57415645
                666d7420 10000000 0100 0100 007d0000 007d0000 0100 0800
                64617461 04000000 00 40 80 c0
                """);
        DacSample sample = DacSampleReader.decodeWav("s", "Resample", source);
        assertEquals(1, sample.rate());
        assertArrayEquals(hex("10 60 af"), sample.pcm());
        assertTrue(Math.abs(sample.pcm().length - 4 * sample.sampleRateHz() / 32000) <= 0.5);
        assertEquals(0, DacSampleReader.nearestRate(800));
    }

    @Test void downsamplingAveragesAnAlternatingSignalAcrossEachOutputFrame() throws IOException {
        byte[] source = hex("""
                52494646 28000000 57415645
                666d7420 10000000 0100 0100 80bb0000 80bb0000 0100 0800
                64617461 04000000 00 ff 00 ff
                """);
        assertArrayEquals(hex("7f 7f"), DacSampleReader.decodeWav("s", "Alternating", source).pcm());
    }

    @Test void rawPcmKeepsEveryByteAndEnforcesTheNativeByteLimit() throws IOException {
        byte[] data = new byte[DacSample.MAX_SAMPLE_BYTES];
        data[0] = (byte) 0xff;
        data[data.length - 1] = (byte) 0x80;
        Path path = Files.write(directory.resolve("Kick.bin"), data);
        DacSample sample = DacSampleReader.read(path, "k");
        assertEquals(0x0c, sample.rate());
        assertArrayEquals(data, sample.pcm());
        Files.write(path, new byte[data.length + 1]);
        assertThrows(IOException.class, () -> DacSampleReader.read(path, "k"));
        Files.write(path, new byte[0]);
        assertThrows(IOException.class, () -> DacSampleReader.read(path, "k"));
    }

    @Test void rejectsTruncationUnsupportedEncodingAndOversizedConvertedAudio() {
        assertThrows(IOException.class, () ->
                DacSampleReader.decodeWav("s", "Truncated", Arrays.copyOf(NATIVE_MONO, NATIVE_MONO.length - 1)));
        byte[] encoding = NATIVE_MONO.clone();
        encoding[20] = 3;
        assertThrows(IOException.class, () -> DacSampleReader.decodeWav("s", "Float", encoding));
        byte[] tooLong = hex("""
                52494646 26000000 57415645
                666d7420 10000000 0100 0100 01000000 01000000 0100 0800
                64617461 02000000 00 ff
                """);
        byte[] over = Arrays.copyOf(tooLong, 116);
        over[4] = 108;
        over[40] = 72;
        assertThrows(IOException.class, () -> DacSampleReader.decodeWav("s", "Over", over));
    }

    private static byte[] hex(String text) {
        return HexFormat.of().parseHex(text.replaceAll("\\s", ""));
    }
}
