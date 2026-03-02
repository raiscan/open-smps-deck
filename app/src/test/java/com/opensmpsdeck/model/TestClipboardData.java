package com.opensmpsdeck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestClipboardData {

    @Test
    void defensiveCopyOnConstruction() {
        byte[] ch0 = {0x01, 0x02};
        byte[][] data = {ch0};
        ClipboardData clip = new ClipboardData(data, 1);
        ch0[0] = (byte) 0xFF; // mutate original
        assertNotEquals((byte) 0xFF, clip.getChannelData(0)[0],
                "Constructor should defensive-copy channel data");
    }

    @Test
    void defensiveCopyOnGet() {
        byte[][] data = {{0x01, 0x02}};
        ClipboardData clip = new ClipboardData(data, 1);
        byte[] retrieved = clip.getChannelData(0);
        retrieved[0] = (byte) 0xFF;
        assertNotEquals((byte) 0xFF, clip.getChannelData(0)[0],
                "getChannelData should return a defensive copy");
    }

    @Test
    void nullChannelBecomesEmpty() {
        byte[][] data = {null};
        ClipboardData clip = new ClipboardData(data, 1);
        assertEquals(0, clip.getChannelData(0).length);
    }

    @Test
    void sourceSongStoredCorrectly() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("Test Voice", new byte[FmVoice.VOICE_SIZE]));
        byte[][] data = {{0x01}};
        ClipboardData clip = new ClipboardData(data, 1, song);
        assertTrue(clip.isCrossSong(), "isCrossSong should be true when sourceSong is provided");
        assertNotNull(clip.getSourceVoices(), "getSourceVoices should be non-null for cross-song copy");
        assertEquals(1, clip.getSourceVoices().size(), "Voice bank snapshot should have 1 entry");
        assertNull(clip.getSourceSong(), "Deprecated getSourceSong should return null");
    }

    @Test
    void sourceSongNullByDefault() {
        byte[][] data = {{0x01}};
        ClipboardData clip = new ClipboardData(data, 1);
        assertFalse(clip.isCrossSong(), "isCrossSong should be false when no source song");
        assertNull(clip.getSourceVoices(), "getSourceVoices should be null for same-song copy");
        assertNull(clip.getSourcePsgEnvelopes(), "getSourcePsgEnvelopes should be null for same-song copy");
    }

    @Test
    void channelCountAndRowCount() {
        byte[][] data = {{0x01}, {0x02}, {0x03}};
        ClipboardData clip = new ClipboardData(data, 5);
        assertEquals(3, clip.getChannelCount());
        assertEquals(5, clip.getRowCount());
    }
}
