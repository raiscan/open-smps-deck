package com.opensmpsdeck.headless.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchMetricsTest {
    @Test void effectCopiesAreRemovedOnlyWhenFlaggedInTheReport() {
        var lead = new BenchMetrics.Event("FM1", 0, .12, 60, null, null);
        var copy = new BenchMetrics.Event("FM5", .16, .28, 60, null, null);
        var events = List.of(lead, copy);
        org.junit.jupiter.api.Assertions.assertSame(events, BenchReconstruction.excludeEffectCopies(events, null));
        org.junit.jupiter.api.Assertions.assertSame(events, BenchReconstruction.excludeEffectCopies(events, List.of()));
        assertEquals(List.of(lead), BenchReconstruction.excludeEffectCopies(events, List.of("FM5")));
    }

    @Test void findsMaximumRatherThanGreedyMatching() {
        var truth = List.of(note(1.00, 1.2, 60), note(1.06, 1.2, 60), note(2.0, 2.2, 62));
        var estimate = List.of(note(1.02, 1.2, 60), note(.96, 1.2, 60), note(2.0, 2.2, 63), note(3, 3.1, 60));
        var pairs = BenchMetrics.notes(truth, estimate, false);
        assertEquals(List.of(new BenchMetrics.Pair(0, 1), new BenchMetrics.Pair(1, 0)), pairs);
        var score = new BenchMetrics.F1(truth.size(), estimate.size(), pairs.size());
        assertEquals(.5, score.precision());
        assertEquals(2.0 / 3, score.recall());
        assertEquals(4.0 / 7, score.f1());
    }

    @Test void usesInclusiveOnsetAndRelativeOrAbsoluteOffsetWindows() {
        var truth = List.of(note(0, .1, 60), note(1, 2, 61), note(3, 3.2, 62));
        var estimate = List.of(note(.05, .15, 60), note(1.05, 2.2, 61), note(3.05, 3.251, 62));
        assertEquals(3, BenchMetrics.notes(truth, estimate, false).size());
        assertEquals(2, BenchMetrics.notes(truth, estimate, true).size());
        assertEquals(2.0 / 3, new BenchMetrics.F1(3, 3, 2).f1());
        assertEquals(1, BenchMetrics.attacks(List.of(note(1, 1.1, 0)), List.of(note(1.03, 1.1, 9)), .03).size());
        assertTrue(BenchMetrics.attacks(List.of(note(1, 1.1, 0)), List.of(note(1.031, 1.1, 9)), .03).isEmpty());
        assertEquals(1, new BenchMetrics.F1(0, 0, 0).f1());
        assertEquals(0, new BenchMetrics.F1(1, 0, 0).f1());
    }

    @Test void selectsHighestSustainedLineIndependentlyForEachBar() {
        var low = new BenchMetrics.Event("FM1", 0, .5, 52, null, null);
        var sustainedHigh = new BenchMetrics.Event("FM2", 1, 1.5, 76, null, null);
        var quickHigh = new BenchMetrics.Event("FM3", 1.3, 1.35, 92, null, null);
        var secondBar = new BenchMetrics.Event("FM1", 2.1, 2.6, 60, null, null);
        assertEquals(List.of(sustainedHigh, secondBar), BenchMetrics.melody(List.of(low, sustainedHigh, quickHigh, secondBar)));
    }

    static BenchMetrics.Event note(double start, double end, int pitch) {
        return new BenchMetrics.Event("FM1", start, end, pitch, null, null);
    }
}
