package com.opensmpsdeck.audio.match;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSpectralTarget {

    private static float[] sine(double hz, double seconds, double amp) {
        int n = (int) (44100 * seconds);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / 44100));
        return s;
    }

    @Test
    void pureSineConcentratesInFirstHarmonic() {
        SpectralTarget t = SpectralTarget.extract(sine(440, 1.0, 0.5), 44100, 440);
        assertEquals(0.0, t.harmonicLevels()[0], 1.0);   // normalized peak = 0 dB
        for (int h = 1; h < t.harmonicLevels().length; h++) {
            assertTrue(t.harmonicLevels()[h] < -30, "harmonic " + (h + 1) + " should be silent");
        }
    }

    @Test
    void twoHarmonicSignalShowsExpectedRatio() {
        float[] sig = sine(440, 1.0, 0.5);
        float[] second = sine(880, 1.0, 0.25); // -6 dB relative
        for (int i = 0; i < sig.length; i++) sig[i] += second[i];
        SpectralTarget t = SpectralTarget.extract(sig, 44100, 440);
        assertEquals(-6.0, t.harmonicLevels()[1] - t.harmonicLevels()[0], 1.5);
    }

    @Test
    void envelopeTracksAmplitude() {
        // 0.5 s loud then 0.5 s quiet
        float[] sig = new float[44100];
        System.arraycopy(sine(440, 0.5, 0.8), 0, sig, 0, 22050);
        System.arraycopy(sine(440, 0.5, 0.1), 0, sig, 22050, 22050);
        SpectralTarget t = SpectralTarget.extract(sig, 44100, 440);
        double[] env = t.rmsEnvelope();
        assertEquals(1.0, env[env.length / 4], 0.1);     // first half ≈ peak
        assertTrue(env[3 * env.length / 4] < 0.25);      // second half quiet
    }

    @Test
    void distanceIsZeroForIdenticalTargets() {
        SpectralTarget a = SpectralTarget.extract(sine(440, 1.0, 0.5), 44100, 440);
        assertEquals(0.0, SpectralTarget.distance(a, a), 1e-9);
    }

    @Test
    void distanceGrowsWithSpectralDifference() {
        SpectralTarget pure = SpectralTarget.extract(sine(440, 1.0, 0.5), 44100, 440);
        float[] rich = sine(440, 1.0, 0.4);
        float[] h3 = sine(1320, 1.0, 0.3);
        for (int i = 0; i < rich.length; i++) rich[i] += h3[i];
        SpectralTarget bright = SpectralTarget.extract(rich, 44100, 440);
        assertTrue(SpectralTarget.distance(pure, bright) > SpectralTarget.distance(pure, pure));
    }
}
