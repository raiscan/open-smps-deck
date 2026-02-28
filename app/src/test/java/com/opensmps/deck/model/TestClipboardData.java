package com.opensmps.deck.model;

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
        byte[][] data = {{0x01}};
        ClipboardData clip = new ClipboardData(data, 1, song);
        assertSame(song, clip.getSourceSong());
    }

    @Test
    void sourceSongNullByDefault() {
        byte[][] data = {{0x01}};
        ClipboardData clip = new ClipboardData(data, 1);
        assertNull(clip.getSourceSong());
    }

    @Test
    void channelCountAndRowCount() {
        byte[][] data = {{0x01}, {0x02}, {0x03}};
        ClipboardData clip = new ClipboardData(data, 5);
        assertEquals(3, clip.getChannelCount());
        assertEquals(5, clip.getRowCount());
    }
}
