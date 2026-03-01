package com.opensmps.deck.audio;

import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.Song;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPlaybackSliceBuilder {

    @Test
    void fmSliceBootstrapsInstrumentAndDurationForRowWithoutInlineContext() {
        Song song = new Song();
        song.getPatterns().get(0).setTrackData(0, new byte[] {
                (byte) 0xEF, 0x04,
                (byte) 0xA1, 0x10,
                (byte) 0xA4,
                (byte) 0xA8,
                (byte) 0xF2
        });

        Song slice = new PlaybackSliceBuilder().createPlaybackSlice(song, 0, 1);
        byte[] track = firstEntryTrack(slice, 0);

        assertTrue(track.length >= 4);
        assertEquals(0xEF, track[0] & 0xFF);
        assertEquals(0x04, track[1] & 0xFF);
        assertEquals(0x10, track[2] & 0xFF);
        assertEquals(0xA4, track[3] & 0xFF);
    }

    @Test
    void psgSliceBootstrapsEnvelopeAndDuration() {
        Song song = new Song();
        song.getPatterns().get(0).setTrackData(6, new byte[] {
                (byte) 0xF5, 0x03,
                (byte) 0xA1, 0x10,
                (byte) 0xA4,
                (byte) 0xA8,
                (byte) 0xF2
        });

        Song slice = new PlaybackSliceBuilder().createPlaybackSlice(song, 0, 1);
        byte[] track = firstEntryTrack(slice, 6);

        assertTrue(track.length >= 4);
        assertEquals(0xF5, track[0] & 0xFF);
        assertEquals(0x03, track[1] & 0xFF);
        assertEquals(0x10, track[2] & 0xFF);
        assertEquals(0xA4, track[3] & 0xFF);
    }

    @Test
    void dacSliceBootstrapsDurationForRowWithoutInlineDuration() {
        Song song = new Song();
        song.getPatterns().get(0).setTrackData(5, new byte[] {
                (byte) 0x81, 0x10,
                (byte) 0x82,
                (byte) 0x83,
                (byte) 0xF2
        });

        Song slice = new PlaybackSliceBuilder().createPlaybackSlice(song, 0, 1);
        byte[] track = firstEntryTrack(slice, 5);

        assertTrue(track.length >= 2);
        assertEquals(0x10, track[0] & 0xFF);
        assertEquals(0x82, track[1] & 0xFF);
    }

    @Test
    void tieRowSliceKeepsDurationContext() {
        Song song = new Song();
        song.getPatterns().get(0).setTrackData(0, new byte[] {
                (byte) 0xA1, 0x10,
                (byte) 0xE7,
                (byte) 0xA4,
                (byte) 0xF2
        });

        Song slice = new PlaybackSliceBuilder().createPlaybackSlice(song, 0, 1);
        byte[] track = firstEntryTrack(slice, 0);

        assertTrue(track.length >= 2);
        assertEquals(0x10, track[0] & 0xFF);
        assertEquals(0xE7, track[1] & 0xFF);
    }

    @Test
    void rowPrefixCoordFlagsArePreservedAtSliceBoundary() {
        Song song = new Song();
        song.getPatterns().get(0).setTrackData(0, new byte[] {
                (byte) 0xA1, 0x10,
                (byte) 0xE1, 0x04,
                (byte) 0xE0, (byte) 0x80,
                (byte) 0xA4, 0x10,
                (byte) 0xF2
        });

        Song slice = new PlaybackSliceBuilder().createPlaybackSlice(song, 0, 1);
        byte[] track = firstEntryTrack(slice, 0);

        assertTrue(track.length >= 6);
        assertEquals(0xE1, track[0] & 0xFF);
        assertEquals(0x04, track[1] & 0xFF);
        assertEquals(0xE0, track[2] & 0xFF);
        assertEquals(0x80, track[3] & 0xFF);
        assertEquals(0xA4, track[4] & 0xFF);
        assertEquals(0x10, track[5] & 0xFF);
    }

    @Test
    void orderSliceRebasesLoopPoint() {
        Song song = new Song();
        Pattern p1 = new Pattern(1, 64);
        song.getPatterns().add(p1);
        int[] row1 = new int[Pattern.CHANNEL_COUNT];
        row1[0] = 1;
        song.getOrderList().add(row1);
        song.setLoopPoint(1);

        Song slice = new PlaybackSliceBuilder().createPlaybackSlice(song, 1, 0);
        assertNotSame(song, slice);
        assertEquals(1, slice.getOrderList().size());
        assertEquals(0, slice.getLoopPoint());
    }

    private byte[] firstEntryTrack(Song slicedSong, int channel) {
        int entryPatternIndex = slicedSong.getOrderList().get(0)[0];
        return slicedSong.getPatterns().get(entryPatternIndex).getTrackData(channel);
    }
}
