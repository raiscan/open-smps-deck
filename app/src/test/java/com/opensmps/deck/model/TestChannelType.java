package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestChannelType {

    @Test
    void fmChannelsMapCorrectly() {
        for (int ch = 0; ch <= 4; ch++) {
            assertEquals(ChannelType.FM, ChannelType.fromChannelIndex(ch));
        }
    }

    @Test
    void dacChannelMapsCorrectly() {
        assertEquals(ChannelType.DAC, ChannelType.fromChannelIndex(5));
    }

    @Test
    void psgToneChannelsMapCorrectly() {
        for (int ch = 6; ch <= 8; ch++) {
            assertEquals(ChannelType.PSG_TONE, ChannelType.fromChannelIndex(ch));
        }
    }

    @Test
    void psgNoiseChannelMapsCorrectly() {
        assertEquals(ChannelType.PSG_NOISE, ChannelType.fromChannelIndex(9));
    }

    @Test
    void invalidChannelThrows() {
        assertThrows(IllegalArgumentException.class, () -> ChannelType.fromChannelIndex(10));
        assertThrows(IllegalArgumentException.class, () -> ChannelType.fromChannelIndex(-1));
    }
}
