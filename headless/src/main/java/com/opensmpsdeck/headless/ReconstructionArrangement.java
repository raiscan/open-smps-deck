package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Persistent arrangement controls. Spans use source seconds and are half-open. */
final class ReconstructionArrangement {
    enum Hats { all, overflow }

    record PartChip(int part, ReconstructionLaneType chip, @Nullable List<String> lanes, @Nullable List<Double> span) {
        List<String> allowed() { return lanes == null ? chip.lanes() : lanes; }
    }

    record Polyphony(int part, int maxVoices, @Nullable List<Double> span) {}

    record BassHarmonics(int part, @Nullable Integer bassPart, @Nullable List<Double> span, @Nullable List<Integer> intervals,
                         @Nullable Integer pitchTolerance, @Nullable Integer minVelocityDelta, @Nullable Double minOverlap,
                         @Nullable Double minLevelDb) {
        Note reference(Note note, List<Note> bass, java.util.Set<String> excluded,
                       java.util.function.BiPredicate<Note, Note> louder) {
            int tolerance = pitchTolerance == null ? 0 : pitchTolerance;
            double overlap = minOverlap == null ? .6 : minOverlap;
            var partials = intervals == null ? List.of(12, 19, 24) : intervals;
            return bass.stream()
                    .filter(root -> Math.min(root.end(), note.end()) - Math.max(root.start(), note.start())
                            >= (note.end() - note.start()) * overlap)
                    .filter(root -> partials.stream().anyMatch(interval ->
                            Math.abs(note.pitch() - root.pitch() - interval) <= tolerance))
                    .filter(root -> !excluded.contains(root.id()) && louder.test(root, note))
                    .max(Comparator.comparingInt(Note::velocity).thenComparing(Note::id)).orElse(null);
        }
        boolean louderByVelocity(Note root, Note partial) {
            return root.velocity() - partial.velocity() >= (minVelocityDelta == null ? 10 : minVelocityDelta);
        }

    }

    record Drop(String reason, List<String> blockers) {}
    record Allocation(Map<String, PartChip> pins, Map<String, Drop> drops, Hats hats) {
        static final Allocation NONE = new Allocation(Map.of(), Map.of(), null);
    }

    record Configuration(@Nullable List<PartChip> partChips, @Nullable List<Polyphony> polyphony,
                         @Nullable List<BassHarmonics> bassHarmonics, @Nullable Hats hats,
                         @Nullable ReconstructionDrumDynamics drumDynamics, @Nullable List<SectionFit> sectionFit,
                         @Nullable Integer vocalRegisterShift) {
        Configuration {
            partChips = partChips == null ? List.of() : List.copyOf(partChips);
            polyphony = polyphony == null ? List.of() : List.copyOf(polyphony);
            bassHarmonics = bassHarmonics == null ? List.of() : List.copyOf(bassHarmonics);
            sectionFit = sectionFit == null ? List.of() : List.copyOf(sectionFit);
        }

        void validate(ReconstructionStages.Score score) throws java.io.IOException {
            for (var rule : partChips) {
                part(score, rule.part(), false);
                span(rule.span());
                require(rule.chip() == ReconstructionLaneType.FM || rule.chip() == ReconstructionLaneType.PSG,
                        "arrangement.partChips", "chip must be FM or PSG");
                require(rule.chip() != ReconstructionLaneType.PSG || !score.inputs().fmOnly(),
                        "arrangement.partChips", "PSG is disabled in FM-only mode");
                var lanes = rule.allowed();
                require(!lanes.isEmpty() && rule.chip().lanes().containsAll(lanes)
                                && new HashSet<>(lanes).size() == lanes.size(),
                        "arrangement.partChips", "lanes must be distinct and belong to the configured chip");
            }
            for (var rule : polyphony) {
                part(score, rule.part(), false);
                span(rule.span());
                require(rule.maxVoices() >= 1, "arrangement.polyphony", "maxVoices must be positive");
            }
            for (var rule : bassHarmonics) {
                part(score, rule.part(), false);
                if (rule.bassPart() != null) part(score, rule.bassPart(), false);
                span(rule.span());
                require(rule.intervals() == null || !rule.intervals().isEmpty()
                                && rule.intervals().stream().allMatch(value -> value >= 2 && value <= 127),
                        "arrangement.bassHarmonics", "intervals must be positive partials of at least two semitones");
                require(rule.pitchTolerance() == null || rule.pitchTolerance() >= 0 && rule.pitchTolerance() <= 1,
                        "arrangement.bassHarmonics", "pitchTolerance must be 0 or 1 semitone");
                require(rule.minVelocityDelta() == null || rule.minVelocityDelta() >= 1 && rule.minVelocityDelta() <= 126,
                        "arrangement.bassHarmonics", "minVelocityDelta must be between 1 and 126");
                require(rule.minOverlap() == null || rule.minOverlap() > 0 && rule.minOverlap() <= 1,
                        "arrangement.bassHarmonics", "minOverlap must be in (0, 1]");
                require(rule.minLevelDb() == null || rule.minLevelDb() > 0 && rule.minLevelDb() <= 60,
                        "arrangement.bassHarmonics", "minLevelDb must be in (0, 60]");
            }
            require(hats == null || !score.inputs().fmOnly(), "arrangement.hats", "PSG is disabled in FM-only mode");
            if (drumDynamics != null) drumDynamics.validate(score);
            require(vocalRegisterShift == null || Math.abs((long) vocalRegisterShift) <= 48,
                    "arrangement.vocalRegisterShift", "expected -48 to 48 semitones");
            var sectionParts = new HashSet<Integer>();
            for (var rule : sectionFit) {
                part(score, rule.part(), false);
                require(score.inputs().stems().containsKey(rule.part()), "arrangement.sectionFit", "part needs a mapped stem");
                require(sectionParts.add(rule.part()), "arrangement.sectionFit", "each part can have one fit rule");
                require(rule.maxJumpDb() >= 0 && rule.maxJumpDb() <= 24,
                        "arrangement.sectionFit", "maxJumpDb must be in [0, 24]");
            }
        }

        Allocation resolve(ReconstructionStages.Score score, ReconstructionRoles.Result roles) throws java.io.IOException {
            validate(score);
            var timeline = ReconstructionTimeMap.of(score);
            var byPart = new HashMap<Integer, List<Note>>();
            for (var entry : roles.notes()) {
                if (entry.role() == ReconstructionRoles.Role.drop) continue;
                var note = entry.note();
                byPart.computeIfAbsent(note.part(), ignored -> new ArrayList<>()).add(note);
            }
            var pins = new HashMap<String, PartChip>();
            for (var rule : partChips) {
                for (var note : byPart.getOrDefault(rule.part(), List.of())) {
                    if (covers(rule.span(), timeline.source(note.start()))) pins.put(note.id(), rule);
                }
            }
            var drops = new HashMap<String, Drop>();
            var spectra = new HashMap<String, ReconstructionStemParts.Spectrum>();
            for (var rule : bassHarmonics) {
                int referencePart = rule.bassPart() == null ? rule.part() : rule.bassPart();
                var bass = retained(byPart, referencePart, drops);
                var rootSpectrum = spectrum(score, referencePart, spectra);
                var partSpectrum = spectrum(score, rule.part(), spectra);
                java.util.function.BiPredicate<Note, Note> louder = rootSpectrum == null || partSpectrum == null
                        ? rule::louderByVelocity : (root, partial) -> {
                            double start = timeline.source(Math.max(root.start(), partial.start()));
                            double end = timeline.source(Math.min(root.end(), partial.end()));
                            double fundamental = rootSpectrum.level(root.withTiming(start, end), root.pitch());
                            double upper = partSpectrum.level(partial.withTiming(start, end), partial.pitch());
                            double ratio = Math.pow(10, (rule.minLevelDb() == null ? 6 : rule.minLevelDb()) / 20);
                            return fundamental >= .001 && fundamental > ratio * upper;
                        };
                // Evaluate lower pitches first so a rejected partial cannot remove its own overtones.
                for (var note : retained(byPart, rule.part(), drops).stream()
                        .sorted(Comparator.comparingInt(Note::pitch).thenComparing(Note::id)).toList()) {
                    if (!covers(rule.span(), timeline.source(note.start()))) continue;
                    var root = rule.reference(note, bass, drops.keySet(), louder);
                    if (root != null) drops.put(note.id(), new Drop("arrangement:bass-harmonic", List.of(root.id())));
                }
            }
            for (var rule : polyphony) {
                // Evaluate in hardware ticks so near-coincident notes that round together count as simultaneous.
                var notes = retained(byPart, rule.part(), drops).stream().map(note -> note.withTiming(
                        note.startTick(timeline.ticks(), timeline.clock()), note.endTick(timeline.ticks(), timeline.clock()))).toList();
                double from = rule.span() == null ? 0 : timeline.firstTickAtOrAfter(timeline.project(rule.span().getFirst()));
                double to = rule.span() == null ? timeline.ticks() : timeline.firstTickAtOrAfter(timeline.project(rule.span().getLast()));
                for (String id : capped(rule, notes, from, to))
                    drops.put(id, new Drop("arrangement:polyphony-cap", List.of()));
            }
            if (drumDynamics != null && drumDynamics.dropBelowDb() != null) {
                drumDynamics.measure(score, roles.notes().stream()
                        .filter(row -> row.role() == ReconstructionRoles.Role.drums).map(row -> timeline.sourceNote(row.note())).toList())
                        .forEach((id, level) -> {
                            if (level < drumDynamics.dropBelowDb())
                                drops.put(id, new Drop("arrangement:quiet-drum-hit", List.of()));
                        });
            }
            return new Allocation(pins, drops, hats);
        }
    }

    record SectionFit(int part, double maxJumpDb) {}

    static List<String> capped(Polyphony rule, List<Note> notes, double from, double to) {
        var active = new ArrayList<Note>();
        var removed = new ArrayList<String>();
        var sorted = notes.stream().filter(note -> Math.max(from, note.start()) < Math.min(to, note.end()))
                .sorted(Comparator.comparingDouble((Note note) -> Math.max(from, note.start())).thenComparing(Note::id)).toList();
        var pitch = Comparator.comparingInt(Note::pitch);
        var strength = Comparator.comparingInt(Note::velocity)
                .thenComparingDouble(note -> note.end() - note.start()).thenComparing(Note::id);
        for (int i = 0; i < sorted.size();) {
            double onset = Math.max(from, sorted.get(i).start());
            active.removeIf(note -> note.end() <= onset);
            do active.add(sorted.get(i++));
            while (i < sorted.size() && Math.max(from, sorted.get(i).start()) == onset);
            if (active.size() <= rule.maxVoices()) continue;
            var highest = active.stream().max(pitch.thenComparing(strength)).orElseThrow();
            var lowest = active.stream().filter(note -> note != highest)
                    .max(pitch.reversed().thenComparing(strength)).orElseThrow();
            while (active.size() > rule.maxVoices()) {
                var loser = active.stream()
                        .filter(note -> note != highest && (rule.maxVoices() == 1 || note != lowest))
                        .min(strength).orElseThrow();
                active.remove(loser);
                removed.add(loser.id());
            }
        }
        return List.copyOf(removed);
    }

    private static ReconstructionStemParts.Spectrum spectrum(ReconstructionStages.Score score, int part,
            Map<String, ReconstructionStemParts.Spectrum> cache) throws java.io.IOException {
        String stem = score.inputs().stems().get(part);
        if (stem == null) return null;
        if (!cache.containsKey(stem))
            cache.put(stem, ReconstructionStemParts.Spectrum.read(java.nio.file.Path.of(stem), score.durationUs() / 1e6));
        return cache.get(stem);
    }

    private static List<Note> retained(Map<Integer, List<Note>> parts, int part, Map<String, Drop> drops) {
        return parts.getOrDefault(part, List.of()).stream().filter(note -> !drops.containsKey(note.id())).toList();
    }

    static void part(ReconstructionStages.Score score, int part, boolean drums) {
        require(score.source().parts().stream().anyMatch(p -> p.id() == part && p.drums() == drums),
                "arrangement.part", "expected an existing " + (drums ? "drum" : "tonal") + " part: " + part);
    }

    static boolean covers(List<Double> span, double onset) {
        return span == null || onset >= span.getFirst() && onset < span.getLast();
    }

    static void span(List<Double> span) {
        require(span == null || span.size() == 2 && span.getFirst() >= 0 && span.getLast() > span.getFirst()
                        && Double.isFinite(span.getLast()),
                "arrangement.span", "expected [start, end) in source seconds");
    }
}
