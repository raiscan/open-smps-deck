package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Short, discrete pitch figures on aligned stems, in source seconds until the tempo warp. */
final class ReconstructionFastRuns {
    static final String REASON = "fast-run";
    static final double HOP = .0025;
    private static final double MIN_DWELL = .0125;
    private static final double MAX_DWELL = .105;
    private static final double MAX_GAP = .022;
    private static final int MIN_STEPS = 4;

    record Step(int pitch, double start, double end) {
        double duration() { return end - start; }

        Step map(DoubleUnaryOperator clock) {
            return new Step(pitch, clock.applyAsDouble(start), clock.applyAsDouble(end));
        }
    }

    record Figure(List<Step> steps, double confidence, String reason) {
        Figure(List<Step> steps, double confidence) { this(steps, confidence, REASON); }
        double start() { return steps.getFirst().start; }
        double end() { return steps.getLast().end; }
    }

    record Ridge(double pitch, double confidence, double level) {}
    private record Plateau(int pitch, int first, int limit, double confidence) {}

    private ReconstructionFastRuns() {}

    static void reconcile(List<Note> notes, int part, List<Figure> figures) {
        reconcile(notes, part, figures, new ArrayList<>());
    }

    static void reconcile(List<Note> notes, int part, List<Figure> figures,
                          List<ReconstructionStages.ScoreDecision> decisions) {
        var ids = new HashSet<String>();
        for (var note : notes) ids.add(note.id());
        for (var figure : figures) {
            if (figure.confidence < .78 || notes.stream().anyMatch(note -> note.run() != null && note.part() == part
                    && note.start() < figure.end() && note.end() > figure.start())) continue;
            var overlapping = notes.stream().filter(note -> note.part() == part && note.run() == null
                    && !note.source().equals("external") && note.end() > figure.start() && note.start() < figure.end()).toList();
            var first = figure.steps.getFirst();
            Note held = overlapping.stream().filter(note -> note.pitch() == first.pitch
                            && Math.min(note.end(), figure.end()) - Math.max(note.start(), figure.start())
                            >= .72 * (figure.end() - figure.start()))
                    .max(Comparator.comparingDouble(note -> Math.min(note.end(), figure.end())
                            - Math.max(note.start(), figure.start()))).orElse(null);
            if (held == null) continue;
            // Periodicity can follow a harmonic or a backing voice. Require an
            // independently transcribed attack at the actual register of every new pitch.
            if (figure.steps.stream().anyMatch(step -> step.pitch != held.pitch()
                    && overlapping.stream().noneMatch(note -> note.pitch() == step.pitch
                    && Math.abs(note.start() - step.start) <= .035
                    && Math.min(note.end(), step.end) - Math.max(note.start(), step.start) >= .60 * step.duration()))) continue;
            // Explicit independently timed attacks take precedence over a held layer.
            long separate = figure.steps.stream().skip(1).filter(step -> overlapping.stream().anyMatch(note ->
                    note != held && note.pitch() == step.pitch && Math.abs(note.start() - step.start) <= .020)).count();
            if (separate >= 2) continue;
            notes.remove(held);
            if (figure.start() - held.start() >= 1.0 / 60)
                notes.add(held.withTiming(held.start(), figure.start()));
            if (held.end() - figure.end() >= 1.0 / 60) {
                notes.add(new Note(unique(ids, held.id() + "-after-run"), part, figure.end(), held.end(),
                        held.pitch(), held.velocity(), held.source(), held.confidence()));
            }
            String figureId = unique(ids, held.id() + "-run");
            notes.add(new Note(figureId, part, figure.start(), figure.end(), first.pitch,
                    held.velocity(), figure.reason, figure.confidence, null, figure.steps));
            decisions.add(new ReconstructionStages.ScoreDecision(held, figure.reason, figureId));
        }
    }

    private static String unique(HashSet<String> ids, String prefix) {
        String id = prefix;
        for (int suffix = 2; !ids.add(id); suffix++) id = prefix + "-" + suffix;
        return id;
    }

    record Frames(int pitch, int start, int end, int firstStep) {}

    static List<Frames> quantize(List<Step> figure, int start, int end) {
        var steps = new ArrayList<>(figure);
        var indexes = new ArrayList<Integer>();
        for (int i = 0; i < figure.size(); i++) indexes.add(i);
        // Remove the shortest steps only when the rounded span cannot hold every attack.
        while (steps.size() > end - start) {
            int shortest = 0;
            for (int i = 1; i < steps.size(); i++)
                if (steps.get(i).duration() < steps.get(shortest).duration()) shortest = i;
            var removed = steps.remove(shortest);
            indexes.remove(shortest);
            if (shortest == 0) {
                var next = steps.getFirst();
                steps.set(0, new Step(next.pitch, removed.start, next.end));
            } else {
                var prior = steps.get(shortest - 1);
                steps.set(shortest - 1, new Step(prior.pitch, prior.start, removed.end));
            }
        }
        var frames = new ArrayList<Frames>();
        for (int i = 0; i < steps.size(); i++) {
            int stop = i + 1 == steps.size() ? end
                    : Math.clamp((int) Math.round(steps.get(i).end * 60), start + 1, end - (steps.size() - i - 1));
            frames.add(new Frames(steps.get(i).pitch, start, stop, indexes.get(i)));
            start = stop;
        }
        return List.copyOf(frames);
    }

    static List<Figure> detect(ReferenceAudio audio) {
        return figures(ridge(audio), audio.samples().length / (double) audio.sampleRate());
    }

    static List<Figure> detect(ReferenceAudio audio, boolean layered) {
        var figures = new ArrayList<>(detect(audio));
        if (layered) {
            for (var figure : figures(upperRidge(audio), audio.samples().length / (double) audio.sampleRate())) {
                if (figures.stream().noneMatch(existing -> existing.start() < figure.end() && figure.start() < existing.end()))
                    figures.add(figure);
            }
        }
        figures.sort(Comparator.comparingDouble(Figure::start));
        return List.copyOf(figures);
    }

    /**
     * Look above the pad for a coherent fundamental and octave. Use interpolated peak observations
     * for both partials and require the two to move together. Keep the unrounded pitch for the
     * plateau screen, so a continuous glide cannot qualify just by crossing note centers.
     */
    static Ridge[] upperRidge(ReferenceAudio audio) {
        int rate = audio.sampleRate();
        var samples = audio.samples();
        var frames = new Ridge[(int) Math.ceil(samples.length / (double) rate / HOP)];
        var spectrum = new LeadEffectMatcher.Spectrum();
        var window = new double[(int) Math.min(spectrum.real.length, Math.round(.026 * rate))];
        for (int i = 0; i < window.length; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (window.length - 1));
        double binHz = rate / (double) spectrum.real.length;
        var amplitudes = new double[128];
        var pitches = new double[128];
        for (int i = 0; i < frames.length; i++) {
            int first = (int) Math.round(i * HOP * rate) - window.length / 2;
            Ridge best = new Ridge(0, 0, 0);
            if (first >= 0 && first + window.length <= samples.length) {
                spectrum.transform(samples, first, window);
                Arrays.fill(amplitudes, 0);
                int low = (int) (ReconstructionLeadAudio.hz(58) / binHz);
                int high = Math.min(spectrum.real.length / 2 - 1, (int) (ReconstructionLeadAudio.hz(104) / binHz));
                for (int k = Math.max(1, low); k <= high; k++) {
                    double a = spectrum.power(k - 1), b = spectrum.power(k), c = spectrum.power(k + 1);
                    if (b <= a || b <= c || b < 1e-10) continue;
                    a = Math.log(Math.max(a, 1e-20));
                    c = Math.log(Math.max(c, 1e-20));
                    double log = Math.log(b);
                    double hz = (k + .5 * (a - c) / (a - 2 * log + c)) * binHz;
                    double pitch = 69 + 12 / Math.log(2) * Math.log(hz / 440);
                    int midi = (int) Math.round(pitch);
                    if (b <= amplitudes[midi]) continue;
                    amplitudes[midi] = b;
                    pitches[midi] = pitch;
                }
                for (int p = 60; p <= 90; p++) {
                    double lower = Math.sqrt(amplitudes[p]), upper = Math.sqrt(amplitudes[p + 12]);
                    if (Math.min(lower, upper) < Math.max(lower, upper) * .18) continue;
                    double disagreement = Math.abs(pitches[p] - (pitches[p + 12] - 12));
                    double level = 4 * Math.sqrt(lower * upper) / window.length;
                    if (disagreement > .25 || level <= Math.max(.003, best.level)) continue;
                    best = new Ridge((pitches[p] + pitches[p + 12] - 12) / 2, 1 - disagreement / 2, level);
                }
            }
            frames[i] = best;
        }
        return frames;
    }

    static Ridge[] ridge(ReferenceAudio audio) {
        int stride = Math.max(1, audio.sampleRate() / 12_000);
        double rate = audio.sampleRate() / (double) stride;
        var mono = new float[audio.samples().length / stride];
        for (int i = 0; i < mono.length; i++) {
            for (int j = 0; j < stride; j++) mono[i] += audio.samples()[i * stride + j];
            mono[i] /= stride;
        }
        var frames = new Ridge[(int) Math.ceil(mono.length / rate / HOP)];
        int longWindow = (int) Math.round(.018 * rate);
        int shortWindow = (int) Math.round(.009 * rate);
        var lag = new double[longWindow / 2 + 2];
        for (int i = 0; i < frames.length; i++) {
            int center = (int) Math.round(i * HOP * rate);
            double energy = 0;
            for (int j = center - shortWindow / 2; j < center + shortWindow / 2; j++)
                if (j >= 0 && j < mono.length) energy += mono[j] * mono[j];
            double level = Math.sqrt(energy / shortWindow);
            var wide = level < .001 ? new Ridge(0, 0, level)
                    : estimate(mono, center, longWindow, rate, level, lag);
            if (level >= .001 && (wide.pitch >= 65 || wide.confidence < .91)) {
                var narrow = estimate(mono, center, shortWindow, rate, level, lag);
                if (narrow.pitch >= 68 && narrow.confidence >= .87
                        && (Math.abs(wide.pitch - narrow.pitch) < 1.6 || wide.confidence < .91)) wide = narrow;
            }
            frames[i] = wide;
        }
        return frames;
    }

    private static Ridge estimate(float[] mono, int center, int width, double rate, double level, double[] lag) {
        int first = center - width / 2;
        if (first < 0 || first + width > mono.length) return new Ridge(0, 0, level);
        double sum = 0, best = .18;
        int selected = 0;
        int minimum = Math.max(2, (int) Math.floor(rate / 2000));
        for (int k = 1; k <= width / 2 + 1; k++) {
            double distance = 0;
            for (int j = first, end = first + width - k; j < end; j++) {
                double diff = mono[j] - mono[j + k];
                distance += diff * diff;
            }
            distance /= width - k;
            sum += distance;
            lag[k] = sum > 0 ? distance * k / sum : 1;
            if (k > minimum && lag[k - 1] < lag[k - 2] && lag[k - 1] <= lag[k] && lag[k - 1] < best) {
                selected = k - 1;
                best = lag[selected];
                if (best < .12) break;
            }
        }
        if (selected == 0) return new Ridge(0, 0, level);
        double left = lag[selected - 1], right = lag[selected + 1];
        double period = selected + .5 * (left - right) / (left - 2 * best + right);
        double pitch = 69 + 12 / Math.log(2) * Math.log(rate / (period * 440));
        return new Ridge(pitch, 1 - best, level);
    }

    static List<Figure> figures(Ridge[] ridge, double duration) {
        if (ridge.length == 0) return List.of();
        var levels = Arrays.stream(ridge).mapToDouble(Ridge::level).sorted().toArray();
        double gate = Math.max(.001, levels[(int) (.95 * (levels.length - 1))] * .032);
        var plateaus = new ArrayList<Plateau>();
        for (int first = 0; first < ridge.length;) {
            int pitch = rounded(ridge[first], gate);
            int limit = first + 1;
            while (limit < ridge.length && rounded(ridge[limit], gate) == pitch) limit++;
            if (pitch >= 0 && limit - first >= 3 && (limit - first) * HOP <= MAX_DWELL) {
                // Find a stable core inside the rounded span. A continuous glide spends most
                // of its time moving across the pitch bin, even when semitone rounding is steady.
                int required = Math.max(3, (int) Math.ceil((limit - first) * .48));
                double confidence = 0;
                for (int center = first; center + required <= limit; center++) {
                    double low = 128, high = 0, support = 1;
                    for (int i = center; i < center + required; i++) {
                        low = Math.min(low, ridge[i].pitch);
                        high = Math.max(high, ridge[i].pitch);
                        support = Math.min(support, ridge[i].confidence);
                    }
                    if (high - low <= .19) confidence = Math.max(confidence, support);
                }
                if (confidence > 0) plateaus.add(new Plateau(pitch, first, limit, confidence));
            }
            first = limit;
        }

        var figures = new ArrayList<Figure>();
        for (int first = 0; first + MIN_STEPS <= plateaus.size();) {
            int direction = Integer.signum(plateaus.get(first + 1).pitch - plateaus.get(first).pitch);
            int limit = first + 1;
            int reversals = 0;
            while (limit < plateaus.size()) {
                var current = plateaus.get(limit);
                var prior = plateaus.get(limit - 1);
                int step = direction * (current.pitch - prior.pitch);
                if (step == 0 || Math.abs(step) > 2 || (current.first - prior.limit) * HOP > MAX_GAP
                        || (prior.limit - prior.first) * HOP > MAX_DWELL
                        || (current.limit - current.first) * HOP > MAX_DWELL) break;
                // A passing neighbor can briefly turn in a longer otherwise stepwise figure.
                if (step < 0 && (limit - first < 4 || ++reversals > 1)) break;
                limit++;
            }
            while (limit - first >= MIN_STEPS) {
                Figure figure = figure(plateaus.subList(first, limit), duration);
                if (figure != null) {
                    figures.add(figure);
                    break;
                }
                limit--;
            }
            first = limit - first >= MIN_STEPS ? limit : first + 1;
        }
        return List.copyOf(figures);
    }

    private static int rounded(Ridge frame, double gate) {
        int pitch = (int) Math.round(frame.pitch);
        return frame.confidence >= .83 && frame.level >= gate && Math.abs(pitch - frame.pitch) <= .34 ? pitch : -1;
    }

    private static Figure figure(List<Plateau> plateaus, double duration) {
        var steps = new ArrayList<Step>();
        double start = Math.max(0, (plateaus.getFirst().first - .5) * HOP);
        double confidence = 0;
        for (int i = 0; i < plateaus.size(); i++) {
            var current = plateaus.get(i);
            double end = i + 1 == plateaus.size() ? Math.min(duration, (current.limit - .5) * HOP)
                    : (current.limit - 1 + plateaus.get(i + 1).first) * HOP / 2;
            double dwell = end - start;
            double supported = (current.limit - current.first) * HOP / dwell;
            if (dwell < (i == 0 || i + 1 == plateaus.size() ? .010 : MIN_DWELL) || dwell > MAX_DWELL || supported < .48) return null;
            steps.add(new Step(current.pitch, start, end));
            start = end;
            confidence += current.confidence * Math.min(1, supported);
        }
        return new Figure(List.copyOf(steps), confidence / steps.size());
    }
}
