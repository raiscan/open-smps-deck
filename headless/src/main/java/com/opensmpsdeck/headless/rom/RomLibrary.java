package com.opensmpsdeck.headless.rom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.opensmpsdeck.headless.UserConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Path-only ROM settings, shared by CLI, MCP and Studio. */
public final class RomLibrary {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path config;

    public RomLibrary(Path config) {
        this.config = config.toAbsolutePath();
    }

    public static RomLibrary userLibrary() {
        return new RomLibrary(UserConfig.file("roms.json"));
    }

    public record Entry(String game, String status, String path, String revision, String detail) {}

    public Entry set(RomGame game, Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        var verified = VerifiedRom.read(game, absolute);
        update(game, absolute);
        return ok(game, absolute, verified.revision());
    }

    public void clear(RomGame game) throws IOException {
        update(game, null);
    }

    /** A non-null override bypasses settings. Both paths are verified on every resolution. */
    public VerifiedRom resolve(RomGame game, Path override) throws IOException {
        Path path = override != null ? override : configuredPath(read(), game);
        if (path == null) {
            throw new IllegalArgumentException("No " + game.id() + " ROM configured. Use rom set "
                    + game.id() + " PATH or supply --rom PATH.");
        }
        return VerifiedRom.read(game, path);
    }

    public List<Entry> list() throws IOException {
        JsonObject settings = read();
        var entries = new ArrayList<Entry>();
        for (RomGame game : RomGame.values()) {
            Path path = configuredPath(settings, game);
            if (path == null) {
                entries.add(new Entry(game.id(), "missing", null, null, null));
                continue;
            }
            try {
                entries.add(ok(game, path, VerifiedRom.read(game, path).revision()));
            } catch (IOException | IllegalArgumentException e) {
                entries.add(new Entry(game.id(), "changed-on-disk", path.toString(), null, e.getMessage()));
            }
        }
        return List.copyOf(entries);
    }

    private static Entry ok(RomGame game, Path path, RomRevision revision) {
        return new Entry(game.id(), "ok", path.toString(), revision.displayName(), null);
    }

    private JsonObject read() throws IOException {
        try (var reader = Files.newBufferedReader(config)) {
            JsonObject settings = JSON.fromJson(reader, JsonObject.class);
            if (settings == null) throw new JsonParseException("expected a JSON object");
            return settings;
        } catch (NoSuchFileException e) {
            return new JsonObject();
        } catch (JsonParseException e) {
            throw new IOException("Invalid ROM settings in " + config + ": " + e.getMessage(), e);
        }
    }

    private Path configuredPath(JsonObject settings, RomGame game) throws IOException {
        var value = settings.get(game.id());
        if (value == null) return null;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            try {
                Path path = Path.of(value.getAsString());
                if (path.isAbsolute()) return path;
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid path for " + game.id() + " in " + config, e);
            }
        }
        throw new IOException("Expected an absolute ROM path for " + game.id() + " in " + config);
    }

    private void update(RomGame game, Path path) throws IOException {
        // The file lock serializes CLI, MCP and Studio processes. FileLock itself does
        // not block on overlapping locks in this JVM, so serialize those callers too.
        synchronized (RomLibrary.class) {
            Files.createDirectories(config.getParent());
            Path lockFile = config.resolveSibling(config.getFileName() + ".lock");
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                JsonObject settings = read();
                if (path == null) {
                    if (settings.remove(game.id()) == null) return;
                } else {
                    settings.addProperty(game.id(), path.toString());
                }
                try (var output = new AtomicRomOutput(config, true)) {
                    Files.writeString(output.path(), JSON.toJson(settings) + "\n");
                    output.publish();
                }
            }
        }
    }
}
