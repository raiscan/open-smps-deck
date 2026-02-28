package com.opensmps.deck.io;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class TestSmpsImporter {

    @TempDir
    File tempDir;

    @Test
    void testImportMinimalSong() {
        // Build a minimal SMPS binary:
        // Header: voice ptr at 0x12, 1 FM ch, 0 PSG, dividing=1, tempo=0x80
        // FM entry: track at 0x0A, key=0, vol=0
        // Track at 0x0A: EF 00 A1 30 F2
        // Voice at 0x12: 25 bytes
        byte[] smps = new byte[0x12 + 25];
        // Header
        smps[0] = 0x12; smps[1] = 0x00; // voice ptr
        smps[2] = 0x01; // 1 FM channel
        smps[3] = 0x00; // 0 PSG
        smps[4] = 0x01; // dividing timing
        smps[5] = (byte) 0x80; // tempo
        // FM entry
        smps[6] = 0x0A; smps[7] = 0x00; // track ptr
        smps[8] = 0x00; // key offset
        smps[9] = 0x00; // vol offset
        // Track data at 0x0A
        smps[0x0A] = (byte) SmpsCoordFlags.SET_VOICE; // EF = set voice
        smps[0x0B] = 0x00;        // voice 0
        smps[0x0C] = (byte) 0xA1; // note C4
        smps[0x0D] = 0x30;        // duration
        smps[0x0E] = (byte) SmpsCoordFlags.STOP; // F2 = track end
        // Voice at 0x12 (leave as zeros = default voice)
        smps[0x12] = 0x32; // algo=2, fb=6

        Song song = new SmpsImporter().importData(smps, "test.bin");

        assertEquals("test", song.getName());
        assertEquals(1, song.getDividingTiming());
        assertEquals(0x80, song.getTempo());
        assertEquals(1, song.getVoiceBank().size());
        assertEquals(2, song.getVoiceBank().get(0).getAlgorithm());
        assertEquals(6, song.getVoiceBank().get(0).getFeedback());
        assertEquals(1, song.getPatterns().size());

        // Track data should be extracted
        byte[] track = song.getPatterns().get(0).getTrackData(0);
        assertNotNull(track);
        assertTrue(track.length > 0);
        assertEquals((byte) SmpsCoordFlags.SET_VOICE, track[0]); // first byte is set voice (EF)
    }

    @Test
    void testImportFromFile() throws IOException {
        byte[] smps = buildMinimalSmps();
        File file = new File(tempDir, "song.bin");
        Files.write(file.toPath(), smps);

        Song song = new SmpsImporter().importFile(file);
        assertNotNull(song);
        assertEquals("song", song.getName()); // extension stripped
        assertTrue(song.getPatterns().size() > 0);
    }

    @Test
    void testImportPreservesHeader() {
        byte[] smps = buildMinimalSmps();
        smps[4] = 0x02; // dividing timing = 2
        smps[5] = (byte) 0xC0; // tempo = 0xC0

        Song song = new SmpsImporter().importData(smps, null);
        assertEquals(2, song.getDividingTiming());
        assertEquals(0xC0, song.getTempo());
    }

    @Test
    void testRejectsTooShort() {
        assertThrows(IllegalArgumentException.class, () -> {
            new SmpsImporter().importData(new byte[3], "short");
        });
    }

    private byte[] buildMinimalSmps() {
        byte[] smps = new byte[0x12 + 25];
        smps[0] = 0x12; smps[1] = 0x00;
        smps[2] = 0x01;
        smps[3] = 0x00;
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        smps[6] = 0x0A; smps[7] = 0x00;
        smps[8] = 0x00;
        smps[9] = 0x00;
        smps[0x0A] = (byte) 0xA1;
        smps[0x0B] = 0x30;
        smps[0x0C] = (byte) SmpsCoordFlags.STOP;
        return smps;
    }
}
