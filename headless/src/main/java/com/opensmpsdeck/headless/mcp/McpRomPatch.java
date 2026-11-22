package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.TestInGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rompatch.RomPatch;

import java.io.IOException;
import java.nio.file.Path;

import static com.opensmpsdeck.headless.mcp.McpArguments.flag;
import static com.opensmpsdeck.headless.mcp.McpArguments.string;

final class McpRomPatch {
    private McpRomPatch() {}

    static JsonObject export(JsonObject args) throws IOException {
        int slot = RomPatch.DEFAULT_SLOT;
        if (args.has("slot")) {
            try {
                slot = args.get("slot").getAsBigDecimal().intValueExact();
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Music ID must be in 0x01–0x33");
            }
        }
        var options = new RomPatch.Options(slot, flag(args, "titleMusic"), flag(args, "noDemo"), flag(args, "loop"));
        var outputs = new TestInGame.Outputs(path(args, "out"), path(args, "bps"), flag(args, "force"));
        return TestInGame.export(RomLibrary.userLibrary(), McpPaths.project(string(args, "project")),
                path(args, "rom"), outputs, options);
    }

    private static Path path(JsonObject args, String field) {
        return args.has(field) ? McpPaths.absolute(string(args, field)) : null;
    }
}
