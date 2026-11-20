package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.DoubleUnaryOperator;

import static com.opensmpsdeck.headless.S3kRenderer.SAMPLE_RATE;
import static com.opensmpsdeck.headless.S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;

/** Stereo WSOLA with a common source offset for both channels. */
public final class ReferenceStretch {
    private static final int HOP = SAMPLE_RATE / 50;
    private static final int SEARCH = SAMPLE_RATE / 125;

    private ReferenceStretch() {}

    /** outputToSource maps output seconds to source seconds. */
    public static void write(Path input, Path output, int presentations, DoubleUnaryOperator outputToSource)
            throws IOException {
        write(input, output, presentations, ReferenceAudio.durationMicros(input) / 1e6, outputToSource);
    }

    static void write(Path input, Path output, int presentations, double sourceDuration,
                      DoubleUnaryOperator outputToSource) throws IOException {
        write(input, output, presentations, sourceDuration, outputToSource, null, false);
    }

    /** Replays the mix's analysis-window positions on another input on the same clock. */
    public static final class Alignment {
        private final int presentations;
        private final int[] positions;

        private Alignment(int presentations) {
            this.presentations = presentations;
            int frames = presentations * STEREO_FRAMES_PER_PRESENTATION;
            positions = new int[Math.ceilDiv(frames, HOP)];
        }

        public void writeStem(Path input, Path output) throws IOException {
            write(input, output, presentations, ReferenceAudio.durationMicros(input) / 1e6, null, positions, true);
        }
    }

    public static Alignment writeAndCaptureAlignment(Path mix, Path output, int presentations,
                                                     DoubleUnaryOperator outputToSource) throws IOException {
        return writeAndCaptureAlignment(mix, output, presentations,
                ReferenceAudio.durationMicros(mix) / 1e6, outputToSource);
    }

    static Alignment writeAndCaptureAlignment(Path mix, Path output, int presentations,
                                              double sourceDuration, DoubleUnaryOperator outputToSource) throws IOException {
        S3kRenderer.validatePresentations(presentations);
        var alignment = new Alignment(presentations);
        write(mix, output, presentations, sourceDuration, outputToSource,
                alignment.positions, false);
        return alignment;
    }

    private static void write(Path input, Path output, int presentations, double sourceDuration,
                              DoubleUnaryOperator outputToSource, int[] positions, boolean reuse) throws IOException {
        var samples = new Source(input, sourceDuration);
        WaveFile.write(output, presentations, sink -> {
            int frames = presentations * STEREO_FRAMES_PER_PRESENTATION;
            var pcm = new short[STEREO_FRAMES_PER_PRESENTATION * 2];
            int previous = 0;
            for (int first = 0; first < frames; first += HOP) {
                int step = first / HOP;
                int selected;
                if (reuse) {
                    selected = positions[step];
                    samples.load(Math.min(previous + HOP, selected),
                            Math.max(previous + 2 * HOP, selected + HOP));
                } else {
                    int target = (int) Math.round(outputToSource.applyAsDouble(first / (double) SAMPLE_RATE) * SAMPLE_RATE);
                    samples.load(Math.min(previous + HOP, target - SEARCH),
                            Math.max(previous + 2 * HOP, target + SEARCH + HOP));
                    selected = first == 0 ? target : align(samples, previous + HOP, target);
                    if (positions != null) positions[step] = selected;
                }
                for (int i = 0; i < HOP && first + i < frames; i++) {
                    int slot = (first + i) % STEREO_FRAMES_PER_PRESENTATION;
                    double blend = first == 0 ? 1 : i / (double) HOP;
                    for (int channel = 0; channel < 2; channel++) {
                        float a = samples.at(previous + HOP + i, channel);
                        float b = samples.at(selected + i, channel);
                        double sample = a + (b - a) * blend;
                        pcm[slot * 2 + channel] = (short) Math.round(Math.clamp(sample, -1, 1) * Short.MAX_VALUE);
                    }
                    if (slot == STEREO_FRAMES_PER_PRESENTATION - 1) sink.accept(pcm);
                }
                previous = selected;
            }
        });
    }

    private static int align(Source samples, int previous, int target) {
        int best = target;
        double strength = correlation(samples, previous, target);
        for (int offset = -SEARCH; offset <= SEARCH; offset += 8) {
            int candidate = target + offset;
            double score = correlation(samples, previous, candidate) - .04 * Math.abs(offset) / SEARCH;
            if (score > strength) {
                best = candidate;
                strength = score;
            }
        }
        int coarse = best;
        for (int candidate = coarse - 7; candidate <= coarse + 7; candidate++) {
            if (Math.abs(candidate - target) > SEARCH) continue;
            double score = correlation(samples, previous, candidate) - .04 * Math.abs(candidate - target) / SEARCH;
            if (score > strength) {
                best = candidate;
                strength = score;
            }
        }
        return best;
    }

    private static double correlation(Source samples, int a, int b) {
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < HOP; i += 6) {
            for (int channel = 0; channel < 2; channel++) {
                double x = samples.at(a + i, channel), y = samples.at(b + i, channel);
                dot += x * y;
                aa += x * x;
                bb += y * y;
            }
        }
        return aa > 1e-8 && bb > 1e-8 ? dot / Math.sqrt(aa * bb) : 0;
    }

    private static final class Source {
        private final Path path;
        private final int frames;
        private int start;
        private float[] pcm = {};

        Source(Path path, double duration) {
            this.path = path;
            frames = (int) (duration * SAMPLE_RATE + 1e-8);
        }

        void load(int from, int to) throws IOException {
            from = Math.clamp(from, 0, frames);
            to = Math.clamp(to, from, frames);
            if (from >= start && to <= start + pcm.length / 2) return;
            start = from;
            int length = Math.min(frames - start, Math.max(SAMPLE_RATE, to - start));
            pcm = length == 0 ? new float[0] : ReferenceAudio.stereoRange(path, SAMPLE_RATE, start, length);
        }

        float at(int frame, int channel) {
            int i = (frame - start) * 2 + channel;
            return i >= 0 && i < pcm.length ? pcm[i] : 0;
        }
    }
}
