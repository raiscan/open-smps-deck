package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

final class ReconstructionStructure {
    private static final double ONSET_WINDOW = 1.0 / 64;
    private static final double CORRECTION_THRESHOLD = .78;

    record Bar(String id, double start, double end, boolean complete) {
        double fraction(double time) { return (time - start) / (end - start); }
        double at(double fraction) { return start + fraction * (end - start); }
    }

    record Section(String id, List<String> bars) {}
    record Repeat(String id, List<String> bars) {}
    record Vote(String barId, String noteId) {}
    record Correction(String id, String barId, Note note, @ReconstructionStages.Nullable String replaces, List<Vote> votes,
                      @ReconstructionStages.Nullable String externalCandidate, boolean applied) {}
    record Result(int version, List<Bar> bars, List<Section> sections, List<Repeat> repeats,
                  List<Correction> corrections, List<Note> notes) {}

    private record Pattern(Bar bar, List<Note> notes) {
        double onset(Note note) { return bar.fraction(note.start()); }
        double duration(Note note) { return bar.fraction(note.end()) - onset(note); }
    }

    private record Attack(Pattern pattern, Note note) {}
    private static final class Slot {
        final Note first;
        final double onset;
        final Map<String, Attack> attacks = new LinkedHashMap<>();

        Slot(Attack attack) {
            first = attack.note;
            onset = attack.pattern.onset(first);
            add(attack);
        }

        void add(Attack attack) { attacks.put(attack.pattern.bar.id, attack); }
    }

    private ReconstructionStructure() {}

    static Result analyze(List<Bar> bars, List<Note> score, List<MidiScore.Part> parts) {
        var drums = new HashSet<Integer>();
        for (var part : parts) if (part.drums()) drums.add(part.id());
        var originals = score.stream().filter(note -> !note.source().equals("external")).toList();
        var candidates = score.stream().filter(note -> note.source().equals("external")).toList();
        var patterns = patterns(bars, originals.stream().filter(note -> !drums.contains(note.part())).toList());
        var groups = group(patterns);
        var repeats = new ArrayList<Repeat>();
        var corrections = new ArrayList<Correction>();
        var corrected = new LinkedHashMap<String, Note>();
        for (var note : originals) corrected.put(note.id(), note);
        for (var group : groups) {
            if (group.size() < 2) continue;
            String id = "repeat-" + group.getFirst().bar.id;
            repeats.add(new Repeat(id, group.stream().map(pattern -> pattern.bar.id).toList()));
            consensus(group, candidates, corrected, corrections, bars.getLast().end());
        }
        var notes = corrected.values().stream().sorted(ReconstructionStages.NOTE_ORDER).toList();
        return new Result(VERSION, bars, sections(patterns, repeats), repeats, corrections, notes);
    }

    private static List<Pattern> patterns(List<Bar> bars, List<Note> notes) {
        var patterns = new ArrayList<Pattern>();
        int index = 0;
        for (var bar : bars) {
            var attacks = new ArrayList<Note>();
            while (index < notes.size() && notes.get(index).start() < bar.end) {
                attacks.add(notes.get(index++));
            }
            patterns.add(new Pattern(bar, attacks));
        }
        return patterns;
    }

    private static List<List<Pattern>> group(List<Pattern> patterns) {
        var groups = new ArrayList<List<Pattern>>();
        for (var pattern : patterns) {
            if (!pattern.bar.complete || pattern.notes.isEmpty()) continue;
            List<Pattern> best = null;
            double bestSimilarity = .8;
            for (var group : groups) {
                // Compare with the anchor and every already admitted instance to prevent chain drift.
                double similarity = 1;
                for (var member : group) {
                    similarity = Math.min(similarity, similarity(pattern, member));
                    if (similarity < bestSimilarity) break;
                }
                if (similarity >= bestSimilarity) {
                    best = group;
                    bestSimilarity = similarity;
                }
            }
            if (best == null) {
                best = new ArrayList<>();
                groups.add(best);
            }
            best.add(pattern);
        }
        return groups;
    }

    private static double similarity(Pattern a, Pattern b) {
        var matched = new HashSet<String>();
        for (var note : a.notes) {
            Note closest = nearest(b, note, a.onset(note), matched, false);
            if (closest != null) matched.add(closest.id());
        }
        return matched.size() / (double) Math.max(a.notes.size(), b.notes.size());
    }

    static Note nearest(Bar bar, List<Note> notes, Note note, double onset, Set<String> used) {
        return nearest(new Pattern(bar, notes), note, onset, used, false);
    }

    private static Note nearest(Pattern pattern, Note target, double onset, Set<String> used, boolean wrongPitch) {
        Note best = null;
        double bestDistance = ONSET_WINDOW;
        for (var note : pattern.notes) {
            double distance = Math.abs(pattern.onset(note) - onset);
            int interval = Math.abs(note.pitch() - target.pitch());
            if (note.part() == target.part() && (wrongPitch ? interval > 0 && interval <= 2 : interval == 0)
                    && distance <= bestDistance && !used.contains(note.id())) {
                best = note;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void consensus(List<Pattern> group, List<Note> candidates, Map<String, Note> corrected,
                                  List<Correction> changes, double scoreEnd) {
        if (group.size() < 3) return;
        var slots = new ArrayList<Slot>();
        for (var pattern : group) {
            for (var note : pattern.notes) {
                var attack = new Attack(pattern, note);
                var slot = slots.stream().filter(s -> s.first.part() == note.part()
                                && s.first.pitch() == note.pitch() && !s.attacks.containsKey(pattern.bar.id)
                                && Math.abs(s.onset - pattern.onset(note)) <= ONSET_WINDOW)
                        .min(Comparator.comparingDouble(s -> Math.abs(s.onset - pattern.onset(note))));
                if (slot.isPresent()) slot.get().add(attack);
                else slots.add(new Slot(attack));
            }
        }
        var protectedNotes = new HashSet<String>();
        for (var slot : slots) if (slot.attacks.size() >= 2)
            slot.attacks.values().forEach(attack -> protectedNotes.add(attack.note.id()));
        for (var slot : slots) {
            int support = slot.attacks.size();
            if (support < 2) continue;
            double onset = median(slot.attacks.values().stream().mapToDouble(a -> a.pattern.onset(a.note)).toArray());
            double duration = median(slot.attacks.values().stream().mapToDouble(a -> a.pattern.duration(a.note)).toArray());
            double spread = slot.attacks.values().stream()
                    .mapToDouble(a -> Math.abs(a.pattern.onset(a.note) - onset)).max().orElse(0);
            int velocity = (int) Math.round(median(slot.attacks.values().stream().mapToDouble(a -> a.note.velocity()).toArray()));
            var votes = slot.attacks.values().stream().map(a -> new Vote(a.pattern.bar.id, a.note.id())).toList();
            for (var target : group) {
                if (slot.attacks.containsKey(target.bar.id)) continue;
                double start = target.bar.at(onset);
                double end = Math.min(target.bar.at(onset + duration), scoreEnd);
                if (end <= start) continue;
                Note wrong = nearest(target, slot.first, onset, protectedNotes, true);
                if (wrong != null && Math.abs(target.duration(wrong) - duration) > ONSET_WINDOW * 2) wrong = null;
                Note external = nearest(new Pattern(target.bar, candidates), slot.first, onset, Set.of(), false);
                double confidence = Math.min(1, .67 + .19 * support / group.size()
                        - .05 * spread / ONSET_WINDOW + (external == null ? 0 : .08));
                boolean apply = support * 3 >= group.size() * 2 && confidence >= CORRECTION_THRESHOLD;
                String id = "consensus-" + target.bar.id + "-" + slot.first.id();
                String noteId = wrong == null ? id : wrong.id();
                var newNote = new Note(noteId, slot.first.part(), start, end, slot.first.pitch(), velocity, "consensus", confidence);
                changes.add(new Correction(id, target.bar.id, newNote, wrong == null ? null : wrong.id(), votes,
                        external == null ? null : external.id(), apply));
                if (apply) {
                    protectedNotes.add(noteId);
                    corrected.put(noteId, newNote);
                }
            }
        }
    }

    private static double median(double[] values) {
        java.util.Arrays.sort(values);
        return (values[(values.length - 1) / 2] + values[values.length / 2]) / 2;
    }

    private static List<Section> sections(List<Pattern> patterns, List<Repeat> repeats) {
        var repeatIds = new HashMap<String, String>();
        for (var repeat : repeats) for (String bar : repeat.bars) repeatIds.put(bar, repeat.id);
        var result = new ArrayList<Section>();
        var current = new ArrayList<String>();
        Set<Integer> previous = Set.of();
        boolean wasRepeated = false;
        for (var pattern : patterns) {
            var active = new HashSet<Integer>();
            for (var note : pattern.notes) active.add(note.part());
            boolean repeated = repeatIds.containsKey(pattern.bar.id);
            if (!current.isEmpty() && (current.size() >= 4 || !previous.equals(active) || repeated != wasRepeated)) {
                result.add(new Section("section-" + current.getFirst(), List.copyOf(current)));
                current.clear();
            }
            current.add(pattern.bar.id);
            previous = active;
            wasRepeated = repeated;
        }
        if (!current.isEmpty()) result.add(new Section("section-" + current.getFirst(), List.copyOf(current)));
        return result;
    }
}
