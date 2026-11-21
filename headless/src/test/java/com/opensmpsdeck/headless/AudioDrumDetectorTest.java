package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioDrumDetectorTest {
    private static final int RATE = 24_000;

    @Test
    void classifiesKickSnareAndHatAndKeepsCoincidentHits() throws Exception {
        float[] samples = new float[RATE * 2];
        add(samples, 0, 36);
        add(samples, .4, 38);
        add(samples, .8, 42);
        add(samples, 1.2, 36);
        add(samples, 1.2, 42);
        var hits = SpectralDrumDetector.detect(new ReferenceAudio(samples, RATE));
        assertEquals(List.of(36, 38, 42, 36, 42), hits.stream().map(AudioDrumDetector.Hit::pitch).toList());
        double[] times = {0, .4, .8, 1.2, 1.2};
        for (int i = 0; i < times.length; i++) assertEquals(times[i], hits.get(i).seconds(), .025);
    }

    @Test
    void silenceAndSustainedLeakDoNotGenerateRepeatedHits() throws Exception {
        float[] samples = new float[RATE];
        assertTrue(SpectralDrumDetector.detect(new ReferenceAudio(samples, RATE)).isEmpty());
        for (int i = 0; i < samples.length; i++)
            samples[i] = (float) (.0001 * Math.sin(Math.TAU * 110 * i / RATE));
        assertTrue(SpectralDrumDetector.detect(new ReferenceAudio(samples, RATE)).isEmpty());
    }

    private static void add(float[] samples, double seconds, int pitch) {
        var noise = new Random(42);
        double previous = 0;
        for (int i = 0; i < RATE / 6; i++) {
            double t = i / (double) RATE;
            double next = noise.nextDouble(-1, 1);
            double value = switch (pitch) {
                case 36 -> .7 * Math.sin(Math.TAU * (51 * t + 90 * t * t)) * Math.exp(-23 * t);
                case 38 -> (.36 * Math.sin(Math.TAU * 200 * t) + .42 * next) * Math.exp(-35 * t);
                default -> .23 * (next - previous) * Math.exp(-75 * t);
            };
            previous = next;
            samples[(int) (seconds * RATE) + i] += (float) value;
        }
    }
}
