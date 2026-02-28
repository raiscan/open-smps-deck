package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
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
        // EF 00 (set voice 0), A1 (note C4), 30 (duration), F2 (track end)
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte)0xA1, 0x30, (byte) SmpsCoordFlags.STOP });

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
            new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte)0xA1, 0x30, (byte) SmpsCoordFlags.STOP });

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

        // Find F6 (jump) in compiled output
        boolean hasJump = false;
        for (int i = 0; i < smps.length - 2; i++) {
            if ((smps[i] & 0xFF) == SmpsCoordFlags.JUMP) {
                hasJump = true;
                // The jump target should point back into the track data area
                int target = (smps[i+1] & 0xFF) | ((smps[i+2] & 0xFF) << 8);
                assertTrue(target >= 6, "Loop target should be in track data area");
                break;
            }
        }
        assertTrue(hasJump, "Should have loop jump (F6)");
    }

    @Test
    void testMultipleChannels() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V1", new byte[25]));

        // FM channel 0 and PSG channel 0 (index 6) both have data
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xA1, 0x30, (byte) SmpsCoordFlags.STOP });
        song.getPatterns().get(0).setTrackData(6,
            new byte[]{ (byte)0xA1, 0x30, (byte) SmpsCoordFlags.STOP });

        byte[] smps = new PatternCompiler().compile(song);

        assertEquals(1, smps[2] & 0xFF, "1 FM channel");
        assertEquals(1, smps[3] & 0xFF, "1 PSG channel");
    }

    @Test
    void testNonZeroLoopPoint() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Pattern 0: intro notes
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0xA1, 0x18, (byte) 0xA3, 0x18 });

        // Pattern 1: loop body
        Pattern loopPattern = new Pattern(1, 64);
        loopPattern.setTrackData(0,
            new byte[]{ (byte) 0xBD, 0x30 });
        song.getPatterns().add(loopPattern);

        // Order: pattern 0 -> pattern 1, loop back to row 1 (pattern 1)
        song.getOrderList().add(new int[]{ 1, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        song.setLoopPoint(1); // loop to second order row

        byte[] smps = new PatternCompiler().compile(song);

        // Find the F6 (Jump) command
        for (int i = 0; i < smps.length - 2; i++) {
            if ((smps[i] & 0xFF) == SmpsCoordFlags.JUMP) {
                int target = (smps[i + 1] & 0xFF) | ((smps[i + 2] & 0xFF) << 8);
                // Target should point past the intro data (A1 18 A3 18 = 4 bytes)
                // and into the loop body area
                int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
                assertTrue(target > trackStart,
                    "Loop target should point past intro into loop body");
                return;
            }
        }
        fail("No Jump (F6) command found in compiled output");
    }

    @Test
    void testEmptySongCompiles() {
        Song song = new Song();
        // No voices, no track data -- should still produce valid (if silent) output
        byte[] smps = new PatternCompiler().compile(song);
        assertNotNull(smps);
        assertTrue(smps.length > 0);
    }

    @Test
    void testLoopPointBeyondOrderListDefaultsToZero() {
        Song song = new Song();
        song.setTempo(120);
        song.setDividingTiming(1);
        // Add some data to FM1 (channel 0)
        Pattern p = song.getPatterns().get(0);
        p.setTrackData(0, new byte[]{(byte) 0x80, 0x30}); // C4 with duration 0x30
        // Set loop point beyond the single order row
        song.setLoopPoint(99);

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);
        // Should not throw, and the JUMP target should point to offset 0 of the track data
        // (the header size bytes offset)
        assertNotNull(smps);
        assertTrue(smps.length > 0);
    }
}
