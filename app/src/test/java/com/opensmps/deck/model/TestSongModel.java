package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestSongModel {

    @Test
    void testNewSongHasDefaults() {
        Song song = new Song();

        assertEquals("Untitled", song.getName());
        assertEquals(SmpsMode.S2, song.getSmpsMode());
        assertEquals(ArrangementMode.HIERARCHICAL, song.getArrangementMode());
        assertEquals(0x80, song.getTempo());
        assertEquals(1, song.getDividingTiming());
        assertEquals(0, song.getLoopPoint());
        assertNotNull(song.getHierarchicalArrangement(), "Hierarchical arrangement should be created by default");

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

        // Set operator 2, param 3 (AM_D1R at byte[1 + 2*6 + 3] = byte[16])
        voice.setOpParam(2, 3, 0xAB);
        assertEquals(0xAB, voice.getOpParam(2, 3));

        // Set operator 3, param 4 (D2R at byte[1 + 3*6 + 4] = byte[23])
        voice.setOpParam(3, 4, 0xFF);
        assertEquals(0xFF, voice.getOpParam(3, 4));

        // Verify the raw data reflects the writes
        byte[] raw = voice.getDataUnsafe();
        assertEquals((byte) 0x71, raw[1]);
        assertEquals((byte) 0xAB, raw[16]);
        assertEquals((byte) 0xFF, raw[23]);

        // Boundary checks
        assertThrows(IndexOutOfBoundsException.class, () -> voice.getOpParam(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> voice.getOpParam(4, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> voice.getOpParam(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> voice.getOpParam(0, 6));
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
        assertEquals(0x2F, voice.getDataUnsafe()[0] & 0xFF);
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

    // --- FmVoice bit-field accessor tests ---

    @Test
    void testFmVoiceMulDt() {
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice("MulDt", voiceData);

        // DT_MUL byte: DT bits 4-6, MUL bits 0-3
        voice.setMul(0, 0x0F);
        assertEquals(0x0F, voice.getMul(0));
        assertEquals(0, voice.getDt(0), "Setting MUL should not change DT");

        voice.setDt(0, 0x07);
        assertEquals(0x07, voice.getDt(0));
        assertEquals(0x0F, voice.getMul(0), "Setting DT should not change MUL");

        // Raw byte should be 0x7F (DT=7 << 4 | MUL=15)
        assertEquals(0x7F, voice.getOpParam(0, 0));
    }

    @Test
    void testFmVoiceTl() {
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice("Tl", voiceData);

        voice.setTl(1, 127);
        assertEquals(127, voice.getTl(1));

        // Bit 7 should always be masked off
        voice.setTl(1, 0xFF);
        assertEquals(0x7F, voice.getTl(1));
    }

    @Test
    void testFmVoiceRsAr() {
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice("RsAr", voiceData);

        // RS_AR byte: RS bits 6-7, AR bits 0-4
        voice.setAr(2, 0x1F);
        assertEquals(0x1F, voice.getAr(2));
        assertEquals(0, voice.getRs(2), "Setting AR should not change RS");

        voice.setRs(2, 3);
        assertEquals(3, voice.getRs(2));
        assertEquals(0x1F, voice.getAr(2), "Setting RS should not change AR");

        // Raw byte should be 0xDF (RS=3 << 6 | AR=31)
        assertEquals(0xDF, voice.getOpParam(2, 2));
    }

    @Test
    void testFmVoiceAmD1r() {
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice("AmD1r", voiceData);

        // AM_D1R byte: AM bit 7, D1R bits 0-4
        voice.setD1r(3, 0x1F);
        assertEquals(0x1F, voice.getD1r(3));
        assertFalse(voice.getAm(3), "D1R should not set AM");

        voice.setAm(3, true);
        assertTrue(voice.getAm(3));
        assertEquals(0x1F, voice.getD1r(3), "Setting AM should not change D1R");

        // Raw byte should be 0x9F (AM=1 << 7 | D1R=31)
        assertEquals(0x9F, voice.getOpParam(3, 3));

        voice.setAm(3, false);
        assertFalse(voice.getAm(3));
        assertEquals(0x1F, voice.getD1r(3), "Clearing AM should not change D1R");
    }

    @Test
    void testFmVoiceD1lRr() {
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice("D1lRr", voiceData);

        // D1L_RR byte: D1L bits 4-7, RR bits 0-3
        voice.setRr(0, 0x0F);
        assertEquals(0x0F, voice.getRr(0));
        assertEquals(0, voice.getD1l(0), "Setting RR should not change D1L");

        voice.setD1l(0, 0x0F);
        assertEquals(0x0F, voice.getD1l(0));
        assertEquals(0x0F, voice.getRr(0), "Setting D1L should not change RR");

        // Raw byte should be 0xFF (D1L=15 << 4 | RR=15)
        assertEquals(0xFF, voice.getOpParam(0, 5));
    }

    @Test
    void testFmVoiceIsCarrier() {
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice("Carrier", voiceData);

        // Algorithm 0: only Op4 (SMPS index 3) is carrier
        voice.setAlgorithm(0);
        assertFalse(voice.isCarrier(0));
        assertFalse(voice.isCarrier(1));
        assertFalse(voice.isCarrier(2));
        assertTrue(voice.isCarrier(3));

        // Algorithm 7: all operators are carriers
        voice.setAlgorithm(7);
        assertTrue(voice.isCarrier(0));
        assertTrue(voice.isCarrier(1));
        assertTrue(voice.isCarrier(2));
        assertTrue(voice.isCarrier(3));

        // Algorithm 4: Op2 (idx 2) and Op4 (idx 3) are carriers
        voice.setAlgorithm(4);
        assertFalse(voice.isCarrier(0));
        assertFalse(voice.isCarrier(1));
        assertTrue(voice.isCarrier(2));
        assertTrue(voice.isCarrier(3));
    }

    @Test
    void testPsgEnvelopeAddStep() {
        byte[] data = {0, 1, 2, (byte) 0x80};
        PsgEnvelope env = new PsgEnvelope("Test", data);
        assertEquals(3, env.getStepCount());
        env.addStep(4);
        assertEquals(4, env.getStepCount());
        assertEquals(4, env.getStep(3));
        byte[] raw = env.getData();
        assertEquals((byte) 0x80, raw[4]);
    }

    @Test
    void testPsgEnvelopeRemoveStep() {
        byte[] data = {0, 1, 2, (byte) 0x80};
        PsgEnvelope env = new PsgEnvelope("Test", data);
        env.removeStep(1);
        assertEquals(2, env.getStepCount());
        assertEquals(0, env.getStep(0));
        assertEquals(2, env.getStep(1));
        byte[] raw = env.getData();
        assertEquals((byte) 0x80, raw[2]);
    }

    @Test
    void testPsgEnvelopeRemoveLastStep() {
        byte[] data = {5, (byte) 0x80};
        PsgEnvelope env = new PsgEnvelope("Test", data);
        env.removeStep(0);
        assertEquals(0, env.getStepCount());
        byte[] raw = env.getData();
        assertEquals(1, raw.length);
        assertEquals((byte) 0x80, raw[0]);
    }

    @Test
    void testPsgEnvelopeSetData() {
        byte[] data = {0, (byte) 0x80};
        PsgEnvelope env = new PsgEnvelope("Test", data);
        byte[] newData = {3, 2, 1, 0, (byte) 0x80};
        env.setData(newData);
        assertEquals(4, env.getStepCount());
        assertEquals(3, env.getStep(0));
    }

    @Test
    void testFmVoiceDisplayOrder() {
        // SMPS order: Op1=0, Op3=1, Op2=2, Op4=3
        // Display order: Op1=0, Op2=1, Op3=2, Op4=3
        // displayToSmps: display[0]=0, display[1]=2, display[2]=1, display[3]=3
        assertEquals(0, FmVoice.displayToSmps(0)); // Display Op1 -> SMPS 0
        assertEquals(2, FmVoice.displayToSmps(1)); // Display Op2 -> SMPS 2
        assertEquals(1, FmVoice.displayToSmps(2)); // Display Op3 -> SMPS 1
        assertEquals(3, FmVoice.displayToSmps(3)); // Display Op4 -> SMPS 3
    }

    @Test
    void testPsgEnvelopeZeroSteps() {
        PsgEnvelope env = new PsgEnvelope("Empty", new byte[]{(byte) 0x80});
        assertEquals(0, env.getStepCount());
        assertArrayEquals(new byte[]{(byte) 0x80}, env.getData());
    }

    @Test
    void songSupportsHierarchicalArrangementMode() {
        var song = new Song();
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);
        assertEquals(ArrangementMode.HIERARCHICAL, song.getArrangementMode());
    }

    @Test
    void songStoresHierarchicalArrangement() {
        var song = new Song();
        var arr = new HierarchicalArrangement();
        arr.getPhraseLibrary().createPhrase("Test", ChannelType.FM);
        song.setHierarchicalArrangement(arr);
        assertNotNull(song.getHierarchicalArrangement());
        assertEquals(1, song.getHierarchicalArrangement().getPhraseLibrary().getAllPhrases().size());
    }
}
