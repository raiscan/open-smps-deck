package com.opensmpsdeck.headless.rom;

import java.util.Objects;

/**
 * Binds a game ID to its patch engine. Each engine chooses its compiled song,
 * options and report types. The engine works in memory with a verified image.
 */
public record RomTarget<S, O, R>(RomGame game, Engine<S, O, R> engine) {
    public RomTarget {
        Objects.requireNonNull(game);
        Objects.requireNonNull(engine);
    }

    @FunctionalInterface
    public interface Engine<S, O, R> {
        PatchedRom<R> patch(VerifiedRom clean, S compiledSong, O options);
    }

    public record PatchedRom<R>(byte[] bytes, R report) {
        public PatchedRom {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() {
            return bytes.clone();
        }
    }

    public PatchedRom<R> patch(VerifiedRom clean, S compiledSong, O options) {
        if (clean.revision().game() != game) {
            throw new IllegalArgumentException("Expected " + game.id() + " ROM, found " + clean.revision().displayName());
        }
        return engine.patch(clean, compiledSong, options);
    }
}
