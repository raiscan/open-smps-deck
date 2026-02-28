package com.opensmps.deck.io;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class TestProjectFile {

    @TempDir
    File tempDir;

    @Test
    void testSaveAndLoadRoundTrip() throws IOException {
        Song original = new Song();
        original.setName("Test Song");
        original.setSmpsMode(SmpsMode.S3K);
        original.setTempo(0xA0);
        original.setDividingTiming(2);
        original.setLoopPoint(1);

        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32; // algo=2, fb=6
        voiceData[1] = 0x01;
        original.getVoiceBank().add(new FmVoice("Lead", voiceData));

        byte[] envData = { 0, 1, 2, 3, 4, 5, (byte) 0x80 };
        original.getPsgEnvelopes().add(new PsgEnvelope("Pluck", envData));

        original.getPatterns().get(0).setTrackData(0,
                new byte[]{ (byte) 0xEF, 0x00, (byte) 0xA1, 0x30, (byte) 0xF2 });
        original.getPatterns().get(0).setTrackData(6,
                new byte[]{ (byte) 0xA1, 0x20 });

        original.getOrderList().add(new int[]{ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });

        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        Song loaded = ProjectFile.load(file);

        assertEquals("Test Song", loaded.getName());
        assertEquals(SmpsMode.S3K, loaded.getSmpsMode());
        assertEquals(0xA0, loaded.getTempo());
        assertEquals(2, loaded.getDividingTiming());
        assertEquals(1, loaded.getLoopPoint());

        // Voices
        assertEquals(1, loaded.getVoiceBank().size());
        FmVoice loadedVoice = loaded.getVoiceBank().get(0);
        assertEquals("Lead", loadedVoice.getName());
        assertEquals(2, loadedVoice.getAlgorithm());
        assertEquals(6, loadedVoice.getFeedback());
        assertEquals(voiceData[1], loadedVoice.getData()[1]);

        // PSG envelopes
        assertEquals(1, loaded.getPsgEnvelopes().size());
        assertEquals("Pluck", loaded.getPsgEnvelopes().get(0).getName());
        assertEquals(6, loaded.getPsgEnvelopes().get(0).getStepCount());

        // Order list
        assertEquals(2, loaded.getOrderList().size());

        // Patterns - track data
        assertArrayEquals(
                new byte[]{ (byte) 0xEF, 0x00, (byte) 0xA1, 0x30, (byte) 0xF2 },
                loaded.getPatterns().get(0).getTrackData(0));
        assertArrayEquals(
                new byte[]{ (byte) 0xA1, 0x20 },
                loaded.getPatterns().get(0).getTrackData(6));
        // Empty channels should remain empty
        assertEquals(0, loaded.getPatterns().get(0).getTrackData(3).length);
    }

    @Test
    void testHexConversion() {
        byte[] data = { 0x00, (byte) 0xFF, 0x7F, (byte) 0x80 };
        String hex = ProjectFile.bytesToHex(data);
        assertEquals("00 FF 7F 80", hex);
        assertArrayEquals(data, ProjectFile.hexToBytes(hex));
    }

    @Test
    void testEmptyHexConversion() {
        assertEquals("", ProjectFile.bytesToHex(new byte[0]));
        assertArrayEquals(new byte[0], ProjectFile.hexToBytes(""));
        assertArrayEquals(new byte[0], ProjectFile.hexToBytes("  "));
    }
}
