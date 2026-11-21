package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.project.DacSample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.bench.BenchDrums.Drum.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchMetricsTest {
    @Test void recognizesGainFittedDacOnlyWhenItsPcmMatchesTheCatalogue() {
        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Kick",
                new byte[]{0, 64, (byte) 128, (byte) 192});
        builder.variant(asset, "Kick", new InstrumentCatalogue.Recipe(4, null, 127, 60,
                null, null, List.of(), List.of()), null);
        var drums = new BenchDrums(builder.build(), "fixture");
        String id = asset + "-4-gain-0x1.0p-1";
        var scaled = DacSample.of(id, "Kick", 4, new byte[]{64, 96, (byte) 128, (byte) 160});
        var changed = DacSample.of(id, "Kick", 4, new byte[]{65, 96, (byte) 128, (byte) 160});
        var truth = new BenchTimeline.Truth(List.of(new BenchTimeline.Channel("dac", List.of(),
                List.of(new BenchTimeline.Hit(0, BenchFiles.hash(scaled.pcm()), 129),
                        new BenchTimeline.Hit(.5, BenchFiles.hash(changed.pcm()), 130)))));
        var events = BenchMetrics.events(truth, drums, false, Map.of(), drums.gainAliases(List.of(scaled, changed)));
        assertEquals(List.of(KICK, OTHER), events.stream().map(BenchMetrics.Event::drum).toList());
        assertEquals(BenchFiles.hash(scaled.pcm()), events.getFirst().hash());
    }

    @Test void effectCopiesAreRemovedOnlyWhenFlaggedInTheReport() {
        var lead = new BenchMetrics.Event("FM1", 0, .12, 60, null, null);
        var copy = new BenchMetrics.Event("FM5", .16, .28, 60, null, null);
        var events = List.of(lead, copy);
        org.junit.jupiter.api.Assertions.assertSame(events, BenchReconstruction.excludeEffectCopies(events, null));
        org.junit.jupiter.api.Assertions.assertSame(events, BenchReconstruction.excludeEffectCopies(events, List.of()));
        assertEquals(List.of(lead), BenchReconstruction.excludeEffectCopies(events, List.of("FM5")));
    }

    @Test void layerWindowsRemoveOnlyTonalCopyAttacksOnSharedLanes() {
        var report = BenchFiles.JSON.fromJson("""
                {
                  "lanePolicyCopies": [{"lane":"PSG1","startTick":12,"endTick":18}],
                  "noteLayers": [
                    {"status":"applied","lane":"FM2","startTick":6,"endTick":12},
                    {"status":"skipped","lane":"FM2","startTick":12,"endTick":18}
                  ]
                }
                """, BenchReconstruction.Report.class);
        var dryBefore = event("PSG1", .1, 72);
        var octaveCopy = event("PSG1", .2, 84);
        var copyBendStep = event("PSG1", .25, 85);
        var dryAtEnd = event("PSG1", .3, 84);
        var otherLane = event("PSG2", .2, 84);
        var authoredCopy = event("FM2", .1, 60);
        var skippedLayer = event("FM2", .2, 60);
        var events = List.of(dryBefore, octaveCopy, copyBendStep, dryAtEnd, otherLane, authoredCopy, skippedLayer);

        assertEquals(List.of(dryBefore, dryAtEnd, otherLane, skippedLayer), report.excludeLayers(events));
        var legacy = BenchFiles.JSON.fromJson("{}", BenchReconstruction.Report.class);
        assertEquals(events, legacy.excludeLayers(events));
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

    @Test void drumClassAttacksPreferClassAcrossSimultaneousHits() {
        var truth = List.of(drum(1, KICK), drum(1, HAT), drum(2, OPEN_HAT), drum(3, CRASH));
        var estimate = List.of(drum(1, KICK), drum(1, HAT), drum(2.03, OPEN_HAT), drum(3, HAT));

        assertEquals(4, BenchMetrics.attacks(truth, estimate, .03).size());
        assertEquals(List.of(new BenchMetrics.Pair(0, 0), new BenchMetrics.Pair(1, 1),
                        new BenchMetrics.Pair(2, 2)),
                BenchMetrics.drumClassAttacks(truth, estimate, .03));
    }

    @Test void psgSourceClassesUseOnlyPlacedDrumsAndKeepCymbalsDistinct() {
        var allocation = new BenchReconstruction.Allocation(List.of(
                new BenchReconstruction.Placement(new BenchReconstruction.SourceNote(42, "note-42"), "drums", "PSG3", 30),
                new BenchReconstruction.Placement(new BenchReconstruction.SourceNote(46, "note-46"), "drums", "PSG3", 40),
                new BenchReconstruction.Placement(new BenchReconstruction.SourceNote(49, "note-49"), "drums", "PSG3", 50),
                new BenchReconstruction.Placement(new BenchReconstruction.SourceNote(51, "note-51"), "drums", "PSG3", 60),
                new BenchReconstruction.Placement(new BenchReconstruction.SourceNote(36, "note-36"), "drums", "DAC", 30),
                new BenchReconstruction.Placement(new BenchReconstruction.SourceNote(70, "note-70"), "harmony", "PSG3", 70),
                new BenchReconstruction.Placement(new BenchReconstruction.SourceNote(57, "note-57"), "drums", null, null)));
        assertEquals(java.util.Map.of(30, HAT, 40, OPEN_HAT, 50, CRASH, 60, RIDE),
                BenchReconstruction.noiseClasses(allocation, null));
        assertEquals(java.util.Map.of(30, HAT, 40, OPEN_HAT, 50, CRASH, 60, RIDE, 80, KICK),
                BenchReconstruction.noiseClasses(allocation,
                        List.of(new BenchReconstruction.VoicedGate("note-36", "PSG3", 80))));
    }

    private static BenchMetrics.Event drum(double time, BenchDrums.Drum type) {
        return new BenchMetrics.Event("drums", time, time, 0, null, type);
    }

    @Test void selectsHighestSustainedLineIndependentlyForEachBar() {
        var low = new BenchMetrics.Event("FM1", 0, .5, 52, null, null);
        var sustainedHigh = new BenchMetrics.Event("FM2", 1, 1.5, 76, null, null);
        var quickHigh = new BenchMetrics.Event("FM3", 1.3, 1.35, 92, null, null);
        var secondBar = new BenchMetrics.Event("FM1", 2.1, 2.6, 60, null, null);
        assertEquals(List.of(sustainedHigh, secondBar), BenchMetrics.melody(List.of(low, sustainedHigh, quickHigh, secondBar)));
    }

    @Test void scoresOnlyFm1AgainstTheDeclaredLead() {
        var truth = List.of(event("PSG1", 0, 60), event("PSG1", .4, 62), event("FM4", 0, 84));
        var estimate = List.of(event("FM1", .05, 60), event("FM1", .4, 65), event("FM2", .4, 62));
        assertEquals(new BenchMetrics.F1(2, 2, 1), BenchLeadFidelity.score(truth, estimate, "PSG1"));
    }

    @Test void creditsSupportedLeadCopiesOnceAndLeavesIndependentNotesInTheDenominator() {
        var truth = new java.util.ArrayList<BenchMetrics.Event>();
        var estimate = new java.util.ArrayList<BenchMetrics.Event>();
        for (int i = 0; i < 5; i++) {
            double onset = i * .3;
            truth.add(event("PSG1", onset, 60 + i));
            truth.add(event("FM3", onset + 8 / 60.0, 72 + i));
            truth.add(event("FM4", onset, 60 + i));
            estimate.add(event("FM1", onset + 8 / 60.0, 72 + i));
        }
        truth.add(event("PSG1", 1.6, 57));
        estimate.add(event("FM1", 0, 60));
        estimate.add(event("FM1", 1.6, 69));
        assertEquals(new BenchMetrics.F1(6, 7, 5), BenchLeadFidelity.score(truth, estimate, "PSG1"));
    }

    @Test void rejectsCoincidentalSinglePitchEchoes() {
        var truth = new java.util.ArrayList<BenchMetrics.Event>();
        var estimate = new java.util.ArrayList<BenchMetrics.Event>();
        for (int i = 0; i < 5; i++) {
            truth.add(event("PSG1", i * .3, 60));
            truth.add(event("FM3", i * .3 + .15, 60));
            estimate.add(event("FM1", i * .3 + .15, 60));
        }
        assertEquals(new BenchMetrics.F1(5, 5, 0), BenchLeadFidelity.score(truth, estimate, "PSG1"));
    }

    private static BenchMetrics.Event event(String lane, double start, int pitch) {
        return new BenchMetrics.Event(lane, start, start + .1, pitch, null, null);
    }

    static BenchMetrics.Event note(double start, double end, int pitch) {
        return new BenchMetrics.Event("FM1", start, end, pitch, null, null);
    }
}
