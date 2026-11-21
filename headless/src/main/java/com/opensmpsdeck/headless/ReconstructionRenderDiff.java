package com.opensmpsdeck.headless;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Compare saved PCM at matching project positions. */
final class ReconstructionRenderDiff {
    record Difference(boolean available, long changedFrames, Long firstFrame) {}

    private ReconstructionRenderDiff() {}

    static Difference diff(Path before, Path after, ReconstructionTrace.Span span) throws IOException {
        var old = before.resolve("render.wav");
        var updated = after.resolve("render.wav");
        var unavailable = new Difference(false, 0, null);
        if (!Files.isRegularFile(old) || !Files.isRegularFile(updated)) return unavailable;
        try (var a = AudioSystem.getAudioInputStream(old.toFile());
             var b = AudioSystem.getAudioInputStream(updated.toFile())) {
            var format = a.getFormat();
            if (!format.matches(b.getFormat()) || format.getFrameSize() <= 0) return unavailable;
            long start = 0, end = 0;
            if (span != null) {
                var oldTime = ReconstructionTimeMap.of(ReconstructionStages.readScore(before.resolve("score.json")));
                var newTime = ReconstructionTimeMap.of(ReconstructionStages.readScore(after.resolve("score.json")));
                end = Long.MAX_VALUE;
                if (span.start() != null)
                    start = (long) Math.ceil(Math.min(oldTime.project(span.start()), newTime.project(span.start()))
                            * format.getFrameRate() - 1e-8);
                if (span.end() != null)
                    end = (long) Math.ceil(Math.max(oldTime.project(span.end()), newTime.project(span.end()))
                            * format.getFrameRate() - 1e-8);
            }
            int frameSize = format.getFrameSize();
            // AudioInputStream reads whole sample frames.
            byte[] oldBytes = new byte[4096 * frameSize];
            byte[] newBytes = new byte[oldBytes.length];
            long changed = 0, frame = 0;
            Long first = null;
            while (true) {
                int oldSize = a.readNBytes(oldBytes, 0, oldBytes.length);
                int newSize = b.readNBytes(newBytes, 0, newBytes.length);
                int size = Math.max(oldSize, newSize);
                if (size == 0) break;
                for (int i = 0; i < size; i += frameSize, frame++) {
                    if (frame >= start && frame < end) continue;
                    if (i >= Math.min(oldSize, newSize)
                            || !Arrays.equals(oldBytes, i, i + frameSize, newBytes, i, i + frameSize)) {
                        if (first == null) first = frame;
                        changed++;
                    }
                }
            }
            return new Difference(true, changed, first);
        } catch (UnsupportedAudioFileException unsupported) {
            return unavailable;
        }
    }
}
