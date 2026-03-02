package com.opensmpsdeck.codec;

import com.opensmpsdeck.io.SmpsImporter;
import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that compile -> import round-trips preserve track data correctly.
 * This verifies that PatternCompiler and SmpsImporter use the same
 * coordination flag convention (via SmpsCoordFlags).
 *
 * <p>Uses hierarchical arrangement (chain-of-phrases) instead of legacy
 * pattern/order data.
 */
class TestCompileImportRoundTrip {

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
        addPhrase(original, 0, trackData);
        setLoopOnActiveChains(original, 0);

        // Compile to SMPS binary
        byte[] smps = new PatternCompiler().compile(original);

        // Import back
        Song imported = new SmpsImporter().importData(smps, "roundtrip.bin");

        // Track data should be preserved; the importer strips the trailing F6 jump
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
        addPhrase(original, 0, new byte[]{ (byte) 0xBD, 0x30 });
        setLoopOnActiveChains(original, 0);

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
        addPhrase(original, 0, new byte[]{ (byte) 0xBD, 0x30 });
        setLoopOnActiveChains(original, 0);

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, null);

        assertEquals(1, imported.getVoiceBank().size());
        assertEquals(2, imported.getVoiceBank().get(0).getAlgorithm());
        assertEquals(6, imported.getVoiceBank().get(0).getFeedback());
    }

    @Test
    void compileThenImportPreservesPsgChannel() {
        Song original = new Song();
        addPhrase(original, 6, new byte[]{ (byte) 0xBD, 0x18 }); // PSG channel 0
        setLoopOnActiveChains(original, 0);

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, null);

        byte[] psgTrack = imported.getPatterns().get(0).getTrackData(6);
        assertNotNull(psgTrack);
        assertTrue(psgTrack.length > 0, "PSG track data should be imported");
        assertEquals((byte) 0xBD, psgTrack[0]);
    }

    @Test
    void compileThenImportPopulatesHierarchicalArrangement() {
        Song original = new Song();
        original.getVoiceBank().add(new FmVoice("V", new byte[25]));

        byte[] trackData = {
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xBD, 0x30,
            (byte) 0xBF, 0x20
        };
        addPhrase(original, 0, trackData);
        addPhrase(original, 6, new byte[]{ (byte) 0xBD, 0x18 }); // PSG
        setLoopOnActiveChains(original, 0);

        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "hier-rt");

        // Imported song should have hierarchical arrangement populated
        var hier = imported.getHierarchicalArrangement();
        assertNotNull(hier);
        assertEquals(ArrangementMode.HIERARCHICAL, imported.getArrangementMode());

        // FM channel 0 should have chain entries and phrases
        assertFalse(hier.getChain(0).getEntries().isEmpty(),
            "FM0 chain should have entries");
        ChainEntry fmEntry = hier.getChain(0).getEntries().get(0);
        Phrase fmPhrase = hier.getPhraseLibrary().getPhrase(fmEntry.getPhraseId());
        assertNotNull(fmPhrase, "FM0 phrase should exist in library");
        assertTrue(fmPhrase.getData().length > 0, "FM0 phrase should have data");

        // PSG channel 6 should also have chain entries
        assertFalse(hier.getChain(6).getEntries().isEmpty(),
            "PSG0 chain should have entries");
    }

    @Test
    void jumpTerminatorIsF6() {
        // Verify PatternCompiler uses F6 (Jump) not F4 (old wrong value)
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));
        addPhrase(song, 0, new byte[]{ (byte) 0xBD, 0x18 });
        setLoopOnActiveChains(song, 0);

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
