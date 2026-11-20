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
