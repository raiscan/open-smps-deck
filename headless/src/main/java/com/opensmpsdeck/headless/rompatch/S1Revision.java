package com.opensmpsdeck.headless.rompatch;

import com.opensmpsdeck.headless.rom.RomRevision;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Sites from the byte-identical REV01 sonic.lst, cited by source file and line. */
final class S1Revision {
    static final RomRevision ROM = RomRevision.S1_REV01;
    static final int OUTPUT_SIZE = 0x100000;
    static final int CHECKSUM = 0x18E, ROM_END = 0x1A4, HEADER_END = 0x200; // sonic.asm:174–196

    // s1.sounddriver.asm:31,35,41–64,99–118.
    static final int MUSIC_MASTER = 0x71998, MUSIC_TABLE = 0x71A9C;
    static final int PSG_MASTER = 0x719A4, PSG_TABLE = 0x719A8;
    static final int[] PSG_POINTERS = {0x719CC, 0x719E3, 0x719EA, 0x719FB, 0x71A15, 0x71A06,
            0x71A3F, 0x71A5B, 0x71A83};
    static final int[] PSG_LENGTHS = {23, 7, 17, 11, 42, 15, 28, 40, 17};
    static final int FIRST_SLOT = 0x81, LAST_SLOT = 0x93, TITLE_SLOT = 0x8A;

    // sonic.asm:2019,2080–2086,2159–2163.
    static final int TITLE = 0x30DC, TITLE_FAILSAFE = 0x318A, DEMO_TIMEOUT = 0x3212;

    // sonic.asm:1225–1239; s1.sounddriver.asm:328,339–344,2632.
    static final int DRIVER_ROM = 0x72E7C, DRIVER_END = 0x745DC, DRIVER_HOOK = 0x136C;
    static final int TIMPANI_WRITE = 0x71CB4, TIMPANI_RATES = 0x71CC4;
    // sound/z80.asm:13–15,68–69,81–105,216–229.
    static final int DRIVER_RAM_END = 0x1BC6, Z80_RESERVED = 0x1FFC, Z80_RAM = 0xA00000;
    static final int DAC_DELTAS = 0x22, DAC_TABLE_OP = 0x44, DAC_TABLE = 0xD6, DAC_ENTRY_SIZE = 8;
    static final int STOCK_DAC_ENTRIES = 3, CUSTOM_DAC_ENTRIES = 3;
    static final int NEW_DAC_TABLE = DRIVER_RAM_END;
    static final int DAC_PAYLOAD = NEW_DAC_TABLE + (STOCK_DAC_ENTRIES + CUSTOM_DAC_ENTRIES) * DAC_ENTRY_SIZE;
    // sound/z80.asm:223–225; the REV01 listing supplies the uncompressed offsets.
    static final List<SampleRange> STOCK_DAC = List.of(
            new SampleRange("kick", 0xEE, 0x440), new SampleRange("snare", 0x440, 0xBB0),
            new SampleRange("timpani", 0xBB0, DRIVER_RAM_END));

    record SampleRange(String name, int start, int end) {}

    record Site(int offset, String expectedHex) {
        void verify(byte[] bytes, String space) {
            byte[] expected = HexFormat.of().parseHex(expectedHex);
            if (offset + expected.length > bytes.length ||
                    !Arrays.equals(bytes, offset, offset + expected.length, expected, 0, expected.length)) {
                throw new IllegalArgumentException("Unexpected Sonic 1 pattern at %s 0x%06X".formatted(space, offset));
            }
        }
    }

    static final List<Site> ROM_SITES = List.of(
            new Site(CHECKSUM, "afc7"), new Site(ROM_END, "0007ffff"),
            // sonic.asm:380–393.
            new Site(0x31C, "207c00000200227c000001a420117200d258b08864fa227c0000018eb2516600007c"),
            new Site(MUSIC_MASTER, "00071a9c"),
            new Site(MUSIC_TABLE, "000745dc00074d4400075194000755f200075a6000075eac000763fc000765e2000766d0"
                    + "000769fc00076ba800076e420007705e0007727a000773d0000775200007767e0007894c00078a76"),
            new Site(PSG_MASTER, "000719a8"),
            new Site(PSG_TABLE, "000719cc000719e3000719ea000719fb00071a1500071a0600071a3f00071a5b00071a83"),
            new Site(TITLE, "103c008a"),
            new Site(TITLE_FAILSAFE, "0c401c00650811fc0000f6004e75"),
            new Site(DEMO_TIMEOUT, "4a78f6146700018c"),
            new Site(0x134A, "4e7133fc010000a1110033fc010000a1120041f900072e7c43f900a000006100052a"
                    + "33fc000000a112004e714e714e714e7133fc010000a1120033fc000000a111004e75"),
            new Site(TIMPANI_WRITE, "13c000a000ea"), new Site(TIMPANI_RATES, "12151c1dffff"));

    static final List<Site> DRIVER_SITES = List.of(
            new Site(DAC_TABLE_OP, "fd21d600"),
            new Site(DAC_TABLE, "ee005203170000004004700701000000b00b16101b000000"));

    private S1Revision() {}

    static void verifyRom(byte[] rom) {
        if (rom.length != ROM.size()) throw new IllegalArgumentException("Expected a 512 KiB Sonic 1 image");
        ROM_SITES.forEach(site -> site.verify(rom, "ROM"));
    }

    static void verifyDriver(byte[] driver) {
        if (driver.length != DRIVER_RAM_END)
            throw new IllegalArgumentException("Unexpected Sonic 1 decompressed DAC driver length " + driver.length);
        DRIVER_SITES.forEach(site -> site.verify(driver, "Z80 RAM"));
    }

    static int u8(byte[] bytes, int at) { return Byte.toUnsignedInt(bytes[at]); }

    static byte[] be32(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
}
