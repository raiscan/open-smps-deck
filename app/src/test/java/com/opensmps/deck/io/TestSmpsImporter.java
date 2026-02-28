package com.opensmps.deck.io;

import com.opensmps.deck.codec.PatternCompiler;
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

    @Test
    void testImportPreservesFmHeaderOffsetsAsLeadingFlags() {
        byte[] smps = new byte[0x10];
        smps[0] = 0x00; smps[1] = 0x00; // no voice table
        smps[2] = 0x01; // 1 FM channel
        smps[3] = 0x00; // 0 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        smps[6] = 0x0A; smps[7] = 0x00; // track ptr
        smps[8] = 0x02;                  // key offset
        smps[9] = (byte) 0xFF;           // volume offset -1
        smps[0x0A] = (byte) 0xA1;
        smps[0x0B] = 0x30;
        smps[0x0C] = (byte) SmpsCoordFlags.STOP;

        Song song = new SmpsImporter().importData(smps, "fm-offsets");
        byte[] track = song.getPatterns().get(0).getTrackData(0);
        assertArrayEquals(new byte[]{
                (byte) SmpsCoordFlags.KEY_DISP, 0x02,
                (byte) SmpsCoordFlags.VOLUME, (byte) 0xFF,
                (byte) 0xA1, 0x30, (byte) SmpsCoordFlags.STOP
        }, track);
    }

    @Test
    void testImportPreservesPsgHeaderStateAsLeadingFlags() {
        byte[] smps = new byte[0x20];
        smps[0] = 0x00; smps[1] = 0x00; // no voice table
        smps[2] = 0x00; // 0 FM
        smps[3] = 0x01; // 1 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        smps[6] = 0x0C; smps[7] = 0x00;             // track ptr
        smps[8] = (byte) 0xFF;                      // key offset -1
        smps[9] = 0x03;                              // volume offset +3
        smps[10] = 0x05;                             // mod env (not preserved in model)
        smps[11] = 0x07;                             // PSG instrument
        smps[0x0C] = (byte) 0xA1;
        smps[0x0D] = 0x30;
        smps[0x0E] = (byte) SmpsCoordFlags.STOP;

        Song song = new SmpsImporter().importData(smps, "psg-state");
        byte[] track = song.getPatterns().get(0).getTrackData(6);
        assertArrayEquals(new byte[]{
                (byte) SmpsCoordFlags.KEY_DISP, (byte) 0xFF,
                (byte) SmpsCoordFlags.PSG_VOLUME, 0x03,
                (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x07,
                (byte) 0xA1, 0x30, (byte) SmpsCoordFlags.STOP
        }, track);
    }

    @Test
    void testImportSongWithPsgChannel() {
        // Build a song with 1 FM channel and 1 PSG channel
        Song original = new Song();
        original.setTempo(0x90);
        original.setDividingTiming(1);
        original.getPatterns().clear();
        original.getOrderList().clear();

        // Add a voice for the FM channel
        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        voiceData[0] = 0x32; // algo=2, fb=6
        original.getVoiceBank().add(new FmVoice("Voice 0", voiceData));

        Pattern pattern = new Pattern(0, 64);

        // FM channel 0: set voice, play a note, stop
        pattern.setTrackData(0, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA1, 0x30,               // note C4, duration 0x30
                (byte) SmpsCoordFlags.STOP
        });

        // PSG channel 0 (index 6): PSG instrument, note, stop
        pattern.setTrackData(6, new byte[]{
                (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x03,
                (byte) 0xB0, 0x18,               // note, duration
                (byte) SmpsCoordFlags.STOP
        });

        original.getPatterns().add(pattern);
        int[] orderRow = new int[Pattern.CHANNEL_COUNT];
        original.getOrderList().add(orderRow);

        // Compile to SMPS binary
        byte[] smps = new PatternCompiler().compile(original);

        // Re-import
        Song imported = new SmpsImporter().importData(smps, "psg-test.bin");

        assertEquals(0x90, imported.getTempo());
        assertEquals(1, imported.getDividingTiming());
        assertTrue(imported.getPatterns().size() > 0);

        Pattern importedPattern = imported.getPatterns().get(0);

        // FM channel 0 should have track data
        byte[] fmTrack = importedPattern.getTrackData(0);
        assertNotNull(fmTrack);
        assertTrue(fmTrack.length > 0, "FM channel should have track data");

        // PSG channel 0 (index 6) should have track data
        byte[] psgTrack = importedPattern.getTrackData(6);
        assertNotNull(psgTrack);
        assertTrue(psgTrack.length > 0, "PSG channel should have track data");

        // Verify the PSG track contains the PSG_INSTRUMENT flag and the note
        boolean foundPsgInstrument = false;
        boolean foundNote = false;
        for (int i = 0; i < psgTrack.length; i++) {
            if ((psgTrack[i] & 0xFF) == SmpsCoordFlags.PSG_INSTRUMENT) {
                foundPsgInstrument = true;
            }
            if ((psgTrack[i] & 0xFF) == 0xB0) {
                foundNote = true;
            }
        }
        assertTrue(foundPsgInstrument, "PSG track should contain PSG_INSTRUMENT command");
        assertTrue(foundNote, "PSG track should contain the note byte 0xB0");
    }

    @Test
    void testImportSongWithMultipleFmChannels() {
        // Build a song with 3 FM channels, each with different notes
        Song original = new Song();
        original.setTempo(0x80);
        original.setDividingTiming(1);
        original.getPatterns().clear();
        original.getOrderList().clear();

        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        voiceData[0] = 0x1A; // algo=2, fb=3
        original.getVoiceBank().add(new FmVoice("Voice 0", voiceData));

        Pattern pattern = new Pattern(0, 64);

        // Channel 0: note 0xA1 (C4)
        pattern.setTrackData(0, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA1, 0x30,
                (byte) SmpsCoordFlags.STOP
        });

        // Channel 1: note 0xA5 (E4)
        pattern.setTrackData(1, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA5, 0x30,
                (byte) SmpsCoordFlags.STOP
        });

        // Channel 2: note 0xA8 (G4)
        pattern.setTrackData(2, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA8, 0x30,
                (byte) SmpsCoordFlags.STOP
        });

        original.getPatterns().add(pattern);
        int[] orderRow = new int[Pattern.CHANNEL_COUNT];
        original.getOrderList().add(orderRow);

        // Compile and reimport
        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "multi-fm.bin");

        Pattern importedPattern = imported.getPatterns().get(0);

        // All 3 channels should have track data
        byte[] track0 = importedPattern.getTrackData(0);
        byte[] track1 = importedPattern.getTrackData(1);
        byte[] track2 = importedPattern.getTrackData(2);

        assertNotNull(track0);
        assertNotNull(track1);
        assertNotNull(track2);
        assertTrue(track0.length > 0, "Channel 0 should have track data");
        assertTrue(track1.length > 0, "Channel 1 should have track data");
        assertTrue(track2.length > 0, "Channel 2 should have track data");

        // Each channel should have a distinct note byte
        assertTrue(containsByte(track0, (byte) 0xA1), "Channel 0 should contain note 0xA1");
        assertTrue(containsByte(track1, (byte) 0xA5), "Channel 1 should contain note 0xA5");
        assertTrue(containsByte(track2, (byte) 0xA8), "Channel 2 should contain note 0xA8");

        // Channels should not contain each other's notes
        assertFalse(containsByte(track0, (byte) 0xA5), "Channel 0 should not contain note 0xA5");
        assertFalse(containsByte(track1, (byte) 0xA8), "Channel 1 should not contain note 0xA8");
        assertFalse(containsByte(track2, (byte) 0xA1), "Channel 2 should not contain note 0xA1");
    }

    @Test
    void testImportRejectsTruncatedHeader() {
        // A 2-byte array is too short for the 6-byte SMPS header
        assertThrows(IllegalArgumentException.class, () -> {
            new SmpsImporter().importData(new byte[2], "truncated");
        });

        // 5 bytes is still too short
        assertThrows(IllegalArgumentException.class, () -> {
            new SmpsImporter().importData(new byte[5], "truncated");
        });

        // Empty array
        assertThrows(IllegalArgumentException.class, () -> {
            new SmpsImporter().importData(new byte[0], "empty");
        });
    }

    @Test
    void testImportHandlesEmptyTrackData() {
        // Build a song where one channel has only a rest (0x80) followed by stop
        Song original = new Song();
        original.setTempo(0x80);
        original.setDividingTiming(1);
        original.getPatterns().clear();
        original.getOrderList().clear();

        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        original.getVoiceBank().add(new FmVoice("Voice 0", voiceData));

        Pattern pattern = new Pattern(0, 64);

        // Channel 0: normal note
        pattern.setTrackData(0, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA1, 0x30,
                (byte) SmpsCoordFlags.STOP
        });

        // Channel 1: only a rest (0x80 = rest note) + duration + stop
        pattern.setTrackData(1, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0x80, 0x30,               // rest, duration
                (byte) SmpsCoordFlags.STOP
        });

        original.getPatterns().add(pattern);
        int[] orderRow = new int[Pattern.CHANNEL_COUNT];
        original.getOrderList().add(orderRow);

        // Compile and reimport
        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "rest-only.bin");

        Pattern importedPattern = imported.getPatterns().get(0);

        // Both channels should have track data present
        byte[] track0 = importedPattern.getTrackData(0);
        byte[] track1 = importedPattern.getTrackData(1);

        assertNotNull(track0);
        assertNotNull(track1);
        assertTrue(track0.length > 0, "Channel 0 should have track data");
        assertTrue(track1.length > 0, "Channel 1 (rest-only) should have track data");

        // The rest-only channel should contain the rest byte (0x80)
        assertTrue(containsByte(track1, (byte) 0x80), "Rest-only channel should contain rest byte 0x80");
    }

    private boolean containsByte(byte[] data, byte target) {
        for (byte b : data) {
            if (b == target) return true;
        }
        return false;
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
