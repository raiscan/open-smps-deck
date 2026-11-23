package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.List;

/** Coordinate descent on additive, reference-weighted magnitude spectra. */
final class VoiceFit {
    private static final int PATCH_PASSES = 3;
    private static final int GAIN_PASSES = 12;
    private static final double MAX_GAIN = 4;
    private static final double NEW_PATCH_MARGIN = .0001;
    // A near-tie that flipped the selected voice moved the fit error by about 0.18%.
    private static final double INCUMBENT_MARGIN = .005;

    /** A patch spectrum. The preference only settles exact acoustic ties. */
    record Candidate(String id, float[] spectrum, double preference) {
        Candidate(String id, float[] spectrum) { this(id, spectrum, 0); }
    }
    record Selection(Candidate candidate, double gain) {}
    record Trial(Selection selection, double fit, double check) {}

    private final float[] target;
    private final int split;
    private final double[] residual;
    private final List<Selection> selected;
    private final java.util.Set<Integer> fixed;

    VoiceFit(float[] target, int split, List<Candidate> initial, java.util.Set<Integer> fixed) {
        this.target = target;
        this.split = split;
        this.fixed = fixed;
        residual = new double[target.length];
        for (int i = 0; i < residual.length; i++) residual[i] = target[i];
        selected = new ArrayList<>(initial.size());
        for (var candidate : initial) {
            selected.add(new Selection(candidate, 1));
            add(candidate, -1);
        }
        polishGains();
    }

    private void polishGains() {
        for (int pass = 0; pass < GAIN_PASSES; pass++) {
            for (int lane = 0; lane < selected.size(); lane++) {
                if (fixed.contains(lane)) continue;
                var old = selected.get(lane);
                replace(lane, new Selection(old.candidate(), gain(lane, old.candidate())));
            }
        }
    }

    static List<Selection> solve(float[] target, int split, List<List<Candidate>> candidates) {
        var fit = new VoiceFit(target, split, candidates.stream().map(List::getFirst).toList(), java.util.Set.of());
        fit.patches(candidates);
        return List.copyOf(fit.selected);
    }

    void patches(List<List<Candidate>> candidates) {
        patches(candidates, java.util.Collections.nCopies(candidates.size(), null));
    }

    void patches(List<List<Candidate>> candidates, List<String> incumbents) {
        for (int pass = 0; pass < PATCH_PASSES; pass++) {
            boolean changed = false;
            for (int lane = 0; lane < candidates.size(); lane++) {
                double margin = selection(lane).candidate().id().equals(incumbents.get(lane))
                        ? INCUMBENT_MARGIN : NEW_PATCH_MARGIN;
                changed |= select(lane, candidates.get(lane), margin);
            }
            if (!changed) break;
            polishGains();
        }
    }

    Selection selection(int lane) { return selected.get(lane); }

    boolean select(int lane, List<Candidate> candidates, double relativeMargin) {
        var original = current();
        var best = original;
        var incumbent = selection(lane).candidate();
        double originalFit = original.fit();
        double originalCheck = original.check();
        double bestFit = originalFit, bestCheck = originalCheck, bestPreference = incumbent.preference();
        for (var candidate : candidates) {
            var trial = trial(lane, candidate);
            double candidateFit = trial.fit();
            if (candidateFit < bestFit && improves(originalFit, candidateFit, relativeMargin)
                    && improves(originalCheck, trial.check(), relativeMargin)) {
                best = trial;
                bestFit = candidateFit;
                bestCheck = trial.check();
                bestPreference = candidate.preference();
            } else if (tied(bestFit, candidateFit) && tied(bestCheck, trial.check())
                    && candidate.preference() > bestPreference) {
                // Names and retail use only settle an acoustic tie.
                best = trial;
                bestPreference = candidate.preference();
            }
        }
        if (best == original) return false;
        replace(lane, best.selection());
        return true;
    }

    private static boolean tied(double a, double b) {
        return Math.abs(a - b) <= 1e-9 * Math.max(1e-12, Math.max(Math.abs(a), Math.abs(b)));
    }

    private static boolean improves(double from, double to, double relativeMargin) {
        return from - to > Math.max(1e-10, from * relativeMargin);
    }

    private double gain(int lane, Candidate candidate) {
        var old = selected.get(lane);
        if (fixed.contains(lane)) return old.gain();
        var a = old.candidate().spectrum();
        var b = candidate.spectrum();
        double cross = 0;
        double norm = 0;
        for (int i = 0; i < split; i++) {
            cross += (residual[i] + old.gain() * a[i]) * b[i];
            norm += (double) b[i] * b[i];
        }
        return norm > 0 ? Math.clamp(cross / norm, 0, MAX_GAIN) : old.gain();
    }

    Trial trial(int lane, Candidate candidate) {
        var old = selected.get(lane);
        var a = old.candidate().spectrum();
        var b = candidate.spectrum();
        double gain = gain(lane, candidate);
        double fit = 0, check = 0;
        for (int i = 0; i < target.length; i++) {
            double error = residual[i] + old.gain() * a[i] - gain * b[i];
            if (i < split) fit += error * error;
            else check += error * error;
        }
        return new Trial(new Selection(candidate, gain), fit, check);
    }

    private Trial current() {
        double fit = 0, check = 0;
        for (int i = 0; i < residual.length; i++) {
            double error = residual[i];
            if (i < split) fit += error * error;
            else check += error * error;
        }
        return new Trial(null, fit, check);
    }

    void replace(int lane, Selection next) {
        var old = selected.set(lane, next);
        add(old.candidate(), old.gain());
        add(next.candidate(), -next.gain());
    }

    private void add(Candidate candidate, double gain) {
        var spectrum = candidate.spectrum();
        for (int i = 0; i < residual.length; i++) residual[i] += gain * spectrum[i];
    }
}
