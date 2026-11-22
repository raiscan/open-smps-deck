package com.opensmpsdeck.headless.rompatch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opensmpsdeck.project.CompiledProject;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.rompatch.S3kRevision.*;
import static com.opensmpsdeck.headless.rompatch.SongLayout.*;

public final class RomPatch {
    public static final int DEFAULT_SLOT = 0x25;
    public static final int INPUT_ROM_BYTES = ROM_SIZE;
    public static final String WARNING = "The patched ROM is derived from the player's retail ROM and contains copyrighted data. Personal use only. Do not redistribute.";

    public record Options(int slot, boolean titleMusic, boolean noDemo, boolean loop) {
        public Options {
            if (slot < 1 || slot > MUSIC_COUNT) throw new IllegalArgumentException("Music ID must be in 0x01–0x33");
        }
    }

    public record Result(byte[] bytes, JsonObject report) {}

    private RomPatch() {}

    /** Upper bound before allocating assets or accounting for individual piece sizes. */
    public static int largestBankCapacity() {
        return new Placement(fragments()).capacity();
    }

    /** Produces a complete new image in memory. Neither the input ROM nor the project is changed. */
    public static Result patch(byte[] rom, CompiledProject compiled, Options options) throws IOException {
        return patch(rom, compiled, options, List.of());
    }

    /** Validates optional package relocation offsets against the reachable bank pointers. */
    public static Result patch(byte[] rom, CompiledProject compiled, Options options, List<Integer> relocations)
            throws IOException {
        if (rom.length != ROM_SIZE || !SHA1.equals(hash("SHA-1", rom)))
            throw new IllegalArgumentException("Unsupported cartridge image. ROM patch requires the verified 4 MiB locked-on S3&K ROM (SHA-1 " + SHA1 + ")");
        boolean title = options.titleMusic() && options.slot() != DEFAULT_SLOT;
        PatchCode.verify(rom, title, options.noDemo());
        byte[] source = compiled.song();
        var placement = new Placement(fragments());
        if (source.length > WINDOW) throw Placement.oversized(source.length, placement.capacity());
        var input = SongInput.of(compiled, source, relocations);
        byte[] driver = driverRam(rom);
        int header = input.header(), base = input.base();
        var layout = SongLayout.music(source, base, header, input.voiceCount(), true);
        var operands = new TreeSet<>(layout.pointers());
        for (int relocation : input.relocations()) {
            if (!operands.contains(relocation))
                throw invalid(relocation, "package relocation is not a recognized bank pointer");
        }
        if (options.loop()) {
            var rewrite = SongLoop.rewrite(source, base, header, layout, operands);
            source = rewrite.bytes();
            header = rewrite.header();
            operands.clear();
            rewrite.relocations().forEach(operands::add);
            if (source.length > WINDOW) throw Placement.oversized(source.length, placement.capacity());
            layout = SongLayout.music(source, base, header, input.voiceCount(), true);
        }
        checkGlobalVoices(input, source, header, driver);

        var edits = new RomEdits(rom);
        var ram = new LinkedHashMap<Integer, Byte>();
        // Allocate the bounded DAC space before the song so the track cannot
        // take a required note pointer or fragment in that bank.
        var dac = PatchAssets.dac(input, layout, source, driver, placement, edits, ram);
        var envelopes = PatchAssets.envelopes(input, layout, source, driver, ram);
        int slotOffset = options.slot() - 1;
        int bankEntry = MUSIC_BANKS + slotOffset, pointerEntry = MUSIC_POINTERS + 2 * slotOffset;
        ram.put(bankEntry, (byte) 0);
        PatchAssets.putWord(ram, pointerEntry, 0);
        int stubLength = PatchCode.stub(ram).length;
        var song = placement.song(layout.pieces(), source.length);
        int stubOffset = placement.findAligned(stubLength);
        if (stubOffset < 0)
            throw new IllegalArgumentException("No proven-free fragment can hold the driver stub and selected assets. Shorten the song or reduce layering.");
        ram.put(bankEntry, (byte) song.bank());
        PatchAssets.putWord(ram, pointerEntry, song.pointer(header));
        edits.write(stubOffset, PatchCode.stub(ram), "Z80 table setup stub");
        var relocated = song.relocate(source, base, operands);
        for (var piece : song.pieces()) {
            edits.write(piece.romOffset(), java.util.Arrays.copyOfRange(relocated,
                    piece.source().start(), piece.source().end()), "song");
        }
        PatchCode.install(edits, stubOffset, options.slot(), title, options.noDemo());
        PatchCode.checksums(edits);

        var report = new LinkedHashMap<String, Object>();
        report.put("inputSha1", hash("SHA-1", rom));
        report.put("outputSha1", hash("SHA-1", edits.bytes()));
        report.put("outputSha256", hash("SHA-256", edits.bytes()));
        report.put("slot", options.slot());
        report.put("bank", song.bank());
        report.put("headerPointer", song.pointer(header));
        report.put("compiledSize", compiled.song().length);
        report.put("freeBytesByBank", freeBytesByBank(fragments()));
        report.put("fragments", song.pieces());
        report.put("romRangesWritten", edits.ranges());
        report.put("stubOffset", stubOffset);
        report.put("dacNoteMap", dac);
        report.put("envelopeMap", envelopes);
        if (ram.containsKey(PSG_MASTER))
            report.put("psgTable", Map.of("start", PSG_FREE_START, "end",
                    ram.keySet().stream().filter(at -> at >= PSG_FREE_START && at < PSG_FREE_END)
                            .mapToInt(Integer::intValue).max().orElseThrow() + 1));
        var approximations = new ArrayList<String>();
        for (var entry : dac) {
            if (!entry.exact()) approximations.add("DAC note 0x%02X → 0x%02X (4-bit DPCM at rate %d)"
                    .formatted(entry.note(), entry.mappedNote(), entry.rate()));
        }
        for (var entry : envelopes) {
            if (!entry.exact()) approximations.add("PSG envelope %d → stock envelope %d"
                    .formatted(entry.index(), entry.mappedIndex()));
        }
        report.put("approximations", approximations);
        var warnings = new ArrayList<>(List.of(WARNING));
        if (options.slot() != DEFAULT_SLOT && !title)
            warnings.add("The title screen still requests slot 0x25. Use --title-music to change its request.");
        warnings.addAll(approximations);
        report.put("warnings", warnings);
        return new Result(edits.bytes(), new Gson().toJsonTree(report).getAsJsonObject());
    }

    private static void checkGlobalVoices(SongInput input, byte[] song, int header, byte[] ram) {
        int pointer = word(song, header);
        if (pointer != 0 && (input.base() != WINDOW || pointer >= WINDOW)) return;
        int at = pointer == 0 ? word(ram, 0x1302) : pointer;
        if (at < word(ram, 0x1302) || at >= PSG_FREE_START)
            throw invalid(header, "global voice pointer is outside the retail table");
        for (var voice : input.project().globalVoices())
            for (int value : voice.bytes())
                if (at >= PSG_FREE_START || u8(ram, at++) != value)
                    throw invalid(header, "project global voices differ from the retail sound driver");
    }

    private static Map<Integer, Integer> freeBytesByBank(List<Span> free) {
        var bytes = new TreeMap<Integer, Integer>();
        for (var span : free) bytes.merge(span.start() / WINDOW, span.size(), Integer::sum);
        return bytes;
    }

    static String hash(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
