package com.opensmpsdeck.headless.rom;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class BpsPatchTest {
    // Hand-assembled from the BPS spec. One metadata byte, SourceRead(2), TargetRead('Z'),
    // SourceCopy(2, +4), SourceCopy(2, -4), TargetCopy(5, +5) with overlap.
    private static final byte[] ALL_ACTIONS = HexFormat.of().parseHex(
            "42505331868c816d84815a86888689938a69fe76bba265e56893ae8d7b");

    @Test void appliesSpecVectorWithAllActions() {
        assertArrayEquals(ascii("ABZEFCDCDCDC"), BpsPatch.apply(ALL_ACTIONS, ascii("ABCDEF")));
    }

    @Test void matchesSpecVectorWithMultibyteNumbers() {
        // Size 257 encodes as 01 81 and SourceRead(257) as 00 87.
        byte[] patch = HexFormat.of().parseHex("4250533101810181800087cea0bfb7cea0bfb71ca88fdd");
        byte[] source = new byte[257];
        assertArrayEquals(patch, BpsPatch.encode(source, source).bytes());
        assertArrayEquals(source, BpsPatch.apply(patch, source));
    }

    @Test void roundTripsExpandedRomWithRelocatedSourceAndRepeatedNewData() {
        byte[] source = new byte[4 * 1024 * 1024];
        new Random(1989).nextBytes(source);
        byte[] target = Arrays.copyOf(source, source.length + 32768);
        target[0] ^= 1;
        target[source.length - 1] ^= 1;
        System.arraycopy(source, 12345, target, source.length, 8192);
        // A repeated byte absent from this source as a run will use overlapping TargetCopy.
        Arrays.fill(target, source.length + 8192, target.length, (byte) 42);
        target[target.length - 1] ^= 1;
        var encoded = BpsPatch.encode(source, target);
        assertArrayEquals(target, BpsPatch.apply(encoded.bytes(), source));
        assertArrayEquals(encoded.bytes(), BpsPatch.encode(source, target).bytes());
        assertTrue(encoded.stats().sourceCopyBytes() >= 8192);
        assertTrue(encoded.stats().targetCopyBytes() > 24000);
        assertTrue(encoded.stats().targetReadBytes() <= 12);
    }

    @Test void supportsEmptyAndTruncatedTargets() {
        for (byte[] target : new byte[][]{new byte[0], ascii("A"), ascii("something new")}) {
            for (byte[] source : new byte[][]{new byte[0], ascii("AB")}) {
                assertArrayEquals(target, BpsPatch.apply(BpsPatch.encode(source, target).bytes(), source));
            }
        }
    }

    @Test void refusesEachCrcMismatch() {
        assertRejected("source CRC32", ALL_ACTIONS, ascii("BBCDEF"));
        byte[] damaged = ALL_ACTIONS.clone();
        damaged[10] ^= 1;
        assertRejected("patch CRC32", damaged, ascii("ABCDEF"));
        updatePatchCrc(damaged);
        assertRejected("target CRC32", damaged, ascii("ABCDEF"));
    }

    @Test void refusesInvalidActionsEvenWithValidPatchCrc() {
        byte[] damaged = ALL_ACTIONS.clone();
        damaged[16] = (byte) 0x8e; // TargetCopy begins at output offset 7, so index 7 is unavailable.
        updatePatchCrc(damaged);
        assertRejected("TargetCopy", damaged, ascii("ABCDEF"));
        damaged = ALL_ACTIONS.clone();
        damaged[8] = (byte) 0xff; // Action length exceeds the target size.
        updatePatchCrc(damaged);
        assertRejected("target size", damaged, ascii("ABCDEF"));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void assertRejected(String message, byte[] patch, byte[] source) {
        var exception = assertThrows(IllegalArgumentException.class, () -> BpsPatch.apply(patch, source));
        assertTrue(exception.getMessage().contains(message), exception::getMessage);
    }

    private static void updatePatchCrc(byte[] bytes) {
        var crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length - 4, (int) crc.getValue());
    }
}
