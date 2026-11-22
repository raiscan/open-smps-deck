package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.opensmpsdeck.headless.rompatch.RomPatch;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

record RomPatchInput(CompiledProject compiled, List<Integer> relocations) {
    static RomPatchInput load(Path path) throws IOException {
        if (!path.getFileName().toString().endsWith(".smps.zip")) {
            var project = ProjectFiles.load(path).project();
            try {
                return new RomPatchInput(ProjectCompiler.compileWithAssets(project), List.of());
            } catch (IllegalArgumentException failure) {
                if (!failure.getMessage().contains("32 KiB")) throw failure;
                throw new IllegalArgumentException(("Compiled song exceeds 32768 bytes (32 KB per-song limit). "
                        + "Largest placeable bank capacity is %d bytes. Shorten the song or reduce layering.")
                        .formatted(RomPatch.largestBankCapacity()), failure);
            }
        }
        try (var zip = new ZipFile(path.toFile())) {
            var manifest = JsonParser.parseString(new String(entry(zip, "manifest.json", 1024 * 1024),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"S3K".equals(manifest.get("mode").getAsString()))
                throw new IllegalArgumentException("ROM patch export requires an S3K package");
            // The legacy archive's native bytecode is authoritative across compiler versions.
            // Its source project supplies the asset bindings.
            var trackRef = manifest.getAsJsonObject("track");
            byte[] track = checked(zip, trackRef, ProjectCompiler.MAX_BANK_BYTES);
            byte[] source = checked(zip, manifest.getAsJsonObject("source"), 16 * 1024 * 1024);
            var project = ProjectFormat.read(new String(source, StandardCharsets.UTF_8));
            var nativeProgram = project.nativeProgram();
            if (manifest.get("bankBase").getAsInt() != (nativeProgram == null ? 0 : nativeProgram.bankBase()))
                throw new IllegalArgumentException("Package track and source have different bank bases");
            var offsets = manifest.has("relocations") ? manifest.getAsJsonArray("relocations")
                    : trackRef.getAsJsonArray("relocations");
            var relocations = offsets == null ? List.<Integer>of()
                    : offsets.asList().stream().map(value -> value.getAsBigDecimal().intValueExact()).toList();
            return new RomPatchInput(new CompiledProject(track, project.envelopes(), project.samples(),
                    nativeProgram == null ? null : nativeProgram.withBank(track),
                    nativeProgram == null ? List.of() : project.voices()), relocations);
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException
                 | ArithmeticException malformed) {
            throw new IOException("Invalid S3K package manifest or source", malformed);
        }
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
