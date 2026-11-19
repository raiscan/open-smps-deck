package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.project.LoadedProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

final class ReconstructionCli {
    private static final long MICROS_PER_SECOND = 1_000_000;
    private static final int REPORT_VERSION = 4;
    private static final List<String> OUTPUTS = List.of("arrangement.smps.json", "arrangement.smps.zip",
            "render.wav", "report.json");

    record Options(Long durationUs, String name, int ticksPerBeat, boolean keepOctaves, boolean fmOnly, boolean wholePartVoices, boolean audioRefine,
                           Map<Integer, Path> stems) {}
    private record FileEvidence(String file, String sha256) {}
    private record PartEvidence(int id, String name, int program, boolean drums, int notesInRange) {}
    private record MatchEvidence(Kind kind, String lane, ReconstructionVoices.Key key, ReconstructionVoices.Choice choice) {}
    private record StageEvidence(int repeatGroups, long consensusCorrections, long harmonyMerges, long harmonyDrops,
                                 int linkedClips) {}
    private record Effects(Map<Integer, ReconstructionEcho.Estimate> echoes,
                           Map<Integer, ReconstructionVibrato.Estimate> vibrato,
                           Map<Integer, ReconstructionReverb.Estimate> reverb) {}
    private record Matched(Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> byLane,
                           List<MatchEvidence> evidence, List<ReconstructionVoices.SectionTrial> sectionTrials) {}
    private record Report(int version, String matcher, long referenceUs, int presentations, int bankBytes,
                          int sourceNotes, int fmNotes, int psgNotes, int dacHits, boolean keepOctaves, boolean fmOnly,
                          Map<String, FileEvidence> inputs, Map<String, FileEvidence> outputs,
                          List<PartEvidence> parts, List<MatchEvidence> matches, List<String> midiWarnings,
                          boolean wholePartVoices, List<ReconstructionVoices.SectionTrial> sectionTrials,
                          boolean audioRefine, int refinedNotes, List<ReconstructionPitchRefinement.Change> pitchChanges,
                          List<ReconstructionEcho.Estimate> echoEstimates, int echoNotes, boolean echoBankLimit,
                          Map<Integer, ReconstructionVibrato.Estimate> vibratoEstimates,
                          List<ReconstructionReverb.Estimate> reverbEstimates,
                          List<S3kReconstruction.Reduction> reductions, List<ReconstructionAudioCheck.Section> audio,
                          StageEvidence stages, List<String> effectCopyLanes) {}

    @FunctionalInterface
    private interface Matching<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    private interface Arrangement {
        S3kReconstruction.Result arrange(Map<Integer, ReconstructionEcho.Estimate> echoes, boolean copies);
    }

    private ReconstructionCli() {}

    static void usage(PrintStream out) {
        out.println("       reconstruct run|transcribe MIDI WAV INDEX.json WORK_DIR [options]");
        out.println("           [--candidates NOTES.json] [--harmony-max-hz RATE]");
        out.println("           [--estimate-tempo | --tempo BPM [--bar-offset S]] [--audio-repeats]");
        out.println("       reconstruct structure|roles|allocate|voice|assemble WORK_DIR");
        out.println("       reconstruct describe WORK_DIR [--json] [--section ID] [--parts IDS] [--bars FROM-TO] [--out FILE]");
        out.println("       reconstruct plan-template WORK_DIR");
        out.println("       reconstruct run --from STAGE WORK_DIR [--harmony-max-hz RATE]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar reconstruct MIDI WAV INDEX.json OUTPUT_DIR");
        out.println("           [--seconds N] [--name NAME] [--ticks-per-beat N] [--keep-octaves] [--fm-only] [--whole-part-voices] [--audio-refine] [--stem PART=WAV]...");
    }

    static void run(String[] args, PrintStream out) throws IOException {
        if (args.length == 2 && args[1].equals("--help")) {
            usage(out);
            return;
        }
        if (args.length >= 2 && args[1].equals("describe")) {
            ReconstructionDescribe.run(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("plan-template")) {
            if (args.length != 3) throw new IllegalArgumentException("reconstruct plan-template expects WORK_DIR");
            out.println(ReconstructionStages.planTemplate(Path.of(args[2])));
            return;
        }
        if (args.length >= 2 && ReconstructionStages.isCommand(args[1])) {
            ReconstructionStages.run(args[1], java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length < 5) throw new IllegalArgumentException("reconstruct expects MIDI WAV INDEX.json OUTPUT_DIR");
        Path midi = Path.of(args[1]), reference = Path.of(args[2]), index = Path.of(args[3]);
        Path target = Path.of(args[4]).toAbsolutePath().normalize();
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Options options = parseOptions(args, midi.getFileName().toString());
        var transcription = ReconstructionStages.transcribe(midi, reference, options);
        assembleLegacy(midi, reference, index, target, options, transcription, out);
    }

    private static void assembleLegacy(Path midi, Path reference, Path index, Path target,
                                       Options options, ReconstructionStages.Score transcription,
                                       PrintStream out) throws IOException {
        long durationUs = transcription.durationUs();
        int frames = transcription.frames();
        MidiScore score = transcription.source();
        var prepared = new S3kReconstruction.Score(score.parts(),
                transcription.notes().stream().map(ReconstructionStages.Note::midi).toList(), transcription.reductions());
        if (prepared.notes().isEmpty()) throw new IllegalArgumentException("No playable MIDI notes in the requested interval");
        InstrumentCatalogue catalogue = InstrumentCatalogue.open(index);
        var choices = select(catalogue, reference, options, score, prepared, durationUs, Kind.FM_VOICE);
        var spill = options.fmOnly() ? Map.<ReconstructionVoices.Key, ReconstructionVoices.Choice>of()
                : select(catalogue, reference, options, score, prepared, durationUs, Kind.PSG_VOLUME);
        var sectionPlan = options.wholePartVoices() ? new ReconstructionVoices.SectionPlan(choices, List.of())
                : matchQuietly(() -> ReconstructionVoices.refineSections(catalogue, reference, options.stems(),
                        score, prepared.notes(), durationUs, choices));
        var evidence = new ArrayList<MatchEvidence>();
        recordMatches(evidence, null, Kind.FM_VOICE, sectionPlan.choices());
        recordMatches(evidence, null, Kind.PSG_VOLUME, spill);
        var matched = new Matched(Map.of(), evidence, sectionPlan.trials());
        var effects = effects(score, reference, options, durationUs);
        Files.createDirectory(target);
        render(midi, reference, index, target, target, options, transcription, matched, effects, null, List.of(),
                (echoes, copies) -> S3kReconstruction.arrange(options.name(), options.ticksPerBeat(), frames, prepared,
                        catalogue, sectionPlan.choices(), spill, echoes), out, null);
    }

    static ReconstructionVoicing.Result fitVoicing(ReconstructionStages.Score score,
            ReconstructionAllocation.Result allocation) throws IOException {
        var catalogue = InstrumentCatalogue.open(Path.of(score.inputs().catalogue()));
        return matchQuietly(() -> ReconstructionVoiceStage.fit(score, catalogue, allocation));
    }

    static void assembleAllocated(Path work, ReconstructionStages.Score score, ReconstructionStructure.Result structure,
                                  ReconstructionAllocation.Result allocation, ReconstructionVoicing.Result voicing,
                                  PrintStream out, boolean preserveReview) throws IOException {
        Path destination = work.resolve(OUTPUTS.getFirst());
        var existing = preserveReview && Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                ? ProjectFiles.load(destination) : null;
        var inputs = score.inputs();
        Path midi = Path.of(inputs.midi()), reference = Path.of(inputs.wav()), index = Path.of(inputs.catalogue());
        var options = inputs.options(score.durationUs());
        var catalogue = InstrumentCatalogue.open(index);
        var ordered = new ReconstructionAllocation.Result(allocation.version(), allocation.notes().stream()
                .sorted(Comparator.comparingInt(entry -> entry.startTick() == null
                        ? entry.note().startTick(score.frames()) : entry.startTick())).toList());
        ReconstructionVoicing.validate(voicing, ordered, catalogue);
        var matched = voicedMatches(catalogue, ordered, voicing);
        var effects = new Effects(Map.of(), Map.of(), Map.of());
        var copyLanes = voicing.lanes().values().stream().map(lane -> lane.effects().copy())
                .filter(java.util.Objects::nonNull).map(ReconstructionVoicing.Copy::lane).sorted().toList();
        var staging = Files.createTempDirectory(work, ".assemble-");
        try {
            render(midi, reference, index, staging, work, options, score, matched, effects, structure, copyLanes,
                    (echoes, copies) -> S3kReconstruction.arrangeAllocated(options.name(), options.ticksPerBeat(), score.frames(),
                            new S3kReconstruction.Score(score.source().parts(), List.of(), score.reductions()), ordered,
                            structure, catalogue, matched.byLane(), echoes, voicing.lanes(), copies), out, existing);
            if (preserveReview) {
                ProjectFiles.save(destination, ProjectFiles.load(staging.resolve(OUTPUTS.getFirst())).project(),
                        existing == null ? null : existing.revision());
            }
            for (String name : OUTPUTS) {
                if (preserveReview && name.equals(OUTPUTS.getFirst())) continue;
                Files.move(staging.resolve(name), work.resolve(name),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            for (String name : OUTPUTS) Files.deleteIfExists(staging.resolve(name));
            Files.deleteIfExists(staging);
        }
    }

    private static Effects effects(MidiScore score, Path reference, Options options, long durationUs) throws IOException {
        if (!options.audioRefine()) return new Effects(Map.of(), Map.of(), Map.of());
        return new Effects(ReconstructionEcho.detect(score, reference, options.stems(), durationUs),
                ReconstructionVibrato.detect(score, reference, options.stems(), durationUs),
                ReconstructionReverb.detect(score, reference, options.stems(), durationUs));
    }

    private static Matched voicedMatches(InstrumentCatalogue catalogue, ReconstructionAllocation.Result allocation,
                                         ReconstructionVoicing.Result voicing) {
        var result = new LinkedHashMap<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>>();
        var evidence = new ArrayList<MatchEvidence>();
        ReconstructionVoicing.byLane(allocation).forEach((id, notes) -> {
            var lane = voicing.lanes().get(id);
            var choices = ReconstructionVoiceStage.choices(catalogue, lane, notes);
            result.put(id, choices);
            var kind = id.startsWith("PSG") ? Kind.PSG_VOLUME : Kind.FM_VOICE;
            if (lane.variantId() != null && lane.articulation().minimumReleaseRate() > 0) {
                var asset = catalogue.match(lane.variantId()).asset();
                String emitted = ReconstructionVoicing.patchId(asset, ReconstructionVoicing.patchBytes(asset, lane.articulation()));
                choices.forEach((key, choice) -> evidence.add(new MatchEvidence(kind, id, key,
                        new ReconstructionVoices.Choice(emitted, choice.variantId(), choice.name(), null,
                                choice.status(), choice.probes(), choice.shortlist()))));
            } else {
                recordMatches(evidence, id, kind, choices);
            }
        });
        return new Matched(result, evidence, List.of());
    }

    private static void recordMatches(List<MatchEvidence> evidence, String lane, Kind kind,
                                      Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> choices) {
        choices.forEach((key, choice) -> evidence.add(new MatchEvidence(
                key.drumPitch() == null ? kind : Kind.DAC_SAMPLE, lane, key, choice)));
    }

    private static void render(Path midi, Path reference, Path index, Path target, Path destination,
                               Options options, ReconstructionStages.Score transcription, Matched matched,
                               Effects effects, ReconstructionStructure.Result structure, List<String> copyLanes,
                               Arrangement arrangement, PrintStream out, LoadedProject existing) throws IOException {
        long durationUs = transcription.durationUs();
        int frames = transcription.frames();
        MidiScore score = transcription.source();
        boolean complete = false;
        try {
            var result = arrangement.arrange(effects.echoes(), true);
            com.opensmpsdeck.project.CompiledProject compiled;
            boolean echoBankLimit = false;
            try {
                compiled = ProjectCompiler.compileWithAssets(result.project());
            } catch (IllegalArgumentException failure) {
                if (result.echoNotes() == 0 || !failure.getMessage().contains("32 KiB S3K bank")) throw failure;
                echoBankLimit = true;
                result = arrangement.arrange(Map.of(), false);
                compiled = ProjectCompiler.compileWithAssets(result.project());
            }
            Path source = target.resolve(OUTPUTS.get(0));
            Path packagePath = target.resolve(OUTPUTS.get(1));
            Path audio = target.resolve(OUTPUTS.get(2));
            var project = result.project();
            if (existing != null) project = project.withReview(existing.project().references(), existing.project().notes());
            ProjectFiles.save(source, project, null);
            ProjectPackage.write(result.project(), packagePath);
            WaveFile.render(compiled, audio, frames);
            var inputEvidence = new LinkedHashMap<String, FileEvidence>();
            inputEvidence.put("midi", evidence(midi));
            inputEvidence.put("wav", evidence(reference));
            inputEvidence.put("catalogue", evidence(index));
            for (var stem : options.stems().entrySet()) inputEvidence.put("stem-" + stem.getKey(), evidence(stem.getValue()));
            var outputEvidence = new LinkedHashMap<String, FileEvidence>();
            outputEvidence.put("project", evidence(source));
            outputEvidence.put("package", evidence(packagePath));
            outputEvidence.put("wav", evidence(audio));
            var noteCounts = transcription.sourceCounts();
            var partEvidence = score.parts().stream().map(p -> new PartEvidence(p.id(), p.name(), p.program(),
                    p.drums(), noteCounts.getOrDefault(p.id(), 0))).toList();
            var stages = structure == null ? null : new StageEvidence(structure.repeats().size(),
                    structure.corrections().stream().filter(ReconstructionStructure.Correction::applied).count(),
                    result.reductions().stream().filter(r -> "rapid-inner-tone-reattack".equals(r.reason())).count(),
                    result.reductions().stream().filter(r -> "isolated-harmony-octave-double".equals(r.reason())).count(),
                    result.project().clips().size() - result.project().sequences().size());
            var report = new Report(REPORT_VERSION, CatalogueMatcher.ASSET_PROFILE, durationUs, frames, compiled.song().length,
                    noteCounts.values().stream().mapToInt(Integer::intValue).sum(), result.fmNotes(), result.psgNotes(),
                    result.dacHits(), options.keepOctaves(), options.fmOnly(), inputEvidence, outputEvidence, partEvidence,
                    matched.evidence(), score.warnings(), options.wholePartVoices(), matched.sectionTrials(),
                    options.audioRefine(), (int) score.notes().stream().filter(note -> note.startUs() < durationUs).count(),
                    transcription.pitchChanges(), List.copyOf(effects.echoes().values()), result.echoNotes(), echoBankLimit,
                    effects.vibrato(), List.copyOf(effects.reverb().values()), result.reductions(),
                    ReconstructionAudioCheck.compare(reference, audio, frames), stages,
                    result.echoNotes() == 0 ? List.of() : copyLanes);
            var json = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
            Files.writeString(target.resolve(OUTPUTS.get(3)), json.toJson(report) + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
            complete = true;
            out.printf("Reconstructed %d FM notes, %d PSG notes, and %d DAC hits over %d presentations into %s%n",
                    result.fmNotes(), result.psgNotes(), result.dacHits(), frames, destination);
        } finally {
            if (!complete) {
                for (String name : OUTPUTS) Files.deleteIfExists(target.resolve(name));
                Files.delete(target);
            }
        }
    }

    private static Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> select(
            InstrumentCatalogue catalogue, Path reference, Options options, MidiScore score,
            S3kReconstruction.Score prepared, long durationUs, Kind kind) throws IOException {
        return matchQuietly(() -> ReconstructionVoices.select(catalogue, reference, options.stems(),
                score, prepared.notes(), durationUs, kind));
    }

    private static <T> T matchQuietly(Matching<T> matching) throws IOException {
        // The engine logs its chip selection at INFO for every probe render.
        Logger logger = Logger.getLogger("com.openggf.audio.session.SmpsPhysicalDevice");
        Level previous = logger.getLevel();
        try {
            if (logger.isLoggable(Level.INFO)) logger.setLevel(Level.WARNING);
            return matching.run();
        } finally {
            logger.setLevel(previous);
        }
    }

    static Options parseOptions(String[] args, String file) {
        Long duration = null;
        String name = file.replaceFirst("(?i)\\.midi?$", "");
        int ticksPerBeat = 30;
        boolean keepOctaves = false;
        boolean fmOnly = false;
        boolean wholePartVoices = false;
        boolean audioRefine = false;
        var stems = new java.util.TreeMap<Integer, Path>();
        var seen = new java.util.HashSet<String>();
        for (int i = 5; i < args.length; i++) {
            String option = args[i];
            if (!option.equals("--stem") && !seen.add(option)) throw new IllegalArgumentException("Duplicate option " + option);
            if (option.equals("--keep-octaves")) {
                keepOctaves = true;
                continue;
            }
            if (option.equals("--fm-only")) {
                fmOnly = true;
                continue;
            }
            if (option.equals("--whole-part-voices")) {
                wholePartVoices = true;
                continue;
            }
            if (option.equals("--audio-refine")) {
                audioRefine = true;
                continue;
            }
            if (++i == args.length) throw new IllegalArgumentException("Missing argument to " + option);
            String value = args[i];
            switch (option) {
                case "--seconds" -> {
                    double seconds = Double.parseDouble(value);
                    if (!(seconds > 0 && seconds <= S3kRenderer.MAX_PRESENTATIONS / (double) PRESENTATIONS_PER_SECOND))
                        throw new IllegalArgumentException("--seconds must be in (0, 600]");
                    duration = Math.round(seconds * MICROS_PER_SECOND);
                }
                case "--name" -> name = value;
                case "--ticks-per-beat" -> {
                    ticksPerBeat = Integer.parseInt(value);
                    if (ticksPerBeat < 1) throw new IllegalArgumentException("--ticks-per-beat must be positive");
                }
                case "--stem" -> {
                    int split = value.indexOf('=');
                    if (split < 1 || split == value.length() - 1) throw new IllegalArgumentException("--stem expects PART=WAV");
                    int part = Integer.parseInt(value.substring(0, split));
                    if (part < 0) throw new IllegalArgumentException("MIDI part ID must be nonnegative");
                    if (stems.putIfAbsent(part, Path.of(value.substring(split + 1))) != null)
                        throw new IllegalArgumentException("Duplicate --stem for part " + part);
                }
                default -> throw new IllegalArgumentException("Unknown reconstruct option " + option);
            }
        }
        if (name.isBlank()) throw new IllegalArgumentException("Project name must not be blank");
        return new Options(duration, name, ticksPerBeat, keepOctaves, fmOnly, wholePartVoices, audioRefine, stems);
    }

    private static FileEvidence evidence(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return new FileEvidence(path.getFileName().toString(), HexFormat.of().formatHex(digest.digest()));
    }
}
