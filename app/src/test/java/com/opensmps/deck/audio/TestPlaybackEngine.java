package com.opensmps.deck.audio;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestPlaybackEngine {

    @Test
    void testLoadAndRenderProducesAudio() {
        Song song = createTestSong();
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        short[] buffer = new short[2048];
        int samples = engine.renderBuffer(buffer);
        assertTrue(samples > 0, "Should render audio samples");

        // Check that at least some samples are non-zero
        boolean hasAudio = false;
        for (short s : buffer) {
            if (s != 0) { hasAudio = true; break; }
        }
        assertTrue(hasAudio, "Rendered audio should contain non-zero samples");
    }

    @Test
    void testMuteDoesNotThrow() {
        PlaybackEngine engine = new PlaybackEngine();
        engine.setFmMute(0, true);
        engine.setPsgMute(0, true);
        engine.setFmMute(0, false);
        engine.setPsgMute(0, false);
    }

    @Test
    void testReloadRestartsFromBeginning() {
        Song song = createTestSong();
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        // Render enough audio to exhaust the note (duration 0x30 = 48 ticks)
        // and confirm the driver eventually completes
        for (int i = 0; i < 20; i++) {
            engine.renderBuffer(new short[8192]);
        }

        // Reload from scratch
        engine.reload(song);

        // After reload, the driver should no longer be complete (fresh sequencer)
        assertFalse(engine.getDriver().isComplete(),
                "After reload, driver should not be complete (sequencer restarted)");

        // Render a batch and verify it produces non-zero audio (note is playing again)
        short[] afterReload = new short[4096];
        engine.renderBuffer(afterReload);

        boolean hasNonZeroAfterReload = false;
        for (short s : afterReload) {
            if (s != 0) { hasNonZeroAfterReload = true; break; }
        }
        assertTrue(hasNonZeroAfterReload,
                "After reload, rendered audio should contain non-zero samples from restarted note");
    }

    @Test
    void testS1ModeUsesBaseNoteOffsetZero() {
        Song song = createTestSong();
        song.setSmpsMode(SmpsMode.S1);
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        short[] buffer = new short[2048];
        int samples = engine.renderBuffer(buffer);
        assertTrue(samples > 0, "S1 mode with baseNoteOffset=0 should render audio without crash");
    }

    @Test
    void testS3KModeUsesBaseNoteOffsetZero() {
        Song song = createTestSong();
        song.setSmpsMode(SmpsMode.S3K);
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        short[] buffer = new short[2048];
        int samples = engine.renderBuffer(buffer);
        assertTrue(samples > 0, "S3K mode with baseNoteOffset=0 should render audio without crash");
    }

    @Test
    void testDacSampleRendersInS2Mode() {
        Song song = createDacTestSong(SmpsMode.S2);
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        assertTrue(renderContainsAudio(engine),
                "DAC sample track should render audible samples in S2 mode");
    }

    @Test
    void testDacSampleRendersInS1ModeWithoutIndexShift() {
        Song song = createDacTestSong(SmpsMode.S1);
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        assertTrue(renderContainsAudio(engine),
                "DAC sample index should remain stable in S1 mode and still render audio");
    }

    @Test
    void testCreatePlaybackSliceStartsAtRequestedOrderAndAdjustsLoopPoint() {
        Song song = createTwoOrderSong();
        PlaybackEngine engine = new PlaybackEngine();

        Song slice = engine.createPlaybackSlice(song, 1, 0);
        assertNotSame(song, slice, "Sliced playback should use a copied Song model");
        assertEquals(1, slice.getOrderList().size(), "Slice should contain only rows from requested start");
        assertEquals(1, slice.getOrderList().get(0)[0], "First row in slice should be original row 1");
        assertEquals(0, slice.getLoopPoint(), "Loop point should be rebased into the sliced order list");
    }

    @Test
    void testCreatePlaybackSliceOutOfRangeReturnsOriginalSong() {
        Song song = createTwoOrderSong();
        PlaybackEngine engine = new PlaybackEngine();
        assertSame(song, engine.createPlaybackSlice(song, 99, 0));
        assertSame(song, engine.createPlaybackSlice(song, -1, 0));
    }

    @Test
    void testCreatePlaybackSliceTrimsFirstOrderToCursorRow() {
        Song song = createRowTrimSong();
        PlaybackEngine engine = new PlaybackEngine();

        Song slice = engine.createPlaybackSlice(song, 0, 1);
        assertNotSame(song, slice, "Row trimming at order 0 should use a copied Song model");
        int firstEntryPatternIndex = slice.getOrderList().get(0)[0];
        byte[] firstTrimmedTrack = slice.getPatterns().get(firstEntryPatternIndex).getTrackData(0);
        assertEquals(0xA4, firstTrimmedTrack[0] & 0xFF);

        Song slicedOrder = engine.createPlaybackSlice(song, 1, 1);
        assertNotSame(song, slicedOrder);
        assertEquals(2, slicedOrder.getOrderList().size());

        int entryPatternIndex = slicedOrder.getOrderList().get(0)[0];
        assertTrue(entryPatternIndex >= 0 && entryPatternIndex < slicedOrder.getPatterns().size());

        byte[] trimmedTrack = slicedOrder.getPatterns().get(entryPatternIndex).getTrackData(0);
        assertTrue(trimmedTrack.length >= 2, "Trimmed entry track should keep rows from cursor onward");
        assertEquals(0xA4, trimmedTrack[0] & 0xFF, "Cursor row 1 should skip first note 0xA1");
        assertEquals(0x10, trimmedTrack[1] & 0xFF);

        // Later rows/patterns should remain untouched.
        byte[] originalTrack = slicedOrder.getPatterns().get(0).getTrackData(0);
        assertEquals(0xA1, originalTrack[0] & 0xFF, "Original pattern should remain unchanged for later order rows");
    }

    @Test
    void testCreatePlaybackSliceBootstrapsInstrumentAndDurationAtCursorRow() {
        Song song = createCarryContextSong();
        PlaybackEngine engine = new PlaybackEngine();

        Song slice = engine.createPlaybackSlice(song, 0, 1);
        int entryPatternIndex = slice.getOrderList().get(0)[0];
        byte[] trimmedTrack = slice.getPatterns().get(entryPatternIndex).getTrackData(0);

        // Row 1 has no inline duration and no row-local instrument.
        // Playback slice should bootstrap both from prior state.
        assertTrue(trimmedTrack.length >= 4);
        assertEquals(0xEF, trimmedTrack[0] & 0xFF);
        assertEquals(0x04, trimmedTrack[1] & 0xFF);
        assertEquals(0x10, trimmedTrack[2] & 0xFF);
        assertEquals(0xA4, trimmedTrack[3] & 0xFF);
    }

    @Test
    void testCreatePlaybackSlicePreservesRowPrefixFlagsAtCursorRow() {
        Song song = createRowPrefixFlagSong();
        PlaybackEngine engine = new PlaybackEngine();

        Song slice = engine.createPlaybackSlice(song, 0, 1);
        int entryPatternIndex = slice.getOrderList().get(0)[0];
        byte[] trimmedTrack = slice.getPatterns().get(entryPatternIndex).getTrackData(0);

        // Row-local EF/E0 flags that sit immediately before row 1 should remain.
        assertTrue(trimmedTrack.length >= 6);
        assertEquals(0xEF, trimmedTrack[0] & 0xFF);
        assertEquals(0x07, trimmedTrack[1] & 0xFF);
        assertEquals(0xE0, trimmedTrack[2] & 0xFF);
        assertEquals(0x80, trimmedTrack[3] & 0xFF);
        assertEquals(0xA4, trimmedTrack[4] & 0xFF);
        assertEquals(0x10, trimmedTrack[5] & 0xFF);
    }

    private Song createTestSong() {
        Song song = new Song();
        song.setTempo(0x80);

        // Simple sine voice: algo 0, op4 as carrier with MUL=1, AR=31
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x00;   // algo 0, fb 0
        // Op1,2,3 TL = 127 (silent modulators)
        voiceData[2] = 0x7F;   // Op1 TL
        voiceData[7] = 0x7F;   // Op3 TL
        voiceData[12] = 0x7F;  // Op2 TL
        // Op4 (carrier): MUL=1, TL=0, AR=31, RR=15
        voiceData[16] = 0x01;  // Op4 DT_MUL
        voiceData[17] = 0x00;  // Op4 TL (loud)
        voiceData[18] = 0x1F;  // Op4 RS_AR (AR=31)
        voiceData[19] = 0x00;  // Op4 AM_D1R
        voiceData[20] = 0x00;  // Op4 D2R
        voiceData[21] = 0x0F;  // Op4 D1L_RR (RR=15)
        song.getVoiceBank().add(new FmVoice("Sine", voiceData));

        // Set voice (EF), play C4, duration 48
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xEF, 0x00, (byte)0xA1, 0x30 });

        return song;
    }

    private Song createDacTestSong(SmpsMode mode) {
        Song song = new Song();
        song.setSmpsMode(mode);
        song.getDacSamples().add(new DacSample(
                "Kick",
                new byte[]{0x00, (byte) 0xFF, 0x10, (byte) 0xF0, 0x20, (byte) 0xE0},
                0x0C
        ));
        song.getPatterns().get(0).setTrackData(5,
                new byte[]{(byte) 0x81, 0x30, (byte) 0xF2});
        return song;
    }

    private Song createTwoOrderSong() {
        Song song = createTestSong();
        Pattern p1 = new Pattern(1, 64);
        p1.setTrackData(0, new byte[]{(byte) 0xBD, 0x20, (byte) 0xF2});
        song.getPatterns().add(p1);
        int[] row1 = new int[Pattern.CHANNEL_COUNT];
        row1[0] = 1;
        song.getOrderList().add(row1);
        song.setLoopPoint(1);
        return song;
    }

    private Song createRowTrimSong() {
        Song song = createTestSong();
        song.getPatterns().get(0).setTrackData(0, new byte[]{
                (byte) 0xA1, 0x10,
                (byte) 0xA4, 0x10,
                (byte) 0xA8, 0x10,
                (byte) 0xF2
        });
        Pattern p1 = new Pattern(1, 64);
        p1.setTrackData(0, new byte[]{(byte) 0xBD, 0x20, (byte) 0xF2});
        song.getPatterns().add(p1);

        int[] row1 = new int[Pattern.CHANNEL_COUNT];
        row1[0] = 0;
        song.getOrderList().add(row1);
        int[] row2 = new int[Pattern.CHANNEL_COUNT];
        row2[0] = 1;
        song.getOrderList().add(row2);
        song.setLoopPoint(2);
        return song;
    }

    private Song createCarryContextSong() {
        Song song = createTestSong();
        song.getPatterns().get(0).setTrackData(0, new byte[] {
                (byte) 0xEF, 0x04,
                (byte) 0xA1, 0x10,
                (byte) 0xA4,
                (byte) 0xA8,
                (byte) 0xF2
        });
        return song;
    }

    private Song createRowPrefixFlagSong() {
        Song song = createTestSong();
        song.getPatterns().get(0).setTrackData(0, new byte[] {
                (byte) 0xA1, 0x10,
                (byte) 0xEF, 0x07,
                (byte) 0xE0, (byte) 0x80,
                (byte) 0xA4, 0x10,
                (byte) 0xF2
        });
        return song;
    }

    @Test
    void getPlaybackPositionReturnsValidPositionAfterLoad() {
        Song song = createTestSong();
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        // Render some audio so the sequencer advances
        for (int i = 0; i < 5; i++) {
            engine.renderBuffer(new short[2048]);
        }

        PlaybackEngine.PlaybackPosition pos = engine.getPlaybackPosition();
        assertNotNull(pos, "Should return a position after loading a song with FM data");
        assertTrue(pos.orderIndex() >= 0, "Order index should be non-negative");
        assertTrue(pos.rowIndex() >= 0, "Row index should be non-negative");
    }

    @Test
    void getPlaybackPositionReturnsNullBeforeLoad() {
        PlaybackEngine engine = new PlaybackEngine();
        assertNull(engine.getPlaybackPosition(),
                "Should return null when no song is loaded");
    }

    private boolean renderContainsAudio(PlaybackEngine engine) {
        short[] buffer = new short[4096];
        for (int i = 0; i < 6; i++) {
            int samples = engine.renderBuffer(buffer);
            if (samples <= 0) continue;
            for (short sample : buffer) {
                if (sample != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
