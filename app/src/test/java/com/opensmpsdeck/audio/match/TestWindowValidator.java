package com.opensmpsdeck.audio.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestWindowValidator {

    private static float[] harmonicTone(double hz, double seconds, double... harmonicAmps) {
        int n = (int) (44100 * seconds);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double v = 0;
            for (int h = 0; h < harmonicAmps.length; h++) {
                v += harmonicAmps[h] * Math.sin(2 * Math.PI * hz * (h + 1) * i / 44100.0);
            }
            s[i] = (float) v;
        }
        return s;
    }

    private static float[] noise(double seconds, double amp, long seed) {
        var rng = new java.util.Random(seed);
        int n = (int) (44100 * seconds);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = (float) ((rng.nextDouble() * 2 - 1) * amp);
        return s;
    }

    @Test
    void estimatesFundamentalOfCleanTone() {
        float[] tone = harmonicTone(155.6, 0.5, 0.5, 0.25, 0.12);
        double f0 = WindowValidator.estimateF0(tone, 44100, 40, 1000);
        assertEquals(155.6, f0, 3.0);
    }

    @Test
    void estimatesLowBassFundamental() {
        float[] tone = harmonicTone(43.7, 0.8, 0.5, 0.3, 0.2, 0.1);
        double f0 = WindowValidator.estimateF0(tone, 44100, 30, 1000);
        assertEquals(43.7, f0, 1.5);
    }

    @Test
    void harmonicityHighForToneLowForNoise() {
        float[] tone = harmonicTone(155.6, 0.5, 0.5, 0.25, 0.12);
        float[] hiss = noise(0.5, 0.3, 7L);
        double hTone = WindowValidator.harmonicity(tone, 44100, 155.6);
        double hNoise = WindowValidator.harmonicity(hiss, 44100, 155.6);
        assertTrue(hTone > 0.8, "tone harmonicity " + hTone);
        assertTrue(hNoise < 0.4, "noise harmonicity " + hNoise);
    }

    @Test
    void validateAcceptsCleanMatchingWindow() {
        float[] tone = harmonicTone(155.6, 0.5, 0.5, 0.25, 0.12);
        var v = WindowValidator.validate(tone, 44100, 155.6);
        assertTrue(v.usable());
        assertEquals(155.6, v.anchoredHz(), 3.0);
    }

    @Test
    void validateSnapsSubOctaveLayer() {
        // audio actually sounds an octave below the MIDI label (sub-bass layer)
        float[] tone = harmonicTone(77.8, 0.6, 0.6, 0.3, 0.15);
        var v = WindowValidator.validate(tone, 44100, 155.6);
        assertTrue(v.usable());
        assertEquals(77.8, v.anchoredHz(), 2.0);
    }

    @Test
    void validateRejectsUnrelatedPitch() {
        // audio is at 5.9x the labeled pitch — reverb mush, not the note
        float[] tone = harmonicTone(258.0, 0.5, 0.5, 0.25);
        var v = WindowValidator.validate(tone, 44100, 43.7);
        assertFalse(v.usable());
    }

    @Test
    void validateRejectsNoise() {
        var v = WindowValidator.validate(noise(0.5, 0.3, 9L), 44100, 155.6);
        assertFalse(v.usable());
    }

    @Test
    void trimLeadingSilenceShiftsToOnset() {
        float[] tone = harmonicTone(155.6, 0.4, 0.5, 0.25);
        float[] padded = new float[(int) (0.12 * 44100) + tone.length];
        System.arraycopy(tone, 0, padded, (int) (0.12 * 44100), tone.length);
        int onset = WindowValidator.findOnset(padded, 44100);
        assertEquals(0.12 * 44100, onset, 0.03 * 44100);
    }
}
