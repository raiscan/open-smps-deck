package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionVocalReview.Span;

/** Use agreement between distinct phrase instances as an anchor for uncertain notes. */
final class VocalPhraseRepeats {
    private static final double MATCH_SECONDS = .85;

    record Consensus(int pitch, int votes, int alignedPhrases) {}
    private record Pair(Note first, Note second) {}

    private VocalPhraseRepeats() {}

    static Map<String, Consensus> align(List<Span> spans, List<Note> notes) {
        var phrases = spans.stream().map(span -> notes.stream()
                .filter(note -> note.start() >= span.start() && note.start() < span.end()).toList()).toList();
        var votes = new HashMap<String, Map<Integer, Integer>>();
        for (int i = 0; i < spans.size(); i++) {
            if (phrases.get(i).size() < 5 || spans.get(i).instrumental()) continue;
            for (int j = i + 1; j < spans.size(); j++) {
                if (phrases.get(j).size() < 5 || spans.get(j).instrumental()) continue;
                double length = spans.get(i).end() - spans.get(i).start();
                double otherLength = spans.get(j).end() - spans.get(j).start();
                if (Math.abs(length - otherLength) > .08 * length) continue;
                var pairs = align(spans.get(i), phrases.get(i), spans.get(j), phrases.get(j));
                long matching = pairs.stream().filter(pair -> sameClass(pair.first, pair.second)).count();
                long exact = pairs.stream().filter(pair -> pair.first.pitch() == pair.second.pitch()).count();
                if (matching < Math.max(5, .48 * Math.min(phrases.get(i).size(), phrases.get(j).size()))
                        || exact < .70 * matching) continue;
                for (var pair : pairs) {
                    votes.computeIfAbsent(pair.first.id(), ignored -> new TreeMap<>()).merge(pair.second.pitch(), 1, Integer::sum);
                    votes.computeIfAbsent(pair.second.id(), ignored -> new TreeMap<>()).merge(pair.first.pitch(), 1, Integer::sum);
                }
            }
        }
        var result = new HashMap<String, Consensus>();
        votes.forEach((id, tally) -> {
            int aligned = tally.values().stream().mapToInt(Integer::intValue).sum();
            var winner = tally.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            if (winner.getValue() >= 2 && winner.getValue() * 3 >= aligned * 2)
                result.put(id, new Consensus(winner.getKey(), winner.getValue(), aligned));
        });
        return result;
    }

    private static List<Pair> align(Span a, List<Note> first, Span b, List<Note> second) {
        // Align attacks monotonically. Pitch-class anchors guide the alignment through missing
        // attacks and modest timing differences, leaving a small reward for uncertain notes.
        double scale = (b.end() - b.start()) / (a.end() - a.start());
        int m = first.size(), n = second.size();
        var scores = new double[m + 1][n + 1];
        var path = new byte[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            var note = first.get(i - 1);
            double predicted = b.start() + (note.start() - a.start()) * scale;
            for (int j = 1; j <= n; j++) {
                var candidate = second.get(j - 1);
                double delta = Math.abs(candidate.start() - predicted);
                double reward;
                if (sameClass(note, candidate) && delta <= MATCH_SECONDS) reward = 2 - delta / MATCH_SECONDS;
                else if (Math.abs(note.pitch() - candidate.pitch()) == 1 && delta <= MATCH_SECONDS)
                    reward = .64 - .45 * delta / MATCH_SECONDS;
                else reward = delta < .22 ? .45 - delta : -1;
                double paired = scores[i - 1][j - 1] + reward;
                if (paired > Math.max(scores[i - 1][j], scores[i][j - 1])) {
                    scores[i][j] = paired;
                    path[i][j] = 1;
                } else if (scores[i - 1][j] >= scores[i][j - 1]) scores[i][j] = scores[i - 1][j];
                else {
                    scores[i][j] = scores[i][j - 1];
                    path[i][j] = 2;
                }
            }
        }
        var pairs = new ArrayList<Pair>();
        for (int i = m, j = n; i > 0 && j > 0;) {
            if (path[i][j] == 1) pairs.add(new Pair(first.get(--i), second.get(--j)));
            else if (path[i][j] == 2) j--;
            else i--;
        }
        return pairs;
    }

    private static boolean sameClass(Note first, Note second) {
        return (first.pitch() - second.pitch()) % 12 == 0;
    }
}
