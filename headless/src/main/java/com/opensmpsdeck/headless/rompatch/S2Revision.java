package com.opensmpsdeck.headless.rompatch;

import com.openggf.data.compression.SaxmanDecompressor;
import com.opensmpsdeck.headless.rom.RomRevision;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** World REV01 addresses from the Sonic 2 disassembly and its assembled image. */
final class S2Revision {
    static final RomRevision ROM = RomRevision.S2_REV01;
    static final int OUTPUT_SIZE = 0x200000;
    static final int HEADER_END = 0x200, CHECKSUM = 0x18E, ROM_END = 0x1A4; // s2.asm:168–174,380–393
    static final int FIRST_SLOT = 0x81, LAST_SLOT = 0x9F, TITLE_SLOT = 0x99, EXTRA_LIFE = 0x98;
    static final int SONG_ROM = 0x100000, SONG_BANK = 0x20, STUB_ROM = 0x108000;
    static final int WINDOW = 0x8000, Z80_RAM = 0xA00000;

    // s2.asm:91428–91446,91469–91472,91563–91572.
    static final int DRIVER_ROM = 0xEC0E8, DRIVER_SIZE = 0xF64;
    static final int BOOT_HOOK = 0xEC028, BOOT_RETURN = 0xEC036;
    static final String PAL_PROBE = "0839000000c0000556f900a00007";
    static final int DEMO = 0x3C54; // s2.asm:4494–4496
    static final int TITLE_INTRO = 0x12EE2, TITLE_NO_INTRO = 0x135E2; // s2.asm:26400,27158

    // s2.sounddriver.asm:1748–1785,2837–2847,3823–3855.
    static final int PLAYLIST = 0x11F5, SELECT_BANK = 0x07A6, READ_POINTER = 0x07C5;
    static final int BANK_SWITCH = 0x0C66, DECODE_MARKER = 0x0C6C;
    static final int STOCK_BANK_1 = 0x1E, STOCK_BANK_2 = 0x1F;
    static final int PAL_FLAG = 0x40, UNCOMPRESSED_FLAG = 0x20, INDEX_MASK = 0x1F;

    // s2.sounddriver.asm:177–181,1802,1281,3724–3735.
    static final int DRIVER_END = 0x1308, PSG_RELOCATED = 0x133C, BUFFER_START = 0x1380;
    static final int PSG_IMMEDIATE = 0x04D9, PSG_TABLE = 0x1029, PSG_COUNT = 13;

    // s2.sounddriver.asm:493,515–525,805–814,3878–3926; s2.asm:91584–91601.
    static final int DAC_BANK_ROM = 0xE8000, DAC_POINTERS = 0x1233, DAC_PLAYLIST = 0x124F;
    static final int DAC_DELTAS = 0x01B3, DAC_BASE_CYCLES = 295;
    static final int DAC_FIRST_NOTE = 0x81, DAC_LAST_NOTE = 0x91;

    record Site(int offset, String expected) {
        void verify(byte[] bytes, String space) {
            byte[] pattern = hex(expected);
            if (offset + pattern.length > bytes.length ||
                    !Arrays.equals(bytes, offset, offset + pattern.length, pattern, 0, pattern.length))
                throw new IllegalArgumentException("Unexpected Sonic 2 pattern at %s 0x%06X".formatted(space, offset));
        }
    }

    static final List<Site> ROM_SITES = List.of(
            new Site(CHECKSUM, "d951"), new Site(ROM_END, "000fffff"),
            new Site(BOOT_HOOK - 8, "051366fc4eba0024" + PAL_PROBE + "34823682"),
            new Site(0xEC04A, "4dfa009c3e3c0f64"),
            new Site(DEMO, "670000d8"), new Site(TITLE_INTRO, "7099"), new Site(TITLE_NO_INTRO, "7099"));

    static final List<Site> DRIVER_SITES = List.of(
            new Site(SELECT_BANK, "47e68032961b"),
            new Site(READ_POINTER, "cd630ce15e2356f1"),
            new Site(BANK_SWITCH - 3, "3a961bb72010af1e01210060777373737377777777c9"
                    + "af1e01210060737373737377777777c9"),
            new Site(PSG_IMMEDIATE - 1, "212910"));

    private S2Revision() {}

    static byte[] decompress(byte[] rom) {
        return new SaxmanDecompressor().decompressRaw(
                Arrays.copyOfRange(rom, DRIVER_ROM, DRIVER_ROM + DRIVER_SIZE), DRIVER_SIZE);
    }

    static void verifyRetailPlaylist(byte[] driver) {
        int end = PLAYLIST + LAST_SLOT - FIRST_SLOT + 1;
        // Fingerprint the expected assembled entries without embedding the retail table.
        String expected = "3c09ed56a039b11d62f3abf30621ae6836001c53f03e143ca2d9bab4a394f2e0";
        if (driver.length != DRIVER_END
                || !expected.equalsIgnoreCase(RomPatch.hash("SHA-256", Arrays.copyOfRange(driver, PLAYLIST, end))))
            throw new IllegalArgumentException("Unexpected Sonic 2 zMasterPlaylist at Z80 RAM 0x11F5");
    }

    static void verify(byte[] rom, byte[] driver) {
        if (rom.length != ROM.size() || driver.length != DRIVER_END)
            throw new IllegalArgumentException("Unexpected Sonic 2 ROM or decompressed driver size");
        ROM_SITES.forEach(site -> site.verify(rom, "ROM"));
        DRIVER_SITES.forEach(site -> site.verify(driver, "Z80 RAM"));
        // Retail indexes occupy 0..29 in bank 0x1F and index 0 in bank 0x1E.
        // Index 31 (with or without bit 6) is reserved for our marker.
        int flags = 0;
        for (int i = PLAYLIST; i <= PLAYLIST + LAST_SLOT - FIRST_SLOT; i++) {
            int value = u8(driver, i);
            int index = value & INDEX_MASK;
            if ((value & 0x80) != 0 && index < 30) flags |= 1 << index;
            else if (i != PLAYLIST + 0x9C - FIRST_SLOT || value != 0)
                throw new IllegalArgumentException("Unexpected Sonic 2 playlist entry at 0x%04X".formatted(i));
        }
        if (flags != (1 << 30) - 1) throw new IllegalArgumentException("Incomplete Sonic 2 retail playlist");
    }

    static int u8(byte[] bytes, int at) { return Byte.toUnsignedInt(bytes[at]); }

    static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
}
