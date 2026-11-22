package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.RomPatchCommand;
import com.opensmpsdeck.headless.rompatch.RomPatch;

import java.io.IOException;

import static com.opensmpsdeck.headless.mcp.McpArguments.flag;
import static com.opensmpsdeck.headless.mcp.McpArguments.string;

final class McpRomPatch {
    private McpRomPatch() {}

    static JsonObject export(JsonObject args) throws IOException {
        int slot = args.has("slot") ? args.get("slot").getAsBigDecimal().intValueExact() : RomPatch.DEFAULT_SLOT;
        return RomPatchCommand.export(McpPaths.project(string(args, "project")), McpPaths.input(string(args, "rom")),
                McpPaths.absolute(string(args, "output")), new RomPatch.Options(slot, flag(args, "titleMusic"),
                        flag(args, "noDemo"), flag(args, "loop")), flag(args, "force"));
    }
}
