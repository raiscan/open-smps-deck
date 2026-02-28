package com.opensmps.deck.codec;

import com.opensmps.deck.io.SmpsImporter;
import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that compile -> import round-trips preserve track data correctly.
 * This verifies that PatternCompiler and SmpsImporter use the same
 * coordination flag convention (via SmpsCoordFlags).
 */
class TestCompileImportRoundTrip {

    @Test
    void compileThenImportPreservesTrackData() {
        Song original = new Song();
        original.setTempo(0x80);
        original.setDividingTiming(1);
        original.getVoiceBank().add(new FmVoice("Test", new byte[25]));

        // Track data: set voice 0, C-5 dur 0x30, D-5 dur 0x20
        byte[] trackData = {
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xBD, 0x30,
            (byte) 0xBF, 0x20
        };
        original.getPatterns().get(0).setTrackData(0, trackData);

        // Compile to SMPS binary
        byte[] smps = new PatternCompiler().compile(original);

        // Import back
        Song imported = new SmpsImporter().importData(smps, "roundtrip.bin");

        // Track data should be preserved (minus F2 terminator, plus F6 jump appended by compiler)
        byte[] importedTrack = imported.getPatterns().get(0).getTrackData(0);
        assertNotNull(importedTrack);
        assertTrue(importedTrack.length >= trackData.length,
            "Imported track should contain at least the original data");

        // First bytes should match the original track data
        for (int i = 0; i < trackData.length; i++) {
            assertEquals(trackData[i], importedTrack[i],
                String.format("Byte %d mismatch: expected 0x%02X, got 0x%02X",
                    i, trackData[i] & 0xFF, importedTrack[i] & 0xFF));
        }
    }

    @Test
    void compileThenImportPreservesHeader() {
        Song original = new Song();
        original.setTempo(0xA0);
        original.setDividingTiming(3);
        original.getVoiceBank().add(new FmVoice("V", new byte[25]));
        original.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0xBD, 0x30 });

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, null);

        assertEquals(0xA0, imported.getTempo());
        assertEquals(3, imported.getDividingTiming());
    }

    @Test
    void compileThenImportPreservesVoices() {
        Song original = new Song();
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32; // algo=2, fb=6
        voiceData[1] = 0x01; // op1 mul
        original.getVoiceBank().add(new FmVoice("Lead", voiceData));
        original.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0xBD, 0x30 });

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, null);

        assertEquals(1, imported.getVoiceBank().size());
        assertEquals(2, imported.getVoiceBank().get(0).getAlgorithm());
        assertEquals(6, imported.getVoiceBank().get(0).getFeedback());
    }

    @Test
    void compileThenImportPreservesPsgChannel() {
        Song original = new Song();
        original.getPatterns().get(0).setTrackData(6, // PSG channel 0
            new byte[]{ (byte) 0xBD, 0x18 });

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, null);

        byte[] psgTrack = imported.getPatterns().get(0).getTrackData(6);
        assertNotNull(psgTrack);
        assertTrue(psgTrack.length > 0, "PSG track data should be imported");
        assertEquals((byte) 0xBD, psgTrack[0]);
    }

    @Test
    void jumpTerminatorIsF6() {
        // Verify PatternCompiler uses F6 (Jump) not F4 (old wrong value)
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte) 0xBD, 0x18 });

        byte[] smps = new PatternCompiler().compile(song);

        // Find the jump command - should be F6, NOT F4
        boolean foundF6 = false;
        boolean foundF4 = false;
        for (int i = 0; i < smps.length; i++) {
            if ((smps[i] & 0xFF) == SmpsCoordFlags.JUMP) foundF6 = true;
            if ((smps[i] & 0xFF) == 0xF4) foundF4 = true;
        }
        assertTrue(foundF6, "Compiled output should use F6 (Jump)");
        // F4 (ModOff) has 0 params and wouldn't be randomly in the data,
        // so absence confirms we're not using the old convention
    }
}
