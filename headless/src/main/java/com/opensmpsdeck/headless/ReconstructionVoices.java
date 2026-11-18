package com.opensmpsdeck.headless;

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

final class ReconstructionVoices {
    private static final int SHORTLIST_SIZE = 8;
    private static final long MICROS_PER_SECOND = 1_000_000;

    record Key(int partId, Integer drumPitch) {}

    record Probe(long startUs, long durationUs, int midi, int gateTicks, int overlappingNotes,
                 String source, String status, int compared, List<Hit> matches) {}

    record Hit(String assetId, String variantId, String name, double distance) {}

    record Candidate(String assetId, String variantId, String name, int windows,
                     double score, double meanDistance) {}

    record Choice(String assetId, String variantId, String name, Integer sampleRate, String status,
                  List<Probe> probes, List<Candidate> shortlist) {}

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
                            choose(catalogue, audio, audioEndUs, source, timeline, entry.getValue(), Kind.DAC_SAMPLE));
            } else {
                result.put(new Key(part.id(), null), choose(catalogue, audio, audioEndUs, source, timeline, partNotes, toneKind));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Choice choose(InstrumentCatalogue catalogue, Path audio, long audioEndUs, String source, Timeline timeline,
                                 List<MidiScore.Note> notes, Kind kind) throws IOException {
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

        var probes = new ArrayList<Probe>();
        var evidence = new LinkedHashMap<String, Evidence>();
        var pitches = new HashSet<Integer>();
        var times = new HashSet<Long>();
        var selected = new ArrayList<Window>();
        int limit = drums ? 3 : 4;
        for (int i = 0; i < limit && !windows.isEmpty(); i++) {
            Window window = windows.stream()
                    .filter(candidate -> selected.stream().noneMatch(previous ->
                            Math.abs(previous.note().startUs() - candidate.note().startUs()) < (drums ? 180_000 : 80_000)))
                    .min(Comparator.comparingInt((Window candidate) ->
                                    candidate.overlappingNotes() + (candidate.durationUs() < 30_000 ? 1_000 : 0)
                                            + (times.contains(timeBand(candidate)) ? 1 : 0)
                                            + (!drums && pitches.contains(candidate.note().pitch()) ? 1 : 0)))
                    .orElse(null);
            if (window == null) break;
            selected.add(window);
            pitches.add(window.note().pitch());
            times.add(timeBand(window));
            Probe probe = probe(catalogue, audio, source, kind, window, timeline);
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
                List.copyOf(probes), shortlist);
    }

    private static long timeBand(Window window) {
        return window.note().startUs() / (8 * MICROS_PER_SECOND);
    }

    private static Probe probe(InstrumentCatalogue catalogue, Path path, String source, Kind kind, Window window,
                               Timeline timeline) throws IOException {
        var note = window.note();
        long start = note.startUs();
        int midi = kind == Kind.DAC_SAMPLE ? Math.clamp(note.pitch(), 12, 117) : note.pitch();
        int gateTicks = Math.clamp(Math.round((note.endUs() - start)
                * (double) S3kRenderer.PRESENTATIONS_PER_SECOND / MICROS_PER_SECOND), 1, 180);
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
            var report = CatalogueMatcher.matchAssets(catalogue, audio, options);
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
