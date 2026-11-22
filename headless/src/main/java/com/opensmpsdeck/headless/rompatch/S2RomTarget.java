package com.opensmpsdeck.headless.rompatch;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomTarget;

public final class S2RomTarget {
    public static final RomTarget<S2Song, S2RomPatch.Options, JsonObject> TARGET = new RomTarget<>(RomGame.S2,
            (clean, song, options) -> {
                var result = S2RomPatch.patch(clean.bytes(), song, options);
                return new RomTarget.PatchedRom<>(result.bytes(), result.report());
            });

    private S2RomTarget() {}
}
