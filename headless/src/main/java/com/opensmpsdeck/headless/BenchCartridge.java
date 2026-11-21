package com.opensmpsdeck.headless;

import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.session.SmpsPhysicalPolicy;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.audio.Sonic1SmpsCompatibilityPolicy;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2SmpsCompatibilityPolicy;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.opensmpsdeck.project.ProjectCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Private benchmark inputs. Earlier dialects never pass through the S3K project compiler. */
public final class BenchCartridge implements AutoCloseable {
    public enum Game {
        s1, s2, s3k;

        public List<Integer> ladderSongs() {
            return switch (this) {
                case s1 -> List.of(0x81, 0x83, 0x84, 0x86);
                case s2 -> List.of(0x81, 0x82, 0x83, 0x8c);
                case s3k -> List.of(0x01, 0x04, 0x0b, 0x15);
            };
        }
    }

    @FunctionalInterface
    public interface Render {
        void write(Path path, int frames, S3kRenderer.ChannelMask mask) throws IOException;
    }

    @FunctionalInterface
    public interface Sequencer {
        SmpsSequencer create(MusicRestoreSink restore);
    }

    public record Song(String name, Render render, Sequencer sequencer, DacData dac) {}

    private final Path path;
    private final Game game;
    private final Rom rom;
    private final com.openggf.audio.smps.SmpsLoader loader;
    private final DacData dac;
    private final Map<Integer, byte[]> sonic2Envelopes;

    public BenchCartridge(Path path, Game game) throws IOException {
        this.path = path;
        this.game = game;
        if (game == Game.s3k) {
            rom = null;
            loader = null;
            dac = null;
            sonic2Envelopes = Map.of();
            return;
        }
        int size = game == Game.s1 ? 0x80000 : 0x100000;
        byte[] image;
        try (var stream = Files.newInputStream(path)) {
            image = stream.readNBytes(size + 1);
        }
        String expected = game == Game.s1 ? "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b"
                : "8bca5dcef1af3e00098666fd892dc1c2a76333f9";
        if (image.length != size || !expected.equals(sha1(image)))
            throw new IOException("Unsupported cartridge image. " + game + " requires the verified REV01 ROM");
        sonic2Envelopes = game == Game.s2 ? Sonic2RomEnvelopes.extract(image) : Map.of();
        rom = Rom.fromReader(RomByteReader.fromBytes(image), path.getFileName().toString());
        loader = game == Game.s1 ? new Sonic1SmpsLoader(rom) : new Sonic2SmpsLoader(rom);
        dac = loader.loadDacData();
    }

    public Song read(int id) throws IOException {
        if (game == Game.s3k) {
            var project = S3kRomImporter.readSong(path, id);
            var source = ProjectCompiler.compileWithAssets(project);
            return new Song(project.name(), (file, frames, mask) -> WaveFile.write(file, frames,
                    sink -> S3kRenderer.render(source, frames, mask, sink)),
                    restore -> S3kRenderer.timelineSequencer(source, restore, new Sonic3kCoordFlagHandler()),
                    S3kRenderer.nativeDac(source.samples(), source.nativeProgram()));
        }
        Map<Integer, String> titles = game == Game.s1 ? Sonic1Music.titleMap() : Sonic2Music.titleMap();
        String title = titles.get(id);
        if (title == null) throw new IllegalArgumentException("Unknown " + game + " music ID: 0x%02x".formatted(id));
        SmpsSequencerConfig config = game == Game.s1 ? Sonic1SmpsSequencerConfig.CONFIG : Sonic2SmpsSequencerConfig.CONFIG;
        SmpsPhysicalPolicy policy = game == Game.s1 ? Sonic1SmpsCompatibilityPolicy.INSTANCE
                : Sonic2SmpsCompatibilityPolicy.INSTANCE;
        AbstractSmpsData data = loader.loadMusic(id);
        if (data == null) throw new IOException("Missing " + game + " music: " + title);
        if (data instanceof Sonic2SmpsData sonic2) sonic2.setPsgEnvelopes(sonic2Envelopes);
        return new Song(title, (file, frames, mask) -> WaveFile.write(file, frames,
                sink -> S3kRenderer.renderMusic(data, dac, config, policy, frames, mask, sink)),
                restore -> new SmpsSequencer(data, dac, restore, config), dac);
    }

    @Override public void close() throws IOException {
        if (rom != null) rom.close();
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
