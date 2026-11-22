package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;

import com.openggf.audio.smps.DacData;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.RetailSongOrigin;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/** Address-preserving import from the verified locked-on S3&K retail cartridge. */
public final class S3kRomImporter {
    private static final int BANK_SIZE = 0x8000;
    private static final int ROM_SIZE = 4 * 1024 * 1024;
    private static final String LOCKED_ON_SHA1 = "cfbf98c36c776677290a872547ac47c53d2761d6";
    private static final int DEFAULT_PREVIEW_FRAMES = 3600;
    private static final int VOICE_BYTES = 25;
    private static final List<Song> SONGS = Sonic3kMusic.titleMap().entrySet().stream()
            .filter(entry -> entry.getKey() >= 1 && entry.getKey() <= Sonic3kMusic.CREDITS_SK.id)
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new Song(entry.getKey(), entry.getKey() == Sonic3kMusic.MINIBOSS_S3.id
                    ? "Mini-Boss (alias of 0x18)" : entry.getValue()))
            .toList();

    public record Song(int id, String name) {}

    private S3kRomImporter() {}

    public static List<Song> listSongs(Path path) throws IOException {
        readCartridge(path);
        return SONGS;
    }

    public static StudioProject readSong(Path path, int id) throws IOException {
        Song song = SONGS.stream().filter(entry -> entry.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Music ID must be in 0x01–0x33"));
        byte[] cartridge = readCartridge(path);
        try (var rom = Rom.fromReader(RomByteReader.fromBytes(cartridge), path.getFileName().toString())) {
            var loader = new Sonic3kSmpsLoader(rom);
            return readSong(rom, loader, song);
        }
    }

    static List<Song> songs() { return SONGS; }

    static StudioProject readSong(Rom rom, Sonic3kSmpsLoader loader, Song song) throws IOException {
        int address = loader.findMusicOffset(song.id());
        if (address < 0) throw new IOException("Cartridge music table has no entry for " + song.name());
        int headerOffset = address % BANK_SIZE;
        byte[] bank = readBank(rom, address);
        return assemble(song.name(), bank, headerOffset, loader.getGlobalVoiceData(),
                loader.getPsgEnvelopes(), loader.getModEnvelopes(), loader.getPlaybackModEnvelopes(),
                loader.loadDacData(), true).withDerivedFrom(
                        new RetailSongOrigin("retail-song", "s3k", song.id(), StockTable.hash(bank)));
    }

    static byte[] readBank(Rom rom, int musicAddress) throws IOException {
        return rom.readBytes(musicAddress - musicAddress % BANK_SIZE, BANK_SIZE);
    }

    static StudioProject assemble(String name, byte[] bank, int headerOffset, byte[] globalTable,
                                  Map<Integer, byte[]> psg, Map<Integer, byte[]> rawModulation,
                                  Map<Integer, byte[]> playbackModulation, DacData dac) {
        return assemble(name, bank, headerOffset, globalTable, psg, rawModulation, playbackModulation, dac, false);
    }

    private static StudioProject assemble(String name, byte[] bank, int headerOffset, byte[] globalTable,
                                          Map<Integer, byte[]> psg, Map<Integer, byte[]> rawModulation,
                                          Map<Integer, byte[]> playbackModulation, DacData dac, boolean retail) {
        var envelopes = new ArrayList<Envelope>();
        var envelopeSlots = new ArrayList<NativeProgram.EnvelopeSlot>();
        new TreeMap<>(psg).forEach((slot, bytes) -> {
            String assetId = "psg-%02x".formatted(slot);
            envelopes.add(new Envelope(assetId, "PSG %02X".formatted(slot), Envelope.PSG_VOLUME, unsigned(bytes),
                    retail ? StockTable.origin("psg", slot, bytes) : null));
            envelopeSlots.add(new NativeProgram.EnvelopeSlot(assetId, slot));
        });
        new TreeMap<>(rawModulation).forEach((slot, raw) -> {
            byte[] playback = playbackModulation.get(slot);
            String assetId = "mod-%02x".formatted(slot);
            String title = "Modulation %02X".formatted(slot);
            if (!Arrays.equals(raw, playback)) {
                envelopes.add(new Envelope(assetId + "-rom", title + " (ROM)", Envelope.MODULATION, unsigned(raw),
                        retail ? AssetOrigin.stock(null) : null));
            }
            if (playback != null) {
                envelopes.add(new Envelope(assetId, title, Envelope.MODULATION, unsigned(playback),
                        retail ? StockTable.origin("mod", slot, playback) : null));
                envelopeSlots.add(new NativeProgram.EnvelopeSlot(assetId, slot));
            }
        });

        if (globalTable == null || globalTable.length % VOICE_BYTES != 0)
            throw new IllegalArgumentException("Invalid global FM voice table");
        var voices = new ArrayList<Voice>();
        for (int offset = 0; offset < globalTable.length; offset += VOICE_BYTES) {
            int index = offset / VOICE_BYTES;
            byte[] bytes = Arrays.copyOfRange(globalTable, offset, offset + VOICE_BYTES);
            voices.add(new Voice("global-%02x".formatted(index), "Global FM %02X".formatted(index),
                    unsigned(bytes), retail ? StockTable.origin("global-fm", index, bytes) : null));
        }

        if (dac.baseCycles() != DacSample.BASE_CYCLES)
            throw new IllegalArgumentException("Invalid S3K DAC base cycles");
        var uniquePcm = new LinkedHashMap<String, DacSample>();
        var dacSlots = new ArrayList<NativeProgram.DacSlot>();
        for (int note = DacSample.FIRST_NOTE; note <= 0xDF; note++) {
            var entry = dac.mappingForNote(note);
            if (entry == null) continue;
            var sample = dac.sample(entry.sampleId());
            if (sample == null) throw new IllegalArgumentException("Missing DAC sample for note %02X".formatted(note));
            byte[] pcm = new byte[sample.length()];
            for (int index = 0; index < pcm.length; index++) pcm[index] = sample.byteAt(index);
            var candidate = DacSample.of("dac-%02x".formatted(note), "DAC %02X".formatted(note), entry.rate(), pcm)
                    .withOrigin(retail ? StockTable.origin("dac", note, pcm) : null);
            var canonical = uniquePcm.computeIfAbsent(candidate.pcmBase64(), ignored -> candidate);
            dacSlots.add(new NativeProgram.DacSlot(note, canonical.id(), entry.rate()));
        }

        var samples = List.copyOf(uniquePcm.values());
        var unmeasured = NativeProgram.of(BANK_SIZE, headerOffset, 0, DEFAULT_PREVIEW_FRAMES, bank, envelopeSlots, dacSlots);
        int voiceCount = NativeBankAnalysis.discoverLocalVoiceCount(
                new CompiledProject(bank, envelopes, samples, unmeasured, voices));
        var program = NativeProgram.of(BANK_SIZE, headerOffset, voiceCount, DEFAULT_PREVIEW_FRAMES, bank, envelopeSlots, dacSlots);
        var project = new StudioProject(5, name, "S3K", 24, program.tempo(), voices, List.of(), List.of(),
                envelopes, samples, program, TargetGame.S3K, null);
        ProjectFormat.validate(project);
        return project;
    }

    static byte[] readCartridge(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Select a regular S3&K cartridge image");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(ROM_SIZE + 1);
        }
        if (bytes.length != ROM_SIZE || !LOCKED_ON_SHA1.equals(sha1(bytes))) {
            throw new IOException("Unsupported cartridge image. Import requires the verified 4 MiB locked-on S3&K ROM");
        }
        return bytes;
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static List<Integer> unsigned(byte[] bytes) {
        return IntStream.range(0, bytes.length).map(index -> Byte.toUnsignedInt(bytes[index])).boxed().toList();
    }
}
