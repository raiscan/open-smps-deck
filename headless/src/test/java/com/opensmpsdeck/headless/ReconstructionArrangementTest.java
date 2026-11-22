package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionArrangementTest {
    @Test
    void capPreservesExtremesAndCountsSustainsAcrossAttacks() {
        var bass = note("bottom", 1, 0, .7, 48, 45);
        var inner = note("inner", 1, .2, .5, 55, 127);
        var top = note("top", 1, 0, .7, 67, 60);
        var outside = note("outside", 1, .8, .9, 62, 70);
        var cap = new ReconstructionArrangement.Polyphony(1, 2, List.of(.1, .6));
        assertEquals(List.of("inner"), ReconstructionArrangement.capped(cap,
                List.of(bass, inner, top, outside), .1, .6));
    }

    @Test
    void harmonicFilterRequiresOverlapPitchAndLevelEvidence() {
        var root = note("root", 0, 0, .5, 36, 100);
        var harmonic = note("harmonic", 1, .1, .3, 55, 50);
        var otherPitch = note("other", 1, .1, .3, 54, 50);
        var after = note("after", 1, .5, .7, 60, 50);
        var louder = note("louder", 1, .1, .3, 48, 110);
        var filter = new ReconstructionArrangement.BassHarmonics(1, 0, null, null, null, null, null, null);
        assertEquals(root, filter.reference(harmonic, List.of(root), java.util.Set.of(), filter::louderByVelocity));
        for (var n : List.of(root, otherPitch, after, louder))
            assertNull(filter.reference(n, List.of(root), java.util.Set.of(), filter::louderByVelocity));
    }

    @Test
    void partChipPinConstrainsAllocationEvenWithAffinityOnAnotherChip() {
        var n = note("tone", 1, 0, .5, 64, 100);
        var roles = new ReconstructionRoles.Result(1, 1000,
                List.of(new ReconstructionRoles.Decision(n, melody, null, null)), null);
        var lanes = new ReconstructionArrangement.PartChip(1, ReconstructionLaneType.PSG, List.of("PSG2"), null);
        var settings = new ReconstructionArrangement.Allocation(java.util.Map.of(n.id(), lanes), java.util.Map.of(), null);
        var allocated = ReconstructionAllocation.allocateWithAffinity(roles,
                List.of(new MidiScore.Part(1, "Lead", 0, false)), 60, false, false,
                java.util.Map.of("tone", "FM1"), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                null, java.util.Map.of(), false, com.opensmpsdeck.project.TargetProfile.S3K, settings);
        assertEquals("PSG2", allocated.notes().getFirst().lane());
    }

    @Test
    void routesEveryHatOrOnlyDacCollisionsAsConfigured() {
        var drumsPart = new MidiScore.Part(0, "Drums", 0, true);
        var kick = note("kick", 0, 0, .1, 36, 110);
        var closed = note("closed", 0, 0, .1, 42, 90);
        var open = note("open", 0, .3, .4, 46, 80);
        var roles = new ReconstructionRoles.Result(1, 1000, List.of(kick, closed, open).stream()
                .map(n -> new ReconstructionRoles.Decision(n, drums, null, null)).toList(), null);
        var louderHat = java.util.Map.of(new ReconstructionVoices.Key(0, 42), .05);
        for (var mode : ReconstructionArrangement.Hats.values()) {
            var settings = new ReconstructionArrangement.Allocation(java.util.Map.of(), java.util.Map.of(), mode);
            var result = ReconstructionAllocation.allocateWithAffinity(roles, List.of(drumsPart), 60, false, true,
                    java.util.Map.of(), louderHat, java.util.Map.of(), java.util.Map.of(), null, java.util.Map.of(),
                    false, com.opensmpsdeck.project.TargetProfile.S3K, settings);
            var lanes = ReconstructionAllocation.lanes(result);
            assertEquals("DAC", lanes.get("kick"));
            assertEquals("PSG3", lanes.get("closed"));
            assertEquals(mode == ReconstructionArrangement.Hats.all ? "PSG3" : "DAC", lanes.get("open"));
        }
    }

    @Test
    void drumStemSeparatesQuietHitsAndQuantizesTheirGains() {
        int rate = 24_000;
        float[] pcm = new float[rate];
        for (int i = 0; i < rate / 20; i++) {
            float sample = (float) (.25 * Math.sin(Math.TAU * 90 * i / rate));
            pcm[rate / 5 + i] = sample;
            pcm[rate * 2 / 5 + i] = .2f * sample;
        }
        var loud = note("full", 0, .2, .26, 36, 100);
        var quiet = note("quiet", 0, .4, .46, 36, 100);
        var silent = note("silent", 0, .6, .66, 36, 100);
        var levels = ReconstructionDrumDynamics.relativeLevels(pcm, rate, List.of(loud, quiet, silent));
        assertEquals(0, levels.get("quiet"), .0001);
        assertEquals(14, levels.get("full"), .05);
        assertEquals(-120, levels.get("silent"));
        assertTrue(levels.get("silent") < -15);
        assertEquals(Math.pow(10, -6 / 20.0), ReconstructionDrumDynamics.gain(-5.8), .0001);
    }

    @Test
    void levelFitBoundsSuccessiveSectionAdjustments() {
        assertEquals(12, ReconstructionSectionLevels.boundedGain(20.0, null, 3));
        assertEquals(9, ReconstructionSectionLevels.boundedGain(-8.0, 12.0, 3));
        assertEquals(9, ReconstructionSectionLevels.boundedGain(null, 9.0, 3));
        assertEquals(-3, ReconstructionSectionLevels.boundedGain(-5.0, 0.0, 3));
    }

    @Test
    void monoSpanReplacesPolyphonicNotesAndKeepsCrossingTails() {
        var polyNotes = List.of(new ExternalTranscribers.RawNote(.1, .9, 55, 90),
                new ExternalTranscribers.RawNote(.4, .5, 76, 95));
        var f0Notes = List.of(new ExternalTranscribers.RawNote(.25, .7, 57, 96));
        var poly = imported("poly", "guitar", polyNotes, java.util.Map.of());
        var f0 = imported("f0", "vocals", f0Notes, java.util.Map.of("f0Cents10ms", "", "vocalMode", "syllable"));
        var rule = new ReconstructionMonophonicStem("f0", List.of(.3, .6), 12);
        float[] samples = new float[24000];
        var reference = new ReferenceAudio(samples, 24000);
        var result = ReconstructionMonophonicStem.join(1, rule.snapshot(poly, f0),
                new ReconstructionLeadAudio.Track(reference), new ReconstructionStemParts.Spectrum(reference), 1, new java.util.ArrayList<>());
        assertEquals(List.of(55, 69, 55), result.stream().map(ReconstructionStages.Note::pitch).toList());
        assertEquals(List.of(.1, .3, .6), result.stream().map(ReconstructionStages.Note::start).toList());
        assertEquals(List.of(.3, .6, .9), result.stream().map(ReconstructionStages.Note::end).toList());
    }

    private static ExternalTranscribers.PartNotes imported(String tool, String role,
            List<ExternalTranscribers.RawNote> notes, java.util.Map<String, String> metadata) {
        return new ExternalTranscribers.PartNotes(new ExternalTranscribers.Evidence(tool, role, "cpu",
                "input", "output", "config", metadata, notes.size(), true), notes);
    }

    private static ReconstructionStages.Note note(String id, int part, double start, double end, int pitch, int velocity) {
        return new ReconstructionStages.Note(id, part, start, end, pitch, velocity, "edit", 1);
    }
}
