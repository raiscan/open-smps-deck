package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.bench.BenchMetrics.Event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class BenchLeadFidelity {
    private static final int FRAMES_PER_SECOND = 60;
    private static final int MAX_LAG = 20;
    private static final int MIN_ATTACKS = 5;
    private static final double MIN_COVERAGE = .8;
    private static final double ONSET_TOLERANCE = .050;
    private static final double EPSILON = 1e-9;

    private record Attack(int pitch, long frame) {}
    private record Pattern(double coverage, int exact, int lag, int shift, Map<Event, Event> copies) {}
    private static final Comparator<Pattern> SUPPORT = Comparator.comparingDouble(Pattern::coverage)
            .thenComparingInt(Pattern::exact).thenComparingInt(p -> -Math.abs(p.lag))
            .thenComparingInt(p -> -Math.abs(p.shift));

    private BenchLeadFidelity() {}

    static BenchMetrics.F1 score(List<Event> truth, List<Event> estimate, String leadLane) {
        var lead = truth.stream().filter(note -> note.lane().equals(leadLane)).toList();
        var fm1 = estimate.stream().filter(note -> note.lane().equals("FM1")).toList();
        var direct = BenchMetrics.notes(lead, fm1, false);
        int matched = direct.size();
        if (matched < lead.size() && matched < fm1.size()) {
            var missed = unmatched(lead, direct.stream().map(BenchMetrics.Pair::truth).collect(Collectors.toSet()));
            var extra = unmatched(fm1, direct.stream().map(BenchMetrics.Pair::estimate).collect(Collectors.toSet()));
            var aliases = aliases(truth, lead, missed, leadLane);
            // Copies can lie outside the ordinary onset window relative to the original attack.
            double window = (MAX_LAG + 2.0) / FRAMES_PER_SECOND + ONSET_TOLERANCE;
            matched += BenchMetrics.match(missed, extra, window,
                    (a, b) -> aliases.getOrDefault(a, List.of()).stream().anyMatch(copy ->
                            copy.pitch() == b.pitch() && Math.abs(copy.start() - b.start()) <= ONSET_TOLERANCE + EPSILON))
                    .size();
        }
        return new BenchMetrics.F1(lead.size(), fm1.size(), matched);
    }

    private static List<Event> unmatched(List<Event> events, Set<Integer> matched) {
        var remaining = new ArrayList<Event>();
        for (int i = 0; i < events.size(); i++) {
            if (!matched.contains(i)) remaining.add(events.get(i));
        }
        return remaining;
    }

    private static Map<Event, List<Event>> aliases(List<Event> truth, List<Event> lead, List<Event> missed,
                                                   String leadLane) {
        var bins = lead.stream().collect(Collectors.groupingBy(BenchLeadFidelity::bin));
        var candidates = truth.stream().filter(note -> !note.lane().equals(leadLane))
                .collect(Collectors.groupingBy(Event::lane));
        var needed = missed.stream().map(BenchLeadFidelity::bin).collect(Collectors.toSet());
        var aliases = new HashMap<Event, List<Event>>();
        for (var lane : candidates.values()) {
            var counts = lane.stream().collect(Collectors.groupingBy(BenchLeadFidelity::bin, Collectors.counting()));
            var attacks = lane.stream().collect(Collectors.groupingBy(note -> new Attack(note.pitch(), frame(note))));
            for (int bin : needed) {
                var originals = bins.get(bin);
                long count = counts.getOrDefault(bin, 0L);
                if (Math.min(count, originals.size()) < MIN_ATTACKS) continue;
                var best = bestPattern(originals, attacks, bin, count);
                if (best != null) best.copies.forEach((note, copy) ->
                        aliases.computeIfAbsent(note, ignored -> new ArrayList<>()).add(copy));
            }
        }
        return aliases;
    }

    private static Pattern bestPattern(List<Event> lead, Map<Attack, List<Event>> attacks, int bin, long count) {
        Pattern best = null;
        for (int shift : new int[]{-12, 0, 12}) {
            for (int lag = -MAX_LAG; lag <= MAX_LAG; lag++) {
                if (Math.abs(lag) == 1) continue; // Covered by the one-frame tolerance at zero lag.
                var copies = new HashMap<Event, Event>();
                var used = new HashSet<Event>();
                var pitches = new HashSet<Integer>();
                int exact = 0, inside = 0;
                for (var note : lead) {
                    long center = frame(note) + lag;
                    var copy = findCopy(attacks, used, note.pitch() + shift, center);
                    if (copy == null) continue;
                    copies.put(note, copy);
                    used.add(copy);
                    pitches.add(note.pitch());
                    if (frame(copy) == center) exact++;
                    if (bin(copy) == bin) inside++;
                }
                double coverage = Math.min((double) copies.size() / lead.size(), (double) inside / count);
                if (copies.size() < MIN_ATTACKS || coverage < MIN_COVERAGE
                        || ((lag != 0 || shift != 0) && pitches.size() < 3)) continue;
                var pattern = new Pattern(coverage, exact, lag, shift, copies);
                if (best == null || SUPPORT.compare(pattern, best) > 0) best = pattern;
            }
        }
        return best;
    }

    private static Event findCopy(Map<Attack, List<Event>> attacks, Set<Event> used, int pitch, long center) {
        for (long frame : new long[]{center, center - 1, center + 1}) {
            for (var note : attacks.getOrDefault(new Attack(pitch, frame), List.of())) {
                if (!used.contains(note)) return note;
            }
        }
        return null;
    }

    private static int bin(Event note) {
        return (int) (note.start() / 2);
    }

    private static long frame(Event note) {
        return Math.round(note.start() * FRAMES_PER_SECOND);
    }
}
