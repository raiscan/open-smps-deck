package com.opensmpsdeck.headless;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionChordReductionTest {
    @Test void protectsTheUpperVoiceAndGuideTonesAtTheSameAttack() {
        var result = allocate(List.of(
                voice("bass", 0, 36, 0, 1, Role.bass),
                voice("root", 1, 48, 0, 1, Role.harmony),
                voice("third", 1, 52, 0, 1, Role.harmony),
                voice("fifth", 1, 55, 0, 1, Role.harmony),
                voice("seventh", 1, 58, 0, 1, Role.harmony),
                voice("top", 1, 62, 0, 1, Role.harmony)), Map.of());
        assertEquals(List.of("bass", "third", "seventh", "top"),
                result.notes().stream().filter(e -> e.lane() != null).map(e -> e.note().id()).toList());
    }

    @Test void reattacksAnInterruptedPadAfterAShortStab() {
        var result = allocate(List.of(
                voice("bass", 0, 36, 0, 1, Role.bass),
                voice("third", 1, 64, 0, 1, Role.harmony),
                voice("pad", 1, 67, 0, 1, Role.harmony),
                voice("seventh", 1, 70, 0, 1, Role.harmony),
                voice("stab", 2, 76, .3, .45, Role.harmony)), Map.of());
        var prefix = result.notes().stream().filter(e -> e.note().id().equals("pad")).findFirst().orElseThrow();
        var resume = result.notes().stream().filter(e -> e.note().id().startsWith("pad-resume-")).findFirst().orElseThrow();
        assertEquals("pad-yield", prefix.reason());
        assertEquals(18, prefix.endTick());
        assertEquals("pad-resumed", resume.reason());
        assertEquals(prefix.lane(), resume.lane());
        assertEquals(27, resume.startTick());
        assertEquals(60, resume.endTick());
    }

    @Test void usesStemStrengthOnlyForATie() {
        var result = allocate(List.of(
                voice("quiet", 0, 60, 0, 1, Role.harmony),
                voice("middle", 1, 60, 0, 1, Role.harmony),
                voice("strong", 2, 60, 0, 1, Role.harmony),
                voice("strongest", 3, 60, 0, 1, Role.harmony)),
                Map.of("quiet", .001, "middle", .1, "strong", .2, "strongest", .3));
        assertEquals(List.of("quiet"), result.notes().stream().filter(e -> e.lane() == null)
                .map(e -> e.note().id()).toList());
    }

    @Test void recoversSustainedUpperPeaksInASeededChord() {
        int rate = 24_000;
        var samples = new float[rate];
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) rate;
            samples[i] = (float) (.10 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(60) * t)
                    + .10 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(64) * t)
                    + .24 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(79) * t));
        }
        var spectrum = new ReconstructionStemParts.Spectrum(new ReferenceAudio(samples, rate));
        var seed = List.of(new ExternalTranscribers.RawNote(0, 1, 60, 96),
                new ExternalTranscribers.RawNote(0, 1, 64, 96));
        var notes = ReconstructionStemParts.upperVoicing(0, seed, spectrum, 1);
        assertEquals(List.of(79), notes.stream().map(Note::pitch).toList());
        assertTrue(notes.getFirst().end() - notes.getFirst().start() > .8);
        assertTrue(ReconstructionStemParts.upperVoicing(0, seed.subList(0, 1), spectrum, 1).isEmpty());
    }

    @Test void rejectsPulsingEnvelopesForAutomaticStemVoicing() {
        assertFalse(ReconstructionStemParts.startsAudibly(new byte[]{15, 0, 0, (byte) 0x81}));
        assertFalse(ReconstructionStemParts.startsAudibly(new byte[]{0, 2, 15, (byte) 0x80}));
        assertTrue(ReconstructionStemParts.startsAudibly(new byte[]{0, 0, 1, (byte) 0x83}));
    }

    @Test void joinsFragmentsOnlyAcrossAnAudiblyContinuousBoundary() {
        int rate = 24_000;
        var audio = new float[rate];
        var notes = List.of(
                new Note("first", 0, .1, .4, 72, 96, "audio-transcribed", 1),
                new Note("second", 0, .42, .7, 72, 96, "audio-transcribed", 1));
        var spectrum = new ReconstructionStemParts.Spectrum(new ReferenceAudio(audio, rate));
        var reductions = new java.util.ArrayList<S3kReconstruction.Reduction>();
        assertEquals(notes, ReconstructionStemParts.joinHolds(notes, spectrum, reductions));
        for (int i = 0; i < rate; i++)
            audio[i] = (float) Math.sin(Math.TAU * ReconstructionLeadAudio.hz(72) * i / rate);
        var joined = ReconstructionStemParts.joinHolds(notes, spectrum, reductions);
        assertEquals(List.of(new Note("first", 0, .1, .7, 72, 96, "audio-transcribed", 1)), joined);
        assertEquals("second", reductions.getFirst().id());
    }

    @Test void detectsStaggeredUnisonCoveredByMidiReattacks() {
        var note = new Note("stem", 1, .2, .6, 72, 96, "audio-transcribed", 1);
        var midi = Map.of(72, List.of(new MidiScore.Note(0, 72, 96, 100_000, 400_000),
                new MidiScore.Note(0, 72, 96, 410_000, 610_000)));
        assertTrue(ReconstructionStemParts.doubling(note, midi, null));
    }

    private static ReconstructionAllocation.Result allocate(List<ReconstructionRoles.Decision> notes,
                                                            Map<String, Double> salience) {
        var parts = java.util.stream.IntStream.range(0, 4)
                .mapToObj(id -> new MidiScore.Part(id, "Fixture " + id, 4, false)).toList();
        return ReconstructionAllocation.allocateWithAffinity(new ReconstructionRoles.Result(1, 0, notes, null),
                parts, 60, true, !salience.isEmpty(), Map.of(), Map.of(), Map.of(), Map.of(), null, salience, true);
    }

    private static ReconstructionRoles.Decision voice(String id, int part, int pitch, double start, double end, Role role) {
        return new ReconstructionRoles.Decision(new Note(id, part, start, end, pitch, 96, "midi", 1), role, null, null);
    }
}
