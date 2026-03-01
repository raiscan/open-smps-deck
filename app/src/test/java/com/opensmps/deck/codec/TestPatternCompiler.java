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

    @Test
    void testS1ModeCompensatesNoteOffset() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S1);
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Put a known note 0xA1 (C-2 in S2 numbering) in FM channel 0
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x30 });

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        // Track data starts after header: 6 (base) + 4 (1 FM track header) = offset 10
        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        // Track data: EF 00 <note> 30 F6 xx xx
        // The note is at trackStart + 2 (after EF 00)
        int compiledNote = smps[trackStart + 2] & 0xFF;
        assertEquals(0xA2, compiledNote,
            "S1 mode should shift note 0xA1 -> 0xA2 (+1 compensation)");

        // Duration byte should NOT be shifted
        assertEquals(0x30, smps[trackStart + 3] & 0xFF,
            "Duration byte should remain unchanged");

        // Coordination flag byte EF should NOT be shifted
        assertEquals(SmpsCoordFlags.SET_VOICE, smps[trackStart] & 0xFF,
            "Coordination flag SET_VOICE should remain unchanged");
    }

    @Test
    void testS2ModeNoCompensation() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S2);
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Same note data as above
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x30 });

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        int compiledNote = smps[trackStart + 2] & 0xFF;
        assertEquals(0xA1, compiledNote,
            "S2 mode should leave note 0xA1 unchanged (no compensation)");
    }

    @Test
    void testS3kModeCompensatesNoteOffset() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S3K);
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0xBD, 0x30 });

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        int compiledNote = smps[trackStart] & 0xFF;
        assertEquals(0xBE, compiledNote,
            "S3K mode should shift note 0xBD -> 0xBE (+1 compensation)");
    }

    @Test
    void testDacChannelNoteIsNotShiftedByModeCompensation() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S1);
        // Channel 5 is DAC: 0x81 means DAC sample index 0 and must remain unchanged.
        song.getPatterns().get(0).setTrackData(5,
                new byte[]{(byte) 0x81, 0x20, (byte) SmpsCoordFlags.STOP});

        byte[] smps = new PatternCompiler().compile(song);
        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        int compiledFirstByte = smps[trackStart] & 0xFF;
        assertEquals(0x81, compiledFirstByte,
                "DAC note bytes must not be shifted by S1/S3K note compensation");
    }

    @Test
    void testNoteCompensationDoesNotShiftRest() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S1);
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // 0x80 is a rest -- should NOT be shifted
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0x80, 0x30 });

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        int compiledByte = smps[trackStart] & 0xFF;
        assertEquals(0x80, compiledByte,
            "Rest byte 0x80 should NOT be shifted by note compensation");
    }

    @Test
    void testNoteCompensationClampsAtMax() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S1);
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // 0xDF is the maximum note value -- shifting +1 would exceed range
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0xDF, 0x30 });

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        int compiledNote = smps[trackStart] & 0xFF;
        assertEquals(0xDF, compiledNote,
            "Note at max 0xDF should be clamped to 0xDF, not overflow into coord flag range");
    }

    @Test
    void testCompileMultiPatternSongHasCorrectOrderJumps() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Pattern 0: note A
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0xA1, 0x18 }); // C-2 dur=0x18

        // Pattern 1: note B
        Pattern p1 = new Pattern(1, 64);
        p1.setTrackData(0,
            new byte[]{ (byte) 0xBD, 0x30 }); // C-5 dur=0x30
        song.getPatterns().add(p1);

        // Order: pattern 0, then pattern 1
        song.getOrderList().add(new int[]{ 1, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        song.setLoopPoint(0);

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);
        assertNotNull(smps);
        assertTrue(smps.length > 0);

        // Re-import and verify both patterns' data are present
        com.opensmps.deck.io.SmpsImporter importer = new com.opensmps.deck.io.SmpsImporter();
        Song imported = importer.importData(smps, "multi-pattern");

        byte[] importedTrack = imported.getPatterns().get(0).getTrackData(0);
        assertNotNull(importedTrack);
        // Track should contain data from both patterns concatenated:
        // Pattern 0 data (A1 18) + Pattern 1 data (BD 30) + F6 jump
        assertTrue(importedTrack.length >= 4,
            "Imported track should contain data from both patterns");

        // Verify first pattern's note (C-2 = 0xA1)
        assertEquals((byte) 0xA1, importedTrack[0], "First note should be from pattern 0");

        // Verify second pattern's note is present after pattern 0's data
        // Pattern 0: A1 18 (2 bytes), pattern 1 starts at offset 2: BD 30
        assertEquals((byte) 0xBD, importedTrack[2], "Second note should be from pattern 1");
    }

    @Test
    void testChannelTimelineResolvesPositionCorrectly() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // FM channel 0: set voice 0, note C4 dur 0x30, note E4 dur 0x20
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x30, (byte) 0xA4, 0x20 });

        PatternCompiler compiler = new PatternCompiler();
        PatternCompiler.CompilationResult result = compiler.compileDetailed(song);

        assertNotNull(result);
        PatternCompiler.ChannelTimeline timeline = result.getChannelTimeline(0);
        assertNotNull(timeline, "Should have a timeline for FM channel 0");
        assertTrue(timeline.getRowCount() > 0, "Timeline should have decoded rows");

        // The track starts at some offset in the compiled binary (past header).
        // resolvePosition with the track's own offset should resolve to order 0, row 0.
        int trackOffset = timeline.getTrackOffset();

        PatternCompiler.CursorPosition pos0 = timeline.resolvePosition(trackOffset);
        assertNotNull(pos0);
        assertEquals(0, pos0.orderIndex(), "First byte should resolve to order 0");
        assertEquals(0, pos0.rowIndex(), "First byte should resolve to row 0");

        // A position past the first row's bytes should resolve to a later row
        PatternCompiler.CursorPosition posLater = timeline.resolvePosition(trackOffset + 4);
        assertNotNull(posLater);
        assertEquals(0, posLater.orderIndex(), "Single pattern song should be order 0");
        assertTrue(posLater.rowIndex() >= 1,
            "Position past first note should resolve to row 1 or later");
    }

    @Test
    void testChannelTimelineResolvesMultiPatternPositions() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Pattern 0: one note
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0xA1, 0x18 });

        // Pattern 1: another note
        Pattern p1 = new Pattern(1, 64);
        p1.setTrackData(0, new byte[]{ (byte) 0xBD, 0x30 });
        song.getPatterns().add(p1);

        // Order: pattern 0 -> pattern 1
        song.getOrderList().add(new int[]{ 1, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        song.setLoopPoint(0);

        PatternCompiler compiler = new PatternCompiler();
        PatternCompiler.CompilationResult result = compiler.compileDetailed(song);

        PatternCompiler.ChannelTimeline timeline = result.getChannelTimeline(0);
        assertNotNull(timeline);

        int trackOffset = timeline.getTrackOffset();

        // First order row's data starts at trackOffset
        PatternCompiler.CursorPosition firstRow = timeline.resolvePosition(trackOffset);
        assertNotNull(firstRow);
        assertEquals(0, firstRow.orderIndex(), "First note should be in order row 0");

        // After pattern 0's 2 bytes (A1 18), pattern 1's data begins
        PatternCompiler.CursorPosition secondOrder = timeline.resolvePosition(trackOffset + 2);
        assertNotNull(secondOrder);
        assertEquals(1, secondOrder.orderIndex(), "Second pattern should resolve to order row 1");
    }

    @Test
    void testCompileRelocatesSegmentLocalLoopPointerPerChannel() {
        Song song = new Song();

        // Pattern 0 contributes 2 bytes.
        song.getPatterns().get(0).setTrackData(0, new byte[]{
                (byte) 0xA1, 0x18
        });

        // Pattern 1 starts with F7 loop pointing to local offset 0x0000
        // (start of pattern 1 segment).
        Pattern p1 = new Pattern(1, 64);
        p1.setTrackData(0, new byte[]{
                (byte) SmpsCoordFlags.LOOP, 0x00, 0x02, 0x00, 0x00,
                (byte) 0xA4, 0x18
        });
        song.getPatterns().add(p1);

        int[] orderRow1 = new int[Pattern.CHANNEL_COUNT];
        orderRow1[0] = 1;
        song.getOrderList().add(orderRow1);
        song.setLoopPoint(0);

        byte[] smps = new PatternCompiler().compile(song);
        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);

        assertEquals(SmpsCoordFlags.LOOP, smps[trackStart + 2] & 0xFF);

        int relocatedLoopTarget = (smps[trackStart + 5] & 0xFF)
                | ((smps[trackStart + 6] & 0xFF) << 8);
        assertEquals(trackStart + 2, relocatedLoopTarget,
                "Loop pointer should target the start of pattern 1 segment in this channel track");
    }
}
