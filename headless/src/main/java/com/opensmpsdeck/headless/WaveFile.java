package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.openggf.audio.synth.ChipWriteObserver;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Fixed-length stereo PCM WAV written one presentation at a time. */
public final class WaveFile {
    private WaveFile() {}

    public static void render(byte[] song, Path output, int presentations) throws IOException {
        render(new CompiledProject(S3kBinary.validateAndCopy(song), List.of()), output, presentations);
    }

    public static void render(CompiledProject project, Path output, int presentations) throws IOException {
        S3kRenderer.validatePresentations(presentations);
        S3kBinary.validateAndCopy(project);
        write(output, presentations, sink -> S3kRenderer.render(project, presentations, sink));
    }

    public record Range(int start, int end) {
        public Range {
            S3kRenderer.validatePresentations(end);
            if (start < 0 || start >= end) throw new IllegalArgumentException("Range is empty at 60 Hz");
        }

        public int count() { return end - start; }

        public static Range seconds(double start, double end) {
            int rate = S3kRenderer.PRESENTATIONS_PER_SECOND;
            if (!(start >= 0 && end > start && end <= S3kRenderer.MAX_PRESENTATIONS / (double) rate))
                throw new IllegalArgumentException("Expected 0 <= start < end <= 600 seconds");
            return new Range((int) Math.round(start * rate), (int) Math.round(end * rate));
        }
    }

    public static void renderRange(CompiledProject project, Path output, Range range, S3kRenderer.ChannelMask mask)
            throws IOException {
        S3kBinary.validateAndCopy(project);
        writeRange(output, range, sink -> S3kRenderer.render(project, range.end(), mask, sink));
    }

    public static void renderWithPreviousMusic(CompiledProject previous, int previousPresentations,
                                               CompiledProject cue, Path output, int presentations) throws IOException {
        renderWithPreviousMusic(previous, previousPresentations, cue, output, new Range(0, presentations),
                S3kRenderer.ChannelMask.NONE);
    }

    static void renderWithPreviousMusic(CompiledProject previous, int previousPresentations, CompiledProject cue,
                                        Path output, Range range, S3kRenderer.ChannelMask mask) throws IOException {
        S3kRenderer.validatePreviousPresentations(previousPresentations);
        S3kBinary.validateAndCopy(previous);
        if (cue.nativeProgram() == null) S3kBinary.validateAndCopy(cue);
        else S3kBinary.inspectNative(cue, true);
        writeRange(output, range, sink -> S3kRenderer.renderWithPreviousMusic(previous, previousPresentations, cue,
                range.end(), mask, sink, ChipWriteObserver.NONE));
    }

    private static void writeRange(Path output, Range range, Renderer renderer) throws IOException {
        write(output, range.count(), sink -> {
            int[] frame = {0};
            renderer.render(samples -> {
                if (frame[0]++ >= range.start()) sink.accept(samples);
            });
        });
    }

    @FunctionalInterface
    public interface Renderer {
        void render(S3kRenderer.PcmSink sink) throws IOException;
    }

    public static void write(Path output, int presentations, Renderer renderer) throws IOException {
        S3kRenderer.validatePresentations(presentations);
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
            renderer.render(pcm -> {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("WAV export cancelled");
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
