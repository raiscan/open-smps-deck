package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.RetailSongOrigin;
import com.opensmpsdeck.project.StockRef;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Canonical inventory of exact stock payload hashes and their loader coordinates. */
final class StockTable {
    static final String RESOURCE = "/stock/s3k.json";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Comparator<StockRef> REF_ORDER = Comparator
            .comparing(StockRef::game).thenComparing(StockRef::table)
            .thenComparingInt(ref -> number(ref.songId())).thenComparingInt(ref -> number(ref.slot()))
            .thenComparingInt(ref -> number(ref.index())).thenComparingInt(ref -> number(ref.note()));
    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::game)
            .thenComparing(Entry::kind).thenComparing(entry -> entry.table() == null ? "" : entry.table())
            .thenComparingInt(entry -> number(entry.songId())).thenComparingInt(entry -> number(entry.slot()))
            .thenComparingInt(entry -> number(entry.index())).thenComparingInt(entry -> number(entry.note()));

    record Entry(String game, String kind, String table, Integer songId, Integer slot,
                 Integer index, Integer note, int length, String sha256) {
        StockRef ref() { return new StockRef(game, table, songId, slot, index, note, sha256); }

        static Entry of(StockRef ref, int length) {
            return new Entry(ref.game(), ref.kind(), ref.table(), ref.songId(), ref.slot(), ref.index(),
                    ref.note(), length, ref.sha256());
        }
    }

    private record Key(String kind, String sha256) {}

    private static final class PublicTable {
        static final List<Entry> ENTRIES = load();
        static final Map<Key, StockRef> ASSETS = new HashMap<>();
        static final Map<String, RetailSongOrigin> BANKS = new HashMap<>();
        static {
            for (Entry entry : ENTRIES) {
                if (entry.kind().equals("BANK")) {
                    BANKS.putIfAbsent(entry.sha256(), new RetailSongOrigin(
                            "retail-song", entry.game(), entry.songId(), entry.sha256()));
                } else {
                    ASSETS.merge(new Key(entry.kind(), entry.sha256()), entry.ref(), StockTable::prefer);
                }
            }
        }

        private static List<Entry> load() {
            var input = StockTable.class.getResourceAsStream(RESOURCE);
            if (input == null) throw new IllegalStateException("Missing public stock table " + RESOURCE);
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return List.copyOf(JSON.fromJson(reader, new TypeToken<List<Entry>>() {}.getType()));
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read public stock table", error);
            }
        }
    }

    private StockTable() {}

    static StockRef prefer(StockRef first, StockRef second) {
        return REF_ORDER.compare(first, second) <= 0 ? first : second;
    }

    static StockRef match(String kind, byte[] bytes) {
        return PublicTable.ASSETS.get(new Key(kind, hash(bytes)));
    }

    static RetailSongOrigin matchBank(byte[] bytes) {
        return matchBankHash(hash(bytes));
    }

    static RetailSongOrigin matchBankHash(String sha256) {
        return PublicTable.BANKS.get(sha256);
    }

    static AssetOrigin origin(String table, int slot, byte[] bytes) {
        return AssetOrigin.stock(ref(table, null, slot, hash(bytes)));
    }

    static StockRef ref(String table, Integer song, int coordinate, String sha256) {
        return new StockRef("s3k", table, song,
                table.endsWith("-fm") ? coordinate : null,
                table.equals("psg") || table.equals("mod") ? coordinate : null,
                table.equals("dac") ? coordinate : null, sha256);
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static byte[] bytes(List<Integer> values) {
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (int) values.get(i);
        return bytes;
    }

    static String encode(List<Entry> entries) {
        return JSON.toJson(entries.stream().sorted(ENTRY_ORDER).toList()) + "\n";
    }

    static List<Entry> entries() { return PublicTable.ENTRIES; }

    static List<Entry> build(Path path) throws IOException {
        byte[] cartridge = S3kRomImporter.readCartridge(path);
        var entries = new ArrayList<Entry>();
        try (var rom = Rom.fromReader(RomByteReader.fromBytes(cartridge), path.getFileName().toString())) {
            var loader = new Sonic3kSmpsLoader(rom);
            byte[] global = loader.getGlobalVoiceData();
            for (int slot = 0; slot < global.length / 25; slot++) {
                add(entries, "global-fm", null, slot, Arrays.copyOfRange(global, slot * 25, (slot + 1) * 25));
            }
            loader.getPsgEnvelopes().forEach((index, bytes) -> add(entries, "psg", null, index, bytes));
            loader.getPlaybackModEnvelopes().forEach((index, bytes) -> add(entries, "mod", null, index, bytes));
            var dac = loader.loadDacData();
            for (int note = 0x81; note <= 0xC4; note++) {
                var entry = dac.mappingForNote(note);
                if (entry == null) continue;
                var sample = dac.sample(entry.sampleId());
                byte[] bytes = new byte[sample.length()];
                for (int i = 0; i < bytes.length; i++) bytes[i] = sample.byteAt(i);
                add(entries, "dac", null, note, bytes);
            }
            for (var song : S3kRomImporter.songs()) {
                var project = S3kRomImporter.readSong(rom, loader, song);
                byte[] bank = project.nativeProgram().bank();
                entries.add(new Entry("s3k", "BANK", null, song.id(), null, null, null, bank.length, hash(bank)));
                var data = (Sonic3kSmpsData) loader.loadMusic(song.id());
                for (int slot = 0; slot < project.nativeProgram().localVoiceCount(); slot++) {
                    int offset = data.getVoicePtr() - 0x8000 + slot * 25;
                    add(entries, "local-fm", song.id(), slot, Arrays.copyOfRange(data.getBankData(), offset, offset + 25));
                }
            }
        }
        return entries.stream().sorted(ENTRY_ORDER).toList();
    }

    static void build(Path rom, Path output) throws IOException {
        Files.writeString(output, encode(build(rom)), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void add(List<Entry> entries, String table, Integer song, int coordinate, byte[] bytes) {
        entries.add(Entry.of(ref(table, song, coordinate, hash(bytes)), bytes.length));
    }

    private static int number(Integer value) { return value == null ? -1 : value; }
}
