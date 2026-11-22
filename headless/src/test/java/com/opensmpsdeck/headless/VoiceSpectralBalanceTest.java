package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceSpectralBalanceTest {
    @Test void penalizesExcessUpperBandEnergyAcrossGainChanges() {
        var analyzer = new VoiceSpectralBalance();
        var source = analyzer.measure(tone(.14, 1), 0, 48000, 440);
        var quieter = analyzer.measure(tone(.14, .2), 0, 48000, 440);
        var brighter = analyzer.measure(tone(.23, 1), 0, 48000, 440);
        var buzzy = analyzer.measure(tone(.8, 1), 0, 48000, 440);

        assertEquals(0, source.excess(quieter), 1e-6);
        assertEquals(0, source.excess(brighter), 1e-6);
        assertTrue(source.excess(buzzy) > 5);
        assertTrue(source.distance(buzzy) > source.distance(brighter));
    }

    @Test void appliesFeedbackAndModulatorOffsetsWithoutChangingCarriersOrRelease() {
        var patch = new byte[25];
        patch[0] = (byte) (0x40 | 7 << 3 | 5);
        patch[21] = (byte) (128 | 126);
        patch[22] = (byte) (128 | 18);
        patch[23] = 24;
        patch[24] = 31;
        patch[20] = (byte) 0x97;
        var timbre = new ReconstructionVoicing.FmTimbre("asset", List.of(8, -5, 2, 5), 2);
        timbre.apply(patch);
        assertEquals(0x40 | 2 << 3 | 5, Byte.toUnsignedInt(patch[0]));
        assertEquals(255, Byte.toUnsignedInt(patch[21]));
        assertEquals(146, Byte.toUnsignedInt(patch[22]));
        assertEquals(24, patch[23]);
        assertEquals(31, patch[24]);
        assertEquals(0x97, Byte.toUnsignedInt(patch[20]));
    }

    private static float[] tone(double upper, double gain) {
        var samples = new float[4096];
        for (int i = 0; i < samples.length; i++) {
            double phase = Math.TAU * 440 * i / 48000;
            samples[i] = (float) (gain * (Math.sin(phase) + upper * Math.sin(13 * phase)));
        }
        return samples;
    }
}
