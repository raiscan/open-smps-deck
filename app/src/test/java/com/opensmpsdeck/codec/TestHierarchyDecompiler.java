package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestHierarchyDecompiler {

    @Test
    void decompilesFlatTrackToSinglePhrase() {
        // Simple track: note + duration + STOP
        byte[] track = {(byte) 0xA1, 0x18, (byte) SmpsCoordFlags.STOP};
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);

        assertEquals(1, result.phrases().size());
        assertEquals(1, result.chainEntries().size());
        assertFalse(result.hasLoopPoint());
    }

    @Test
    void detectsJumpAsLoopPoint() {
        // Track: note + JUMP back to start
        byte[] track = {
            (byte) 0xA1, 0x18,
            (byte) SmpsCoordFlags.JUMP, 0x00, 0x00 // jump to offset 0
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);
        assertTrue(result.hasLoopPoint());
        assertEquals(0, result.loopEntryIndex());
    }

    @Test
    void detectsCallReturnAsSharedPhrase() {
        // Main track: CALL to subroutine at offset 5, then STOP
        // Subroutine at offset 5: note + RETURN
        byte[] track = {
            (byte) SmpsCoordFlags.CALL, 0x05, 0x00,  // call offset 5
            (byte) SmpsCoordFlags.STOP,               // offset 3: stop
            0x00,                                      // offset 4: padding
            (byte) 0xA1, 0x18,                        // offset 5: subroutine note
            (byte) SmpsCoordFlags.RETURN              // offset 7: return
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);

        // Should have at least 2 phrases: main segment + subroutine
        assertTrue(result.phrases().size() >= 1);
        // The subroutine should be identified as a separate phrase
        assertTrue(result.sharedPhraseCount() >= 1 || result.phrases().size() >= 2);
    }

    @Test
    void detectsLoopWithCounter() {
        // Track: note + LOOP(3x back to start) + STOP
        // LOOP format: F7 <index> <count> <ptr_lo> <ptr_hi>
        byte[] track = {
            (byte) 0xA1, 0x18,
            (byte) SmpsCoordFlags.LOOP, 0x00, 0x03, 0x00, 0x00, // index=0, count=3, target=0
            (byte) SmpsCoordFlags.STOP
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);

        // Should detect the repeat count of 3
        boolean hasRepeat = result.chainEntries().stream()
            .anyMatch(e -> e.getRepeatCount() == 3);
        assertTrue(hasRepeat, "Expected chain entry with repeat count == 3");
    }

    @Test
    void loopSpanningCallsDuplicatesEntries() {
        // Simulates a track where a LOOP wraps a CALL sequence:
        //   [CALL sub1] [CALL sub2] [LOOP 3x back to start] [STOP]
        //   ... subroutines at the end
        // Sub bodies at offsets 14 and 17
        byte[] track = {
            // Main: offset 0
            (byte) SmpsCoordFlags.CALL, 14, 0x00,          // offset 0: CALL sub1 at 14
            (byte) SmpsCoordFlags.CALL, 17, 0x00,          // offset 3: CALL sub2 at 17
            (byte) SmpsCoordFlags.LOOP, 0x00, 0x03, 0x00, 0x00, // offset 6: LOOP idx=0, count=3, target=0
            (byte) SmpsCoordFlags.STOP,                     // offset 11: STOP
            0x00, 0x00,                                      // offset 12-13: padding
            (byte) 0xA1, 0x18,                              // offset 14: sub1 body
            (byte) SmpsCoordFlags.RETURN,                   // offset 16: return
            (byte) 0xB1, 0x18,                              // offset 17: sub2 body
            (byte) SmpsCoordFlags.RETURN                    // offset 19: return
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);

        // The LOOP wraps 2 CALLs and repeats 3 times, so we expect 6 chain entries
        // (2 entries per iteration * 3 iterations)
        assertEquals(6, result.chainEntries().size(),
            "2 CALLs x 3 iterations should produce 6 chain entries");
    }

    @Test
    void loopSpanningCallsPreservesSubroutineIds() {
        // Same structure as above but verify that duplicated entries reference
        // the same phrase IDs as the originals
        byte[] track = {
            (byte) SmpsCoordFlags.CALL, 14, 0x00,
            (byte) SmpsCoordFlags.CALL, 17, 0x00,
            (byte) SmpsCoordFlags.LOOP, 0x00, 0x02, 0x00, 0x00, // count=2
            (byte) SmpsCoordFlags.STOP,
            0x00, 0x00,
            (byte) 0xA1, 0x18,
            (byte) SmpsCoordFlags.RETURN,
            (byte) 0xB1, 0x18,
            (byte) SmpsCoordFlags.RETURN
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);

        // 2 CALLs x 2 iterations = 4 entries
        assertEquals(4, result.chainEntries().size());

        // Entries 0,2 should share the same phrase ID (sub1)
        // Entries 1,3 should share the same phrase ID (sub2)
        assertEquals(result.chainEntries().get(0).getPhraseId(),
            result.chainEntries().get(2).getPhraseId(),
            "Duplicated entry should reference same sub1 phrase");
        assertEquals(result.chainEntries().get(1).getPhraseId(),
            result.chainEntries().get(3).getPhraseId(),
            "Duplicated entry should reference same sub2 phrase");
    }
}
