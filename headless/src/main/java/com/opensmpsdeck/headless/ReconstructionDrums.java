package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DacSample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Fits a kit from per-class audio evidence on the arrangement clock. */
final class ReconstructionDrums {
    enum Source { catalogue, sliced, auto }

    private static final int ANALYSIS_RATE = 24_000;
    private static final int SLICE_RATE = 2;
    private static final int HITS_PER_CLASS = 5;
    private static final double REUSE_PENALTY = .12;
    private static final double AUTO_THRESHOLD = .30;
    private static final Comparator<ReconstructionVoices.Key> CLASS_ORDER =
            Comparator.comparingInt(ReconstructionVoices.Key::partId)
                    .thenComparingInt(ReconstructionVoices.Key::drumPitch);

    record Features(List<Double> bands, AudioFingerprint.Summary sound, @Nullable Double pitchHz) {}
    record Candidate(String assetId, String variantId, String name, int rate, double distance, Features features) {}
    record Hit(double onset, double start, double length, int overlaps) {}
    record Evidence(String reference, ReconstructionTimeMap.TimeBase timeBase, List<Hit> hits, @Nullable Features target,
                    List<Candidate> alternatives, @Nullable Candidate catalogue, Source selected, String reason) {}
    record Selection(@Nullable String variantId, int rate, @Nullable DacSample slice, Evidence evidence) {}

    private record Clip(Hit hit, float[] samples, Features features, AudioFingerprint fingerprint, double isolation) {}
    private record Rated(InstrumentCatalogue.Variant variant, int rate, Features features, AudioFingerprint fingerprint) {}
    private record Fit(List<Clip> clips, List<Candidate> candidates, DacSample slice, boolean stem,
                       Candidate mixFallback) {}
    private record AssetRate(String id, int rate) {}

    private ReconstructionDrums() {}

    static Map<ReconstructionVoices.Key, Selection> match(ReconstructionStages.Score score,
            InstrumentCatalogue catalogue, List<ReconstructionAllocation.Entry> allocated, Path mix,
            Map<Integer, Path> stems) throws IOException {
        if (allocated.isEmpty()) return Map.of();
        var timeline = ReconstructionTimeMap.of(score);
        var clockNotes = score.notes().stream().map(timeline::projectNote).map(ReconstructionStages.Note::midi).toList();
        var drumParts = score.source().parts().stream().filter(MidiScore.Part::drums).map(MidiScore.Part::id).toList();
        var classes = new TreeMap<ReconstructionVoices.Key, List<MidiScore.Note>>(CLASS_ORDER);
        for (var entry : allocated) {
            var key = new ReconstructionVoices.Key(entry.note().part(), entry.note().pitch());
            classes.putIfAbsent(key, new ArrayList<>());
        }
        for (var note : clockNotes) {
            var hits = classes.get(new ReconstructionVoices.Key(note.partId(), note.pitch()));
            if (hits != null) hits.add(note);
        }

        var matched = new LinkedHashMap<ReconstructionVoices.Key, Fit>();
        var variants = probes(catalogue);
        var families = families(catalogue);
        if (!timeline.warped()) {
            analyze(classes, clockNotes, drumParts, mix, stems, variants, families, matched);
        } else {
            Path scratch = Files.createTempDirectory("drum-reference-");
            var files = new ArrayList<Path>();
            try {
                Path warpedMix = scratch.resolve("mix.wav");
                files.add(warpedMix);
                var alignment = ReferenceStretch.writeAndCaptureAlignment(mix, warpedMix, timeline.frames(),
                        timeline.sourceDuration(), timeline::source);
                var warpedStems = new HashMap<Integer, Path>();
                for (var part : drumParts) {
                    Path stem = stems.get(part);
                    if (stem == null) continue;
                    Path warped = stem.equals(mix) ? warpedMix : scratch.resolve("part-" + part + ".wav");
                    if (!stem.equals(mix)) {
                        files.add(warped);
                        alignment.writeStem(stem, warped);
                    }
                    warpedStems.put(part, warped);
                }
                analyze(classes, clockNotes, drumParts, warpedMix, warpedStems, variants, families, matched);
            } finally {
                for (Path file : files) Files.deleteIfExists(file);
                Files.deleteIfExists(scratch);
            }
        }
        preserveUnisolatedMixChoices(score, catalogue, mix, stems, variants, matched);
        var source = score.inputs().drumSource();
        return selectKit(catalogue, matched, families, timeline.timeBase(), source == null ? Source.catalogue : source);
    }

    private static void analyze(Map<ReconstructionVoices.Key, List<MidiScore.Note>> classes,
            List<MidiScore.Note> notes, List<Integer> drumParts, Path mix, Map<Integer, Path> stems,
            List<Rated> probes, Map<String, S3kDacFamily> families,
            Map<ReconstructionVoices.Key, Fit> matched) throws IOException {
        for (var entry : classes.entrySet()) {
            var key = entry.getKey();
            Path stem = stems.get(key.partId());
            var context = notes.stream().filter(note -> stem == null || drumParts.contains(note.partId())).toList();
            var hits = cleanHits(stem == null ? mix : stem, entry.getValue(), context, key.drumPitch());
            var ranked = rank(hits, probes, families, key.drumPitch());
            DacSample sample = stem == null || hits.isEmpty() ? null : slice(hits.getFirst().samples(), key);
            matched.put(key, new Fit(hits, ranked, sample, stem != null, null));
        }
    }

    private static void preserveUnisolatedMixChoices(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
            Path mix, Map<Integer, Path> stems, List<Rated> probes, Map<ReconstructionVoices.Key, Fit> fitted)
            throws IOException {
        var uncertain = fitted.entrySet().stream().filter(entry -> !entry.getValue().stem()
                && entry.getValue().clips().stream().filter(clip -> clip.hit().overlaps() == 0).limit(2).count() < 2)
                .map(Map.Entry::getKey).toList();
        if (uncertain.isEmpty()) return;
        // Mixed tonal attacks can dominate the drum features. Where the mix
        // supplies too few isolated exemplars, retain the legacy rank vote.
        var notes = score.notes().stream().map(ReconstructionStages.Note::midi)
                .filter(note -> uncertain.contains(new ReconstructionVoices.Key(note.partId(), note.pitch()))).toList();
        var legacy = ReconstructionVoices.select(catalogue, mix, stems, score.source(), notes,
                score.durationUs(), InstrumentCatalogue.Kind.FM_VOICE);
        for (var key : uncertain) {
            var previous = legacy.get(key);
            if (previous == null || previous.assetId() == null) continue;
            var nativeProbe = probes.stream().filter(probe -> probe.variant().assetId().equals(previous.assetId())
                    && probe.rate() == previous.sampleRate()).findFirst().orElse(null);
            if (nativeProbe == null) continue;
            var fit = fitted.get(key);
            var fallback = new Candidate(previous.assetId(), previous.variantId(), previous.name(),
                    previous.sampleRate(), distance(fit.clips(), nativeProbe, key.drumPitch()), nativeProbe.features());
            fitted.put(key, new Fit(fit.clips(), fit.candidates(), fit.slice(), fit.stem(), fallback));
        }
    }

    private static List<Clip> cleanHits(Path file, List<MidiScore.Note> hits, List<MidiScore.Note> context, int pitch)
            throws IOException {
        long duration = ReferenceAudio.durationMicros(file);
        long[] attacks = context.stream().mapToLong(MidiScore.Note::startUs).sorted().toArray();
        var candidates = new ArrayList<Clip>();
        double highPassHz = switch (pitch) {
            case 42, 44, 46, 49, 51, 52, 53, 55, 57, 59 -> 950;
            case 37, 39 -> 430;
            default -> 0;
        };
        // Read a bounded, time-distributed subset. Include quiet pre-roll to measure bleed and
        // permit small MIDI/transcription offsets without clipping the leading transient.
        int stride = Math.max(1, Math.ceilDiv(hits.size(), 64));
        for (int index = 0; index < hits.size(); index += stride) {
            double onset = hits.get(index).startUs() / 1e6;
            double from = Math.max(0, onset - .035);
            int next = lowerBound(attacks, hits.get(index).startUs() + 25_000);
            double nextAttack = next == attacks.length ? Double.POSITIVE_INFINITY : attacks[next] / 1e6;
            double end = Math.min(Math.min(duration / 1e6, onset + .28), Math.max(onset + .045, nextAttack));
            if (end - from < .045) continue;
            int overlaps = count(attacks, hits.get(index).startUs() - 25_000, hits.get(index).startUs() + 100_000) - 1;
            var raw = ReferenceAudio.read(file, from, end - from);
            float[] pcm = resample(raw.samples(), raw.sampleRate(), ANALYSIS_RATE);
            int nominal = (int) Math.round((onset - from) * ANALYSIS_RATE);
            // Cymbals and sticks often land on the same frame as a kick. Find
            // and describe their own upper-frequency transient instead of the
            // stronger low-frequency attack on the shared drum stem.
            if (highPassHz > 0) highPass(pcm, highPassHz);
            Clip clip = isolate(pcm, nominal, onset, from, Math.max(0, overlaps));
            if (clip != null) candidates.add(clip);
        }
        return candidates.stream().sorted(Comparator.comparingDouble(Clip::isolation).reversed()
                .thenComparingDouble(clip -> clip.hit().onset())).limit(HITS_PER_CLASS).toList();
    }

    private static void highPass(float[] signal, double hz) {
        double retain = Math.exp(-2 * Math.PI * hz / ANALYSIS_RATE);
        double previous = signal[0], first = 0, second = 0;
        for (int i = 0; i < signal.length; i++) {
            double value = signal[i];
            double next = retain * (first + value - previous);
            second = retain * (second + next - first);
            signal[i] = (float) second;
            first = next;
            previous = value;
        }
    }

    private static int count(long[] sorted, long first, long end) {
        return lowerBound(sorted, end) - lowerBound(sorted, first);
    }

    private static int lowerBound(long[] sorted, long value) {
        int first = 0, end = sorted.length;
        while (first < end) {
            int middle = (first + end) >>> 1;
            if (sorted[middle] < value) first = middle + 1;
            else end = middle;
        }
        return first;
    }

    private static Clip isolate(float[] pcm, int nominal, double onset, double time, int overlaps) {
        int hop = ANALYSIS_RATE / 200;
        var rms = new double[pcm.length / hop];
        for (int i = 0; i < rms.length; i++) {
            double energy = 0;
            for (int j = i * hop; j < (i + 1) * hop; j++) energy += pcm[j] * pcm[j];
            rms[i] = Math.sqrt(energy / hop);
        }
        int beat = nominal / hop;
        double floor = beat < 4 ? 0 : (rms[0] + rms[1]) / 2;
        int first = Math.max(0, beat - 4);
        int peak = first;
        double rise = 0;
        for (int i = first; i < Math.min(beat + 6, rms.length); i++) {
            double candidate = rms[i] - (i < 2 ? 0 : rms[i - 2]);
            if (candidate > rise) {
                rise = candidate;
                peak = i;
            }
        }
        if (rms[peak] < 1e-5 || rise < 1e-5) return null;
        int attack = peak;
        while (attack > first && rms[attack - 1] > Math.max(floor * 1.5, rms[peak] * .12)) attack--;
        int start = attack * hop;
        // As in the legacy slicer, backtrack to a nearby crossing.
        while (start > 0 && start > attack * hop - hop && pcm[start - 1] * pcm[start] > 0) start--;
        double gate = Math.max(floor * 1.5, rms[peak] * .018);
        int quiet = 0;
        int stop = rms.length;
        for (int i = peak + 1; i < rms.length; i++) {
            quiet = rms[i] < gate ? quiet + 1 : 0;
            if (quiet == 6) {
                stop = i + 2 - quiet;
                break;
            }
        }
        int end = Math.min(pcm.length, Math.max(start + 2 * hop, stop * hop));
        float[] signal = Arrays.copyOfRange(pcm, start, end);
        var fingerprint = AudioFingerprint.extract(signal, ANALYSIS_RATE);
        double contrast = Math.log1p(rise / Math.max(.001, floor));
        return new Clip(new Hit(onset, time + start / (double) ANALYSIS_RATE,
                signal.length / (double) ANALYSIS_RATE, overlaps), signal, features(fingerprint, signal), fingerprint,
                contrast - 1.1 * overlaps);
    }

    private static List<Rated> probes(InstrumentCatalogue catalogue) throws IOException {
        var result = new ArrayList<Rated>();
        var seen = new java.util.HashSet<AssetRate>();
        for (var variant : catalogue.variants()) {
            var asset = catalogue.asset(variant.assetId());
            if (asset.kind() != InstrumentCatalogue.Kind.DAC_SAMPLE) continue;
            int nativeRate = variant.recipe().rate();
            double nativeHz = DacSample.sampleRateHz(nativeRate);
            byte[] bytes = asset.bytes();
            float[] pcm = new float[bytes.length];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (Byte.toUnsignedInt(bytes[i]) - 128) / 128f;
            for (int semitones : new int[] {0, -2, 2, -4, 4}) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                double targetHz = nativeHz * Math.pow(2, semitones / 12.0);
                int rate = nearestRate(targetHz);
                if (!seen.add(new AssetRate(asset.id(), rate))) continue;
                float[] audio = resample(pcm, DacSample.sampleRateHz(rate), ANALYSIS_RATE);
                if (audio.length < ANALYSIS_RATE / 100) audio = Arrays.copyOf(audio, ANALYSIS_RATE / 100);
                try {
                    var fingerprint = AudioFingerprint.extract(audio, ANALYSIS_RATE);
                    result.add(new Rated(variant, rate, features(fingerprint, audio), fingerprint));
                } catch (IllegalArgumentException invalid) {
                    if (!"Fingerprint audio is silent".equals(invalid.getMessage())) throw invalid;
                }
            }
        }
        return result;
    }

    private static int nearestRate(double hz) {
        int best = 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int rate = 0; rate <= 255; rate++) {
            double error = Math.abs(Math.log(DacSample.sampleRateHz(rate) / hz));
            if (error < distance) {
                best = rate;
                distance = error;
            }
        }
        return best;
    }

    private static List<Candidate> rank(List<Clip> clips, List<Rated> probes, Map<String, S3kDacFamily> families,
                                        int pitch) {
        if (clips.isEmpty()) return List.of();
        var bestByAsset = new HashMap<String, Candidate>();
        for (var probe : probes) {
            var variant = probe.variant();
            var candidate = new Candidate(variant.assetId(), variant.id(), variant.name(), probe.rate(),
                    distance(clips, probe, pitch), probe.features());
            bestByAsset.merge(candidate.assetId(), candidate, (a, b) -> a.distance() <= b.distance() ? a : b);
        }
        return bestByAsset.values().stream().sorted(Comparator.comparingDouble(
                        (Candidate c) -> c.distance() + familyPenalty(families.get(c.assetId()), pitch))
                .thenComparing(Candidate::variantId)).toList();
    }

    private static Map<String, S3kDacFamily> families(InstrumentCatalogue catalogue) {
        var result = new HashMap<String, S3kDacFamily>();
        var conflicts = new java.util.HashSet<String>();
        for (var variant : catalogue.variants()) {
            if (catalogue.asset(variant.assetId()).kind() != InstrumentCatalogue.Kind.DAC_SAMPLE) continue;
            for (var usage : variant.occurrences()) {
                if (!CatalogueMatcher.LOCKED_ON_S3K.equals(usage.revision()) || usage.slot() == null
                        || !"dac".equals(usage.table())) continue;
                var family = S3kDacFamily.slot(usage.slot());
                if (family == S3kDacFamily.OTHER) continue;
                var previous = result.putIfAbsent(variant.assetId(), family);
                if (previous != null && previous != family) conflicts.add(variant.assetId());
            }
        }
        conflicts.forEach(result::remove);
        return result;
    }

    private static double familyPenalty(S3kDacFamily actual, int pitch) {
        boolean cymbal = switch (pitch) {
            case 42, 44, 46, 49, 51, 52, 53, 55, 57, 59 -> true;
            default -> false;
        };
        // The catalogue has few explicit hat labels. Give an unlabelled
        // cymbal candidate a small preference over a documented kick or snare.
        if (cymbal) return actual == null ? 0 : .08;
        var expected = switch (pitch) {
            case 35, 36 -> S3kDacFamily.KICK;
            case 38, 39, 40 -> S3kDacFamily.SNARE;
            case 41, 43, 45, 47, 48, 50 -> S3kDacFamily.OTHER;
            default -> null;
        };
        if (expected == null || expected == actual) return 0;
        if (actual == null) return expected == S3kDacFamily.OTHER ? 0 : .08;
        return .19;
    }

    private static double distance(List<Clip> clips, Rated probe, int pitch) {
        if (clips.isEmpty()) return 1;
        boolean pitched = pitch == 35 || pitch == 36 || pitch == 41 || pitch == 43 || pitch == 45
                || pitch == 47 || pitch == 48 || pitch == 50;
        double[] distances = new double[clips.size()];
        for (int i = 0; i < distances.length; i++) {
            var clip = clips.get(i);
            var shape = clip.fingerprint().distance(probe.fingerprint());
            var source = clip.features();
            var target = probe.features();
            double length = relative(source.sound().effectiveDurationSeconds(), target.sound().effectiveDurationSeconds(), .02);
            double centroid = relative(source.sound().centroidHz(), target.sound().centroidHz(), 200);
            double noise = Math.abs(source.sound().noisiness() - target.sound().noisiness());
            double pitchError = 0;
            if (pitched && source.pitchHz() != null)
                pitchError = target.pitchHz() == null ? 1 :
                        Math.min(1, Math.abs(Math.log(source.pitchHz() / target.pitchHz())) / Math.log(2));
            distances[i] = .48 * shape.spectral() + .25 * shape.envelope() + .1 * length + .07 * centroid
                    + .10 * noise + .15 * pitchError;
        }
        return median(distances);
    }

    private static double relative(double a, double b, double floor) {
        return Math.abs(a - b) / (floor + Math.max(a, b));
    }

    private static Features features(AudioFingerprint fingerprint, float[] signal) {
        return new Features(fingerprint.bands(), fingerprint.summary(), pitch(signal));
    }

    private static Double pitch(float[] signal) {
        // Low-pass and decimate for the low resonances of kicks and toms. A
        // periodicity maximum must be strong and local to qualify as pitch.
        int stride = 6;
        double rate = ANALYSIS_RATE / (double) stride;
        int count = Math.min(signal.length / stride, (int) (rate * .10));
        double[] low = new double[count];
        double level = 0, dc = 0;
        for (int i = 0; i < count * stride; i++) {
            level += .13 * (signal[i] - level);
            dc += .007 * (level - dc);
            low[i / stride] = level - dc;
        }
        int first = (int) (rate / 400), last = Math.min(count / 3, (int) (rate / 36));
        double[] correlation = new double[last + 2];
        for (int lag = first; lag <= last; lag++) {
            double xy = 0, xx = 0, yy = 0;
            for (int i = (int) (rate * .008) + lag; i < count; i++) {
                double x = low[i], y = low[i - lag];
                xy += x * y;
                xx += x * x;
                yy += y * y;
            }
            correlation[lag] = xx > 1e-10 && yy > 1e-10 ? xy / Math.sqrt(xx * yy) : 0;
        }
        double maximum = Arrays.stream(correlation).max().orElse(0);
        for (int lag = first + 1; lag < last; lag++) {
            double r = correlation[lag];
            if (r >= Math.max(.62, maximum * .87) && r > correlation[lag - 1] && r >= correlation[lag + 1])
                return rate / lag;
        }
        return null;
    }

    private static Map<ReconstructionVoices.Key, Selection> selectKit(InstrumentCatalogue catalogue,
            Map<ReconstructionVoices.Key, Fit> fitted, Map<String, S3kDacFamily> families,
            ReconstructionTimeMap.TimeBase clock, Source mode) {
        var usage = new HashMap<String, Integer>();
        var sizes = new HashMap<AssetRate, Integer>();
        var result = new LinkedHashMap<ReconstructionVoices.Key, Selection>();
        // Give classes with a clear acoustic preference first refusal. Close
        // alternatives are preferred when a sample already serves another class.
        var ordered = fitted.entrySet().stream().sorted(Comparator.comparingDouble(
                (Map.Entry<ReconstructionVoices.Key, Fit> e) -> margin(e.getValue().candidates(), families, e.getKey().drumPitch())).reversed()
                .thenComparing(Map.Entry::getKey, CLASS_ORDER)).toList();
        int bytes = 0;
        for (var entry : ordered) {
            Fit fit = entry.getValue();
            int pitch = entry.getKey().drumPitch();
            var chosen = fit.mixFallback() != null ? fit.mixFallback() : fit.candidates().stream().filter(candidate -> sizes.containsKey(key(candidate))
                            || sizes.size() < DacSample.MAX_SAMPLES)
                    .min(Comparator.comparingDouble(c -> c.distance() + familyPenalty(families.get(c.assetId()), pitch)
                            + REUSE_PENALTY * usage.getOrDefault(c.assetId(), 0)))
                    .orElse(null);
            var sliced = fit.slice();
            boolean useSlice = sliced != null && (mode == Source.sliced || mode == Source.auto
                    && (chosen == null || chosen.distance() > AUTO_THRESHOLD));
            AssetRate key = useSlice ? new AssetRate(sliced.id(), sliced.rate()) : chosen == null ? null : key(chosen);
            int size = useSlice ? sliced.pcm().length : chosen == null ? 0 : catalogue.asset(chosen.assetId()).bytes().length;
            if (key != null && !sizes.containsKey(key) && (sizes.size() >= DacSample.MAX_SAMPLES
                    || bytes + size > DacSample.MAX_TOTAL_PCM_BYTES)) {
                chosen = fit.candidates().stream().filter(c -> sizes.containsKey(key(c))).findFirst().orElse(null);
                useSlice = false;
                key = chosen == null ? null : key(chosen);
            }
            if (key != null && !sizes.containsKey(key)) {
                sizes.put(key, size);
                bytes += size;
            }
            if (!useSlice && chosen != null) usage.merge(chosen.assetId(), 1, Integer::sum);
            String reason = useSlice ? mode == Source.auto ? "catalogue distance above threshold" : "requested"
                    : !fit.stem() && mode != Source.catalogue ? "no drum stem supplied"
                    : chosen == null ? "no eligible fit"
                    : chosen == fit.mixFallback() ? "legacy vote: fewer than two isolated hits in the mix" : "catalogue fit";
            var evidence = new Evidence(fit.stem() ? "stem" : "mix", clock, fit.clips().stream().map(Clip::hit).toList(),
                    summarize(fit.clips()), fit.candidates().stream().limit(5).toList(), chosen,
                    useSlice ? Source.sliced : Source.catalogue, reason);
            result.put(entry.getKey(), new Selection(chosen == null ? null : chosen.variantId(),
                    useSlice ? sliced.rate() : chosen == null ? 0 : chosen.rate(), useSlice ? sliced : null, evidence));
        }
        return result;
    }

    private static Features summarize(List<Clip> clips) {
        if (clips.isEmpty()) return null;
        var sounds = clips.stream().map(c -> c.features().sound()).toList();
        var summary = new AudioFingerprint.Summary(median(sounds.stream().mapToDouble(AudioFingerprint.Summary::centroidHz).toArray()),
                median(sounds.stream().mapToDouble(AudioFingerprint.Summary::flatness).toArray()),
                median(sounds.stream().mapToDouble(AudioFingerprint.Summary::brightness).toArray()),
                median(sounds.stream().mapToDouble(AudioFingerprint.Summary::noisiness).toArray()),
                median(sounds.stream().mapToDouble(AudioFingerprint.Summary::attackSeconds).toArray()),
                median(sounds.stream().mapToDouble(AudioFingerprint.Summary::decaySeconds).toArray()),
                median(sounds.stream().mapToDouble(AudioFingerprint.Summary::effectiveDurationSeconds).toArray()));
        var bands = new ArrayList<Double>();
        for (int i = 0; i < clips.getFirst().features().bands().size(); i++) {
            int band = i;
            bands.add(median(clips.stream().mapToDouble(clip -> clip.features().bands().get(band)).toArray()));
        }
        double[] pitches = clips.stream().map(c -> c.features().pitchHz()).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray();
        return new Features(bands, summary, pitches.length == 0 ? null : median(pitches));
    }

    private static AssetRate key(Candidate candidate) { return new AssetRate(candidate.assetId(), candidate.rate()); }

    private static double margin(List<Candidate> candidates, Map<String, S3kDacFamily> families, int pitch) {
        if (candidates.size() < 2) return candidates.isEmpty() ? -1 : 1;
        var first = candidates.getFirst();
        var second = candidates.get(1);
        return second.distance() + familyPenalty(families.get(second.assetId()), pitch)
                - first.distance() - familyPenalty(families.get(first.assetId()), pitch);
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return (values[values.length / 2] + values[(values.length - 1) / 2]) / 2;
    }

    private static DacSample slice(float[] signal, ReconstructionVoices.Key key) {
        float[] audio = resample(signal, ANALYSIS_RATE, DacSample.sampleRateHz(SLICE_RATE));
        double peak = 1e-6;
        for (float value : audio) peak = Math.max(peak, Math.abs(value));
        int fade = (int) (DacSample.sampleRateHz(SLICE_RATE) * .003);
        var bytes = new byte[audio.length];
        for (int i = 0; i < audio.length; i++) {
            double edge = Math.min(1, Math.min(i, audio.length - 1 - i) / (double) fade);
            bytes[i] = (byte) (128 + Math.round(113 * audio[i] / peak * edge));
        }
        return DacSample.of("stem-" + InstrumentCatalogue.digest(bytes), "Stem drum " + key.drumPitch(), SLICE_RATE, bytes);
    }

    private static float[] resample(float[] signal, double inputRate, double outputRate) {
        double ratio = inputRate / outputRate;
        int length = Math.clamp((int) Math.ceil(signal.length / ratio), 1, (int) (outputRate * .5));
        var output = new float[length];
        // Windowed sinc removes content above the destination Nyquist on downsampling.
        double cutoff = Math.min(1, 1 / ratio) * .94;
        for (int i = 0; i < length; i++) {
            double position = i * ratio;
            int nearest = (int) position;
            double sum = 0, weight = 0;
            for (int j = nearest - 12; j <= nearest + 12; j++) {
                double x = position - j;
                if (Math.abs(x) >= 12) continue;
                double tap = (Math.abs(x) < 1e-9 ? cutoff : Math.sin(Math.PI * cutoff * x) / (Math.PI * x))
                        * (.5 + .5 * Math.cos(Math.PI * x / 12));
                weight += tap;
                if (j >= 0 && j < signal.length) sum += signal[j] * tap;
            }
            output[i] = (float) (sum / weight);
        }
        return output;
    }
}
