package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestLegacyMigrator {

    @Test
    void migratesSimpleSong() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.LEGACY_PATTERNS);
        // Default song has 1 pattern (id=0, 64 rows) and 1 order row

        var pattern = song.getPatterns().get(0);
        pattern.setTrackData(0, new byte[]{(byte) 0xA1, 0x18}); // FM1: one note

        var result = LegacyMigrator.migrate(song);

        assertEquals(ArrangementMode.HIERARCHICAL, result.getArrangementMode());
        assertNotNull(result.getHierarchicalArrangement());

        var arr = result.getHierarchicalArrangement();
        // Should have at least 1 phrase (for the non-empty FM1 channel)
        assertFalse(arr.getPhraseLibrary().getAllPhrases().isEmpty());

        // FM1 chain should have 1 entry
        var chain0 = arr.getChain(0);
        assertEquals(1, chain0.getEntries().size());

        // The phrase should contain the original bytecode
        int phraseId = chain0.getEntries().get(0).getPhraseId();
        var phrase = arr.getPhraseLibrary().getPhrase(phraseId);
        assertNotNull(phrase);
        assertArrayEquals(new byte[]{(byte) 0xA1, 0x18}, phrase.getData());
    }

    @Test
    void preservesLoopPoint() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.LEGACY_PATTERNS);
        // Add second pattern and order row
        song.getPatterns().add(new Pattern(1, 64));
        song.getPatterns().get(0).setTrackData(0, new byte[]{(byte) 0xA1, 0x18});
        song.getPatterns().get(1).setTrackData(0, new byte[]{(byte) 0xA5, 0x18});
        song.getOrderList().add(new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        song.setLoopPoint(0); // loop back to order 0

        var result = LegacyMigrator.migrate(song);
        var chain0 = result.getHierarchicalArrangement().getChain(0);
        assertEquals(0, chain0.getLoopEntryIndex());
    }

    @Test
    void reusesPhrasesForSamePatternIndex() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.LEGACY_PATTERNS);
        song.getPatterns().get(0).setTrackData(0, new byte[]{(byte) 0xA1, 0x18});
        // Two order rows referencing same pattern
        song.getOrderList().add(new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

        var result = LegacyMigrator.migrate(song);
        var chain0 = result.getHierarchicalArrangement().getChain(0);
        assertEquals(2, chain0.getEntries().size());
        // Both entries should reference the same phrase
        assertEquals(chain0.getEntries().get(0).getPhraseId(),
                     chain0.getEntries().get(1).getPhraseId());
    }

    @Test
    void preservesVoiceBankAndInstruments() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.LEGACY_PATTERNS);
        var voice = new FmVoice("Test", new byte[25]);
        song.getVoiceBank().add(voice);

        var result = LegacyMigrator.migrate(song);
        assertEquals(1, result.getVoiceBank().size());
        assertEquals("Test", result.getVoiceBank().get(0).getName());
    }
}
