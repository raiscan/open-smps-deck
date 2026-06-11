package com.opensmpsdeck.audio.match;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestFft {

    @Test
    void impulseHasFlatSpectrum() {
        double[] re = new double[64];
        double[] im = new double[64];
        re[0] = 1.0;
        Fft.transform(re, im);
        for (int i = 0; i < 64; i++) {
            assertEquals(1.0, Math.hypot(re[i], im[i]), 1e-9);
        }
    }

    @Test
    void sinePeaksAtItsBin() {
        int n = 1024;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.sin(2 * Math.PI * 16 * i / n); // bin 16
        Fft.transform(re, im);
        int peak = 0;
        double max = 0;
        for (int i = 1; i < n / 2; i++) {
            double mag = Math.hypot(re[i], im[i]);
            if (mag > max) { max = mag; peak = i; }
        }
        assertEquals(16, peak);
    }

    @Test
    void rejectsNonPowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> Fft.transform(new double[100], new double[100]));
    }
}
