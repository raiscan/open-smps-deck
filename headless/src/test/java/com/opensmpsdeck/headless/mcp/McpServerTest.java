package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.headless.S3kRomImporter;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {
    @TempDir Path directory;

    @Test void romPatchAcceptsOptionalRomAndValidatesOutputAndDriver() throws Exception {
        Path source = directory.resolve("song.smps.json");
        ProjectFiles.save(source, DemoProject.create(), null);
        assertToolError("rom_patch", Map.of("project", source.toString()), "--out or --bps");
        assertToolError("rom_patch", Map.of("project", source.toString(), "output", "old.gen"), "unknown argument");
        assertToolError("rom_patch", Map.of("project", source.toString(), "bps", "relative.bps"), "absolute path");
        assertToolError("rom_patch", Map.of("project", source.toString(), "bps", directory.resolve("song.bps").toString(),
                "slot", 52), "Music ID must be in");

        Path archive = directory.resolve("other.smps.zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            var entry = new java.util.zip.ZipEntry("manifest.json");
            entry.setTime(0);
            zip.putNextEntry(entry);
            zip.write("{\"schemaVersion\":4,\"driver\":\"s2\"}".getBytes(UTF_8));
        }
        assertToolError("rom_patch", Map.of("project", archive.toString(),
                "out", directory.resolve("song.gen").toString()), "manifest");
        assertToolError("rom_patch", Map.of("project", source.toString(), "driver", "s2",
                "out", directory.resolve("song.gen").toString(), "slot", 37), "Sonic 2 music ID");
    }

    @Test void stdioUsesOneUtf8JsonMessagePerLineAndRecoversFromInvalidFrames() throws Exception {
        var responses = exchange("""
                Content-Length: 12
                {"jsonrpc":"2.0","id":2,"method":"ping"} garbage
                [{"jsonrpc":"2.0","id":3,"method":"ping"}]
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}
                {"jsonrpc":"2.0","id":"café","method":"ping"}
                {"jsonrpc":"2.0","id":4,"method":"unknown"}
                """);
        assertEquals(5, responses.size());
        assertEquals(-32700, errorCode(responses.get(0)));
        assertEquals(-32700, errorCode(responses.get(1)));
        assertEquals(-32600, errorCode(responses.get(2)));
        assertEquals("café", responses.get(3).get("id").getAsString());
        assertEquals(new JsonObject(), responses.get(3).getAsJsonObject("result"));
        assertEquals(-32601, errorCode(responses.get(4)));
    }

    @Test void negotiatesLegacyAndValidatesModernMetadata() throws Exception {
        var old = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{},"capabilities":{}}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """);
        assertEquals(2, old.size());
        assertEquals("2025-06-18", old.getFirst().getAsJsonObject("result").get("protocolVersion").getAsString());
        var tools = old.getLast().getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(35, tools.size());
        var names = tools.asList().stream().map(tool -> tool.getAsJsonObject().get("name").getAsString()).toList();
        assertTrue(names.containsAll(List.of("reconstruct_describe", "reconstruct_vocal_review", "reconstruct_trace", "reconstruct_trace_diff",
                "reconstruct_edit", "reconstruct_voices", "reconstruct_sections", "reconstruct_plan_template", "reconstruct_view",
                "transcribers_list", "separators_list", "separate", "project_export", "reconstruct_rerun", "package_validate", "package_inspect",
                "rom_list", "rom_set", "rom_patch")));

        var modern = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2099-01-01","io.modelcontextprotocol/clientCapabilities":{}}}}
                {"jsonrpc":"2.0","id":2,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}
                {"jsonrpc":"2.0","id":3,"method":"tools/list"}
                """);
        assertEquals(-32022, errorCode(modern.getFirst()));
        var discovery = modern.get(1).getAsJsonObject("result");
        assertEquals("2026-07-28", discovery.getAsJsonArray("supportedVersions").get(0).getAsString());
        assertEquals("complete", discovery.get("resultType").getAsString());
        assertTrue(discovery.getAsJsonObject("_meta").has("io.modelcontextprotocol/serverInfo"));
        assertEquals(-32602, errorCode(modern.getLast()));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S3K_ROM", matches = ".+")
    void exportsAndValidatesRetailPatchOverMcp() throws Exception {
        Path rom = Path.of(System.getenv("OPENSMPSDECK_S3K_ROM"));
        Path source = directory.resolve("retail.smps.json");
        ProjectFiles.save(source, S3kRomImporter.readSong(rom, 13), null);
        var exported = call("project_export", Map.of("project", source.toString(), "output", "retail.smps.zip",
                "format", "portable", "retailPatch", true, "roms", List.of(rom.toString())));
        String output = exported.get("output").getAsString();
        assertEquals(List.of("DERIVED_FROM_RETAIL_SONG"), exported.getAsJsonArray("warnings").asList().stream()
                .map(value -> value.getAsString()).toList());
        assertEquals("patch", call("package_inspect", Map.of("package", output))
                .getAsJsonObject("bank").get("form").getAsString());
        assertTrue(call("package_validate", Map.of("package", output, "roms", List.of(rom.toString())))
                .get("playbackValidated").getAsBoolean());
    }

    @Test void notesAreVisibleToProjectFilesOnReloadAndAcrossIndependentServers() throws Exception {
        Path path = directory.resolve("song.smps.json");
        ProjectFiles.save(path, DemoProject.create(), null);
        var add = call("notes_add", Map.of("project", path.toString(), "at", 1.125,
                "lane", "FM1", "text", "Less ring\nCheck the tail", "author", "Reviewer"));
        String id = add.get("id").getAsString();

        var loaded = ProjectFiles.load(path);
        assertEquals(id, loaded.project().notes().getFirst().id());
        assertEquals("Less ring\nCheck the tail", loaded.project().notes().getFirst().text());

        var open = call("notes_list", Map.of("project", path.toString(), "openOnly", true));
        assertEquals(1, open.getAsJsonArray("notes").size());
        call("notes_resolve", Map.of("project", path.toString(), "id", id,
                "reply", "Adjusted the gate.", "author", "Agent"));
        var note = ProjectFiles.load(path).project().notes().getFirst();
        assertFalse(note.isOpen());
        assertEquals("Adjusted the gate.", note.reply().text());
        assertTrue(call("notes_list", Map.of("project", path.toString(), "openOnly", true))
                .getAsJsonArray("notes").isEmpty());
    }

    @Test void invalidArgumentsAndManagedSymlinksAreToolErrors() throws Exception {
        Path score = directory.resolve("outside.json");
        Files.writeString(score, "{}");
        Path work = Files.createDirectory(directory.resolve("work"));
        Files.createSymbolicLink(work.resolve("score.json"), score);
        assertToolError("artifact_write", Map.of("workDir", work.toString(), "artifact", "score",
                "value", Map.of("version", 1)), "regular file");
        assertToolError("artifact_read", Map.of("workDir", work.toString(), "artifact", "../outside"), "one of");
        assertToolError("reconstruct_describe", Map.of("workDir", work.toString()), "regular file");
        assertToolError("reconstruct_trace", Map.of("workDir", work.toString()), "regular file");
        assertToolError("reconstruct_trace_diff", Map.of("oldWorkDir", work.toString(),
                "newWorkDir", work.toString()), "regular file");
        assertToolError("reconstruct_plan_template", Map.of("workDir", work.toString()), "regular file");
        assertToolError("project_validate", Map.of("project", directory.resolve("missing").toString()), "existing project");
        assertToolError("render_clip", Map.of("project", score.toString(), "output", "clip.wav",
                "start", "0", "end", 1), "expected number");
        assertEquals("{}", Files.readString(score));
    }

    @Test void reconstructionImportAndTranscribePassTheTargetThrough() throws Exception {
        var folder = Files.createDirectory(directory.resolve("download"));
        Path midi = folder.resolve("song.mid"), wav = folder.resolve("mix.wav");
        var sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        var track = sequence.createTrack();
        track.add(new javax.sound.midi.MidiEvent(
                new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_ON, 0, 69, 96), 0));
        track.add(new javax.sound.midi.MidiEvent(
                new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_OFF, 0, 69, 0), 480));
        javax.sound.midi.MidiSystem.write(sequence, 0, midi.toFile());
        com.opensmpsdeck.headless.WaveFile.write(wav, 60, sink -> {
            var samples = new short[1_600];
            java.util.Arrays.fill(samples, (short) 1024);
            for (int i = 0; i < 60; i++) sink.accept(samples);
        });
        var initial = toolResult("reconstruct_import", Map.of("folder", folder.toString(),
                "workDir", directory.resolve("import").toString(), "target", "s2"));
        assertFalse(initial.get("isError").getAsBoolean(), initial::toString);
        assertEquals("s2", initial.getAsJsonObject("structuredContent").getAsJsonObject("runRequest")
                .getAsJsonObject("options").get("target").getAsString());

        Path catalogue = directory.resolve("index.json");
        Files.writeString(catalogue, "{}"); // Transcribe does not open the catalogue.
        Path work = directory.resolve("work");
        var build = toolResult("reconstruct_stage", Map.of("stage", "transcribe", "workDir", work.toString(),
                "inputs", Map.of("midi", midi.toString(), "wav", wav.toString(), "catalogue", catalogue.toString()),
                "options", Map.of("target", "s2")));
        assertFalse(build.get("isError").getAsBoolean(), build::toString);
        assertEquals("s2", JsonParser.parseString(Files.readString(work.resolve("score.json"))).getAsJsonObject()
                .getAsJsonObject("inputs").get("target").getAsString());
    }

    @Test void portableProjectExportUsesTheSavedTargetByDefault() throws Exception {
        Path source = directory.resolve("target.smps.json");
        Files.writeString(source, ProjectFormat.write(DemoProject.create().withTarget(TargetGame.S1)));
        var result = toolResult("project_export", Map.of("project", source.toString(), "format", "portable",
                "output", "target.smps.zip"));
        assertFalse(result.get("isError").getAsBoolean(), result::toString);
        var manifest = com.opensmpsdeck.headless.PortablePackages.inspect(directory.resolve("target.smps.zip"));
        assertEquals("s1", manifest.get("driver").getAsString());
    }

    @Test void bankLimitErrorsCarryTheCompiledAndLimitByteCounts() throws Exception {
        var demo = DemoProject.create();
        var sequence = demo.sequences().getFirst();
        var large = new StudioProject(demo.formatVersion(), "Oversize", demo.mode(), demo.ticksPerBeat(), demo.tempo(),
                demo.voices(), List.of(sequence), List.of(new Clip("clip", sequence.id(), "FM1", 0, Integer.MAX_VALUE)));
        var limit = assertThrows(ProjectCompiler.BankLimitException.class, () -> ProjectCompiler.compile(large));
        Path source = directory.resolve("oversize.smps.json");
        Files.writeString(source, ProjectFormat.write(large));
        var validation = toolResult("project_validate", Map.of("project", source.toString()));
        var exported = toolResult("project_export", Map.of("project", source.toString(), "format", "package",
                "output", "oversize.smps.zip"));
        for (var result : List.of(validation, exported)) {
            assertTrue(result.get("isError").getAsBoolean(), result::toString);
            var failure = result.getAsJsonObject("structuredContent");
            assertEquals(limit.getMessage(), failure.get("error").getAsString());
            assertEquals(limit.compiledBytes(), failure.get("compiledBytes").getAsLong());
            assertEquals(32_768, failure.get("limitBytes").getAsInt());
        }
        assertFalse(Files.exists(directory.resolve("oversize.smps.zip")));
    }

    private static int errorCode(JsonObject response) {
        assertTrue(response.get("id").isJsonNull() || response.has("error"));
        return response.getAsJsonObject("error").get("code").getAsInt();
    }

    private static JsonObject call(String name, Map<String, ?> arguments) throws Exception {
        var result = toolResult(name, arguments);
        assertFalse(result.get("isError").getAsBoolean(), result::toString);
        return result.getAsJsonObject("structuredContent");
    }

    private static void assertToolError(String name, Map<String, ?> arguments, String message) throws Exception {
        var result = toolResult(name, arguments);
        assertTrue(result.get("isError").getAsBoolean(), result::toString);
        assertTrue(result.getAsJsonObject("structuredContent").get("error").getAsString().contains(message),
                result::toString);
    }

    private static JsonObject toolResult(String name, Map<String, ?> arguments) throws Exception {
        String input = McpJson.JSON.toJson(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments))) + "\n";
        return exchange(input).getFirst().getAsJsonObject("result");
    }

    private static List<JsonObject> exchange(String input) throws Exception {
        var output = new ByteArrayOutputStream();
        McpServer.run(new ByteArrayInputStream(input.getBytes(UTF_8)), new PrintStream(output, false, UTF_8));
        return output.toString(UTF_8).lines().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }
}
