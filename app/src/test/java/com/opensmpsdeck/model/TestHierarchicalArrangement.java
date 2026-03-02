package com.opensmpsdeck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestHierarchicalArrangement {

    @Test
    void phraseLibraryAllocatesIncrementingIds() {
        var lib = new PhraseLibrary();
        var p1 = lib.createPhrase("A", ChannelType.FM);
        var p2 = lib.createPhrase("B", ChannelType.PSG_TONE);
        assertEquals(1, p1.getId());
        assertEquals(2, p2.getId());
    }

    @Test
    void phraseLibraryFindsById() {
        var lib = new PhraseLibrary();
        var p = lib.createPhrase("Test", ChannelType.FM);
        assertSame(p, lib.getPhrase(p.getId()));
        assertNull(lib.getPhrase(999));
    }

    @Test
    void phraseLibraryRemovesById() {
        var lib = new PhraseLibrary();
        var p = lib.createPhrase("Test", ChannelType.FM);
        assertTrue(lib.removePhrase(p.getId()));
        assertNull(lib.getPhrase(p.getId()));
        assertFalse(lib.removePhrase(p.getId()));
    }

    @Test
    void arrangementHasTenChains() {
        var arr = new HierarchicalArrangement();
        assertEquals(10, arr.getChains().size());
        for (int ch = 0; ch < 10; ch++) {
            assertEquals(ch, arr.getChains().get(ch).getChannelIndex());
        }
    }

    @Test
    void arrangementHasEmptyPhraseLibrary() {
        var arr = new HierarchicalArrangement();
        assertTrue(arr.getPhraseLibrary().getAllPhrases().isEmpty());
    }

    @Test
    void cycleDetectionRejectsSelfReference() {
        var arr = new HierarchicalArrangement();
        var p = arr.getPhraseLibrary().createPhrase("Self", ChannelType.FM);
        assertTrue(arr.wouldCreateCycle(p.getId(), p.getId()));
    }

    @Test
    void cycleDetectionRejectsIndirectCycle() {
        var arr = new HierarchicalArrangement();
        var lib = arr.getPhraseLibrary();
        var a = lib.createPhrase("A", ChannelType.FM);
        var b = lib.createPhrase("B", ChannelType.FM);
        a.getSubPhraseRefs().add(new Phrase.SubPhraseRef(b.getId(), 0, 1));
        // b referencing a would create a cycle
        assertTrue(arr.wouldCreateCycle(b.getId(), a.getId()));
    }

    @Test
    void cycleDetectionAllowsValidReference() {
        var arr = new HierarchicalArrangement();
        var lib = arr.getPhraseLibrary();
        var a = lib.createPhrase("A", ChannelType.FM);
        var b = lib.createPhrase("B", ChannelType.FM);
        assertFalse(arr.wouldCreateCycle(a.getId(), b.getId()));
    }

    @Test
    void maxDepthEnforced() {
        var arr = new HierarchicalArrangement();
        var lib = arr.getPhraseLibrary();
        // Build chain: p1 -> p2 -> p3 -> p4 (depth 3)
        var p1 = lib.createPhrase("L1", ChannelType.FM);
        var p2 = lib.createPhrase("L2", ChannelType.FM);
        var p3 = lib.createPhrase("L3", ChannelType.FM);
        var p4 = lib.createPhrase("L4", ChannelType.FM);
        var p5 = lib.createPhrase("L5", ChannelType.FM);
        p1.getSubPhraseRefs().add(new Phrase.SubPhraseRef(p2.getId(), 0, 1));
        p2.getSubPhraseRefs().add(new Phrase.SubPhraseRef(p3.getId(), 0, 1));
        p3.getSubPhraseRefs().add(new Phrase.SubPhraseRef(p4.getId(), 0, 1));
        // p4 -> p5 would be depth 4 (allowed, max is 4)
        assertFalse(arr.wouldCreateCycle(p4.getId(), p5.getId()));
        assertEquals(4, arr.getDepth(p1.getId()));
    }
}
