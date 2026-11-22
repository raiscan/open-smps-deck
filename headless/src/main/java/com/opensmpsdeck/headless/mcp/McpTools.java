package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.Main;
import com.opensmpsdeck.headless.ProjectExport;
import com.opensmpsdeck.headless.PortablePackages;
import com.opensmpsdeck.headless.ExternalSeparators;
import com.opensmpsdeck.headless.ExternalTranscribers;
import com.opensmpsdeck.headless.ReconstructionStages;
import com.opensmpsdeck.headless.bench.BenchCli;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
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
    private static final Field SOURCE_START = field("start", "number", "Inclusive start in the selected clock (default source)");
    private static final Field SOURCE_END = field("end", "number", "Exclusive end in the selected clock (default source)");
    private static final Field SECTION = field("section", "string", "Effective section, period or phrase ID or unique name").optional();
    private static final Field START = field("start", "number", "Inclusive start in project seconds, rounded to 60 Hz");
    private static final Field END = field("end", "number", "Exclusive end in project seconds, rounded to 60 Hz");
    private static final Field AUTHOR = field("author", "string", "Review author display name");
    private static final Field ROMS = field("roms", object(Map.of("type", "array", "items", Map.of("type", "string"))),
            "Absolute paths to local ROM images").optional();
    private static final Field PACKAGE = path("package", "Absolute path to an SMPS package");
    private static final Field ARTIFACT = choice("artifact", "Stage artifact name without .json", McpReconstruction.ARTIFACTS);
    private static final Field SELECTION = field("selection",
            object(Map.of("type", "array", "items", Map.of("type", "string"))),
            "Path within the artifact, as object keys or zero-based array indexes. Omit for the whole artifact").optional();
    private static final String[] READABLE_ARTIFACTS = {"score", "structure", "roles", "allocation", "voicing", "roleplan", "report", "import", "manifest", "edits", "edit-results", "sections", "voices", "voice-edits"};

    private static final List<Tool> TOOLS = List.of(
            tool("rom_list", "Verify and list the ROM paths saved in the server owner's settings.",
                    (args, progress) -> McpRoms.list()),
            tool("rom_set", "Verify a clean retail ROM and save its absolute path in the server owner's settings.",
                    (args, progress) -> McpRoms.set(args),
                    choice("game", "Game ID", "s1", "s2", "s3k"),
                    path("path", "Absolute path to a clean retail ROM")),
            tool("transcribers_list", "List the transcriber names from the server owner's local configuration.",
                    (args, progress) -> object(Map.of("transcribers", ExternalTranscribers.configuredNames(),
                            "drumTranscribers", ExternalTranscribers.configuredDrums()))),
            tool("separators_list", "List the separator names from the server owner's local configuration.",
                    (args, progress) -> object(Map.of("separators", ExternalSeparators.configuredNames()))),
            tool("separate", "Separate one WAV or MP3 into an importable folder using a configured local model.",
                    McpTools::separate, path("input", "Absolute path to the mix"),
                    path("outputDir", "Absolute path to a new output directory"),
                    field("separator", "string", "Name returned by separators_list"),
                    choice("device", "Device selection (default auto)", "auto", "cpu", "rocm", "cuda").optional()),
            tool("reconstruct_import", "Check a Suno download, separated folder or aligned audio-only folder and write import.json with file status, mapping evidence and available run arguments.",
                    McpReconstruction::importFolder, path("folder", "Absolute path to the input folder"), WORK,
                    CATALOGUE.optional(),
                    choice("target", "Target game (default s3k)", "s1", "s2", "s3k").optional(),
                    choice("drumKit", "DAC kit selection. S1 and S2 default to stock", "stock", "custom").optional(),
                    field("assign", object(Map.of("type", "object", "additionalProperties", Map.of("type", "integer"))),
                            "Map of folder-relative stem files to MIDI part IDs").optional(),
                    field("exclude", object(Map.of("type", "array", "items", Map.of("type", "string"))),
                            "Folder-relative stem files to skip").optional(),
                    field("audioOnly", "boolean", "Prepare a reconstruction request using the mix and labelled stems").optional(),
                    field("transcriber", "string", "Configured transcriber for audio-only import").optional(),
                    field("transcriberFor", McpReconstruction.STEM_ROLES, "Map of stem roles to configured transcriber names").optional(),
                    field("drumDetector", "string", "Select an audio-only drum detector").optional(),
                    choice("lanePolicyPreset", "Override the target-specific layering default", "s1-layering", "s2-layering", "s3k-layering", "off").optional(),
                    field("transcriberDevice", "string", "Device passed to external transcribers").optional(),
                    field("stemRoles", McpReconstruction.STEM_ROLES, "Map of folder-relative stem files to audio-only role overrides").optional(),
                    field("transcribeUnmapped", "string", "Configured polyphonic transcriber for unmatched tonal stems").optional()),
            tool("reconstruct_run",
                    "Run all stages in a new work directory, or resume from the first stage that consumes your edit. From voice reuses existing voicing. Assembly carries forward project reviews.",
                    (args, progress) -> McpReconstruction.run(args, false, progress), WORK,
                    field("inputs", McpReconstruction.INPUTS, "Source files for a fresh run").optional(),
                    choice("from", "First stage to execute in an existing work directory", McpReconstruction.STAGES).optional(),
                    field("options", McpReconstruction.OPTIONS, "CLI reconstruction options").optional(),
                    field("withProposals", "boolean", "Render proposals in a new sibling directory and export built/proposed A/B WAVs").optional(),
                    field("proposalId", "string", "Preview only this proposed edit").optional(),
                    field("group", "string", "Preview only this proposal group").optional()),
            tool("reconstruct_rerun", "Replay a saved reconstruction in a new directory, or rebuild in place with explicit overwrite permission.",
                    (args, progress) -> rerun(args), WORK,
                    field("output", "string", "Absolute destination work directory").optional(),
                    field("overwriteDetached", "boolean", "Allow replacing the arrangement for in-place replay").optional(),
                    field("keepVoicing", "boolean", "Keep compatible voicing").optional(),
                    field("search", object(Map.of("type", "array", "items", Map.of("type", "string"))),
                            "Directories to search for moved inputs on new-directory replay").optional()),
            tool("rom_patch", "Test in game in Sonic 1 or 2 World REV01, or locked-on S3&K. Set out (personal-use ROM), bps, or both.",
                    (args, progress) -> McpRomPatch.export(args),
                    path("project", "an editable project, legacy S3K package, or S1/S2 portable package"),
                    choice("driver", "Target for editable projects (default s3k); packages declare their own driver", "s1", "s2", "s3k").optional(),
                    path("rom", "a clean retail ROM override (defaults to the ROM library)").optional(),
                    path("out", "the patched ROM destination").optional(),
                    path("bps", "the verified BPS patch destination").optional(),
                    field("slot", "integer", "Music slot: S3K 1–51 (default 37); S1 129–147 (default 138); S2 129–159 (default 153)").optional(),
                    field("titleMusic", "boolean", "Request the selected slot from the title screen").optional(),
                    field("noDemo", "boolean", "Disable title demo timeouts").optional(),
                    field("loop", "boolean", "Loop duration-aligned channels (S2 and S3K)").optional(),
                    field("force", "boolean", "Replace existing output files").optional()),
            tool("project_export", "Export a project or reconstruction as audio, SMPS or a disassembly kit. Disasm also accepts a portable package as project.",
                    McpTools::export, PROJECT.optional(), WORK.optional(),
                    field("output", "string", "New file or directory inside the project or work directory"),
                    choice("format", "Export format", "wav", "mp3", "vgm", "smps", "package", "portable", "mod", "disasm"),
                    ROMS,
                    field("dpcmClosed", "boolean", "Quantise disassembly WAVs to DPCM-representable PCM").optional(),
                    field("replaceDac", object(Map.of("type", "array", "items", Map.of("type", "integer"))),
                            "Unused retail DAC note numbers to repurpose, in order (S1: 129–130, S2: 129–145, S3K: 129–196)").optional(),
                    choice("driver", "Portable target driver (defaults to the project target)", "s1", "s2", "s3k").optional(),
                    field("speedUpTempo", "integer", "S3K-clock speed-shoes tempo byte (0..255) for S1/S2 exports").optional(),
                    field("strictTempo", "boolean", "Reject inexact tempo conversions for portable or mod export").optional(),
                    field("noSource", "boolean", "Omit portable source").optional(),
                    field("retailPatch", "boolean", "Store a native retail bank delta and omit source (edited banks need roms)").optional(),
                    field("license", "string", "SPDX license expression").optional(),
                    field("packageId", "string", "Portable package ID").optional(),
                    field("packageVersion", "string", "Portable package version").optional(),
                    field("authors", object(Map.of("type", "array", "items", Map.of("type", "string"))),
                            "Package author names").optional(),
                    field("replace", object(Map.of("type", "array", "items", Map.of("type", "integer"))),
                            "Stock music IDs to replace in a mod").optional(),
                    START.optional(), END.optional(),
                    field("fade", "number", "Fade-out seconds within the exported WAV or MP3 interval").optional(),
                    field("loops", "integer", "Number of loop passes for native bank WAV or MP3").optional(),
                    field("stems", "boolean", "Render one WAV or MP3 per hardware channel").optional(),
                    field("zip", "boolean", "Write the stems to a ZIP archive").optional()),
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
                    SOURCE_START.optional(), SOURCE_END.optional(), SECTION,
                    field("sections", "boolean", "Show musical section, period and phrase bands").optional(),
                    field("output", "string", "New PNG path relative to or inside the work directory"),
                    choice("time", "Time base for the range (default source)", "source", "project").optional(),
                    field("stem", "integer", "Zero-based part ID of a mapped stem").optional(),
                    path("wav", "an aligned source-time WAV").optional(),
                    choice("colorBy", "Color note overlays by lane (default) or voice", "lane", "voice").optional(),
                    field("render", "boolean", "Include a warmed chip render (solos `lanes` when given, else the full mix)").optional(),
                    field("pitchMin", "integer", "Lowest displayed MIDI pitch (inclusive)").optional(),
                    field("pitchMax", "integer", "Highest displayed MIDI pitch (inclusive)").optional(),
                    lanes("lanes", "Filter note overlays and lane rows").optional(),
                    field("parts", object(Map.of("type", "array", "items", Map.of("type", "integer"))),
                            "Filter overlays to these zero-based part IDs").optional(),
                    field("harmonics", "string", "Trace note ID whose expected harmonic series is drawn").optional(),
                    field("harmonicSum", "boolean", "Emphasize fundamentals on the source spectrogram").optional(),
                    path("notes", "External source-time notes in MIDI or JSON triples").optional(),
                    field("harmonicsAt", object(Map.of("type", "array", "items", Map.of("type", "string"))),
                            "Harmonic targets as MIDI pitch or note name with optional @TIME").optional(),
                    field("ridges", "boolean", "Write interpolated peaks and selected trajectories to sibling JSON").optional(),
                    field("ridgeCount", "integer", "Peaks per column, 1–32 (default 3). Enables ridges.").optional(),
                    field("sharedDb", "boolean", "Use one dB scale for both panels and show their peak levels").optional(),
                    field("db", "boolean", "Show per-panel peak spectral dBFS").optional(),
                    choice("pitchPreset", "Use the General MIDI percussion range (35–81)", "drums").optional(),
                    field("width", "integer", "PNG width in pixels (640–2400, default 1440)").optional()),
            tool("reconstruct_sections", "List musical form or apply an atomic batch to the human/agent section journal.",
                    (args, progress) -> McpReconstruction.sections(args), WORK,
                    choice("action", "Section operation (default list)", "list", "add", "set", "remove", "rename").optional(),
                    field("entries", object(Map.of("type", "array", "items", Map.of("type", "object"))),
                            "Changes with id, author and reason. Add/set also give level, name, sourceStart and sourceEnd. Rename gives name. Remove requires only id, author and reason; set can include removed:true.").optional()),
            tool("reconstruct_voices", "List identified voices or append an atomic batch of authored edits.",
                    (args, progress) -> McpReconstruction.voices(args), WORK,
                    choice("action", "Voice operation (default list)", "list", "create", "rename", "merge", "split", "move", "assign").optional(),
                    field("entries", object(Map.of("type", "array", "items", Map.of("type", "object"))),
                            "Edits with id, author and voiceId, plus action-specific fields. Resume from voices to rebuild.").optional()),
            tool("reconstruct_edit", "Add or propose musical intents, or list, accept and reject proposals.",
                    McpReconstruction::edit, WORK,
                    choice("action", "Edit command (defaults to list, or add with propose)", "add", "list", "remove", "proposals", "accept", "reject", "revise").optional(),
                    field("propose", "boolean", "Save as an agent proposal and dry-run it without rebuilding").optional(),
                    field("group", "string", "Select a proposal group for proposals, accept or reject").optional(),
                    field("edit", object(Map.of("type", "object")), "Intent with id, reason, op and operation fields. Supports splitNote, retrigger (attacks with second offsets and gates, or rate in seconds and count), mergeNotes and pinLane (lane or laneType). transpose takes noteIds, signed integer semitones and optional laneType FM (default) or PSG. arpeggio and chordInstrument use noteIds or part and span (including {section}); arpeggio takes rate in driver ticks and order (up, down, up-down). layer uses noteIds, point selectors or a filtered span with mode echo, chorus, double or off; copies specify laneType FM or PSG and can set attenuation (TL) or levelDb. Off can mask a list of named policies.").optional(),
                    field("edits", object(Map.of("type", "array", "items", Map.of("type", "object"))),
                            "Batch of intent objects for add, instead of edit").optional(),
                    field("id", "string", "Edit ID to remove or review").optional(),
                    field("ids", object(Map.of("type", "array", "items", Map.of("type", "string"))),
                            "Proposal IDs for batch review").optional(),
                    path("file", "a file containing a JSON array or whitespace-separated proposal IDs").optional(),
                    field("op", "string", "Select proposals by edit operation").optional(),
                    field("showDecided", "boolean", "Include accepted and rejected proposals when listing").optional(),
                    field("verbose", "boolean", "Include detailed edit outcomes and markers").optional(),
                    field("rerun", "boolean", "Rebuild from the affected stage and return a trace diff").optional(),
                    field("dryRun", "boolean", "Resolve proposed edits without writing artifacts").optional()),
            tool("reconstruct_vocal_review", "Review a vocal lead against captured syllables and RMVPE pitch.",
                    (args, progress) -> McpReconstruction.vocalReview(args), WORK,
                    field("phrase", "string", "Limit to one phrase ID").optional(),
                    field("propose", "boolean", "Save suggestions as reviewable edits.json proposals").optional(),
                    field("showDecided", "boolean", "Include accepted and rejected vocal proposals").optional()),
            tool("reconstruct_vocal_render", "Render an FM1-only phrase and compare it with the vocal stem.",
                    (args, progress) -> McpReconstruction.vocalRender(args), WORK,
                    field("phrase", "string", "Phrase ID from vocal review"), OUTPUT),
            tool("reconstruct_trace", "Follow source notes through voicing, including saved policy decisions and drum lane blockers.",
                    (args, progress) -> McpReconstruction.trace(args), WORK,
                    field("start", "number", "Inclusive start in the selected time base").optional(),
                    field("end", "number", "Exclusive end in the selected time base").optional(),
                    choice("time", "Time base for bounds (default source)", "source", "project").optional(),
                    field("byOnset", "boolean", "Group attacks by part and onset with roles, lanes, fates and stable IDs").optional(),
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
                    (args, progress) -> McpReconstruction.audition(args), WORK, SOURCE_START.optional(), SOURCE_END.optional(), OUTPUT,
                    choice("time", "Time base for bounds (default source)", "source", "project").optional(),
                    field("note", "string", "Trace note ID to audition on its assembled voiced gate").optional(),
                    choice("lane", "FM lane to audition (inferred when using note)", "FM1", "FM2", "FM3", "FM4", "FM5").optional(),
                    field("variants", object(Map.of("type", "array", "items", Map.of("type", "string"))),
                            "One or more catalogue variant IDs"),
                    field("gain", "number", "Multiplier for the existing note levels (0–4)").optional(),
                    field("solo", "boolean", "Mute all other lanes (default false)").optional(),
                    path("reference", "Aligned reference WAV to append").optional(),
                    field("rmsJson", "string", "Save pre-match RMS bins and variant IDs to a new JSON file").optional()),
            tool("artifact_read", "Read a stage artifact, assembly report or run manifest as JSON.",
                    (args, progress) -> McpReconstruction.artifact(args, false), WORK,
                    choice("artifact", "Artifact name without .json", READABLE_ARTIFACTS), SELECTION),
            tool("artifact_write", "Validate the entire artifact after applying an edit, then replace it atomically.",
                    (args, progress) -> McpReconstruction.artifact(args, true), WORK, ARTIFACT, SELECTION,
                    field("value", new JsonObject(), "Complete artifact object including version, or replacement at selection")),
            tool("package_validate", "Validate a portable package and optionally resolve ROM assets and render a new WAV.",
                    (args, progress) -> validatePackage(args), PACKAGE, ROMS,
                    field("render", "string", "New WAV path inside the package directory").optional(),
                    field("frames", "integer", "60 Hz presentations to render").optional()),
            tool("package_inspect", "Inspect a portable package manifest and its provenance warnings.",
                    (args, progress) -> PortablePackages.inspect(McpPaths.input(string(args, "package"))), PACKAGE),
            tool("project_validate", "Load the project and validate schema, arrangement and compiled S3K bank.",
                    (args, progress) -> object(Map.of("valid", true, "summary",
                            cli(List.of("validate", McpPaths.project(string(args, "project")).toString())))), PROJECT),
            tool("render_clip", "Render a project time range through the native chips. Output stays inside the project directory.",
                    (args, progress) -> McpAudio.clip(args), PROJECT, START, END, OUTPUT,
                    lanes("solo", "If supplied, keep only these lanes").optional(),
                    lanes("mute", "Silence these lanes after applying solo").optional()),
            tool("render_compare", "Concatenate a level-matched rendered excerpt and aligned reference excerpt, in that order. Maximum 60 seconds per side.",
                    (args, progress) -> McpAudio.compare(args), WORK, OUTPUT, SOURCE_START.optional(), SOURCE_END.optional(), SECTION,
                    path("render", "Rendered WAV"), path("reference", "Reference WAV"),
                    choice("time", "Time base for bounds (default source)", "source", "project").optional(),
                    field("referenceOffset", "number", "Project time at which a project-aligned reference begins").optional()),
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
                    field("driver", "string", "Legacy driver text filter").optional(),
                    choice("target", "Target driver filter", "s1", "s2", "s3k").optional(),
                    field("role", "string", "Role filter").optional(),
                    field("source", "string", "Source filter").optional(),
                    field("variant", "string", "Variant ID filter").optional(),
                    field("offset", "integer", "Page start").optional(),
                    field("limit", "integer", "Page size").optional()),
            tool("catalog_audition", "Render a catalogue variant to a new WAV inside the work directory.",
                    (args, progress) -> catalogue(args, true), CATALOGUE, WORK, OUTPUT,
                    field("variant", "string", "Catalogue variant or asset ID"),
                    choice("target", "Target driver (defaults to the variant's driver)", "s1", "s2", "s3k").optional(),
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
            options(command, args, "query", "kind", "driver", "target", "role", "source", "variant", "offset", "limit");
            return McpJson.parse(cli(command)).getAsJsonObject();
        }
        var work = McpPaths.directory(string(args, "workDir"));
        var output = McpPaths.output(work, string(args, "output"));
        command.add(string(args, "variant"));
        command.add(output.toString());
        options(command, args, "frames", "midi", "gateTicks", "target");
        var result = McpJson.parse(cli(command)).getAsJsonObject();
        result.addProperty("output", output.toString());
        return result;
    }

    private static JsonObject separate(JsonObject args, ReconstructionStages.Progress progress) throws IOException {
        var output = ExternalSeparators.separate(McpPaths.input(string(args, "input")),
                McpPaths.newDirectory(string(args, "outputDir")), string(args, "separator"),
                args.has("device") ? string(args, "device") : "auto", null, progress);
        return object(Map.of("outputDir", output.toString(), "provenance", output.resolve("separation.json").toString()));
    }

    private static JsonObject export(JsonObject args, ReconstructionStages.Progress progress) throws IOException {
        boolean work = args.has("workDir");
        if (work == args.has("project")) throw new IllegalArgumentException("Supply exactly one of project or workDir");
        var input = work ? McpPaths.directory(string(args, "workDir")) : McpPaths.project(string(args, "project"));
        if (work) {
            McpPaths.managed(input, "arrangement.smps.json");
            McpPaths.managed(input, "score.json");
        }
        var output = McpPaths.output(work ? input : input.getParent(), string(args, "output"));
        if (string(args, "format").equalsIgnoreCase("disasm")) {
            if (List.of("start", "end", "fade", "loops", "stems", "zip", "driver", "speedUpTempo",
                    "strictTempo", "noSource", "retailPatch", "license", "packageId", "packageVersion", "authors", "replace").stream().anyMatch(args::has))
                throw new IllegalArgumentException("Disassembly export accepts roms, dpcmClosed and replaceDac");
            var slots = args.has("replaceDac") ? args.getAsJsonArray("replaceDac").asList().stream()
                    .map(number -> number.getAsBigDecimal().intValueExact()).toList() : List.<Integer>of();
            var warnings = ProjectExport.exportDisasm(input, output, roms(args), flag(args, "dpcmClosed"), slots, progress);
            return object(Map.of("output", output.toString(), "format", "disasm", "warnings", warnings));
        }
        if (args.has("dpcmClosed") || args.has("replaceDac"))
            throw new IllegalArgumentException("dpcmClosed and replaceDac require format disasm");
        var format = ProjectExport.Format.valueOf(string(args, "format").toUpperCase(Locale.ROOT));
        boolean portable = format == ProjectExport.Format.PORTABLE || format == ProjectExport.Format.MOD;
        if (!portable && List.of("driver", "speedUpTempo", "strictTempo", "noSource", "retailPatch", "license", "packageId", "packageVersion", "authors", "replace")
                .stream().anyMatch(args::has))
            throw new IllegalArgumentException("Portable metadata requires format portable or mod");
        var authors = args.has("authors") ? args.getAsJsonArray("authors").asList().stream()
                .map(com.google.gson.JsonElement::getAsString).toList() : List.<String>of();
        var replace = args.has("replace") ? args.getAsJsonArray("replace").asList().stream()
                .map(number -> number.getAsBigDecimal().intValueExact()).toList() : List.<Integer>of();
        var romPaths = roms(args);
        var settings = new ProjectExport.PortableSettings(optionalString(args, "driver"),
                !flag(args, "noSource"), optionalString(args, "license"), optionalString(args, "packageId"),
                optionalString(args, "packageVersion"), authors, replace,
                args.has("speedUpTempo") ? args.get("speedUpTempo").getAsBigDecimal().intValueExact() : null,
                flag(args, "strictTempo"), flag(args, "retailPatch"), romPaths);
        var options = new ProjectExport.Options(format, decimal(args, "start"), decimal(args, "end"), decimal(args, "fade"),
                args.has("loops") ? args.get("loops").getAsBigDecimal().intValueExact() : null,
                flag(args, "stems"), flag(args, "zip"), settings);
        var warnings = new java.util.TreeSet<String>();
        var source = work ? input.resolve("arrangement.smps.json") : input;
        var project = portable || format == ProjectExport.Format.PACKAGE ? ProjectFiles.loadSource(source)
                : PortablePackages.openExportProject(source, romPaths, warnings::add);
        JsonObject result;
        if (portable) {
            result = ProjectExport.exportPortable(project, output, options, progress);
        } else {
            warnings.addAll(ProjectExport.write(project, work ? input : null, output, options, progress));
            result = new JsonObject();
        }
        if (result.has("warnings"))
            result.getAsJsonArray("warnings").forEach(value -> warnings.add(value.getAsString()));
        if (portable || format == ProjectExport.Format.PACKAGE || !warnings.isEmpty())
            result.add("warnings", McpJson.JSON.toJsonTree(warnings));
        result.addProperty("output", output.toString());
        result.addProperty("format", string(args, "format"));
        return result;
    }

    private static JsonObject validatePackage(JsonObject args) throws IOException {
        var input = McpPaths.input(string(args, "package"));
        var output = args.has("render") ? McpPaths.output(input.getParent(), string(args, "render")) : null;
        Integer frames = args.has("frames") ? args.get("frames").getAsBigDecimal().intValueExact() : null;
        return PortablePackages.validate(input, roms(args), output, frames);
    }

    private static List<Path> roms(JsonObject args) throws IOException {
        var roms = new ArrayList<Path>();
        if (args.has("roms"))
            for (var rom : args.getAsJsonArray("roms")) roms.add(McpPaths.input(rom.getAsString()));
        return List.copyOf(roms);
    }

    private static String optionalString(JsonObject args, String name) {
        return args.has(name) ? string(args, name) : null;
    }

    private static Double decimal(JsonObject args, String name) {
        return args.has(name) ? args.get(name).getAsDouble() : null;
    }

    private static JsonObject rerun(JsonObject args) throws IOException {
        var work = McpPaths.directory(string(args, "workDir"));
        var output = args.has("output") ? McpPaths.absolute(string(args, "output")) : work;
        if (output.equals(work)) {
            McpReconstruction.requireManaged(work);
        } else {
            output = McpPaths.newDirectory(output.toString());
        }
        var command = new ArrayList<>(List.of("reconstruct", "rerun", work.toString(), output.toString()));
        options(command, args, "keepVoicing", "overwriteDetached");
        if (args.has("search")) for (var directory : args.getAsJsonArray("search")) {
            command.add("--search");
            command.add(McpPaths.directory(directory.getAsString()).toString());
        }
        String summary = cli(command);
        return object(Map.of("workDir", output.toString(), "summary", summary,
                "warnings", ReconstructionStages.packageWarnings(output)));
    }

    private static String cli(List<String> command) throws IOException {
        // Capture CLI text in-process. stdout on the server belongs to JSON-RPC.
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        try (var out = new PrintStream(output, false, StandardCharsets.UTF_8);
             var err = new PrintStream(errors, false, StandardCharsets.UTF_8)) {
            if (Main.runForMcp(command.toArray(String[]::new), out, err) != 0) {
                String detail = errors.toString(StandardCharsets.UTF_8);
                int usage = detail.indexOf("\nUsage:");
                throw new IOException((usage < 0 ? detail : detail.substring(0, usage)).strip());
            }
        }
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}
