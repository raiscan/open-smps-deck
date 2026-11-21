package com.opensmpsdeck.headless;

import com.openggf.audio.smps.DacData;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.RomFingerprint;
import com.openggf.data.RomIdentity;
import com.openggf.data.RomIdentityTable;
import com.openggf.game.sonic3k.audio.Sonic3kAudioResourceProfile;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.StockRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class S3kPackageDriver implements PackageDriver {
    static final S3kPackageDriver INSTANCE = new S3kPackageDriver();

    // The resolver lazily caches local FM tables while the ROM is open.
    static final class RomSource implements StockAssets {
        private final Sonic3kAudioResourceProfile profile;
        private final Sonic3kSmpsLoader loader;
        private final Map<Integer, Sonic3kSmpsData> songs = new HashMap<>();
        private final DacData dac;

        RomSource(Rom rom, Sonic3kAudioResourceProfile profile) {
            this.profile = profile;
            loader = new Sonic3kSmpsLoader(rom, profile);
            dac = loader.loadDacData();
        }

        @Override public byte[] payload(StockRef ref) {
            if (!ref.game().equals("s3k")) return null;
            return switch (ref.table()) {
                case "global-fm" -> voice(loader.getGlobalVoiceData(), ref.slot() * 25);
                case "local-fm" -> localVoice(ref.songId(), ref.slot());
                case "psg" -> loader.getPsgEnvelopes().get(ref.index());
                case "mod" -> loader.getPlaybackModEnvelopes().get(ref.index());
                case "dac" -> dacPayload(dac, ref.note());
                default -> throw new AssertionError(ref.table());
            };
        }

        private byte[] localVoice(int songId, int slot) {
            final int musicId;
            try {
                musicId = profile.musicId(songId);
            } catch (IllegalArgumentException absent) {
                return null;
            }
            var song = songs.computeIfAbsent(musicId, id -> (Sonic3kSmpsData) loader.loadMusic(id));
            if (song == null || song.getVoicePtr() < 0x8000) return null;
            return voice(song.getBankData(), song.getVoicePtr() - 0x8000 + slot * 25);
        }

        private static byte[] voice(byte[] table, int offset) {
            return table == null || offset < 0 || offset + 25 > table.length ? null
                    : Arrays.copyOfRange(table, offset, offset + 25);
        }
    }

    static byte[] dacPayload(DacData dac, int note) {
        if (dac == null) return null;
        var entry = dac.mappingForNote(note);
        if (entry == null) return null;
        var sample = dac.sample(entry.sampleId());
        if (sample == null) return null;
        byte[] pcm = new byte[sample.length()];
        for (int i = 0; i < pcm.length; i++) pcm[i] = sample.byteAt(i);
        return pcm;
    }

    private S3kPackageDriver() {}

    @Override public String id() { return "s3k"; }

    @Override public void validateBase(int base) {
        if (base != 0 && base != 32768) throw new IllegalArgumentException("S3K bank base must be 0 or 32768");
    }

    @Override public Image openRom(Path path) throws IOException {
        final byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(0x400001);
        }
        if (bytes.length != 0x200000 && bytes.length != 0x400000)
            throw new IllegalArgumentException("Expected a 2 or 4 MiB S3/S&K image: " + path.getFileName());
        // The byte-array ROM has no file channel. Its backing data remains
        // live for lazy local-voice lookups and is owned by this image.
        var rom = Rom.fromReader(RomByteReader.fromBytes(bytes), path.getFileName().toString());
        var profile = Sonic3kAudioResourceProfile.fromRom(rom);
        var identity = switch (profile) {
            case LOCKED_ON -> RomIdentity.S3K;
            case SONIC_3 -> RomIdentity.S3;
            case SONIC_KNUCKLES -> RomIdentity.SK;
        };
        boolean known = RomIdentityTable.verify(identity, RomFingerprint.of(bytes)).isPresent();
        return new Image(new RomSource(rom, profile), known);
    }

    @Override public void preflight(CompiledProject project) {
        S3kBinary.validateAndCopy(project);
    }

    @Override public void render(CompiledProject project, Path output, int frames) throws IOException {
        try (var target = new ExportTarget(output, false)) {
            WaveFile.render(project, target.path(), frames);
            target.publish();
        }
    }
}
