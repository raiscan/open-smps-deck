package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Beat anchors in source seconds. Index zero has no special metrical meaning. */
record ReconstructionBeatMap(List<Double> beats, int downbeat, int beatsPerBar, double confidence) {
    record Summary(@Nullable Double startBpm, @Nullable Double endBpm, @Nullable Double maxDeviation,
                   double confidence, boolean warped, @Nullable Double steadyBpm, double durationSeconds) {}

    // The beat path already smooths local timing noise. Measure sustained
    // changes over longer windows against the initial rate of that path.
    double openingBpm() {
        double duration = beats.getLast();
        for (double start = 0; start <= Math.min(30, duration * .2) && start + 12 <= duration; start += 4) {
            double a = 15 * (index(start + 4) - index(start));
            double b = 15 * (index(start + 8) - index(start + 4));
            double c = 15 * (index(start + 12) - index(start + 8));
            double low = Math.min(a, Math.min(b, c)), high = Math.max(a, Math.max(b, c));
            if (high - low <= .001 * b) return (a + b + c) / 3;
        }
        // A ramp may begin immediately. Extrapolate neighboring smoothed
        // interval rates to zero, where the project clock is anchored.
        double first = beats.getFirst(), second = beats.get(1);
        double rate = 1 / (second - first);
        if (beats.size() > 2) {
            double third = beats.get(2);
            double nextRate = 1 / (third - second);
            double offset = Math.clamp((first + second) / (third - first), -1, 1);
            rate -= (nextRate - rate) * offset;
        }
        return Math.clamp(60 * rate, 20, 400);
    }
    double closingBpm() { return 60 / (beats.getLast() - beats.get(beats.size() - 2)); }

    private double rate(int from, int direction) {
        int to = from + direction;
        while (to + direction >= 0 && to + direction < beats.size()
                && Math.abs(beats.get(to) - beats.get(from)) < 16) to += direction;
        return 60 * (to - from) / (beats.get(to) - beats.get(from));
    }

    double deviation() {
        double opening = openingBpm();
        double max = 0;
        double span = Math.min(12, (beats.getLast() - beats.getFirst()) / 2);
        for (int i = 0; i < beats.size() - 1 && beats.get(i) <= beats.getLast() - span; i++)
            max = Math.max(max, Math.abs(rate(i, 1) / opening - 1));
        return max;
    }

    double index(double time) {
        int found = Collections.binarySearch(beats, time);
        if (found >= 0) return found;
        int lower = Math.clamp(-found - 2, 0, beats.size() - 2);
        return lower + (time - beats.get(lower)) / (beats.get(lower + 1) - beats.get(lower));
    }

    double seconds(double index) {
        int lower = Math.clamp((int) Math.floor(index), 0, beats.size() - 2);
        return beats.get(lower) + (index - lower) * (beats.get(lower + 1) - beats.get(lower));
    }

    List<ReconstructionStructure.Bar> bars(double duration) {
        var bars = new ArrayList<ReconstructionStructure.Bar>();
        double start = 0;
        int first = (int) Math.ceil((index(0) - downbeat) / beatsPerBar - 1e-8) * beatsPerBar + downbeat;
        boolean complete = seconds(first) <= 1e-8;
        for (int i = complete ? first + beatsPerBar : first; start < duration; i += beatsPerBar) {
            double boundary = seconds(i);
            bars.add(new ReconstructionStructure.Bar("b" + (bars.size() + 1), start,
                    Math.min(boundary, duration), complete && boundary <= duration));
            start = boundary;
            complete = true;
        }
        return List.copyOf(bars);
    }

    static ReconstructionBeatMap constant(ReconstructionTempo.Grid grid, double duration) {
        double period = 60 / grid.bpm();
        var beats = new ArrayList<Double>();
        for (double time = grid.beatPhase(); time < duration + 2 * period; time += period) beats.add(time);
        int downbeat = (int) Math.round((grid.barOffset() - grid.beatPhase()) / period);
        return new ReconstructionBeatMap(List.copyOf(beats), Math.floorMod(downbeat, grid.beatsPerBar()),
                grid.beatsPerBar(), 1);
    }
}
