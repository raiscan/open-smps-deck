package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestChainModel {

    @Test
    void chainEntryDefaults() {
        var entry = new ChainEntry(1);
        assertEquals(1, entry.getPhraseId());
        assertEquals(0, entry.getTransposeSemitones());
        assertEquals(1, entry.getRepeatCount());
    }

    @Test
    void chainEntryRepeatClampedToMin1() {
        var entry = new ChainEntry(1);
        entry.setRepeatCount(0);
        assertEquals(1, entry.getRepeatCount());
        entry.setRepeatCount(-5);
        assertEquals(1, entry.getRepeatCount());
    }

    @Test
    void chainEntryTransposeAcceptsSigned() {
        var entry = new ChainEntry(1);
        entry.setTransposeSemitones(-7);
        assertEquals(-7, entry.getTransposeSemitones());
        entry.setTransposeSemitones(12);
        assertEquals(12, entry.getTransposeSemitones());
    }

    @Test
    void newChainHasDefaults() {
        var chain = new Chain(0);
        assertEquals(0, chain.getChannelIndex());
        assertTrue(chain.getEntries().isEmpty());
        assertEquals(-1, chain.getLoopEntryIndex());
    }

    @Test
    void chainLoopPointClamped() {
        var chain = new Chain(0);
        chain.getEntries().add(new ChainEntry(1));
        chain.getEntries().add(new ChainEntry(2));
        chain.setLoopEntryIndex(1);
        assertEquals(1, chain.getLoopEntryIndex());
        chain.setLoopEntryIndex(-1); // no loop
        assertEquals(-1, chain.getLoopEntryIndex());
    }

    @Test
    void chainHasLoop() {
        var chain = new Chain(0);
        assertFalse(chain.hasLoop());
        chain.setLoopEntryIndex(0);
        assertTrue(chain.hasLoop());
    }
}
