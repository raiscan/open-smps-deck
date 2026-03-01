package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestStructuredArrangementModel {

    @Test
    void structuredArrangementInitializesAllChannelLanes() {
        StructuredArrangement arrangement = new StructuredArrangement();
        assertEquals(6, arrangement.getTicksPerRow());
        assertEquals(Pattern.CHANNEL_COUNT, arrangement.getChannels().size());
        for (int i = 0; i < arrangement.getChannels().size(); i++) {
            assertNotNull(arrangement.getChannels().get(i));
        }
    }

    @Test
    void blockDefinitionTracksDefensiveCopies() {
        BlockDefinition block = new BlockDefinition(7, "Intro", 96);
        byte[] data = new byte[] { (byte) 0xA1, 0x10 };
        block.setTrackData(0, data);
        data[0] = (byte) 0xA4;

        byte[] fromModel = block.getTrackData(0);
        assertEquals(0xA1, fromModel[0] & 0xFF);

        fromModel[0] = (byte) 0xA8;
        assertEquals(0xA1, block.getTrackData(0)[0] & 0xFF);
    }

    @Test
    void blockRefNormalizesStartAndRepeat() {
        BlockRef ref = new BlockRef(1, 10);
        ref.setStartTick(-5);
        ref.setRepeatCount(0);
        assertEquals(0, ref.getStartTick());
        assertEquals(1, ref.getRepeatCount());
    }

    @Test
    void blockDefinitionRejectsInvalidChannel() {
        BlockDefinition block = new BlockDefinition(0, "A", 48);
        assertThrows(IndexOutOfBoundsException.class, () -> block.getTrackData(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> block.setTrackData(Pattern.CHANNEL_COUNT, new byte[0]));
    }
}

