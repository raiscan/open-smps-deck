package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestRomVoiceImporter {

    @Test
    void testImportableVoiceRecord() {
        byte[] data = new byte[25];
        data[0] = 0x32;
        ImportableVoice voice = new ImportableVoice("TestSong", 0, data, 2);
        assertEquals("TestSong", voice.sourceSong());
        assertEquals(0, voice.originalIndex());
        assertEquals(2, voice.algorithm());
        assertArrayEquals(data, voice.voiceData());
    }

    @Test
    void testScanSingleFile(@TempDir Path tempDir) throws Exception {
        Song song = new Song();
        song.setTempo(120);
        song.setDividingTiming(1);
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32;
        song.getVoiceBank().add(new FmVoice("Lead", voiceData));
        song.getPatterns().get(0).setTrackData(0, new byte[]{(byte) 0x80, 0x18});

        new SmpsExporter().export(song, tempDir.resolve("test.bin").toFile());

        RomVoiceImporter importer = new RomVoiceImporter();
        List<ImportableVoice> voices = importer.scanDirectory(tempDir.toFile());
        assertFalse(voices.isEmpty());
        assertEquals(0x32, voices.get(0).voiceData()[0] & 0xFF);
        assertEquals("test", voices.get(0).sourceSong());
    }

    @Test
    void testDeduplication(@TempDir Path tempDir) throws Exception {
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32;

        for (String name : new String[]{"song1.bin", "song2.bin"}) {
            Song song = new Song();
            song.setTempo(120);
            song.setDividingTiming(1);
            song.getVoiceBank().add(new FmVoice("Lead", voiceData.clone()));
            song.getPatterns().get(0).setTrackData(0, new byte[]{(byte) 0x80, 0x18});
            new SmpsExporter().export(song, tempDir.resolve(name).toFile());
        }

        RomVoiceImporter importer = new RomVoiceImporter();
        List<ImportableVoice> voices = importer.scanDirectory(tempDir.toFile());
        assertEquals(1, voices.size());
    }

    @Test
    void testEmptyDirectory(@TempDir Path tempDir) {
        RomVoiceImporter importer = new RomVoiceImporter();
        List<ImportableVoice> voices = importer.scanDirectory(tempDir.toFile());
        assertTrue(voices.isEmpty());
    }
}
