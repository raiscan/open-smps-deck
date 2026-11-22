package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceAttackShapeTest {
    @Test void distinguishesLateBrightnessAndSlowRiseFromTheSameAttackAtAnotherLevel() {
        var shape = new VoiceAttackShape();
        var reference = shape.measure(attack(false, false, 1));
        var quieter = shape.measure(attack(false, false, .25));
        var bloom = shape.measure(attack(false, true, 1));
        var slow = shape.measure(attack(true, false, 1));

        assertEquals(0, reference.distance(quieter), 1e-8);
        assertTrue(reference.distance(bloom) > .1);
        assertTrue(reference.distance(slow) > .1);
    }

    private static float[] attack(boolean slow, boolean bloom, double gain) {
        var samples = new float[VoiceAttackShape.SAMPLES];
        for (int i = 0; i < samples.length; i++) {
            double time = i / (double) VoiceSpectra.RATE;
            double rise = slow ? Math.min(1, time / .045) : 1;
            double overtone = bloom ? Math.min(1, time / .03) : Math.max(0, 1 - time / .03);
            samples[i] = (float) (gain * rise * (.3 * Math.sin(Math.TAU * 400 * time)
                    + .3 * overtone * Math.sin(Math.TAU * 2400 * time)));
        }
        return samples;
    }
}
