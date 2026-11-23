package com.opensmpsdeck.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class EvidenceSpectrumTest {
    private static final int RATE = 44_100;

    @Test
    void pairedColumnsMatchSingleColumnTransforms() {
        var audio = audio();
        double[] times = {.30, .34, .38, .42, .46, .50, .54, 1.4, -.1};
        for (boolean emphasize : new boolean[]{false, true}) {
            var together = EvidenceSpectrum.measure(audio, times, 48, 71, 48, .4, emphasize);
            for (int x = 0; x < times.length; x++) {
                var alone = EvidenceSpectrum.measure(audio, new double[]{times[x]}, 48, 71, 48, .4, emphasize);
                for (int y = 0; y < 48; y++)
                    assertClose(alone.powers()[y], together.powers()[y * times.length + x]);
            }
        }
    }

    @Test
    void pairedTransformSplitsIntoBothInputs() {
        var samples = audio().samples();
        double[] first = new double[301], second = new double[301];
        for (int i = 0; i < first.length; i++) {
            first[i] = Math.sin(Math.PI * i / 300);
            second[i] = Math.cos(Math.PI * i / 300) * .3;
        }
        var packed = new LeadEffectMatcher.Spectrum(1024);
        var split = new LeadEffectMatcher.Spectrum(1024);
        packed.transformPair(samples, 1000, first, 1100, second);
        EvidenceSpectrum.split(packed, split);
        var a = new LeadEffectMatcher.Spectrum(1024);
        var b = new LeadEffectMatcher.Spectrum(1024);
        a.transform(samples, 1000, first);
        b.transform(samples, 1100, second);
        for (int bin = 0; bin <= 512; bin++) {
            assertEquals(a.real[bin], packed.real[bin], 1e-9);
            assertEquals(a.imag[bin], packed.imag[bin], 1e-9);
            assertEquals(b.real[bin], split.real[bin], 1e-9);
            assertEquals(b.imag[bin], split.imag[bin], 1e-9);
        }
    }

    @Test
    void sharedSourceMatchesSeparateMeasurements() {
        var audio = audio();
        double[] times = {.3, .35, .4, .45};
        for (boolean reassigned : new boolean[]{false, true}) {
            var shared = new EvidenceSpectrum.Source(audio);
            for (int block : new int[]{6, 3, 9, 4}) {
                int low = block * 12, high = Math.min(127, low + 11), rows = 4 * (high - low + 1);
                var once = shared.measure(times, low, high, rows, .2, false, reassigned);
                var separate = EvidenceSpectrum.measure(audio, times, low, high, rows, .2, false, reassigned);
                assertEquals(separate.peak(), once.peak());
                for (int i = 0; i < once.powers().length; i++) assertEquals(separate.powers()[i], once.powers()[i]);
            }
        }
    }

    private static EvidenceSpectrum.Audio audio() {
        var random = new Random(7);
        var samples = new float[RATE];
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) RATE;
            samples[i] = (float) (.4 * Math.sin(Math.TAU * 220 * t) + .2 * Math.sin(Math.TAU * 659.3 * t)
                    + .1 * Math.sin(Math.TAU * 98 * t) + .02 * random.nextGaussian());
        }
        return new EvidenceSpectrum.Audio(samples, RATE, 0, 1);
    }

    private static void assertClose(float expected, float actual) {
        assertEquals(expected, actual, 1e-6 * Math.max(Math.abs(expected), 1e-12));
    }
}
