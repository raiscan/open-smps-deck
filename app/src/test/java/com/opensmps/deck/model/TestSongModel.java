package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestSongModel {

    @Test
    void testNewSongHasDefaults() {
        Song song = new Song();

        assertEquals("Untitled", song.getName());
        assertEquals(SmpsMode.S2, song.getSmpsMode());
        assertEquals(0x80, song.getTempo());
        assertEquals(1, song.getDividingTiming());
        assertEquals(0, song.getLoopPoint());

        assertTrue(song.getVoiceBank().isEmpty(), "Voice bank should be empty");
        assertTrue(song.getPsgEnvelopes().isEmpty(), "PSG envelopes should be empty");

        assertEquals(1, song.getPatterns().size(), "Should have 1 default pattern");
        Pattern p = song.getPatterns().get(0);
        assertEquals(0, p.getId());
        assertEquals(64, p.getRows());

        assertEquals(1, song.getOrderList().size(), "Should have 1 order row");
        int[] order = song.getOrderList().get(0);
        assertEquals(Pattern.CHANNEL_COUNT, order.length);
        for (int i = 0; i < order.length; i++) {
            assertEquals(0, order[i], "Order row channel " + i + " should default to 0");
        }
    }

    @Test
    void testFmVoiceRoundTrip() {
        byte[] voiceData = new byte[25];
        // algo=2 (bits 0-2), fb=6 (bits 3-5) => 0b00110_010 = 0x32
        voiceData[0] = 0x32;
        // Fill operator bytes with recognizable values
        for (int i = 1; i < 25; i++) {
            voiceData[i] = (byte) i;
        }

        FmVoice voice = new FmVoice("TestVoice", voiceData);

        assertEquals("TestVoice", voice.getName());
        assertEquals(2, voice.getAlgorithm());
        assertEquals(6, voice.getFeedback());

        // getData() should return a copy that matches
        byte[] retrieved = voice.getData();
        assertArrayEquals(voiceData, retrieved);

        // Modifying the returned copy should not affect internal state
        retrieved[0] = 0;
        assertEquals(2, voice.getAlgorithm(), "Modifying getData() result should not affect voice");
    }

    @Test
    void testFmVoiceOpParams() {
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice("OpTest", voiceData);

        // Set operator 0, param 0 (DT_MUL at byte[1])
        voice.setOpParam(0, 0, 0x71);
        assertEquals(0x71, voice.getOpParam(0, 0));

        // Set operator 2, param 3 (AM_D1R at byte[1 + 2*5 + 3] = byte[14])
        voice.setOpParam(2, 3, 0xAB);
        assertEquals(0xAB, voice.getOpParam(2, 3));

        // Set operator 3, param 4 (D1L_RR at byte[1 + 3*5 + 4] = byte[20])
        voice.setOpParam(3, 4, 0xFF);
        assertEquals(0xFF, voice.getOpParam(3, 4));

        // Verify the raw data reflects the writes
        byte[] raw = voice.getRawData();
        assertEquals((byte) 0x71, raw[1]);
        assertEquals((byte) 0xAB, raw[14]);
        assertEquals((byte) 0xFF, raw[20]);

        // Boundary checks
        assertThrows(IndexOutOfBoundsException.class, () -> voice.getOpParam(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> voice.getOpParam(4, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> voice.getOpParam(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> voice.getOpParam(0, 5));
    }

    @Test
    void testFmVoiceAlgoFeedbackSetters() {
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice("SetterTest", voiceData);

        voice.setAlgorithm(7);
        assertEquals(7, voice.getAlgorithm());
        assertEquals(0, voice.getFeedback(), "Setting algo should not change feedback");

        voice.setFeedback(5);
        assertEquals(5, voice.getFeedback());
        assertEquals(7, voice.getAlgorithm(), "Setting feedback should not change algo");

        // byte[0] should be 0b00101_111 = 0x2F
        assertEquals(0x2F, voice.getRawData()[0] & 0xFF);
    }

    @Test
    void testFmVoiceRejectsWrongSize() {
        assertThrows(IllegalArgumentException.class, () -> new FmVoice("Bad", new byte[24]));
        assertThrows(IllegalArgumentException.class, () -> new FmVoice("Bad", new byte[26]));
        assertThrows(IllegalArgumentException.class, () -> new FmVoice("Bad", null));
    }

    @Test
    void testPsgEnvelopeRoundTrip() {
        byte[] envData = new byte[]{0, 0, 1, 1, 2, 3, 4, 5, 6, 7, (byte) 0x80};
        PsgEnvelope env = new PsgEnvelope("TestEnv", envData);

        assertEquals("TestEnv", env.getName());
        assertEquals(10, env.getStepCount());
        assertArrayEquals(envData, env.getData());

        // Verify individual steps
        assertEquals(0, env.getStep(0));
        assertEquals(0, env.getStep(1));
        assertEquals(1, env.getStep(2));
        assertEquals(7, env.getStep(9));

        // Modifying the returned copy should not affect internal state
        byte[] copy = env.getData();
        copy[0] = 99;
        assertEquals(0, env.getStep(0), "Modifying getData() result should not affect envelope");
    }

    @Test
    void testPsgEnvelopeSetStep() {
        byte[] envData = new byte[]{0, 0, 0, (byte) 0x80};
        PsgEnvelope env = new PsgEnvelope("Mutable", envData);

        assertEquals(3, env.getStepCount());
        env.setStep(1, 5);
        assertEquals(5, env.getStep(1));
    }

    @Test
    void testPsgEnvelopeStepBounds() {
        byte[] envData = new byte[]{0, 1, (byte) 0x80};
        PsgEnvelope env = new PsgEnvelope("Bounds", envData);

        assertEquals(2, env.getStepCount());
        assertThrows(IndexOutOfBoundsException.class, () -> env.getStep(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> env.getStep(2));
    }

    @Test
    void testPatternHasTenChannels() {
        assertEquals(10, Pattern.CHANNEL_COUNT);

        Pattern pattern = new Pattern(0, 64);
        assertEquals(10, pattern.getTrackCount());

        // All tracks should be initialized to empty arrays
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            assertNotNull(pattern.getTrackData(ch), "Track " + ch + " should not be null");
            assertEquals(0, pattern.getTrackData(ch).length, "Track " + ch + " should be empty");
        }
    }

    @Test
    void testPatternTrackData() {
        Pattern pattern = new Pattern(1, 32);
        assertEquals(1, pattern.getId());
        assertEquals(32, pattern.getRows());

        byte[] trackData = new byte[]{0x01, 0x02, 0x03};
        pattern.setTrackData(0, trackData);
        assertArrayEquals(trackData, pattern.getTrackData(0));

        pattern.setRows(48);
        assertEquals(48, pattern.getRows());
    }

    @Test
    void testOrderListManipulation() {
        Song song = new Song();

        // Song starts with 1 order row
        assertEquals(1, song.getOrderList().size());

        // Add a second order row
        int[] secondOrder = new int[Pattern.CHANNEL_COUNT];
        secondOrder[0] = 1; // FM1 plays pattern 1
        secondOrder[5] = 2; // PSG1 plays pattern 2
        song.getOrderList().add(secondOrder);
        assertEquals(2, song.getOrderList().size());

        // Verify the second row's values
        int[] retrieved = song.getOrderList().get(1);
        assertEquals(1, retrieved[0]);
        assertEquals(2, retrieved[5]);

        // Remove the first order row
        song.getOrderList().remove(0);
        assertEquals(1, song.getOrderList().size());
        assertEquals(1, song.getOrderList().get(0)[0], "After removing first, second becomes first");
    }

    @Test
    void testSongSetters() {
        Song song = new Song();

        song.setName("Green Hill Zone");
        assertEquals("Green Hill Zone", song.getName());

        song.setSmpsMode(SmpsMode.S3K);
        assertEquals(SmpsMode.S3K, song.getSmpsMode());

        song.setTempo(0x60);
        assertEquals(0x60, song.getTempo());

        song.setDividingTiming(3);
        assertEquals(3, song.getDividingTiming());

        song.setLoopPoint(2);
        assertEquals(2, song.getLoopPoint());
    }

    @Test
    void testSongVoiceBankManipulation() {
        Song song = new Song();
        assertTrue(song.getVoiceBank().isEmpty());

        byte[] voiceData = new byte[25];
        voiceData[0] = 0x12;
        song.getVoiceBank().add(new FmVoice("Voice0", voiceData));

        assertEquals(1, song.getVoiceBank().size());
        assertEquals("Voice0", song.getVoiceBank().get(0).getName());
    }

    @Test
    void testSongPsgEnvelopeManipulation() {
        Song song = new Song();
        assertTrue(song.getPsgEnvelopes().isEmpty());

        song.getPsgEnvelopes().add(new PsgEnvelope("Env0", new byte[]{0, 1, 2, (byte) 0x80}));

        assertEquals(1, song.getPsgEnvelopes().size());
        assertEquals("Env0", song.getPsgEnvelopes().get(0).getName());
        assertEquals(3, song.getPsgEnvelopes().get(0).getStepCount());
    }
}
