package com.opensmpsdeck.headless.rompatch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.opensmpsdeck.headless.rompatch.S2Revision.*;

public final class S2RomPatch {
    public static final int DEFAULT_SLOT = TITLE_SLOT;
    private static final Gson JSON = new Gson();

    public record Options(int slot, boolean titleMusic, boolean noDemo, boolean loop) {
        public Options {
            if (slot < FIRST_SLOT || slot > LAST_SLOT)
                throw new IllegalArgumentException("Sonic 2 music ID must be in 0x81–0x9F");
        }
    }

    public record Result(byte[] bytes, JsonObject report) {}
    private record Z80Write(int start, byte[] bytes, String purpose) {}
    private record Z80Range(int start, int end, String purpose) {}

    private S2RomPatch() {}

    public static Result patch(byte[] cleanRom, S2Song song, Options options) {
        byte[] rom = cleanRom.clone();
        if (rom.length != ROM.size() || !ROM.sha1().equalsIgnoreCase(RomPatch.hash("SHA-1", rom)))
            throw new IllegalArgumentException("ROM patch requires the verified 1 MiB Sonic 2 World REV01 ROM (SHA-1 "
                    + ROM.sha1() + ")");
        byte[] driver = decompress(rom);
        verifyRetailPlaylist(driver);
        return patchVerified(rom, driver, song, options);
    }

    // The public entry point verifies the full-file hash. Synthetic layout tests supply short patterns.
    static Result patchVerified(byte[] rom, byte[] driver, S2Song song, Options options) {
        verify(rom, driver);
        var layout = S2Bank.read(song);
        byte[] bank = song.bank();
        var psg = S2Psg.map(driver, bank, layout.envelopes, song.envelopes());
        var dac = S2Dac.map(rom, driver, bank, layout.drums, song.drums());
        layout.relocate(bank);
        int slot = options.slot();
        bank = layout.finish(bank, options.loop(), slot == EXTRA_LIFE);

        int playlistEntry = PLAYLIST + slot - FIRST_SLOT;
        int oldFlags = u8(driver, playlistEntry);
        int marker = UNCOMPRESSED_FLAG | INDEX_MASK | (oldFlags & PAL_FLAG);
        var writes = new ArrayList<>(List.of(
                new Z80Write(SELECT_BANK, hex("cd6c0c32961b"), "Decode the selected playlist bank"),
                new Z80Write(READ_POINTER, hex("cd2a13"), "Resolve the song header"),
                new Z80Write(BANK_SWITCH, hex("873810c30813"), "Dispatch retail bank 0x1F or extended selector"),
                new Z80Write(DECODE_MARKER, hex("47fe%02x2803e680c93e01c9".formatted(marker)), "Decode marker as selector 1"),
                new Z80Write(playlistEntry, new byte[]{(byte) marker}, "Selected playlist slot"),
                new Z80Write(DRIVER_END, hex("2010" + "af1e01210060777373737377777777c9"
                        + "af1e01210060777777777773777777c9"
                        + "3a961b3dc2630ccd630ce1e1110080c3cc07"),
                        "Banks 0x1E and 0x20 and direct header helper")));
        if (psg.relocatedTable().length > 0) {
            writes.add(new Z80Write(PSG_IMMEDIATE, hex("3c13"), "Relocate PSG table pointer"));
            writes.add(new Z80Write(PSG_RELOCATED, psg.relocatedTable(), "Stock pointers and custom PSG envelopes"));
        }

        byte[] expanded = Arrays.copyOf(rom, OUTPUT_SIZE);
        Arrays.fill(expanded, rom.length, expanded.length, (byte) 0xFF);
        var edits = new RomEdits(expanded);
        edits.write(SONG_ROM, bank, "Uncompressed music bank 0x20");
        edits.write(STUB_ROM, bootStub(writes), "Install Z80 patches while 68000 owns the bus");
        edits.write(BOOT_HOOK, hex("4ef9001080004e714e714e714e71"), "SoundDriverLoad hook");
        boolean redirect = options.titleMusic() && slot != TITLE_SLOT;
        if (redirect) {
            byte[] operand = {(byte) slot};
            edits.write(TITLE_INTRO + 1, operand, "Title intro music request");
            edits.write(TITLE_NO_INTRO + 1, operand, "Title music request without intro");
        }
        if (options.noDemo()) edits.write(DEMO, hex("4e714e71"), "Keep the title screen active after its idle timeout");
        edits.write(ROM_END, ByteBuffer.allocate(4).putInt(OUTPUT_SIZE - 1).array(), "Inclusive cartridge end");
        byte[] output = edits.bytes();
        int checksum = 0;
        for (int i = HEADER_END; i < output.length; i += 2)
            checksum = (checksum + (u8(output, i) << 8) + u8(output, i + 1)) & 0xFFFF;
        edits.write(CHECKSUM, ByteBuffer.allocate(2).putShort((short) checksum).array(), "Cold boot checksum");

        var report = new JsonObject();
        report.addProperty("revision", ROM.displayName());
        report.addProperty("inputSha1", ROM.sha1());
        report.addProperty("outputSha1", RomPatch.hash("SHA-1", output));
        report.addProperty("inputSha256", RomPatch.hash("SHA-256", rom));
        report.addProperty("outputSha256", RomPatch.hash("SHA-256", output));
        report.addProperty("inputSize", rom.length);
        report.addProperty("outputSize", output.length);
        report.addProperty("checksum", checksum);
        report.addProperty("slot", slot);
        report.addProperty("bank", SONG_BANK);
        report.addProperty("songPointer", WINDOW);
        report.addProperty("playlistMarker", marker);
        report.addProperty("compiledSize", song.bank().length);
        report.addProperty("installedSize", bank.length);
        report.addProperty("titleMusic", slot == TITLE_SLOT || redirect);
        report.addProperty("noDemo", options.noDemo());
        report.addProperty("loop", options.loop());
        report.add("freeBytesByBank", JSON.toJsonTree(java.util.Map.of(SONG_BANK, WINDOW)));
        report.add("romRanges", JSON.toJsonTree(edits.ranges()));
        report.add("z80Changes", JSON.toJsonTree(writes.stream()
                .map(write -> new Z80Range(write.start(), write.start() + write.bytes().length, write.purpose())).toList()));
        report.add("dacNoteMap", JSON.toJsonTree(dac));
        report.add("envelopeMap", JSON.toJsonTree(psg.bindings()));

        var warnings = new ArrayList<>(List.of(RomPatch.WARNING));
        if (slot != TITLE_SLOT && !redirect) warnings.add("The title still requests retail slot 0x99. Use --title-music to redirect it.");
        if (psg.bindings().stream().anyMatch(binding -> !binding.exact()))
            warnings.add("PSG envelopes exceeded Z80 RAM capacity. See envelopeMap for stock approximations.");
        if (dac.stream().anyMatch(binding -> !binding.exact()))
            warnings.add("Sonic 2 substitutes stock DAC sounds. See dacNoteMap for substitutions and distances.");
        report.add("warnings", JSON.toJsonTree(warnings));
        return new Result(output, report);
    }

    private static byte[] bootStub(List<Z80Write> writes) {
        int count = writes.stream().mapToInt(write -> write.bytes().length).sum();
        var code = ByteBuffer.allocate(4 + 8 * count + 14 + 6);
        code.put(hex("051366fc")); // BTST D2,(A3); BNE.S back to BTST.
        boolean[] claimed = new boolean[BUFFER_START];
        for (var write : writes) {
            for (int i = 0; i < write.bytes().length; i++) {
                int address = write.start() + i;
                if (address < 0 || address >= BUFFER_START || claimed[address])
                    throw new IllegalStateException("Overlapping or out-of-bounds S2 Z80 patch");
                claimed[address] = true;
                code.putShort((short) 0x13FC).putShort((short) u8(write.bytes(), i)).putInt(Z80_RAM + address);
            }
        }
        return code.put(hex(PAL_PROBE)).putShort((short) 0x4EF9).putInt(BOOT_RETURN).array();
    }
}
