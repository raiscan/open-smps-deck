package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.StockRef;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetailBankPatchTest {
    private static final byte[] BASE = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    @Test void encodesOnlyChangedAndExtendedBytesInCanonicalRuns() {
        byte[] target = {1, 30, 31, 4, 5, 60, 7, 8, 9, 10, 11, 12, 0, 70};
        byte[] delta = RetailBankPatch.encode(BASE, target);
        assertArrayEquals(new byte[]{0, 14, 0, 1, 0, 2, 30, 31, 0, 5, 0, 1, 60, 0, 12, 0, 2, 0, 70}, delta);
        assertArrayEquals(delta, RetailBankPatch.encode(BASE.clone(), target.clone()));
        assertArrayEquals(target, RetailBankPatch.apply(delta, BASE));
    }

    @Test void identityAndTruncationNeedNoRuns() {
        assertArrayEquals(new byte[]{0, 12}, RetailBankPatch.encode(BASE, BASE));
        byte[] shorter = Arrays.copyOf(BASE, 10);
        byte[] delta = RetailBankPatch.encode(BASE, shorter);
        assertArrayEquals(new byte[]{0, 10}, delta);
        assertArrayEquals(shorter, RetailBankPatch.apply(delta, BASE));
    }

    @Test void rejectsMalformedRunsAndUnchangedLiterals() {
        assertFailure("target length", () -> RetailBankPatch.targetLength(new byte[]{0, 9}));
        assertFailure("truncated", () -> RetailBankPatch.targetLength(new byte[]{0, 12, 0}));
        assertFailure("truncated", () -> RetailBankPatch.targetLength(new byte[]{0, 12, 0, 11, 0, 1}));
        assertFailure("range", () -> RetailBankPatch.targetLength(new byte[]{0, 12, 0, 12, 0, 1, 0}));
        assertFailure("range", () -> RetailBankPatch.targetLength(new byte[]{0, 12, 0, 1, 0, 0}));
        assertFailure("separated", () -> RetailBankPatch.targetLength(
                new byte[]{0, 12, 0, 0, 0, 1, 40, 0, 1, 0, 1, 50}));
        assertFailure("separated", () -> RetailBankPatch.targetLength(
                new byte[]{0, 12, 0, 0, 0, 2, 40, 41, 0, 1, 0, 1, 50}));
        assertFailure("unchanged", () -> RetailBankPatch.apply(new byte[]{0, 12, 0, 1, 0, 1, 2}, BASE));
        assertFailure("extension", () -> RetailBankPatch.apply(new byte[]{0, 14, 0, 13, 0, 1, 0}, BASE));
    }

    @Test void baseResolutionChecksGameSongAndHashAcrossSources() {
        var retail = new PackageManifest.Retail("s3k", 13, StockTable.hash(BASE));
        byte[] wrong = BASE.clone();
        wrong[0] ^= 1;
        var bad = source(wrong);
        var good = source(BASE);
        assertFailure("needs ROM", () -> RetailBankPatch.resolveBase(retail, List.of()));
        assertFailure(StockTable.hash(wrong), () -> RetailBankPatch.resolveBase(retail, List.of(bad)));
        assertArrayEquals(BASE, RetailBankPatch.resolveBase(retail, List.of(bad, good)));
        assertFailure("missing", () -> RetailBankPatch.resolveBase(
                new PackageManifest.Retail("s3k", 14, retail.bankSha256()), List.of(good)));
    }

    @Test void handlesMaximumBankSizeWithAlternatingEdits() {
        byte[] base = new byte[PortableArchive.MAX_TRACK];
        byte[] target = base.clone();
        for (int i = 0; i < target.length; i += 2) target[i] = 1;
        byte[] delta = RetailBankPatch.encode(base, target);
        assertEquals(target.length, Short.toUnsignedInt(ByteBuffer.wrap(delta).getShort()));
        assertTrue(delta.length <= PortableArchive.entryLimit("track.delta"));
        assertArrayEquals(target, RetailBankPatch.apply(delta, base));
    }

    private static StockAssets source(byte[] bytes) {
        return new StockAssets() {
            @Override public byte[] payload(StockRef ref) { return null; }
            @Override public byte[] retailBank(String game, int songId) {
                return game.equals("s3k") && songId == 13 ? bytes : null;
            }
        };
    }

    private static void assertFailure(String message, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(IllegalArgumentException.class, action);
        assertTrue(error.getMessage().contains(message), error::getMessage);
    }
}
