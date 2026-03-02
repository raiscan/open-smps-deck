package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestHierarchyCompiler {

    @Test
    void singleInlinedPhraseCompilesDirectly() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Test", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18}); // C-5, duration 24

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(phrase.getId()));

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should be: note bytes + STOP
        assertEquals((byte) 0xA1, track[0]);
        assertEquals(0x18, track[1]);
        assertEquals((byte) SmpsCoordFlags.STOP, track[2]);
    }

    @Test
    void chainLoopPointEmitsJump() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Loop", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(phrase.getId()));
        chain.setLoopEntryIndex(0); // loop back to start

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should end with JUMP (F6) + pointer back to start
        int lastIdx = track.length - 3;
        assertEquals((byte) SmpsCoordFlags.JUMP, track[lastIdx]);
    }

    @Test
    void repeatCountEmitsLoopWithCorrectParameterOrder() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Drum", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});

        var chain = arr.getChain(0);
        var entry = new ChainEntry(phrase.getId());
        entry.setRepeatCount(4);
        chain.getEntries().add(entry);

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Find LOOP command and verify parameter order: F7 <index> <count> <ptr_lo> <ptr_hi>
        int loopPos = -1;
        for (int i = 0; i < track.length; i++) {
            if ((track[i] & 0xFF) == SmpsCoordFlags.LOOP) { loopPos = i; break; }
        }
        assertTrue(loopPos >= 0, "Expected LOOP command in compiled track");
        assertEquals(0, track[loopPos + 1] & 0xFF, "LOOP index should be 0");
        assertEquals(4, track[loopPos + 2] & 0xFF, "LOOP count should be 4");
    }

    @Test
    void transposeEmitsKeyDisp() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Melody", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});

        var chain = arr.getChain(0);
        var entry = new ChainEntry(phrase.getId());
        entry.setTransposeSemitones(7);
        chain.getEntries().add(entry);

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should start with KEY_DISP (E9) + 07
        assertEquals((byte) SmpsCoordFlags.KEY_DISP, track[0]);
        assertEquals(0x07, track[1]);
    }

    @Test
    void transposeResetAfterPhrase() {
        var arr = new HierarchicalArrangement();
        var p1 = arr.getPhraseLibrary().createPhrase("Trans", ChannelType.FM);
        p1.setData(new byte[]{(byte) 0xA1, 0x18});
        var p2 = arr.getPhraseLibrary().createPhrase("Normal", ChannelType.FM);
        p2.setData(new byte[]{(byte) 0xA5, 0x18});

        var chain = arr.getChain(0);
        var e1 = new ChainEntry(p1.getId());
        e1.setTransposeSemitones(5);
        chain.getEntries().add(e1);
        chain.getEntries().add(new ChainEntry(p2.getId()));

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should contain KEY_DISP +5 before p1, KEY_DISP 0 before p2
        boolean foundReset = false;
        for (int i = 2; i < track.length - 1; i++) {
            if ((track[i] & 0xFF) == SmpsCoordFlags.KEY_DISP && track[i + 1] == 0) {
                foundReset = true;
                break;
            }
        }
        assertTrue(foundReset, "Expected KEY_DISP reset to 0 between transposed and normal phrase");
    }

    @Test
    void emptyChainEmitsStop() {
        var arr = new HierarchicalArrangement();
        var chain = arr.getChain(0);
        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        assertEquals(1, track.length);
        assertEquals((byte) SmpsCoordFlags.STOP, track[0]);
    }

    @Test
    void sharedPhraseEmitsCallReturn() {
        var arr = new HierarchicalArrangement();
        var shared = arr.getPhraseLibrary().createPhrase("Shared", ChannelType.FM);
        shared.setData(new byte[]{(byte) 0xA1, 0x18});

        // Two entries reference the same phrase
        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(shared.getId()));
        chain.getEntries().add(new ChainEntry(shared.getId()));

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should contain CALL (F8) bytes
        int callCount = 0;
        for (int i = 0; i < track.length; i++) {
            if ((track[i] & 0xFF) == SmpsCoordFlags.CALL) callCount++;
        }
        assertEquals(2, callCount, "Expected 2 CALL instructions for shared phrase");
    }
}
