package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LowRegisterSpectrumTest {
    @Test void retainsTheFundamentalBelowTheBroadSpectrumCutoff() {
        int pitch = 24; // C1, 32.7 Hz
        var bands = new LowRegisterSpectrum();
        var target = bands.profile(note(pitch, 1, .25, .02), pitch);
        var matching = bands.profile(note(pitch, .5, .125, .01), pitch);
        var weakFundamental = bands.profile(note(pitch, .05, 1, .02), pitch);

        assertTrue(LowRegisterSpectrum.distance(target, matching) < 1e-6);
        assertTrue(LowRegisterSpectrum.distance(target, weakFundamental) > 50);
    }

    @Test void penalizesUpperHarmonicExcessMoreThanAnEqualDeficit() {
        int pitch = 36;
        var bands = new LowRegisterSpectrum();
        var target = bands.profile(note(pitch, 1, .35, .16), pitch);
        var brighter = bands.profile(note(pitch, 1, .35, .32), pitch);
        var darker = bands.profile(note(pitch, 1, .35, .08), pitch);

        assertTrue(LowRegisterSpectrum.distance(target, brighter) > LowRegisterSpectrum.distance(target, darker));
    }

    private static float[] note(int pitch, double fundamental, double second, double eighth) {
        float[] audio = new float[7 * VoiceSpectra.SAMPLES_PER_TICK];
        double f0 = 440 * Math.pow(2, (pitch - 69) / 12.0);
        for (int i = 0; i < audio.length; i++) {
            double phase = Math.TAU * f0 * i / VoiceSpectra.RATE;
            audio[i] = (float) (fundamental * Math.sin(phase) + second * Math.sin(2 * phase)
                    + eighth * Math.sin(8 * phase));
        }
        return audio;
    }
}
