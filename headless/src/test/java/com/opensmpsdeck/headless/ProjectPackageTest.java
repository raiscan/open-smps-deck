package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.project.*;
import com.opensmpsdeck.project.Sequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectPackageTest {
    @TempDir Path directory;

    @Test void exportContainsReproducibleNativeBytesSourceEveryAssetAndVerifiedManifest() throws Exception {
        StudioProject project = fixture();
        Path first = directory.resolve("first.smps.zip"), second = directory.resolve("second.smps.zip");
        ProjectPackage.write(project, first); ProjectPackage.write(project, second);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second), "paths and clock must not affect the archive");
        Map<String, byte[]> entries = read(first);
        assertEquals(List.of("track.bin", "source.smps.json", "manifest.json", "psg/001.bin", "mod/001.bin", "psg/002.bin"),
                List.copyOf(entries.keySet()), "all declared assets, including unused ones, retain authored order");
        assertArrayEquals(ProjectCompiler.compile(project), entries.get("track.bin"));
        assertEquals(ProjectFormat.write(project), new String(entries.get("source.smps.json"), StandardCharsets.UTF_8));
        assertEquals(project, ProjectFormat.read(new String(entries.get("source.smps.json"), StandardCharsets.UTF_8)));
        assertArrayEquals(new byte[]{0, 1, (byte) 0x83}, entries.get("psg/001.bin"));
        assertArrayEquals(new byte[]{0, 2, (byte) 0x81}, entries.get("mod/001.bin"));
        assertArrayEquals(new byte[]{2, 1, (byte) 0x80}, entries.get("psg/002.bin"));

        JsonObject manifest = JsonParser.parseString(new String(entries.get("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, manifest.get("schemaVersion").getAsInt());
        assertFalse(manifest.has("dac"));
        assertEquals("S3K", manifest.get("mode").getAsString());
        assertEquals(0, manifest.get("bankBase").getAsInt());
        assertReference(manifest.getAsJsonObject("track"), entries);
        assertReference(manifest.getAsJsonObject("source"), entries);
        var voice = manifest.getAsJsonArray("voices").get(0).getAsJsonObject();
        assertEquals("native/../voice", voice.get("id").getAsString());
        assertEquals("Native voice ☃", voice.get("name").getAsString());
        assertEquals(0, voice.get("index").getAsInt());
        var envelopes = manifest.getAsJsonArray("envelopes");
        assertEquals(3, envelopes.size());
        for (int position = 0; position < envelopes.size(); position++) {
            JsonObject envelope = envelopes.get(position).getAsJsonObject();
            assertReference(envelope, entries);
            assertEquals(project.envelopes().get(position).id(), envelope.get("id").getAsString());
            assertEquals(project.envelopes().get(position).name(), envelope.get("name").getAsString());
            assertEquals(project.envelopes().get(position).type(), envelope.get("kind").getAsString());
            assertEquals(position == 2 ? 2 : 1, envelope.get("index").getAsInt());
        }
        assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("..") || name.startsWith("/")),
                "asset IDs and names must never become filesystem paths");
    }

    @Test void dacPackageKeepsNativeOrdinalsRatesAndRawPcmEvenForUnusedAssets() throws Exception {
        var original = fixture();
        var samples = List.of(
                DacSample.of("../../unused", "Unused / PCM", 0, new byte[]{(byte) 0x80, (byte) 0xFF}),
                DacSample.of("hit", "Hit", 255, new byte[]{0, 17, (byte) 0xF0}));
        var drums = new Sequence("drums", "Drums", "DAC", 6, null, 127, List.of(), List.of(),
                "RESET", null, null, List.of(new SampleTrigger("one", 0, "hit")));
        var project = new StudioProject(4, original.name(), "S3K", 24, 0, original.voices(),
                List.of(original.sequences().getFirst(), drums),
                List.of(original.clips().getFirst(), new Clip("drum-clip", "drums", "DAC", 0, 6)),
                original.envelopes(), samples);
        Path archive = directory.resolve("dac.zip"), second = directory.resolve("dac-again.zip");
        ProjectPackage.write(project, archive);
        ProjectPackage.write(project, second);
        assertArrayEquals(Files.readAllBytes(archive), Files.readAllBytes(second));
        var entries = read(archive);
        assertEquals(List.of("track.bin", "source.smps.json", "manifest.json", "psg/001.bin", "mod/001.bin",
                "psg/002.bin", "dac/000.pcm", "dac/001.pcm"), List.copyOf(entries.keySet()));
        byte[] bank = entries.get("track.bin");
        int dacPointer = (bank[6] & 0xFF) | ((bank[7] & 0xFF) << 8);
        assertEquals(0x82, bank[dacPointer] & 0xFF, "the second declaration maps to the second DAC note");
        assertEquals(project, ProjectFormat.read(new String(entries.get("source.smps.json"), StandardCharsets.UTF_8)));
        var manifest = JsonParser.parseString(new String(entries.get("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(2, manifest.get("schemaVersion").getAsInt());
        assertReference(manifest.getAsJsonObject("track"), entries);
        assertReference(manifest.getAsJsonObject("source"), entries);
        var dac = manifest.getAsJsonObject("dac");
        assertEquals(297, dac.get("baseCycles").getAsInt());
        var mappings = dac.getAsJsonArray("samples");
        assertEquals(2, mappings.size());
        for (int index = 0; index < samples.size(); index++) {
            var sample = samples.get(index);
            var mapping = mappings.get(index).getAsJsonObject();
            String path = index == 0 ? "dac/000.pcm" : "dac/001.pcm";
            assertReference(mapping, entries);
            assertEquals(path, mapping.get("path").getAsString());
            assertEquals(sample.id(), mapping.get("id").getAsString());
            assertEquals(sample.name(), mapping.get("name").getAsString());
            assertEquals(index, mapping.get("sampleId").getAsInt());
            assertEquals(0x81 + index, mapping.get("note").getAsInt());
            assertEquals(sample.rate(), mapping.get("rate").getAsInt());
            assertArrayEquals(sample.pcm(), entries.get(path));
        }
        var missing = new StudioProject(4, project.name(), project.mode(), project.ticksPerBeat(), project.tempo(),
                project.voices(), project.sequences(), project.clips(), project.envelopes(), List.of(samples.getFirst()));
        Path invalid = directory.resolve("missing-hit.zip");
        assertThrows(IllegalArgumentException.class, () -> ProjectPackage.write(missing, invalid));
        assertFalse(Files.exists(invalid));
    }

    @Test void existingFilesAndSymlinkTargetsAreNeverOverwritten() throws Exception {
        Path existing = Files.writeString(directory.resolve("keep.zip"), "keep me");
        assertThrows(FileAlreadyExistsException.class, () -> ProjectPackage.write(fixture(), existing));
        assertEquals("keep me", Files.readString(existing));
        Path link = directory.resolve("alias.zip"); Files.createSymbolicLink(link, existing);
        assertThrows(FileAlreadyExistsException.class, () -> ProjectPackage.write(fixture(), link));
        assertTrue(Files.isSymbolicLink(link)); assertEquals("keep me", Files.readString(existing));
    }

    @Test void invalidResourcesFailBeforePublicationAndCancelledWritesCleanOnlyTheirOutput() throws Exception {
        var original = fixture();
        var invalid = new StudioProject(3, original.name(), "S3K", 24, 0, original.voices(), original.sequences(), original.clips(),
                List.of(new Envelope("mod", "Unsafe", "MODULATION", List.of(0x80))));
        Path absent = directory.resolve("invalid.zip");
        assertThrows(IllegalArgumentException.class, () -> ProjectPackage.write(invalid, absent));
        assertFalse(Files.exists(absent));
        Path cancelled = directory.resolve("cancelled.zip"), unrelated = Files.writeString(directory.resolve("unrelated"), "keep me");
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> ProjectPackage.write(original, cancelled));
        } finally { Thread.interrupted(); }
        assertFalse(Files.exists(cancelled)); assertEquals("keep me", Files.readString(unrelated));
    }

    @Test void cliRequiresPackageEvenForUnusedAssetsAndPreservesSourcesAndExistingOutputs() throws Exception {
        var original = fixture();
        var sequence = original.sequences().getFirst();
        var plain = new Sequence(sequence.id(), sequence.name(), sequence.type(), sequence.lengthTicks(), sequence.voiceId(),
                sequence.volume(), sequence.notes(), sequence.commands(), sequence.repeatState(), null, null);
        var project = new StudioProject(3, original.name(), "S3K", 24, 0, original.voices(), List.of(plain), original.clips(), original.envelopes());
        assertTrue(ProjectCompiler.compileWithAssets(project).requiresAssets());
        String source = ProjectFormat.write(project);
        Path input = Files.writeString(directory.resolve("source.json"), source);
        Path binary = directory.resolve("incomplete.bin");
        Result refused = run("compile-project", input.toString(), binary.toString());
        assertEquals(2, refused.exit()); assertTrue(refused.err().contains("compile-package"), refused.err());
        assertFalse(Files.exists(binary));
        assertEquals(0, run("validate-project", input.toString()).exit());
        Path archive = directory.resolve("complete.zip");
        assertEquals(0, run("compile-package", input.toString(), archive.toString()).exit());
        byte[] before = Files.readAllBytes(archive);
        assertEquals(2, run("compile-package", input.toString(), archive.toString()).exit());
        assertArrayEquals(before, Files.readAllBytes(archive));
        assertEquals(2, run("compile-package", input.toString(), input.toString()).exit());
        assertEquals(source, Files.readString(input));
    }

    private static void assertReference(JsonObject reference, Map<String, byte[]> entries) throws Exception {
        byte[] bytes = entries.get(reference.get("path").getAsString());
        assertNotNull(bytes);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), reference.get("sha256").getAsString());
    }

    private static Map<String, byte[]> read(Path archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertEquals(0L, entry.getTime(), "archive timestamp must be fixed");
                assertEquals(ZipEntry.STORED, entry.getMethod());
                assertNull(entries.put(entry.getName(), zip.readAllBytes()), "duplicate entry");
            }
        }
        return entries;
    }

    private static StudioProject fixture() {
        var voice = new Voice("native/../voice", "Native voice ☃", List.of(7, 1, 2, 3, 4, 31, 31, 31, 31,
                0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15, 0xA0, 0xA8, 0xB0, 0xB8));
        var sequence = new Sequence("fm", "Assets", "FM", 8, voice.id(), 100, List.of(new NoteEvent("note", 0, 8, 60)),
                List.of(), "RESET", null, "mod");
        return new StudioProject(3, "Portable package", "S3K", 24, 0, List.of(voice), List.of(sequence),
                List.of(new Clip("clip", "fm", "FM1", 0, 8)), List.of(
                new Envelope("../../unused", "Unused / PSG ☃", "PSG_VOLUME", List.of(0, 1, 0x83)),
                new Envelope("mod", "Pitch", "MODULATION", List.of(0, 2, 0x81)),
                new Envelope("second", "PSG loop", "PSG_VOLUME", List.of(2, 1, 0x80))));
    }

    private static Result run(String... arguments) {
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
        int exit = Main.run(arguments, new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Result(exit, errors.toString(StandardCharsets.UTF_8));
    }
    private record Result(int exit, String err) {}
}
