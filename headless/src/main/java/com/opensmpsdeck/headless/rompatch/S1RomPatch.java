package com.opensmpsdeck.headless.rompatch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.openggf.data.compression.KosinskiReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.rompatch.S1Revision.*;

/** In-memory cartridge patcher for Sonic 1 World REV01. */
public final class S1RomPatch {
    public static final int DEFAULT_SLOT = TITLE_SLOT;
    private static final int MAX_SONG_BYTES = 32768;
    private static final Gson JSON = new Gson();

    public record Options(int slot, boolean titleMusic, boolean noDemo) {
        public Options {
            if (slot < FIRST_SLOT || slot > LAST_SLOT)
                throw new IllegalArgumentException("Sonic 1 music ID must be in 0x81–0x93");
        }
    }

    public record Result(byte[] bytes, JsonObject report) {}
    private record EnvelopeBinding(int index, int mappedIndex, boolean stock) {}
    private record Range(int start, int end) {}

    private S1RomPatch() {}

    public static Result patch(byte[] cleanRom, S1Song song, Options options) throws IOException {
        byte[] rom = cleanRom.clone();
        if (rom.length != ROM.size() || !ROM.sha1().equalsIgnoreCase(RomPatch.hash("SHA-1", rom))) {
            throw new IllegalArgumentException("Unsupported cartridge image. ROM patch requires the verified "
                    + "512 KiB Sonic 1 World REV01 ROM (SHA-1 " + ROM.sha1() + ")");
        }
        try (var compressed = Channels.newChannel(new ByteArrayInputStream(rom, DRIVER_ROM, DRIVER_END - DRIVER_ROM))) {
            return patchVerified(rom, song, options, KosinskiReader.decompress(compressed, false));
        }
    }

    // The public entry point always checks the full hash. The layout and writes
    // can also be tested with synthetic instruction, header and driver fixtures.
    static Result patchVerified(byte[] rom, S1Song song, Options options, byte[] driver) {
        verifyRom(rom);
        verifyDriver(driver);
        byte[] bank = song.bank();
        if (bank.length > MAX_SONG_BYTES) throw Placement.oversized(bank.length, MAX_SONG_BYTES);
        var layout = S1Bank.read(bank, song.localVoiceCount());
        var dac = S1Dac.plan(rom, driver, bank, layout.dac(), song.dacBindings());

        int songStart = ROM.size(), songEnd = songStart + bank.length;
        byte[] expanded = Arrays.copyOf(rom, OUTPUT_SIZE);
        Arrays.fill(expanded, songStart, expanded.length, (byte) 0xFF);
        var edits = new RomEdits(expanded);
        int tableStart = align(songEnd);
        var envelopeMap = new ArrayList<EnvelopeBinding>();
        int tableEnd = envelopes(edits, bank, layout.envelopes(), song.psgEnvelopes(), tableStart, envelopeMap);
        edits.write(songStart, bank, "S1 music bank");
        int slot = options.slot();
        edits.write(MUSIC_TABLE + (slot - FIRST_SLOT) * 4, be32(songStart), "music slot");
        boolean redirectsTitle = options.titleMusic() && slot != TITLE_SLOT;
        if (redirectsTitle) edits.write(TITLE + 3, new byte[]{(byte) slot}, "title music ID");
        if (options.noDemo()) {
            edits.write(DEMO_TIMEOUT + 4, new byte[]{0x4E, 0x71, 0x4E, 0x71}, "title demo timeout");
            edits.write(TITLE_FAILSAFE + 4, new byte[]{0x60}, "keep title active past scroll limit");
        }
        if (!dac.writes().isEmpty()) {
            int stub = align(tableEnd);
            edits.write(stub, PatchCode.stub(dac.writes()), "Z80 DAC install stub");
            edits.write(DRIVER_HOOK, ByteBuffer.allocate(8).putShort((short) 0x4EB9).putInt(stub)
                    .putShort((short) 0x4E71).array(), "DAC driver load hook");
            edits.write(TIMPANI_WRITE + 2, be32(Z80_RAM + NEW_DAC_TABLE + 2 * DAC_ENTRY_SIZE + 4),
                    "timpani pitch destination");
        }
        edits.write(ROM_END, be32(OUTPUT_SIZE - 1), "header inclusive ROM end");
        int checksum = checksum(edits.bytes());
        edits.write(CHECKSUM, ByteBuffer.allocate(2).putShort((short) checksum).array(), "header checksum");

        var approximations = new ArrayList<String>();
        for (var binding : dac.bindings()) {
            if (binding.exact()) continue;
            if (binding.kind().equals("stock")) {
                approximations.add("DAC note 0x%02X → stock %s 0x%02X (rate %d → %d, distance %.4f)"
                        .formatted(binding.note(), binding.stockSample(), binding.mappedNote(),
                                binding.requestedRate(), binding.rate(), binding.distance()));
            } else {
                approximations.add("DAC note 0x%02X → 0x%02X (4-bit DPCM at rate %d)"
                        .formatted(binding.note(), binding.mappedNote(), binding.rate()));
            }
        }
        var warnings = new ArrayList<>(List.of(RomPatch.WARNING));
        if (slot != TITLE_SLOT && !redirectsTitle) warnings.add("The title screen still requests slot 0x8A.");
        if (slot <= 0x88) warnings.add("This slot uses its retail speed-shoes tempo during gameplay.");
        if (slot <= 0x83) warnings.add("Retail speed-shoes lookups for later slots can read bytes from this music pointer.");
        warnings.addAll(approximations);

        var report = new LinkedHashMap<String, Object>();
        report.put("inputSha1", RomPatch.hash("SHA-1", rom));
        report.put("outputSha1", RomPatch.hash("SHA-1", edits.bytes()));
        report.put("outputSha256", RomPatch.hash("SHA-256", edits.bytes()));
        report.put("slot", slot);
        report.put("titleSlot", redirectsTitle ? slot : TITLE_SLOT);
        report.put("noDemo", options.noDemo());
        report.put("songRomRange", new Range(songStart, songEnd));
        report.put("compiledSize", bank.length);
        report.put("romSize", OUTPUT_SIZE);
        report.put("romEnd", OUTPUT_SIZE - 1);
        report.put("checksum", checksum);
        report.put("romRangesWritten", edits.ranges());
        if (tableEnd > tableStart) {
            int lastSlot = envelopeMap.stream().mapToInt(EnvelopeBinding::mappedIndex).max().orElseThrow();
            report.put("psgTable", new Range(tableStart, tableStart + 4 * lastSlot));
        }
        report.put("envelopeMap", envelopeMap);
        report.put("z80FreeBytes", Z80_RESERVED - DRIVER_RAM_END);
        report.put("z80CustomDpcmCapacity", Z80_RESERVED - DAC_PAYLOAD);
        report.put("dacNoteMap", dac.bindings());
        report.put("approximations", approximations);
        report.put("warnings", warnings);
        return new Result(edits.bytes(), JSON.toJsonTree(report).getAsJsonObject());
    }

    private static int envelopes(RomEdits edits, byte[] bank, List<Integer> operands,
                                 Map<Integer, byte[]> assets, int start, List<EnvelopeBinding> report) {
        int stockCount = PSG_POINTERS.length;
        var stock = new ArrayList<byte[]>();
        for (int i = 0; i < stockCount; i++)
            stock.add(Arrays.copyOfRange(edits.bytes(), PSG_POINTERS[i], PSG_POINTERS[i] + PSG_LENGTHS[i]));
        var data = new ArrayList<>(stock);
        var mapping = new TreeMap<Integer, Integer>();
        for (int at : operands) {
            int index = u8(bank, at);
            if (index == 0) continue;
            if (!mapping.containsKey(index)) {
                byte[] envelope = assets.get(index);
                S1Bank.require(validEnvelope(envelope), at,
                        "PSG envelope %d requires 1–255 attenuation bytes followed by a single 0x80 hold".formatted(index));
                int slot = 0;
                while (slot < data.size() && !Arrays.equals(data.get(slot), envelope)) slot++;
                S1Bank.require(slot < 255, at, "PSG envelope table is full after retaining nine stock slots");
                if (slot == data.size()) data.add(envelope);
                mapping.put(index, slot + 1);
            }
            bank[at] = mapping.get(index).byteValue();
        }
        mapping.forEach((from, to) -> report.add(new EnvelopeBinding(from, to, to <= stockCount)));
        if (data.size() == stockCount) return start;
        var pointers = ByteBuffer.allocate(4 * data.size());
        for (int pointer : PSG_POINTERS) pointers.putInt(pointer);
        int next = start + pointers.capacity();
        for (int i = stockCount; i < data.size(); i++) {
            byte[] envelope = data.get(i);
            pointers.putInt(next);
            edits.write(next, envelope, "PSG envelope " + (i + 1));
            next += envelope.length;
        }
        edits.write(start, pointers.array(), "PSG envelope table");
        edits.write(PSG_MASTER, be32(start), "PSG master table pointer");
        return next;
    }

    private static boolean validEnvelope(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes.length > 256 || u8(bytes, bytes.length - 1) != 0x80)
            return false;
        for (int i = 0; i < bytes.length - 1; i++) if (bytes[i] < 0) return false;
        return true;
    }

    private static int align(int at) { return (at + 1) & ~1; }

    static int checksum(byte[] rom) {
        int end = ByteBuffer.wrap(rom).getInt(ROM_END);
        int sum = 0;
        for (int at = HEADER_END; at <= end; at += 2) sum += (u8(rom, at) << 8) | u8(rom, at + 1);
        return sum & 0xFFFF;
    }
}
