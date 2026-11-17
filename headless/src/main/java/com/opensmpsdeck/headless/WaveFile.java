package com.opensmpsdeck.headless;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Fixed-length stereo PCM WAV written one presentation at a time. */
final class WaveFile {
    private WaveFile() {}

    static void render(byte[] song, Path output, int presentations) throws IOException {
        S3kRenderer.validatePresentations(presentations);
        byte[] validated = S3kBinary.validateAndCopy(song);
        // CREATE_NEW also rejects symlinks and concurrent creators; never truncate a user's file.
        OutputStream created = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean complete = false;
        try {
          try (OutputStream out = new BufferedOutputStream(created)) {
            int bytes = presentations * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 4;
            ascii(out, "RIFF"); le32(out, 36 + bytes); ascii(out, "WAVEfmt ");
            le32(out, 16); le16(out, 1); le16(out, 2);
            le32(out, S3kRenderer.SAMPLE_RATE); le32(out, S3kRenderer.SAMPLE_RATE * 4);
            le16(out, 4); le16(out, 16); ascii(out, "data"); le32(out, bytes);
            byte[] encoded = new byte[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 4];
            S3kRenderer.render(validated, presentations, pcm -> {
                for (int i = 0; i < pcm.length; i++) {
                    encoded[i * 2] = (byte) pcm[i];
                    encoded[i * 2 + 1] = (byte) (pcm[i] >>> 8);
                }
                out.write(encoded);
            });
            out.flush();
          }
          complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(output);
        }
    }

    private static void ascii(OutputStream out, String text) throws IOException { out.write(text.getBytes(StandardCharsets.US_ASCII)); }
    private static void le16(OutputStream out, int value) throws IOException { out.write(value); out.write(value >>> 8); }
    private static void le32(OutputStream out, int value) throws IOException { le16(out, value); le16(out, value >>> 16); }
}
