package com.opensmpsdeck.model;

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

    @Test
    void testRateZero() {
        DacSample sample = new DacSample("Zero", new byte[]{0x01}, 0);
        assertEquals(0, sample.getRate());
    }

    @Test
    void testRateMax255() {
        DacSample sample = new DacSample("Max", new byte[]{0x01}, 255);
        assertEquals(255, sample.getRate());
    }

    @Test
    void testRateOverflow256MaskedToZero() {
        DacSample sample = new DacSample("Overflow", new byte[]{0x01}, 256);
        assertEquals(0, sample.getRate(),
                "rate=256 should be masked to 0 via (rate & 0xFF)");
    }

    @Test
    void testSetRate() {
        DacSample sample = new DacSample("Test", new byte[]{0x01}, 0x00);
        sample.setRate(100);
        assertEquals(100, sample.getRate());
    }

    @Test
    void testSetName() {
        DacSample sample = new DacSample("OldName", new byte[]{0x01}, 0x10);
        sample.setName("NewName");
        assertEquals("NewName", sample.getName());
    }

    @Test
    void testSetData() {
        DacSample sample = new DacSample("Test", new byte[]{0x01, 0x02}, 0x10);
        byte[] newData = {0x0A, 0x0B, 0x0C};
        sample.setData(newData);
        assertArrayEquals(newData, sample.getData());

        // Verify setData clones — mutating newData should not affect internal data
        newData[0] = (byte) 0xFF;
        assertEquals(0x0A, sample.getData()[0],
                "setData should clone the input array");
    }

    @Test
    void testGetDataDirect() {
        DacSample sample = new DacSample("Test", new byte[]{0x01, 0x02, 0x03}, 0x10);
        byte[] ref1 = sample.getDataDirect();
        byte[] ref2 = sample.getDataDirect();
        assertSame(ref1, ref2,
                "getDataDirect() should return the same internal array reference");
    }

    @Test
    void testEmptyDataArray() {
        DacSample sample = new DacSample("Empty", new byte[]{}, 0x10);
        assertEquals(0, sample.getData().length,
                "Sample created with empty data should have length 0");
    }
}
