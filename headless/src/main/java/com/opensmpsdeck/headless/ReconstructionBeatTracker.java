package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Fits a smooth tempo path to overlapping onset spectra. A linear tempo trend
 * restricts the search to gradual changes; local phase observations correct
 * that trend for timing variations and keep the pulse aligned through the song.
 */
final class ReconstructionBeatTracker {
    private static final int MIN_BPM = 55, MAX_BPM = 240;
    private static final double BPM_STEP = .25;
    private static final int BINS = (int) ((MAX_BPM - MIN_BPM) / BPM_STEP) + 1;
    private static final double WINDOW = 10;
    private static final double STRIDE = 3;
    private static final double[] COS = new double[4096];
    static {
        for (int i = 0; i < COS.length; i++) COS[i] = Math.cos(2 * Math.PI * i / COS.length);
    }

    private record Attack(double time, double weight, double accent, double audio) {}
    private record Pulse(double strength, double phase) {}
    private record Window(double time, List<Attack> attacks, double[][] spectrum) {}
    private record Trend(double bpm, double change, int subdivision, double strength) {
        double index(double time, double duration) { return time * (bpm + change * time / (2 * duration)) / 60; }
        double tempo(double time, double duration) { return bpm + change * time / duration; }
    }
    private record Phase(double time, double offset, double weight) {}
    private record PhasePath(List<Phase> anchors, double evidence, double coverage) {}

    private ReconstructionBeatTracker() {}

    static ReconstructionBeatMap track(MidiScore score, double duration,
                                       List<ReconstructionRhythmAudio.Onset> audio, int meter) {
        if (duration < 12) return null;
        var drums = new HashSet<Integer>();
        for (var part : score.parts()) if (part.drums()) drums.add(part.id());
        var attacks = attacks(score, duration, audio, drums);
        if (attacks.size() < 40) return null;
        var windows = windows(attacks, duration);
        if (windows.size() < 4) return null;

        Trend trend = bestTrend(windows, duration);
        if (trend.strength < .28) return null;
        var path = phases(windows, trend, duration);
        if (path == null) return null;
        var phases = path.anchors;

        // Choose the quarter-note phase among the equivalent subdivision phases.
        double offset = phaseAt(phases, 0, true);
        double[] slots = new double[trend.subdivision];
        for (var event : attacks) {
            double index = trend.index(event.time, duration) - phaseAt(phases, event.time, false);
            int slot = (int) Math.round(index * slots.length);
            if (Math.abs(index - slot / (double) slots.length) < .06)
                slots[Math.floorMod(slot, slots.length)] += event.accent + .10 * event.weight + .1 * event.audio;
        }
        int beatSlot = 0;
        for (int i = 1; i < slots.length; i++) if (slots[i] > slots[beatSlot]) beatSlot = i;
        double phase = beatSlot / (double) slots.length;
        double confidence = (.65 * path.evidence + .35 * trend.strength) * path.coverage;
        var beats = new ArrayList<Double>();
        int first = (int) Math.floor(-offset - phase);
        for (int beat = first; ; beat++) {
            double time = inverse(trend, phases, duration, beat + phase);
            beats.add(time);
            if (time >= duration) break;
        }
        if (beats.size() < 3) return null;
        int beatsPerBar = meter == 0 ? 4 : meter;
        var map = new ReconstructionBeatMap(List.copyOf(beats), 0, beatsPerBar, confidence);
        map = new ReconstructionBeatMap(map.beats(), downbeat(score, drums, map, duration), beatsPerBar, confidence);
        return ReconstructionOnsetClock.refine(map, score, drums, duration);
    }

    private static List<Attack> attacks(MidiScore score, double duration, List<ReconstructionRhythmAudio.Onset> audio,
                                        HashSet<Integer> drums) {
        var grid = new TreeMap<Long, Attack>();
        for (var note : score.notes()) {
            if (note.startUs() >= duration * 1e6) continue;
            long bin = Math.round(note.startUs() / 2500.0);
            boolean drum = drums.contains(note.partId());
            double accent = drum ? switch (note.pitch()) {
                case 35, 36, 37, 38, 40 -> 1;
                default -> .25;
            } : 0;
            var hit = new Attack(bin * .0025, drum ? 2.5 : 1, accent, 0);
            grid.merge(bin, hit, (a, b) -> new Attack(a.time, Math.max(a.weight, b.weight),
                    Math.max(a.accent, b.accent), 0));
        }
        for (var hit : audio) {
            long bin = Math.round(hit.time() / .0025);
            var onset = new Attack(bin * .0025, 0, 0, Math.min(.45, hit.weight() * 1.7));
            grid.merge(bin, onset, (a, b) -> new Attack(a.time, a.weight, a.accent, b.audio));
        }
        return List.copyOf(grid.values());
    }

    private static List<Window> windows(List<Attack> attacks, double duration) {
        var windows = new ArrayList<Window>();
        int first = 0, end = 0;
        double span = Math.min(duration, WINDOW);
        for (double center = span / 2; center <= duration - span / 2 + STRIDE / 2; center += STRIDE) {
            while (first < attacks.size() && attacks.get(first).time < center - span / 2) first++;
            end = Math.max(end, first);
            while (end < attacks.size() && attacks.get(end).time < center + span / 2) end++;
            var local = attacks.subList(first, end);
            if (local.stream().filter(a -> a.weight > 0).count() < 16) continue;
            double[][] values = new double[2][BINS];
            for (int bin = 0; bin < BINS; bin++) {
                double bpm = MIN_BPM + bin * BPM_STEP;
                double beat = pulse(local, center, bpm, 1, true).strength;
                double half = pulse(local, center, bpm, 2, false).strength;
                values[0][bin] = .65 * pulse(local, center, bpm, 4, false).strength + .20 * half + .15 * beat;
                values[1][bin] = .63 * pulse(local, center, bpm, 6, false).strength + .20 * half + .15 * beat;
            }
            windows.add(new Window(center, local, values));
        }
        return windows;
    }

    private static Trend bestTrend(List<Window> windows, double duration) {
        Trend best = new Trend(0, 0, 4, -1);
        for (int row = 0; row < 2; row++) {
            for (double bpm = MIN_BPM; bpm <= MAX_BPM; bpm += BPM_STEP) {
                for (double change = -Math.ceil(bpm * .18); change <= bpm * .18; change += .5) {
                    double strength = 0;
                    for (var window : windows) {
                        double bin = (bpm + change * window.time / duration - MIN_BPM) / BPM_STEP;
                        if (bin < 0 || bin >= BINS - 1) continue;
                        int i = (int) bin;
                        strength += window.spectrum[row][i] * (1 - bin + i)
                                + window.spectrum[row][i + 1] * (bin - i);
                    }
                    strength = strength / windows.size() - .015 * Math.abs(change / bpm);
                    if (strength > best.strength) best = new Trend(bpm, change, row == 0 ? 4 : 6, strength);
                }
            }
        }
        return best;
    }

    private static Pulse pulse(List<Attack> events, double center, double bpm, int subdivision, boolean accent) {
        double re = 0, im = 0, total = 0;
        double frequency = bpm * subdivision / 60;
        for (var event : events) {
            double weight = accent ? event.accent + .15 * event.weight : event.weight + event.audio;
            int angle = (int) Math.round((event.time - center) * frequency * COS.length);
            re += weight * COS[angle & (COS.length - 1)];
            im += weight * COS[(angle - COS.length / 4) & (COS.length - 1)];
            total += weight;
        }
        return total == 0 ? new Pulse(0, 0) : new Pulse(Math.min(1, Math.hypot(re, im) / total),
                Math.atan2(im, re) / (2 * Math.PI * subdivision));
    }

    private static PhasePath phases(List<Window> windows, Trend trend, double duration) {
        var raw = new ArrayList<Phase>();
        double previousRate = 0;
        for (var window : windows) {
            var pulse = pulse(window.attacks, window.time, trend, duration);
            if (pulse.strength < .32) continue;
            double offset = trend.index(window.time, duration) + pulse.phase;
            double rate = offsetRate(window, trend, duration);
            double predicted = raw.isEmpty() ? 0 : raw.getLast().offset
                    + (previousRate + rate) / 2 * (window.time - raw.getLast().time);
            offset -= Math.rint((offset - predicted) * trend.subdivision) / trend.subdivision;
            raw.add(new Phase(window.time, offset, pulse.strength * pulse.strength));
            previousRate = rate;
        }
        if (raw.size() < 4) return null;
        double coverage = Math.min(1, (raw.getLast().time - raw.getFirst().time + WINDOW) / duration);
        double evidence = raw.stream().mapToDouble(p -> Math.sqrt(p.weight)).average().orElseThrow();

        // A linear BPM ramp has quadratic phase. Refit that shape globally when
        // the phase observations agree, so local groove changes do not bias the
        // estimated opening rate. For more complex curves retain local anchors.
        var quadratic = fitQuadratic(raw, duration);
        if (quadratic != null) return new PhasePath(quadratic, evidence, coverage);

        // Weighted local linear regression suppresses noisy phase measurements
        // while extrapolating through the first and last partial windows.
        var result = new ArrayList<Phase>();
        for (var phase : raw) {
            double weights = 0, x = 0, y = 0, xx = 0, xy = 0;
            for (var sample : raw) {
                double delta = sample.time - phase.time;
                double weight = sample.weight * Math.exp(-.5 * Math.pow(delta / 5, 2));
                weights += weight;
                x += weight * delta;
                y += weight * sample.offset;
                xx += weight * delta * delta;
                xy += weight * delta * sample.offset;
            }
            double intercept = (y * xx - x * xy) / (weights * xx - x * x);
            result.add(new Phase(phase.time, intercept, phase.weight));
        }
        return new PhasePath(result, evidence, coverage);
    }

    // Phase is ambiguous by one subdivision. Use the local spectral peak
    // to resolve that ambiguity if the global trend lags a change in tempo.
    private static double offsetRate(Window window, Trend trend, double duration) {
        double center = (trend.tempo(window.time, duration) - MIN_BPM) / BPM_STEP;
        var spectrum = window.spectrum[trend.subdivision == 4 ? 0 : 1];
        int best = Math.clamp((int) Math.round(center), 0, BINS - 1);
        double support = spectrum[best];
        for (int i = Math.max(0, (int) center - 16); i <= Math.min(BINS - 1, (int) center + 16); i++) {
            double candidate = spectrum[i] - .002 * Math.abs(i - center);
            if (candidate > support) {
                best = i;
                support = candidate;
            }
        }
        return (center - best) * BPM_STEP / 60;
    }

    private static Pulse pulse(List<Attack> events, double center, Trend trend, double duration) {
        double re = 0, im = 0, total = 0;
        double phase = trend.index(center, duration);
        for (var event : events) {
            double weight = event.weight + event.audio;
            int angle = (int) Math.round((trend.index(event.time, duration) - phase) * trend.subdivision * COS.length);
            re += weight * COS[angle & (COS.length - 1)];
            im += weight * COS[(angle - COS.length / 4) & (COS.length - 1)];
            total += weight;
        }
        return new Pulse(Math.min(1, Math.hypot(re, im) / total),
                Math.atan2(im, re) / (2 * Math.PI * trend.subdivision));
    }

    private static List<Phase> fitQuadratic(List<Phase> raw, double duration) {
        double[][] normal = new double[3][4];
        for (var phase : raw) {
            double t = phase.time / duration;
            double[] x = {1, t, t * t};
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) normal[i][j] += phase.weight * x[i] * x[j];
                normal[i][3] += phase.weight * x[i] * phase.offset;
            }
        }
        for (int i = 0; i < 3; i++) {
            double pivot = normal[i][i];
            if (pivot < 1e-8) return null;
            for (int j = i; j < 4; j++) normal[i][j] /= pivot;
            for (int k = 0; k < 3; k++) {
                if (k == i) continue;
                double scale = normal[k][i];
                for (int j = i; j < 4; j++) normal[k][j] -= scale * normal[i][j];
            }
        }
        double intercept = normal[0][3], rate = normal[1][3], curvature = normal[2][3];
        double error = 0, total = 0;
        for (var phase : raw) {
            double t = phase.time / duration;
            double residual = phase.offset - (intercept + t * (rate + t * curvature));
            error += phase.weight * residual * residual;
            total += phase.weight;
        }
        if (Math.sqrt(error / total) > .017) return null;
        var fit = new ArrayList<Phase>();
        for (int i = 0; i <= (int) Math.ceil(duration); i++) {
            double t = i / duration;
            double weight = Math.clamp(total / raw.size(), 0, 1);
            fit.add(new Phase(i, intercept + t * (rate + t * curvature), weight));
        }
        return fit;
    }

    private static double phaseAt(List<Phase> phases, double time, boolean extrapolate) {
        int low = 1, high = phases.size() - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (phases.get(middle).time < time) low = middle + 1;
            else high = middle;
        }
        var left = phases.get(low - 1);
        var next = phases.get(low);
        double fraction = (time - left.time) / (next.time - left.time);
        if (!extrapolate && (time < phases.getFirst().time || time > phases.getLast().time))
            fraction = Math.clamp(fraction, 0, 1);
        return Math.fma(next.offset - left.offset, fraction, left.offset);
    }

    private static double inverse(Trend trend, List<Phase> phases, double duration, double beat) {
        double low = -2, high = duration + 2;
        for (int i = 0; i < 28; i++) {
            double time = (low + high) / 2;
            if (trend.index(time, duration) - phaseAt(phases, time, true) < beat) low = time;
            else high = time;
        }
        return (low + high) / 2;
    }

    private static int downbeat(MidiScore score, HashSet<Integer> drums, ReconstructionBeatMap map, double duration) {
        int meter = map.beatsPerBar();
        var accents = new double[meter];
        var last = new HashMap<Integer, Long>();
        double first = Double.POSITIVE_INFINITY;
        for (var note : score.notes()) {
            double time = note.startUs() / 1e6;
            if (time >= duration) continue;
            double index = map.index(time);
            int tick = (int) Math.round(index);
            if (Math.abs(index - tick) > .10) continue;
            first = Math.min(first, index);
            double weight;
            if (drums.contains(note.partId())) {
                weight = switch (note.pitch()) {
                    case 35, 36 -> 2;
                    case 38, 40 -> -2;
                    case 49, 57 -> 4;
                    default -> .1;
                };
            } else {
                Long previous = last.put(note.partId(), note.startUs());
                if (previous != null && note.startUs() - previous < 25_000) continue;
                weight = note.pitch() < 55 ? .65 : .15;
            }
            accents[Math.floorMod(tick, meter)] += weight;
        }
        int best = 0;
        for (int i = 0; i < meter; i++) {
            double opening = ReconstructionTempo.modulo(first - i + .1, meter);
            accents[i] += Double.isFinite(opening) ? .25 * (meter - opening) : 0;
            if (accents[i] > accents[best]) best = i;
        }
        return best;
    }
}
