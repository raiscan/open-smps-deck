package com.opensmpsdeck.headless;

import java.util.List;
import java.util.Set;

/**
 * A MIDI export from a frame-clocked source can preserve its original clock
 * in the microtiming of drum hits. Where that independent evidence is strong,
 * refine an already detected tempo ramp to sub-frame precision. These
 * candidate rates describe the input. The output clock is applied after the
 * beat map maps source seconds into project seconds.
 */
final class ReconstructionOnsetClock {
    private static final int[] CLOCK_RATES = {24, 25, 30, 48, 50, 60, 100, 120};
    private static final double MIN_COHERENCE = .985;
    private static final double SEARCH = .009;
    private record Candidate(int rate, double drift, double coherence) {}

    private ReconstructionOnsetClock() {}

    static ReconstructionBeatMap refine(ReconstructionBeatMap map, MidiScore midi, Set<Integer> drums,
                                         double duration) {
        double drift = map.closingBpm() / map.openingBpm() - 1;
        if (Math.abs(drift) < ReconstructionTimeMap.DRIFT_THRESHOLD || Math.abs(drift) > .18) return map;
        var onsets = midi.notes().stream().filter(n -> drums.contains(n.partId()) && n.startUs() < duration * 1e6)
                .mapToDouble(n -> n.startUs() / 1e6).distinct().sorted().toArray();
        if (onsets.length < 48 || onsets[onsets.length - 1] - onsets[0] < .75 * duration) return map;

        Candidate best = new Candidate(0, 0, 0);
        for (int rate : CLOCK_RATES) {
            if (coherence(onsets, rate, 0, duration) >= .80) continue;
            double step = .5 / (rate * duration);
            for (double trial = drift - SEARCH; trial <= drift + SEARCH; trial += step) {
                double strength = coherence(onsets, rate, trial, duration);
                if (strength > best.coherence) best = new Candidate(rate, trial, strength);
            }
        }
        if (best.coherence < .90) return map;

        double step = .5 / (best.rate * duration);
        for (int pass = 0; pass < 4; pass++) {
            step /= 5;
            double center = best.drift;
            for (int i = -5; i <= 5; i++) {
                double trial = center + i * step;
                double strength = coherence(onsets, best.rate, trial, duration);
                if (strength > best.coherence) best = new Candidate(best.rate, trial, strength);
            }
        }
        if (best.coherence < MIN_COHERENCE || !distinctSectionsAgree(onsets, best, duration)) return map;

        // Keep the musical phase and beat count. Change only the shape of
        // the clock between these anchors, using the observed acceleration.
        var beats = map.beats();
        double slope = best.drift / (2 * duration);
        double start = steady(beats.getFirst(), slope);
        double period = (steady(beats.getLast(), slope) - start) / (beats.size() - 1);
        var refined = new java.util.ArrayList<Double>(beats.size());
        for (int i = 0; i < beats.size(); i++) {
            double time = start + i * period;
            refined.add(2 * time / (1 + Math.sqrt(1 + 4 * slope * time)));
        }
        return new ReconstructionBeatMap(List.copyOf(refined), map.downbeat(), map.beatsPerBar(), map.confidence());
    }

    private static boolean distinctSectionsAgree(double[] onsets, Candidate best, double duration) {
        // Each third of the input needs local support, including the tail.
        // This prevents a dense figure in one window from determining the fit.
        var counts = new int[3];
        var real = new double[3];
        var imaginary = new double[3];
        double slope = best.drift / (2 * duration);
        for (double time : onsets) {
            int section = Math.min(2, (int) (time * 3 / duration));
            double angle = 2 * Math.PI * best.rate * steady(time, slope);
            real[section] += Math.cos(angle);
            imaginary[section] += Math.sin(angle);
            counts[section]++;
        }
        for (int i = 0; i < 3; i++)
            if (counts[i] < 12 || Math.hypot(real[i], imaginary[i]) < MIN_COHERENCE * counts[i]) return false;
        return true;
    }

    private static double coherence(double[] onsets, int rate, double drift, double duration) {
        // Bound the search on long inputs. The final fit also checks each
        // section using all the original onsets.
        int stride = Math.max(1, onsets.length / 512);
        double real = 0, imaginary = 0;
        double slope = drift / (2 * duration);
        int count = 0;
        for (int i = 0; i < onsets.length; i += stride) {
            double angle = 2 * Math.PI * rate * steady(onsets[i], slope);
            real += Math.cos(angle);
            imaginary += Math.sin(angle);
            count++;
        }
        return Math.hypot(real, imaginary) / count;
    }

    private static double steady(double time, double slope) {
        return time * (1 + slope * time);
    }
}
