package com.opensmpsdeck.headless;

import com.google.gson.JsonElement;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.LoadedProject;
import com.opensmpsdeck.project.TargetProfile;
import com.opensmpsdeck.project.TargetClock;

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
import java.util.TreeMap;
import java.util.Set;

public final class ReconstructionStages {
    static final int VERSION = 1;
    static final long MICROS_PER_SECOND = 1_000_000;

    @Target(ElementType.RECORD_COMPONENT)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Nullable {}

    enum Stage {
        TRANSCRIBE, STRUCTURE, ROLES, VOICES, ALLOCATE, VOICE, ASSEMBLE;

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

        Note withVelocity(int level) {
            return new Note(id, part, start, end, pitch, level, source, confidence, motion, run, bend);
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
            return startTick(frames, TargetClock.FRAME_CLOCK);
        }

        int startTick(int ticks, TargetClock clock) {
            return Math.min(ticks - 1, S3kReconstruction.toTicks(Math.round(start * MICROS_PER_SECOND), clock));
        }

        int endTick(int frames) {
            return endTick(frames, TargetClock.FRAME_CLOCK);
        }

        int endTick(int ticks, TargetClock clock) {
            return Math.min(ticks, Math.max(startTick(ticks, clock) + 1,
                    S3kReconstruction.toTicks(Math.round(end * MICROS_PER_SECOND), clock)));
        }
    }

    record Inputs(@Nullable String midi, String wav, String catalogue, String name, int ticksPerBeat, boolean keepOctaves,
                  boolean fmOnly, boolean wholePartVoices, boolean audioRefine, Map<Integer, String> stems,
                  @Nullable String candidates, double harmonyMaxHz, @Nullable ReconstructionTempo.Options grid,
                  @Nullable Boolean audioRepeats, @Nullable Map<Integer, ExternalTranscribers.PartNotes> externalNotes,
                  @Nullable Integer leadPart, @Nullable Boolean fastRunsOff,
                  @Nullable ReconstructionDrums.Source drumSource, @Nullable Boolean stemTemplateNotesOff,
                  @Nullable ReconstructionAudioOnly.Provenance audioOnly, @Nullable String target,
                  @Nullable ReconstructionDrums.KitMode drumKit) {
        TargetGame targetGame() { return target == null ? TargetGame.S3K : TargetGame.parse(target); }

        TargetProfile profile() { return TargetProfile.of(targetGame()); }

        Inputs(String midi, String wav, String catalogue, String name, int ticksPerBeat, boolean keepOctaves,
               boolean fmOnly, boolean wholePartVoices, boolean audioRefine, Map<Integer, String> stems,
               String candidates, double harmonyMaxHz, ReconstructionTempo.Options grid, Boolean audioRepeats,
               Map<Integer, ExternalTranscribers.PartNotes> externalNotes, Integer leadPart, Boolean fastRunsOff,
               ReconstructionDrums.Source drumSource, Boolean stemTemplateNotesOff,
               ReconstructionAudioOnly.Provenance audioOnly) {
            this(midi, wav, catalogue, name, ticksPerBeat, keepOctaves, fmOnly, wholePartVoices, audioRefine, stems,
                    candidates, harmonyMaxHz, grid, audioRepeats, externalNotes, leadPart, fastRunsOff, drumSource,
                    stemTemplateNotesOff, audioOnly, null, null);
        }

        Inputs(String midi, String wav, String catalogue, String name, int ticksPerBeat, boolean keepOctaves,
               boolean fmOnly, boolean wholePartVoices, boolean audioRefine, Map<Integer, String> stems,
               String candidates, double harmonyMaxHz, ReconstructionTempo.Options grid, Boolean audioRepeats) {
            this(midi, wav, catalogue, name, ticksPerBeat, keepOctaves, fmOnly, wholePartVoices, audioRefine,
                    stems, candidates, harmonyMaxHz, grid, audioRepeats, null, null, null, null, null, null);
        }
        Inputs(String midi, String wav, String catalogue, String name, int ticksPerBeat, boolean keepOctaves,
               boolean fmOnly, boolean wholePartVoices, boolean audioRefine, Map<Integer, String> stems,
               String candidates, double harmonyMaxHz, ReconstructionTempo.Options grid, Boolean audioRepeats,
               Map<Integer, ExternalTranscribers.PartNotes> externalNotes, Integer leadPart) {
            this(midi, wav, catalogue, name, ticksPerBeat, keepOctaves, fmOnly, wholePartVoices, audioRefine,
                    stems, candidates, harmonyMaxHz, grid, audioRepeats, externalNotes, leadPart, null, null, null, null);
        }
        Inputs withLeadPart(Integer lead) {
            return new Inputs(midi, wav, catalogue, name, ticksPerBeat, keepOctaves, fmOnly, wholePartVoices,
                    audioRefine, stems, candidates, harmonyMaxHz, grid, audioRepeats, externalNotes, lead,
                    fastRunsOff, drumSource, stemTemplateNotesOff, audioOnly, target, drumKit);
        }

        ReconstructionCli.Options options(long durationUs) {
            var paths = new java.util.TreeMap<Integer, Path>();
            stems.forEach((key, value) -> paths.put(key, Path.of(value)));
            return new ReconstructionCli.Options(durationUs, name, ticksPerBeat, keepOctaves, fmOnly,
                    wholePartVoices, audioRefine, paths, targetGame(), drumKit);
        }
    }

    record ScoreDecision(Note note, String reason, String successorId) {}

    record Score(int version, long durationUs, int frames, Number ticksPerSecond, @Nullable Inputs inputs,
                 List<ReconstructionStructure.Bar> bars, MidiScore source, Map<Integer, Integer> sourceCounts,
                 List<Note> notes, List<S3kReconstruction.Reduction> reductions,
                 List<ReconstructionPitchRefinement.Change> pitchChanges,
                 @Nullable ReconstructionTempo.Estimate tempo, @Nullable List<ReconstructionStructure.Bar> midiBars, @Nullable ReconstructionBeatMap beatMap,
                 @Nullable Map<String, List<ReconstructionFigureVoices.Voice>> figureVoices,
                 @Nullable List<ReconstructionRetriggers.Burst> retriggers,
                 @Nullable List<ScoreDecision> scoreDecisions) {
        Score(int version, long durationUs, int frames, Number ticksPerSecond, Inputs inputs,
              List<ReconstructionStructure.Bar> bars, MidiScore source, Map<Integer, Integer> sourceCounts,
              List<Note> notes, List<S3kReconstruction.Reduction> reductions,
              List<ReconstructionPitchRefinement.Change> pitchChanges, ReconstructionTempo.Estimate tempo,
              List<ReconstructionStructure.Bar> midiBars) {
            this(version, durationUs, frames, ticksPerSecond, inputs, bars, source, sourceCounts, notes, reductions,
                    pitchChanges, tempo, midiBars, null, null, null, null);
        }
        Score(int version, long durationUs, int frames, Number ticksPerSecond, Inputs inputs,
              List<ReconstructionStructure.Bar> bars, MidiScore source, Map<Integer, Integer> sourceCounts,
              List<Note> notes, List<S3kReconstruction.Reduction> reductions,
              List<ReconstructionPitchRefinement.Change> pitchChanges, ReconstructionTempo.Estimate tempo,
              List<ReconstructionStructure.Bar> midiBars, ReconstructionBeatMap beatMap) {
            this(version, durationUs, frames, ticksPerSecond, inputs, bars, source, sourceCounts, notes, reductions,
                    pitchChanges, tempo, midiBars, beatMap, null, null, null);
        }
        TargetProfile profile() {
            var profile = inputs == null ? TargetProfile.S3K : inputs.profile();
            // R1 tagged classic projects still used a 60 Hz arrangement grid.
            return profile.game() != TargetGame.S3K && ticksPerSecond != null && ticksPerSecond.doubleValue() == 60
                    ? profile.withClock(TargetClock.FRAME_CLOCK) : profile;
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
                                Map<Integer, ExternalTranscribers.Import> imports, Map<Path, String> unmapped,
                                @Nullable Integer leadPart, boolean fastRunsOff, boolean stemTemplateNotesOff,
                                @Nullable ReconstructionDrums.Source drumSource, java.util.Set<String> lanes,
                                boolean refitLevels, boolean keepVoicing, @Nullable String variant, boolean overwriteDetached,
                                @Nullable String lanePolicyPreset, boolean policyDecisions,
                                Map<String, Path> audioStems, Map<String, String> stemRoles, @Nullable String drumDetector,
                                Map<String, String> transcriberFor, @Nullable Path separatorConfig) {}
    private record Transcription(Score score, List<ReconstructionStemTemplates.Candidate> candidates,
                                 List<ReconstructionBendRecovery.Candidate> approaches) {}
    private record Request(Path work, Stage from, boolean chain, @Nullable Transcription initial, @Nullable Double harmonyHz,
                           java.util.Set<String> lanes, boolean refitLevels, boolean keepVoicing, @Nullable String variant,
                           boolean overwriteDetached, @Nullable String lanePolicyPreset, boolean policyDecisions) {}

    private ReconstructionStages() {}

    static boolean isCommand(String token) {
        return token.equals("run") || Arrays.stream(Stage.values()).anyMatch(stage -> stage.name().equalsIgnoreCase(token));
    }

    static void run(String command, String[] args, PrintStream out, PrintStream err) throws IOException {
        run(command, args, out, err, false, (completed, total, message) -> {}, ignored -> {}, null);
    }

    @FunctionalInterface
    public interface Progress {
        void report(int completed, int total, String message);
    }

    /** MCP shares the CLI parser and stage loop while retaining project review metadata. */
    public static List<String> runForReview(String command, String[] args, Progress progress) throws IOException {
        var changes = new java.util.ArrayList<String>();
        try (var out = new PrintStream(java.io.OutputStream.nullOutputStream())) {
            run(command, args, out, out, true, progress, changes::add, null);
        }
        return List.copyOf(changes);
    }

    record LiveCompilation(TargetPlayback playback, ReconstructionAllocation.Result allocation) {}

    private record LiveRun(LoadedProject existing, java.util.function.Consumer<LiveCompilation> result) {}

    static LiveCompilation runForLive(String[] args, Progress progress, LoadedProject existing) throws IOException {
        var result = new LiveCompilation[1];
        try (var out = new PrintStream(java.io.OutputStream.nullOutputStream())) {
            run("run", args, out, out, true, progress, ignored -> {}, new LiveRun(existing, playback -> result[0] = playback));
        }
        if (result[0] == null) throw new IOException("The reconstruction did not assemble");
        return result[0];
    }

    private static void run(String command, String[] args, PrintStream out, PrintStream err,
                            boolean preserveReview, Progress progress, java.util.function.Consumer<String> refitChange,
                            LiveRun live)
            throws IOException {
        try (var capture = new ReconstructionManifest.Capture()) {
            Request request = request(command, args, progress, err, capture);
            if (request.chain && request.from != Stage.TRANSCRIBE) {
                var edits = ReconstructionEdits.read(request.work).accepted();
                var built = ReconstructionEdits.Journal.read(request.work);
                if (!java.util.Objects.equals(edits.scoreRecipe(request.work), built.scoreRecipe())) {
                    out.println("Rebuilding from transcribe because the score edit recipe changed");
                    request = new Request(request.work, Stage.TRANSCRIBE, true, null, request.harmonyHz,
                            request.lanes, request.refitLevels, request.keepVoicing, request.variant,
                            request.overwriteDetached, request.lanePolicyPreset, request.policyDecisions);
                }
            }
            if (live == null && (request.chain || request.from == Stage.ASSEMBLE))
                ReconstructionReview.requireAttached(request.work, request.overwriteDetached);
            var run = new ReconstructionManifest.Run(stageName(request.from), request.harmonyHz);
            if (request.initial != null) {
                Files.createDirectory(request.work);
                capture.write(request.work, capture.relocateGeneratedStems(request.work, request.initial.score()),
                        List.of(args).subList(4, args.length), run, request.lanePolicyPreset);
            } else if (request.chain && !Files.exists(request.work.resolve(ReconstructionManifest.FILE))) {
                ReconstructionManifest.resumed(request.work, readScore(request.work.resolve("score.json")), run, capture);
            }
            execute(request, request.work, out, preserveReview, progress, refitChange, capture, live);
            if (live != null) return;
            if (request.chain && request.initial == null)
                ReconstructionManifest.resumed(request.work, readScore(request.work.resolve("score.json")), run, capture);
            if (request.chain || request.from == Stage.ASSEMBLE)
                ProjectProvenance.printWarnings(packageWarnings(request.work), err);
            if (request.chain) summarizeEdits(request.work, err);
        }
    }

    static void rerun(Path oldWork, Path work, List<Path> search, boolean keepVoicing, boolean overwriteDetached, PrintStream out, PrintStream err) throws IOException {
        rerun(oldWork, work, search, keepVoicing, overwriteDetached, null, out, err);
    }

    static void rerun(Path oldWork, Path work, List<Path> search, boolean keepVoicing, boolean overwriteDetached,
                      TargetGame requested, PrintStream out, PrintStream err) throws IOException {
        if (Files.exists(work) && Files.isSameFile(oldWork, work)) {
            if (requested != null) requireSameTarget(readScore(work.resolve("score.json")).inputs(), requested);
            ReconstructionReview.requireAttached(work, overwriteDetached);
            if (!overwriteDetached || !search.isEmpty())
                throw new IllegalArgumentException("In-place rerun requires --overwrite-detached and uses the existing score inputs");
            var options = new ArrayList<>(List.of("--from", "transcribe", work.toString(), "--overwrite-detached"));
            if (keepVoicing) options.add("--keep-voicing");
            run("run", options.toArray(String[]::new), out, err);
            return;
        }
        if (Files.exists(work, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.nio.file.FileAlreadyExistsException(work.toString());
        var replay = ReconstructionManifest.resolve(oldWork, work, search);
        var inputs = replay.inputs();
        requireSameTarget(inputs, requested);
        Files.createDirectory(work);
        Path plan = oldWork.resolve("roleplan.json");
        if (Files.exists(plan)) Files.copy(plan, work.resolve("roleplan.json"));
        var layout = ReconstructionClipLinks.Layout.read(oldWork);
        if (layout != null) ReconstructionArtifacts.write(work.resolve(ReconstructionClipLinks.LAYOUT), layout);
        ReconstructionManifest.writeReplay(work, replay);
        var sectionFile = oldWork.resolve(ReconstructionSections.FILE);
        if (Files.exists(sectionFile)) Files.copy(sectionFile, work.resolve(ReconstructionSections.FILE));
        var voiceJournal = oldWork.resolve(ReconstructionVoicePlan.JOURNAL);
        if (Files.exists(voiceJournal)) Files.copy(voiceJournal, work.resolve(ReconstructionVoicePlan.JOURNAL));
        var edits = ReconstructionEdits.read(oldWork);
        if (!edits.isEmpty()) edits.write(work);
        Path templateReport = oldWork.resolve(ReconstructionStemTemplates.FILE);
        if (Files.exists(templateReport)) Files.copy(templateReport, work.resolve(ReconstructionStemTemplates.FILE));
        var transcription = transcribeWithSavedNotes(inputs, replay.manifest().durationUs());
        execute(new Request(work, Stage.TRANSCRIBE, true, transcription, replay.manifest().lastRun().harmonyMaxHz(),
                        java.util.Set.of(), false, keepVoicing, null, false, null, false), oldWork, out, false,
                (completed, total, message) -> {}, ignored -> {}, null, null);
        ProjectProvenance.printWarnings(packageWarnings(work), err);
        summarizeEdits(work, err);
    }

    public static com.google.gson.JsonObject renderPart(Path work, int part, Path output) throws IOException {
        return ReconstructionCli.renderPart(work, part, output);
    }

    public static List<String> packageWarnings(Path work) throws IOException {
        var project = com.opensmpsdeck.project.ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        return ProjectProvenance.detect(project).warnings();
    }

    private static void summarizeEdits(Path work, PrintStream err) throws IOException {
        var issues = ReconstructionEdits.Journal.read(work).all().stream()
                .filter(outcome -> outcome.status() == ReconstructionEdits.Status.invalid
                        || outcome.status() == ReconstructionEdits.Status.orphaned).toList();
        if (issues.isEmpty()) return;
        err.printf("%d invalid or orphaned edits%n", issues.size());
        for (var issue : issues)
            err.printf("  %s %s %s%s%n", issue.tag(), issue.status(), issue.detail(),
                    issue.missingNoteIds() == null || issue.missingNoteIds().isEmpty()
                            ? "" : " " + issue.missingNoteIds());
    }

    private static ReconstructionAllocation.Result previousAllocation(Path file, PrintStream out) throws IOException {
        if (Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            return ReconstructionArtifacts.read(file, ReconstructionAllocation.Result.class);
        } catch (IllegalArgumentException invalid) {
            // Allocation can be rebuilt even if the artifact it replaces is damaged.
            out.println("Ignoring invalid previous allocation: " + invalid.getMessage());
            return null;
        }
    }

    private static void execute(Request request, Path previousWork, PrintStream out, boolean preserveReview,
                                Progress progress,
                                java.util.function.Consumer<String> refitChange,
                                ReconstructionManifest.Capture capture, LiveRun live) throws IOException {
        int total = request.chain ? Stage.values().length - request.from.ordinal() : 1;
        Path work = request.work;
        Path scoreFile = work.resolve("score.json");
        Path structureFile = work.resolve("structure.json");
        Path rolesFile = work.resolve("roles.json");
        Path allocationFile = work.resolve("allocation.json");
        Path voicingFile = work.resolve("voicing.json");
        var previousAllocation = request.chain && request.from.ordinal() <= Stage.ALLOCATE.ordinal()
                ? previousAllocation(previousWork.resolve("allocation.json"), out) : null;
        Path oldVoicingFile = previousWork.resolve("voicing.json");
        boolean fitOrAllocate = request.from == Stage.ALLOCATE || request.from == Stage.VOICE
                || request.chain && request.from.ordinal() < Stage.ALLOCATE.ordinal();
        var arrangement = fitOrAllocate ? arrangement(oldVoicingFile) : null;
        var journal = ReconstructionEdits.read(work);
        var edits = journal.accepted();
        var previousEdits = ReconstructionEdits.Journal.read(work);
        var editJournal = previousEdits.through(request.from);
        boolean trackEdits = !edits.isEmpty() || !previousEdits.stages().isEmpty();

        Score replayScore = live == null ? null : readScore(scoreFile);
        if (request.initial == null) {
            progress.report(0, total, "Starting " + stageName(request.from));
            if (preserveReview) checkInputFiles((replayScore == null ? readScore(scoreFile) : replayScore).inputs);
        }
        ReconstructionVoicePlan.Result voicePlan = null;
        ReconstructionStructure.Result replayStructure = null;
        ReconstructionRoles.Result replayRoles = null;
        ReconstructionAllocation.Result replayAllocation = null;
        ReconstructionVoicing.Result replayVoicing = null;
        for (var stage : Stage.values()) {
            if (stage.ordinal() < request.from.ordinal()) continue;
            var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
            if (stage == Stage.TRANSCRIBE) {
                var transcription = request.initial != null ? request.initial
                        : retranscribe(replayScore == null ? readScore(scoreFile) : replayScore);
                Score score = transcription.score();
                if (request.initial != null && capture != null) score = capture.relocateGeneratedStems(work, score);
                var bound = edits.bindSections(work, score, null, Stage.TRANSCRIBE, outcomes);
                score = ReconstructionBendRecovery.apply(work, score, bound, journal, transcription.approaches(), outcomes);
                trackEdits |= !outcomes.isEmpty();
                ReconstructionArtifacts.write(scoreFile, score);
                if (live != null) replayScore = score;
                journal = ReconstructionStemTemplates.publish(work, journal, transcription.candidates());
                journal = ReconstructionBendRecovery.publish(work, journal, transcription.approaches(), outcomes);
            } else {
                var score = replayScore == null ? readScore(scoreFile) : replayScore;
                if (live != null) replayScore = score;
                switch (stage) {
                    case STRUCTURE -> {
                        var detected = structure(score, editJournal.stages().getOrDefault("transcribe", List.of()));
                        ReconstructionArtifacts.write(structureFile, detected);
                        if (live != null) replayStructure = detected;
                    }
                    case ROLES -> {
                        var structure = replayStructure == null ? readStructure(structureFile, score) : replayStructure;
                        double rate = request.harmonyHz == null ? score.inputs.harmonyMaxHz : request.harmonyHz;
                        var plan = readRolePlan(work, structure, score);
                        if (plan != null) {
                            var timeline = ReconstructionTimeMap.of(score);
                            for (var time : plan.resolvedTimes(timeline)) {
                                out.printf(java.util.Locale.ROOT,
                                        "roleplan#%d %.6f–%.6f s source, %.6f–%.6f s project, ticks [%d,%d)%n",
                                        time.rule(), time.sourceStart(), time.sourceEnd(), time.projectStart(),
                                        time.projectEnd(), time.startTick(), time.endTick());
                            }
                            plan = plan.projectBounds(timeline);
                        }
                        var continuity = ReconstructionLeadContinuity.analyze(score, structure.notes());
                        var scoreEdits = editJournal.stages().getOrDefault("transcribe", List.of());
                        var boundEdits = edits.bindSections(work, score, structure, Stage.ROLES, outcomes);
                        var roles = boundEdits.assignRoles(score, structure, rate, plan, continuity, scoreEdits, outcomes);
                        if (live == null) {
                            ReconstructionArtifacts.write(rolesFile, roles);
                            ReconstructionManifest.recordRolePlan(work, rate);
                        } else {
                            replayRoles = roles;
                            replayStructure = structure;
                        }
                    }
                    case VOICES -> {
                        var roles = replayRoles == null ? ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class) : replayRoles;
                        ReconstructionChecks.roles(roles, score);
                        if (live != null) replayRoles = roles;
                        voicePlan = ReconstructionVoicePlan.generate(work, score, roles);
                        ReconstructionVoicePlan.reviewOutcomes(edits, voicePlan, outcomes);
                        for (var issue : voicePlan.issues())
                            out.printf("voice-edit:%s %s %s%n", issue.id(), issue.status(), issue.detail());
                    }
                    case ALLOCATE -> {
                        var roles = replayRoles == null ? ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class) : replayRoles;
                        if (live == null || voicePlan == null) ReconstructionChecks.roles(roles, score);
                        if (voicePlan == null) voicePlan = ReconstructionVoicePlan.generate(work, score, roles);
                        var laneTypes = voicePlan.laneTypes();
                        var savedAffinity = previousWork.equals(work) ? null : ReconstructionAllocation.Affinity.read(previousWork);
                        var seed = savedAffinity == null ? ReconstructionAllocation.lanes(previousAllocation) : savedAffinity.seed();
                        int frames = ReconstructionTimeMap.of(score).ticks();
                        boolean stems = !score.inputs.stems.isEmpty();
                        var cymbalLevels = ReconstructionCymbals.measure(score);
                        boolean chordReduction = !ReconstructionStemParts.addedParts(score.inputs()).isEmpty();
                        var salience = chordReduction ? ReconstructionStemParts.salience(score, roles) : Map.<String, Double>of();
                        var chords = ReconstructionChords.select(edits.bindSections(work, score, null, stage, outcomes),
                                score, roles, laneTypes, outcomes);
                        var rules = arrangement == null ? ReconstructionArrangement.Allocation.NONE
                                : arrangement.resolve(score, roles);
                        var holds = allocationHolds(score, roles);
                        var allocation = ReconstructionAllocation.allocateWithAffinity(roles, score.source.parts(),
                                frames, score.inputs.fmOnly, stems, seed, cymbalLevels, laneTypes, chords, outcomes, salience,
                                chordReduction, score.profile(), rules, holds, score.inputs.audioOnly != null);
                        if (live == null) {
                            ReconstructionArtifacts.write(allocationFile, allocation);
                            var fresh = seed.isEmpty() ? allocation : ReconstructionAllocation.allocateWithAffinity(roles,
                                    score.source.parts(), frames, score.inputs.fmOnly, stems, Map.of(), cymbalLevels, laneTypes, chords, null, salience,
                                    chordReduction, score.profile(), rules, holds, score.inputs.audioOnly != null);
                            ReconstructionArtifacts.write(work.resolve(ReconstructionAllocation.Affinity.FILE),
                                    ReconstructionAllocation.Affinity.compare(seed, allocation, fresh));
                        } else {
                            replayAllocation = allocation;
                            replayRoles = roles;
                        }
                        outcomes.addAll(edits.allocationOutcomes(allocation, roles.lanePins()));
                    }
                    case VOICE -> {
                        // A resume at voice normally assembles the saved settings directly.
                        if (request.chain && request.from == Stage.VOICE && request.lanes.isEmpty()
                                && !request.refitLevels && !request.keepVoicing && Files.exists(voicingFile)) break;
                        var allocation = replayAllocation == null ? ReconstructionArtifacts.read(allocationFile, ReconstructionAllocation.Result.class) : replayAllocation;
                        ReconstructionChecks.allocation(allocation, score);
                        var previous = Files.notExists(oldVoicingFile, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? null
                                : ReconstructionArtifacts.read(oldVoicingFile, ReconstructionVoicing.Result.class);
                        java.util.function.Consumer<String> report = change -> {
                            out.println(change);
                            refitChange.accept(change);
                        };
                        var fitted = request.keepVoicing && previous != null
                                ? ReconstructionCli.keepVoicing(score, allocation, previous, report)
                                : ReconstructionCli.fitVoicing(score, allocation, previousWork.equals(work) ? previous : null,
                                        request.lanes, request.refitLevels, previousAllocation, request.variant);
                        var policy = previous != null ? previous.lanePolicies() : initialLanePolicy(work, score.inputs().targetGame());
                        fitted = ReconstructionVoicing.withPercussionEvidence(fitted.withLanePolicies(policy), allocation);
                        if (voicePlan == null) voicePlan = ReconstructionVoicePlan.saved(work);
                        if (voicePlan != null && !voicePlan.laneTypes().isEmpty()) {
                            var roles = replayRoles == null ? ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class) : replayRoles;
                            fitted = fitted.withAssignments(voicePlan.assignments(allocation, roles.lanePins()));
                        }
                        if (live == null) ReconstructionArtifacts.write(voicingFile, fitted.withArrangement(arrangement));
                        else {
                            replayVoicing = fitted.withArrangement(arrangement);
                            replayAllocation = allocation;
                        }
                        if (fitted.sustainWarning() != null) out.println("Warning: " + fitted.sustainWarning());
                        if (fitted.leadProminenceWarning() != null) out.println("Warning: " + fitted.leadProminenceWarning());
                        if (previous != null)
                            ReconstructionVoicing.changes(previous, fitted).forEach(change -> report.accept(change.describe()));
                    }
                    case ASSEMBLE -> {
                        var structure = replayStructure == null ? readStructure(structureFile, score) : replayStructure;
                        var allocation = replayAllocation == null ? ReconstructionArtifacts.read(allocationFile, ReconstructionAllocation.Result.class) : replayAllocation;
                        if (live == null || replayVoicing == null) ReconstructionChecks.allocation(allocation, score);
                        var voicing = replayVoicing == null ? ReconstructionArtifacts.read(voicingFile, ReconstructionVoicing.Result.class) : replayVoicing;
                        if (request.lanePolicyPreset != null) {
                            var preset = request.lanePolicyPreset.equals("off") ? null
                                    : ReconstructionLanePolicies.Configuration.preset(request.lanePolicyPreset);
                            voicing = voicing.withLanePolicies(preset);
                        }
                        if (live == null)
                            ReconstructionCli.assembleAllocated(work, score, structure, allocation, voicing, out, preserveReview, request.overwriteDetached, edits, outcomes, request.policyDecisions);
                        else live.result().accept(new LiveCompilation(
                                ReconstructionCli.compileLive(work, score, structure, allocation, voicing, edits, outcomes,
                                        live.existing()), allocation));
                        if (request.lanePolicyPreset != null) ReconstructionArtifacts.write(voicingFile, voicing);
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
        if (live == null && journal != edits) ReconstructionProposals.refresh(work, ReconstructionEdits.read(work));
        if (!preserveReview) for (var issue : ReconstructionSections.anchorIssues(work))
            out.println("section:" + issue.id() + " " + issue.detail());
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
        return writePlanTemplate(work, work.resolve("roleplan.json"));
    }

    static Path writePlanTemplate(Path work, Path path) throws IOException {
        if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.nio.file.FileAlreadyExistsException(path.toString());
        // CREATE_NEW also protects edits made concurrently after the existence check.
        Files.writeString(path, planTemplateJson(work), java.nio.file.StandardOpenOption.CREATE_NEW);
        return path;
    }

    static String planTemplateJson(Path work) throws IOException {
        var score = readScore(work.resolve("score.json"));
        var structure = readStructure(work.resolve("structure.json"), score);
        var heuristic = ReconstructionRoles.assign(structure, score.source.parts(), score.inputs.harmonyMaxHz,
                null, null, null, score.inputs.leadPart, Set.of(), Set.of(), note -> false,
                ReconstructionStemParts.addedParts(score.inputs()));
        var plan = ReconstructionRolePlan.template(structure, heuristic);
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(plan) + "\n";
    }

    private static String stageName(Stage stage) {
        return stage.name().toLowerCase(Locale.ROOT);
    }

    private static ReconstructionRolePlan readRolePlan(Path work, ReconstructionStructure.Result structure, Score score)
            throws IOException {
        Path path = work.resolve("roleplan.json");
        if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        return resolveRolePlan(work, ReconstructionArtifacts.read(path, ReconstructionRolePlan.class), structure, score);
    }

    private static ReconstructionRolePlan resolveRolePlan(Path work, ReconstructionRolePlan plan,
                                                          ReconstructionStructure.Result structure, Score score) throws IOException {
        ReconstructionChecks.rolePlan(plan, structure, score);
        if (plan.rules().stream().noneMatch(rule -> rule.section() != null)) return plan;
        return plan.resolveSections(ReconstructionSections.load(work, score, structure), ReconstructionTimeMap.of(score));
    }

    public static List<?> rolePlanTimes(Path work) throws IOException {
        if (Files.notExists(work.resolve("roleplan.json"))) return List.of();
        var score = readScore(work.resolve("score.json"));
        var plan = readRolePlan(work, readStructure(work.resolve("structure.json"), score), score);
        return plan == null ? List.of() : plan.resolvedTimes(ReconstructionTimeMap.of(score));
    }

    static void previewEdits(Path work, Stage from, ReconstructionEdits edits, ReconstructionEdits.Journal prior,
                             List<ReconstructionEdits.Outcome> outcomes, List<ReconstructionEdits.Resolution> resolutions)
            throws IOException {
        previewEdits(work, from, edits, prior, outcomes, resolutions, null);
    }

    static void previewEdits(Path work, Stage from, ReconstructionEdits edits, ReconstructionEdits.Journal prior,
                             List<ReconstructionEdits.Outcome> outcomes, List<ReconstructionEdits.Resolution> resolutions,
                             Map<String, Note> resolvedNotes) throws IOException {
        var score = readScore(work.resolve("score.json"));
        if (resolvedNotes != null) for (var note : score.notes()) resolvedNotes.put(note.id(), note);
        var structureFile = work.resolve("structure.json");
        Path rolesFile = work.resolve("roles.json");
        var currentRoles = Files.isRegularFile(rolesFile)
                ? ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class) : null;
        if (resolvedNotes != null && currentRoles != null) {
            var clock = ReconstructionTimeMap.of(score);
            for (var row : currentRoles.notes()) resolvedNotes.putIfAbsent(row.note().id(), row.note().mapTime(clock::source));
        }
        if (from == Stage.ASSEMBLE) {
            var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
            if (ReconstructionLayers.present(edits))
                ReconstructionCli.previewLayers(work, score, readStructure(structureFile, score), allocation, edits,
                        outcomes, null);
            else previewNoteVoicing(edits, score, allocation, outcomes);
            return;
        }
        if (from.ordinal() >= Stage.VOICES.ordinal() && currentRoles != null) {
            var structure = ReconstructionLayers.present(edits) ? readStructure(structureFile, score) : null;
            previewAllocationEdits(work, edits, score, structure, currentRoles, outcomes, resolvedNotes != null,
                    prior.stages().getOrDefault("transcribe", List.of()));
            return;
        }
        List<ReconstructionEdits.Outcome> scoreEdits;
        if (from == Stage.TRANSCRIBE) {
            var transcription = retranscribe(score);
            score = transcription.score();
            var bound = edits.bindSections(work, score, null, Stage.TRANSCRIBE, outcomes);
            score = ReconstructionBendRecovery.apply(work, score, bound, ReconstructionEdits.read(work),
                    transcription.approaches(), outcomes);
            scoreEdits = List.copyOf(outcomes);
        } else {
            scoreEdits = prior.stages().getOrDefault("transcribe", List.of());
        }
        var structure = from == Stage.TRANSCRIBE || Files.notExists(structureFile)
                ? structure(score, scoreEdits) : readStructure(structureFile, score);
        var timeline = ReconstructionTimeMap.of(score);
        var plan = readRolePlan(work, structure, score);
        if (plan != null) plan = plan.projectBounds(timeline);
        var continuity = ReconstructionLeadContinuity.analyze(score, structure.notes());
        double rate = currentRoles == null ? score.inputs.harmonyMaxHz : currentRoles.harmonyMaxHz();
        var boundEdits = edits.bindSections(work, score, structure, Stage.ROLES, outcomes);
        var roles = boundEdits.assignRoles(score, structure, rate, plan, continuity, scoreEdits, outcomes, resolutions);

        if (resolvedNotes != null) {
            var clock = ReconstructionTimeMap.of(score);
            for (var row : roles.notes()) resolvedNotes.put(row.note().id(), row.note().mapTime(clock::source));
            for (var note : score.notes()) resolvedNotes.put(note.id(), note);
        }
        previewAllocationEdits(work, edits, score, structure, roles, outcomes, resolvedNotes != null, scoreEdits);
        if (from != Stage.TRANSCRIBE) return;
        var previous = new HashMap<String, ReconstructionRoles.Decision>();
        if (currentRoles != null) for (var row : currentRoles.notes()) previous.put(row.note().id(), row);
        var resulting = new HashMap<String, ReconstructionRoles.Decision>();
        for (var row : roles.notes()) resulting.put(row.note().id(), row);
        var written = new HashMap<String, Note>();
        for (var note : score.notes()) written.put(note.id(), note);
        var resolved = new java.util.HashSet<List<String>>();
        for (var row : resolutions) resolved.add(List.of(row.editId(), row.noteId()));
        for (var outcome : scoreEdits) {
            if (outcome.status() != ReconstructionEdits.Status.applied) continue;
            for (String id : outcome.noteIds()) {
                if (!resolved.add(List.of(outcome.id(), id))) continue;
                var before = previous.get(id);
                var after = resulting.get(id);
                var note = written.getOrDefault(id, outcome.original());
                resolutions.add(new ReconstructionEdits.Resolution(outcome.id(), id,
                        before == null ? null : before.role(), after == null ? null : after.role(), false,
                        note == null ? null : note.start(), note == null ? null : timeline.project(note.start())));
            }
        }
    }

    private static void previewAllocationEdits(Path work, ReconstructionEdits edits, Score score,
                                               ReconstructionStructure.Result structure, ReconstructionRoles.Result roles,
                                               List<ReconstructionEdits.Outcome> outcomes,
                                               boolean allPins, List<ReconstructionEdits.Outcome> scoreEdits)
            throws IOException {
        var pins = roles.lanePins();
        var bound = edits.bindSections(work, score, null, Stage.ALLOCATE, outcomes);
        boolean hasChordEdits = bound.intents().stream().anyMatch(edit -> edit.firstStage() == Stage.ALLOCATE);
        boolean hasVoiceEdits = edits.intents().stream().anyMatch(edit -> edit.kind() == ReconstructionEdits.Kind.voiceEdit);
        boolean hasNoteVoicing = edits.intents().stream().anyMatch(edit -> edit.kind() == ReconstructionEdits.Kind.noteVoicing);
        boolean checkPins = pins != null && pins.values().stream().anyMatch(pin -> allPins || pin.laneType() != null);
        boolean hasLayers = ReconstructionLayers.present(edits);
        boolean hasShaping = scoreEdits.stream().anyMatch(edit -> ReconstructionEdits.shapesSound(edit.op()));
        if (!hasChordEdits && !checkPins && !hasVoiceEdits && !hasNoteVoicing && !hasLayers && !hasShaping) return;
        ReconstructionVoicePlan.Result voices = null;
        var laneTypes = Map.<String, ReconstructionLaneType>of();
        if (hasVoiceEdits || Files.isRegularFile(work.resolve(ReconstructionVoicePlan.JOURNAL))) {
            voices = ReconstructionVoicePlan.preview(work, score, roles, edits, scoreEdits);
            laneTypes = voices.laneTypes();
            ReconstructionVoicePlan.reviewOutcomes(edits, voices, outcomes);
        }
        var chords = ReconstructionChords.select(bound, score, roles, laneTypes, outcomes);
        if (chords.isEmpty() && !checkPins && !hasNoteVoicing && !hasLayers && !hasShaping) return;
        try (var silent = new PrintStream(java.io.OutputStream.nullOutputStream())) {
            var previous = previousAllocation(work.resolve("allocation.json"), silent);
            boolean chordReduction = !ReconstructionStemParts.addedParts(score.inputs()).isEmpty();
            var salience = chordReduction ? ReconstructionStemParts.salience(score, roles) : Map.<String, Double>of();
            var arrangement = arrangement(work.resolve("voicing.json"));
            var rules = arrangement == null ? ReconstructionArrangement.Allocation.NONE : arrangement.resolve(score, roles);
            var allocation = ReconstructionAllocation.allocateWithAffinity(roles, score.source.parts(),
                    ReconstructionTimeMap.of(score).ticks(),
                    score.inputs.fmOnly, !score.inputs.stems.isEmpty(), ReconstructionAllocation.lanes(previous),
                    ReconstructionCymbals.measure(score), laneTypes, chords, outcomes, salience,
                    chordReduction, score.profile(), rules, allocationHolds(score, roles), score.inputs.audioOnly != null);
            outcomes.addAll(edits.allocationOutcomes(allocation, pins).stream()
                    .filter(outcome -> allPins || outcome.laneType() != null).toList());
            ReconstructionEdits.reportLostSound(allocation, scoreEdits, null, outcomes);
            if (hasLayers) {
                if (voices == null) voices = ReconstructionVoicePlan.preview(work, score, roles, edits, scoreEdits);
                ReconstructionCli.previewLayers(work, score, structure, allocation, edits, outcomes,
                        voices.assignments(allocation, pins));
            }
            else if (hasNoteVoicing) previewNoteVoicing(edits, score, allocation, outcomes);
        }
    }

    static ReconstructionArrangement.Configuration arrangement(Path voicing) throws IOException {
        return Files.isRegularFile(voicing)
                ? ReconstructionArtifacts.read(voicing, ReconstructionVoicing.Result.class).arrangement() : null;
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
                case "roleplan.json" -> resolveRolePlan(work,
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
                    var voicing = ReconstructionArtifacts.decode(file, ReconstructionVoicing.Result.class, value);
                    if (voicing.arrangement() != null) voicing.arrangement().validate(score);
                    ReconstructionVoicing.validate(voicing, allocation, InstrumentCatalogue.openReconstruction(Path.of(score.inputs.catalogue), score.inputs.targetGame()),
                            ReconstructionTimeMap.of(score));
                    if (voicing.lanePolicies() != null && !voicing.lanePolicies().rules().isEmpty())
                        ReconstructionLanePolicies.resolve(work, score, readStructure(work.resolve("structure.json"), score),
                                voicing.lanePolicies());
                }
                default -> throw new IllegalArgumentException("Unknown stage artifact: " + file.getFileName());
            }
        }
        ReconstructionArtifacts.write(file, value);
    }

    private static Map<String, ReconstructionHeldNotes.Hold> allocationHolds(Score score, ReconstructionRoles.Result roles)
            throws IOException {
        var timeline = ReconstructionTimeMap.of(score);
        return ReconstructionHeldNotes.measure(score, roles.notes().stream()
                .filter(decision -> decision.role() == ReconstructionRoles.Role.bass
                        || decision.role() == ReconstructionRoles.Role.melody
                        || score.inputs.audioOnly != null && (decision.role() == ReconstructionRoles.Role.counter
                            || decision.role() == ReconstructionRoles.Role.harmony))
                .map(decision -> timeline.sourceNote(decision.note())).toList());
    }

    private static void previewNoteVoicing(ReconstructionEdits edits, Score score, ReconstructionAllocation.Result allocation,
                                           List<ReconstructionEdits.Outcome> outcomes) throws IOException {
        var catalogue = InstrumentCatalogue.openReconstruction(Path.of(score.inputs.catalogue), score.inputs.targetGame());
        ReconstructionNoteVoicing.resolve(edits.intents(), allocation, catalogue, score, Map.of(), Map.of(), outcomes);
    }

    private static void checkInputFiles(Inputs inputs) {
        var paths = new ArrayList<>(List.of(inputs.wav, inputs.catalogue));
        if (inputs.midi != null) paths.addFirst(inputs.midi);
        paths.addAll(inputs.stems.values());
        if (inputs.candidates != null) paths.add(inputs.candidates);
        for (String value : paths) {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !Files.isRegularFile(path))
                throw new IllegalArgumentException("score.json.inputs: expected an existing absolute file path: " + value);
        }
    }

    private static ReconstructionLanePolicies.Configuration initialLanePolicy(Path work, TargetGame target) throws IOException {
        Path file = work.resolve(ReconstructionManifest.FILE);
        String preset = Files.exists(file)
                ? ReconstructionArtifacts.read(file, ReconstructionManifest.Manifest.class).initialLanePolicyPreset() : null;
        return "off".equals(preset) ? null
                : ReconstructionLanePolicies.Configuration.preset(preset == null ? ReconstructionLanePolicies.defaultPreset(target) : preset);
    }

    private static Request request(String command, String[] args, Progress progress, PrintStream err,
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
        boolean audioOnly = args[0].equals("--audio-only");
        Path midi = audioOnly ? null : absolute(args[0]), reference = absolute(args[1]), index = absolute(args[2]), work = absolute(args[3]);
        if (Files.exists(work, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(work.toString());
        var extra = stageOptions(args, 4, audioOnly);
        if (extra.policyDecisions && !chain)
            throw new IllegalArgumentException("--policy-decisions requires run or assemble");
        if (!extra.lanes.isEmpty() || extra.refitLevels || extra.keepVoicing || extra.variant != null || extra.overwriteDetached)
            throw new IllegalArgumentException("Refit and overwrite options require an existing work directory");
        String[] legacy = java.util.stream.Stream.concat(java.util.stream.Stream.of("reconstruct"),
                Arrays.stream(extra.legacy)).toArray(String[]::new);
        String transcriber = extra.transcriber;
        boolean externalRequested = audioOnly || !extra.transcribeParts.isEmpty() || !extra.unmapped.isEmpty();
        var config = externalRequested ? ExternalTranscribers.config(extra.transcriberConfig, audioOnly) : null;
        var transcriberFor = new TreeMap<>(extra.transcriberFor);
        String drumOverride = transcriberFor.remove("drums");
        if (drumOverride != null && (!audioOnly || extra.drumDetector != null))
            throw new IllegalArgumentException("A drums transcriber override requires audio-only mode and no --drum-detector");
        String device = extra.device == null ? "auto" : extra.device;
        String filename = (audioOnly ? reference : midi).getFileName().toString();
        if (audioOnly) filename = filename.replaceFirst("(?i)\\.wav$", "");
        var options = ReconstructionCli.parseOptions(legacy, filename);
        if (options.stockDrums() && extra.drumSource != null)
            throw new IllegalArgumentException("--drum-source on s1 or s2 requires --drum-kit custom");
        double harmonyHz = extra.harmonyHz == null ? ReconstructionRoles.DEFAULT_HARMONY_HZ : extra.harmonyHz;
        var stems = new java.util.TreeMap<Integer, String>();
        options.stems().forEach((id, file) -> stems.put(id, file.toAbsolutePath().normalize().toString()));
        progress.report(0, chain ? Stage.values().length : 1, "Starting transcribe");
        ReconstructionAudioOnly.Input audio = null;
        Score score;
        if (audioOnly) {
            long duration = duration(reference, options);
            boolean mappedDrums = extra.audioStems.keySet().stream().anyMatch(label ->
                    extra.stemRoles.getOrDefault(label, ReconstructionAudioOnly.defaultRole(label)).equals("drums"));
            Path drums = mappedDrums || extra.audioStems.isEmpty() ? null
                    : separateDrums(reference, extra.separatorConfig, progress, err, capture, chain);
            audio = ReconstructionAudioOnly.prepare(extra.audioStems, extra.stemRoles, config.fallback(transcriber, true),
                    AudioDrumDetector.select(config, config.drumName(drumOverride == null ? extra.drumDetector : drumOverride),
                            device), duration / 1e6, extra.leadPart != null, mappedDrums ? null : drums,
                    mappedDrums ? null : reference);
            if (drums != null) capture.recordDrums(audio.drums().keySet(), drums);
            audio.drums().forEach((id, file) -> stems.put(id, file.toString()));
            score = prepare(ReconstructionAudioOnly.drumScore(audio.provenance()), reference, options, duration,
                    ReconstructionAudioOnly.DRUM_SOURCE);
        } else {
            score = transcribe(midi, reference, options);
            var drumParts = score.source.parts().stream().filter(MidiScore.Part::drums)
                    .map(MidiScore.Part::id).filter(id -> !stems.containsKey(id))
                    .filter(id -> score.source.notes().stream().anyMatch(note ->
                            note.partId() == id && note.startUs() < score.durationUs)).toList();
            if (!drumParts.isEmpty()) {
                Path drums = separateDrums(reference, extra.separatorConfig, progress, err, capture, chain);
                if (drums != null) {
                    drumParts.forEach(id -> stems.put(id, drums.toString()));
                    capture.recordDrums(drumParts, drums);
                }
            }
        }
        var source = score.source;
        var roles = new java.util.TreeMap<>(extra.transcribeParts);
        var newParts = new ArrayList<Integer>();
        var unmapped = audioOnly ? audio.tonal() : extra.unmapped;
        if (!unmapped.isEmpty()) {
            int id = audioOnly ? 1 : source.parts().stream().mapToInt(MidiScore.Part::id).max().orElse(-1) + 1;
            var parts = new ArrayList<>(source.parts());
            var claimed = new java.util.HashSet<String>();
            for (String path : stems.values()) claimed.add(Path.of(path).toRealPath().toString());
            for (var stem : unmapped.entrySet()) {
                String path = stem.getKey().toString();
                if (!claimed.add(path)) throw new IllegalArgumentException("Stem already mapped: " + path);
                newParts.add(id);
                parts.add(audioOnly ? ReconstructionAudioOnly.part(id, audio.provenance().stems().get(id))
                        : ReconstructionStemParts.part(id, stem.getValue()));
                stems.put(id, path);
                roles.put(id++, stem.getValue());
            }
            for (int part : extra.imports.keySet()) ExternalTranscribers.validatePart(part, source.parts());
            for (int part : extra.transcribeParts.keySet()) ExternalTranscribers.validatePart(part, source.parts());
            source = new MidiScore(parts, source.notes(), source.warnings());
        }
        var external = ExternalTranscribers.collect(source, stems, transcriber, config, transcriberFor, audioOnly,
                roles, device, extra.imports, capture, audioOnly ? audio.provenance().stems() : Map.of());
        for (int part : newParts) {
            var result = external.get(part);
            var e = result.provenance();
            external.put(part, new ExternalTranscribers.PartNotes(new ExternalTranscribers.Evidence(e.tool(),
                    e.role(), e.device(), e.inputSha256(), e.outputSha256(), e.configSha256(),
                    e.metadata(), e.noteCount(), false, true), result.notes()));
        }
        var inputs = new Inputs(audioOnly ? null : midi.toString(), reference.toString(), index.toString(), options.name(),
                options.ticksPerBeat(), options.keepOctaves(), options.fmOnly(), options.wholePartVoices(),
                options.audioRefine(), stems, extra.candidates == null ? null : extra.candidates.toString(), harmonyHz,
                extra.grid, extra.audioRepeats, external, extra.leadPart != null || !audioOnly ? extra.leadPart : audio.leadPart(),
                extra.fastRunsOff ? true : null, extra.drumSource, extra.stemTemplateNotesOff ? true : null,
                audioOnly ? audio.provenance() : null,
                options.target() == TargetGame.S3K ? null : options.target().id(), options.drumKit());
        return new Request(work, stage, chain, attach(score, inputs), extra.harmonyHz,
                extra.lanes, extra.refitLevels, extra.keepVoicing, null, false, extra.lanePolicyPreset, extra.policyDecisions);
    }

    private static Path separateDrums(Path reference, Path config, Progress progress, PrintStream err,
                                      ReconstructionManifest.Capture capture, boolean chain) throws IOException {
        int total = chain ? Stage.values().length : 1;
        if (!ExternalSeparators.configuredNames(config).contains("bs-roformer")) {
            String warning = "Warning: no mapped drums stem or configured bs-roformer separator; fitting drums from the mix";
            err.println(warning);
            progress.report(0, total, warning);
            return null;
        }
        return capture.separateDrums(reference, config, (done, steps, message) -> progress.report(0, total, message));
    }

    private static Request resume(String[] args, int workIndex, Stage stage, boolean chain) throws IOException {
        if (args.length <= workIndex) throw new IllegalArgumentException("Expected a reconstruction WORK_DIR");
        var extra = stageOptions(Arrays.copyOfRange(args, workIndex + 1, args.length), 0, false);
        if (extra.policyDecisions && !chain && stage != Stage.ASSEMBLE)
            throw new IllegalArgumentException("--policy-decisions requires run or assemble");
        if (extra.overwriteDetached && !chain && stage != Stage.ASSEMBLE)
            throw new IllegalArgumentException("--overwrite-detached requires run or assemble");
        if (extra.lanePolicyPreset != null && !chain && stage != Stage.ASSEMBLE)
            throw new IllegalArgumentException("--lane-policy-preset requires run or assemble");
        if (extra.variant != null && (chain || stage != Stage.VOICE || extra.lanes.size() != 1
                || !ReconstructionAllocation.FM.contains(extra.lanes.iterator().next())))
            throw new IllegalArgumentException("--variant requires reconstruct voice with one FM --lane");
        if (extra.keepVoicing && (!chain || stage.ordinal() > Stage.VOICE.ordinal()
                || extra.refitLevels || !extra.lanes.isEmpty()))
            throw new IllegalArgumentException("--keep-voicing requires a run through voice and cannot be combined with refit options");
        if (extra.legacy.length != 0) {
            var target = ReconstructionCli.parseTargetAssertion(extra.legacy);
            var saved = readScore(absolute(args[workIndex]).resolve("score.json")).inputs();
            requireSameTarget(saved, target);
        }
        if (extra.candidates != null || extra.grid.force() || extra.drumSource != null
                || extra.transcriber != null || extra.separatorConfig != null || !extra.transcriberFor.isEmpty() || !extra.imports.isEmpty() || extra.leadPart != null || extra.fastRunsOff || extra.stemTemplateNotesOff
                || extra.grid.bpm() != null || extra.grid.steady() != null || extra.audioRepeats
                || (!extra.lanes.isEmpty() || extra.refitLevels) && (chain ? stage.ordinal() > Stage.VOICE.ordinal() : stage != Stage.VOICE)
                || extra.harmonyHz != null && (chain ? stage.ordinal() > Stage.ROLES.ordinal() : stage != Stage.ROLES))
            throw new IllegalArgumentException("Unsupported options when resuming reconstruct " + stage.name().toLowerCase(Locale.ROOT));
        return new Request(absolute(args[workIndex]), stage, chain, null, extra.harmonyHz,
                extra.lanes, extra.refitLevels || extra.variant != null, extra.keepVoicing, extra.variant, extra.overwriteDetached, extra.lanePolicyPreset, extra.policyDecisions);
    }

    private static StageOptions stageOptions(String[] args, int firstOption, boolean audioOnly) throws IOException {
        var lanes = new java.util.LinkedHashSet<String>();
        boolean refitLevels = false, keepVoicing = false, overwriteDetached = false;
        Path candidates = null;
        Double hz = null, bpm = null, offset = null;
        Integer leadPart = null;
        ReconstructionDrums.Source drumSource = null;
        boolean estimate = false, audioRepeats = false, fastRunsOff = false, stemTemplateNotesOff = false;
        boolean policyDecisions = false;
        String steady = null, transcriber = null, device = null, variant = null, lanePolicyPreset = null;
        Path transcriberConfig = null, separatorConfig = null;
        var audioStems = new LinkedHashMap<String, Path>();
        var stemRoles = new java.util.TreeMap<String, String>();
        String drumDetector = null;
        var transcriberFor = new TreeMap<String, String>();
        var parts = new java.util.TreeMap<Integer, String>();
        var imports = new java.util.TreeMap<Integer, ExternalTranscribers.Import>();
        var unmapped = new LinkedHashMap<Path, String>();
        var legacy = new ArrayList<>(Arrays.asList(args).subList(0, firstOption));
        for (int i = firstOption; i < args.length; i++) {
            switch (args[i]) {
                case "--estimate-tempo" -> estimate = true;
                case "--no-fast-runs" -> fastRunsOff = true;
                case "--no-stem-template-notes" -> stemTemplateNotesOff = true;
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
                case "--separator-config" -> {
                    if (separatorConfig != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --separator-config FILE");
                    separatorConfig = absolute(args[i]);
                }
                case "--transcriber" -> {
                    if (transcriber != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --transcriber NAME");
                    transcriber = args[i];
                }
                case "--transcriber-for" -> {
                    if (++i == args.length) throw new IllegalArgumentException("--transcriber-for expects ROLE=NAME");
                    ExternalTranscribers.putRoleOverride(transcriberFor, args[i]);
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
                case "--transcribe-unmapped" -> {
                    if (++i == args.length) throw new IllegalArgumentException("Expected --transcribe-unmapped ROLE=WAV");
                    int split = args[i].indexOf('=');
                    if (split <= 0 || split == args[i].length() - 1)
                        throw new IllegalArgumentException("Expected --transcribe-unmapped ROLE=WAV");
                    var file = absolute(args[i].substring(split + 1)).toRealPath();
                    if (unmapped.putIfAbsent(file, args[i].substring(0, split)) != null)
                        throw new IllegalArgumentException("Duplicate transcription stem: " + file);
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
                        if (drumSource == ReconstructionDrums.Source.stock) throw new IllegalArgumentException();
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
                case "--variant" -> {
                    if (variant != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --variant ID");
                    variant = args[i];
                }
                case "--refit-levels" -> refitLevels = true;
                case "--keep-voicing" -> keepVoicing = true;
                case "--overwrite-detached" -> overwriteDetached = true;
                case "--policy-decisions" -> policyDecisions = true;
                case "--lane-policy-preset" -> {
                    if (lanePolicyPreset != null || ++i == args.length
                            || !("off".equals(args[i]) || ReconstructionLanePolicies.PRESETS.contains(args[i])))
                        throw new IllegalArgumentException("--lane-policy-preset expects s1-layering, s2-layering, s3k-layering or off");
                    lanePolicyPreset = args[i];
                }
                case "--harmony-max-hz" -> {
                    if (hz != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --harmony-max-hz RATE");
                    hz = Double.parseDouble(args[i]);
                    ReconstructionChecks.rate(hz);
                }
                case "--stem", "--stem-role", "--drum-detector" -> {
                    String flag = args[i];
                    if (++i == args.length) throw new IllegalArgumentException("Missing argument to " + flag);
                    String value = args[i];
                    if (!audioOnly) {
                        if (!flag.equals("--stem")) throw new IllegalArgumentException(flag + " requires --audio-only");
                        legacy.add(flag);
                        legacy.add(value);
                    } else if (flag.equals("--drum-detector")) {
                        if (drumDetector != null) throw new IllegalArgumentException("Expected one --drum-detector NAME");
                        drumDetector = value;
                    } else {
                        String[] pair = value.split("=", 2);
                        if (pair.length != 2 || pair[1].isBlank())
                            throw new IllegalArgumentException(flag + " expects LABEL=" + (flag.equals("--stem") ? "WAV" : "ROLE"));
                        String label = ReconstructionAudioOnly.label(pair[0]);
                        boolean duplicate = flag.equals("--stem") ? audioStems.putIfAbsent(label, absolute(pair[1])) != null
                                : stemRoles.putIfAbsent(label, ReconstructionAudioOnly.label(pair[1])) != null;
                        if (duplicate) throw new IllegalArgumentException("Duplicate " + flag + " for " + label);
                    }
                }
                case "--seconds", "--name", "--ticks-per-beat", "--target" -> {
                    legacy.add(args[i]);
                    if (++i == args.length) throw new IllegalArgumentException("Missing argument to " + args[i - 1]);
                    legacy.add(args[i]);
                }
                default -> legacy.add(args[i]);
            }
        }
        if (audioOnly && (!parts.isEmpty() || !unmapped.isEmpty() || !imports.isEmpty()))
            throw new IllegalArgumentException("Use labelled --stem and --stem-role with --audio-only");
        if (!audioOnly && parts.isEmpty() && unmapped.isEmpty() &&
                (transcriber != null || !transcriberFor.isEmpty() || transcriberConfig != null || device != null))
            throw new IllegalArgumentException("Transcriber options require --transcribe-part PART=ROLE or --transcribe-unmapped ROLE=WAV");
        return new StageOptions(legacy.toArray(String[]::new), candidates, hz,
                new ReconstructionTempo.Options(estimate, bpm, offset, steady), audioRepeats,
                transcriber, transcriberConfig, device, parts, imports, unmapped, leadPart, fastRunsOff,
                stemTemplateNotesOff, drumSource, java.util.Set.copyOf(lanes), refitLevels, keepVoicing, variant,
                overwriteDetached, lanePolicyPreset, policyDecisions, audioStems, stemRoles, drumDetector, transcriberFor, separatorConfig);
    }

    private static void requireSameTarget(Inputs inputs, TargetGame requested) {
        var saved = inputs.targetGame();
        if (requested != null && requested != saved)
            throw new IllegalArgumentException("Saved reconstruction target is " + saved.id()
                    + ". Start a new reconstruction to choose " + requested.id() + ".");
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
                ? rhythmAudio(Path.of(score.inputs.wav), seconds) : null;
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
            if (!edit.authorsGate()) continue;
            authoredIds.addAll(edit.noteIds());
            if (edit.original() != null) suppressed.add(timeline.projectNote(edit.original()));
            if (edit.removedNotes() != null)
                edit.removedNotes().forEach(note -> suppressed.add(timeline.projectNote(note)));
        }
        return ReconstructionStructure.analyze(timeline.projectBars(bars), notes, score.source.parts(), audio,
                inferred, tracker ? score.beatMap.beatsPerBar() : inferred ? score.tempo.selected().beatsPerBar() : 4,
                roleBars, timeline, authoredIds, suppressed);
    }

    private record RhythmKey(Path path, double seconds) {}

    private static ReconstructionRhythmAudio rhythmAudio(Path path, double seconds) throws IOException {
        ReconstructionLiveEvidence.track(path);
        return ReconstructionLiveEvidence.memo(new RhythmKey(path, seconds),
                () -> ReconstructionRhythmAudio.read(path, seconds));
    }

    private static boolean trackedGrid(ReconstructionBeatMap beats, ReconstructionTempo.Options grid) {
        return beats != null && grid.bpm() == null
                && beats.confidence() >= ReconstructionTimeMap.MIN_CONFIDENCE && beats.deviation() >= .006;
    }

    private static Transcription retranscribe(Score saved) throws IOException {
        var inputs = saved.inputs();
        for (String path : inputs.stems().values()) ReconstructionLiveEvidence.track(Path.of(path));
        ReconstructionLiveEvidence.track(Path.of(inputs.wav()));
        if (inputs.midi() != null) ReconstructionLiveEvidence.track(Path.of(inputs.midi()));
        if (inputs.candidates() != null) ReconstructionLiveEvidence.track(Path.of(inputs.candidates()));
        return ReconstructionLiveEvidence.transcription(saved.durationUs(), inputs,
                () -> transcribeWithSavedNotes(inputs, saved.durationUs));
    }

    static void warmLive(Path work) throws IOException {
        for (String name : List.of("score.json", "structure.json", "roles.json", "allocation.json", "voicing.json"))
            ReconstructionLiveEvidence.warmArtifact(work.resolve(name));
        var score = readScore(work.resolve("score.json"));
        if (score.inputs().stems().isEmpty()) return;
        var transcription = retranscribe(score);
        if (Thread.currentThread().isInterrupted()) return;
        var timeline = ReconstructionTimeMap.of(transcription.score());
        for (String path : transcription.score().inputs().stems().values()) {
            if (Thread.currentThread().isInterrupted()) return;
            ReconstructionLeadAudio.Track.read(Path.of(path), timeline.sourceDuration());
        }
        readStructure(work.resolve("structure.json"), score);
        ReconstructionTrace.optional(work, "allocation.json", ReconstructionAllocation.Result.class);
        arrangement(work.resolve("voicing.json"));
        var roles = ReconstructionTrace.optional(work, "roles.json", ReconstructionRoles.Result.class);
        if (roles != null && !Thread.currentThread().isInterrupted()) ReconstructionVoicePlan.preview(work, score, roles);
        if (!Thread.currentThread().isInterrupted()) ReconstructionCymbals.measure(score);
    }

    private static Transcription transcribeWithSavedNotes(Inputs inputs, long durationUs) throws IOException {
        var options = inputs.options(durationUs);
        if (inputs.externalNotes != null) for (var entry : inputs.externalNotes.entrySet())
            if (Boolean.TRUE.equals(entry.getValue().provenance().addedStem())) options.stems().remove(entry.getKey());
        Path reference = Path.of(inputs.wav);
        if (inputs.audioOnly != null) {
            var drumScore = ReconstructionAudioOnly.drumScore(inputs.audioOnly);
            return attach(prepare(drumScore, reference, options, duration(reference, options),
                    ReconstructionAudioOnly.DRUM_SOURCE), inputs);
        }
        return attach(transcribe(Path.of(inputs.midi), reference, options), inputs);
    }

    private static Transcription attach(Score score, Inputs inputs) throws IOException {
        var notes = new ArrayList<>(score.notes);
        var newParts = new java.util.HashSet<Integer>();
        Integer lead = inputs.leadPart;
        Double otherTonalRms = null;
        if (inputs.externalNotes != null) {
            var parts = new ArrayList<>(score.source.parts());
            var sourceCounts = new LinkedHashMap<>(score.sourceCounts);
            var reductions = new ArrayList<>(score.reductions);
            var tonalParts = score.source.parts().stream().filter(p -> !p.drums()).map(MidiScore.Part::id)
                    .collect(java.util.stream.Collectors.toSet());
            var midiByPitch = new HashMap<Integer, List<MidiScore.Note>>();
            for (var midi : score.source.notes()) {
                if (tonalParts.contains(midi.partId()))
                    midiByPitch.computeIfAbsent(midi.pitch(), ignored -> new ArrayList<>()).add(midi);
            }
            double limit = score.durationUs / (double) MICROS_PER_SECOND;
            for (var entry : inputs.externalNotes.entrySet()) {
                int part = entry.getKey();
                var imported = entry.getValue();
                boolean added = Boolean.TRUE.equals(imported.provenance().addedStem());
                var candidates = ReconstructionStemParts.notes(part, imported.notes(), limit, added);
                if (!added) {
                    ExternalTranscribers.validatePart(part, score.source.parts());
                    if (imported.provenance().replace()) notes.removeIf(note -> note.part == part);
                    notes.addAll(candidates);
                    continue;
                }
                if (parts.stream().anyMatch(p -> p.id() == part) || !inputs.stems.containsKey(part))
                    throw new IllegalArgumentException("Expected a new part and aligned stem for " + part);
                var stem = inputs.audioOnly == null ? null : inputs.audioOnly.stems().get(part);
                parts.add(stem == null ? ReconstructionStemParts.part(part, imported.provenance().role())
                        : ReconstructionAudioOnly.part(part, stem));
                newParts.add(part);
                Path stemPath = Path.of(inputs.stems.get(part));
                boolean vocal = ReconstructionVocalLead.isVocal(inputs, part);
                var spectrum = vocal ? null : ReconstructionStemParts.Spectrum.read(stemPath, limit);
                List<Note> joined;
                if (vocal) {
                    var selection = ReconstructionVocalLead.select(part, imported,
                            ReconstructionLeadAudio.Track.read(stemPath, limit), limit);
                    joined = selection.notes();
                    boolean pinned = Boolean.TRUE.equals(inputs.audioOnly.explicitLead())
                            && java.util.Objects.equals(lead, part);
                    if (pinned) imported.provenance().metadata().put("vocalGate", "pinned");
                    if (!pinned) {
                        if (otherTonalRms == null) {
                            otherTonalRms = 0.0;
                            for (var other : inputs.audioOnly.stems().entrySet()) {
                                if (other.getValue().role().equals("drums")
                                        || "vocals".equals(other.getValue().transcriberRole())) continue;
                                otherTonalRms = Math.max(otherTonalRms, ReconstructionVocalLead.rms(
                                        Path.of(inputs.stems.get(other.getKey())), limit));
                            }
                        }
                        boolean passed = !joined.isEmpty() && selection.supportsLead(limit, otherTonalRms);
                        imported.provenance().metadata().put("vocalGate", passed ? "passed" : "rejected");
                        if (!passed) {
                            if (java.util.Objects.equals(lead, part)) lead = null;
                            for (var note : joined) reductions.add(new S3kReconstruction.Reduction(note.midi(),
                                    S3kReconstruction.Change.ROLE_OVERFLOW, note.id(), "residual-vocal-stem"));
                            continue;
                        }
                    }
                } else if (imported.provenance().metadata().containsKey(ReconstructionMonophonicStem.KEY)) {
                    joined = ReconstructionMonophonicStem.join(part, imported, ReconstructionLeadAudio.Track.read(stemPath, limit),
                            spectrum, limit, reductions);
                } else {
                    candidates.addAll(ReconstructionStemParts.upperVoicing(part, imported.notes(), spectrum, limit, reductions));
                    joined = ReconstructionStemParts.joinHolds(candidates, spectrum, reductions);
                }
                for (var note : joined) {
                    if (spectrum != null && ReconstructionStemParts.doubling(note, midiByPitch, spectrum)) {
                        reductions.add(new S3kReconstruction.Reduction(note.midi(),
                                S3kReconstruction.Change.OCTAVE_DOUBLE, note.id(), "stem-midi-doubling"));
                    } else {
                        notes.add(note);
                        sourceCounts.merge(part, 1, Integer::sum);
                    }
                }
            }
            if (!newParts.isEmpty()) {
                var source = new MidiScore(List.copyOf(parts), score.source.notes(), score.source.warnings());
                score = new Score(score.version, score.durationUs, score.frames, score.ticksPerSecond, null,
                        score.bars, source, sourceCounts, score.notes, reductions, score.pitchChanges, null, null);
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
        var stemCandidates = new ArrayList<ReconstructionStemTemplates.Candidate>();
        var sourceNotes = new HashMap<Integer, List<Note>>();
        for (var midiNote : score.source.notes()) {
            if (!inputs.stems.containsKey(midiNote.partId()) || midiNote.startUs() >= score.durationUs) continue;
            sourceNotes.computeIfAbsent(midiNote.partId(), ignored -> new ArrayList<>())
                    .add(new Note(midiNoteKey(midiNote), midiNote.partId(), midiNote.startUs() / 1e6,
                            Math.min(score.durationUs, midiNote.endUs()) / 1e6, midiNote.pitch(), midiNote.velocity(), "midi", 1));
        }
        var voices = new java.util.TreeMap<String, List<ReconstructionFigureVoices.Voice>>();
        var bursts = new ArrayList<ReconstructionRetriggers.Burst>();
        var decisions = new ArrayList<ScoreDecision>();
        var approaches = new ArrayList<ReconstructionBendRecovery.Candidate>();
        boolean fastRuns = !Boolean.TRUE.equals(inputs.fastRunsOff);
        for (var part : score.source.parts()) {
            String stem = inputs.stems.get(part.id());
            if (part.drums() || stem == null || notes.stream().noneMatch(note -> note.part == part.id())) continue;
            boolean selectedLead = java.util.Objects.equals(lead, part.id());
            boolean guitar = part.program() >= 24 && part.program() <= 31;
            if (newParts.contains(part.id())) {
                if ((selectedLead || guitar) && inputs.targetGame() == TargetGame.S3K) {
                    var audio = ReconstructionLeadAudio.readMono(Path.of(stem), score.durationUs / 1e6);
                    approaches.addAll(ReconstructionBendRecovery.detect(audio,
                            notes.stream().filter(note -> note.part == part.id()).toList(), true));
                }
                continue;
            }
            boolean leadCandidate = inputs.leadPart != null && inputs.leadPart == part.id()
                    || part.program() < 32 || part.program() > 39;
            var anchors = sourceNotes.getOrDefault(part.id(), List.of());
            boolean learnTemplate = anchors.size() >= 4;
            if (!fastRuns && !leadCandidate && !learnTemplate) continue;
            var audio = ReconstructionLeadAudio.readMono(Path.of(stem), score.durationUs / 1e6);
            if (learnTemplate) {
                var occupancy = new ArrayList<>(anchors);
                notes.stream().filter(note -> note.part == part.id() && !note.source.equals("midi"))
                        .forEach(occupancy::add);
                stemCandidates.addAll(ReconstructionStemTemplates.detect(audio, anchors, occupancy));
            }
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
                if (inputs.targetGame() == TargetGame.S3K && (selectedLead || guitar || lead == null))
                    approaches.addAll(ReconstructionBendRecovery.detect(audio,
                            notes.stream().filter(note -> note.part == part.id()).toList(), selectedLead || guitar));
            }
        }
        if (!Boolean.TRUE.equals(inputs.stemTemplateNotesOff))
            stemCandidates.stream().filter(ReconstructionStemTemplates.Candidate::automatic)
                    .map(ReconstructionStemTemplates.Candidate::note).forEach(notes::add);
        if (inputs.audioOnly != null) {
            var effective = inputs.withLeadPart(lead);
            var grid = ReconstructionAudioOnly.grid(effective, score.source, notes, score.durationUs / 1e6);
            var result = score.withInputs(effective, grid.bars(), notes.stream().sorted(NOTE_ORDER).toList(),
                    grid.tempo(), null, grid.beats());
            ReconstructionChecks.score(result);
            return new Transcription(result, List.copyOf(stemCandidates), List.copyOf(approaches));
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
        return new Transcription(result, List.copyOf(stemCandidates), List.copyOf(approaches));
    }

    static String midiNoteKey(MidiScore.Note note) {
        return "n-" + note.partId() + "-" + note.startUs() + "-" + note.pitch();
    }

    static String noteInstance(String key, int instance) {
        return instance == 1 ? key : key + "-" + instance;
    }

    private static long duration(Path reference, ReconstructionCli.Options options) throws IOException {
        long sourceDurationUs = ReferenceAudio.durationMicros(reference);
        long durationUs = options.durationUs() == null ? sourceDurationUs : options.durationUs();
        if (durationUs > sourceDurationUs) throw new IllegalArgumentException("--seconds exceeds reference WAV duration");
        long presentations = (durationUs * TargetClock.FRAMES_PER_SECOND + MICROS_PER_SECOND - 1) / MICROS_PER_SECOND;
        if (presentations < 1 || presentations > S3kRenderer.MAX_PRESENTATIONS)
            throw new IllegalArgumentException("Reconstruction interval must fit in "
                    + S3kRenderer.MAX_PRESENTATIONS + " presentations. Use --seconds for a shorter interval.");
        return durationUs;
    }

    static Score transcribe(Path midi, Path reference, ReconstructionCli.Options options) throws IOException {
        long durationUs = duration(reference, options);
        return prepare(MidiScore.read(midi), reference, options, durationUs, "midi");
    }

    private static Score prepare(MidiScore original, Path reference, ReconstructionCli.Options options,
                                 long durationUs, String source) throws IOException {
        Number tickRate = TargetClock.FRAMES_PER_SECOND;
        if (options.target() != TargetGame.S3K) tickRate = options.profile().ticksPerSecond();
        int frames = (int) ((durationUs * TargetClock.FRAMES_PER_SECOND + MICROS_PER_SECOND - 1) / MICROS_PER_SECOND);
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
                    note.pitch(), note.velocity(), remaining == 0 ? "audio-refine" : source, remaining == 0 ? .85 : 1));
        }
        return new Score(VERSION, durationUs, frames, tickRate, null, List.of(), score, counts, notes,
                prepared.reductions(), refinement.changes(), null, null);
    }
}
