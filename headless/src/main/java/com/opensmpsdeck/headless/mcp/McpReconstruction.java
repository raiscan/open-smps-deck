package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionAudition;
import com.opensmpsdeck.headless.ReconstructionDescribe;
import com.opensmpsdeck.headless.ReconstructionEditCommand;
import com.opensmpsdeck.headless.ReconstructionProposals;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.headless.ReconstructionStages;
import com.opensmpsdeck.headless.ReconstructionSections;
import com.opensmpsdeck.headless.ReconstructionVoicePlan;
import com.opensmpsdeck.headless.ReconstructionTrace;
import com.opensmpsdeck.headless.SunoImport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

import static com.opensmpsdeck.headless.mcp.McpArguments.*;
import static com.opensmpsdeck.headless.mcp.McpJson.object;

final class McpReconstruction {
    static final String[] STAGES = {"transcribe", "structure", "roles", "voices", "allocate", "voice", "assemble"};
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
            field("noStemTemplateNotes", "boolean", "Skip automatic recovered stem notes (still report candidates). Fresh runs only").optional(),
            field("candidates", "string", "Absolute external note candidates JSON path").optional(),
            field("leadPart", "integer", "Prefer this MIDI part as the song-wide lead. Fresh runs only").optional(),
            field("harmonyMaxHz", "number", "Harmony attack cap, 0 to disable").optional(),
            choice("lanePolicyPreset", "Replace lane policies when rebuilding an existing work directory", "s3k-layering", "off").optional(),
            field("overwriteDetached", "boolean", "Allow a resumed run or assemble to replace a hand-edited arrangement").optional(),
            field("keepVoicing", "boolean", "Keep compatible voicing in a resumed run").optional(),
            field("refitLevels", "boolean", "Recompute levels for the lanes being refitted").optional(),
            field("refitLanes", object(Map.of("type", "array", "items", Map.of("type", "string"))),
                    "Limit a resumed voice fit to specific lanes").optional(),
            field("estimateTempo", "boolean", "Infer a musical grid").optional(),
            field("tempo", "number", "Override grid BPM").optional(),
            field("barOffset", "number", "Override bar origin in seconds (with tempo)").optional(),
            field("steadyTempo", "string", "Steady output tempo: off, auto, or BPM").optional(),
            field("audioRepeats", "boolean", "Include audio chroma in repeat matching").optional(),
            field("transcriber", "string", "Name returned by transcribers_list. Fresh runs only").optional(),
            field("transcribeParts", object(Map.of("type", "object", "additionalProperties", Map.of("type", "string"))),
                    "Map of melodic MIDI part IDs to transcriber roles. Each part needs an aligned stem").optional(),
            field("transcribeUnmapped", object(Map.of("type", "object", "additionalProperties", Map.of("type", "string"))),
                    "Map of new aligned stem WAV paths to transcriber roles").optional(),
            field("transcriberDevice", "string", "Device passed to the selected transcriber, default auto").optional());

    private static final List<String> FILES = List.of("score.json", "stem-template-candidates.json", "structure.json", "roles.json", "allocation.json",
            "voicing.json", "roleplan.json", "manifest.json", ReconstructionVoicePlan.FILE, ReconstructionVoicePlan.JOURNAL, ReconstructionSections.FILE, ReconstructionEditCommand.FILE, ReconstructionEditCommand.RESULTS, ReconstructionProposals.RESULTS, "arrangement.smps.json", "arrangement.smps.zip", "render.wav", "report.json", ReconstructionStages.STEADY_REFERENCE);

    private McpReconstruction() {}

    static void requireManaged(Path work) {
        for (String file : FILES) McpPaths.managed(work, file);
    }

    static JsonObject importFolder(JsonObject args, ReconstructionStages.Progress progress) throws IOException {
        Path folder = McpPaths.directory(string(args, "folder"));
        Path work = McpPaths.absolute(string(args, "workDir"));
        Path catalogue = args.has("catalogue") ? McpPaths.input(string(args, "catalogue")) : null;
        var assign = new LinkedHashMap<String, Integer>();
        if (args.has("assign")) {
            for (var entry : args.getAsJsonObject("assign").entrySet()) {
                try {
                    assign.put(entry.getKey(), entry.getValue().getAsBigDecimal().intValueExact());
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException("Expected a valid MIDI part ID for " + entry.getKey(), e);
                }
            }
        }
        var exclude = new HashSet<String>();
        if (args.has("exclude")) {
            for (var file : args.getAsJsonArray("exclude")) exclude.add(file.getAsString());
        }
        return SunoImport.inspect(folder, work, catalogue, assign, exclude, optional(args, "transcribeUnmapped"), progress).json();
    }


    static JsonObject run(JsonObject args, boolean single, ReconstructionStages.Progress progress) throws IOException {
        String command = single ? string(args, "stage") : "run";
        boolean initial = args.has("inputs");
        boolean resume = args.has("from");
        if (args.has("withProposals") && args.get("withProposals").getAsBoolean()) {
            if (single || initial || resume || args.has("options"))
                throw new IllegalArgumentException("Proposal preview only takes workDir and an optional proposalId or group");
            Path work = McpPaths.directory(string(args, "workDir"));
            for (String file : FILES) McpPaths.managed(work, file);
            var preview = ReconstructionReview.preview(work, optional(args, "proposalId"), optional(args, "group"), progress);
            return object(Map.of("workDir", preview.workDir().toString(), "ids", preview.ids(),
                    "comparisons", preview.comparisons()));
        }
        if (args.has("proposalId") || args.has("group"))
            throw new IllegalArgumentException("Proposal selection requires withProposals");
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
            requireManaged(work);
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
                    "audioRefine", "noStemTemplateNotes", "leadPart", "harmonyMaxHz", "estimateTempo", "tempo",
                    "barOffset", "steadyTempo", "audioRepeats", "transcriber", "transcriberDevice",
                    "refitLevels", "keepVoicing", "overwriteDetached", "lanePolicyPreset");
            if (options.has("refitLanes")) for (var lane : options.getAsJsonArray("refitLanes")) {
                cli.add("--lane");
                cli.add(lane.getAsString());
            }
            if (options.has("transcribeParts")) {
                for (var part : options.getAsJsonObject("transcribeParts").entrySet()) {
                    cli.add("--transcribe-part");
                    cli.add(Integer.parseInt(part.getKey()) + "=" + part.getValue().getAsString());
                }
            }
            if (options.has("transcribeUnmapped")) {
                for (var stem : options.getAsJsonObject("transcribeUnmapped").entrySet()) {
                    cli.add("--transcribe-unmapped");
                    cli.add(stem.getValue().getAsString() + "=" + McpPaths.input(stem.getKey()));
                }
            }
            if (options.has("candidates")) {
                cli.add("--candidates");
                cli.add(McpPaths.input(string(options, "candidates")).toString());
            }
        }
        var changed = ReconstructionStages.runForReview(command, cli.toArray(String[]::new), progress);
        var files = new LinkedHashMap<String, String>();
        for (String file : FILES) {
            Path path = work.resolve(file);
            if (Files.isRegularFile(path)) files.put(file, path.toString());
        }
        var result = object(Map.of("workDir", work.toString(), "files", files, "refitChanges", changed));
        var issues = ReconstructionSections.anchorIssues(work);
        if (!issues.isEmpty()) result.add("sectionIssues", McpJson.JSON.toJsonTree(issues));
        var voicesFile = work.resolve(ReconstructionVoicePlan.FILE);
        if (Files.isRegularFile(voicesFile)) {
            var voiceIssues = read(voicesFile).getAsJsonObject().getAsJsonArray("issues");
            if (!voiceIssues.isEmpty()) result.add("voiceEditIssues", voiceIssues);
        }
        Path edits = work.resolve(ReconstructionEditCommand.RESULTS);
        if (Files.isRegularFile(edits)) result.add("editResults", read(edits).getAsJsonObject().get("stages"));
        if (command.equals("roles") || !single && (!resume || List.of("transcribe", "structure", "roles").contains(string(args, "from")))) {
            var times = ReconstructionStages.rolePlanTimes(work);
            if (!times.isEmpty()) result.add("rolePlanTimes", McpJson.JSON.toJsonTree(times));
        }
        return result;
    }

    static JsonObject audition(JsonObject args) throws IOException {
        Path work = McpPaths.directory(string(args, "workDir"));
        for (String file : List.of("score.json", "report.json", "arrangement.smps.json", ReconstructionStages.STEADY_REFERENCE))
            McpPaths.managed(work, file);
        if (args.has("note")) for (String file : ReconstructionTrace.FILES) McpPaths.managed(work, file);
        var variants = new ArrayList<String>();
        for (var variant : args.getAsJsonArray("variants")) variants.add(variant.getAsString());
        Path requested = Path.of(string(args, "output"));
        Path output = work.resolve(requested);
        for (int i = 0; i < variants.size(); i++) {
            var file = McpPaths.output(work, ReconstructionAudition.outputPath(requested, i, variants.size()).toString());
            output = file.resolveSibling(requested.getFileName());
        }
        Path rmsJson = args.has("rmsJson") ? McpPaths.output(work, string(args, "rmsJson")) : null;
        var result = ReconstructionAudition.run(work, optional(args, "lane"),
                args.has("start") ? args.get("start").getAsDouble() : null,
                args.has("end") ? args.get("end").getAsDouble() : null,
                args.has("time") ? string(args, "time") : "source", optional(args, "note"), variants, args.has("gain") ? args.get("gain").getAsDouble() : null,
                args.has("solo") && args.get("solo").getAsBoolean(),
                args.has("reference") ? McpPaths.input(string(args, "reference")) : null, output, rmsJson);
        var response = object(result);
        if (rmsJson != null) response.addProperty("rmsJson", rmsJson.toString());
        return response;
    }

    static JsonObject voices(JsonObject args) throws IOException {
        var work = McpPaths.directory(string(args, "workDir"));
        for (String file : List.of("score.json", "roles.json", ReconstructionVoicePlan.JOURNAL, ReconstructionVoicePlan.LOCK))
            McpPaths.managed(work, file);
        return object(ReconstructionVoicePlan.run(work, args.has("action") ? string(args, "action") : "list",
                args.get("entries")));
    }

    static JsonObject sections(JsonObject args) throws IOException {
        var work = McpPaths.directory(string(args, "workDir"));
        for (String file : List.of("score.json", "stem-template-candidates.json", "structure.json", ReconstructionSections.FILE, ReconstructionSections.LOCK))
            McpPaths.managed(work, file);
        return object(ReconstructionSections.run(work, args.has("action") ? string(args, "action") : "list",
                args.get("entries")));
    }

    static JsonObject edit(JsonObject args, ReconstructionStages.Progress progress) throws IOException {
        Path work = McpPaths.directory(string(args, "workDir"));
        for (String file : FILES) McpPaths.managed(work, file);
        McpPaths.managed(work, ReconstructionEditCommand.LOCK);
        if (args.has("edit") && args.has("edits")) throw new IllegalArgumentException("Choose edit or edits");
        boolean propose = args.has("propose") && args.get("propose").getAsBoolean();
        String action = args.has("action") ? string(args, "action") : propose ? "add" : "list";
        var edit = args.get(args.has("edits") ? "edits" : "edit");
        var id = optional(args, "id");
        var group = optional(args, "group");
        boolean rerun = args.has("rerun") && args.get("rerun").getAsBoolean();
        boolean dryRun = args.has("dryRun") && args.get("dryRun").getAsBoolean();
        if (List.of("accept", "reject", "proposals").contains(action)) {
            if (propose || rerun || dryRun || edit != null)
                throw new IllegalArgumentException("Proposal review takes only an optional id or group");
            return ReconstructionEditCommand.proposals(work, action.equals("proposals") ? "list" : action, id, group);
        }
        if (group != null || propose && !action.equals("add"))
            throw new IllegalArgumentException("Use propose with add and include the group on each intent");
        return ReconstructionEditCommand.run(work, propose ? "propose" : action, edit, id, rerun, dryRun, progress);
    }

    static JsonObject trace(JsonObject args) throws IOException {
        var cli = new ArrayList<String>();
        options(cli, args, "start", "end", "part", "pitch", "pitchMin", "pitchMax", "lane", "status", "time");
        if (args.has("byOnset") && args.get("byOnset").getAsBoolean()) cli.add("--by-onset");
        return ReconstructionTrace.trace(traceDirectory(args, "workDir"), cli.toArray(String[]::new));
    }

    static JsonObject traceDiff(JsonObject args) throws IOException {
        return ReconstructionTrace.diff(traceDirectory(args, "oldWorkDir"), traceDirectory(args, "newWorkDir"),
                optional(args, "span"));
    }

    private static Path traceDirectory(JsonObject args, String key) throws IOException {
        Path work = McpPaths.directory(string(args, key));
        for (String file : ReconstructionTrace.FILES) McpPaths.managed(work, file);
        return work;
    }

    static JsonObject describe(JsonObject args) throws IOException {
        Path work = McpPaths.directory(string(args, "workDir"));
        for (String file : List.of("score.json", "stem-template-candidates.json", "structure.json", "roles.json", "allocation.json", ReconstructionSections.FILE)) McpPaths.managed(work, file);
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
        for (String file : List.of("score.json", "stem-template-candidates.json", "structure.json", "roleplan.json")) McpPaths.managed(work, file);
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
            if (path.getFileName().toString().equals("roleplan.json")) {
                McpPaths.managed(work, "structure.json");
                McpPaths.managed(work, ReconstructionSections.FILE);
            }
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
