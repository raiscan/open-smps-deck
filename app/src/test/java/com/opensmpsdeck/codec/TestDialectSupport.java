package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.Phrase;
import com.opensmps.smps.SmpsCoordFlags;
import com.opensmps.smps.SmpsCoordFlags.Dialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-driver coordination flag dialects: S1 and S3K assign different sizes and
 * meanings to several flags (SMPSPlay DefCFlag definitions). Mis-sized flags
 * derail the entire byte stream during import.
 */
class TestDialectSupport {

    @Test
    void s1FlagDeviations() {
        // ED: ClearPush, no parameter (S2: 1 parameter)
        assertEquals(0, SmpsCoordFlags.getParamCount(0xED, Dialect.S1));
        assertEquals(1, SmpsCoordFlags.getParamCount(0xED, Dialect.S2));
        // EE ends the track in S1
        assertTrue(SmpsCoordFlags.isTrackEnd(0xEE, Dialect.S1));
        assertFalse(SmpsCoordFlags.isTrackEnd(0xEE, Dialect.S2));
    }

    @Test
    void s3kFlagDeviations() {
        // Size changes vs S2
        assertEquals(1, SmpsCoordFlags.getParamCount(0xE4, Dialect.S3K)); // VOL_ABS
        assertEquals(2, SmpsCoordFlags.getParamCount(0xE5, Dialect.S3K)); // VOL_CC_FMP2
        assertEquals(0, SmpsCoordFlags.getParamCount(0xE9, Dialect.S3K)); // SPINDASH_REV
        assertEquals(3, SmpsCoordFlags.getParamCount(0xEB, Dialect.S3K)); // LOOP_EXIT
        assertEquals(2, SmpsCoordFlags.getParamCount(0xEE, Dialect.S3K)); // FM_COMMAND
        assertEquals(2, SmpsCoordFlags.getParamCount(0xF1, Dialect.S3K)); // MOD_ENV
        // Meaning changes
        assertTrue(SmpsCoordFlags.isTrackEnd(0xE3, Dialect.S3K), "S3K E3 = TRK_END");
        assertTrue(SmpsCoordFlags.isReturn(0xF9, Dialect.S3K), "S3K F9 = RETURN");
        assertFalse(SmpsCoordFlags.isReturn(0xE3, Dialect.S3K));
        assertEquals(0xFB, SmpsCoordFlags.transposeAddFlag(Dialect.S3K));
        assertEquals(0xE9, SmpsCoordFlags.transposeAddFlag(Dialect.S2));
        // FF meta sub-command sizes
        assertEquals(1, SmpsCoordFlags.getMetaParamCount(0x00)); // TEMPO
        assertEquals(4, SmpsCoordFlags.getMetaParamCount(0x05)); // SSG_EG
        assertEquals(0, SmpsCoordFlags.getMetaParamCount(0x07)); // SDREV_RESET
    }

    @Test
    void s3kSubroutinesEndWithF9() {
        // main: CALL sub, STOP; sub: notes, F9 RETURN
        byte[] track = {
            (byte) 0xF8, 0x06, 0x00,    // CALL -> 6
            (byte) 0xA1, 0x08,
            (byte) 0xF2,                // STOP
            (byte) 0xC0, 0x0C,          // sub body
            (byte) 0xF9,                // S3K RETURN
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM, Dialect.S3K);
        boolean subFound = result.phrases().stream()
                .anyMatch(p -> p.getDataDirect().length == 2
                        && (p.getDataDirect()[0] & 0xFF) == 0xC0);
        assertTrue(subFound, "S3K subroutine should end at F9, not run past it");
    }

    @Test
    void midStreamGotoIsFollowedAndBackEdgeBecomesLoop() {
        // intro, JUMP -> 8 (forward goto); at 8: notes, JUMP -> 8 (back-edge loop)
        byte[] track = {
            (byte) 0xA1, 0x08,          // 0: intro note
            (byte) 0xF6, 0x08, 0x00,    // 2: goto 8
            0x00, 0x00, 0x00,           // 5: unreachable padding
            (byte) 0xA5, 0x08,          // 8: body note
            (byte) 0xA7, 0x08,
            (byte) 0xF6, 0x08, 0x00,    // 12: loop back to 8
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM, Dialect.S2);
        assertTrue(result.hasLoopPoint(), "Back-edge jump should produce a loop point");
        assertFalse(result.chainEntries().isEmpty());

        // The loop entry's phrase must start at the body (note A5), not the intro
        int loopIdx = result.loopEntryIndex();
        assertTrue(loopIdx >= 0 && loopIdx < result.chainEntries().size());
        Phrase loopPhrase = findPhrase(result.phrases(),
                result.chainEntries().get(loopIdx).getPhraseId());
        assertEquals((byte) 0xA5, loopPhrase.getDataDirect()[0],
                "Loop must target the body phrase, not the intro");
    }

    @Test
    void nestedCallsAreFlattenedIntoSubBodies() {
        // main: CALL subA, STOP; subA: note, CALL subB, RETURN; subB: note, RETURN
        byte[] track = {
            (byte) 0xF8, 0x06, 0x00,    // 0: CALL -> 6 (subA)
            (byte) 0xA1, 0x08,          // 3
            (byte) 0xF2,                // 5: STOP
            (byte) 0xC0, 0x0C,          // 6: subA note
            (byte) 0xF8, 0x0E, 0x00,    // 8: CALL -> 14 (subB)
            (byte) 0xC2, 0x0C,          // 11: subA note 2
            (byte) 0xE3,                // 13: RETURN
            (byte) 0xD0, 0x06,          // 14: subB note
            (byte) 0xE3,                // 16: RETURN
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM, Dialect.S2);
        // subA's phrase must contain subB's note inline, with no raw F8 pointer
        boolean flattened = result.phrases().stream().anyMatch(p -> {
            byte[] d = p.getDataDirect();
            boolean hasD0 = false, hasF8 = false, startsC0 = d.length > 0 && (d[0] & 0xFF) == 0xC0;
            for (byte x : d) {
                if ((x & 0xFF) == 0xD0) hasD0 = true;
                if ((x & 0xFF) == 0xF8) hasF8 = true;
            }
            return startsC0 && hasD0 && !hasF8;
        });
        assertTrue(flattened, "Nested CALL should be inlined into the subroutine body");
    }

    @Test
    void loopsInsideSubBodiesAreUnrolled() {
        // main: CALL sub, STOP; sub: [note A1] x3 via F7, then RETURN
        byte[] track = {
            (byte) 0xF8, 0x06, 0x00,    // 0: CALL -> 6
            (byte) 0xA5, 0x08,          // 3
            (byte) 0xF2,                // 5: STOP
            (byte) 0xA1, 0x04,          // 6: loop body
            (byte) 0xF7, 0x00, 0x03, 0x06, 0x00, // 8: LOOP x3 -> 6
            (byte) 0xE3,                // 13: RETURN
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM, Dialect.S2);
        boolean unrolled = result.phrases().stream().anyMatch(p -> {
            byte[] d = p.getDataDirect();
            int count = 0;
            boolean hasF7 = false;
            for (byte x : d) {
                if ((x & 0xFF) == 0xA1) count++;
                if ((x & 0xFF) == 0xF7) hasF7 = true;
            }
            return count == 3 && !hasF7;
        });
        assertTrue(unrolled, "F7 loop inside a subroutine body should be unrolled (3 iterations)");
    }

    private static Phrase findPhrase(List<Phrase> phrases, int id) {
        for (Phrase p : phrases) {
            if (p.getId() == id) return p;
        }
        return null;
    }
}
