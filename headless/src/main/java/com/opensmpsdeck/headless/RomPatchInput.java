package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rompatch.RomPatch;
import com.opensmpsdeck.headless.rompatch.S3kRomTarget;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

record RomPatchInput(RomGame game, S3kRomTarget.Song song) {
    static RomPatchInput compile(StudioProject project) {
        var game = TestInGame.game(project);
        TestInGame.requireAvailable(game);
        try {
            return new RomPatchInput(game, new S3kRomTarget.Song(ProjectCompiler.compileWithAssets(project), List.of()));
        } catch (IllegalArgumentException failure) {
            var limit = ProjectCompiler.BankLimitException.from(failure);
            if (limit == null) throw failure;
            throw new IllegalArgumentException(("Compiled song is %,d bytes, over the 32,768-byte (32 KB) per-song limit, "
                    + "after loops and calls. The largest S3K bank has %,d free bytes before assets. "
                    + "Shorten the song or reduce layering.")
                    .formatted(limit.compiledBytes(), RomPatch.largestBankCapacity()), failure);
        }
    }

    static RomPatchInput load(Path path) throws IOException {
        if (!path.getFileName().toString().endsWith(".smps.zip")) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Expected a regular project file: " + path);
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                var source = StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(ProjectFiles.readSource(input))).toString();
                return compile(readProject(source));
            }
        }
        try (var zip = new ZipFile(path.toFile())) {
            var manifest = JsonParser.parseString(new String(entry(zip, "manifest.json", 1024 * 1024),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            var game = checkDriver(manifest);
            if (!manifest.has("track") || !manifest.has("source")) {
                throw new IllegalArgumentException("Test in game requires a legacy SMPS package with track and source entries. "
                        + "Export from the original project instead.");
            }
            // The legacy archive's native bytecode is authoritative across compiler versions.
            // Its source project supplies the asset bindings.
            var trackRef = manifest.getAsJsonObject("track");
            byte[] track = checked(zip, trackRef, ProjectCompiler.MAX_BANK_BYTES);
            byte[] source = checked(zip, manifest.getAsJsonObject("source"), 16 * 1024 * 1024);
            var project = readProject(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString());
            var nativeProgram = project.nativeProgram();
            if (manifest.get("bankBase").getAsInt() != (nativeProgram == null ? 0 : nativeProgram.bankBase()))
                throw new IllegalArgumentException("Package track and source have different bank bases");
            var offsets = manifest.has("relocations") ? manifest.getAsJsonArray("relocations")
                    : trackRef.getAsJsonArray("relocations");
            var relocations = offsets == null ? List.<Integer>of()
                    : offsets.asList().stream().map(value -> value.getAsBigDecimal().intValueExact()).toList();
            var compiled = new CompiledProject(track, project.envelopes(), project.samples(),
                    nativeProgram == null ? null : nativeProgram.withBank(track),
                    nativeProgram == null ? List.of() : project.voices());
            return new RomPatchInput(game, new S3kRomTarget.Song(compiled, relocations));
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException
                 | ArithmeticException malformed) {
            throw new IOException("Invalid S3K package manifest or source", malformed);
        }
    }

    private static StudioProject readProject(String source) throws IOException {
        try {
            checkDriver(JsonParser.parseString(source).getAsJsonObject());
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IOException("Invalid project JSON: " + malformed.getMessage(), malformed);
        }
        return ProjectFormat.read(source);
    }

    private static RomGame checkDriver(JsonObject source) {
        RomGame game = RomGame.S3K;
        // Portable packages declare driver. Legacy packages and project JSON use mode.
        for (String field : List.of("driver", "mode")) {
            if (source.has(field)) {
                var value = source.get(field);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Expected a string " + field + " for Test in game.");
                game = TestInGame.game(value.getAsString());
                TestInGame.requireAvailable(game);
            }
        }
        return game;
    }

    private static byte[] checked(ZipFile zip, JsonObject ref, int limit) throws IOException {
        if (ref == null) throw new IllegalArgumentException("ROM patch requires a legacy package with track and source entries");
        String path = ref.get("path").getAsString();
        byte[] data = entry(zip, path, limit);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if (!digest.equals(ref.get("sha256").getAsString()))
                throw new IllegalArgumentException("Package hash does not match " + path);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        return data;
    }

    private static byte[] entry(ZipFile zip, String name, int limit) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing package entry " + name);
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Package entry exceeds size limit: " + name);
            return bytes;
        }
    }
}
