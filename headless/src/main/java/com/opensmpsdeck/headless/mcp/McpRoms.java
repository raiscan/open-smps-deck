package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;

import java.io.IOException;
import java.util.Map;

import static com.opensmpsdeck.headless.mcp.McpArguments.string;
import static com.opensmpsdeck.headless.mcp.McpJson.object;

final class McpRoms {
    private McpRoms() {}

    static JsonObject list() throws IOException {
        return object(Map.of("roms", RomLibrary.userLibrary().list()));
    }

    static JsonObject set(JsonObject args) throws IOException {
        var game = RomGame.parse(string(args, "game"));
        var path = McpPaths.absolute(string(args, "path"));
        return object(RomLibrary.userLibrary().set(game, path));
    }
}
