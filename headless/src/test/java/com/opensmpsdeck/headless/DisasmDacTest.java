package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DisasmDacTest {
    @Test void everyRateInvertsTheSkdisasmMacro() {
        for (int rate = 0; rate <= 255; rate++) {
            int hz = S3kDisasmDac.sampleRate(rate);
            assertEquals(rate, skdisasmRate(hz), "rate " + rate + ", WAV Hz " + hz);
        }
    }

    private static int skdisasmRate(int hz) {
        return 1 + (53693175 / 15 / hz - 303 / 2 + 13 / 2) / 13;
    }

    @Test void scalesSharedSampleRatesWithinTheIntegerInterval() {
        for (int first = 0; first <= 255; first++) {
            int hz = S3kDisasmDac.sampleRate(first);
            for (int second = 0; second <= 255; second++) {
                int scaled = (int) (hz * Double.parseDouble(S3kDisasmDac.scale(hz, second)));
                assertEquals(second, skdisasmRate(scaled));
            }
        }
    }

    @Test void closedWavsSurviveTheEncoderIncludingWrappingAndOddLengths() {
        var random = new Random(19);
        byte[] input = new byte[1025];
        random.nextBytes(input);
        byte[] closed = S3kDisasmDac.quantize(input);
        assertEquals(1026, closed.length);
        assertFalse(java.util.Arrays.equals(input, closed));
        assertArrayEquals(closed, S3kDisasmDac.quantize(closed));
        assertArrayEquals(new byte[] {(byte) 128, (byte) 129},
                S3kDisasmDac.quantize(new byte[] {(byte) 128, (byte) 129}));
    }
}
