package com.opensmpsdeck.headless.rompatch;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import static com.opensmpsdeck.headless.rompatch.S3kRevision.*;

final class PatchCode {
    // btst #0,($A11100).l; bne.s back to btst. The caller already requests the bus.
    private static final byte[] WAIT_FOR_BUS = hex("0839000000a1110066f6");
    private static final byte[] RESET = hex("33fc000000a11200");
    private static final byte[] NOP = hex("4e71");

    private PatchCode() {}

    static void verify(byte[] rom, boolean title, boolean noDemo) {
        expect(rom, DRIVER_HOOK);
        if (title) TITLE_REQUESTS.forEach(site -> expect(rom, site));
        if (noDemo) DEMO_TIMEOUTS.forEach(site -> expect(rom, site));
        CHECKSUMS.forEach(site -> expect(rom, site));
        for (var fragment : FRAGMENTS) {
            var boundary = new byte[16];
            System.arraycopy(rom, fragment.start(), boundary, 0, 8);
            System.arraycopy(rom, fragment.end() - 8, boundary, 8, 8);
            if (!RomPatch.hash("SHA-256", boundary).equals(fragment.boundarySha256()))
                throw new IllegalArgumentException("Unexpected fragment boundary at ROM 0x%06X".formatted(fragment.start()));
        }
    }

    static void install(RomEdits edits, int stub, int slot, boolean title, boolean noDemo) {
        edits.write(DRIVER_HOOK.offset(), ByteBuffer.allocate(8).putShort((short) 0x4EB9)
                .putInt(stub).put(NOP).array(), "driver hook");
        if (title) for (var site : TITLE_REQUESTS) edits.write(site.offset() + 1, new byte[]{(byte) slot}, "title request");
        if (noDemo) for (var site : DEMO_TIMEOUTS) edits.write(site.offset() + 4, hex("4e714e71"), "demo timeout branch");
    }

    static void checksums(RomEdits edits) {
        byte[] rom = edits.bytes();
        for (var site : CHECKSUMS) {
            int start = site.offset() - 0x18E;
            int sum = 0;
            for (int at = start + 0x200; at < start + CARTRIDGE_SIZE; at += 2)
                sum += ((rom[at] & 255) << 8) | (rom[at + 1] & 255);
            byte[] checksum = ByteBuffer.allocate(2).putShort((short) sum).array();
            if (rom[site.offset()] != checksum[0] || rom[site.offset() + 1] != checksum[1])
                edits.write(site.offset(), checksum, "cartridge header checksum");
        }
    }

    static byte[] stub(Map<Integer, Byte> ram) {
        var code = new ByteArrayOutputStream();
        code.writeBytes(WAIT_FOR_BUS);
        ram.forEach((at, value) -> code.writeBytes(ByteBuffer.allocate(8)
                .putShort((short) 0x13FC) // move.b #value,(Z80_RAM+at).l
                .putShort((short) Byte.toUnsignedInt(value)).putInt(0xA00000 + at).array()));
        code.writeBytes(RESET);
        code.writeBytes(hex("4e75"));
        return code.toByteArray();
    }

    static void expect(byte[] rom, Site site) {
        byte[] expected = hex(site.expectedHex());
        int offset = site.offset();
        if (offset + expected.length > rom.length ||
                !Arrays.equals(rom, offset, offset + expected.length, expected, 0, expected.length))
            throw new IllegalArgumentException("Unexpected instruction or header pattern at ROM 0x%06X".formatted(offset));
    }

    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
}
