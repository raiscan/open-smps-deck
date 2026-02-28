package com.opensmps.deck.ui;

import com.opensmps.deck.model.Song;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSongTab {

    @Test
    void testNewSongTabHasDefaultSong() {
        SongTab tab = new SongTab();
        assertNotNull(tab.getSong());
        assertEquals("Untitled", tab.getSong().getName());
        assertNull(tab.getFile());
        assertFalse(tab.isDirty());
    }

    @Test
    void testSongTabWithExistingSong() {
        Song song = new Song();
        song.setName("Test Song");
        SongTab tab = new SongTab(song);
        assertEquals("Test Song", tab.getSong().getName());
    }

    @Test
    void testSongTabFileTracking() {
        SongTab tab = new SongTab();
        assertNull(tab.getFile());
        java.io.File file = new java.io.File("test.osmpsd");
        tab.setFile(file);
        assertEquals(file, tab.getFile());
    }

    @Test
    void testSongTabDirtyFlag() {
        SongTab tab = new SongTab();
        assertFalse(tab.isDirty());
        tab.setDirty(true);
        assertTrue(tab.isDirty());
    }

    @Test
    void testGetTitleUntitled() {
        SongTab tab = new SongTab();
        assertEquals("Untitled", tab.getTitle());
    }

    @Test
    void testGetTitleWithFile() {
        SongTab tab = new SongTab();
        tab.setFile(new java.io.File("mysong.osmpsd"));
        assertEquals("mysong.osmpsd", tab.getTitle());
    }

    @Test
    void testGetTitleDirty() {
        SongTab tab = new SongTab();
        tab.setDirty(true);
        assertEquals("Untitled *", tab.getTitle());
    }
}
