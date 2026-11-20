package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionDescribe;
import com.opensmpsdeck.headless.ReconstructionStages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.mcp.McpArguments.*;
import static com.opensmpsdeck.headless.mcp.McpJson.object;

final class McpReconstruction {
    static final String[] STAGES = {"transcribe", "structure", "roles", "allocate", "voice", "assemble"};
    static final String[] ARTIFACTS = {"score", "structure", "roles", "allocation", "voicing", "roleplan"};
    static final JsonObject INPUTS = schema(
            field("midi", "string", "Absolute MIDI file path"),
            field("wav", "string", "Absolute aligned mix WAV path"),
            field("catalogue", "string", "Absolute instrument catalogue JSON path"),
            field("stems", object(Map.of("type", "object", "additionalProperties", Map.of("type", "string"))),
                    "Map of MIDI part IDs to absolute aligned stem WAV paths").optional());
    static final JsonObject OPTIONS = schema(
            field("seconds", "number", "Duration from time zero, in (0, 600] seconds").optional(),
            field("name", "string", "Project title").optional(),
            field("ticksPerBeat", "integer", "Studio ruler ticks per beat").optional(),
            field("keepOctaves", "boolean", "Keep octave doubles").optional(),
            field("fmOnly", "boolean", "Disable PSG spill").optional(),
            field("wholePartVoices", "boolean", "Use one FM patch per part").optional(),
            field("audioRefine", "boolean", "Use audio pitch refinement").optional(),
            field("candidates", "string", "Absolute external note candidates JSON path").optional(),
            field("harmonyMaxHz", "number", "Harmony attack cap, 0 to disable. Only option accepted on resume").optional(),
            field("estimateTempo", "boolean", "Infer a musical grid").optional(),
            field("tempo", "number", "Override grid BPM").optional(),
            field("barOffset", "number", "Override bar origin in seconds (with tempo)").optional(),
            field("steadyTempo", "string", "Steady output tempo: off, auto, or BPM").optional(),
            field("audioRepeats", "boolean", "Include audio chroma in repeat matching").optional());

    private static final List<String> FILES = List.of("score.json", "structure.json", "roles.json", "allocation.json",
            "voicing.json", "roleplan.json", "arrangement.smps.json", "arrangement.smps.zip", "render.wav", "report.json", ReconstructionStages.STEADY_REFERENCE);

    private McpReconstruction() {}

    static JsonObject run(JsonObject args, boolean single, ReconstructionStages.Progress progress) throws IOException {
        String command = single ? string(args, "stage") : "run";
        boolean initial = args.has("inputs");
        boolean resume = args.has("from");
        if (!single && initial == resume)
            throw new IllegalArgumentException("Supply inputs for a fresh run or from to resume a work directory");
        if (single && initial != command.equals("transcribe"))
            throw new IllegalArgumentException("Only transcribe takes inputs, and it requires them");

        Path work = initial ? McpPaths.newDirectory(string(args, "workDir"))
                : McpPaths.directory(string(args, "workDir"));
        var cli = new ArrayList<String>();
        if (initial) {
            var inputs = args.getAsJsonObject("inputs");
            for (String key : List.of("midi", "wav", "catalogue")) {
                cli.add(McpPaths.input(string(inputs, key)).toString());
            }
        } else {
            for (String file : FILES) McpPaths.managed(work, file);
            if (resume) {
                cli.add("--from");
                cli.add(string(args, "from"));
            }
        }
        cli.add(work.toString());
        if (initial && args.getAsJsonObject("inputs").has("stems")) {
            for (var stem : args.getAsJsonObject("inputs").getAsJsonObject("stems").entrySet()) {
                cli.add("--stem");
                cli.add(Integer.parseInt(stem.getKey()) + "=" + McpPaths.input(stem.getValue().getAsString()));
            }
        }
        if (args.has("options")) {
            var options = args.getAsJsonObject("options");
            options(cli, options, "seconds", "name", "ticksPerBeat", "keepOctaves", "fmOnly", "wholePartVoices",
                    "audioRefine", "harmonyMaxHz", "estimateTempo", "tempo", "barOffset", "steadyTempo", "audioRepeats");
            if (options.has("candidates")) {
                cli.add("--candidates");
                cli.add(McpPaths.input(string(options, "candidates")).toString());
            }
        }
        ReconstructionStages.runForReview(command, cli.toArray(String[]::new), progress);
        var files = new LinkedHashMap<String, String>();
        for (String file : FILES) {
            Path path = work.resolve(file);
            if (Files.isRegularFile(path)) files.put(file, path.toString());
        }
        return object(Map.of("workDir", work.toString(), "files", files));
    }

    static JsonObject describe(JsonObject args) throws IOException {
        Path work = McpPaths.directory(string(args, "workDir"));
        for (String file : List.of("score.json", "structure.json", "roles.json")) McpPaths.managed(work, file);
        boolean json = args.has("json") && args.get("json").getAsBoolean();
        String description = ReconstructionDescribe.describe(work, json, optional(args, "section"),
                optional(args, "parts"), optional(args, "bars"));
        if (args.has("output")) {
            Path output = McpPaths.output(work, string(args, "output"));
            Files.writeString(output, description, java.nio.file.StandardOpenOption.CREATE_NEW);
            return object(Map.of("path", output.toString()));
        }
        if (json) return McpJson.parse(new java.io.StringReader(description)).getAsJsonObject();
        return object(Map.of("text", description));
    }

    static JsonObject planTemplate(JsonObject args) throws IOException {
        Path work = McpPaths.directory(string(args, "workDir"));
        for (String file : List.of("score.json", "structure.json", "roleplan.json")) McpPaths.managed(work, file);
        return object(Map.of("path", ReconstructionStages.planTemplate(work).toString()));
    }

    private static String optional(JsonObject args, String key) {
        return args.has(key) ? string(args, key) : null;
    }

    static JsonObject artifact(JsonObject args, boolean write) throws IOException {
        Path work = McpPaths.directory(string(args, "workDir"));
        Path path = McpPaths.managed(work, string(args, "artifact") + ".json");
        JsonArray selection = args.has("selection") ? args.getAsJsonArray("selection") : new JsonArray();
        if (write) {
            McpPaths.managed(work, "score.json");
            if (path.getFileName().toString().equals("voicing.json")) McpPaths.managed(work, "allocation.json");
            if (path.getFileName().toString().equals("roleplan.json")) McpPaths.managed(work, "structure.json");
            JsonElement value = args.get("value");
            if (!selection.isEmpty()) {
                var tree = read(path);
                var parent = select(tree, selection, selection.size() - 1);
                String member = selection.get(selection.size() - 1).getAsString();
                member(parent, member); // Refuse a typo instead of creating an unintended member.
                if (parent.isJsonObject()) parent.getAsJsonObject().add(member, value);
                else parent.getAsJsonArray().set(index(member), value);
                value = tree;
            }
            ReconstructionStages.writeArtifact(path, value);
            return object(Map.of("path", path.toString(), "valid", true));
        }
        JsonElement value = select(read(path), selection, selection.size());
        return object(Map.of("path", path.toString(), "value", value));
    }

    private static JsonElement read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Artifact does not exist: " + path);
        if (Files.size(path) > 32L * 1024 * 1024) throw new IllegalArgumentException("Artifact exceeds 32 MiB: " + path);
        try (var reader = Files.newBufferedReader(path)) {
            return McpJson.parse(reader);
        }
    }

    private static JsonElement select(JsonElement value, JsonArray selection, int count) {
        for (int i = 0; i < count; i++) value = member(value, selection.get(i).getAsString());
        return value;
    }

    private static JsonElement member(JsonElement parent, String key) {
        if (parent.isJsonObject() && parent.getAsJsonObject().has(key)) return parent.getAsJsonObject().get(key);
        if (parent.isJsonArray()) {
            int index = index(key);
            if (index >= 0 && index < parent.getAsJsonArray().size()) return parent.getAsJsonArray().get(index);
        }
        throw new IllegalArgumentException("Artifact selection has no member: " + key);
    }

    private static int index(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Artifact selection expects a zero-based array index: " + value, invalid);
        }
    }

}
