package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReconstructionVoices {
    private static final int SHORTLIST_SIZE = 8;
    private static final long MICROS_PER_SECOND = 1_000_000;

    static final long SECTION_US = 16 * MICROS_PER_SECOND;
    private static final long INDEPENDENT_US = 500_000;
    // Standalone kick slots from skdisasm's S3K DAC equates. Hybrids such as dKickHey
    // and dOddSnareKick can have strong bass but add a voice or another percussion hit.
    private static final Set<Integer> S3K_KICK_SLOTS = Set.of(0x86, 0x9D, 0xA4, 0xA7, 0xA8, 0xBD);

    record Key(int partId, Integer drumPitch, Long sectionStartUs) {
        Key(int partId, Integer drumPitch) {
            this(partId, drumPitch, null);
        }

        static Key section(MidiScore.Note note) {
            return new Key(note.partId(), null, note.startUs() / SECTION_US * SECTION_US);
        }
    }

    record Check(long startUs, long durationUs, int midi, int gateTicks, int overlappingNotes,
                 double defaultDistance, double candidateDistance) {}

    record SectionTrial(Key key, String defaultVariant, Choice candidate, List<Check> validation,
                        boolean accepted) {}

    record SectionPlan(Map<Key, Choice> choices, List<SectionTrial> trials) {}

    record Probe(long startUs, long durationUs, int midi, int gateTicks, int overlappingNotes,
                 String source, String status, int compared, List<Hit> matches) {}

    record Hit(String assetId, String variantId, String name, double distance) {}

    record Candidate(String assetId, String variantId, String name, int windows,
                     double score, double meanDistance) {}

    record Choice(String assetId, String variantId, String name, Integer sampleRate, String status,
                  List<Probe> probes, List<Candidate> shortlist, String rankingPolicy,
                  com.opensmpsdeck.project.DacSample slice, ReconstructionDrums.Evidence drums) {
        Choice(String assetId, String variantId, String name, Integer sampleRate, String status,
               List<Probe> probes, List<Candidate> shortlist, String rankingPolicy) {
            this(assetId, variantId, name, sampleRate, status, probes, shortlist, rankingPolicy, null, null);
        }

        Choice report() {
            return slice == null ? this : new Choice(assetId, variantId, name, sampleRate, status, probes,
                    shortlist, rankingPolicy, null, drums);
        }

        Choice(String assetId, String variantId, String name, Integer sampleRate, String status,
               List<Probe> probes, List<Candidate> shortlist) {
            this(assetId, variantId, name, sampleRate, status, probes, shortlist, null);
        }
    }

    private record Window(MidiScore.Note note, long durationUs, int overlappingNotes) {}

    private static final class Timeline {
        private final long[] starts;
        private final long[] ends;

        Timeline(List<MidiScore.Note> notes) {
            starts = notes.stream().mapToLong(MidiScore.Note::startUs).sorted().toArray();
            ends = notes.stream().mapToLong(MidiScore.Note::endUs).sorted().toArray();
        }

        int overlapping(long start, long end) {
            return Math.max(0, firstAtLeast(starts, end) - firstAtLeast(ends, start + 1) - 1);
        }

        private static int firstAtLeast(long[] times, long target) {
            int lower = 0;
            int upper = times.length;
            while (lower < upper) {
                int middle = (lower + upper) >>> 1;
                if (times[middle] < target) lower = middle + 1;
                else upper = middle;
            }
            return lower;
        }
    }

    private static final class Evidence {
        private Hit best;
        private int windows;
        private double score;
        private double distance;

        void add(Hit hit, int rank, double weight) {
            if (best == null || hit.distance() < best.distance()
                    || (hit.distance() == best.distance() && hit.variantId().compareTo(best.variantId()) < 0))
                best = hit;
            windows++;
            score += weight / (rank + 1);
            distance += hit.distance();
        }

        Candidate summarize() {
            return new Candidate(best.assetId(), best.variantId(), best.name(), windows, score, distance / windows);
        }
    }

    private ReconstructionVoices() {}

    static Map<Key, Choice> select(InstrumentCatalogue catalogue, Path mix, Map<Integer, Path> stems, MidiScore sourceScore,
                                   List<MidiScore.Note> notes, long limitUs, Kind toneKind) throws IOException {
        return select(catalogue, mix, stems, sourceScore, notes, limitUs, toneKind, Set.of(), TargetGame.S3K);
    }

    static Map<Key, Choice> select(InstrumentCatalogue catalogue, Path mix, Map<Integer, Path> stems, MidiScore sourceScore,
                                   List<MidiScore.Note> notes, long limitUs, Kind toneKind,
                                   Set<Integer> addedParts, TargetGame target) throws IOException {
        Set<String> sustainedPsg = addedParts.isEmpty() ? Set.of() : catalogue.variants().stream()
                .filter(variant -> {
                    var asset = catalogue.asset(variant.assetId());
                    return asset.kind() == Kind.PSG_VOLUME && ReconstructionStemParts.startsAudibly(asset.bytes());
                }).map(InstrumentCatalogue.Variant::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var byPart = new LinkedHashMap<Integer, List<MidiScore.Note>>();
        for (var note : notes) {
            if (note.endUs() > note.startUs())
                byPart.computeIfAbsent(note.partId(), ignored -> new ArrayList<>()).add(note);
        }

        var originals = sourceScore.notes().stream()
                .filter(note -> note.startUs() < limitUs && note.endUs() > note.startUs()).toList();
        var mixTimeline = new Timeline(originals);
        long mixEndUs = Math.min(limitUs, ReferenceAudio.durationMicros(mix));
        var result = new LinkedHashMap<Key, Choice>();
        for (var part : sourceScore.parts()) {
            var partNotes = byPart.get(part.id());
            if (partNotes == null || (part.drums() && toneKind == Kind.PSG_VOLUME)) continue;
            Path stem = stems.get(part.id());
            Path audio = stem == null ? mix : stem;
            var timeline = stem == null ? mixTimeline : new Timeline(
                    originals.stream().filter(note -> note.partId() == part.id()).toList());
            long audioEndUs = stem == null ? mixEndUs : Math.min(limitUs, ReferenceAudio.durationMicros(stem));
            String source = stem == null ? "mix" : "stem";
            if (part.drums()) {
                var byPitch = new java.util.TreeMap<Integer, List<MidiScore.Note>>();
                for (var note : partNotes)
                    byPitch.computeIfAbsent(note.pitch(), ignored -> new ArrayList<>()).add(note);
                for (var entry : byPitch.entrySet())
                    result.put(new Key(part.id(), entry.getKey()),
                            choose(catalogue, audio, audioEndUs, source, timeline, entry.getValue(), Kind.DAC_SAMPLE, Set.of(), target));
            } else {
                boolean added = addedParts.contains(part.id());
                if (added && stem != null) timeline = new Timeline(partNotes);
                result.put(new Key(part.id(), null), choose(catalogue, audio, audioEndUs, source, timeline, partNotes,
                        toneKind, added ? sustainedPsg : Set.of(), target));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Choice choose(InstrumentCatalogue catalogue, Path audio, long audioEndUs, String source, Timeline timeline,
                                 List<MidiScore.Note> notes, Kind kind, Set<String> variants, TargetGame target) throws IOException {
        boolean drums = kind == Kind.DAC_SAMPLE;
        var windows = windows(notes, timeline, audioEndUs, kind);
        long spacing = drums ? 180_000 : INDEPENDENT_US;
        return rank(catalogue, audio, source, timeline, kind,
                pickWindows(windows, drums ? 3 : 4, spacing, drums), variants, target);
    }

    private static List<Window> windows(List<MidiScore.Note> notes, Timeline timeline, long audioEndUs, Kind kind) {
        boolean drums = kind == Kind.DAC_SAMPLE;
        int lowest = kind == Kind.PSG_VOLUME ? 45 : 12;
        int highest = kind == Kind.PSG_VOLUME ? 117 : 106;
        var windows = new ArrayList<Window>();
        for (var note : notes) {
            if (!drums && (note.pitch() < lowest || note.pitch() > highest)) continue;
            long gate = note.endUs() - note.startUs();
            long duration = drums ? 180_000 : Math.min(420_000, gate >= 120_000 ? gate : gate + 60_000);
            duration = Math.clamp(audioEndUs - note.startUs(), 0, duration);
            windows.add(new Window(note, duration, timeline.overlapping(note.startUs(), note.startUs() + duration)));
        }
        windows.sort(Comparator.comparingInt(Window::overlappingNotes)
                .thenComparing(Comparator.comparingLong(Window::durationUs).reversed())
                .thenComparing(Comparator.comparingInt((Window window) -> window.note().velocity()).reversed())
                .thenComparingLong(window -> window.note().startUs())
                .thenComparingInt(window -> window.note().pitch()));

        return windows;
    }

    private static List<Window> pickWindows(List<Window> windows, int limit, long spacing, boolean drums) {
        var pitches = new HashSet<Integer>();
        var times = new HashSet<Long>();
        var selected = new ArrayList<Window>();
        for (int i = 0; i < limit && !windows.isEmpty(); i++) {
            Window window = windows.stream()
                    .filter(candidate -> selected.stream().noneMatch(previous ->
                            Math.abs(previous.note().startUs() - candidate.note().startUs()) < spacing))
                    .min(Comparator.comparingInt((Window candidate) ->
                                    candidate.overlappingNotes() + (candidate.durationUs() < 30_000 ? 1_000 : 0)
                                            + (times.contains(timeBand(candidate)) ? 1 : 0)
                                            + (!drums && pitches.contains(candidate.note().pitch()) ? 1 : 0)))
                    .orElse(null);
            if (window == null) break;
            selected.add(window);
            pitches.add(window.note().pitch());
            times.add(timeBand(window));
        }
        return selected;
    }

    private static Choice rank(InstrumentCatalogue catalogue, Path audio, String source, Timeline timeline,
                               Kind kind, List<Window> windows, Set<String> variants, TargetGame target) throws IOException {
        boolean drums = kind == Kind.DAC_SAMPLE;
        var probes = new ArrayList<Probe>();
        var evidence = new LinkedHashMap<String, Evidence>();
        Set<String> kicks = drums ? corroboratedKicks(catalogue, audio, windows) : Set.of();
        for (Window window : windows) {
            Probe probe = probe(catalogue, audio, source, kind, window, timeline, drums ? kicks : variants, target);
            probes.add(probe);
            double weight = 1.0 / (1.0 + probe.overlappingNotes() * 0.25);
            for (int rank = 0; rank < probe.matches().size(); rank++) {
                var hit = probe.matches().get(rank);
                String id = drums ? hit.variantId() : hit.assetId();
                evidence.computeIfAbsent(id, ignored -> new Evidence()).add(hit, rank, weight);
            }
        }

        var shortlist = evidence.values().stream().map(Evidence::summarize)
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparingDouble(Candidate::meanDistance)
                        .thenComparing(Candidate::variantId))
                .limit(SHORTLIST_SIZE).toList();
        if (shortlist.isEmpty())
            return new Choice(null, null, null, null, windows.isEmpty() ? "no-playable-pitches" : "no-match",
                    List.copyOf(probes), shortlist);

        Candidate first = shortlist.getFirst();
        Integer rate = drums ? catalogue.match(first.variantId()).variant().recipe().rate() : null;
        boolean weak = first.windows() < 2 || probes.stream().noneMatch(probe ->
                probe.overlappingNotes() == 0 && probe.matches().stream()
                        .anyMatch(hit -> drums ? hit.variantId().equals(first.variantId()) : hit.assetId().equals(first.assetId())));
        return new Choice(first.assetId(), first.variantId(), first.name(), rate, weak ? "weak-evidence" : "matched",
                List.copyOf(probes), shortlist, kicks.contains(first.variantId()) ? "low-transient-kick" : null);
    }

    static SectionPlan refineSections(InstrumentCatalogue catalogue, Path mix, Map<Integer, Path> stems,
                                      MidiScore source, List<MidiScore.Note> notes, long limitUs,
                                      Map<Key, Choice> defaults, TargetGame target) throws IOException {
        var choices = new LinkedHashMap<>(defaults);
        var trials = new ArrayList<SectionTrial>();
        var sections = new LinkedHashMap<Key, List<MidiScore.Note>>();
        if (limitUs <= SECTION_US) return new SectionPlan(defaults, List.of());
        for (var note : notes) {
            Choice fallback = defaults.get(new Key(note.partId(), null));
            if (fallback != null && fallback.variantId() != null && note.endUs() > note.startUs())
                sections.computeIfAbsent(Key.section(note), ignored -> new ArrayList<>()).add(note);
        }
        var originals = source.notes().stream().filter(note -> note.endUs() > note.startUs()).toList();
        var mixTimeline = new Timeline(originals);
        var stemTimelines = new java.util.HashMap<Integer, Timeline>();
        var stemEnds = new java.util.HashMap<Integer, Long>();
        for (var stem : stems.entrySet()) {
            int id = stem.getKey();
            stemTimelines.put(id, new Timeline(originals.stream().filter(note -> note.partId() == id).toList()));
            stemEnds.put(id, ReferenceAudio.durationMicros(stem.getValue()));
        }
        for (var section : sections.entrySet()) {
            Key key = section.getKey();
            Choice fallback = defaults.get(new Key(key.partId(), null));
            Path audio = stems.getOrDefault(key.partId(), mix);
            var timeline = stemTimelines.getOrDefault(key.partId(), mixTimeline);
            long endUs = Math.min(key.sectionStartUs() + SECTION_US, Math.min(limitUs, stemEnds.getOrDefault(key.partId(), limitUs)));
            var available = windows(section.getValue(), timeline, endUs, Kind.FM_VOICE).stream()
                    .filter(window -> window.durationUs() >= 60_000)
                    .filter(window -> fallback.probes().stream().noneMatch(probe ->
                            Math.abs(probe.startUs() - window.note().startUs()) < INDEPENDENT_US))
                    .toList();
            var selected = pickWindows(available, 4, INDEPENDENT_US, false);
            if (selected.size() < 4) continue;
            Choice candidate = rank(catalogue, audio, stems.containsKey(key.partId()) ? "stem" : "mix",
                    timeline, Kind.FM_VOICE, List.of(selected.get(0), selected.get(2)), Set.of(), target);
            if (candidate.assetId() == null || candidate.assetId().equals(fallback.assetId())) continue;
            var validation = new ArrayList<Check>();
            for (Window window : List.of(selected.get(1), selected.get(3))) {
                Check check = check(catalogue, audio, fallback, candidate, window, target);
                if (check != null) validation.add(check);
            }
            boolean accepted = validation.size() == 2 && validation.stream().allMatch(check ->
                    check.defaultDistance() - check.candidateDistance() >= Math.max(0.01, check.defaultDistance() * 0.10));
            trials.add(new SectionTrial(key, fallback.variantId(), candidate, List.copyOf(validation), accepted));
            if (accepted) choices.put(key, candidate);
        }
        return new SectionPlan(Collections.unmodifiableMap(choices), List.copyOf(trials));
    }

    private static Check check(InstrumentCatalogue catalogue, Path input, Choice fallback, Choice candidate,
                               Window window, TargetGame target) throws IOException {
        var note = window.note();
        var audio = ReferenceAudio.read(input, note.startUs() / (double) MICROS_PER_SECOND,
                window.durationUs() / (double) MICROS_PER_SECOND);
        AudioFingerprint reference;
        try {
            reference = AudioFingerprint.extract(audio.samples(), audio.sampleRate());
        } catch (IllegalArgumentException silent) {
            if (!"Fingerprint audio is silent".equals(silent.getMessage())) throw silent;
            return null;
        }
        int frames = (int) Math.ceil(audio.samples().length * (double) S3kRenderer.PRESENTATIONS_PER_SECOND / audio.sampleRate());
        int gate = gateTicks(note);
        double original = CatalogueMatcher.assetDistance(catalogue, reference, fallback.variantId(), note.pitch(), gate, frames, target);
        double replacement = CatalogueMatcher.assetDistance(catalogue, reference, candidate.variantId(), note.pitch(), gate, frames, target);
        return new Check(note.startUs(), window.durationUs(), note.pitch(), gate, window.overlappingNotes(),
                original, replacement);
    }

    private static int gateTicks(MidiScore.Note note) {
        return Math.clamp(Math.round((note.endUs() - note.startUs())
                * (double) S3kRenderer.PRESENTATIONS_PER_SECOND / MICROS_PER_SECOND), 1, 180);
    }

    private static long timeBand(Window window) {
        return window.note().startUs() / (8 * MICROS_PER_SECOND);
    }

    private static Set<String> corroboratedKicks(InstrumentCatalogue catalogue, Path audio, List<Window> windows)
            throws IOException {
        if (windows.size() < 2) return Set.of();
        int pitch = windows.getFirst().note().pitch();
        if (pitch != 35 && pitch != 36) return Set.of();

        var kicks = new HashSet<String>();
        for (var variant : catalogue.variants()) {
            if (variant.roles().contains("kick") || variant.occurrences().stream().anyMatch(occurrence ->
                    CatalogueMatcher.LOCKED_ON_S3K.equals(occurrence.revision())
                            && "S3K".equals(occurrence.driver()) && "dac".equals(occurrence.table())
                            && occurrence.slot() != null && S3K_KICK_SLOTS.contains(occurrence.slot()))) {
                kicks.add(variant.id());
            }
        }
        if (kicks.isEmpty()) return kicks;

        int transients = 0;
        for (var window : windows) {
            if (hasLowTransient(audio, window) && ++transients == 2) return kicks;
        }
        return Set.of();
    }

    private static boolean hasLowTransient(Path audio, Window window) throws IOException {
        long start = window.note().startUs();
        if (start < 80_000 || window.durationUs() < 155_000) return false;
        var excerpt = ReferenceAudio.read(audio, (start - 80_000) / (double) MICROS_PER_SECOND, 0.235);
        float[] samples = excerpt.samples();
        int rate = excerpt.sampleRate();
        double lowCoefficient = 1 - Math.exp(-2 * Math.PI * 140 / rate);
        double dcCoefficient = 1 - Math.exp(-2 * Math.PI * 30 / rate);
        double low = samples[0];
        double dc = low;
        double before = 0;
        double attack = 0;
        double tail = 0;
        double broadbandAttack = 0;
        // Equal 50 ms blocks placed before, just after, and well after the onset.
        for (int i = 0; i < samples.length; i++) {
            double sample = samples[i];
            low += lowCoefficient * (sample - low);
            dc += dcCoefficient * (low - dc);
            double band = low - dc;
            if (i >= rate * 20 / 1_000 && i < rate * 70 / 1_000) before += band * band;
            if (i >= rate * 90 / 1_000 && i < rate * 140 / 1_000) {
                attack += band * band;
                broadbandAttack += sample * sample;
            }
            if (i >= rate * 185 / 1_000) tail += band * band;
        }
        // Require a material bass component and at least 3 dB of rise and fall.
        return attack > 0 && attack >= broadbandAttack * 0.25 && attack >= before * 2 && attack >= tail * 2;
    }

    private static Probe probe(InstrumentCatalogue catalogue, Path path, String source, Kind kind, Window window,
                               Timeline timeline, Set<String> variants, TargetGame target) throws IOException {
        var note = window.note();
        long start = note.startUs();
        int midi = kind == Kind.DAC_SAMPLE ? Math.clamp(note.pitch(), 12, 117) : note.pitch();
        int gateTicks = gateTicks(note);
        long duration = window.durationUs();
        if (duration < 10_000)
            return new Probe(start, duration, midi, gateTicks, window.overlappingNotes(), source,
                    "outside-audio", 0, List.of());
        ReferenceAudio audio = ReferenceAudio.read(path, start / (double) MICROS_PER_SECOND,
                duration / (double) MICROS_PER_SECOND);
        int overlaps = timeline.overlapping(start, start + duration);
        try {
            var options = new CatalogueMatcher.Options(kind, null, null, midi, gateTicks,
                    kind == Kind.DAC_SAMPLE ? SHORTLIST_SIZE : InstrumentCatalogue.PAGE_LIMIT);
            var report = CatalogueMatcher.matchAssets(catalogue, audio, options, variants, target);
            if (kind == Kind.DAC_SAMPLE && !variants.isEmpty() && report.matches().isEmpty())
                report = CatalogueMatcher.matchAssets(catalogue, audio, options, Set.of(), target);
            var seenAssets = new HashSet<String>();
            var hits = report.matches().stream()
                    .filter(row -> kind == Kind.DAC_SAMPLE || seenAssets.add(row.assetId()))
                    .limit(SHORTLIST_SIZE)
                    .map(row -> new Hit(row.assetId(), row.variantId(), row.name(), row.distance().total()))
                    .toList();
            return new Probe(start, duration, midi, gateTicks, overlaps, source,
                    hits.isEmpty() ? "no-eligible-match" : "matched", report.compared(), hits);
        } catch (IllegalArgumentException invalid) {
            if (!"Fingerprint audio is silent".equals(invalid.getMessage())) throw invalid;
            return new Probe(start, duration, midi, gateTicks, overlaps, source, "silent", 0, List.of());
        }
    }
}
