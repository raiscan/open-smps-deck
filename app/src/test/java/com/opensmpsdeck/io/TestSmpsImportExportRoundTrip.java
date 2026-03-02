package com.opensmpsdeck.io;

import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Import→export round-trip fidelity tests.
 * Verifies that songs survive compile→import with hierarchical decompilation
 * and that note compensation is correctly handled for S1/S3K modes.
 */
class TestSmpsImportExportRoundTrip {

    private static void addPhrase(Song song, int channel, byte[] data) {
        var arr = song.getHierarchicalArrangement();
        int end = data.length;
        while (end > 0 && (data[end - 1] & 0xFF) == 0xF2) end--;
        byte[] phraseData = end < data.length ? Arrays.copyOf(data, end) : data;
        var phrase = arr.getPhraseLibrary().createPhrase(
            "Ch" + channel, ChannelType.fromChannelIndex(channel));
        phrase.setData(phraseData);
        arr.getChain(channel).getEntries().add(new ChainEntry(phrase.getId()));
    }

    private static void setLoopOnActiveChains(Song song, int entryIndex) {
        var arr = song.getHierarchicalArrangement();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            var chain = arr.getChain(ch);
            if (!chain.getEntries().isEmpty() && entryIndex < chain.getEntries().size()) {
                chain.setLoopEntryIndex(entryIndex);
            }
        }
    }

    @Test
    void testS2RoundTripPreservesNotes() {
        Song original = new Song();
        original.setSmpsMode(SmpsMode.S2);
        original.getVoiceBank().add(new FmVoice("V0", new byte[FmVoice.VOICE_SIZE]));

        byte[] phraseData = {
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA1, 0x30,  // C4, dur 0x30
            (byte) 0xB5, 0x20,  // note, dur 0x20
            (byte) 0xC9, 0x10   // note, dur 0x10
        };
        addPhrase(original, 0, phraseData);
        setLoopOnActiveChains(original, 0);

        // Compile to SMPS binary
        byte[] smps = new PatternCompiler().compile(original);

        // Import back
        Song imported = new SmpsImporter().importData(smps, "s2-round-trip.bin");

        // Verify hierarchical arrangement is populated
        var hier = imported.getHierarchicalArrangement();
        assertNotNull(hier);
        assertFalse(hier.getChain(0).getEntries().isEmpty(),
            "Imported song should have chain entries on channel 0");

        // Extract phrase data from imported hierarchical arrangement
        ChainEntry entry = hier.getChain(0).getEntries().get(0);
        Phrase importedPhrase = hier.getPhraseLibrary().getPhrase(entry.getPhraseId());
        assertNotNull(importedPhrase);

        // Phrase data should contain the original note bytes (S2 has no compensation)
        byte[] importedData = importedPhrase.getData();
        assertTrue(containsNoteSequence(importedData, new int[]{0xA1, 0xB5, 0xC9}),
            "Imported phrase should preserve original note bytes");
    }

    @Test
    void testS1RoundTripPreservesNotesWithCompensation() {
        Song original = new Song();
        original.setSmpsMode(SmpsMode.S1);
        original.getVoiceBank().add(new FmVoice("V0", new byte[FmVoice.VOICE_SIZE]));

        // Model-native notes (before +1 compensation)
        byte[] phraseData = {
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA1, 0x30,  // C4
            (byte) 0xB5, 0x20
        };
        addPhrase(original, 0, phraseData);
        setLoopOnActiveChains(original, 0);

        // Compile to S1 SMPS (+1 compensation applied)
        byte[] smps = new PatternCompiler().compile(original, SmpsMode.S1);

        // Import back as S1 (-1 compensation reversed)
        Song imported = new SmpsImporter().importData(smps, "s1-round-trip.smp");
        // importFile sets mode from extension; importData uses S2 by default.
        // For S1 fidelity test, we need to use importFile or a file-based test.
        // Since importData defaults to S2, test the file path instead.

        // Verify via file import which detects S1 mode
        java.io.File tmpDir;
        try {
            tmpDir = java.nio.file.Files.createTempDirectory("smps-rt").toFile();
            java.io.File smpFile = new java.io.File(tmpDir, "test.smp");
            java.nio.file.Files.write(smpFile.toPath(), smps);
            imported = new SmpsImporter().importFile(smpFile);
        } catch (java.io.IOException e) {
            fail("Failed to create temp file for S1 round-trip test: " + e.getMessage());
            return;
        }

        assertEquals(SmpsMode.S1, imported.getSmpsMode());

        // Verify notes are back to model-native values
        var hier = imported.getHierarchicalArrangement();
        assertFalse(hier.getChain(0).getEntries().isEmpty());
        ChainEntry entry = hier.getChain(0).getEntries().get(0);
        Phrase importedPhrase = hier.getPhraseLibrary().getPhrase(entry.getPhraseId());
        assertNotNull(importedPhrase);

        byte[] importedData = importedPhrase.getData();
        // Notes should match original (0xA1, 0xB5) — compensation reversed
        assertTrue(containsNoteSequence(importedData, new int[]{0xA1, 0xB5}),
            "S1 imported phrase should have model-native notes (compensation reversed)");
    }

    @Test
    void testRoundTripPreservesVoices() {
        Song original = new Song();
        byte[] voiceBytes = new byte[FmVoice.VOICE_SIZE];
        voiceBytes[0] = 0x32;  // algo=2, fb=6
        voiceBytes[1] = 0x71;  // op1 mul
        voiceBytes[5] = 0x1F;  // op1 tl
        original.getVoiceBank().add(new FmVoice("Lead", voiceBytes));

        addPhrase(original, 0, new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xBD, 0x30
        });
        setLoopOnActiveChains(original, 0);

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "voice-rt.bin");

        assertEquals(1, imported.getVoiceBank().size());
        FmVoice importedVoice = imported.getVoiceBank().get(0);
        assertArrayEquals(voiceBytes, importedVoice.getData(),
            "Voice data should survive round-trip with identical 25-byte content");
    }

    @Test
    void testRoundTripPreservesCoordinationFlags() {
        Song original = new Song();
        original.getVoiceBank().add(new FmVoice("V0", new byte[FmVoice.VOICE_SIZE]));

        // Phrase with PAN, MODULATION, and note data
        byte[] phraseData = {
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) SmpsCoordFlags.PAN, (byte) 0xC0,     // Pan L+R
            (byte) SmpsCoordFlags.MODULATION, 0x01, 0x02, 0x03, 0x04,  // Mod enable
            (byte) 0xA1, 0x30
        };
        addPhrase(original, 0, phraseData);
        setLoopOnActiveChains(original, 0);

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "flags-rt.bin");

        var hier = imported.getHierarchicalArrangement();
        assertFalse(hier.getChain(0).getEntries().isEmpty());

        ChainEntry entry = hier.getChain(0).getEntries().get(0);
        Phrase importedPhrase = hier.getPhraseLibrary().getPhrase(entry.getPhraseId());
        byte[] importedData = importedPhrase.getData();

        // Verify PAN flag is present
        assertTrue(containsFlag(importedData, SmpsCoordFlags.PAN),
            "PAN flag should survive round-trip");
        // Verify MODULATION flag is present
        assertTrue(containsFlag(importedData, SmpsCoordFlags.MODULATION),
            "MODULATION flag should survive round-trip");
    }

    @Test
    void testRoundTripPreservesLoopPoint() {
        Song original = new Song();
        original.getVoiceBank().add(new FmVoice("V0", new byte[FmVoice.VOICE_SIZE]));

        // Two phrases: intro + loop body
        byte[] intro = {
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA1, 0x30
        };
        byte[] loop = {
            (byte) 0xBD, 0x20,
            (byte) 0xBF, 0x10
        };
        addPhrase(original, 0, intro);
        addPhrase(original, 0, loop);

        // Loop back to entry 1 (the second phrase)
        original.getHierarchicalArrangement().getChain(0).setLoopEntryIndex(1);

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "loop-rt.bin");

        var hier = imported.getHierarchicalArrangement();
        Chain chain = hier.getChain(0);
        assertFalse(chain.getEntries().isEmpty());
        assertTrue(chain.hasLoop(), "Imported chain should have a loop point");
        assertTrue(chain.getLoopEntryIndex() >= 0, "Loop entry index should be non-negative");
    }

    @Test
    void testMultiChannelRoundTrip() {
        Song original = new Song();
        original.getVoiceBank().add(new FmVoice("V0", new byte[FmVoice.VOICE_SIZE]));

        // FM channel 0
        addPhrase(original, 0, new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA1, 0x30
        });
        // FM channel 1
        addPhrase(original, 1, new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA5, 0x20
        });
        // PSG channel (6)
        addPhrase(original, 6, new byte[]{
            (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x00,
            (byte) 0xB0, 0x18
        });

        setLoopOnActiveChains(original, 0);

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "multi-ch-rt.bin");

        var hier = imported.getHierarchicalArrangement();

        // FM channels 0, 1 should have chain entries (importer maps to consecutive FM indices)
        assertFalse(hier.getChain(0).getEntries().isEmpty(), "FM0 should have chain entries");
        assertFalse(hier.getChain(1).getEntries().isEmpty(), "FM1 should have chain entries");
        // PSG channel should have chain entries
        assertFalse(hier.getChain(6).getEntries().isEmpty(), "PSG0 should have chain entries");

        // Count total chains with entries — should be at least 3
        int activeChains = 0;
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (!hier.getChain(ch).getEntries().isEmpty()) activeChains++;
        }
        assertTrue(activeChains >= 3,
            "At least 3 channels should have hierarchical chain entries");

        // Verify each active chain has valid phrases
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (hier.getChain(ch).getEntries().isEmpty()) continue;
            ChainEntry entry = hier.getChain(ch).getEntries().get(0);
            Phrase phrase = hier.getPhraseLibrary().getPhrase(entry.getPhraseId());
            assertNotNull(phrase, "Channel " + ch + " should have a phrase");
            assertTrue(phrase.getData().length > 0,
                "Channel " + ch + " phrase should have data");
        }
    }

    // --- Helpers ---

    private boolean containsNoteSequence(byte[] data, int[] expectedNotes) {
        int noteIdx = 0;
        int pos = 0;
        while (pos < data.length && noteIdx < expectedNotes.length) {
            int b = data[pos] & 0xFF;
            if (b >= 0xE0) {
                pos += 1 + SmpsCoordFlags.getParamCount(b);
            } else if (b >= 0x81 && b <= 0xDF) {
                if (b == expectedNotes[noteIdx]) {
                    noteIdx++;
                }
                pos++;
            } else {
                pos++;
            }
        }
        return noteIdx == expectedNotes.length;
    }

    private boolean containsFlag(byte[] data, int flag) {
        int pos = 0;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            if (b == flag) return true;
            if (b >= 0xE0) {
                pos += 1 + SmpsCoordFlags.getParamCount(b);
            } else {
                pos++;
            }
        }
        return false;
    }
}
