package com.opensmpsdeck.headless;

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

final class ReconstructionStages {
    static final int VERSION = 1;
    static final long MICROS_PER_SECOND = 1_000_000;

    @Target(ElementType.RECORD_COMPONENT)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Nullable {}

    enum Stage {
        TRANSCRIBE, STRUCTURE, ROLES, ALLOCATE, ASSEMBLE;

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
                String source, double confidence) {
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
                  @Nullable String candidates, double harmonyMaxHz) {
        ReconstructionCli.Options options(long durationUs) {
            var paths = new java.util.TreeMap<Integer, Path>();
            stems.forEach((key, value) -> paths.put(key, Path.of(value)));
            return new ReconstructionCli.Options(durationUs, name, ticksPerBeat, keepOctaves, fmOnly,
                    wholePartVoices, audioRefine, paths);
        }
    }

    record Score(int version, long durationUs, int frames, int ticksPerSecond, @Nullable Inputs inputs,
                 List<ReconstructionStructure.Bar> bars, MidiScore source, Map<Integer, Integer> sourceCounts,
                 List<Note> notes, List<S3kReconstruction.Reduction> reductions,
                 List<ReconstructionPitchRefinement.Change> pitchChanges) {
        Score withInputs(Inputs inputs, List<ReconstructionStructure.Bar> bars, List<Note> notes) {
            return new Score(version, durationUs, frames, ticksPerSecond, inputs, bars, source, sourceCounts, notes, reductions, pitchChanges);
        }
    }

    record Candidate(String id, int part, double start, double end, int pitch, int velocity) {}
    record Candidates(int version, List<Candidate> notes) {}

    private record StageOptions(String[] legacy, @Nullable Path candidates, @Nullable Double harmonyHz) {}
    private record Request(Path work, Stage from, boolean chain, @Nullable Score initial, @Nullable Double harmonyHz) {}

    private ReconstructionStages() {}

    static boolean isCommand(String token) {
        return token.equals("run") || Arrays.stream(Stage.values()).anyMatch(stage -> stage.name().equalsIgnoreCase(token));
    }

    static void run(String command, String[] args, PrintStream out) throws IOException {
        Request request = request(command, args);
        Path work = request.work;
        Path scoreFile = work.resolve("score.json");
        Path structureFile = work.resolve("structure.json");
        Path rolesFile = work.resolve("roles.json");
        Path allocationFile = work.resolve("allocation.json");

        if (request.initial != null) Files.createDirectory(work);
        for (var stage : Stage.values()) {
            if (stage.ordinal() < request.from.ordinal()) continue;
            if (stage == Stage.TRANSCRIBE) {
                Score score = request.initial != null ? request.initial : retranscribe(readScore(scoreFile));
                ReconstructionArtifacts.write(scoreFile, score);
            } else {
                var score = readScore(scoreFile);
                switch (stage) {
                    case STRUCTURE -> ReconstructionArtifacts.write(structureFile, ReconstructionStructure.analyze(
                            score.bars, score.notes.stream().sorted(NOTE_ORDER).toList(), score.source.parts()));
                    case ROLES -> {
                        var structure = readStructure(structureFile, score);
                        double rate = request.harmonyHz == null ? score.inputs.harmonyMaxHz : request.harmonyHz;
                        ReconstructionArtifacts.write(rolesFile, ReconstructionRoles.assign(structure, score.source.parts(), rate));
                    }
                    case ALLOCATE -> {
                        var roles = ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class);
                        ReconstructionChecks.roles(roles, score);
                        ReconstructionArtifacts.write(allocationFile, ReconstructionAllocation.allocate(
                                roles, score.source.parts(), score.frames, score.inputs.fmOnly));
                    }
                    case ASSEMBLE -> {
                        var structure = readStructure(structureFile, score);
                        var allocation = ReconstructionArtifacts.read(allocationFile, ReconstructionAllocation.Result.class);
                        ReconstructionChecks.allocation(allocation, score);
                        ReconstructionCli.assembleAllocated(work, score, structure, allocation, out);
                    }
                    default -> throw new AssertionError(stage);
                }
            }
            if (!request.chain) break;
        }
    }

    private static Request request(String command, String[] args) throws IOException {
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
        String[] legacy = java.util.stream.Stream.concat(java.util.stream.Stream.of("reconstruct"),
                Arrays.stream(extra.legacy)).toArray(String[]::new);
        var options = ReconstructionCli.parseOptions(legacy, midi.getFileName().toString());
        double harmonyHz = extra.harmonyHz == null ? ReconstructionRoles.DEFAULT_HARMONY_HZ : extra.harmonyHz;
        var stems = new java.util.TreeMap<Integer, String>();
        options.stems().forEach((id, file) -> stems.put(id, file.toAbsolutePath().normalize().toString()));
        var inputs = new Inputs(midi.toString(), reference.toString(), index.toString(), options.name(),
                options.ticksPerBeat(), options.keepOctaves(), options.fmOnly(), options.wholePartVoices(),
                options.audioRefine(), stems, extra.candidates == null ? null : extra.candidates.toString(), harmonyHz);
        var score = transcribe(midi, reference, options);
        return new Request(work, stage, chain, attach(score, inputs), extra.harmonyHz);
    }

    private static Request resume(String[] args, int workIndex, Stage stage, boolean chain) {
        if (args.length <= workIndex) throw new IllegalArgumentException("Expected a reconstruction WORK_DIR");
        var extra = stageOptions(Arrays.copyOfRange(args, workIndex + 1, args.length), 0);
        if (extra.legacy.length != 0 || extra.candidates != null
                || extra.harmonyHz != null && (chain ? stage.ordinal() > Stage.ROLES.ordinal() : stage != Stage.ROLES))
            throw new IllegalArgumentException("Unsupported options when resuming reconstruct " + stage.name().toLowerCase(Locale.ROOT));
        return new Request(absolute(args[workIndex]), stage, chain, null, extra.harmonyHz);
    }

    private static StageOptions stageOptions(String[] args, int firstOption) {
        Path candidates = null;
        Double hz = null;
        var legacy = new ArrayList<>(Arrays.asList(args).subList(0, firstOption));
        for (int i = firstOption; i < args.length; i++) {
            switch (args[i]) {
                case "--candidates" -> {
                    if (candidates != null || ++i == args.length)
                        throw new IllegalArgumentException("Expected one --candidates NOTES.json");
                    candidates = absolute(args[i]);
                }
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
        return new StageOptions(legacy.toArray(String[]::new), candidates, hz);
    }

    private static Path absolute(String path) { return Path.of(path).toAbsolutePath().normalize(); }

    private static Score readScore(Path file) throws IOException {
        Score score = ReconstructionArtifacts.read(file, Score.class);
        ReconstructionChecks.score(score);
        return score;
    }

    private static ReconstructionStructure.Result readStructure(Path file, Score score) throws IOException {
        var structure = ReconstructionArtifacts.read(file, ReconstructionStructure.Result.class);
        ReconstructionChecks.structure(structure, score);
        return new ReconstructionStructure.Result(structure.version(), structure.bars(), structure.sections(),
                structure.repeats(), structure.corrections(), structure.notes().stream().sorted(NOTE_ORDER).toList());
    }

    private static Score retranscribe(Score saved) throws IOException {
        var inputs = saved.inputs;
        var score = transcribe(Path.of(inputs.midi), Path.of(inputs.wav), inputs.options(saved.durationUs));
        return attach(score, inputs);
    }

    private static Score attach(Score score, Inputs inputs) throws IOException {
        var notes = new ArrayList<>(score.notes);
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
        var bars = MidiScore.bars(Path.of(inputs.midi), score.durationUs);
        var result = score.withInputs(inputs, bars, notes.stream().sorted(NOTE_ORDER).toList());
        ReconstructionChecks.score(result);
        return result;
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
            String key = "n-" + note.partId() + "-" + note.startUs() + "-" + note.pitch();
            int instance = ids.merge(key, 1, Integer::sum);
            notes.add(new Note(instance == 1 ? key : key + "-" + instance, note.partId(),
                    note.startUs() / (double) MICROS_PER_SECOND, note.endUs() / (double) MICROS_PER_SECOND,
                    note.pitch(), note.velocity(), remaining == 0 ? "audio-refine" : "midi", remaining == 0 ? .85 : 1));
        }
        return new Score(VERSION, durationUs, frames, PRESENTATIONS_PER_SECOND, null, List.of(), score, counts, notes,
                prepared.reductions(), refinement.changes());
    }
}
