package com.opensmps.deck.io;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class TestSmpsExporter {

    @TempDir
    File tempDir;

    @Test
    void testExportWritesValidFile() throws IOException {
        Song song = new Song();
        song.setTempo(0x80);
        song.getVoiceBank().add(new FmVoice("Test", new byte[25]));
        song.getPatterns().get(0).setTrackData(0,
                new byte[]{(byte) 0xEF, 0x00, (byte) 0xA1, 0x30, (byte) 0xF2});

        File file = new File(tempDir, "test.bin");
        new SmpsExporter().export(song, file);

        assertTrue(file.exists());
        byte[] data = Files.readAllBytes(file.toPath());
        assertTrue(data.length > 0);

        // Verify it starts with a valid SMPS header
        assertEquals(1, data[2] & 0xFF, "1 FM channel");
        assertEquals(0, data[3] & 0xFF, "0 PSG channels");
        assertEquals(0x80, data[5] & 0xFF, "Tempo");
    }

    @Test
    void testCompileMatchesExport() throws IOException {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));
        song.getPatterns().get(0).setTrackData(0,
                new byte[]{(byte) 0xA1, 0x30});

        SmpsExporter exporter = new SmpsExporter();
        byte[] compiled = exporter.compile(song);

        File file = new File(tempDir, "test2.bin");
        exporter.export(song, file);
        byte[] fromFile = Files.readAllBytes(file.toPath());

        assertArrayEquals(compiled, fromFile);
    }

    @Test
    void testExportContainsVoiceData() throws IOException {
        Song song = new Song();
        song.setTempo(120);
        song.setDividingTiming(1);
        // Add an FM voice
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32; // algo=2, fb=6
        song.getVoiceBank().add(new FmVoice("Lead", voiceData));
        // Add some FM1 data
        song.getPatterns().get(0).setTrackData(0,
                new byte[]{(byte) 0x80, 0x30});

        File file = new File(tempDir, "test-voice.bin");
        new SmpsExporter().export(song, file);

        byte[] exported = Files.readAllBytes(file.toPath());
        // Voice pointer is at bytes 0-1 (LE)
        int voicePtr = (exported[0] & 0xFF) | ((exported[1] & 0xFF) << 8);
        assertTrue(voicePtr > 0, "Voice pointer should be non-zero");
        assertTrue(voicePtr + 25 <= exported.length, "Voice data should fit within exported file");
        // Verify the voice data at the pointer offset matches
        assertEquals(0x32, exported[voicePtr] & 0xFF, "First voice byte (FB_ALG) should match");
    }
}
