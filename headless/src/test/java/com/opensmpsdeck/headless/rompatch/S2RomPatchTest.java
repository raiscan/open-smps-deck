package com.opensmpsdeck.headless.rompatch;

import com.opensmpsdeck.headless.rom.BpsPatch;
import com.opensmpsdeck.project.DacSample;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S2RomPatchTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void refusesAnUnverifiedImage() {
        var song = song(0);
        assertThrows(IllegalArgumentException.class, () ->
                S2RomPatch.patch(new byte[0x100000], song, options(0x99)));
    }

    @Test
    void relocatesAbsoluteControlFlowAndRejectsAnOperandAsTarget() {
        // One DAC track. Rest, loop twice, call a timed rest, stop.
        byte[] bank = HEX.parseHex("0000010001ff0a000000807ff700020a00f81500f28001e3");
        var song = new S2Song(bank, 0, 0, Map.of(), Map.of(), List.of(6, 15, 18));
        var layout = S2Bank.read(song);
        layout.relocate(bank);
        assertEquals(0x800A, SongLayout.word(bank, 6));
        assertEquals(0x800A, SongLayout.word(bank, 15));
        assertEquals(0x8015, SongLayout.word(bank, 18));
        bank[15] = 11;
        assertThrows(IllegalArgumentException.class, () ->
                S2Bank.read(new S2Song(bank, 0x8000, 0, Map.of(), Map.of(), List.of())));
    }

    @Test
    void acceptsAFullWindowAndRoundTripsTheExpansion() {
        byte[] rom = fixture(S2Revision.ROM.size(), S2Revision.ROM_SITES);
        byte[] driver = driverFixture();
        byte[] full = Arrays.copyOf(song(0x8000).bank(), 0x8000);
        SongLayout.word(full, 6, 0xFFFD);
        System.arraycopy(HEX.parseHex("8001f2"), 0, full, full.length - 3, 3);
        var result = S2RomPatch.patchVerified(rom, driver,
                new S2Song(full, 0x8000, 0, Map.of(), Map.of(), List.of(6)), options(0x99));
        assertEquals(0x200000, result.bytes().length);
        assertArrayEquals(full, Arrays.copyOfRange(result.bytes(), 0x100000, 0x108000));
        assertEquals(0x1FFFFF, java.nio.ByteBuffer.wrap(result.bytes()).getInt(0x1A4));
        var encoded = BpsPatch.encode(rom, result.bytes());
        assertArrayEquals(result.bytes(), BpsPatch.apply(encoded.bytes(), rom));
        byte[] tooLarge = Arrays.copyOf(full, full.length + 1);
        var oversized = assertThrows(IllegalArgumentException.class, () -> S2RomPatch.patchVerified(rom, driver,
                new S2Song(tooLarge, 0x8000, 0, Map.of(), Map.of(), List.of()), options(0x99)));
        assertTrue(oversized.getMessage().contains("32768"));
        driver[0x07A6] ^= 1;
        assertThrows(IllegalArgumentException.class, () ->
                S2RomPatch.patchVerified(rom, driver, song(0), options(0x99)));
    }

    @Test
    void fitsFortyByteEnvelopeAndMapsOverflowWithoutTouchingStockPointers() {
        byte[] driver = driverFixture();
        for (int i = 0; i < 13; i++) {
            SongLayout.word(driver, 0x1029 + 2 * i, 0x1043 + 2 * i);
            driver[0x1043 + 2 * i] = (byte) i;
            driver[0x1044 + 2 * i] = (byte) 0x80;
        }
        byte[] fitted = new byte[40], overflow = new byte[41];
        Arrays.fill(fitted, (byte) 7);
        Arrays.fill(overflow, (byte) 12);
        fitted[0] = 5;
        fitted[39] = overflow[40] = (byte) 0x80;
        byte[] bank = HEX.parseHex("01020304");
        var plan = S2Psg.map(driver, bank, java.util.Set.of(0, 1, 2, 3),
                Map.of(1, fitted, 2, overflow, 3, fitted, 4, HEX.parseHex("0580")));
        assertArrayEquals(new byte[]{14, 13, 14, 6}, bank);
        assertEquals(68, plan.relocatedTable().length);
        assertArrayEquals(Arrays.copyOfRange(driver, 0x1029, 0x1043),
                Arrays.copyOf(plan.relocatedTable(), 26));
        assertEquals(0x1358, SongLayout.word(plan.relocatedTable(), 26));
        assertFalse(plan.bindings().get(1).exact());
        assertEquals(0., plan.bindings().get(1).rmsAttenuationError());
        assertTrue(plan.bindings().get(3).exact());
    }

    @Test
    void dacMatchesSampleAndRateFromDecompressedTables() {
        byte[] rom = new byte[0x100000];
        byte[] driver = new byte[0x1308];
        driver[0x01B4] = 1; // DPCM nibble 1 increments by one.
        for (int i = 0; i < 7; i++) {
            SongLayout.word(driver, 0x1233 + i * 4, 0x8100 + i);
            SongLayout.word(driver, 0x1235 + i * 4, 1);
            rom[0xE8100 + i] = 0x11;
        }
        for (int i = 0; i < 17; i++) {
            driver[0x124F + i * 2] = (byte) (0x81 + i % 7);
            driver[0x1250 + i * 2] = (byte) (1 + i);
        }
        byte[] bank = {(byte) 0x81, (byte) 0x81};
        var sample = DacSample.of("sample", "Sample", 17, HEX.parseHex("8182"));
        var bindings = S2Dac.map(rom, driver, bank, java.util.Set.of(0, 1), Map.of(0x81, sample));
        assertArrayEquals(HEX.parseHex("9191"), bank);
        assertEquals("clap", bindings.getFirst().stockSample());
        assertEquals(17, bindings.getFirst().rate());
        assertTrue(bindings.getFirst().exact());
    }

    @Test
    void extraLifePadsTheDacTrackThenRestoresAndFiniteTracksCanLoop() {
        byte[] source = HEX.parseHex("0000010101ff100000001300000000008001f28003f2");
        var layout = S2Bank.read(new S2Song(source, 0, 0, Map.of(), Map.of(), List.of()));
        byte[] bank = source.clone();
        layout.relocate(bank);
        byte[] extraLife = layout.finish(bank, false, true);
        assertEquals(0x8015, SongLayout.word(extraLife, 10));
        assertArrayEquals(HEX.parseHex("80018002e48003f2"), Arrays.copyOfRange(extraLife, 16, extraLife.length));
        assertThrows(IllegalArgumentException.class, () -> layout.finish(bank, true, false));
        source[17] = 3;
        var aligned = S2Bank.read(new S2Song(source, 0, 0, Map.of(), Map.of(), List.of()));
        aligned.relocate(source);
        byte[] loop = aligned.finish(source, true, false);
        assertArrayEquals(HEX.parseHex("8003f610808003f61580"), Arrays.copyOfRange(loop, 16, loop.length));
    }

    @Test
    void retailInstallUsesOnlyTheChosenPlaylistSlot() throws Exception {
        String path = System.getenv("OPENSMPSDECK_S2_ROM");
        assumeTrue(path != null && !path.isBlank(), "Set OPENSMPSDECK_S2_ROM to verified World REV01");
        byte[] clean = Files.readAllBytes(Path.of(path));
        byte[] driver = S2Revision.decompress(clean);
        for (int slot = 0x81; slot <= 0x9F; slot++) {
            var result = S2RomPatch.patch(clean, song(0), options(slot));
            byte[] output = result.bytes();
            byte[] installed = simulateInstall(driver, output);
            int marker = 0x3F | (driver[0x11F5 + slot - 0x81] & 0x40);
            for (int id = 0x81; id <= 0x9F; id++) {
                int index = 0x11F5 + id - 0x81;
                int before = driver[index] & 255;
                int after = installed[index] & 255;
                assertEquals(id == slot ? marker : before, after);
                assertEquals(before & 0x40, after & 0x40);
                int selector = runZ80(installed, 0x0C6C, after, 0).a();
                int bank = runZ80(installed, 0x0C63, 0, selector).bank();
                if (id == slot) {
                    assertEquals(0x20, bank);
                    assertEquals(0x132A, SongLayout.word(installed, 0x07C6));
                    assertArrayEquals(HEX.parseHex("3a961b3dc2630ccd630ce1e1110080c3cc07"),
                            Arrays.copyOfRange(installed, 0x132A, 0x133C));
                    int header = SongLayout.word(installed, 0x1337);
                    assertEquals(0x8000, header);
                    assertEquals(0x800A, SongLayout.word(output, bank * 0x8000 + header - 0x8000 + 6));
                } else {
                    int originalBank = (before & 0x80) == 0 ? 0x1E : 0x1F;
                    assertEquals(originalBank, bank);
                    int ptr = (after & 31) * 2;
                    assertEquals(SongLayout.word(clean, originalBank * 0x8000 + ptr),
                            SongLayout.word(output, bank * 0x8000 + ptr));
                }
            }
            assertArrayEquals(Arrays.copyOfRange(clean, 0xED100, 0x100000),
                    Arrays.copyOfRange(output, 0xED100, 0x100000));
            if (slot == 0x99) {
                assertArrayEquals(output, BpsPatch.apply(BpsPatch.encode(clean, output).bytes(), clean));
                assertArrayEquals(HEX.parseHex("7099"), Arrays.copyOfRange(output, 0x12EE2, 0x12EE4));
            } else {
                assertEquals(slot, output[0x12EE3] & 255);
                assertEquals(slot, output[0x135E3] & 255);
            }
        }
    }

    private record Registers(int a, int bank) {}

    /** Runs just the instruction subset used by the marker and serial bank routines. */
    private static Registers runZ80(byte[] bytes, int pc, int a, int selector) {
        int e = 0, hl = 0, bank = 0, writes = 0;
        boolean z = false, carry = false;
        for (int step = 0; step < 40; step++) {
            int opcode = bytes[pc++] & 255;
            switch (opcode) {
                case 0x3A -> {
                    assertEquals(0x1B96, SongLayout.word(bytes, pc));
                    pc += 2;
                    a = selector;
                }
                case 0x3E -> a = bytes[pc++] & 255;
                case 0x47 -> {} // Save playlist flags in B.
                case 0xFE -> z = a == (bytes[pc++] & 255);
                case 0xE6 -> a &= bytes[pc++] & 255;
                case 0x87 -> {
                    carry = (a & 0x80) != 0;
                    a = a << 1 & 255;
                    z = a == 0;
                }
                case 0x20, 0x28, 0x38 -> {
                    int displacement = bytes[pc++];
                    if (opcode == 0x20 && !z || opcode == 0x28 && z || opcode == 0x38 && carry)
                        pc += displacement;
                }
                case 0xC3 -> pc = SongLayout.word(bytes, pc);
                case 0xAF -> a = 0;
                case 0x1E -> e = bytes[pc++] & 255;
                case 0x21 -> {
                    hl = SongLayout.word(bytes, pc);
                    pc += 2;
                }
                case 0x73, 0x77 -> {
                    assertEquals(0x6000, hl);
                    bank |= ((opcode == 0x73 ? e : a) & 1) << writes++;
                }
                case 0xC9 -> {
                    assertTrue(writes == 0 || writes == 9);
                    return new Registers(a, bank);
                }
                default -> fail("Unexpected Z80 opcode 0x%02X".formatted(opcode));
            }
        }
        throw new AssertionError("Z80 path did not return");
    }

    private static byte[] fixture(int size, List<S2Revision.Site> sites) {
        byte[] bytes = new byte[size];
        for (var site : sites) {
            byte[] expected = HEX.parseHex(site.expected());
            System.arraycopy(expected, 0, bytes, site.offset(), expected.length);
        }
        return bytes;
    }

    private static byte[] driverFixture() {
        byte[] driver = fixture(0x1308, S2Revision.DRIVER_SITES);
        for (int i = 0; i < 30; i++) driver[0x11F5 + (i < 27 ? i : i + 1)] = (byte) (0x80 + i);
        return driver;
    }

    private static S2Song song(int base) {
        byte[] bank = HEX.parseHex("0000010001ff0a0000008001f2");
        bank[7] = (byte) (base >>> 8);
        return new S2Song(bank, base, 0, Map.of(), Map.of(), List.of(6));
    }

    private static S2RomPatch.Options options(int slot) {
        return new S2RomPatch.Options(slot, true, true, false);
    }

    private static byte[] simulateInstall(byte[] driver, byte[] output) {
        byte[] ram = java.util.Arrays.copyOf(driver, 0x2000);
        var code = java.nio.ByteBuffer.wrap(output);
        int hook = 0xEC028;
        assertEquals(0x4EF9, code.getChar(hook));
        int pc = code.getInt(hook + 2);
        assertEquals(0x108000, pc);
        assertEquals(0x051366FC, code.getInt(pc)); // BTST D2,(A3); BNE.S -4
        pc += 4;
        while (code.getChar(pc) == 0x13FC) { // MOVE.B #imm,abs.l
            int address = code.getInt(pc + 4) - 0xA00000;
            assertTrue(address >= 0 && address < 0x1380);
            ram[address] = (byte) code.getChar(pc + 2);
            pc += 8;
        }
        assertArrayEquals(HEX.parseHex("0839000000c0000556f900a000074ef9000ec036"),
                java.util.Arrays.copyOfRange(output, pc, pc + 20));
        return ram;
    }
}
