package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Repeat evidence on whole phrases, separate from clip-link eligibility. */
final class ReconstructionPhrases {
    record Instance(List<String> bars) {}
    record Group(String id, List<Instance> instances, double similarity, double melodySimilarity,
                 double partSimilarity, @ReconstructionStages.Nullable Double audioSimilarity) {}
    record Window(int index, List<ReconstructionStructure.Bar> bars, List<Note> notes,
                  Line melody, Map<Integer, Line> parts, double tolerance) {
        double start() { return bars.getFirst().start(); }
        double length() { return bars.getLast().end() - start(); }
        double relative(Note note) { return (note.start() - start()) / length(); }
        String barAt(double time) {
            return bars.stream().filter(bar -> time < bar.end()).findFirst().orElseThrow().id();
        }
    }
    record Match(Group evidence, List<Window> windows) {}
    private record Evidence(boolean accepted, double melody, double part, Double audio) {}
    private record Line(Map<Integer, List<Note>> pitches, int size, int diversity, double span) {}
    record Connection(Note first, Note second) {}
    private record Alignment(double score, List<Connection> links) {}

    private ReconstructionPhrases() {}

    static List<Match> find(List<ReconstructionStructure.Bar> bars, Map<String, List<Note>> byBar,
                            java.util.Set<String> lead, ReconstructionRhythmAudio audio, int meter,
                            DoubleUnaryOperator toSource) {
        var result = new ArrayList<Match>();
        for (int length : new int[]{4, 2, 1}) {
            var windows = new ArrayList<Window>();
            for (int start = 0; start + length <= bars.size(); start++) {
                var range = bars.subList(start, start + length);
                if (range.stream().anyMatch(bar -> !bar.complete())) continue;
                var notes = range.stream().flatMap(bar -> byBar.get(bar.id()).stream()).toList();
                if (notes.size() < 4) continue;
                var parts = new TreeMap<Integer, List<Note>>();
                for (var note : notes) parts.computeIfAbsent(note.part(), ignored -> new ArrayList<>()).add(note);
                var lines = new LinkedHashMap<Integer, Line>();
                parts.forEach((part, attacks) -> lines.put(part, line(attacks)));
                var melody = line(notes.stream().filter(note -> lead.contains(note.id())).toList());
                windows.add(new Window(start, range, notes, melody, lines, .70 / (meter * 4 * length)));
            }
            var assigned = new java.util.HashSet<Integer>();
            for (int i = 0; i < windows.size(); i++) {
                var anchor = windows.get(i);
                if (assigned.contains(anchor.index)) continue;
                var group = new ArrayList<>(List.of(anchor));
                double minimum = 1, melodic = 1, part = 1;
                Double acoustic = null;
                for (int j = i + 1; j < windows.size(); j++) {
                    var target = windows.get(j);
                    if (target.index < group.getLast().index + length || assigned.contains(target.index)) continue;
                    double score = 1, leadScore = 1, partScore = 1;
                    boolean accepted = true;
                    Double audioScore = null;
                    for (var member : group) {
                        var evidence = compare(member, target, audio, meter * length, toSource);
                        if (!evidence.accepted) {
                            accepted = false;
                            break;
                        }
                        score = Math.min(score, Math.max(evidence.melody, evidence.part));
                        leadScore = Math.min(leadScore, evidence.melody);
                        partScore = Math.min(partScore, evidence.part);
                        if (evidence.audio != null)
                            audioScore = audioScore == null ? evidence.audio : Math.min(audioScore, evidence.audio);
                    }
                    if (!accepted) continue;
                    group.add(target);
                    minimum = Math.min(minimum, score);
                    melodic = Math.min(melodic, leadScore);
                    part = Math.min(part, partScore);
                    if (audioScore != null) acoustic = acoustic == null ? audioScore : Math.min(acoustic, audioScore);
                }
                if (group.size() < 2) continue;
                group.forEach(w -> assigned.add(w.index));
                var instances = group.stream().map(w -> new Instance(w.bars.stream()
                        .map(ReconstructionStructure.Bar::id).toList())).toList();
                result.add(new Match(new Group("phrase-" + length + "-" + anchor.bars.getFirst().id(),
                        instances, minimum, melodic, part, acoustic), group));
            }
        }
        return result;
    }

    private static Evidence compare(Window a, Window b, ReconstructionRhythmAudio audio, int beats,
                                    DoubleUnaryOperator toSource) {
        double melody = similarity(a, b, a.melody, b.melody);
        double part = 0;
        for (var entry : a.parts.entrySet()) {
            var other = b.parts.get(entry.getKey());
            if (other != null) part = Math.max(part, similarity(a, b, entry.getValue(), other));
        }
        double best = Math.max(melody, part);
        double minimum = a.bars.size() > 1 ? .70 : .75;
        if (best < (audio == null ? minimum : .42)) return new Evidence(false, melody, part, null);
        Double acoustic = audio == null ? null : audio.similarity(a.start(), b.start(), a.length(), b.length(), beats, toSource);
        boolean accepted = best >= minimum;
        if (acoustic != null) {
            accepted = (accepted && acoustic >= .52) || (acoustic >= .86 && best >= (a.bars.size() > 1 ? .42 : .51));
        }
        return new Evidence(accepted, melody, part, acoustic);
    }

    private static double similarity(Window a, Window b, Line left, Line right) {
        int denominator = Math.max(left.size, right.size);
        if (Math.min(left.size, right.size) < 4 || Math.min(left.diversity, right.diversity) < 3
                || Math.min(left.span / a.length(), right.span / b.length()) < .28) return 0;
        double score = 0;
        double tolerance = Math.max(a.tolerance, b.tolerance);
        for (var entry : left.pitches.entrySet()) {
            var other = right.pitches.get(entry.getKey());
            if (other != null) score += align(a, b, entry.getValue(), other, tolerance, false).score;
        }
        return score / denominator;
    }

    /** Align each exact part/pitch independently for voting, even if detection used folded pitch. */
    static List<Connection> votingAlignment(Window a, Window b, List<Note> left, List<Note> right) {
        var links = new ArrayList<Connection>();
        var byFirst = byPartPitch(left);
        var bySecond = byPartPitch(right);
        for (var entry : byFirst.entrySet()) {
            var other = bySecond.get(entry.getKey());
            if (other != null)
                links.addAll(align(a, b, entry.getValue(), other, Math.max(a.tolerance, b.tolerance), true).links);
        }
        return links;
    }

    static double partSimilarity(Window a, Window b, int part) {
        var left = a.parts.get(part);
        var right = b.parts.get(part);
        return left == null || right == null ? 0 : similarity(a, b, left, right);
    }

    private static Map<Integer, List<Note>> byPartPitch(List<Note> notes) {
        var result = new LinkedHashMap<Integer, List<Note>>();
        for (var note : notes) result.computeIfAbsent(note.part() * 128 + note.pitch(), ignored -> new ArrayList<>()).add(note);
        return result;
    }

    private static Line line(List<Note> notes) {
        var pitches = new HashMap<Integer, List<Note>>();
        int size = 0;
        for (var note : notes) {
            var group = pitches.computeIfAbsent(note.pitch() % 12, ignored -> new ArrayList<>());
            if (!group.isEmpty() && note.start() - group.getLast().start() <= .020) continue;
            group.add(note);
            size++;
        }
        return new Line(pitches, size, pitches.size(), notes.isEmpty() ? 0 : notes.getLast().start() - notes.getFirst().start());
    }

    private static Alignment align(Window a, Window b, List<Note> left, List<Note> right,
                                   double tolerance, boolean trace) {
        // Weighted longest common subsequence. Partitioning by pitch preserves chord
        // matches when jitter changes attack order within a chord.
        int rows = left.size(), columns = right.size();
        var previous = new double[columns + 1];
        var current = new double[columns + 1];
        byte[][] path = trace ? new byte[rows + 1][columns + 1] : null;
        for (int i = 1; i <= rows; i++) {
            double first = a.relative(left.get(i - 1));
            for (int j = 1; j <= columns; j++) {
                double error = Math.abs(first - b.relative(right.get(j - 1)));
                double match = error <= tolerance ? previous[j - 1] + 1 - .20 * error / tolerance : -1;
                byte step = 0;
                double best = previous[j];
                if (current[j - 1] > best) {
                    step = 1;
                    best = current[j - 1];
                }
                if (match >= best) {
                    step = 2;
                    best = match;
                }
                current[j] = best;
                if (path != null) path[i][j] = step;
            }
            var recycled = previous;
            previous = current;
            current = recycled;
        }
        if (path == null) return new Alignment(previous[columns], List.of());
        var links = new ArrayList<Connection>();
        for (int i = rows, j = columns; i > 0 && j > 0;) {
            switch (path[i][j]) {
                case 0 -> i--;
                case 1 -> j--;
                default -> links.add(new Connection(left.get(--i), right.get(--j)));
            }
        }
        links.sort(Comparator.comparingDouble(link -> link.first.start()));
        return new Alignment(previous[columns], links);
    }
}
