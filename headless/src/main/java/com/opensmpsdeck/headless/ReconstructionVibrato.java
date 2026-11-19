package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Looks for repeatable pitch oscillation in isolated, held score notes. */
final class ReconstructionVibrato {
    private static final long EDGE_US = 100_000;
    private static final long MIN_ANALYSIS_US = 550_000;
    private static final long MAX_ANALYSIS_US = 2_500_000;
    private static final double MIN_RATE = 3.5;
    private static final double MAX_RATE = 10;
    private static final double LOG_TO_CENTS = 1_200 / Math.log(2);

    private ReconstructionVibrato() {}

    record Estimate(double rateHz, double depthCents, int supportingNotes, String source, boolean stem) {}

    private record Motion(double rateHz, double depthCents) {}

    static Map<Integer, Estimate> detect(MidiScore score, Path mix, Map<Integer, Path> stems, long limitUs)
            throws IOException {
        if (limitUs <= 0) {
            return Map.of();
        }
        Path mixSource = mix == null ? null : mix.toAbsolutePath().normalize();
        var stemSources = new HashMap<Integer, Path>();
        stems.forEach((part, path) -> stemSources.put(part, path.toAbsolutePath().normalize()));
        var ordered = score.notes().stream().sorted(Comparator.comparingLong(MidiScore.Note::startUs)).toList();
        var durations = new HashMap<Path, Long>();
        var result = new HashMap<Integer, Estimate>();
        for (var part : score.parts()) {
            Path source = stemSources.getOrDefault(part.id(), mixSource);
            if (part.drums() || source == null) {
                continue;
            }
            boolean stem = !source.equals(mixSource);
            var sourceNotes = ordered.stream().filter(note -> !stem || note.partId() == part.id()
                    || source.equals(stemSources.get(note.partId()))).toList();
            var motions = new ArrayList<Motion>();
            long precedingEnd = 0;
            for (int i = 0; i < sourceNotes.size(); i++) {
                var note = sourceNotes.get(i);
                boolean clear = precedingEnd <= note.startUs() && (i + 1 == sourceNotes.size()
                        || sourceNotes.get(i + 1).startUs() >= note.endUs());
                precedingEnd = Math.max(precedingEnd, note.endUs());
                if (!clear || note.partId() != part.id() || note.endUs() - note.startUs() < MIN_ANALYSIS_US + 2 * EDGE_US
                        || note.endUs() > limitUs) {
                    continue;
                }
                Long duration = durations.get(source);
                if (duration == null) {
                    duration = ReferenceAudio.durationMicros(source);
                    durations.put(source, duration);
                }
                if (note.endUs() <= duration) {
                    Motion motion = measure(note, source, stem);
                    if (motion != null) {
                        motions.add(motion);
                    }
                }
            }
            Estimate estimate = agree(motions, source, stem);
            if (estimate != null) {
                result.put(part.id(), estimate);
            }
        }
        return Map.copyOf(result);
    }

    private static Estimate agree(List<Motion> motions, Path source, boolean stem) {
        if (motions.size() < 2) {
            return null;
        }
        double minRate = motions.stream().mapToDouble(Motion::rateHz).min().orElseThrow();
        double maxRate = motions.stream().mapToDouble(Motion::rateHz).max().orElseThrow();
        double minDepth = motions.stream().mapToDouble(Motion::depthCents).min().orElseThrow();
        double maxDepth = motions.stream().mapToDouble(Motion::depthCents).max().orElseThrow();
        if (maxRate - minRate > .65 || maxDepth / minDepth > 2.2) {
            return null;
        }
        return new Estimate(median(motions.stream().mapToDouble(Motion::rateHz).sorted().toArray()),
                median(motions.stream().mapToDouble(Motion::depthCents).sorted().toArray()),
                motions.size(), source.getFileName().toString(), stem);
    }

    private static double median(double[] values) {
        int middle = values.length / 2;
        return (values[(values.length - 1) / 2] + values[middle]) / 2;
    }

    private static Motion measure(MidiScore.Note note, Path source, boolean stem) throws IOException {
        double carrierHz = 440 * Math.pow(2, (note.pitch() - 69) / 12.0);
        if (carrierHz < 75 || carrierHz > 2_000) {
            return null;
        }
        long span = Math.min(MAX_ANALYSIS_US, note.endUs() - note.startUs() - 2 * EDGE_US);
        var audio = ReferenceAudio.read(source, (note.startUs() + EDGE_US) / 1_000_000.0, span / 1_000_000.0);
        int sampleRate = audio.sampleRate();
        if (sampleRate < 8 * carrierHz) {
            return null;
        }
        // Keep pitch deviations within one unambiguous phase difference at high carriers.
        int hop = Math.max(1, (int) (sampleRate / Math.max(125, carrierHz / 7)));
        double hopSeconds = hop / (double) sampleRate;
        int window = (int) (sampleRate * Math.max(.014, 3 / carrierHz));
        int frames = 1 + (audio.samples().length - window) / hop;
        double[] cents = new double[frames - 1];
        double[] envelope = new double[frames - 1];
        var projection = new Projection(window, carrierHz / sampleRate);
        double expectedPhase = 2 * Math.PI * carrierHz * hopSeconds;
        double lastPhase = 0;
        double lastLevel = 0;
        for (int frame = 0; frame < frames; frame++) {
            Carrier carrier = projection.measure(audio.samples(), frame * hop);
            if (carrier.coherence() < (stem ? .47 : .68) || carrier.level() < .004) {
                return null;
            }
            if (frame > 0) {
                double phase = Math.IEEEremainder(carrier.phase() - lastPhase - expectedPhase, 2 * Math.PI);
                double value = LOG_TO_CENTS * Math.log1p(phase / expectedPhase);
                if (!Double.isFinite(value) || Math.abs(value) > 95) {
                    return null;
                }
                cents[frame - 1] = value;
                envelope[frame - 1] = (carrier.level() + lastLevel) / 2;
            }
            lastPhase = carrier.phase();
            lastLevel = carrier.level();
        }
        return fitMotion(cents, envelope, hopSeconds, window / (double) sampleRate);
    }

    private static Motion fitMotion(double[] cents, double[] envelope, double hop, double window) {
        double drift = removeLine(cents);
        double energy = dot(cents, cents);
        if (energy < cents.length * 18 || Math.abs(drift) > 28) {
            return null;
        }
        double[] sine = new double[cents.length];
        double[] cosine = new double[cents.length];
        double bestExplained = 0;
        double bestDepth = 0;
        double bestRate = 0;
        for (int step = 0; step <= (int) Math.round((MAX_RATE - MIN_RATE) * 20); step++) {
            double rate = MIN_RATE + step / 20.0;
            if (rate * hop * cents.length < 2.7) {
                continue;
            }
            for (int i = 0; i < cents.length; i++) {
                double phase = 2 * Math.PI * rate * hop * i;
                sine[i] = Math.sin(phase);
                cosine[i] = Math.cos(phase);
            }
            removeLine(sine);
            removeLine(cosine);
            double ss = dot(sine, sine);
            double cc = dot(cosine, cosine);
            double sc = dot(sine, cosine);
            double ys = dot(cents, sine);
            double yc = dot(cents, cosine);
            double determinant = ss * cc - sc * sc;
            double a = (ys * cc - yc * sc) / determinant;
            double b = (yc * ss - ys * sc) / determinant;
            double explained = (a * ys + b * yc) / energy;
            if (explained > bestExplained) {
                bestExplained = explained;
                bestRate = rate;
                bestDepth = Math.hypot(a, b);
            }
        }
        // Closely spaced simultaneous tones can imitate vibrato as their beating changes phase and level.
        double averageLevel = Arrays.stream(envelope).average().orElseThrow();
        removeLine(envelope);
        double levelEnergy = dot(envelope, envelope);
        boolean beating = levelEnergy > envelope.length * Math.pow(.025 * averageLevel, 2)
                && Math.abs(dot(cents, envelope)) > .8 * Math.sqrt(energy * levelEnergy);
        if (beating || bestExplained < .82 || bestDepth < 8) {
            return null;
        }
        // Compensate for the Hann window and the time between phase measurements.
        double windowCycles = bestRate * window;
        double depth = bestDepth * (1 - windowCycles * windowCycles) / (sinc(windowCycles) * sinc(bestRate * hop));
        return new Motion(bestRate, depth);
    }

    private static double sinc(double cycles) {
        double radians = Math.PI * cycles;
        return Math.sin(radians) / radians;
    }

    /** Detrends a series and returns the fitted change across its duration. */
    private static double removeLine(double[] values) {
        double center = (values.length - 1) / 2.0;
        double sum = 0;
        double moment = 0;
        double square = 0;
        for (int i = 0; i < values.length; i++) {
            double time = i - center;
            sum += values[i];
            moment += time * values[i];
            square += time * time;
        }
        double mean = sum / values.length;
        double slope = moment / square;
        for (int i = 0; i < values.length; i++) {
            values[i] -= mean + slope * (i - center);
        }
        return 2 * center * slope;
    }

    private static double dot(double[] left, double[] right) {
        double value = 0;
        for (int i = 0; i < left.length; i++) {
            value += left[i] * right[i];
        }
        return value;
    }

    private record Carrier(double phase, double level, double coherence) {}

    private static final class Projection {
        private final double[] weights;
        private final double[] real;
        private final double[] imaginary;
        private final double weightSum;

        private Projection(int window, double carrier) {
            weights = new double[window];
            real = new double[window];
            imaginary = new double[window];
            double sum = 0;
            for (int i = 0; i < window; i++) {
                double weight = .5 - .5 * Math.cos(2 * Math.PI * i / (window - 1));
                double phase = 2 * Math.PI * carrier * i;
                weights[i] = weight;
                real[i] = weight * Math.cos(phase);
                imaginary[i] = -weight * Math.sin(phase);
                sum += weight;
            }
            weightSum = sum;
        }

        private Carrier measure(float[] audio, int start) {
            double x = 0;
            double y = 0;
            double energy = 0;
            for (int i = 0; i < weights.length; i++) {
                double value = audio[start + i];
                x += value * real[i];
                y += value * imaginary[i];
                energy += value * value * weights[i];
            }
            double square = x * x + y * y;
            double coherence = energy == 0 ? 0 : 2 * square / (energy * weightSum);
            return new Carrier(Math.atan2(y, x), 2 * Math.sqrt(square) / weightSum, coherence);
        }
    }
}
