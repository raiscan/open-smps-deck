package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionLeadAudioTest {
    @Test void separatesFrequencyModulationFromStaticPolyphony() {
        var vibrato = new ArrayList<Double>();
        var staticChord = new ArrayList<Double>();
        var beating = new ArrayList<Double>();
        for (int pitch : List.of(60, 64, 68, 72, 76)) {
            for (double rate : List.of(4.5, 5.5, 7.0)) {
                double phase = pitch + rate;
                vibrato.add(score(pitch, rate, phase, true, false));
                staticChord.add(score(pitch, rate, phase, false, false));
                beating.add(score(pitch, rate, phase, false, true));
            }
        }
        for (var group : List.of(vibrato, staticChord, beating)) {
            System.out.printf(Locale.ROOT, "Lead modulation n=%d min=%.3f mean=%.3f max=%.3f%n", group.size(),
                    group.stream().mapToDouble(x -> x).min().orElseThrow(),
                    group.stream().mapToDouble(x -> x).average().orElseThrow(),
                    group.stream().mapToDouble(x -> x).max().orElseThrow());
        }
        assertTrue(vibrato.stream().allMatch(score -> score >= .75), vibrato.toString());
        assertTrue(staticChord.stream().allMatch(score -> score < .25), staticChord.toString());
        assertTrue(beating.stream().allMatch(score -> score < .25), beating.toString());
    }

    @Test void findsAContinuingToneAndStopsAtStaticSamePitchBacking() {
        int pitch = 69, sampleRate = 12_000;
        var samples = new float[sampleRate];
        double carrier = 0;
        for (int i = 0; i < samples.length; i++) {
            double time = (double) i / sampleRate;
            double level = time < .25 ? 0 : time < .62 ? .20 : .07;
            double cents = time < .62 ? 20 * Math.sin(Math.TAU * 5.5 * time) : 0;
            carrier += Math.TAU * 440 * Math.pow(2, cents / 1200) / sampleRate;
            samples[i] = (float) (level * Math.sin(carrier));
        }
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, sampleRate));
        var tone = track.tone(pitch, .25, .62, List.of());
        assertNotNull(tone);
        assertTrue(tone.end() - tone.start() >= .15);
        assertNull(track.tone(pitch, .66, .92, List.of()));
        double extended = track.release(pitch, .25, .40, .92, List.of());
        assertTrue(extended >= .52 && extended <= .66, "Extended to " + extended);
        assertEquals(.62, track.release(pitch, .25, .62, .92, List.of()));
    }

    @Test void recordsGlideSeparatelyFromPeriodicMotion() {
        int sampleRate = 12_000;
        var samples = new float[sampleRate];
        double phase = 0;
        for (int i = 0; i < samples.length; i++) {
            double time = (double) i / sampleRate;
            phase += Math.TAU * 440 * Math.pow(2, (80 * time - 40) / 1200) / sampleRate;
            samples[i] = (float) (.20 * Math.sin(phase));
        }
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, sampleRate));
        var motion = track.evidence(69, .15, .85, List.of());
        assertTrue(motion.glide() >= .65, motion.toString());
        assertEquals(0, motion.vibrato());
    }

    @Test void supportsStraightLeadOverBackingAcrossShortGaps() {
        var track = legatoTrack(.72, .20);
        var backing = List.of(chord(64), chord(72));
        assertTrue(track.bridges(lead("prior", 69, .20, .38), lead("next", 69, .51, .7), backing));
        assertTrue(track.bridges(lead("prior", 69, .20, .38), lead("next", 69, .397, .7), backing));
    }

    @Test void rejectsRestsAndQuietTailsOverBacking() {
        var ended = legatoTrack(.38, 0);
        var quietTail = legatoTrack(.38, .015);
        var backing = List.of(chord(64), chord(72));
        assertFalse(ended.bridges(lead("prior", 69, .20, .38), lead("next", 69, .51, .7), backing));
        assertFalse(quietTail.bridges(lead("prior", 69, .20, .38), lead("next", 69, .51, .7), backing));
        assertFalse(ended.bridges(lead("prior", 69, .20, .38), lead("next", 69, .397, .7), backing));
    }

    @Test void rejectsShortRestBeforeSamePitchReattack() {
        var track = legatoTrack(.38, 0, .395);
        assertFalse(track.bridges(lead("prior", 69, .20, .38), lead("next", 69, .395, .7), List.of(chord(64), chord(72))));
    }

    @Test void rejectsPitchEvidenceFromANotatedBackingTone() {
        var track = legatoTrack(.72, .20);
        var backing = new ReconstructionStages.Note("backing", 0, .30, .6, 57, 90, "midi", 1);
        assertFalse(track.bridges(lead("prior", 69, .20, .38), lead("next", 69, .51, .7), List.of(backing)));
    }

    @Test void acceptsOnePitchHandoffOnlyWhenTheGapStaysSounding() {
        int rate = 12_000;
        for (double rest : new double[]{0, .08}) {
            var samples = new float[rate];
            for (int i = 0; i < samples.length; i++) {
                double t = i / (double) rate;
                double old = t < .45 ? .20 * Math.sin(Math.TAU * 440 * t) : 0;
                double next = t >= .45 + rest ? .20 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(72) * t) : 0;
                samples[i] = (float) (old + next);
            }
            var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, rate));
            assertEquals(rest == 0, track.bridges(lead("prior", 69, .20, .36), lead("next", 72, .58, .7), List.of()));
        }
    }

    private static ReconstructionStages.Note lead(String id, int pitch, double start, double end) {
        return new ReconstructionStages.Note(id, 0, start, end, pitch, 90, "midi", 1);
    }

    private static ReconstructionLeadAudio.Track legatoTrack(double release, double tailLevel) {
        return legatoTrack(release, tailLevel, 1);
    }

    private static ReconstructionLeadAudio.Track legatoTrack(double release, double tailLevel, double reattack) {
        int rate = 12_000;
        var samples = new float[rate];
        for (int i = 0; i < samples.length; i++) {
            double time = i / (double) rate;
            double level = time < .20 ? 0 : time < release || time >= reattack ? .20 : tailLevel;
            samples[i] = (float) (level * Math.sin(Math.TAU * 440 * time)
                    + .07 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(64) * time)
                    + .07 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(72) * time));
        }
        return new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, rate));
    }

    private static double score(int pitch, double rate, double phase, boolean vibrato, boolean beating) {
        int sampleRate = 12_000;
        float[] samples = new float[sampleRate];
        double target = ReconstructionLeadAudio.hz(pitch);
        double carrier = phase;
        for (int i = 0; i < samples.length; i++) {
            double t = (double) i / sampleRate;
            double cents = vibrato ? 20 * Math.sin(Math.TAU * rate * t + phase) : 0;
            carrier += Math.TAU * target * Math.pow(2, cents / 1200) / sampleRate;
            double chord = 0;
            for (int offset : new int[]{-12, -5, 3}) {
                double chordPhase = Math.TAU * ReconstructionLeadAudio.hz(pitch + offset) * t;
                chord += .055 * (Math.sin(chordPhase) + .42 * Math.sin(2 * chordPhase)
                        + .32 * Math.sin(3 * chordPhase));
            }
            // A phase-modulated carrier gives the lead multiple FM partials.
            samples[i] = (float) (chord + .24 * Math.sin(carrier + .8 * Math.sin(carrier))
                    + (beating ? .04 * Math.sin(Math.TAU * (target + rate) * t) : 0));
        }
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, sampleRate));
        var competitors = List.of(chord(pitch - 12), chord(pitch - 5), chord(pitch + 3));
        return track.evidence(pitch, .08, .92, competitors).vibrato();
    }

    private static ReconstructionStages.Note chord(int pitch) {
        return new ReconstructionStages.Note("p" + pitch, 0, 0, 1, pitch, 90, "midi", 1);
    }
}
