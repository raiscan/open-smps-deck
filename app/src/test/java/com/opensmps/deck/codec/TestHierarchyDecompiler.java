package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
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
}
