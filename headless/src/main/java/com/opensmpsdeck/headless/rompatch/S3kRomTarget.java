package com.opensmpsdeck.headless.rompatch;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomTarget;
import com.opensmpsdeck.project.CompiledProject;

import java.util.List;

/** Adapts the in-memory S3K patcher to the verified ROM export contract. */
public final class S3kRomTarget {
    public record Song(CompiledProject compiled, List<Integer> relocations) {
        public Song {
            relocations = List.copyOf(relocations);
        }
    }

    public static final RomTarget<Song, RomPatch.Options, JsonObject> TARGET =
            new RomTarget<>(RomGame.S3K, (clean, song, options) -> {
                var patched = RomPatch.patch(clean.bytes(), song.compiled(), options, song.relocations());
                return new RomTarget.PatchedRom<>(patched.bytes(), patched.report());
            });

    private S3kRomTarget() {}
}
