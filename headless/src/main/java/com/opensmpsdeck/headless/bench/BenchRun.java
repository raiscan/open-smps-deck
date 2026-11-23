package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchCartridge.Game;
import com.opensmpsdeck.headless.BenchClock;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.LeadGap;
import com.opensmpsdeck.headless.ReferenceStretch;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.DoubleUnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BenchRun {
    record Options(boolean quick, boolean holdout, boolean audioRefine, int jobs, Path gold, boolean goldOnly,
                   BenchRolePlan.Mode rolePlan, String steadyTempo, TargetGame target, boolean keepWork) {
        Options(boolean quick, boolean holdout, boolean audioRefine, int jobs, Path gold, boolean goldOnly,
                BenchRolePlan.Mode rolePlan, String steadyTempo, TargetGame target) {
            this(quick, holdout, audioRefine, jobs, gold, goldOnly, rolePlan, steadyTempo, target, false);
        }

        boolean classic() { return target != TargetGame.S3K; }
    }
    private record Song(Path truthFile, List<BenchMetrics.Event> truth, float[] reference, Path index,
                        BenchTimeline.Truth nativeTruth) {}

    private record TonalScore(BenchMetrics.F1 notes, BenchMetrics.F1 offsets, BenchMetrics.F1 melody,
                              BenchVoice.Score voices, List<BenchMetrics.Pair> pairs) {}

    private static final String WITH_LAYERS = "WithLayers";

    private BenchRun() {}

    static int run(Path manifestFile, Path index, Path output, Options options, PrintStream out,
                   BenchCli.Hooks hooks) throws IOException {
        var manifest = BenchFiles.read(manifestFile, BenchCorpus.Manifest.class);
        if (manifest.version() != 1 || manifest.cases() == null || manifest.frames() < 1 || manifest.frames() > 36_000)
            throw new IllegalArgumentException("Unsupported or invalid benchmark manifest");
        var cases = manifest.cases().stream().filter(c -> !options.goldOnly)
                .filter(c -> !options.quick || (c.ladder() == null ? BenchCorpus.QUICK.contains(c.song())
                        : c.ladder().torture() || c.ladder().game().ladderSongs().contains(c.song()))).toList();
        if (cases.isEmpty() && !options.goldOnly) throw new IllegalArgumentException("No corpus cases selected");
        var gold = options.gold == null ? null : BenchGold.read(options.gold);
        if (options.goldOnly && gold == null) throw new IllegalArgumentException("--gold-only requires --gold");
        var goldInputs = gold == null ? Map.<String, String>of() : goldInputs(gold);
        var corpus = manifestFile.toAbsolutePath().normalize().getParent();
        var seen = new HashSet<String>();
        var verified = new HashMap<Path, String>();
        for (var c : cases) {
            if (!seen.add(c.id()) || c.profile() == null || c.variant() == null)
                throw new IllegalArgumentException("Duplicate or invalid case: " + c.id());
            var files = new ArrayList<>(List.of(c.truth(), c.reference(), c.audio()));
            if (c.midi() != null) files.add(c.midi());
            else if (c.ladder() == null || c.ladder().rung() != BenchLadder.Rung.LOCAL)
                throw new IllegalArgumentException("Case has no MIDI: " + c.id());
            if (c.ladder() != null) {
                var input = c.ladder();
                if (input.game() == null || input.rung() == null || input.frames() < 1 || input.frames() > manifest.frames()
                        || !c.id().equals(input.game() + "/%02x/".formatted(c.song()) + input.rung().label)
                        || c.song() < 1 || c.song() > 255)
                    throw new IllegalArgumentException("Invalid ladder case: " + c.id());
                files.addAll(input.stems().values());
                if (input.transcription() != null) files.add(input.transcription());
            }
            for (var file : files) {
                var path = file.resolve(corpus);
                if (!verified.containsKey(path)) verified.put(path, BenchFiles.hash(path));
                if (!verified.get(path).equals(file.sha256())) throw new IOException("Corpus SHA-256 mismatch: " + path);
            }
        }

        var catalogue = InstrumentCatalogue.open(index);
        var game = cases.stream().filter(c -> c.ladder() != null).findFirst().map(c -> c.ladder().game())
                .orElse(Game.s3k);
        if (cases.stream().anyMatch(c -> c.ladder() != null && c.ladder().game() != game))
            throw new IllegalArgumentException("Each ladder manifest must contain a single game");
        String revision = "sha256:" + manifest.romSha256();
        var truthDrums = new BenchDrums(options.classic() ? catalogue.forTarget(TargetGame.parse(game.name())) : catalogue,
                revision, game);
        var scoringCatalogue = options.classic() ? catalogue.forTarget(options.target) : catalogue;
        BenchDrums outputDrums = truthDrums;
        if (options.classic()) {
            if (scoringCatalogue.variants().isEmpty())
                throw new IllegalArgumentException("Benchmark catalogue has no " + options.target.id()
                        + " variants. Use catalog scan INDEX.json --game " + options.target.id() + " --rom ROM.");
            var rom = RomLibrary.userLibrary().resolve(RomGame.parse(options.target.id()), null);
            outputDrums = new BenchDrums(scoringCatalogue, "sha256:" + BenchFiles.hash(rom.bytes()), Game.valueOf(options.target.id()));
        }
        var drums = outputDrums;

        Files.createDirectories(output);
        if (Files.exists(output.resolve("scorecard.json")) || Files.exists(output.resolve("scorecard.md")))
            throw new IOException("Benchmark scorecard already exists: " + output);
        boolean ladder = cases.stream().anyMatch(c -> c.ladder() != null);
        if (ladder) Files.createDirectory(output.resolve("cases"));
        var scratch = ladder
                ? Files.createTempDirectory("opensmpsdeck-bench-") : Files.createTempDirectory(output, ".bench-");
        var logger = Logger.getLogger("com.openggf.audio.session.SmpsPhysicalDevice");
        Level previousLevel = logger.getLevel();
        try {
            logger.setLevel(Level.WARNING);
            var songs = new HashMap<Integer, Song>();
            var tasks = new ArrayList<Callable<BenchScorecard.Case>>();
            var warps = new java.util.concurrent.ConcurrentSkipListMap<String, Boolean>();
            var reductions = new java.util.concurrent.ConcurrentSkipListMap<String, Map<String, Long>>();
            if (gold != null) tasks.add(() -> capture(gold.id(), null, null, scratch, out,
                    () -> scoreGold(gold, index, scratch.resolve("gold"), options, drums, hooks, reductions)));
            for (var c : cases) {
                Song song = songs.get(c.song());
                if (song == null) {
                    Path useIndex = index;
                    if (options.holdout) {
                        useIndex = scratch.resolve("holdout-" + c.song() + ".json");
                        BenchFiles.write(useIndex, BenchHoldout.filter(scoringCatalogue, revision, c.song(), c.songName()));
                    }
                    Path truthFile = c.truth().resolve(corpus);
                    var truth = BenchFiles.read(truthFile, BenchTimeline.Truth.class);
                    if (options.classic()) truth = truth.catalogueOrder(game);
                    song = new Song(truthFile, BenchMetrics.events(truth, truthDrums, true),
                            BenchAudio.mono(BenchAudio.read(c.reference().resolve(corpus))), useIndex, truth);
                    songs.put(c.song(), song);
                } else if (!song.truthFile.equals(c.truth().resolve(corpus))) {
                    throw new IllegalArgumentException("Inconsistent truth files for " + c.songName());
                }
                Song prepared = song;
                Path target = scratch.resolve(Integer.toString(tasks.size()));
                tasks.add(() -> capture(c.id(), c.profile(), c.variant(), scratch, out,
                        () -> scoreCase(c, corpus, target, prepared, options, scoringCatalogue, drums, hooks, reductions, warps, output)));
            }
            var rows = new ArrayList<BenchScorecard.Case>();
            try (var workers = Executors.newFixedThreadPool(options.jobs)) {
                for (var result : workers.invokeAll(tasks)) rows.add(result.get());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Benchmark interrupted");
            } catch (ExecutionException failure) {
                throw new IOException("Benchmark worker failed", failure.getCause());
            }
            var card = BenchScorecard.create(BenchFiles.hash(manifestFile), BenchFiles.hash(index), options.holdout,
                    options.audioRefine, goldInputs, List.copyOf(rows), options.target);
            BenchFiles.write(output.resolve("scorecard.json"), card);
            Files.writeString(output.resolve("scorecard.md"), BenchScorecard.markdown(card));
            BenchFiles.write(output.resolve("stage-reductions.json"), reductions);
            BenchFiles.write(output.resolve("tempo-warp.json"), warps);
            return rows.stream().anyMatch(row -> row.error() != null) ? 1 : 0;
        } finally {
            logger.setLevel(previousLevel);
            BenchFiles.deleteScratch(scratch);
        }
    }

    private static BenchScorecard.Case capture(String id, BenchMidi.Profile profile, BenchAudio.Variant variant,
                                               Path scratch, PrintStream out, Callable<BenchScorecard.Case> work)
            throws Exception {
        try {
            var result = work.call();
            out.printf("Scored %s%n", id);
            return result;
        } catch (InterruptedIOException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            String message = failure.toString().replace(scratch.toString(), "<work>");
            out.printf("Failed %s — %s%n", id, message);
            return new BenchScorecard.Case(id, profile, variant, null, null, null, null, null, null, null, message);
        }
    }

    private static BenchScorecard.Case scoreCase(BenchCorpus.Case c, Path root, Path output, Song song,
                                                 Options options, InstrumentCatalogue catalogue, BenchDrums drums,
                                                 BenchCli.Hooks hooks, Map<String, Map<String, Long>> reductions,
                                                 Map<String, Boolean> warps, Path results) throws IOException {
        long started = System.nanoTime();
        boolean ladder = c.ladder() != null;
        try {
            var truth = song.truth.stream().filter(note -> note.drum() == null).toList();
            var input = ladder ? BenchLadderRun.inputs(c, root, hooks) : null;
            var reconstruction = ladder && input.audioOnly()
                    ? BenchReconstruction.runAudioOnly(c.audio().resolve(root), song.index, output, options.audioRefine,
                    input.audioStems(), drums, hooks, options.rolePlan, truth, options.steadyTempo, options.target)
                    : BenchReconstruction.run(c.midi().resolve(root), c.audio().resolve(root), song.index,
                    output, options.audioRefine, null, ladder ? input.stems() : Map.of(),
                    drums, hooks, options.rolePlan, truth, options.steadyTempo, ladder ? input.leadPart() : null, ladder, options.target);
            warps.put(c.id(), reconstruction.warped());
            reductions.put(c.id(), reconstruction.reductions());
            var clock = ladder || options.classic() ? BenchClock.read(output) : null;
            var projectToTruth = ladder ? input.projectToTruth(clock) : clock == null ? null : clock.toSource();
            var truthToInput = ladder ? input.toInput() : DoubleUnaryOperator.identity();
            var projectTones = tones(reconstruction.events());
            var projectTonesWithLayers = tones(reconstruction.eventsWithLayers());
            double seconds = ladder ? c.ladder().frames() / 60.0 : Double.POSITIVE_INFINITY;
            var estimate = onTruthClock(projectTones, projectToTruth, seconds);
            var estimateWithLayers = onTruthClock(projectTonesWithLayers, projectToTruth, seconds);
            var truthDrums = song.truth.stream().filter(note -> note.drum() != null).toList();
            var estimateDrums = onTruthClock(reconstruction.events().stream().filter(note -> note.drum() != null)
                    .toList(), projectToTruth, seconds);
            var drumPairs = BenchMetrics.attacks(truthDrums, estimateDrums, .030);
            var drum = counts(truthDrums, estimateDrums, drumPairs);
            var leadTruth = BenchMetrics.melody(truth);
            var voiceScorer = new BenchVoice(catalogue);
            var dry = scoreTones(truth, leadTruth, estimate, song, reconstruction, voiceScorer, ladder, options, truthToInput);
            var layered = estimate.equals(estimateWithLayers) ? dry
                    : scoreTones(truth, leadTruth, estimateWithLayers, song, reconstruction, voiceScorer, ladder, options, truthToInput);
            Path audioFile = reconstruction.audio();
            if (options.classic() && (reconstruction.warped() || c.drift() != null)) {
                audioFile = output.resolve("truth-clock.wav");
                int frames = song.reference.length * 2 / S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
                ReferenceStretch.write(reconstruction.audio(), audioFile, frames,
                        time -> clock.toProject().applyAsDouble(truthToInput.applyAsDouble(time)));
            }
            float[] audio = BenchAudio.mono(BenchAudio.read(audioFile));
            if (c.drift() != null || (ladder || options.classic()) && audio.length != song.reference.length) {
                // Score the same undrifted interval in both modes, including
                // silence when playback ends early.
                audio = java.util.Arrays.copyOf(audio, song.reference.length);
            }
            var distance = BenchAudio.compare(song.reference, audio);
            var metrics = new TreeMap<String, Double>();
            if (c.grid() != null)
                BenchStructure.measure(output, c.grid(), song.nativeTruth, reconstruction.referenceUs() / 1e6, metrics);
            addTonalMetrics(metrics, dry, "");
            addTonalMetrics(metrics, layered, WITH_LAYERS);
            metrics.put("drumF1", drum.f1());
            if (drum.matched() > 0) {
                int sameClass = BenchMetrics.drumClassAttacks(truthDrums, estimateDrums, .030).size();
                long legacyCorrect = drumPairs.stream().filter(p -> truthDrums.get(p.truth()).legacyDrum()
                        == estimateDrums.get(p.estimate()).legacyDrum()).count();
                metrics.put("drumClassAccuracy", (double) sameClass / drum.matched());
                metrics.put("drumClassAccuracyLegacy", (double) legacyCorrect / drum.matched());
            }
            metrics.put("logMelDistance", distance.logMel());
            metrics.put("onsetCorrelation", distance.onsetCorrelation());
            release(truth, estimateWithLayers, layered.pairs(), audio)
                    .ifPresent(value -> metrics.put("releaseEnergy", value));
            BenchGold.Score lead = null;
            if (ladder) {
                lead = scoreLadderLead(metrics, "", output, truth, estimate, projectTones, c.ladder(), clock, input);
                scoreLadderLead(metrics, WITH_LAYERS, output, truth, estimateWithLayers, projectTonesWithLayers,
                        c.ladder(), clock, input);
                var trace = BenchLadderTrace.read(output, truth, projectTones, projectTonesWithLayers, c.ladder(),
                        input.leadPart(), projectToTruth, input.toTruth(), reconstruction.clock());
                BenchLadderRun.save(c, root, output, results, started, metrics, trace, reconstruction.reductions(), null);
            }
            return new BenchScorecard.Case(c.id(), c.profile(), c.variant(), metrics, dry.notes(), dry.offsets(),
                    dry.melody(), drum, dry.voices(), lead, null);
        } catch (IOException | IllegalArgumentException failure) {
            if (ladder && !(failure instanceof InterruptedIOException)) {
                String diagnostic = failure.toString().replace(output.toString(), "<work>");
                BenchLadderRun.save(c, root, output, results, started, null, null, null, diagnostic);
            }
            throw failure;
        } finally {
            if (ladder && options.keepWork() && Files.exists(output)) keep(output, results.resolve("cases")
                    .resolve(c.ladder().game().name()).resolve("%02x".formatted(c.song()))
                    .resolve(c.ladder().rung().label).resolve("work"));
            if (Files.exists(output)) BenchFiles.deleteScratch(output);
        }
    }

    // Diagnostic copy of a case's reconstruction work directory.
    private static void keep(Path work, Path destination) throws IOException {
        try (var files = Files.walk(work)) {
            for (var path : files.toList()) {
                Path target = destination.resolve(work.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static List<BenchMetrics.Event> tones(List<BenchMetrics.Event> events) {
        return events.stream().filter(note -> note.drum() == null).toList();
    }

    private static List<BenchMetrics.Event> onTruthClock(List<BenchMetrics.Event> events,
                                                       DoubleUnaryOperator clock, double seconds) {
        return clock == null ? events : events.stream().map(note -> BenchLadderTrace.map(note, clock))
                .filter(note -> note.start() < seconds).toList();
    }

    private static TonalScore scoreTones(List<BenchMetrics.Event> truth, List<BenchMetrics.Event> leadTruth,
                                        List<BenchMetrics.Event> estimate, Song song,
                                        BenchReconstruction.Result reconstruction, BenchVoice voiceScorer,
                                        boolean ladder, Options options, DoubleUnaryOperator truthToInput) throws IOException {
        var pairs = BenchMetrics.notes(truth, estimate, false);
        var notes = counts(truth, estimate, pairs);
        var offsets = counts(truth, estimate, BenchMetrics.notes(truth, estimate, true));
        var leadEstimate = BenchMetrics.melody(estimate);
        var melody = counts(leadTruth, leadEstimate, BenchMetrics.notes(leadTruth, leadEstimate, false));
        var voices = ladder ? BenchVoice.ladder(truth, estimate, pairs, song.nativeTruth.voices(), reconstruction.voices(),
                options.classic() && !options.holdout ? reconstruction.attribution() : null, truthToInput)
                : voiceScorer.score(truth, estimate, pairs, reconstruction.attribution(), options.holdout);
        return new TonalScore(notes, offsets, melody, voices, pairs);
    }

    private static void addTonalMetrics(Map<String, Double> metrics, TonalScore score, String suffix) {
        metrics.put("noteF1" + suffix, score.notes().f1());
        metrics.put("notePrecision" + suffix, score.notes().precision());
        metrics.put("noteRecall" + suffix, score.notes().recall());
        metrics.put("offsetF1" + suffix, score.offsets().f1());
        metrics.put("melodyF1" + suffix, score.melody().f1());
        var voices = score.voices();
        if (voices.distance() != null) metrics.put("voiceSpectralDistance" + suffix, voices.distance());
        if (voices.compared() != null && voices.matched() > 0)
            metrics.put("voiceSpectralCoverage" + suffix, (double) voices.compared() / voices.matched());
        if (voices.top1() != null && voices.matched() > 0) {
            metrics.put("voiceTop1" + suffix, (double) voices.top1() / voices.matched());
            metrics.put("voiceTop3" + suffix, (double) voices.top3() / voices.matched());
        }
    }

    private static BenchGold.Score scoreLadderLead(Map<String, Double> metrics, String suffix, Path output,
                                                   List<BenchMetrics.Event> truth, List<BenchMetrics.Event> estimate,
                                                   List<BenchMetrics.Event> projectTones, BenchLadder.Input ladder,
                                                   BenchClock clock, BenchLadderRun.Inputs input) throws IOException {
        String lane = ladder.leadLane();
        if (lane != null) metrics.put("leadFidelityF1" + suffix, BenchLeadFidelity.score(truth, estimate, lane).f1());
        var lead = BenchLadderTrace.leadScore(truth, estimate, lane, ladder.frames() / 60.0);
        var gap = BenchLadderTrace.leadGap(output, truth, projectTones, lane, clock, input.toInput());
        addLeadMetrics(metrics, suffix, lead, gap);
        return lead;
    }

    private static void addLeadMetrics(Map<String, Double> metrics, String suffix, BenchGold.Score lead, Double gap) {
        metrics.put("leadAttackF1" + suffix, lead.attacks().f1());
        if (lead.pitchAccuracy() != null) metrics.put("leadPitchAccuracy" + suffix, lead.pitchAccuracy());
        if (gap != null) metrics.put("leadGap" + suffix, gap);
    }

    private static java.util.OptionalDouble release(List<BenchMetrics.Event> truth, List<BenchMetrics.Event> estimate,
                                                     List<BenchMetrics.Pair> pairs, float[] audio) {
        var attacks = new HashMap<Integer, java.util.NavigableSet<Double>>();
        for (var note : estimate) attacks.computeIfAbsent(note.pitch(), ignored -> new java.util.TreeSet<>()).add(note.start());
        return pairs.stream().mapToDouble(pair -> {
            var note = truth.get(pair.truth());
            var heard = estimate.get(pair.estimate());
            Double next = attacks.get(note.pitch()).higher(heard.start());
            return BenchAudio.tailEnergy(audio, note, next == null ? Double.POSITIVE_INFINITY : next);
        }).filter(Double::isFinite).average();
    }

    private static BenchScorecard.Case scoreGold(BenchGold.Input gold, Path index, Path output, Options options,
                                                 BenchDrums drums, BenchCli.Hooks hooks,
                                                 Map<String, Map<String, Long>> reductions) throws IOException {
        try {
            var result = BenchReconstruction.run(gold.midi(), gold.reference(), index, output,
                    options.audioRefine, gold.seconds(), gold.stems(), drums, hooks, null, List.of(), options.steadyTempo, null, false, options.target);
            reductions.put(gold.id(), result.reductions());
            if (gold.regions().getLast().end() > result.referenceUs() / 1_000_000.0)
                throw new IllegalArgumentException("Gold reconstruction ends before the annotations");
            var metrics = new TreeMap<String, Double>();
            var score = scoreGoldLead(metrics, "", output, gold, result.attribution(), tones(result.events()));
            scoreGoldLead(metrics, WITH_LAYERS, output, gold, result.attribution(), tones(result.eventsWithLayers()));
            return new BenchScorecard.Case(gold.id(), null, null, metrics, null, null, null, null,
                    null, score, null);
        } finally {
            if (Files.exists(output)) BenchFiles.deleteScratch(output);
        }
    }

    private static BenchGold.Score scoreGoldLead(Map<String, Double> metrics, String suffix, Path output,
                                                 BenchGold.Input gold, BenchReconstruction.Attribution attribution,
                                                 List<BenchMetrics.Event> tones) throws IOException {
        var lead = gold.leadPart() == null ? BenchMetrics.melody(tones)
                : tones.stream().filter(note -> attribution.fromPart(note, gold.leadPart())).toList();
        var score = BenchGold.score(gold.regions(), lead);
        Double gap = BenchGold.hasLeadReleases(gold) ? BenchGold.leadGap(output, gold, melodyGates(tones)).leadGap() : null;
        addLeadMetrics(metrics, suffix, score, gap);
        return score;
    }

    static List<LeadGap.Interval> melodyGates(List<BenchMetrics.Event> events) {
        return events.stream().filter(note -> note.lane().equals("FM1"))
                .map(note -> new LeadGap.Interval(note.start(), note.end()))
                .sorted(java.util.Comparator.comparingDouble(LeadGap.Interval::start)).toList();
    }

    private static Map<String, String> goldInputs(BenchGold.Input input) throws IOException {
        var hashes = new TreeMap<String, String>();
        hashes.put("reference", BenchFiles.hash(input.reference()));
        hashes.put("midi", BenchFiles.hash(input.midi()));
        hashes.put("annotations", BenchFiles.hash(input.annotations()));
        hashes.put("scoring-options", BenchFiles.hash(
                BenchFiles.JSON.toJson(java.util.Arrays.asList(input.leadPart(), input.seconds()))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (var stem : input.stems().entrySet()) hashes.put("stem-" + stem.getKey(), BenchFiles.hash(stem.getValue()));
        return hashes;
    }

    private static BenchMetrics.F1 counts(List<?> truth, List<?> estimate, List<?> pairs) {
        return new BenchMetrics.F1(truth.size(), estimate.size(), pairs.size());
    }
}
