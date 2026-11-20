package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionFastRuns.HOP;
import static com.opensmpsdeck.headless.ReconstructionFastRuns.Figure;
import static com.opensmpsdeck.headless.ReconstructionFastRuns.Step;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Finds repeated attacks in the partials of a transcribed held pitch. */
final class ReconstructionRetriggers {
    static final String REASON = "fast-retrigger";
    private static final double MIN_INTERVAL = .030;
    private static final double MAX_INTERVAL = .105;
    private static final int MIN_ATTACKS = 6;
    private static final int[] PARTIALS = {1, 2, 3, 4};

    private ReconstructionRetriggers() {}

    static List<Figure> detect(ReferenceAudio audio, List<Note> notes) {
        var figures = new ArrayList<Figure>();
        for (var note : notes) {
            if (note.run() != null || note.pitch() < 48 || note.pitch() > 92
                    || note.end() - note.start() < .24 || note.source().equals("external")) continue;
            // A quieter chord voice can share the lead's partials, especially its octave.
            if (notes.stream().anyMatch(other -> other.velocity() > note.velocity() + 4
                    && Math.abs(other.start() - note.start()) <= .030 && other.end() - other.start() >= .12)) continue;
            figures.addAll(find(audio, note.pitch(), note.start(), note.end()));
        }
        return figures.stream().sorted(java.util.Comparator.comparingDouble(Figure::start)).toList();
    }

    static List<Figure> find(ReferenceAudio audio, int pitch, double start, double end) {
        var envelope = partialEnvelope(audio, pitch, start, end);
        if (envelope.length < MIN_ATTACKS * MIN_INTERVAL / HOP) return List.of();
        double[] flux = new double[envelope.length];
        int window = 3;
        for (int i = window; i + window < envelope.length; i++)
            flux[i] = envelope[i + window] - envelope[i - window];
        var attacks = new ArrayList<Integer>();
        for (int i = window; i + window < flux.length; i++) {
            if (flux[i] < .13 || flux[i] <= flux[i - 1] || flux[i] < flux[i + 1]) continue;
            int previous = attacks.isEmpty() ? -100 : attacks.getLast();
            if ((i - previous) * HOP < MIN_INTERVAL * .82) {
                if (flux[i] > flux[previous]) attacks.set(attacks.size() - 1, i);
            } else attacks.add(i);
        }
        var figures = new ArrayList<Figure>();
        for (int first = 0; first + MIN_ATTACKS <= attacks.size();) {
            int last = first + 1;
            int firstFrame = attacks.get(first);
            int interval = attacks.get(last) - firstFrame;
            double error = 0;
            while (interval * HOP >= MIN_INTERVAL && interval * HOP <= MAX_INTERVAL && last + 1 < attacks.size()) {
                int next = attacks.get(last + 1) - attacks.get(last);
                double jitter = Math.abs(next - interval) * HOP;
                if (next * HOP < MIN_INTERVAL || next * HOP > MAX_INTERVAL || jitter > Math.max(.009, interval * HOP * .20)) break;
                error += jitter;
                last++;
            }
            if (last - first + 1 < MIN_ATTACKS) {
                first++;
                continue;
            }
            // Flux locates an entrance, and the preceding span can include the held note's first attack.
            var steps = new ArrayList<Step>();
            double period = (attacks.get(last) - firstFrame) * HOP / (last - first);
            double previous = start + firstFrame * HOP;
            for (int i = first; i <= last; i++) {
                double next = i < last ? start + attacks.get(i + 1) * HOP : Math.min(end, previous + period);
                if (next > previous) steps.add(new Step(pitch, previous, next));
                previous = next;
            }
            double confidence = Math.clamp(.99 - error / ((last - first) * period), .78, .99);
            figures.add(new Figure(List.copyOf(steps), confidence, REASON));
            first = last + 1;
        }
        return List.copyOf(figures);
    }

    /** Positive flux of the combined, level-normalized partial series, sampled at detector hops. */
    static double[] partialEnvelope(ReferenceAudio audio, int pitch, double start, double end) {
        int rate = audio.sampleRate();
        int width = Math.max(8, (int) Math.round(.016 * rate));
        float[] samples = audio.samples();
        int frames = (int) Math.ceil(Math.max(0, Math.min(end, samples.length / (double) rate) - start) / HOP);
        if (frames < 1) return new double[0];
        var envelopes = new double[PARTIALS.length][frames];
        for (int h : PARTIALS) {
            double frequency = h * ReconstructionLeadAudio.hz(pitch);
            if (frequency >= rate / 2.0) break;
            double[] cos = new double[width], sin = new double[width];
            for (int i = 0; i < width; i++) {
                double window = .5 - .5 * Math.cos(Math.TAU * i / (width - 1));
                double angle = Math.TAU * frequency * i / rate;
                cos[i] = window * Math.cos(angle);
                sin[i] = window * Math.sin(angle);
            }
            for (int frame = 0; frame < frames; frame++) {
                int first = (int) Math.round((start + frame * HOP) * rate) - width / 2;
                double re = 0, im = 0;
                for (int i = Math.max(0, -first), last = Math.min(width, samples.length - first); i < last; i++) {
                    re += samples[i + first] * cos[i];
                    im += samples[i + first] * sin[i];
                }
                envelopes[h - 1][frame] = Math.hypot(re, im) * 4 / width;
            }
        }
        var levels = Arrays.stream(envelopes).mapToDouble(band -> {
            var sorted = band.clone();
            Arrays.sort(sorted);
            return sorted[(int) (.90 * (sorted.length - 1))];
        }).toArray();
        double max = Arrays.stream(levels).max().orElse(0);
        var sum = new double[frames];
        double total = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] < Math.max(.002, max * .12)) continue;
            double weight = Math.sqrt(levels[i]);
            total += weight;
            for (int j = 0; j < frames; j++) sum[j] += weight * Math.min(1.5, envelopes[i][j] / levels[i]);
        }
        if (total > 0) for (int i = 0; i < sum.length; i++) sum[i] /= total;
        return sum;
    }
}
