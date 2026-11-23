package com.opensmpsdeck.headless.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BenchVoiceTest {
    @Test void algorithmMatchIgnoresFeedbackAndEveryOtherParameter() {
        byte[] original = new byte[25], sibling = new byte[25], other = new byte[25];
        original[0] = 0x3d;
        sibling[0] = 0x05;
        sibling[21] = 0x18;
        other[0] = 0x3e;

        assertEquals(true, BenchVoice.sameAlgorithm(original, sibling));
        assertEquals(false, BenchVoice.sameAlgorithm(original, other));
        assertNull(BenchVoice.sameAlgorithm(original, null));
    }
}
