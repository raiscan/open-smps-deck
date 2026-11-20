package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.Main;
import com.opensmpsdeck.headless.ExternalTranscribers;
import com.opensmpsdeck.headless.ReconstructionStages;
import com.opensmpsdeck.headless.bench.BenchCli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.mcp.McpArguments.*;
import static com.opensmpsdeck.headless.mcp.McpJson.object;

final class McpTools {
    private interface Handler {
        JsonObject call(JsonObject args, ReconstructionStages.Progress progress) throws IOException;
    }

    record Description(String name, String description, JsonObject inputSchema) {}

    record Result(JsonObject value, byte[] png) {}
    private interface ImageHandler {
        Result call(JsonObject args, ReconstructionStages.Progress progress) throws IOException;
    }

    private record Tool(Description description, ImageHandler handler) {}

    private static final Field WORK = field("workDir", "string", "Absolute work directory path");
    private static final Field PROJECT = field("project", "string", "Absolute project JSON path");
    private static final Field CATALOGUE = field("catalogue", "string", "Absolute catalogue JSON path");
    private static final Field OUTPUT = field("output", "string", "New WAV path relative to or inside the work/project directory");
    private static final Field START = field("start", "number", "Inclusive start in project seconds, rounded to 60 Hz");
    private static final Field END = field("end", "number", "Exclusive end in project seconds, rounded to 60 Hz");
    private static final Field AUTHOR = field("author", "string", "Review author display name");
    private static final Field ARTIFACT = choice("artifact", "Stage artifact name without .json", McpReconstruction.ARTIFACTS);
    private static final Field SELECTION = field("selection",
            object(Map.of("type", "array", "items", Map.of("type", "string"))),
            "Path within the artifact, as object keys or zero-based array indexes. Omit for the whole artifact").optional();
    private static final String[] READABLE_ARTIFACTS = {"score", "structure", "roles", "allocation", "voicing", "roleplan", "report", "manifest"};

    private static final List<Tool> TOOLS = List.of(
            tool("transcribers_list", "List the transcriber names from the server owner's local configuration.",
                    (args, progress) -> object(Map.of("transcribers", ExternalTranscribers.configuredNames()))),
            tool("reconstruct_run",
                    "Run all stages in a new work directory, or resume from the first stage that consumes your edit. From voice reuses existing voicing. Assembly carries forward project reviews.",
                    (args, progress) -> McpReconstruction.run(args, false, progress), WORK,
                    field("inputs", McpReconstruction.INPUTS, "Source files for a fresh run").optional(),
                    choice("from", "First stage to execute in an existing work directory", McpReconstruction.STAGES).optional(),
                    field("options", McpReconstruction.OPTIONS, "CLI reconstruction options").optional()),
            tool("reconstruct_stage", "Run one reconstruction stage. Voice refits preserve scoped overrides and existing levels by default.",
                    (args, progress) -> McpReconstruction.run(args, true, progress), WORK,
                    choice("stage", "Stage to execute", McpReconstruction.STAGES),
                    field("inputs", McpReconstruction.INPUTS, "Required for transcribe in a new directory").optional(),
                    field("options", McpReconstruction.OPTIONS, "CLI reconstruction options").optional()),
            tool("reconstruct_describe", "Inspect a compact score with part summaries and bar grids.",
                    (args, progress) -> McpReconstruction.describe(args), WORK,
                    field("json", "boolean", "Return structured JSON instead of text").optional(),
                    field("section", "string", "Limit to a section ID").optional(),
                    field("parts", "string", "Comma-separated MIDI part IDs").optional(),
                    field("bars", "string", "Inclusive one-based bar range such as 17-24").optional(),
                    field("output", "string", "Save inside the work directory instead of returning inline").optional()),
            imageTool("reconstruct_view", "Save a time-aligned PNG of source audio, note fates, and lane gates.",
                    (args, progress) -> McpReconstructionView.view(args), WORK,
                    field("start", "number", "Inclusive start in the selected time base"),
                    field("end", "number", "Exclusive end in the selected time base"),
                    field("output", "string", "New PNG path relative to or inside the work directory"),
                    choice("time", "Time base for the range (default source)", "source", "project").optional(),
                    field("stem", "integer", "Zero-based part ID of a mapped stem").optional(),
                    path("wav", "an aligned source-time WAV").optional(),
                    field("render", "boolean", "Include a warmed full-mix chip render").optional(),
                    field("pitchMin", "integer", "Lowest displayed MIDI pitch (inclusive)").optional(),
                    field("pitchMax", "integer", "Highest displayed MIDI pitch (inclusive)").optional(),
                    lanes("lanes", "Filter note overlays and lane rows").optional(),
                    field("parts", object(Map.of("type", "array", "items", Map.of("type", "integer"))),
                            "Filter overlays to these zero-based part IDs").optional(),
                    field("harmonics", "string", "Trace note ID whose expected harmonic series is drawn").optional(),
                    field("harmonicSum", "boolean", "Emphasize fundamentals on the source spectrogram").optional(),
                    field("width", "integer", "PNG width in pixels (640–2400, default 1440)").optional()),
            tool("reconstruct_trace", "Follow individual source notes through score, roles, hardware allocation, and voicing.",
                    (args, progress) -> McpReconstruction.trace(args), WORK,
                    field("start", "number", "Inclusive start in source seconds").optional(),
                    field("end", "number", "Exclusive end in source seconds").optional(),
                    field("part", "integer", "Zero-based MIDI part ID").optional(),
                    field("pitch", "integer", "Exact source MIDI pitch").optional(),
                    field("pitchMin", "integer", "Inclusive minimum source MIDI pitch").optional(),
                    field("pitchMax", "integer", "Inclusive maximum source MIDI pitch").optional(),
                    field("lane", "string", "Allocated lane, e.g. FM1").optional(),
                    choice("status", "Summary fate", "kept", "moved", "shortened", "merged", "dropped").optional()),
            tool("reconstruct_trace_diff", "Compare note fates and flag changes outside the edited span.",
                    (args, progress) -> McpReconstruction.traceDiff(args),
                    field("oldWorkDir", "string", "Absolute baseline work directory"),
                    field("newWorkDir", "string", "Absolute candidate work directory"),
                    field("span", "string", "Edited START,END in source seconds").optional()),
            tool("reconstruct_plan_template", "Create roleplan.json from the heuristic's dominant roles per section and part.",
                    (args, progress) -> McpReconstruction.planTemplate(args), WORK),
            tool("reconstruct_audition", "Compare FM variants on a frozen phrase from the assembled arrangement.",
                    (args, progress) -> McpReconstruction.audition(args), WORK, START, END, OUTPUT,
                    choice("lane", "FM lane to audition", "FM1", "FM2", "FM3", "FM4", "FM5"),
                    field("variants", object(Map.of("type", "array", "items", Map.of("type", "string"))),
                            "One or more catalogue variant IDs"),
                    field("gain", "number", "Multiplier for the existing note levels (0–4)").optional(),
                    field("solo", "boolean", "Mute all other lanes (default false)").optional(),
                    path("reference", "Aligned reference WAV to append").optional()),
            tool("artifact_read", "Read a stage artifact, assembly report or run manifest as JSON.",
                    (args, progress) -> McpReconstruction.artifact(args, false), WORK,
                    choice("artifact", "Artifact name without .json", READABLE_ARTIFACTS), SELECTION),
            tool("artifact_write", "Validate the entire artifact after applying an edit, then replace it atomically.",
                    (args, progress) -> McpReconstruction.artifact(args, true), WORK, ARTIFACT, SELECTION,
                    field("value", new JsonObject(), "Complete artifact object including version, or replacement at selection")),
            tool("project_validate", "Load the project and validate schema, arrangement and compiled S3K bank.",
                    (args, progress) -> object(Map.of("valid", true, "summary",
                            cli(List.of("validate", McpPaths.project(string(args, "project")).toString())))), PROJECT),
            tool("render_clip", "Render a project time range through the native chips. Output stays inside the project directory.",
                    (args, progress) -> McpAudio.clip(args), PROJECT, START, END, OUTPUT,
                    lanes("solo", "If supplied, keep only these lanes").optional(),
                    lanes("mute", "Silence these lanes after applying solo").optional()),
            tool("render_compare", "Concatenate a level-matched rendered excerpt and aligned reference excerpt, in that order. Maximum 60 seconds per side.",
                    (args, progress) -> McpAudio.compare(args), WORK, OUTPUT, START, END,
                    path("render", "Rendered WAV"), path("reference", "Reference WAV"),
                    field("referenceOffset", "number", "Project time in seconds at which reference time zero aligns").optional()),
            tool("notes_list", "Load persisted project review notes and replies.",
                    (args, progress) -> review(args, "notes", "list"), PROJECT,
                    field("openOnly", "boolean", "Return only unresolved feedback").optional()),
            tool("notes_add", "Add a point or range review note. Returns its ID.",
                    (args, progress) -> review(args, "notes", "add"), PROJECT, AUTHOR,
                    field("at", "number", "Start time in project seconds"),
                    field("end", "number", "End time in project seconds").optional(),
                    choice("lane", "Hardware lane for the note", McpAudio.LANES).optional(),
                    field("clip", "string", "Clip ID").optional(),
                    field("text", "string", "Review note text")),
            tool("notes_resolve", "Resolve a review note by ID. Supply both reply and author to include a reply.",
                    (args, progress) -> review(args, "notes", "resolve"), PROJECT,
                    field("id", "string", "ID returned by notes_list or notes_add"),
                    field("reply", "string", "Review reply text").optional(), AUTHOR.optional()),
            tool("reference_add", "Attach an existing WAV to the project with a timeline offset. Returns its ID.",
                    (args, progress) -> review(args, "reference", "add"), PROJECT, path("wav", "Reference WAV"),
                    field("offset", "number", "Project seconds at WAV time zero. May be negative").optional(),
                    field("label", "string", "Studio display label").optional(),
                    field("role", "string", "Optional mix/stem role label").optional()),
            tool("catalog_search", "Search a local instrument catalogue and return stable variant IDs with a page of matches.",
                    (args, progress) -> catalogue(args, false), CATALOGUE,
                    field("query", "string", "Search text").optional(),
                    choice("kind", "Catalogue asset kind", "fm-voice", "psg-volume", "modulation", "dac-sample", "psg-noise").optional(),
                    field("driver", "string", "Driver filter (S3K)").optional(),
                    field("role", "string", "Role filter").optional(),
                    field("source", "string", "Source filter").optional(),
                    field("variant", "string", "Variant ID filter").optional(),
                    field("offset", "integer", "Page start").optional(),
                    field("limit", "integer", "Page size").optional()),
            tool("catalog_audition", "Render a catalogue variant to a new WAV inside the work directory.",
                    (args, progress) -> catalogue(args, true), CATALOGUE, WORK, OUTPUT,
                    field("variant", "string", "Catalogue variant or asset ID"),
                    field("frames", "integer", "Number of 60 Hz presentations").optional(),
                    field("midi", "integer", "Pitch for a match probe").optional(),
                    field("gateTicks", "integer", "Gate length in ticks for a match probe").optional()),
            tool("bench_compare", "Compare scorecards with the CLI benchmark gate and return metric deltas and pass/fail.",
                    (args, progress) -> BenchCli.compareJson(McpPaths.input(string(args, "old")),
                            McpPaths.input(string(args, "new")), args.has("tolerance")
                                    ? args.get("tolerance").getAsDouble() : 0.01),
                    path("old", "Baseline scorecard"), path("new", "Candidate scorecard"),
                    field("tolerance", "number", "Absolute regression tolerance (default 0.01)").optional()));

    private McpTools() {}

    static List<Description> descriptions() {
        return TOOLS.stream().map(Tool::description).toList();
    }

    static boolean contains(String name) {
        return TOOLS.stream().anyMatch(tool -> tool.description.name.equals(name));
    }

    static Result call(String name, JsonObject args, ReconstructionStages.Progress progress) throws IOException {
        var tool = TOOLS.stream().filter(candidate -> candidate.description.name.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + name));
        validate("arguments", tool.description.inputSchema, args);
        return tool.handler.call(args, progress);
    }

    private static Field path(String name, String description) {
        return field(name, "string", "Absolute path to " + description);
    }

    private static Field lanes(String name, String description) {
        return field(name, object(Map.of("type", "array",
                "items", Map.of("type", "string", "enum", McpAudio.LANES))), description);
    }

    private static Tool tool(String name, String description, Handler handler, Field... fields) {
        return imageTool(name, description, (args, progress) -> new Result(handler.call(args, progress), null), fields);
    }

    private static Tool imageTool(String name, String description, ImageHandler handler, Field... fields) {
        return new Tool(new Description(name, description, schema(fields)), handler);
    }

    private static JsonObject review(JsonObject args, String verb, String action) throws IOException {
        var project = McpPaths.project(string(args, "project"));
        var command = new ArrayList<>(List.of(verb, action, project.toString()));
        if (verb.equals("reference")) {
            command.add(McpPaths.input(string(args, "wav")).toString());
            options(command, args, "offset", "label", "role");
        } else if (action.equals("list")) {
            if (flag(args, "openOnly")) command.add("--open");
            return object(Map.of("notes", McpJson.parse(cli(command))));
        } else if (action.equals("resolve")) {
            command.add(string(args, "id"));
            options(command, args, "reply", "author");
        } else {
            options(command, args, "at", "end", "lane", "clip", "text", "author");
        }
        return object(Map.of("id", cli(command)));
    }

    private static JsonObject catalogue(JsonObject args, boolean audition) throws IOException {
        var index = McpPaths.input(string(args, "catalogue"));
        var command = new ArrayList<>(List.of("catalog", audition ? "audition" : "search", index.toString()));
        if (!audition) {
            options(command, args, "query", "kind", "driver", "role", "source", "variant", "offset", "limit");
            return McpJson.parse(cli(command)).getAsJsonObject();
        }
        var work = McpPaths.directory(string(args, "workDir"));
        var output = McpPaths.output(work, string(args, "output"));
        command.add(string(args, "variant"));
        command.add(output.toString());
        options(command, args, "frames", "midi", "gateTicks");
        var result = McpJson.parse(cli(command)).getAsJsonObject();
        result.addProperty("output", output.toString());
        return result;
    }

    private static String cli(List<String> command) throws IOException {
        // Capture CLI text in-process. stdout on the server belongs to JSON-RPC.
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        try (var out = new PrintStream(output, false, StandardCharsets.UTF_8);
             var err = new PrintStream(errors, false, StandardCharsets.UTF_8)) {
            if (Main.run(command.toArray(String[]::new), out, err) != 0) {
                String detail = errors.toString(StandardCharsets.UTF_8);
                int usage = detail.indexOf("\nUsage:");
                throw new IOException((usage < 0 ? detail : detail.substring(0, usage)).strip());
            }
        }
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}
