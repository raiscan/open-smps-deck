package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip integration tests verifying that:
 * 1. Compile hierarchical → SMPS binary
 * 2. Decompile SMPS binary → phrases + chains
 * 3. Recompile → produces equivalent bytecode
 *
 * This is the core promise of the bidirectional architecture.
 */
class TestHierarchyRoundTrip {

    @Test
    void singlePhraseRoundTrips() {
        // Build a hierarchical arrangement with a single phrase
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Melody", ChannelType.FM);
        byte[] phraseData = {(byte) 0xA1, 0x18, (byte) 0xA5, 0x0C, (byte) 0xA9, 0x18};
        phrase.setData(phraseData);

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(phrase.getId()));

        // Compile
        byte[] compiled = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        assertTrue(compiled.length > 0);

        // Decompile
        var result = HierarchyDecompiler.decompileTrack(compiled, ChannelType.FM);
        assertFalse(result.phrases().isEmpty(), "Decompiler should produce at least one phrase");
        assertEquals(1, result.chainEntries().size());

        // Verify phrase data preserved
        Phrase decompiled = result.phrases().getFirst();
        assertArrayEquals(phraseData, decompiled.getData(),
            "Phrase data should be preserved through compile-decompile cycle");
    }

    @Test
    void loopPointToStartRoundTrips() {
        // Build arrangement with JUMP back to start (simple case)
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Main", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18, (byte) 0xA5, 0x0C});

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(phrase.getId()));
        chain.setLoopEntryIndex(0); // loop back to start

        // Compile
        byte[] compiled = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());

        // Verify JUMP is present
        boolean hasJump = false;
        for (byte b : compiled) {
            if ((b & 0xFF) == SmpsCoordFlags.JUMP) { hasJump = true; break; }
        }
        assertTrue(hasJump, "Compiled track should contain JUMP");

        // Decompile
        var result = HierarchyDecompiler.decompileTrack(compiled, ChannelType.FM);
        assertTrue(result.hasLoopPoint(), "Decompiler should detect loop point");
        assertEquals(0, result.loopEntryIndex(), "Loop should point to first entry");
    }

    @Test
    void loopPointWithSharedPhrasesRoundTrips() {
        // Use shared phrases (CALL/RETURN) so the decompiler can distinguish entries
        var arr = new HierarchicalArrangement();
        var intro = arr.getPhraseLibrary().createPhrase("Intro", ChannelType.FM);
        intro.setData(new byte[]{(byte) 0xA1, 0x18});
        var loop = arr.getPhraseLibrary().createPhrase("Loop", ChannelType.FM);
        loop.setData(new byte[]{(byte) 0xA5, 0x18});

        var chain = arr.getChain(0);
        // Reference intro once and loop twice to force CALL pattern
        chain.getEntries().add(new ChainEntry(intro.getId()));
        chain.getEntries().add(new ChainEntry(loop.getId()));
        chain.getEntries().add(new ChainEntry(loop.getId()));
        chain.setLoopEntryIndex(1); // loop back to first "Loop" entry

        // Compile
        byte[] compiled = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());

        // Decompile
        var result = HierarchyDecompiler.decompileTrack(compiled, ChannelType.FM);
        assertTrue(result.hasLoopPoint(), "Decompiler should detect loop point");
        // The CALL entries are structural boundaries, so loop resolution should work
        assertTrue(result.loopEntryIndex() >= 1,
            "Loop should point to an entry after the intro");
    }

    @Test
    void repeatCountRoundTrips() {
        // Build arrangement with repeat count
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Drum", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});

        var chain = arr.getChain(0);
        var entry = new ChainEntry(phrase.getId());
        entry.setRepeatCount(3);
        chain.getEntries().add(entry);

        // Compile
        byte[] compiled = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());

        // Verify LOOP command has correct parameters
        int loopPos = -1;
        for (int i = 0; i < compiled.length; i++) {
            if ((compiled[i] & 0xFF) == SmpsCoordFlags.LOOP) { loopPos = i; break; }
        }
        assertTrue(loopPos >= 0, "Should contain LOOP command");
        assertEquals(0, compiled[loopPos + 1] & 0xFF, "LOOP index should be 0");
        assertEquals(3, compiled[loopPos + 2] & 0xFF, "LOOP count should be 3");

        // Decompile
        var result = HierarchyDecompiler.decompileTrack(compiled, ChannelType.FM);
        boolean found = result.chainEntries().stream()
            .anyMatch(e -> e.getRepeatCount() == 3);
        assertTrue(found, "Decompiled chain should have entry with repeat count 3");
    }

    @Test
    void sharedPhraseRoundTrips() {
        // Build arrangement where same phrase is used twice (CALL/RETURN pattern)
        var arr = new HierarchicalArrangement();
        var shared = arr.getPhraseLibrary().createPhrase("Riff", ChannelType.FM);
        shared.setData(new byte[]{(byte) 0xA1, 0x18, (byte) 0xA5, 0x0C});

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(shared.getId()));
        chain.getEntries().add(new ChainEntry(shared.getId()));

        // Compile
        byte[] compiled = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());

        // Should have 2 CALL instructions
        int callCount = 0;
        for (byte b : compiled) {
            if ((b & 0xFF) == SmpsCoordFlags.CALL) callCount++;
        }
        assertEquals(2, callCount, "Should have 2 CALL instructions");

        // Decompile
        var result = HierarchyDecompiler.decompileTrack(compiled, ChannelType.FM);
        assertTrue(result.sharedPhraseCount() >= 1, "Should detect shared phrases");
        assertEquals(2, result.chainEntries().size(), "Should have 2 chain entries");
    }

    @Test
    void transposeRoundTrips() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Bass", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});

        var chain = arr.getChain(0);
        var e1 = new ChainEntry(phrase.getId());
        e1.setTransposeSemitones(7);
        chain.getEntries().add(e1);

        // Compile
        byte[] compiled = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());

        // Verify KEY_DISP present
        assertEquals((byte) SmpsCoordFlags.KEY_DISP, compiled[0]);
        assertEquals(7, compiled[1]);
    }

    @Test
    void effectMnemonicPanRoundTrips() {
        // Verify PAN with AMS/FMS round-trips
        int flag = SmpsCoordFlags.PAN;
        int[] params = {0xD7}; // L+R pan (0xC0) + AMS/FMS (0x17)
        String mnemonic = EffectMnemonics.format(flag, params);
        assertEquals("PAN LR 17", mnemonic);

        // Parse back
        var parsed = EffectMnemonics.parse(mnemonic);
        assertNotNull(parsed);
        assertEquals(SmpsCoordFlags.PAN, parsed.flag());
        assertEquals(0xD7, parsed.params()[0]);
    }

    @Test
    void effectMnemonicSetVoiceRoundTrips() {
        String formatted = EffectMnemonics.format(SmpsCoordFlags.SET_VOICE, new int[]{0x03});
        assertEquals("VOI 03", formatted);

        var parsed = EffectMnemonics.parse(formatted);
        assertNotNull(parsed);
        assertEquals(SmpsCoordFlags.SET_VOICE, parsed.flag());
        assertEquals(0x03, parsed.params()[0]);
    }

    @Test
    void effectMnemonicPsgInstrumentRoundTrips() {
        String formatted = EffectMnemonics.format(SmpsCoordFlags.PSG_INSTRUMENT, new int[]{0x02});
        assertEquals("PSI 02", formatted);

        var parsed = EffectMnemonics.parse(formatted);
        assertNotNull(parsed);
        assertEquals(SmpsCoordFlags.PSG_INSTRUMENT, parsed.flag());
        assertEquals(0x02, parsed.params()[0]);
    }

    @Test
    void compileDecompileRecompileProducesEquivalentBytecode() {
        // The ultimate round-trip: compile, decompile, recompile
        var arr = new HierarchicalArrangement();
        var p1 = arr.getPhraseLibrary().createPhrase("A", ChannelType.FM);
        p1.setData(new byte[]{(byte) 0xA1, 0x18, (byte) 0xA3, 0x0C});
        var p2 = arr.getPhraseLibrary().createPhrase("B", ChannelType.FM);
        p2.setData(new byte[]{(byte) 0xA5, 0x18, (byte) 0xA7, 0x0C});

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(p1.getId()));
        chain.getEntries().add(new ChainEntry(p2.getId()));

        // First compile
        byte[] compiled1 = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());

        // Decompile
        var result = HierarchyDecompiler.decompileTrack(compiled1, ChannelType.FM);

        // Rebuild arrangement from decompiled result
        var arr2 = new HierarchicalArrangement();
        PhraseLibrary lib2 = arr2.getPhraseLibrary();
        java.util.Map<Integer, Integer> idMap = new java.util.HashMap<>();
        for (Phrase p : result.phrases()) {
            Phrase newPhrase = lib2.createPhrase(p.getName(), p.getChannelType());
            newPhrase.setData(p.getData());
            idMap.put(p.getId(), newPhrase.getId());
        }
        Chain chain2 = arr2.getChain(0);
        for (ChainEntry entry : result.chainEntries()) {
            Integer newId = idMap.get(entry.getPhraseId());
            assertNotNull(newId, "All phrase IDs should be mapped");
            ChainEntry newEntry = new ChainEntry(newId);
            newEntry.setRepeatCount(entry.getRepeatCount());
            newEntry.setTransposeSemitones(entry.getTransposeSemitones());
            chain2.getEntries().add(newEntry);
        }
        if (result.hasLoopPoint()) {
            chain2.setLoopEntryIndex(result.loopEntryIndex());
        }

        // Second compile
        byte[] compiled2 = HierarchyCompiler.compileChain(chain2, lib2);

        // The compiled output should be equivalent
        assertArrayEquals(compiled1, compiled2,
            "Compile→decompile→recompile should produce identical bytecode.\n" +
            "First:  " + Arrays.toString(compiled1) + "\n" +
            "Second: " + Arrays.toString(compiled2));
    }
}
