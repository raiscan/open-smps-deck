package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestDacSample {

    @Test
    void constructorStoresNameAndRate() {
        byte[] data = {0x00, 0x40, (byte) 0x80, (byte) 0xFF};
        DacSample sample = new DacSample("Kick", data, 0x1A);

        assertEquals("Kick", sample.getName());
        assertEquals(0x1A, sample.getRate());
        assertArrayEquals(data, sample.getData());
    }

    @Test
    void getDataReturnsDefensiveCopy() {
        byte[] data = {0x10, 0x20, 0x30};
        DacSample sample = new DacSample("Snare", data, 0x05);

        byte[] returned = sample.getData();
        returned[0] = (byte) 0xFF;

        // Original internal data must be unaffected
        assertEquals(0x10, sample.getData()[0],
                "Modifying returned array should not affect internal data");
    }

    @Test
    void constructorClonesInput() {
        byte[] data = {0x01, 0x02, 0x03};
        DacSample sample = new DacSample("Tom", data, 0x0C);

        // Mutate the input array after construction
        data[0] = (byte) 0xFF;

        // Internal data must be unaffected
        assertEquals(0x01, sample.getData()[0],
                "Mutating input array should not affect internal data");
    }

    @Test
    void songDacSamplesListInitiallyEmpty() {
        Song song = new Song();
        assertNotNull(song.getDacSamples());
        assertTrue(song.getDacSamples().isEmpty(),
                "New Song should have an empty dacSamples list");
    }
}
