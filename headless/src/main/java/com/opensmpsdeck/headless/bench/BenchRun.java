package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchClock;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.LeadGap;

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
import java.util.logging.Level;
import java.util.logging.Logger;

final class BenchRun {
    record Options(boolean quick, boolean holdout, boolean audioRefine, int jobs, Path gold, boolean goldOnly,
                   BenchRolePlan.Mode rolePlan, String steadyTempo) {}
    private record Song(Path truthFile, List<BenchMetrics.Event> truth, float[] reference, Path index,
                        BenchTimeline.Truth nativeTruth) {}

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
            var files = new ArrayList<>(List.of(c.truth(), c.reference(), c.audio(), c.midi()));
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
            var catalogue = InstrumentCatalogue.open(index);
            String revision = "sha256:" + manifest.romSha256();
            var game = cases.stream().filter(c -> c.ladder() != null).findFirst().map(c -> c.ladder().game())
                    .orElse(com.opensmpsdeck.headless.BenchCartridge.Game.s3k);
            var drums = new BenchDrums(catalogue, revision, game);
            if (cases.stream().anyMatch(c -> c.ladder() != null && c.ladder().game() != game))
                throw new IllegalArgumentException("Each ladder manifest must contain a single game");
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
                        BenchFiles.write(useIndex, BenchHoldout.filter(catalogue, revision, c.song(), c.songName()));
                    }
                    Path truthFile = c.truth().resolve(corpus);
                    var truth = BenchFiles.read(truthFile, BenchTimeline.Truth.class);
                    song = new Song(truthFile, BenchMetrics.events(truth, drums, true),
                            BenchAudio.mono(BenchAudio.read(c.reference().resolve(corpus))), useIndex, truth);
                    songs.put(c.song(), song);
                } else if (!song.truthFile.equals(c.truth().resolve(corpus))) {
                    throw new IllegalArgumentException("Inconsistent truth files for " + c.songName());
                }
                Song prepared = song;
                Path target = scratch.resolve(Integer.toString(tasks.size()));
                tasks.add(() -> capture(c.id(), c.profile(), c.variant(), scratch, out,
                        () -> scoreCase(c, corpus, target, prepared, options, catalogue, drums, hooks, reductions, warps, output)));
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
                    options.audioRefine, goldInputs, List.copyOf(rows));
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
            var reconstruction = BenchReconstruction.run(c.midi().resolve(root), c.audio().resolve(root), song.index,
                    output, options.audioRefine, null, ladder ? input.stems() : Map.of(),
                    drums, hooks, options.rolePlan, truth, options.steadyTempo, ladder ? input.leadPart() : null, ladder);
            warps.put(c.id(), reconstruction.warped());
            reductions.put(c.id(), reconstruction.reductions());
            var clock = ladder ? BenchClock.read(output) : null;
            var projectToTruth = ladder ? input.projectToTruth(clock) : null;
            var events = ladder ? reconstruction.events().stream().map(n -> BenchLadderTrace.map(n, projectToTruth))
                    .filter(n -> n.start() < c.ladder().frames() / 60.0).toList() : reconstruction.events();
            var estimate = events.stream().filter(note -> note.drum() == null).toList();
            var truthDrums = song.truth.stream().filter(note -> note.drum() != null).toList();
            var estimateDrums = events.stream().filter(note -> note.drum() != null).toList();
            var pairs = BenchMetrics.notes(truth, estimate, false);
            var notes = counts(truth, estimate, pairs);
            var offsets = counts(truth, estimate, BenchMetrics.notes(truth, estimate, true));
            var leadTruth = BenchMetrics.melody(truth);
            var leadEstimate = BenchMetrics.melody(estimate);
            var melody = counts(leadTruth, leadEstimate, BenchMetrics.notes(leadTruth, leadEstimate, false));
            var drumPairs = BenchMetrics.attacks(truthDrums, estimateDrums, .030);
            var drum = counts(truthDrums, estimateDrums, drumPairs);
            long drumCorrect = drumPairs.stream().filter(p -> truthDrums.get(p.truth()).drum()
                    == estimateDrums.get(p.estimate()).drum()).count();
            var voices = ladder ? BenchVoice.ladder(truth, estimate, pairs, song.nativeTruth.voices(), reconstruction.voices())
                    : new BenchVoice(catalogue).score(truth, estimate, pairs, reconstruction.attribution(), options.holdout);
            float[] audio = BenchAudio.mono(BenchAudio.read(reconstruction.audio()));
            if (c.drift() != null || ladder && audio.length != song.reference.length) {
                // Score the same undrifted interval in both modes, including
                // silence when playback ends early.
                audio = java.util.Arrays.copyOf(audio, song.reference.length);
            }
            var distance = BenchAudio.compare(song.reference, audio);
            var metrics = new TreeMap<String, Double>();
            if (c.grid() != null)
                BenchStructure.measure(output, c.grid(), song.nativeTruth, reconstruction.referenceUs() / 1e6, metrics);
            metrics.put("noteF1", notes.f1());
            metrics.put("notePrecision", notes.precision());
            metrics.put("noteRecall", notes.recall());
            metrics.put("offsetF1", offsets.f1());
            metrics.put("melodyF1", melody.f1());
            metrics.put("drumF1", drum.f1());
            if (drum.matched() > 0) metrics.put("drumClassAccuracy", (double) drumCorrect / drum.matched());
            if (options.holdout || ladder) {
                if (voices.distance() != null) metrics.put("voiceSpectralDistance", voices.distance());
                if (voices.matched() > 0) metrics.put("voiceSpectralCoverage", (double) voices.compared() / voices.matched());
            } else if (voices.matched() > 0) {
                metrics.put("voiceTop1", (double) voices.top1() / voices.matched());
                metrics.put("voiceTop3", (double) voices.top3() / voices.matched());
            }
            metrics.put("logMelDistance", distance.logMel());
            metrics.put("onsetCorrelation", distance.onsetCorrelation());
            release(truth, estimate, pairs, audio).ifPresent(value -> metrics.put("releaseEnergy", value));
            BenchGold.Score lead = null;
            if (ladder) {
                double seconds = c.ladder().frames() / 60.0;
                var projectTones = reconstruction.events().stream().filter(note -> note.drum() == null).toList();
                lead = BenchLadderTrace.leadScore(truth, estimate, c.ladder().leadLane(), seconds);
                metrics.put("leadAttackF1", lead.attacks().f1());
                if (lead.pitchAccuracy() != null) metrics.put("leadPitchAccuracy", lead.pitchAccuracy());
                var gap = BenchLadderTrace.leadGap(output, truth, projectTones, c.ladder().leadLane(), clock, input.toInput());
                if (gap != null) metrics.put("leadGap", gap);
                var trace = BenchLadderTrace.read(output, truth, projectTones, c.ladder(), input.leadPart(),
                        projectToTruth, input.toTruth());
                BenchLadderRun.save(c, root, output, results, started, metrics, trace, reconstruction.reductions(), null);
            }
            return new BenchScorecard.Case(c.id(), c.profile(), c.variant(), metrics, notes, offsets, melody, drum,
                    voices, lead, null);
        } catch (IOException | IllegalArgumentException failure) {
            if (ladder && !(failure instanceof InterruptedIOException)) {
                String diagnostic = failure.toString().replace(output.toString(), "<work>");
                BenchLadderRun.save(c, root, output, results, started, null, null, null, diagnostic);
            }
            throw failure;
        } finally {
            if (Files.exists(output)) BenchFiles.deleteScratch(output);
        }
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
                    options.audioRefine, gold.seconds(), gold.stems(), drums, hooks, null, List.of(), options.steadyTempo);
            reductions.put(gold.id(), result.reductions());
            if (gold.regions().getLast().end() > result.referenceUs() / 1_000_000.0)
                throw new IllegalArgumentException("Gold reconstruction ends before the annotations");
            var tones = result.events().stream().filter(note -> note.drum() == null).toList();
            var lead = gold.leadPart() == null ? BenchMetrics.melody(tones)
                    : tones.stream().filter(note -> result.attribution().fromPart(note, gold.leadPart())).toList();
            var score = BenchGold.score(gold.regions(), lead);
            var metrics = new TreeMap<String, Double>();
            metrics.put("leadAttackF1", score.attacks().f1());
            if (BenchGold.hasLeadReleases(gold))
                metrics.put("leadGap", BenchGold.leadGap(output, gold, melodyGates(tones)).leadGap());
            if (score.pitchAccuracy() != null) metrics.put("leadPitchAccuracy", score.pitchAccuracy());
            return new BenchScorecard.Case(gold.id(), null, null, metrics, null, null, null, null,
                    null, score, null);
        } finally {
            if (Files.exists(output)) BenchFiles.deleteScratch(output);
        }
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
