package com.opensmpsdeck.audio.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestModulationDetector {

    /** Tone with sinusoidal vibrato: depthCents peak-to-peak at rateHz. */
    private static float[] vibratoTone(double hz, double seconds, double depthCents,
                                       double rateHz) {
        int n = (int) (44100 * seconds);
        float[] s = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / 44100.0;
            double cents = depthCents / 2 * Math.sin(2 * Math.PI * rateHz * t);
            double f = hz * Math.pow(2, cents / 1200.0);
            phase += 2 * Math.PI * f / 44100.0;
            s[i] = (float) (0.5 * Math.sin(phase) + 0.2 * Math.sin(2 * phase));
        }
        return s;
    }

    @Test
    void steadyToneHasNegligibleModulation() {
        var m = ModulationDetector.measure(vibratoTone(155.6, 1.0, 0, 0), 44100, 155.6);
        assertTrue(m.depthCents() < 12, "steady tone depth " + m.depthCents());
    }

    @Test
    void detectsModerateVibrato() {
        var m = ModulationDetector.measure(vibratoTone(155.6, 1.2, 60, 5.5), 44100, 155.6);
        assertEquals(60, m.depthCents(), 20);
        assertEquals(5.5, m.rateHz(), 1.5);
    }

    @Test
    void detectsDeepSlowVibrato() {
        var m = ModulationDetector.measure(vibratoTone(220, 1.5, 120, 3.0), 44100, 220);
        assertEquals(120, m.depthCents(), 35);
        assertEquals(3.0, m.rateHz(), 1.0);
    }

    @Test
    void shortSliceReturnsZero() {
        var m = ModulationDetector.measure(vibratoTone(155.6, 0.15, 80, 6), 44100, 155.6);
        assertEquals(0, m.depthCents(), 1e-9); // too short to track — report none
    }
}
