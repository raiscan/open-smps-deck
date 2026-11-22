package com.opensmpsdeck.headless.rompatch;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomTarget;

/** Adapts the Sonic 1 engine to verified ROM export. */
public final class S1RomTarget {
    public static final RomTarget<S1Song, S1RomPatch.Options, JsonObject> TARGET =
            new RomTarget<>(RomGame.S1, (clean, song, options) -> {
                var patched = S1RomPatch.patch(clean.bytes(), song, options);
                return new RomTarget.PatchedRom<>(patched.bytes(), patched.report());
            });

    private S1RomTarget() {}
}
