package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class S3kRendererTest {
    @TempDir Path directory;

    @Test void fmAndPsgBothProduceDeterministicAudioAtPresentationCadence() throws Exception {
        byte[] song = S3kDemo.song();
        byte[] fmOnly = song.clone();
        fmOnly[read16(song, 14)] = (byte) 0xF2;
        byte[] psgOnly = song.clone();
        psgOnly[read16(song, 10)] = (byte) 0xF2;
        byte[] stopped = song.clone();
        stopped[read16(song, 10)] = (byte) 0xF2;
        stopped[read16(song, 14)] = (byte) 0xF2;
        byte[] silence = render(stopped, 12);
        assertTrue(nonzero(render(fmOnly, 12)), "FM must sound without PSG");
        assertTrue(nonzero(render(psgOnly, 12)), "PSG must sound without FM");
        assertFalse(Arrays.equals(silence, render(fmOnly, 12)), "FM must differ from the core's stopped/DC baseline");
        assertFalse(Arrays.equals(silence, render(psgOnly, 12)), "PSG must differ from the core's stopped/DC baseline");
        assertArrayEquals(render(song, 12), render(song, 12));
        int[] calls = {0};
        S3kRenderer.render(song, 3, pcm -> {
            assertEquals(1600, pcm.length, "one 800-stereo-sample hardware presentation");
            calls[0]++;
        });
        assertEquals(3, calls[0]);
    }

    @Test void productionDemoMatchesIndependentlyRenderedOriginalUpstream() throws Exception {
        // Independently measured against the exact upstream source at the
        // lockfile pin, using the production activation and hardware cadence.
        byte[] pcm = render(S3kDemo.song(), 120);
        assertEquals("acc6a3d5f35d293356edf1efb08401734348b41f2eb2b673e1a7c01128100451",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm)));
    }

    @Test void demoWritesBoundedStereoWave() throws Exception {
        Path output = directory.resolve("demo.wav");
        assertEquals(0, Main.run(new String[]{"demo", output.toString(), "--frames", "3"}, System.out, System.err));
        byte[] wav = Files.readAllBytes(output);
        assertEquals(44 + 3 * 800 * 4, wav.length);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, header.getShort(22));
        assertEquals(48000, header.getInt(24));
        assertEquals(16, header.getShort(34));
        assertEquals(3 * 800 * 4, header.getInt(40));
    }

    @Test void malformedAndExternalResourceInputsFailBeforeRendering() {
        assertInvalid(new byte[6], "header");
        byte[] bad = S3kDemo.song(); bad[2] = 8; assertInvalid(bad, "channel");
        bad = S3kDemo.song(); bad[6] = 1; assertInvalid(bad, "pointer");
        bad = S3kDemo.song(); bad[18] = 1; assertInvalid(bad, "modulation");
        bad = S3kDemo.song(); bad[19] = 1; assertInvalid(bad, "envelope");
        bad = S3kDemo.song(); bad[read16(bad, 6)] = (byte) 0x81; assertInvalid(bad, "DAC");
        bad = S3kDemo.song(); bad[0] = 0; bad[1] = 0; assertInvalid(bad, "voice");
        bad = S3kDemo.song(); bad[read16(bad, 10)] = (byte) 0xF6; assertInvalid(bad, "unsupported");
        bad = S3kDemo.song(); bad[read16(bad, 10) + 1] = 1; assertInvalid(bad, "voice");
        bad = S3kDemo.song(); bad[read16(bad, 14) - 1] = (byte) 0xE1; assertInvalid(bad, "truncated");
        bad = S3kDemo.song(); bad[read16(bad, 14) - 1] = 1; assertInvalid(bad, "stop");
        assertInvalid(new byte[32769], "bank");
        byte[] song = S3kDemo.song();
        assertThrows(IllegalArgumentException.class, () -> S3kRenderer.render(song, 0, ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> S3kRenderer.render(song, 36001, ignored -> {}));
    }

    @Test void cliErrorsPreserveInputAndExistingOutput() throws Exception {
        Path input = directory.resolve("song.bin"); Files.write(input, S3kDemo.song());
        byte[] before = Files.readAllBytes(input);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"render", input.toString(), input.toString()}, System.out, new PrintStream(errors)));
        assertArrayEquals(before, Files.readAllBytes(input));
        assertFalse(errors.toString().isBlank());
        Path output = directory.resolve("exists.wav"); Files.writeString(output, "keep me");
        assertEquals(2, Main.run(new String[]{"demo", output.toString()}, System.out, new PrintStream(errors)));
        assertEquals("keep me", Files.readString(output));
        Path invalid = directory.resolve("invalid.wav");
        assertEquals(2, Main.run(new String[]{"demo", invalid.toString(), "--frames", "999999"}, System.out, new PrintStream(errors)));
        assertFalse(Files.exists(invalid));
    }

    @Test void cancelledWaveExportRemovesItsPartialOutput() throws Exception {
        Path output=directory.resolve("cancelled.wav");
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.io.IOException.class,()->WaveFile.render(S3kDemo.song(),output,120));
        } finally { Thread.interrupted(); }
        assertFalse(Files.exists(output),"cancelled export must not leave a truncated WAV");
    }

    private static void assertInvalid(byte[] song, String message) {
        var failure = assertThrows(IllegalArgumentException.class, () -> S3kRenderer.render(song, 1, ignored -> fail("must validate first")));
        assertTrue(failure.getMessage().toLowerCase().contains(message.toLowerCase()), failure.getMessage());
    }
    private static int read16(byte[] data, int at) { return (data[at] & 255) | ((data[at + 1] & 255) << 8); }
    private static boolean nonzero(byte[] pcm) { for (byte b : pcm) if (b != 0) return true; return false; }
    private static byte[] render(byte[] song, int frames) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        S3kRenderer.render(song, frames, pcm -> {
            for (short sample : pcm) { output.write(sample); output.write(sample >>> 8); }
        });
        return output.toByteArray();
    }
}
