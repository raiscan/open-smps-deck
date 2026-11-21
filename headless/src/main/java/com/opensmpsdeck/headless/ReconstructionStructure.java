package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

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
    record Layout(List<Bar> bars, List<Section> sections, List<Repeat> repeats) {}
    record Vote(String barId, String noteId) {}
    record Correction(String id, String barId, Note note, @ReconstructionStages.Nullable String replaces, List<Vote> votes,
                      @ReconstructionStages.Nullable String externalCandidate, boolean applied,
                      @ReconstructionStages.Nullable String phraseId) {}
    record Result(int version, List<Bar> bars, List<Section> sections, List<Repeat> repeats,
                  List<Correction> corrections, List<Note> notes,
                  @ReconstructionStages.Nullable List<ReconstructionPhrases.Group> phrases,
                  @ReconstructionStages.Nullable Layout roleLayout,
                  @ReconstructionStages.Nullable ReconstructionTimeMap.TimeBase timeBase,
                  @ReconstructionStages.Nullable Map<String, String> scoreNoteHashes) {
        Result(int version, List<Bar> bars, List<Section> sections, List<Repeat> repeats,
               List<Correction> corrections, List<Note> notes) {
            this(version, bars, sections, repeats, corrections, notes, null, null, null, null);
        }
    }

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
        return analyze(bars, score, parts, null, false);
    }

    static Result analyze(List<Bar> bars, List<Note> score, List<MidiScore.Part> parts,
                          ReconstructionRhythmAudio audio, boolean inferred) {
        return analyze(bars, score, parts, audio, inferred, 4, null);
    }

    static Result analyze(List<Bar> bars, List<Note> score, List<MidiScore.Part> parts,
                          ReconstructionRhythmAudio audio, boolean inferred, int meter, List<Bar> midiBars) {
        return analyze(bars, score, parts, audio, inferred, meter, midiBars, null);
    }

    static Result analyze(List<Bar> bars, List<Note> score, List<MidiScore.Part> parts,
                          ReconstructionRhythmAudio audio, boolean inferred, int meter, List<Bar> midiBars,
                          ReconstructionTimeMap timeline) {
        return analyze(bars, score, parts, audio, inferred, meter, midiBars, timeline, Set.of(), List.of());
    }

    static Result analyze(List<Bar> bars, List<Note> score, List<MidiScore.Part> parts,
                          ReconstructionRhythmAudio audio, boolean inferred, int meter, List<Bar> midiBars,
                          ReconstructionTimeMap timeline, Set<String> authoredIds, List<Note> suppressed) {
        var drums = new HashSet<Integer>();
        for (var part : parts) if (part.drums()) drums.add(part.id());
        var originals = score.stream().filter(note -> !note.source().equals("external")).toList();
        var candidates = score.stream().filter(note -> note.source().equals("external")).toList();
        var tones = originals.stream().filter(note -> note.run() == null && !drums.contains(note.part())
                && !ReconstructionStemTemplates.SOURCE.equals(note.source())).toList();
        var patterns = patterns(bars, tones);
        var rolePatterns = midiBars == null ? patterns : patterns(midiBars, tones);
        var groups = inferred && midiBars == null ? List.<List<Pattern>>of() : group(rolePatterns);
        var repeats = new ArrayList<Repeat>();
        var corrections = new ArrayList<Correction>();
        var corrected = new LinkedHashMap<String, Note>();
        for (var note : originals) corrected.put(note.id(), note);
        for (var group : groups) {
            if (group.size() < 2) continue;
            String id = "repeat-" + group.getFirst().bar.id;
            repeats.add(new Repeat(id, group.stream().map(pattern -> pattern.bar.id).toList()));
            consensus(group, candidates, corrected, corrections, bars.getLast().end(), authoredIds, suppressed);
        }
        var sections = sections(patterns, midiBars == null ? repeats : List.of());
        var roleLayout = midiBars == null ? null : new Layout(midiBars, sections(rolePatterns, repeats), repeats);
        var byBar = new LinkedHashMap<String, List<Note>>();
        for (var pattern : patterns) byBar.put(pattern.bar.id, pattern.notes);
        var programs = new HashMap<Integer, Integer>();
        for (var part : parts) programs.put(part.id(), part.program());
        var prepared = corrected.values().stream().filter(note -> !drums.contains(note.part())
                        && !ReconstructionStemTemplates.SOURCE.equals(note.source()))
                .sorted(ReconstructionStages.NOTE_ORDER).toList();
        var melody = ReconstructionRoles.melody(roleLayout == null ? new Layout(bars, sections, repeats) : roleLayout,
                prepared, programs);
        var phrases = ReconstructionPhrases.find(bars, byBar, melody.lead(), audio, meter,
                timeline == null ? DoubleUnaryOperator.identity() : timeline::source);
        phraseConsensus(phrases, candidates, corrected, corrections, bars.getLast().end(), suppressed);
        var notes = corrected.values().stream().sorted(ReconstructionStages.NOTE_ORDER).toList();
        var linked = inferred ? identicalBars(bars, notes, phrases, timeline) : repeats;
        return new Result(VERSION, bars, sections, linked, corrections, notes,
                phrases.stream().map(ReconstructionPhrases.Match::evidence).toList(), roleLayout,
                timeline == null ? ReconstructionTimeMap.TimeBase.source : timeline.timeBase(),
                ReconstructionArtifacts.noteHashes(score));
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
                                  List<Correction> changes, double scoreEnd,
                                  Set<String> authoredIds, List<Note> suppressed) {
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
        var protectedNotes = new HashSet<>(authoredIds);
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
                if (end <= start || suppressed(suppressed, slot.first, start, ONSET_WINDOW * (target.bar.end() - target.bar.start()))) continue;
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
                        external == null ? null : external.id(), apply, null));
                if (apply) {
                    protectedNotes.add(noteId);
                    corrected.put(noteId, newNote);
                }
            }
        }
    }

    private record RelativeNote(int part, int pitch, int velocity, long start, long end) {}
    private record BarContent(long length, List<RelativeNote> notes) {}

    private static List<Repeat> identicalBars(List<Bar> bars, List<Note> notes, List<ReconstructionPhrases.Match> phrases,
                                             ReconstructionTimeMap timeline) {
        java.util.function.DoubleToLongFunction toFrame = timeline != null && timeline.warped()
                ? time -> S3kReconstruction.toTicks(Math.round(time * 1e6)) : time -> Math.round(time * 60);
        var eligible = new HashSet<String>();
        for (var match : phrases) for (var window : match.windows()) for (var bar : window.bars()) eligible.add(bar.id());
        var groups = new LinkedHashMap<BarContent, List<String>>();
        for (var pattern : patterns(bars, notes)) {
            Bar bar = pattern.bar;
            if (!eligible.contains(bar.id) || pattern.notes.isEmpty()) continue;
            long start = toFrame.applyAsLong(bar.start), end = toFrame.applyAsLong(bar.end);
            if (pattern.notes.stream().anyMatch(n -> toFrame.applyAsLong(n.end()) > end)) continue;
            var signature = pattern.notes.stream().map(n -> new RelativeNote(n.part(), n.pitch(), n.velocity(),
                    toFrame.applyAsLong(n.start()) - start, toFrame.applyAsLong(n.end()) - start)).toList();
            groups.computeIfAbsent(new BarContent(end - start, signature), ignored -> new ArrayList<>()).add(bar.id);
        }
        return groups.values().stream().filter(group -> group.size() >= 2)
                .map(group -> new Repeat("repeat-" + group.getFirst(), group)).toList();
    }

    private static void phraseConsensus(List<ReconstructionPhrases.Match> phrases, List<Note> candidates,
                                        Map<String, Note> corrected, List<Correction> changes, double scoreEnd, List<Note> suppressed) {
        for (var phrase : phrases) {
            var windows = phrase.windows();
            if (windows.size() < 3) continue;
            var remaining = new LinkedHashMap<ReconstructionPhrases.Window, List<Note>>();
            for (var window : windows) remaining.put(window, window.notes());
            // Walk exemplars until every attack has an alignment slot. A note absent
            // from the first instance can still seed a slot in a later instance.
            for (var exemplar : windows) {
                var anchors = remaining.remove(exemplar);
                var slots = new LinkedHashMap<String, Map<ReconstructionPhrases.Window, Note>>();
                for (var note : anchors) slots.put(note.id(), new LinkedHashMap<>(Map.of(exemplar, note)));
                for (var entry : remaining.entrySet()) {
                    var used = new HashSet<String>();
                    for (var link : ReconstructionPhrases.votingAlignment(exemplar, entry.getKey(), anchors, entry.getValue())) {
                        slots.get(link.first().id()).put(entry.getKey(), link.second());
                        used.add(link.second().id());
                    }
                    entry.setValue(entry.getValue().stream().filter(note -> !used.contains(note.id())).toList());
                }
                for (var anchor : anchors) {
                    var voters = slots.get(anchor.id());
                    if (voters.size() < 2) continue;
                    for (var target : windows) {
                        if (voters.containsKey(target)) continue;
                        propose(phrase, target, anchor, voters, candidates, corrected, changes, scoreEnd, suppressed);
                    }
                }
            }
        }
    }

    private static void propose(ReconstructionPhrases.Match match, ReconstructionPhrases.Window target, Note anchor,
                                Map<ReconstructionPhrases.Window, Note> voters, List<Note> candidates,
                                Map<String, Note> corrected, List<Correction> changes, double scoreEnd, List<Note> suppressed) {
        double similarity = voters.keySet().stream().mapToDouble(w ->
                ReconstructionPhrases.partSimilarity(w, target, anchor.part())).min().orElse(0);
        if (similarity < .76) return;
        double relative = median(voters.entrySet().stream().mapToDouble(v -> v.getKey().relative(v.getValue())).toArray());
        double start = target.start() + relative * target.length();
        double duration = median(voters.values().stream().mapToDouble(n -> n.end() - n.start()).toArray());
        double end = Math.min(start + duration, scoreEnd);
        if (end <= start) return;
        double tolerance = target.tolerance() * target.length();
        if (suppressed(suppressed, anchor, start, tolerance)) return;
        // A nearby octave-equivalent attack already supplies this slot.
        if (corrected.values().stream().anyMatch(n -> n.part() == anchor.part()
                && Math.floorMod(n.pitch() - anchor.pitch(), 12) == 0 && Math.abs(n.start() - start) <= tolerance)) return;
        if (changes.stream().anyMatch(c -> c.phraseId() == null && c.note().part() == anchor.part()
                && c.note().pitch() == anchor.pitch() && Math.abs(c.note().start() - start) <= tolerance)) return;
        Note external = candidates.stream().filter(n -> n.part() == anchor.part() && n.pitch() == anchor.pitch()
                && Math.abs(n.start() - start) <= tolerance).findFirst().orElse(null);
        double spread = voters.entrySet().stream().mapToDouble(v ->
                Math.abs(v.getKey().relative(v.getValue()) - relative) * target.length()).max().orElse(0);
        int velocity = (int) Math.round(median(voters.values().stream().mapToDouble(Note::velocity).toArray()));
        double confidence = Math.min(1, .67 + .19 * voters.size() / match.windows().size()
                - .05 * spread / tolerance + (external == null ? 0 : .08));
        int targetCount = (int) target.notes().stream().filter(n -> n.part() == anchor.part()).count();
        double typicalCount = median(voters.keySet().stream().mapToDouble(w ->
                w.notes().stream().filter(n -> n.part() == anchor.part()).count()).toArray());
        boolean isolatedGap = typicalCount - targetCount >= 1 && typicalCount - targetCount <= 2;
        boolean apply = voters.size() * 3 >= match.windows().size() * 2 && confidence >= CORRECTION_THRESHOLD
                && (isolatedGap || external != null);
        String targetBar = target.barAt(start);
        String id = "consensus-" + match.evidence().id() + "-" + targetBar + "-" + anchor.id();
        var note = new Note(id, anchor.part(), start, end, anchor.pitch(), velocity, "consensus", confidence);
        var votes = voters.entrySet().stream().sorted(java.util.Comparator.comparingInt(v -> v.getKey().index()))
                .map(v -> new Vote(v.getKey().barAt(v.getValue().start()), v.getValue().id())).toList();
        changes.add(new Correction(id, targetBar, note, null, votes, external == null ? null : external.id(),
                apply, match.evidence().id()));
        if (apply) corrected.put(id, note);
    }

    static boolean suppressed(List<Note> suppressed, Note slot, double start, double tolerance) {
        return suppressed.stream().anyMatch(note -> note.part() == slot.part() && note.pitch() == slot.pitch()
                && Math.abs(note.start() - start) <= tolerance);
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
