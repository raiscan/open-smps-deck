package com.opensmpsdeck.headless;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

public final class ReconstructionStages {
    static final int VERSION = 1;
    static final long MICROS_PER_SECOND = 1_000_000;

    @Target(ElementType.RECORD_COMPONENT)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Nullable {}

    enum Stage {
        TRANSCRIBE, STRUCTURE, ROLES, ALLOCATE, VOICE, ASSEMBLE;

        static Stage parse(String name) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException badStage) {
                throw new IllegalArgumentException("Unknown reconstruction stage: " + name, badStage);
            }
        }
    }

    static final Comparator<Note> NOTE_ORDER = Comparator.comparingDouble(Note::start)
            .thenComparingInt(Note::part).thenComparingInt(Note::pitch);

    record Note(String id, int part, double start, double end, int pitch, int velocity,
                String source, double confidence, @Nullable ReconstructionLeadAudio.Evidence motion,
                @Nullable List<ReconstructionFastRuns.Step> run, @Nullable ReconstructionBends.Bend bend) {
        Note(String id, int part, double start, double end, int pitch, int velocity, String source, double confidence,
             ReconstructionLeadAudio.Evidence motion, List<ReconstructionFastRuns.Step> run) {
            this(id, part, start, end, pitch, velocity, source, confidence, motion, run, null);
        }
        Note(String id, int part, double start, double end, int pitch, int velocity, String source, double confidence,
             ReconstructionLeadAudio.Evidence motion) {
            this(id, part, start, end, pitch, velocity, source, confidence, motion, null);
        }
        Note(String id, int part, double start, double end, int pitch, int velocity, String source, double confidence) {
            this(id, part, start, end, pitch, velocity, source, confidence, null);
        }

        Note withTiming(double from, double to) {
            return new Note(id, part, from, to, pitch, velocity, source, confidence, motion, run, bend);
        }

        Note withMotion(ReconstructionLeadAudio.Evidence evidence) {
            return new Note(id, part, start, end, pitch, velocity, source, confidence, evidence, run, bend);
        }

        Note withBend(ReconstructionBends.Bend evidence) {
            return new Note(id, part, start, end, pitch, velocity, source, confidence, motion, run, evidence);
        }

        Note mapTime(java.util.function.DoubleUnaryOperator clock) {
            return new Note(id, part, clock.applyAsDouble(start), clock.applyAsDouble(end), pitch, velocity,
                    source, confidence, motion, run == null ? null : run.stream().map(step -> step.map(clock)).toList(),
                    bend == null ? null : bend.map(clock));
        }

        int exitPitch() { return run == null ? pitch : run.getLast().pitch(); }

        MidiScore.Note midi() {
            return new MidiScore.Note(part, pitch, velocity, Math.round(start * MICROS_PER_SECOND),
                    Math.round(end * MICROS_PER_SECOND));
        }

        int startTick(int frames) {
            return Math.min(frames - 1, S3kReconstruction.toTicks(Math.round(start * MICROS_PER_SECOND)));
        }

        int endTick(int frames) {
            return Math.min(frames, Math.max(startTick(frames) + 1,
                    S3kReconstruction.toTicks(Math.round(end * MICROS_PER_SECOND))));
        }
    }

    record Inputs(String midi, String wav, String catalogue, String name, int ticksPerBeat, boolean keepOctaves,
                  boolean fmOnly, boolean wholePartVoices, boolean audioRefine, Map<Integer, String> stems,
                  @Nullable String candidates, double harmonyMaxHz, @Nullable ReconstructionTempo.Options grid,
                  @Nullable Boolean audioRepeats, @Nullable Map<Integer, ExternalTranscribers.PartNotes> externalNotes,
                  @Nullable Integer leadPart, @Nullable Boolean fastRunsOff,
                  @Nullable ReconstructionDrums.Source drumSource) {
        Inputs(String midi, String wav, String catalogue, String name, int ticksPerBeat, boolean keepOctaves,
               boolean fmOnly, boolean wholePartVoices, boolean audioRefine, Map<Integer, String> stems,
               String candidates, double harmonyMaxHz, ReconstructionTempo.Options grid, Boolean audioRepeats) {
            this(midi, wav, catalogue, name, ticksPerBeat, keepOctaves, fmOnly, wholePartVoices, audioRefine,
                    stems, candidates, harmonyMaxHz, grid, audioRepeats, null, null, null, null);
        }
        Inputs(String midi, String wav, String catalogue, String name, int ticksPerBeat, boolean keepOctaves,
               boolean fmOnly, boolean wholePartVoices, boolean audioRefine, Map<Integer, String> stems,
               String candidates, double harmonyMaxHz, ReconstructionTempo.Options grid, Boolean audioRepeats,
               Map<Integer, ExternalTranscribers.PartNotes> externalNotes, Integer leadPart) {
            this(midi, wav, catalogue, name, ticksPerBeat, keepOctaves, fmOnly, wholePartVoices, audioRefine,
                    stems, candidates, harmonyMaxHz, grid, audioRepeats, externalNotes, leadPart, null, null);
        }
        ReconstructionCli.Options options(long durationUs) {
            var paths = new java.util.TreeMap<Integer, Path>();
            stems.forEach((key, value) -> paths.put(key, Path.of(value)));
            return new ReconstructionCli.Options(durationUs, name, ticksPerBeat, keepOctaves, fmOnly,
                    wholePartVoices, audioRefine, paths);
        }
    }

    record ScoreDecision(Note note, String reason, String successorId) {}

    record Score(int version, long durationUs, int frames, int ticksPerSecond, @Nullable Inputs inputs,
                 List<ReconstructionStructure.Bar> bars, MidiScore source, Map<Integer, Integer> sourceCounts,
                 List<Note> notes, List<S3kReconstruction.Reduction> reductions,
                 List<ReconstructionPitchRefinement.Change> pitchChanges,
                 @Nullable ReconstructionTempo.Estimate tempo, @Nullable List<ReconstructionStructure.Bar> midiBars, @Nullable ReconstructionBeatMap beatMap,
                 @Nullable Map<String, List<ReconstructionFigureVoices.Voice>> figureVoices,
                 @Nullable List<ReconstructionRetriggers.Burst> retriggers,
                 @Nullable List<ScoreDecision> scoreDecisions) {
        Score(int version, long durationUs, int frames, int ticksPerSecond, Inputs inputs,
              List<ReconstructionStructure.Bar> bars, MidiScore source, Map<Integer, Integer> sourceCounts,
              List<Note> notes, List<S3kReconstruction.Reduction> reductions,
              List<ReconstructionPitchRefinement.Change> pitchChanges, ReconstructionTempo.Estimate tempo,
              List<ReconstructionStructure.Bar> midiBars) {
            this(version, durationUs, frames, ticksPerSecond, inputs, bars, source, sourceCounts, notes, reductions,
                    pitchChanges, tempo, midiBars, null, null, null, null);
        }
        Score(int version, long durationUs, int frames, int ticksPerSecond, Inputs inputs,
              List<ReconstructionStructure.Bar> bars, MidiScore source, Map<Integer, Integer> sourceCounts,
              List<Note> notes, List<S3kReconstruction.Reduction> reductions,
              List<ReconstructionPitchRefinement.Change> pitchChanges, ReconstructionTempo.Estimate tempo,
              List<ReconstructionStructure.Bar> midiBars, ReconstructionBeatMap beatMap) {
            this(version, durationUs, frames, ticksPerSecond, inputs, bars, source, sourceCounts, notes, reductions,
                    pitchChanges, tempo, midiBars, beatMap, null, null, null);
        }
        boolean usesBeatGrid() { return beatMap != null && tempo != null && tempo.source().equals("tracked"); }

        Score withInputs(Inputs inputs, List<ReconstructionStructure.Bar> bars, List<Note> notes,
                         ReconstructionTempo.Estimate tempo, List<ReconstructionStructure.Bar> midiBars, ReconstructionBeatMap beatMap) {
            return withInputs(inputs, bars, notes, tempo, midiBars, beatMap, figureVoices, retriggers, scoreDecisions);
        }

        Score withInputs(Inputs inputs, List<ReconstructionStructure.Bar> bars, List<Note> notes,
                         ReconstructionTempo.Estimate tempo, List<ReconstructionStructure.Bar> midiBars,
                         ReconstructionBeatMap beatMap, Map<String, List<ReconstructionFigureVoices.Voice>> voices,
                         List<ReconstructionRetriggers.Burst> bursts, List<ScoreDecision> decisions) {
            return new Score(version, durationUs, frames, ticksPerSecond, inputs, bars, source, sourceCounts,
                    notes, reductions, pitchChanges, tempo, midiBars, beatMap, voices, bursts, decisions);
        }
    }

    record Candidate(String id, int part, double start, double end, int pitch, int velocity) {}
    record Candidates(int version, List<Candidate> notes) {}

    private record StageOptions(String[] legacy, @Nullable Path candidates, @Nullable Double harmonyHz,
                                ReconstructionTempo.Options grid, boolean audioRepeats, @Nullable String transcriber,
                                @Nullable Path transcriberConfig, @Nullable String device, Map<Integer, String> transcribeParts,
                                Map<Integer, ExternalTranscribers.Import> imports, @Nullable Integer leadPart, boolean fastRunsOff,
                                @Nullable ReconstructionDrums.Source drumSource, java.util.Set<String> lanes, boolean refitLevels) {}
    private record Request(Path work, Stage from, boolean chain, @Nullable Score initial, @Nullable Double harmonyHz,
                           java.util.Set<String> lanes, boolean refitLevels) {}

    private ReconstructionStages() {}

    static boolean isCommand(String token) {
        return token.equals("run") || Arrays.stream(Stage.values()).anyMatch(stage -> stage.name().equalsIgnoreCase(token));
    }

    static void run(String command, String[] args, PrintStream out) throws IOException {
        run(command, args, out, false, (completed, total, message) -> {}, ignored -> {});
    }

    @FunctionalInterface
    public interface Progress {
        void report(int completed, int total, String message);
    }

    /** MCP shares the CLI parser and stage loop while retaining project review metadata. */
    public static List<String> runForReview(String command, String[] args, Progress progress) throws IOException {
        var changes = new java.util.ArrayList<String>();
        try (var out = new PrintStream(java.io.OutputStream.nullOutputStream())) {
            run(command, args, out, true, progress, changes::add);
        }
        return List.copyOf(changes);
    }

    private static void run(String command, String[] args, PrintStream out,
                            boolean preserveReview, Progress progress, java.util.function.Consumer<String> refitChange)
            throws IOException {
        try (var capture = new ReconstructionManifest.Capture()) {
            Request request = request(command, args, progress, capture);
            var run = new ReconstructionManifest.Run(stageName(request.from), request.harmonyHz);
            if (request.initial != null) {
                Files.createDirectory(request.work);
                capture.write(request.work, request.initial, List.of(args).subList(4, args.length), run);
            } else if (request.chain && !Files.exists(request.work.resolve(ReconstructionManifest.FILE))) {
                ReconstructionManifest.resumed(request.work, readScore(request.work.resolve("score.json")), run, capture);
            }
            execute(request, out, preserveReview, progress, refitChange);
            if (request.chain && request.initial == null) {
                ReconstructionManifest.resumed(request.work, readScore(request.work.resolve("score.json")), run, capture);
            }
        }
    }

    static void rerun(Path oldWork, Path work, List<Path> search, PrintStream out) throws IOException {
        if (Files.exists(work, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.nio.file.FileAlreadyExistsException(work.toString());
        var replay = ReconstructionManifest.resolve(oldWork, work, search);
        var inputs = replay.inputs();
        Files.createDirectory(work);
        ReconstructionManifest.writeReplay(work, replay);
        var edits = ReconstructionEdits.read(oldWork);
        if (!edits.isEmpty()) edits.write(work);
        var score = attach(transcribe(Path.of(inputs.midi()), Path.of(inputs.wav()),
                inputs.options(replay.manifest().durationUs())), inputs);
        execute(new Request(work, Stage.TRANSCRIBE, true, score, null, java.util.Set.of(), false), out, false,
                (completed, total, message) -> {}, ignored -> {});
    }

    private static void execute(Request request, PrintStream out, boolean preserveReview, Progress progress,
                                java.util.function.Consumer<String> refitChange) throws IOException {
        int total = request.chain ? Stage.values().length - request.from.ordinal() : 1;
        Path work = request.work;
        Path scoreFile = work.resolve("score.json");
        Path structureFile = work.resolve("structure.json");
        Path rolesFile = work.resolve("roles.json");
        Path allocationFile = work.resolve("allocation.json");
        Path voicingFile = work.resolve("voicing.json");
        var edits = ReconstructionEdits.read(work);
        var previousEdits = ReconstructionEdits.Journal.read(work);
        var editJournal = previousEdits.through(request.from);
        boolean trackEdits = !edits.isEmpty() || !previousEdits.stages().isEmpty();

        if (request.initial == null) {
            progress.report(0, total, "Starting " + stageName(request.from));
            if (preserveReview) checkInputFiles(readScore(scoreFile).inputs);
        }
        for (var stage : Stage.values()) {
            if (stage.ordinal() < request.from.ordinal()) continue;
            var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
            if (stage == Stage.TRANSCRIBE) {
                Score score = request.initial != null ? request.initial : retranscribe(readScore(scoreFile));
                score = edits.applyScore(score, outcomes);
                ReconstructionArtifacts.write(scoreFile, score);
            } else {
                var score = readScore(scoreFile);
                switch (stage) {
                    case STRUCTURE -> ReconstructionArtifacts.write(structureFile, structure(score,
                            editJournal.stages().getOrDefault("transcribe", List.of())));
                    case ROLES -> {
                        var structure = readStructure(structureFile, score);
                        double rate = request.harmonyHz == null ? score.inputs.harmonyMaxHz : request.harmonyHz;
                        Path planFile = work.resolve("roleplan.json");
                        ReconstructionRolePlan plan = null;
                        if (!Files.notExists(planFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            plan = ReconstructionArtifacts.read(planFile, ReconstructionRolePlan.class);
                            ReconstructionChecks.rolePlan(plan, structure, score);
                        }
                        var continuity = ReconstructionLeadContinuity.analyze(score, structure.notes());
                        var scoreEdits = editJournal.stages().getOrDefault("transcribe", List.of());
                        var roles = edits.assignRoles(score, structure, rate, plan, continuity, scoreEdits, outcomes);
                        ReconstructionArtifacts.write(rolesFile, roles);
                    }
                    case ALLOCATE -> {
                        var roles = ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class);
                        ReconstructionChecks.roles(roles, score);
                        var allocation = ReconstructionAllocation.allocate(roles, score.source.parts(),
                                ReconstructionTimeMap.of(score).frames(), score.inputs.fmOnly, !score.inputs.stems.isEmpty());
                        ReconstructionArtifacts.write(allocationFile, allocation);
                        outcomes.addAll(edits.allocationOutcomes(allocation, roles.lanePins()));
                    }
                    case VOICE -> {
                        // Resuming an existing voicing uses the user's edits.
                        // Refit when explicitly requested or when rebuilding from an upstream stage.
                        if (request.chain && request.from == Stage.VOICE && request.lanes.isEmpty()
                                && !request.refitLevels && Files.exists(voicingFile)) break;
                        var allocation = ReconstructionArtifacts.read(allocationFile, ReconstructionAllocation.Result.class);
                        ReconstructionChecks.allocation(allocation, score);
                        var previous = Files.notExists(voicingFile, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? null
                                : ReconstructionArtifacts.read(voicingFile, ReconstructionVoicing.Result.class);
                        var fitted = ReconstructionCli.fitVoicing(score, allocation, previous, request.lanes, request.refitLevels);
                        ReconstructionArtifacts.write(voicingFile, fitted);
                        if (previous != null) {
                            var lanes = new java.util.TreeSet<>(previous.lanes().keySet());
                            lanes.addAll(fitted.lanes().keySet());
                            for (String lane : lanes) {
                                var before = previous.lanes().get(lane);
                                var after = fitted.lanes().get(lane);
                                if (java.util.Objects.equals(before, after)) continue;
                                String change = "Refit changed " + lane;
                                if (before == null) change += " (added)";
                                else if (after == null) change += " (removed)";
                                else if (before.gain() != after.gain() || !java.util.Objects.equals(before.partGains(), after.partGains()))
                                    change += " (gain " + before.gain() + " → " + after.gain() + ", part gains "
                                            + before.partGains() + " → " + after.partGains() + ")";
                                out.println(change);
                                refitChange.accept(change);
                            }
                        }
                    }
                    case ASSEMBLE -> {
                        var structure = readStructure(structureFile, score);
                        var allocation = ReconstructionArtifacts.read(allocationFile, ReconstructionAllocation.Result.class);
                        ReconstructionChecks.allocation(allocation, score);
                        var voicing = ReconstructionArtifacts.read(voicingFile, ReconstructionVoicing.Result.class);
                        ReconstructionCli.assembleAllocated(work, score, structure, allocation, voicing, out, preserveReview);
                    }
                    default -> throw new AssertionError(stage);
                }
            }
            if (trackEdits) editJournal = editJournal.record(work, stage, outcomes, edits);
            for (var outcome : outcomes) {
                if (outcome.status() != ReconstructionEdits.Status.applied)
                    out.println(outcome.tag() + " " + outcome.status() + " " + outcome.detail()
                            + (outcome.missingNoteIds() == null || outcome.missingNoteIds().isEmpty()
                                ? "" : " " + outcome.missingNoteIds()));
            }
            progress.report(stage.ordinal() - request.from.ordinal() + 1, total, "Finished " + stageName(stage));
            if (!request.chain) break;
        }
    }

    public static final String STEADY_REFERENCE = ReconstructionCli.STEADY_REFERENCE;

    /** Use the assembly report, since score edits may not have been assembled yet. */
    public static boolean usesSteadyReference(Path report, Path requestedReference) throws IOException {
        if (!Files.isRegularFile(report) || Files.size(report) > 32L * 1024 * 1024) return false;
        try (var reader = Files.newBufferedReader(report)) {
            var json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            var tempo = json.getAsJsonObject("tempoCurve");
            if (tempo == null || !tempo.get("warped").getAsBoolean()) return false;
            var original = json.getAsJsonObject("inputs").getAsJsonObject("wav");
            return original.get("sha256").getAsString().equals(ReconstructionCli.audioHash(requestedReference));
        }
    }

    public static Path planTemplate(Path work) throws IOException {
        var score = readScore(work.resolve("score.json"));
        var structure = readStructure(work.resolve("structure.json"), score);
        Path path = work.resolve("roleplan.json");
        if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.nio.file.FileAlreadyExistsException(path.toString());
        var heuristic = ReconstructionRoles.assign(structure, score.source.parts(), score.inputs.harmonyMaxHz,
                null, null, null, score.inputs.leadPart);
        var plan = ReconstructionRolePlan.template(structure, heuristic);
        // CREATE_NEW also protects edits made concurrently after the existence check.
        Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(plan) + "\n",
                java.nio.file.StandardOpenOption.CREATE_NEW);
        return path;
    }

    private static String stageName(Stage stage) {
        return stage.name().toLowerCase(Locale.ROOT);
    }

    public static void writeArtifact(Path file, JsonElement value) throws IOException {
        Path work = file.getParent();
        if (file.getFileName().toString().equals("score.json")) {
            var score = ReconstructionArtifacts.decode(file, Score.class, value);
            ReconstructionChecks.score(score);
            checkInputFiles(score.inputs);
        } else {
            var score = readScore(work.resolve("score.json"));
            switch (file.getFileName().toString()) {
                case "structure.json" -> ReconstructionChecks.structure(
                        ReconstructionArtifacts.decode(file, ReconstructionStructure.Result.class, value), score);
                case "roleplan.json" -> ReconstructionChecks.rolePlan(
                        ReconstructionArtifacts.decode(file, ReconstructionRolePlan.class, value),
                        readStructure(work.resolve("structure.json"), score), score);
                case "roles.json" -> ReconstructionChecks.roles(
                        ReconstructionArtifacts.decode(file, ReconstructionRoles.Result.class, value), score);
                case "allocation.json" -> ReconstructionChecks.allocation(
                        ReconstructionArtifacts.decode(file, ReconstructionAllocation.Result.class, value), score);
                case "voicing.json" -> {
                    var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"),
                            ReconstructionAllocation.Result.class);
                    ReconstructionChecks.allocation(allocation, score);
                    ReconstructionVoicing.validate(
                            ReconstructionArtifacts.decode(file, ReconstructionVoicing.Result.class, value),
                            allocation, InstrumentCatalogue.open(Path.of(score.inputs.catalogue)), ReconstructionTimeMap.of(score).frames());
                }
                default -> throw new IllegalArgumentException("Unknown stage artifact: " + file.getFileName());
            }
        }
        ReconstructionArtifacts.write(file, value);
    }

    private static void checkInputFiles(Inputs inputs) {
        var paths = new ArrayList<>(List.of(inputs.midi, inputs.wav, inputs.catalogue));
        paths.addAll(inputs.stems.values());
        if (inputs.candidates != null) paths.add(inputs.candidates);
        for (String value : paths) {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !Files.isRegularFile(path))
                throw new IllegalArgumentException("score.json.inputs: expected an existing absolute file path: " + value);
        }
    }

    private static Request request(String command, String[] args, Progress progress,
                                   ReconstructionManifest.Capture capture) throws IOException {
        boolean chain = command.equals("run");
        Stage stage = chain ? Stage.TRANSCRIBE : Stage.parse(command);
        if (chain && args.length >= 1 && args[0].equals("--from")) {
            if (args.length < 3) throw new IllegalArgumentException("reconstruct run --from expects STAGE WORK_DIR");
            stage = Stage.parse(args[1]);
            return resume(args, 2, stage, true);
        }
        if (chain && args.length >= 3 && args[1].equals("--from")) {
            var reordered = args.clone();
            reordered[2] = args[0];
            return resume(reordered, 2, Stage.parse(args[2]), true);
        }
        if (stage != Stage.TRANSCRIBE) return resume(args, 0, stage, false);
        if (args.length < 4)
            throw new IllegalArgumentException("reconstruct " + command + " expects MIDI WAV INDEX.json WORK_DIR");
        Path midi = absolute(args[0]), reference = absolute(args[1]), index = absolute(args[2]), work = absolute(args[3]);
        if (Files.exists(work, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(work.toString());
        var extra = stageOptions(args, 4);
        if (!extra.lanes.isEmpty() || extra.refitLevels)
            throw new IllegalArgumentException("--lane and --refit-levels require an existing work directory");
        String[] legacy = java.util.stream.Stream.concat(java.util.stream.Stream.of("reconstruct"),
                Arrays.stream(extra.legacy)).toArray(String[]::new);
        var options = ReconstructionCli.parseOptions(legacy, midi.getFileName().toString());
        double harmonyHz = extra.harmonyHz == null ? ReconstructionRoles.DEFAULT_HARMONY_HZ : extra.harmonyHz;
        var stems = new java.util.TreeMap<Integer, String>();
        options.stems().forEach((id, file) -> stems.put(id, file.toAbsolutePath().normalize().toString()));
        progress.report(0, chain ? Stage.values().length : 1, "Starting transcribe");
        var score = transcribe(midi, reference, options);
        var external = ExternalTranscribers.collect(score.source, stems, extra.transcriber, extra.transcriberConfig,
                extra.transcribeParts, extra.device == null ? "auto" : extra.device, extra.imports, capture);
        var inputs = new Inputs(midi.toString(), reference.toString(), index.toString(), options.name(),
                options.ticksPerBeat(), options.keepOctaves(), options.fmOnly(), options.wholePartVoices(),
                options.audioRefine(), stems, extra.candidates == null ? null : extra.candidates.toString(), harmonyHz,
                extra.grid, extra.audioRepeats, external, extra.leadPart, extra.fastRunsOff ? true : null, extra.drumSource);
        return new Request(work, stage, chain, attach(score, inputs), extra.harmonyHz, extra.lanes, extra.refitLevels);
    }

    private static Request resume(String[] args, int workIndex, Stage stage, boolean chain) {
        if (args.length <= workIndex) throw new IllegalArgumentException("Expected a reconstruction WORK_DIR");
        var extra = stageOptions(Arrays.copyOfRange(args, workIndex + 1, args.length), 0);
        if (extra.legacy.length != 0 || extra.candidates != null || extra.grid.force()
                || extra.transcriber != null || !extra.imports.isEmpty() || extra.leadPart != null || extra.fastRunsOff
                || extra.grid.bpm() != null || extra.grid.steady() != null || extra.audioRepeats
                || (!extra.lanes.isEmpty() || extra.refitLevels) && (chain ? stage.ordinal() > Stage.VOICE.ordinal() : stage != Stage.VOICE)
                || extra.harmonyHz != null && (chain ? stage.ordinal() > Stage.ROLES.ordinal() : stage != Stage.ROLES))
            throw new IllegalArgumentException("Unsupported options when resuming reconstruct " + stage.name().toLowerCase(Locale.ROOT));
        return new Request(absolute(args[workIndex]), stage, chain, null, extra.harmonyHz, extra.lanes, extra.refitLevels);
    }

    private static StageOptions stageOptions(String[] args, int firstOption) {
        var lanes = new java.util.LinkedHashSet<String>();
        boolean refitLevels = false;
        Path candidates = null;
        Double hz = null, bpm = null, offset = null;
        Integer leadPart = null;
        ReconstructionDrums.Source drumSource = null;
        boolean estimate = false, audioRepeats = false, fastRunsOff = false;
        String steady = null, transcriber = null, device = null;
        Path transcriberConfig = null;
        var parts = new java.util.TreeMap<Integer, String>();
        var imports = new java.util.TreeMap<Integer, ExternalTranscribers.Import>();
        var legacy = new ArrayList<>(Arrays.asList(args).subList(0, firstOption));
        for (int i = firstOption; i < args.length; i++) {
            switch (args[i]) {
                case "--estimate-tempo" -> estimate = true;
                case "--no-fast-runs" -> fastRunsOff = true;
                case "--audio-repeats" -> audioRepeats = true;
                case "--tempo" -> {
                    if (bpm != null || ++i == args.length) throw new IllegalArgumentException("Expected one --tempo BPM");
                    bpm = Double.parseDouble(args[i]);
                }
                case "--steady-tempo" -> {
                    if (steady != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --steady-tempo off|auto|BPM");
                    steady = args[i];
                }
                case "--bar-offset" -> {
                    if (offset != null || ++i == args.length) throw new IllegalArgumentException("Expected one --bar-offset S");
                    offset = Double.parseDouble(args[i]);
                }
                case "--candidates" -> {
                    if (candidates != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --candidates NOTES.json");
                    candidates = absolute(args[i]);
                }
                case "--transcriber" -> {
                    if (transcriber != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --transcriber NAME");
                    transcriber = args[i];
                }
                case "--transcriber-config" -> {
                    if (transcriberConfig != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --transcriber-config FILE");
                    transcriberConfig = absolute(args[i]);
                }
                case "--transcriber-device" -> {
                    if (device != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --transcriber-device DEVICE");
                    device = args[i];
                }
                case "--transcribe-part", "--replace-notes", "--add-notes" -> {
                    String flag = args[i];
                    if (++i == args.length) throw new IllegalArgumentException("Missing argument to " + flag);
                    String[] assignment = args[i].split("=", 2);
                    if (assignment.length != 2 || assignment[1].isBlank())
                        throw new IllegalArgumentException("Expected " + flag + " PART=" +
                                (flag.equals("--transcribe-part") ? "ROLE" : "FILE"));
                    int part = Integer.parseInt(assignment[0]);
                    if (flag.equals("--transcribe-part")) {
                        if (parts.putIfAbsent(part, assignment[1]) != null)
                            throw new IllegalArgumentException("Duplicate external role for part " + part);
                    } else if (imports.putIfAbsent(part, new ExternalTranscribers.Import(
                            absolute(assignment[1]), flag.equals("--replace-notes"))) != null) {
                        throw new IllegalArgumentException("Duplicate notes import for part " + part);
                    }
                }
                case "--drum-source" -> {
                    if (drumSource != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --drum-source catalogue|sliced|auto");
                    try {
                        drumSource = ReconstructionDrums.Source.valueOf(args[i]);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException("--drum-source expects catalogue, sliced or auto");
                    }
                }
                case "--lead-part" -> {
                    if (leadPart != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --lead-part PART");
                    leadPart = Integer.parseInt(args[i]);
                }
                case "--lane" -> {
                    if (++i == args.length || !args[i].matches("FM[1-5]|PSG[1-3]|DAC") || !lanes.add(args[i]))
                        throw new IllegalArgumentException("--lane expects a distinct reconstruction lane");
                }
                case "--refit-levels" -> refitLevels = true;
                case "--harmony-max-hz" -> {
                    if (hz != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --harmony-max-hz RATE");
                    hz = Double.parseDouble(args[i]);
                    ReconstructionChecks.rate(hz);
                }
                case "--seconds", "--name", "--ticks-per-beat", "--stem" -> {
                    legacy.add(args[i]);
                    if (++i == args.length) throw new IllegalArgumentException("Missing argument to " + args[i - 1]);
                    legacy.add(args[i]);
                }
                default -> legacy.add(args[i]);
            }
        }
        if (transcriber == null && (!parts.isEmpty() || transcriberConfig != null || device != null))
            throw new IllegalArgumentException("External transcription options require --transcriber NAME");
        if (transcriber != null && parts.isEmpty())
            throw new IllegalArgumentException("--transcriber " + transcriber + " requires --transcribe-part PART=ROLE");
        return new StageOptions(legacy.toArray(String[]::new), candidates, hz,
                new ReconstructionTempo.Options(estimate, bpm, offset, steady), audioRepeats,
                transcriber, transcriberConfig, device, parts, imports, leadPart, fastRunsOff, drumSource, java.util.Set.copyOf(lanes), refitLevels);
    }

    private static Path absolute(String path) { return Path.of(path).toAbsolutePath().normalize(); }

    static Score readScore(Path file) throws IOException {
        Score score = ReconstructionArtifacts.read(file, Score.class);
        ReconstructionChecks.score(score);
        return score;
    }

    static ReconstructionStructure.Result readStructure(Path file, Score score) throws IOException {
        var structure = ReconstructionArtifacts.read(file, ReconstructionStructure.Result.class);
        ReconstructionChecks.structure(structure, score);
        return new ReconstructionStructure.Result(structure.version(), structure.bars(), structure.sections(),
                structure.repeats(), structure.corrections(), structure.notes().stream().sorted(NOTE_ORDER).toList(),
                structure.phrases(), structure.roleLayout(), structure.timeBase(), structure.scoreNoteHashes());
    }

    static ReconstructionStructure.Result structure(Score score) throws IOException {
        return structure(score, List.of());
    }

    private static ReconstructionStructure.Result structure(Score score, List<ReconstructionEdits.Outcome> edits)
            throws IOException {
        boolean inferred = score.tempo != null && !score.tempo.source().equals("midi");
        boolean tracker = score.usesBeatGrid();
        double seconds = score.durationUs / 1e6;
        var bars = tracker ? score.beatMap.bars(seconds) : score.bars;
        var audio = Boolean.TRUE.equals(score.inputs.audioRepeats)
                ? ReconstructionRhythmAudio.read(Path.of(score.inputs.wav), seconds) : null;
        var timeline = ReconstructionTimeMap.of(score);
        var notes = score.notes.stream().map(timeline::projectNote).sorted(NOTE_ORDER).toList();
        var roleBars = timeline.projectBars(score.midiBars);
        if (timeline.warped() && tracker && score.midiBars != null
                && (score.inputs.grid == null || !score.inputs.grid.force())) {
            // The placeholder MIDI clock supplies fixed contour windows. Lay
            // out those windows on the new clock, independently of the tracked
            // musical bar boundaries.
            roleBars = MidiScore.bars(Path.of(score.inputs.midi), timeline.projectDuration());
        }
        var authoredIds = new java.util.HashSet<String>();
        var suppressed = new ArrayList<Note>();
        for (var edit : edits) {
            if (edit.status() != ReconstructionEdits.Status.applied) continue;
            authoredIds.addAll(edit.noteIds());
            if (edit.original() != null) suppressed.add(timeline.projectNote(edit.original()));
        }
        return ReconstructionStructure.analyze(timeline.projectBars(bars), notes, score.source.parts(), audio,
                inferred, tracker ? score.beatMap.beatsPerBar() : inferred ? score.tempo.selected().beatsPerBar() : 4,
                roleBars, timeline, authoredIds, suppressed);
    }

    private static boolean trackedGrid(ReconstructionBeatMap beats, ReconstructionTempo.Options grid) {
        return beats != null && grid.bpm() == null
                && beats.confidence() >= ReconstructionTimeMap.MIN_CONFIDENCE && beats.deviation() >= .006;
    }

    private static Score retranscribe(Score saved) throws IOException {
        var inputs = saved.inputs;
        var score = transcribe(Path.of(inputs.midi), Path.of(inputs.wav), inputs.options(saved.durationUs));
        return attach(score, inputs);
    }

    private static Score attach(Score score, Inputs inputs) throws IOException {
        var notes = new ArrayList<>(score.notes);
        if (inputs.externalNotes != null) {
            double limit = score.durationUs / (double) MICROS_PER_SECOND;
            for (var entry : inputs.externalNotes.entrySet()) {
                int part = entry.getKey();
                ExternalTranscribers.validatePart(part, score.source.parts());
                var imported = entry.getValue();
                if (imported.provenance().replace()) notes.removeIf(note -> note.part == part);
                int index = 0;
                for (var raw : imported.notes()) {
                    if (raw.start() < limit && raw.pitch() >= 12 && raw.pitch() <= 106) {
                        notes.add(new Note("transcriber-" + part + "-" + index, part, raw.start(),
                                Math.min(limit, raw.end()), raw.pitch(), raw.velocity(), "transcriber", 1));
                    }
                    index++;
                }
            }
        }
        if (inputs.candidates != null) {
            var file = Path.of(inputs.candidates);
            var candidates = ReconstructionArtifacts.read(file, Candidates.class);
            var ids = new java.util.HashSet<String>();
            var parts = score.source.parts().stream().filter(part -> !part.drums()).map(MidiScore.Part::id)
                    .collect(java.util.stream.Collectors.toSet());
            for (var candidate : candidates.notes) {
                var note = new Note("external-" + candidate.id, candidate.part, candidate.start, candidate.end,
                        candidate.pitch, candidate.velocity, "external", .35);
                ReconstructionChecks.note(note, parts, Double.POSITIVE_INFINITY, file.toString());
                ReconstructionArtifacts.require(!candidate.id.isBlank() && ids.add(candidate.id), file.toString(),
                        "candidate IDs must be nonblank and unique");
                ReconstructionArtifacts.require(candidate.end > candidate.start, file.toString(),
                        "candidate release must follow its attack");
                if (candidate.start < score.durationUs / (double) MICROS_PER_SECOND) {
                    notes.add(new Note(note.id, note.part, note.start, Math.min(note.end, score.durationUs / 1_000_000.0),
                            note.pitch, note.velocity, note.source, note.confidence));
                }
            }
        }
        var voices = new java.util.TreeMap<String, List<ReconstructionFigureVoices.Voice>>();
        var bursts = new ArrayList<ReconstructionRetriggers.Burst>();
        var decisions = new ArrayList<ScoreDecision>();
        boolean fastRuns = !Boolean.TRUE.equals(inputs.fastRunsOff);
        for (var part : score.source.parts()) {
            String stem = inputs.stems.get(part.id());
            if (part.drums() || stem == null || notes.stream().noneMatch(note -> note.part == part.id())) continue;
            boolean leadCandidate = inputs.leadPart != null && inputs.leadPart == part.id()
                    || part.program() < 32 || part.program() > 39;
            if (!fastRuns && !leadCandidate) continue;
            var audio = ReconstructionLeadAudio.readMono(Path.of(stem), score.durationUs / 1e6);
            if (fastRuns) {
                var local = notes.stream().filter(note -> note.part == part.id()).toList();
                ReconstructionFastRuns.reconcile(notes, part.id(), ReconstructionFastRuns.detect(audio,
                        inputs.leadPart == null || inputs.leadPart == part.id()), decisions);
                if (inputs.leadPart != null && inputs.leadPart == part.id()) {
                    var current = notes.stream().filter(note -> note.part == part.id()).toList();
                    for (var burst : ReconstructionRetriggers.detect(audio, current)) {
                        bursts.add(burst);
                        var companions = ReconstructionFigureVoices.find(audio, burst.figure(), local);
                        if (!companions.isEmpty()) voices.put(burst.figure().id(), companions);
                    }
                }
                for (var figure : notes) {
                    if (figure.part != part.id() || figure.run == null) continue;
                    var companions = ReconstructionFigureVoices.find(audio, figure, local);
                    if (!companions.isEmpty()) voices.put(figure.id, companions);
                }
            }
            if (leadCandidate) {
                var tracker = new ReconstructionBends.Tracker(audio);
                for (int i = 0; i < notes.size(); i++) {
                    var note = notes.get(i);
                    if (note.part != part.id()) continue;
                    var bend = tracker.detect(note);
                    if (bend != null) notes.set(i, note.withBend(bend));
                }
            }
        }
        Path midi = Path.of(inputs.midi);
        var metadata = MidiScore.gridMetadata(midi);
        var grid = inputs.grid == null ? new ReconstructionTempo.Options(false, null, null) : inputs.grid;
        ReconstructionTempo.Estimate tempo = null;
        ReconstructionBeatMap beats = null;
        double seconds = score.durationUs / 1e6;
        if (grid.bpm() != null) {
            tempo = ReconstructionTempo.override(grid, metadata.meter());
            beats = ReconstructionBeatMap.constant(tempo.selected(), seconds);
        } else if (grid.force() || metadata.defaultTempo()) {
            var audio = seconds < .01 ? List.<ReconstructionRhythmAudio.Onset>of()
                    : ReconstructionRhythmAudio.read(Path.of(inputs.wav), seconds, false).onsets();
            beats = ReconstructionBeatTracker.track(score.source, seconds, audio, metadata.meter());
            tempo = ReconstructionTempo.estimate(score.source, seconds, audio, metadata.meter());
            if (!grid.force() && (tempo.confidence() < .52 || tempo.midiEvidence() < .52)) {
                tempo = new ReconstructionTempo.Estimate(tempo.selected(), tempo.confidence(), tempo.midiEvidence(),
                        tempo.audioEvidence(), tempo.alternatives(), "midi");
            }
        }
        boolean estimated = tempo != null && !tempo.source().equals("midi");
        var bars = estimated ? ReconstructionTempo.bars(tempo.selected(), seconds) : MidiScore.bars(midi, score.durationUs);
        var midiBars = estimated && !grid.force() && grid.bpm() == null ? MidiScore.bars(midi, score.durationUs) : null;
        if (trackedGrid(beats, grid)) {
            // Keep the existing role window layout when changing musical bars.
            if (midiBars == null) midiBars = bars;
            bars = beats.bars(seconds);
            double bpm = beats.openingBpm(), period = 60 / bpm;
            double downbeat = beats.seconds(beats.downbeat());
            var selected = new ReconstructionTempo.Grid(bpm, ReconstructionTempo.modulo(downbeat, period),
                    ReconstructionTempo.modulo(downbeat, period * beats.beatsPerBar()), beats.beatsPerBar(), beats.confidence());
            tempo = new ReconstructionTempo.Estimate(selected, beats.confidence(), tempo.midiEvidence(),
                    tempo.audioEvidence(), tempo.alternatives(), "tracked");
        }
        if (grid.steady() != null && !List.of("auto", "off").contains(grid.steady()) && beats == null) {
            if (estimated) beats = ReconstructionBeatMap.constant(tempo.selected(), seconds);
            else beats = MidiScore.beatMap(midi, score.durationUs, metadata.meter());
        }
        var result = score.withInputs(inputs, bars, notes.stream().sorted(NOTE_ORDER).toList(), tempo, midiBars, beats,
                voices.isEmpty() ? null : voices, bursts.isEmpty() ? null : List.copyOf(bursts),
                decisions.isEmpty() ? null : List.copyOf(decisions));
        ReconstructionChecks.score(result);
        return result;
    }

    static String midiNoteKey(MidiScore.Note note) {
        return "n-" + note.partId() + "-" + note.startUs() + "-" + note.pitch();
    }

    static String noteInstance(String key, int instance) {
        return instance == 1 ? key : key + "-" + instance;
    }

    static Score transcribe(Path midi, Path reference, ReconstructionCli.Options options) throws IOException {
        long sourceDurationUs = ReferenceAudio.durationMicros(reference);
        long durationUs = options.durationUs() == null ? sourceDurationUs : options.durationUs();
        if (durationUs > sourceDurationUs) throw new IllegalArgumentException("--seconds exceeds reference WAV duration");
        long presentations = (durationUs * PRESENTATIONS_PER_SECOND + MICROS_PER_SECOND - 1) / MICROS_PER_SECOND;
        if (presentations < 1 || presentations > S3kRenderer.MAX_PRESENTATIONS)
            throw new IllegalArgumentException("Reconstruction interval must fit in "
                    + S3kRenderer.MAX_PRESENTATIONS + " presentations. Use --seconds for a shorter interval.");
        int frames = (int) presentations;
        MidiScore original = MidiScore.read(midi);
        for (int part : options.stems().keySet()) {
            if (original.parts().stream().noneMatch(p -> p.id() == part))
                throw new IllegalArgumentException("Unknown MIDI part ID for --stem: " + part);
            ReferenceAudio.durationMicros(options.stems().get(part));
        }
        var refinement = options.audioRefine()
                ? ReconstructionPitchRefinement.refine(original, reference, options.stems(), durationUs)
                : new ReconstructionPitchRefinement.Result(original, List.of());
        MidiScore score = refinement.score();
        var prepared = S3kReconstruction.prepare(score, durationUs, options.keepOctaves());
        var counts = new LinkedHashMap<Integer, Integer>();
        var originalEnds = new HashMap<MidiScore.Note, Integer>();
        for (var note : original.notes()) {
            if (note.startUs() >= durationUs) break;
            counts.merge(note.partId(), 1, Integer::sum);
            var clipped = new MidiScore.Note(note.partId(), note.pitch(), note.velocity(), note.startUs(),
                    Math.min(note.endUs(), durationUs));
            originalEnds.merge(clipped, 1, Integer::sum);
        }
        var notes = new ArrayList<Note>();
        var ids = new HashMap<String, Integer>();
        for (var note : prepared.notes()) {
            int remaining = originalEnds.getOrDefault(note, 0);
            if (remaining != 0) originalEnds.put(note, remaining - 1);
            String key = midiNoteKey(note);
            int instance = ids.merge(key, 1, Integer::sum);
            notes.add(new Note(noteInstance(key, instance), note.partId(),
                    note.startUs() / (double) MICROS_PER_SECOND, note.endUs() / (double) MICROS_PER_SECOND,
                    note.pitch(), note.velocity(), remaining == 0 ? "audio-refine" : "midi", remaining == 0 ? .85 : 1));
        }
        return new Score(VERSION, durationUs, frames, PRESENTATIONS_PER_SECOND, null, List.of(), score, counts, notes,
                prepared.reductions(), refinement.changes(), null, null);
    }
}
