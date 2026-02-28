package com.opensmps.deck.audio;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestPatternCompiler {

    @Test
    void testCompilesMinimalSong() {
        Song song = new Song();
        song.setTempo(0x80);
        song.setDividingTiming(1);

        byte[] voiceData = new byte[25];
        voiceData[0] = 0x00; // algo 0
        song.getVoiceBank().add(new FmVoice("Test", voiceData));

        // Put note data in FM channel 0 of pattern 0
        // E1 00 (set voice 0), A1 (note C4), 30 (duration), F2 (track end)
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xE1, 0x00, (byte)0xA1, 0x30, (byte)0xF2 });

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        assertNotNull(smps);
        assertTrue(smps.length > 0);

        // Verify header basics
        assertEquals(1, smps[2] & 0xFF, "Should have 1 FM channel");
        assertEquals(0, smps[3] & 0xFF, "Should have 0 PSG channels");
        assertEquals(1, smps[4] & 0xFF, "Dividing timing");
        assertEquals(0x80, smps[5] & 0xFF, "Tempo");

        // Voice pointer should point past track data
        int voicePtr = (smps[0] & 0xFF) | ((smps[1] & 0xFF) << 8);
        assertTrue(voicePtr > 10, "Voice pointer should be past header");
        assertTrue(voicePtr + 25 <= smps.length, "Voice data should fit");
    }

    @Test
    void testVoiceDataPresent() {
        Song song = new Song();
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32; // algo=2, fb=6
        song.getVoiceBank().add(new FmVoice("Lead", voiceData));

        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xE1, 0x00, (byte)0xA1, 0x30, (byte)0xF2 });

        byte[] smps = new PatternCompiler().compile(song);

        int voicePtr = (smps[0] & 0xFF) | ((smps[1] & 0xFF) << 8);
        assertEquals(0x32, smps[voicePtr] & 0xFF, "Voice algo/fb should match");
    }

    @Test
    void testLoopJumpPresent() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("Test", new byte[25]));
        song.setLoopPoint(0);

        // Two order rows both pointing to pattern 0
        song.getOrderList().add(new int[Pattern.CHANNEL_COUNT]);

        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xA1, 0x30 });

        byte[] smps = new PatternCompiler().compile(song);

        // Find F4 (jump) in compiled output
        boolean hasF4 = false;
        for (int i = 0; i < smps.length - 2; i++) {
            if ((smps[i] & 0xFF) == 0xF4) {
                hasF4 = true;
                // The jump target should point back into the track data area
                int target = (smps[i+1] & 0xFF) | ((smps[i+2] & 0xFF) << 8);
                assertTrue(target >= 6, "Loop target should be in track data area");
                break;
            }
        }
        assertTrue(hasF4, "Should have loop jump (F4)");
    }

    @Test
    void testMultipleChannels() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V1", new byte[25]));

        // FM channel 0 and PSG channel 0 (index 6) both have data
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xA1, 0x30, (byte)0xF2 });
        song.getPatterns().get(0).setTrackData(6,
            new byte[]{ (byte)0xA1, 0x30, (byte)0xF2 });

        byte[] smps = new PatternCompiler().compile(song);

        assertEquals(1, smps[2] & 0xFF, "1 FM channel");
        assertEquals(1, smps[3] & 0xFF, "1 PSG channel");
    }

    @Test
    void testEmptySongCompiles() {
        Song song = new Song();
        // No voices, no track data -- should still produce valid (if silent) output
        byte[] smps = new PatternCompiler().compile(song);
        assertNotNull(smps);
        assertTrue(smps.length > 0);
    }
}
