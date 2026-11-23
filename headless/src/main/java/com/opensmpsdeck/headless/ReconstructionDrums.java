package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.TargetProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Fits a kit from per-class audio evidence on the arrangement clock. */
final class ReconstructionDrums {
    enum Source { catalogue, sliced, auto, stock }
    enum KitMode {
        stock, custom;

        static KitMode forTarget(String value, TargetGame target) {
            if (value == null) return null;
            var kit = parse(value);
            if (target == TargetGame.S3K && kit == stock)
                throw new IllegalArgumentException("--drum-kit stock is available for s1 and s2");
            return kit;
        }

        private static KitMode parse(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("--drum-kit expects stock or custom");
            }
        }
    }

    private static final int ANALYSIS_RATE = DacAcoustics.ANALYSIS_RATE;
    private static final int SLICE_RATE = 2;
    private static final int HITS_PER_CLASS = 5;
    private static final double REUSE_PENALTY = .12;
    private static final double KIT_BONUS = .045;
    private static final int[] LEGACY_TUNING = {0, -2, 2, -4, 4};
    private static final int[] STEM_TUNING = {0, -1, 1, -2, 2, -3, 3, -4, 4, -5, 5, -6, 6};
    private static final double AUTO_THRESHOLD = .30;
    private static final Comparator<ReconstructionVoices.Key> CLASS_ORDER =
            Comparator.comparingInt(ReconstructionVoices.Key::partId)
                    .thenComparingInt(ReconstructionVoices.Key::drumPitch);

    record Features(List<Double> bands, AudioFingerprint.Summary sound, @Nullable Double pitchHz) {}
    record Candidate(String assetId, String variantId, String name, int rate, double distance, Features features,
                     @Nullable Double classPenalty, @Nullable DacAcoustics.Profile acoustics) {
        double score() { return distance + (classPenalty == null ? 0 : classPenalty); }
    }
    record Kit(String driver, String revision, @Nullable Integer songId, @Nullable String song) {}
    record Hit(double onset, double start, double length, int overlaps, @Nullable Boolean tailCensored) {}
    record Exclusion(String assetId, String name, int rate, DacAcoustics.SampleClass sampleClass, String reason) {}
    record Evidence(String reference, ReconstructionTimeMap.TimeBase timeBase, List<Hit> hits, @Nullable Features target,
                    List<Candidate> alternatives, @Nullable Candidate catalogue, Source selected, String reason,
                    @Nullable Kit preferredKit, @Nullable Boolean inPreferredKit,
                    @Nullable DrumClass drumClass, @Nullable Integer acousticProfileVersion,
                    @Nullable List<Exclusion> exclusions,
                    @Nullable String fallbackLane, @Nullable NoiseFit noise) {}
    /** The native PSG3 cymbal voicing scored with the DAC stem distance. */
    record NoiseFit(String voicing, double distance, Features features) {
        /** The short closed-hat envelope, rather than the longer open envelope. */
        boolean closed() { return "closed-hat".equals(voicing); }
        /** The hat's dual noise clock: a 14 kHz attack before the 7 kHz decay. */
        boolean dualClock() { return !"cymbal".equals(voicing); }
    }
    record Selection(@Nullable String variantId, int rate, @Nullable DacSample slice, Evidence evidence) {}

    private record Clip(Hit hit, float[] samples, Features features, AudioFingerprint fingerprint, double isolation) {}
    private record Rated(String assetId, String variantId, String name, int rate, Features features,
                         AudioFingerprint fingerprint, Map<Integer, AudioFingerprint> filtered,
                         DacAcoustics.Profile acoustics, DacAcoustics.AssetProfile asset) {}
    private record Fit(List<Clip> clips, List<Candidate> candidates, DacSample slice, boolean stem,
                       Candidate mixFallback, List<Exclusion> exclusions, boolean eligible, @Nullable NoiseFit noise) {}
    private record AssetRate(String id, int rate) {}

    private ReconstructionDrums() {}

    static Map<ReconstructionVoices.Key, Selection> match(ReconstructionStages.Score score,
            InstrumentCatalogue catalogue, List<ReconstructionAllocation.Entry> allocated, Path mix,
            Map<Integer, Path> stems) throws IOException {
        var keys = allocated.stream()
                .map(entry -> new ReconstructionVoices.Key(entry.note().part(), entry.note().pitch())).toList();
        var options = score.inputs().options(score.durationUs());
        return match(score, catalogue, keys, mix, stems, options, score.inputs().drumSource());
    }

    static Map<ReconstructionVoices.Key, Selection> matchLegacy(ReconstructionStages.Score score,
            InstrumentCatalogue catalogue, Path mix, ReconstructionCli.Options options) throws IOException {
        var drums = score.source().parts().stream().filter(MidiScore.Part::drums).map(MidiScore.Part::id).toList();
        var keys = score.notes().stream().filter(note -> drums.contains(note.part()))
                .map(note -> new ReconstructionVoices.Key(note.part(), note.pitch())).toList();
        return match(score, catalogue, keys, mix, options.stems(), options, null);
    }

    static boolean isTargetStock(DacSample sample, TargetGame target) {
        return sample != null && sample.origin() != null && sample.origin().ref() != null
                && sample.origin().ref().game().equals(target.id()) && sample.origin().ref().table().equals("dac")
                && (sample.origin().transforms() == null || sample.origin().transforms().isEmpty());
    }

    private static Map<ReconstructionVoices.Key, Selection> match(ReconstructionStages.Score score,
            InstrumentCatalogue catalogue, List<ReconstructionVoices.Key> keys, Path mix, Map<Integer, Path> stems,
            ReconstructionCli.Options options, Source source) throws IOException {
        if (keys.isEmpty()) return Map.of();
        var timeline = ReconstructionTimeMap.of(score);
        var clockNotes = score.notes().stream().map(timeline::projectNote).map(ReconstructionStages.Note::midi).toList();
        var drumParts = score.source().parts().stream().filter(MidiScore.Part::drums).map(MidiScore.Part::id).toList();
        var classes = new TreeMap<ReconstructionVoices.Key, List<MidiScore.Note>>(CLASS_ORDER);
        for (var key : keys) classes.putIfAbsent(key, new ArrayList<>());
        for (var note : clockNotes) {
            var hits = classes.get(new ReconstructionVoices.Key(note.partId(), note.pitch()));
            if (hits != null) hits.add(note);
        }

        var matched = new LinkedHashMap<ReconstructionVoices.Key, Fit>();
        boolean hasStem = classes.keySet().stream().anyMatch(key -> stems.containsKey(key.partId()));
        boolean needsMix = classes.keySet().stream().anyMatch(key -> !stems.containsKey(key.partId()));
        var profile = options.profile();
        var stock = options.stockDrums() ? ReconstructionStockDrums.load(profile.game()) : Map.<String, DacSample>of();
        var acoustics = stock.isEmpty() ? new DacAcoustics(catalogue, profile) : new DacAcoustics(profile);
        var filters = new HashSet<Integer>();
        classes.keySet().stream().filter(key -> stems.containsKey(key.partId()))
                .map(key -> cutoff(key.drumPitch())).filter(hz -> hz > 0).forEach(filters::add);
        var mixProbes = List.<Rated>of();
        var stemProbes = List.<Rated>of();
        var families = stock.isEmpty() ? families(catalogue, profile.game()) : new HashMap<String, DacFamily>();
        Map<String, DrumClass> labels;
        if (stock.isEmpty()) {
            if (needsMix) mixProbes = probes(catalogue, acoustics, LEGACY_TUNING, Set.of(), profile);
            if (hasStem) stemProbes = probes(catalogue, acoustics, STEM_TUNING, filters, profile);
            labels = hasStem ? labels(catalogue, families, acoustics) : Map.of();
        } else {
            var nativeProbes = new ArrayList<Rated>();
            var nativeLabels = new HashMap<String, DrumClass>();
            for (var sample : stock.values()) {
                var family = DacFamily.slot(profile.game(), sample.origin().ref().note());
                nativeLabels.put(sample.id(), DrumClass.valueOf(family.name()));
                var probe = probe(sample.id(), sample.id(), sample.name(), sample.rate(),
                        DacAcoustics.pcm(sample.pcm()), filters, profile, acoustics);
                if (probe != null) nativeProbes.add(probe);
            }
            mixProbes = nativeProbes;
            stemProbes = nativeProbes;
            labels = nativeLabels;
        }
        if (!timeline.warped()) {
            analyze(classes, clockNotes, drumParts, mix, stems, mixProbes, stemProbes, families, labels, matched, profile, options.stockDrums(), !options.fmOnly());
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
                analyze(classes, clockNotes, drumParts, warpedMix, warpedStems, mixProbes, stemProbes, families, labels, matched, profile, options.stockDrums(), !options.fmOnly());
            } finally {
                for (Path file : files) Files.deleteIfExists(file);
                Files.deleteIfExists(scratch);
            }
        }
        if (stock.isEmpty()) preserveUnisolatedMixChoices(score, catalogue, mix, stems, mixProbes, matched);
        var mode = options.stockDrums() ? Source.stock : source == null ? Source.catalogue : source;
        return selectKit(catalogue, stock, matched, hasStem && stock.isEmpty() ? kits(catalogue) : Map.of(),
                timeline.timeBase(), mode, !options.fmOnly(), profile);
    }

    private static void analyze(Map<ReconstructionVoices.Key, List<MidiScore.Note>> classes,
            List<MidiScore.Note> notes, List<Integer> drumParts, Path mix, Map<Integer, Path> stems,
            List<Rated> mixProbes, List<Rated> stemProbes, Map<String, DacFamily> families,
            Map<String, DrumClass> labels, Map<ReconstructionVoices.Key, Fit> matched, TargetProfile profile, boolean stock,
            boolean allowNoise) throws IOException {
        for (var entry : classes.entrySet()) {
            var key = entry.getKey();
            Path stem = stems.get(key.partId());
            var context = notes.stream().filter(note -> stem == null || drumParts.contains(note.partId())).toList();
            var hits = cleanHits(stem == null ? mix : stem, entry.getValue(), context, key.drumPitch(), stem != null);
            var isolated = hits.stream().filter(clip -> clip.hit().overlaps() == 0).toList();
            if (stem != null && isolated.size() >= 2) hits = isolated;
            int pitch = key.drumPitch();
            var allowed = new ArrayList<Rated>();
            var exclusions = new LinkedHashMap<List<String>, Exclusion>();
            for (var probe : stem == null ? mixProbes : stemProbes) {
                var excluded = exclusion(probe, pitch);
                if (excluded != null) exclusions.putIfAbsent(List.of(excluded.assetId(), excluded.reason()), excluded);
                else if (stem == null || cutoff(pitch) == 0 || probe.filtered().containsKey(cutoff(pitch))) allowed.add(probe);
            }
            var ranked = rank(hits, allowed, families, labels, pitch, stem != null);
            DacSample sample = stem == null || hits.isEmpty() || stock ? null : slice(hits.getFirst().samples(), key, profile);
            NoiseFit noise = allowNoise && stem != null && !hits.isEmpty() && ReconstructionCymbals.eligible(pitch)
                    ? noiseCandidate(hits, pitch, profile) : null;
            matched.put(key, new Fit(hits, ranked, sample, stem != null, null, List.copyOf(exclusions.values()),
                    !allowed.isEmpty(), noise));
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
                score.durationUs(), InstrumentCatalogue.Kind.FM_VOICE, Set.of(), score.inputs().targetGame());
        for (var key : uncertain) {
            var previous = legacy.get(key);
            if (previous == null || previous.assetId() == null) continue;
            var nativeProbe = probes.stream().filter(probe -> probe.assetId().equals(previous.assetId())
                    && probe.rate() == previous.sampleRate() && exclusion(probe, key.drumPitch()) == null)
                    .findFirst().orElse(null);
            if (nativeProbe == null) continue;
            var fit = fitted.get(key);
            var fallback = new Candidate(previous.assetId(), previous.variantId(), previous.name(),
                    previous.sampleRate(), distance(fit.clips(), nativeProbe, key.drumPitch(), false),
                    nativeProbe.features(), null, nativeProbe.acoustics());
            fitted.put(key, new Fit(fit.clips(), fit.candidates(), fit.slice(), fit.stem(), fallback, fit.exclusions(),
                    fit.eligible(), fit.noise()));
        }
    }

    private static List<Clip> cleanHits(Path file, List<MidiScore.Note> hits, List<MidiScore.Note> context, int pitch,
                                        boolean stem) throws IOException {
        long duration = ReferenceAudio.durationMicros(file);
        long[] attacks = context.stream().mapToLong(MidiScore.Note::startUs).sorted().toArray();
        var candidates = new ArrayList<Clip>();
        int highPassHz = cutoff(pitch);
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
            Clip clip = isolate(pcm, nominal, onset, from, Math.max(0, overlaps), stem);
            if (clip != null) candidates.add(clip);
        }
        return candidates.stream().sorted(Comparator.comparingDouble(Clip::isolation).reversed()
                .thenComparingDouble(clip -> clip.hit().onset())).limit(HITS_PER_CLASS).toList();
    }

    private static int cutoff(int pitch) {
        if (ReconstructionCymbals.eligible(pitch)) return 950;
        return pitch == 37 || pitch == 39 ? 430 : 0;
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

    private static Clip isolate(float[] pcm, int nominal, double onset, double time, int overlaps, boolean stem) {
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
            // Transcriptions can miss an attack. A second, comparable rise
            // out of a decay belongs to the next hit, not this one's envelope.
            if (stem && i >= peak + 7 && rms[i - 2] < .45 * rms[peak]
                    && rms[i] - rms[i - 2] > .40 * rms[peak] && rms[i] > .65 * rms[peak]) {
                stop = i - 1;
                break;
            }
            quiet = rms[i] < gate ? quiet + 1 : 0;
            if (quiet == 6) {
                stop = i + 2 - quiet;
                break;
            }
        }
        int end = Math.min(pcm.length, Math.max(start + 2 * hop, stop * hop));
        float[] signal = Arrays.copyOfRange(pcm, start, end);
        boolean tailCensored = stem && stop > attack && rms[stop - 1] > .15 * rms[peak];
        var fingerprint = AudioFingerprint.extract(signal, ANALYSIS_RATE);
        double contrast = Math.log1p(rise / Math.max(.001, floor));
        return new Clip(new Hit(onset, time + start / (double) ANALYSIS_RATE,
                signal.length / (double) ANALYSIS_RATE, overlaps, tailCensored ? true : null), signal, features(fingerprint, signal), fingerprint,
                contrast - 1.1 * overlaps);
    }

    private static List<Rated> probes(InstrumentCatalogue catalogue, DacAcoustics acoustics, int[] tuning, Set<Integer> filters, TargetProfile target)
            throws IOException {
        var result = new ArrayList<Rated>();
        var seen = new HashSet<AssetRate>();
        for (var variant : catalogue.variants()) {
            var asset = catalogue.asset(variant.assetId());
            if (asset.kind() != InstrumentCatalogue.Kind.DAC_SAMPLE) continue;
            int nativeRate = variant.recipe().rate();
            double nativeHz = target.dacSampleRateHz(nativeRate);
            float[] pcm = DacAcoustics.pcm(asset.bytes());
            for (int semitones : tuning) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                double targetHz = nativeHz * Math.pow(2, semitones / 12.0);
                int rate = nearestRate(targetHz, target);
                if (!seen.add(new AssetRate(asset.id(), rate))) continue;
                var probe = probe(asset.id(), variant.id(), variant.name(), rate, pcm, filters, target, acoustics);
                if (probe != null) result.add(probe);
            }
        }
        return result;
    }

    /**
     * The closest native PSG3 voicing, scored against the stem hits like a DAC
     * candidate: the short dual-clock closed hat, the longer dual-clock open
     * hat, or the 7 kHz cymbal. Any voicing may serve any hat or cymbal.
     */
    private static NoiseFit noiseCandidate(List<Clip> clips, int pitch, TargetProfile target) {
        NoiseFit best = null;
        for (var voicing : List.of("closed-hat", "open-hat", "cymbal")) {
            boolean closed = voicing.equals("closed-hat");
            float[] rendered = ReconstructionCymbals.renderHit(closed, !voicing.equals("cymbal"), target);
            if (rendered == null) return null;
            float[] audio = resample(rendered, 48_000, ANALYSIS_RATE);
            try {
                var fingerprint = AudioFingerprint.extract(audio, ANALYSIS_RATE);
                var filtered = new HashMap<Integer, AudioFingerprint>();
                int cutoff = cutoff(pitch);
                if (cutoff > 0) {
                    float[] band = audio.clone();
                    highPass(band, cutoff);
                    filtered.put(cutoff, AudioFingerprint.extract(band, ANALYSIS_RATE));
                }
                var probe = new Rated("psg3-" + voicing, null, voicing, 0, features(fingerprint, audio), fingerprint,
                        filtered, null, null);
                var fit = new NoiseFit(voicing, distance(clips, probe, pitch, true), probe.features());
                if (best == null || fit.distance() < best.distance()) best = fit;
            } catch (IllegalArgumentException invalid) {
                if (!"Fingerprint audio is silent".equals(invalid.getMessage())) throw invalid;
            }
        }
        return best;
    }

    private static Rated probe(String id, String variant, String name, int rate, float[] pcm, Set<Integer> filters,
                               TargetProfile target, DacAcoustics acoustics) {
        float[] audio = resample(pcm, target.dacSampleRateHz(rate), ANALYSIS_RATE);
        var profile = acoustics.profile(id, rate, audio);
        if (audio.length < ANALYSIS_RATE / 100) audio = Arrays.copyOf(audio, ANALYSIS_RATE / 100);
        try {
            var fingerprint = AudioFingerprint.extract(audio, ANALYSIS_RATE);
            var filtered = new HashMap<Integer, AudioFingerprint>();
            for (int hz : filters) {
                float[] band = audio.clone();
                highPass(band, hz);
                try {
                    filtered.put(hz, AudioFingerprint.extract(band, ANALYSIS_RATE));
                } catch (IllegalArgumentException invalid) {
                    if (!"Fingerprint audio is silent".equals(invalid.getMessage())) throw invalid;
                }
            }
            return new Rated(id, variant, name, rate, features(fingerprint, audio), fingerprint, filtered,
                    profile, acoustics.asset(id));
        } catch (IllegalArgumentException invalid) {
            if (!"Fingerprint audio is silent".equals(invalid.getMessage())) throw invalid;
            return null;
        }
    }

    private static Exclusion exclusion(Rated probe, int pitch) {
        var asset = probe.asset();
        var rateClass = probe.acoustics().sampleClass();
        if (asset == null) {
            // Native drums are eligible by definition. Keep the existing cymbal fallback.
            return ReconstructionCymbals.eligible(pitch) && rateClass != DacAcoustics.SampleClass.NOISE_LIKE
                    ? new Exclusion(probe.assetId(), probe.name(), probe.rate(), rateClass, "hat-or-cymbal-requires-noise")
                    : null;
        }
        var sampleClass = asset.sampleClass().pitched() ? asset.sampleClass() : rateClass;
        String reason = asset.excludeFromDrumFit() ? "excludeFromDrumFit" : exclusionReason(sampleClass, pitch);
        return reason == null ? null : new Exclusion(asset.assetId(), asset.name(), probe.rate(), sampleClass, reason);
    }

    private static String exclusionReason(DacAcoustics.SampleClass sampleClass, int pitch) {
        return switch (sampleClass) {
            case SPEECH_LIKE -> "speech-like";
            case PITCHED_TONAL -> "pitched-tonal";
            case UNCLASSIFIED -> "insufficient-acoustic-evidence";
            default -> ReconstructionCymbals.eligible(pitch) && sampleClass != DacAcoustics.SampleClass.NOISE_LIKE
                    ? "hat-or-cymbal-requires-noise" : null;
        };
    }

    private static int nearestRate(double hz, TargetProfile target) {
        int best = 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int rate = 0; rate <= 255; rate++) {
            double error = Math.abs(Math.log(target.dacSampleRateHz(rate) / hz));
            if (error < distance) {
                best = rate;
                distance = error;
            }
        }
        return best;
    }

    private static List<Candidate> rank(List<Clip> clips, List<Rated> probes, Map<String, DacFamily> families,
                                        Map<String, DrumClass> labels, int pitch, boolean stem) {
        if (clips.isEmpty()) return List.of();
        var bestByAsset = new HashMap<String, Candidate>();
        for (var probe : probes) {
            String asset = probe.assetId();
            double penalty = stem || probe.asset() == null
                    ? classPenalty(labels.get(asset), pitch) : familyPenalty(families.get(asset), pitch);
            var candidate = new Candidate(asset, probe.variantId(), probe.name(), probe.rate(),
                    distance(clips, probe, pitch, stem), probe.features(), penalty, probe.acoustics());
            bestByAsset.merge(asset, candidate, (a, b) -> a.distance() <= b.distance() ? a : b);
        }
        return bestByAsset.values().stream().sorted(Comparator.comparingDouble(Candidate::score)
                .thenComparing(Candidate::variantId)).toList();
    }

    private static Map<String, DacFamily> families(InstrumentCatalogue catalogue, TargetGame target) {
        var result = new HashMap<String, DacFamily>();
        var conflicts = new java.util.HashSet<String>();
        for (var variant : catalogue.variants()) {
            if (catalogue.asset(variant.assetId()).kind() != InstrumentCatalogue.Kind.DAC_SAMPLE) continue;
            for (var usage : variant.occurrences()) {
                var family = DacFamily.occurrence(target, usage);
                if (family == DacFamily.OTHER) continue;
                var previous = result.putIfAbsent(variant.assetId(), family);
                if (previous != null && previous != family) conflicts.add(variant.assetId());
            }
        }
        conflicts.forEach(result::remove);
        return result;
    }

    private static double familyPenalty(DacFamily actual, int pitch) {
        boolean cymbal = switch (pitch) {
            case 42, 44, 46, 49, 51, 52, 53, 55, 57, 59 -> true;
            default -> false;
        };
        // The catalogue has few explicit hat labels. Give an unlabelled
        // cymbal candidate a small preference over a documented kick or snare.
        if (cymbal) return actual == null ? 0 : .08;
        var expected = switch (pitch) {
            case 35, 36 -> DacFamily.KICK;
            case 38, 39, 40 -> DacFamily.SNARE;
            case 41, 43, 45, 47, 48, 50 -> DacFamily.OTHER;
            default -> null;
        };
        if (expected == null || expected == actual || expected == DacFamily.OTHER && actual == DacFamily.TOM) return 0;
        if (actual == null) return expected == DacFamily.OTHER ? 0 : .08;
        return .19;
    }

    enum DrumClass { KICK, SNARE, CLAP, STICK, TOM, HAT, CYMBAL, OTHER }

    private static Map<String, DrumClass> labels(InstrumentCatalogue catalogue, Map<String, DacFamily> families,
                                                  DacAcoustics acoustics) {
        var labels = new HashMap<String, DrumClass>();
        for (var variant : catalogue.variants()) {
            var asset = catalogue.asset(variant.assetId());
            if (asset.kind() != InstrumentCatalogue.Kind.DAC_SAMPLE) continue;
            String description = (asset.name() + " " + variant.name() + " " + String.join(" ", variant.roles()))
                    .toLowerCase(Locale.ROOT);
            DrumClass tagged = namedClass(description);
            if (tagged != DrumClass.OTHER) labels.putIfAbsent(asset.id(), tagged);
        }
        for (var variant : catalogue.variants()) {
            var profile = acoustics.asset(variant.assetId());
            if (profile != null && profile.playedRoles().size() == 1)
                labels.putIfAbsent(profile.assetId(), DrumClass.valueOf(profile.playedRoles().getFirst()));
        }
        families.forEach((id, family) -> labels.putIfAbsent(id,
                DrumClass.valueOf(family.name())));
        return labels;
    }

    private static DrumClass namedClass(String name) {
        if (name.contains("kick") || name.contains("bass drum")) return DrumClass.KICK;
        if (name.contains("clap")) return DrumClass.CLAP;
        if (name.contains("snare")) return DrumClass.SNARE;
        if (name.contains("side stick") || name.contains("sidestick") || name.contains("rimshot")) return DrumClass.STICK;
        if (name.contains("timpani") || name.matches(".*\\btom(?:s|[- ](?:low|mid|high))?\\b.*")) return DrumClass.TOM;
        if (name.contains("hi-hat") || name.contains("hihat") || name.contains("hi hat")) return DrumClass.HAT;
        if (name.contains("cymbal") || name.contains("crash") || name.contains("ride")) return DrumClass.CYMBAL;
        return DrumClass.OTHER;
    }

    private static DrumClass drumClass(int pitch) {
        return switch (pitch) {
            case 35, 36 -> DrumClass.KICK;
            case 38, 40 -> DrumClass.SNARE;
            case 39 -> DrumClass.CLAP;
            case 37 -> DrumClass.STICK;
            case 41, 43, 45, 47, 48, 50 -> DrumClass.TOM;
            case 42, 44, 46 -> DrumClass.HAT;
            default -> ReconstructionCymbals.eligible(pitch) ? DrumClass.CYMBAL : DrumClass.OTHER;
        };
    }

    private static double classPenalty(DrumClass actual, int pitch) {
        DrumClass expected = drumClass(pitch);
        if (expected == actual) return 0;
        if (actual == null) return expected == DrumClass.KICK || expected == DrumClass.SNARE ? .16 : .035;
        if (expected == DrumClass.CLAP && actual == DrumClass.SNARE
                || expected == DrumClass.STICK && actual == DrumClass.SNARE
                || expected == DrumClass.HAT && actual == DrumClass.CYMBAL
                || expected == DrumClass.CYMBAL && actual == DrumClass.HAT) return .09;
        return expected == DrumClass.OTHER ? .10 : .30;
    }

    private static double distance(List<Clip> clips, Rated probe, int pitch, boolean stem) {
        if (clips.isEmpty()) return 1;
        boolean pitched = pitch == 35 || pitch == 36 || pitch == 41 || pitch == 43 || pitch == 45
                || pitch == 47 || pitch == 48 || pitch == 50;
        int cutoff = stem ? cutoff(pitch) : 0;
        var comparable = probe.filtered().getOrDefault(cutoff, probe.fingerprint());
        double lowBody = 0;
        if (cutoff > 0) {
            // Comparing the projected bands isolates the intended transient.
            // Still charge for the low ringing body the DAC would actually emit.
            var bands = probe.fingerprint().bands();
            for (int i = 0; i < bands.size() && 36 * Math.pow(2, i / 3.0) < cutoff / 2.0; i++)
                lowBody += bands.get(i);
        }
        double[] distances = new double[clips.size()];
        for (int i = 0; i < distances.length; i++) {
            var clip = clips.get(i);
            var shape = clip.fingerprint().distance(comparable);
            var source = clip.features();
            var target = comparable.summary();
            double length = relative(source.sound().effectiveDurationSeconds(), target.effectiveDurationSeconds(), .02);
            double centroid = relative(source.sound().centroidHz(), target.centroidHz(), 200);
            double noise = Math.abs(source.sound().noisiness() - target.noisiness());
            double pitchError = 0;
            if (pitched && source.pitchHz() != null)
                pitchError = probe.features().pitchHz() == null ? 1 :
                        Math.min(1, Math.abs(Math.log(source.pitchHz() / probe.features().pitchHz())) / Math.log(2));
            if (stem) {
                double attack = relative(source.sound().attackSeconds(), target.attackSeconds(), .025);
                double decay = relative(source.sound().decaySeconds(), target.decaySeconds(), .035);
                double envelope = shape.envelope();
                if (Boolean.TRUE.equals(clip.hit().tailCensored())) {
                    // The observed decay is a lower bound when the next hit
                    // or the reference window interrupts a still-audible tail.
                    if (target.decaySeconds() >= source.sound().decaySeconds()) decay = 0;
                    if (target.effectiveDurationSeconds() >= source.sound().effectiveDurationSeconds()) length = 0;
                    envelope = .70 * prefixEnvelopeDistance(clip.fingerprint(), comparable) + .15 * (attack + decay);
                }
                distances[i] = .37 * shape.spectral() + .20 * envelope + .08 * length + .06 * centroid
                        + .07 * noise + .16 * pitchError + .08 * attack + .14 * decay + .23 * lowBody;
            } else {
                distances[i] = .48 * shape.spectral() + .25 * shape.envelope() + .1 * length + .07 * centroid
                        + .10 * noise + .15 * pitchError;
            }
        }
        return median(distances);
    }

    private static double prefixEnvelopeDistance(AudioFingerprint source, AudioFingerprint candidate) {
        var reference = source.envelope();
        var playback = candidate.envelope();
        double difference = 0;
        for (int i = 0; i < reference.size(); i++)
            difference += Math.abs(Math.sqrt(reference.get(i)) - (i < playback.size() ? Math.sqrt(playback.get(i)) : 0));
        return difference / reference.size();
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

    private static Map<ReconstructionVoices.Key, Selection> selectKit(InstrumentCatalogue catalogue, Map<String, DacSample> stock,
            Map<ReconstructionVoices.Key, Fit> fitted, Map<Kit, Set<String>> kits,
            ReconstructionTimeMap.TimeBase clock, Source mode, boolean allowNoise, TargetProfile profile) {
        Kit preferred = preferredKit(fitted, kits);
        var kitAssets = preferred == null ? Set.<String>of() : kits.get(preferred);
        var usage = new HashMap<String, Integer>();
        var sizes = new HashMap<AssetRate, Integer>();
        var result = new LinkedHashMap<ReconstructionVoices.Key, Selection>();
        // Give classes with a clear acoustic preference first refusal. Close
        // alternatives are preferred when a sample already serves another class.
        var ordered = fitted.entrySet().stream().sorted(Comparator.comparingDouble(
                (Map.Entry<ReconstructionVoices.Key, Fit> e) -> margin(e.getValue().candidates())).reversed()
                .thenComparing(Map.Entry::getKey, CLASS_ORDER)).toList();
        int bytes = 0;
        for (var entry : ordered) {
            Fit fit = entry.getValue();
            var chosen = fit.mixFallback() != null ? fit.mixFallback() : fit.candidates().stream().filter(candidate -> sizes.containsKey(key(candidate))
                            || sizes.size() < DacSample.MAX_SAMPLES)
                    .min(Comparator.comparingDouble(c -> c.score()
                            - (fit.stem() && kitAssets.contains(c.assetId()) ? KIT_BONUS : 0)
                            + REUSE_PENALTY * usage.getOrDefault(c.assetId(), 0)))
                    .orElse(null);
            var sliced = fit.slice();
            var noise = allowNoise && mode != Source.sliced && fit.mixFallback() == null ? fit.noise() : null;
            boolean useSlice = sliced != null && (mode == Source.sliced || mode == Source.auto
                    && (chosen == null || chosen.distance() > AUTO_THRESHOLD)
                    && (noise == null || noise.distance() > AUTO_THRESHOLD));
            var exclusions = fit.exclusions();
            if (useSlice && mode == Source.auto) {
                var sampleClass = DacAcoustics.profile(sliced, profile).sampleClass();
                String exclusion = exclusionReason(sampleClass, entry.getKey().drumPitch());
                if (exclusion != null) {
                    useSlice = false;
                    var rejected = new ArrayList<>(exclusions);
                    rejected.add(new Exclusion(sliced.id(), sliced.name(), sliced.rate(), sampleClass, exclusion));
                    exclusions = List.copyOf(rejected);
                }
            }
            // The native PSG3 voicing competes on the same stem distance as the DAC candidates.
            boolean noiseRoute = !useSlice && noise != null && (chosen == null || noise.distance() < chosen.score());
            var catalogueChoice = chosen;
            if (noiseRoute) chosen = null;
            AssetRate key = useSlice ? new AssetRate(sliced.id(), sliced.rate()) : chosen == null ? null : key(chosen);
            var nativeSample = chosen == null ? null : stock.get(chosen.assetId());
            int size = useSlice ? sliced.pcm().length : chosen == null ? 0
                    : nativeSample != null ? nativeSample.pcm().length : catalogue.asset(chosen.assetId()).bytes().length;
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
            boolean noEligibleDac = !useSlice && !noiseRoute && chosen == null && !fit.eligible();
            String fallbackLane = noiseRoute || noEligibleDac && allowNoise ? "PSG3" : null;
            String reason = noiseRoute ? catalogueChoice == null ? "PSG3 noise; no eligible DAC sample" : "PSG3 noise closer than DAC"
                    : noEligibleDac ? "no acoustically eligible DAC sample"
                    : useSlice ? mode == Source.auto ? "catalogue distance above threshold" : "requested"
                    : !fit.stem() && mode != Source.catalogue ? "no drum stem supplied"
                    : chosen == null ? "no eligible fit"
                    : chosen == fit.mixFallback() ? "legacy vote: fewer than two isolated hits in the mix" : "catalogue fit";
            if (mode == Source.stock && chosen != null) reason = "stock kit fit";
            var evidence = new Evidence(fit.stem() ? "stem" : "mix", clock, fit.clips().stream().map(Clip::hit).toList(),
                    summarize(fit.clips()), fit.candidates().stream().limit(5).toList(), noiseRoute ? catalogueChoice : chosen,
                    useSlice ? Source.sliced : mode == Source.stock ? Source.stock : Source.catalogue, reason, fit.stem() ? preferred : null,
                    !fit.stem() || preferred == null || chosen == null ? null : kitAssets.contains(chosen.assetId()),
                    drumClass(entry.getKey().drumPitch()), DacAcoustics.VERSION, exclusions, fallbackLane, fit.noise());
            var selectedSample = useSlice ? sliced : chosen == null ? null : stock.get(chosen.assetId());
            result.put(entry.getKey(), new Selection(chosen == null || mode == Source.stock ? null : chosen.variantId(),
                    useSlice ? sliced.rate() : chosen == null ? 0 : chosen.rate(), selectedSample, evidence));
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

    private static double margin(List<Candidate> candidates) {
        if (candidates.size() < 2) return candidates.isEmpty() ? -1 : 1;
        return candidates.get(1).score() - candidates.getFirst().score();
    }

    private static Map<Kit, Set<String>> kits(InstrumentCatalogue catalogue) {
        var bySong = new LinkedHashMap<Kit, Set<String>>();
        var byBank = new LinkedHashMap<Kit, Set<String>>();
        for (var variant : catalogue.variants()) {
            if (catalogue.asset(variant.assetId()).kind() != InstrumentCatalogue.Kind.DAC_SAMPLE) continue;
            for (var occurrence : variant.occurrences()) {
                var bank = new Kit(occurrence.driver(), occurrence.revision(), null, null);
                byBank.computeIfAbsent(bank, ignored -> new HashSet<>()).add(variant.assetId());
                if (!occurrence.played() || occurrence.songId() == null && occurrence.song() == null) continue;
                var song = new Kit(occurrence.driver(), occurrence.revision(), occurrence.songId(), occurrence.song());
                bySong.computeIfAbsent(song, ignored -> new HashSet<>()).add(variant.assetId());
            }
        }
        // Authored banks without playback provenance still form kits.
        byBank.forEach((bank, assets) -> {
            if (bySong.keySet().stream().noneMatch(song -> song.driver().equals(bank.driver())
                    && song.revision().equals(bank.revision()))) bySong.put(bank, assets);
        });
        return bySong;
    }

    private static Kit preferredKit(Map<ReconstructionVoices.Key, Fit> fitted, Map<Kit, Set<String>> kits) {
        var eligible = fitted.values().stream().filter(fit -> fit.stem() && fit.clips().size() >= 2)
                .filter(fit -> !fit.candidates().isEmpty()).toList();
        if (eligible.size() < 2) return null;
        double best = KIT_BONUS;
        Kit preferred = null;
        for (var entry : kits.entrySet()) {
            double benefit = 0;
            for (var fit : eligible) {
                double limit = fit.candidates().getFirst().score() + KIT_BONUS;
                for (var candidate : fit.candidates()) {
                    if (candidate.score() >= limit) break;
                    if (!entry.getValue().contains(candidate.assetId())) continue;
                    benefit += limit - candidate.score();
                    break;
                }
            }
            if (benefit > best + 1e-9) {
                preferred = entry.getKey();
                best = benefit;
            }
        }
        return preferred;
    }

    static double median(double[] values) {
        Arrays.sort(values);
        return (values[values.length / 2] + values[(values.length - 1) / 2]) / 2;
    }

    private static DacSample slice(float[] signal, ReconstructionVoices.Key key, TargetProfile profile) {
        float[] audio = resample(signal, ANALYSIS_RATE, profile.dacSampleRateHz(SLICE_RATE));
        double peak = 1e-6;
        for (float value : audio) peak = Math.max(peak, Math.abs(value));
        int fade = (int) (profile.dacSampleRateHz(SLICE_RATE) * .003);
        var bytes = new byte[audio.length];
        for (int i = 0; i < audio.length; i++) {
            double edge = Math.min(1, Math.min(i, audio.length - 1 - i) / (double) fade);
            bytes[i] = (byte) (128 + Math.round(113 * audio[i] / peak * edge));
        }
        return DacSample.of("stem-" + InstrumentCatalogue.digest(bytes), "Stem drum " + key.drumPitch(), SLICE_RATE, bytes);
    }

    static float[] resample(float[] signal, double inputRate, double outputRate) {
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
