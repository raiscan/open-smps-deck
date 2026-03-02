package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestPatternCompiler {

    // ── Hierarchical arrangement helpers ──

    private static void addPhrase(Song song, int channel, byte[] data) {
        var arr = song.getHierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase(
            "Ch" + channel, ChannelType.fromChannelIndex(channel));
        phrase.setData(stripTrailingStop(data));
        arr.getChain(channel).getEntries().add(new ChainEntry(phrase.getId()));
    }

    private static void setLoopOnActiveChains(Song song, int entryIndex) {
        var arr = song.getHierarchicalArrangement();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            var chain = arr.getChain(ch);
            if (!chain.getEntries().isEmpty() && entryIndex < chain.getEntries().size()) {
                chain.setLoopEntryIndex(entryIndex);
            }
        }
    }

    private static byte[] stripTrailingStop(byte[] data) {
        int end = data.length;
        while (end > 0 && (data[end - 1] & 0xFF) == SmpsCoordFlags.STOP) {
            end--;
        }
        return end < data.length ? java.util.Arrays.copyOf(data, end) : data;
    }

    // ── Tests ──

    @Test
    void testCompilesMinimalSong() {
        Song song = new Song();
        song.setTempo(0x80);
        song.setDividingTiming(1);

        byte[] voiceData = new byte[25];
        voiceData[0] = 0x00; // algo 0
        song.getVoiceBank().add(new FmVoice("Test", voiceData));

        // EF 00 (set voice 0), A1 (note C4), 30 (duration)
        addPhrase(song, 0, new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte)0xA1, 0x30 });
        setLoopOnActiveChains(song, 0);

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

        addPhrase(song, 0, new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte)0xA1, 0x30 });
        setLoopOnActiveChains(song, 0);

        byte[] smps = new PatternCompiler().compile(song);

        int voicePtr = (smps[0] & 0xFF) | ((smps[1] & 0xFF) << 8);
        assertEquals(0x32, smps[voicePtr] & 0xFF, "Voice algo/fb should match");
    }

    @Test
    void testLoopJumpPresent() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("Test", new byte[25]));

        addPhrase(song, 0, new byte[]{ (byte)0xA1, 0x30 });
        setLoopOnActiveChains(song, 0);

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
        addPhrase(song, 0, new byte[]{ (byte)0xA1, 0x30 });
        addPhrase(song, 6, new byte[]{ (byte)0xA1, 0x30 });
        setLoopOnActiveChains(song, 0);

        byte[] smps = new PatternCompiler().compile(song);

        assertEquals(1, smps[2] & 0xFF, "1 FM channel");
        assertEquals(1, smps[3] & 0xFF, "1 PSG channel");
    }

    @Test
    void testNonZeroLoopPoint() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Phrase 0: intro notes, Phrase 1: loop body
        addPhrase(song, 0, new byte[]{ (byte) 0xA1, 0x18, (byte) 0xA3, 0x18 });
        addPhrase(song, 0, new byte[]{ (byte) 0xBD, 0x30 });
        // Loop back to phrase 1 (entry index 1)
        song.getHierarchicalArrangement().getChain(0).setLoopEntryIndex(1);

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
    void testStructuredArrangementCompilesWithoutLegacyPatternData() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.STRUCTURED_BLOCKS);

        StructuredArrangement structured = new StructuredArrangement();
        BlockDefinition block = new BlockDefinition(1, "Lead", 24);
        block.setTrackData(0, new byte[] { (byte) 0xA1, 0x18 });
        structured.getBlocks().add(block);
        structured.getChannels().get(0).getBlockRefs().add(new BlockRef(1, 0));
        song.setStructuredArrangement(structured);

        byte[] smps = new PatternCompiler().compile(song);
        assertEquals(1, smps[2] & 0xFF, "Structured mode should compile active FM channel");
        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        assertEquals(0xA1, smps[trackStart] & 0xFF);
        assertEquals(0x18, smps[trackStart + 1] & 0xFF);
    }

    @Test
    void testStructuredArrangementRespectsStartTickGaps() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.STRUCTURED_BLOCKS);

        StructuredArrangement structured = new StructuredArrangement();
        BlockDefinition block = new BlockDefinition(2, "Hit", 12);
        block.setTrackData(0, new byte[] { (byte) 0xA4, 0x0C });
        structured.getBlocks().add(block);
        structured.getChannels().get(0).getBlockRefs().add(new BlockRef(2, 10));
        song.setStructuredArrangement(structured);

        byte[] smps = new PatternCompiler().compile(song);
        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        assertEquals(0x80, smps[trackStart] & 0xFF, "Gap should compile as rest");
        assertEquals(10, smps[trackStart + 1] & 0xFF, "Rest duration should match start tick gap");
        assertEquals(0xA4, smps[trackStart + 2] & 0xFF);
    }

    @Test
    void testLoopPointBeyondOrderListDefaultsToZero() {
        Song song = new Song();
        song.setTempo(120);
        song.setDividingTiming(1);
        // Add some data to FM1 (channel 0) with no loop (chain terminates with STOP)
        addPhrase(song, 0, new byte[]{(byte) 0x80, 0x30});

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);
        assertNotNull(smps);
        assertTrue(smps.length > 0);
    }

    @Test
    void testS1ModeCompensatesNoteOffset() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S1);
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Put a known note 0xA1 (C-2 in S2 numbering) in FM channel 0
        addPhrase(song, 0, new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x30 });
        setLoopOnActiveChains(song, 0);

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

        addPhrase(song, 0, new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x30 });
        setLoopOnActiveChains(song, 0);

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

        addPhrase(song, 0, new byte[]{ (byte) 0xBD, 0x30 });
        setLoopOnActiveChains(song, 0);

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
        addPhrase(song, 5, new byte[]{(byte) 0x81, 0x20});
        setLoopOnActiveChains(song, 0);

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
        addPhrase(song, 0, new byte[]{ (byte) 0x80, 0x30 });
        setLoopOnActiveChains(song, 0);

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
        addPhrase(song, 0, new byte[]{ (byte) 0xDF, 0x30 });
        setLoopOnActiveChains(song, 0);

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        int compiledNote = smps[trackStart] & 0xFF;
        assertEquals(0xDF, compiledNote,
            "Note at max 0xDF should be clamped to 0xDF, not overflow into coord flag range");
    }

    @Test
    void testPsgChannelNoteIsNotShiftedByModeCompensation() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S1);
        // PSG channel 6 (PSG Tone 1): note 0xBD should NOT be shifted because
        // PSG always uses baseNoteOffset=0 regardless of SMPS mode.
        addPhrase(song, 6, new byte[]{(byte) 0xBD, 0x18});
        setLoopOnActiveChains(song, 0);

        byte[] smps = new PatternCompiler().compile(song);
        // PSG is the only active channel: 0 FM channels, 1 PSG channel
        // Header: 6 bytes base + 0 FM headers + 1 PSG header (6 bytes) = 12
        int psgTrackPtr = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);
        int compiledNote = smps[psgTrackPtr] & 0xFF;
        assertEquals(0xBD, compiledNote,
            "PSG note bytes must not be shifted by S1/S3K note compensation");
    }

    @Test
    void testCompileMultiPhraseSongHasCorrectData() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Phrase 0: note A, Phrase 1: note B
        addPhrase(song, 0, new byte[]{ (byte) 0xA1, 0x18 });
        addPhrase(song, 0, new byte[]{ (byte) 0xBD, 0x30 });
        setLoopOnActiveChains(song, 0);

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);
        assertNotNull(smps);
        assertTrue(smps.length > 0);

        // Re-import and verify both phrases' data are present
        com.opensmpsdeck.io.SmpsImporter importer = new com.opensmpsdeck.io.SmpsImporter();
        Song imported = importer.importData(smps, "multi-phrase");

        byte[] importedTrack = imported.getPatterns().get(0).getTrackData(0);
        assertNotNull(importedTrack);
        // Track should contain data from both phrases concatenated:
        // Phrase 0 data (A1 18) + Phrase 1 data (BD 30) + F6 jump
        assertTrue(importedTrack.length >= 4,
            "Imported track should contain data from both phrases");

        // Verify first phrase's note (C-2 = 0xA1)
        assertEquals((byte) 0xA1, importedTrack[0], "First note should be from phrase 0");

        // Verify second phrase's note is present after phrase 0's data
        // Phrase 0: A1 18 (2 bytes), phrase 1 starts at offset 2: BD 30
        assertEquals((byte) 0xBD, importedTrack[2], "Second note should be from phrase 1");
    }

    @Test
    void testChannelTimelineResolvesPositionCorrectly() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // FM channel 0: set voice 0, note C4 dur 0x30, note E4 dur 0x20
        addPhrase(song, 0, new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x30, (byte) 0xA4, 0x20 });
        setLoopOnActiveChains(song, 0);

        PatternCompiler compiler = new PatternCompiler();
        PatternCompiler.CompilationResult result = compiler.compileDetailed(song);

        assertNotNull(result);
        PatternCompiler.ChannelTimeline timeline = result.getChannelTimeline(0);
        assertNotNull(timeline, "Should have a timeline for FM channel 0");
        assertTrue(timeline.getRowCount() > 0, "Timeline should have decoded rows");

        // resolvePosition with the track's own offset should resolve to row 0
        int trackOffset = timeline.getTrackOffset();

        PatternCompiler.CursorPosition pos0 = timeline.resolvePosition(trackOffset);
        assertNotNull(pos0);
        assertEquals(0, pos0.orderIndex(), "First byte should resolve to entry 0");
        assertEquals(0, pos0.rowIndex(), "First byte should resolve to row 0");

        // A position past the first row's bytes should resolve to a later row
        PatternCompiler.CursorPosition posLater = timeline.resolvePosition(trackOffset + 4);
        assertNotNull(posLater);
        assertTrue(posLater.rowIndex() >= 1,
            "Position past first note should resolve to row 1 or later");
    }

    @Test
    void testChannelTimelineResolvesMultiPhrasePositions() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // Phrase 0: one note, Phrase 1: another note
        addPhrase(song, 0, new byte[]{ (byte) 0xA1, 0x18 });
        addPhrase(song, 0, new byte[]{ (byte) 0xBD, 0x30 });
        setLoopOnActiveChains(song, 0);

        PatternCompiler compiler = new PatternCompiler();
        PatternCompiler.CompilationResult result = compiler.compileDetailed(song);

        PatternCompiler.ChannelTimeline timeline = result.getChannelTimeline(0);
        assertNotNull(timeline);

        int trackOffset = timeline.getTrackOffset();

        // First entry's data starts at trackOffset
        PatternCompiler.CursorPosition firstRow = timeline.resolvePosition(trackOffset);
        assertNotNull(firstRow);
        assertEquals(0, firstRow.orderIndex(), "First note should be in entry 0");

        // After phrase 0's 2 bytes (A1 18), phrase 1's data begins
        PatternCompiler.CursorPosition secondEntry = timeline.resolvePosition(trackOffset + 2);
        assertNotNull(secondEntry);
        assertEquals(1, secondEntry.orderIndex(), "Second phrase should resolve to entry 1");
    }

    @Test
    void testCompileRelocatesInternalLoopPointer() {
        Song song = new Song();

        // Single phrase with an internal LOOP pointing to its own start (offset 0)
        addPhrase(song, 0, new byte[]{
                (byte) 0xA1, 0x18,
                (byte) SmpsCoordFlags.LOOP, 0x00, 0x02, 0x00, 0x00,
                (byte) 0xA4, 0x18
        });
        setLoopOnActiveChains(song, 0);

        byte[] smps = new PatternCompiler().compile(song);
        int trackStart = (smps[6] & 0xFF) | ((smps[7] & 0xFF) << 8);

        // LOOP command at trackStart+2 (after A1 18)
        assertEquals(SmpsCoordFlags.LOOP, smps[trackStart + 2] & 0xFF);

        // LOOP pointer (bytes at trackStart+5 and trackStart+6)
        int relocatedLoopTarget = (smps[trackStart + 5] & 0xFF)
                | ((smps[trackStart + 6] & 0xFF) << 8);
        assertEquals(trackStart, relocatedLoopTarget,
                "LOOP pointer should target the start of the track after relocation");
    }
}
