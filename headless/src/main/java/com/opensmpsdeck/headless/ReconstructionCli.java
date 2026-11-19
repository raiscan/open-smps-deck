package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private record MatchEvidence(Kind kind, ReconstructionVoices.Key key, ReconstructionVoices.Choice choice) {}
    private record Report(int version, String matcher, long referenceUs, int presentations, int bankBytes,
                          int sourceNotes, int fmNotes, int psgNotes, int dacHits, boolean keepOctaves, boolean fmOnly,
                          Map<String, FileEvidence> inputs, Map<String, FileEvidence> outputs,
                          List<PartEvidence> parts, List<MatchEvidence> matches, List<String> midiWarnings,
                          boolean wholePartVoices, List<ReconstructionVoices.SectionTrial> sectionTrials,
                          boolean audioRefine, int refinedNotes, List<ReconstructionPitchRefinement.Change> pitchChanges,
                          List<ReconstructionEcho.Estimate> echoEstimates, int echoNotes, boolean echoBankLimit,
                          Map<Integer, ReconstructionVibrato.Estimate> vibratoEstimates,
                          List<ReconstructionReverb.Estimate> reverbEstimates,
                          List<S3kReconstruction.Reduction> reductions, List<ReconstructionAudioCheck.Section> audio) {}

    @FunctionalInterface
    private interface Matching<T> {
        T run() throws IOException;
    }

    private ReconstructionCli() {}

    static void usage(PrintStream out) {
        out.println("       reconstruct run MIDI WAV INDEX.json WORK_DIR [options]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar reconstruct MIDI WAV INDEX.json OUTPUT_DIR");
        out.println("           [--seconds N] [--name NAME] [--ticks-per-beat N] [--keep-octaves] [--fm-only] [--whole-part-voices] [--audio-refine] [--stem PART=WAV]...");
    }

    static void run(String[] args, PrintStream out) throws IOException {
        if (args.length == 2 && args[1].equals("--help")) {
            usage(out);
            return;
        }
        if (args.length >= 2 && args[1].equals("run")) {
            ReconstructionStages.run(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length < 5) throw new IllegalArgumentException("reconstruct expects MIDI WAV INDEX.json OUTPUT_DIR");
        Path midi = Path.of(args[1]), reference = Path.of(args[2]), index = Path.of(args[3]);
        Path target = Path.of(args[4]).toAbsolutePath().normalize();
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Options options = parseOptions(args, midi.getFileName().toString());
        var transcription = ReconstructionStages.transcribe(midi, reference, options);
        assemble(midi, reference, index, target, target, options, transcription, transcription.notes(), out);
    }

    static void assemble(Path midi, Path reference, Path index, Path target, Path work,
                         Options options, ReconstructionStages.Score transcription,
                         List<ReconstructionStages.Note> notes, PrintStream out) throws IOException {
        long durationUs = transcription.durationUs();
        int frames = transcription.frames();
        MidiScore score = transcription.source();
        var prepared = new S3kReconstruction.Score(score.parts(),
                notes.stream().map(ReconstructionStages.Note::midi).toList(), transcription.reductions());
        var echoes = options.audioRefine() ? ReconstructionEcho.detect(score, reference, options.stems(), durationUs)
                : Map.<Integer, ReconstructionEcho.Estimate>of();
        var vibrato = options.audioRefine() ? ReconstructionVibrato.detect(score, reference, options.stems(), durationUs)
                : Map.<Integer, ReconstructionVibrato.Estimate>of();
        var reverb = options.audioRefine() ? ReconstructionReverb.detect(score, reference, options.stems(), durationUs)
                : Map.<Integer, ReconstructionReverb.Estimate>of();
        if (prepared.notes().isEmpty()) throw new IllegalArgumentException("No playable MIDI notes in the requested interval");
        InstrumentCatalogue catalogue = InstrumentCatalogue.open(index);
        var choices = select(catalogue, reference, options, score, prepared, durationUs, Kind.FM_VOICE);
        var spill = options.fmOnly() ? Map.<ReconstructionVoices.Key, ReconstructionVoices.Choice>of()
                : select(catalogue, reference, options, score, prepared, durationUs, Kind.PSG_VOLUME);
        var sectionPlan = options.wholePartVoices() ? new ReconstructionVoices.SectionPlan(choices, List.of())
                : matchQuietly(() -> ReconstructionVoices.refineSections(catalogue, reference, options.stems(),
                        score, prepared.notes(), durationUs, choices));
        var result = S3kReconstruction.arrange(options.name(), options.ticksPerBeat(), frames, prepared,
                catalogue, sectionPlan.choices(), spill, echoes);
        com.opensmpsdeck.project.CompiledProject compiled;
        boolean echoBankLimit = false;
        try {
            compiled = ProjectCompiler.compileWithAssets(result.project());
        } catch (IllegalArgumentException failure) {
            if (result.echoNotes() == 0 || !failure.getMessage().contains("32 KiB S3K bank")) throw failure;
            echoBankLimit = true;
            result = S3kReconstruction.arrange(options.name(), options.ticksPerBeat(), frames, prepared,
                    catalogue, sectionPlan.choices(), spill);
            compiled = ProjectCompiler.compileWithAssets(result.project());
        }
        if (!target.equals(work) || !Files.isDirectory(target)) Files.createDirectory(target);
        boolean complete = false;
        try {
            Path source = target.resolve(OUTPUTS.get(0));
            Path packagePath = target.resolve(OUTPUTS.get(1));
            Path audio = target.resolve(OUTPUTS.get(2));
            ProjectFiles.save(source, result.project(), null);
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
            var matches = new java.util.ArrayList<MatchEvidence>();
            sectionPlan.choices().forEach((key, choice) -> matches.add(
                    new MatchEvidence(key.drumPitch() == null ? Kind.FM_VOICE : Kind.DAC_SAMPLE, key, choice)));
            spill.forEach((key, choice) -> matches.add(new MatchEvidence(Kind.PSG_VOLUME, key, choice)));
            var report = new Report(REPORT_VERSION, CatalogueMatcher.ASSET_PROFILE, durationUs, frames, compiled.song().length,
                    noteCounts.values().stream().mapToInt(Integer::intValue).sum(), result.fmNotes(), result.psgNotes(),
                    result.dacHits(), options.keepOctaves(), options.fmOnly(), inputEvidence, outputEvidence, partEvidence,
                    matches, score.warnings(), options.wholePartVoices(), sectionPlan.trials(),
                    options.audioRefine(), (int) score.notes().stream().filter(note -> note.startUs() < durationUs).count(),
                    transcription.pitchChanges(), List.copyOf(echoes.values()), result.echoNotes(), echoBankLimit,
                    vibrato, List.copyOf(reverb.values()), result.reductions(), ReconstructionAudioCheck.compare(reference, audio, frames));
            var json = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
            Files.writeString(target.resolve(OUTPUTS.get(3)), json.toJson(report) + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
            complete = true;
        } finally {
            if (!complete) {
                for (String name : OUTPUTS) Files.deleteIfExists(target.resolve(name));
                if (!Files.exists(target.resolve("score.json"))) Files.delete(target);
            }
        }
        out.printf("Reconstructed %d FM notes, %d PSG notes, and %d DAC hits over %d presentations into %s%n",
                result.fmNotes(), result.psgNotes(), result.dacHits(), frames, target);
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
