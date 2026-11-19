package com.opensmpsdeck.headless.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.function.BiPredicate;

final class BenchMetrics {
    record Event(String lane, double start, double end, int pitch, String hash, BenchDrums.Drum drum) {}
    record Pair(int truth, int estimate) {}
    record F1(int truth, int estimate, int matched) {
        double precision() { return estimate == 0 ? (truth == 0 ? 1 : 0) : (double) matched / estimate; }
        double recall() { return truth == 0 ? (estimate == 0 ? 1 : 0) : (double) matched / truth; }
        double f1() { return truth + estimate == 0 ? 1 : 2.0 * matched / (truth + estimate); }
    }

    private static final double EPSILON = 1e-9;
    private static final double BAR = 2;
    private static final double SUSTAINED = .100;

    private BenchMetrics() {}

    static List<Event> events(BenchTimeline.Truth truth, BenchDrums drums, boolean nativeSlots) {
        var result = new ArrayList<Event>();
        for (var channel : truth.channels()) {
            boolean noise = channel.kind().equals("noise");
            for (var note : channel.notes()) {
                result.add(new Event(channel.kind(), note.start(), note.end(), note.pitch(), note.voiceHash(),
                        noise ? BenchDrums.Drum.HAT : null));
            }
            for (var hit : channel.hits()) {
                result.add(new Event(channel.kind(), hit.time(), hit.time(), 0, hit.sampleHash(),
                        drums.classify(hit.sampleHash(), nativeSlots ? hit.slot() : -1)));
            }
        }
        return result.stream().sorted(Comparator.comparingDouble(Event::start)
                .thenComparing(Event::lane).thenComparingInt(Event::pitch)).toList();
    }

    static List<Pair> notes(List<Event> truth, List<Event> estimate, boolean offset) {
        return match(truth, estimate, .050, (a, b) -> a.pitch == b.pitch && (!offset
                || Math.abs(a.end - b.end) <= Math.max(.050, .2 * (a.end - a.start)) + EPSILON));
    }

    static List<Pair> attacks(List<Event> truth, List<Event> estimate, double tolerance) {
        return match(truth, estimate, tolerance, (a, b) -> true);
    }

    // Maximum-cardinality bipartite matching. Onset proximity orders equal-cardinality
    // alternatives deterministically without a greedy assignment losing true positives.
    private static List<Pair> match(List<Event> truth, List<Event> estimate, double tolerance,
                                    BiPredicate<Event, Event> eligible) {
        var edges = new ArrayList<List<Integer>>(truth.size());
        var sorted = java.util.stream.IntStream.range(0, estimate.size()).boxed()
                .sorted(Comparator.comparingDouble(i -> estimate.get(i).start)).toList();
        for (Event expected : truth) {
            var neighbors = new ArrayList<Integer>();
            int lo = 0, hi = sorted.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (estimate.get(sorted.get(mid)).start < expected.start - tolerance - EPSILON) lo = mid + 1;
                else hi = mid;
            }
            for (int k = lo; k < sorted.size(); k++) {
                int i = sorted.get(k);
                Event actual = estimate.get(i);
                if (actual.start > expected.start + tolerance + EPSILON) break;
                if (eligible.test(expected, actual)) neighbors.add(i);
            }
            neighbors.sort(Comparator.comparingDouble((Integer i) -> Math.abs(estimate.get(i).start - expected.start))
                    .thenComparingInt(Integer::intValue));
            edges.add(neighbors);
        }
        int[] owner = new int[estimate.size()], seen = new int[estimate.size()];
        Arrays.fill(owner, -1);
        for (int i = 0; i < truth.size(); i++) augment(i, i + 1, edges, owner, seen);
        var pairs = new ArrayList<Pair>();
        for (int i = 0; i < owner.length; i++) {
            if (owner[i] >= 0) pairs.add(new Pair(owner[i], i));
        }
        pairs.sort(Comparator.comparingInt(Pair::truth));
        return pairs;
    }

    private static boolean augment(int truth, int pass, List<List<Integer>> edges, int[] owner, int[] seen) {
        for (int estimate : edges.get(truth)) {
            if (seen[estimate] == pass) continue;
            seen[estimate] = pass;
            if (owner[estimate] == -1 || augment(owner[estimate], pass, edges, owner, seen)) {
                owner[estimate] = truth;
                return true;
            }
        }
        return false;
    }

    static List<Event> melody(List<Event> notes) {
        var bars = new TreeMap<Integer, TreeMap<String, Register>>();
        for (var note : notes) {
            int first = (int) (note.start / BAR);
            int last = (int) (Math.nextDown(note.end) / BAR);
            for (int bar = first; bar <= last; bar++) {
                double duration = Math.min(note.end, (bar + 1) * BAR) - Math.max(note.start, bar * BAR);
                if (duration <= 0) continue;
                bars.computeIfAbsent(bar, ignored -> new TreeMap<>())
                        .computeIfAbsent(note.lane, ignored -> new Register()).add(note.pitch, duration);
            }
        }
        var chosen = new TreeMap<Integer, String>();
        bars.forEach((bar, lanes) -> {
            boolean sustained = lanes.values().stream().anyMatch(r -> r.sustainedDuration > 0);
            String lane = lanes.entrySet().stream()
                    .filter(entry -> !sustained || entry.getValue().sustainedDuration > 0)
                    .max(Comparator.comparingDouble(entry -> entry.getValue().pitch(sustained)))
                    .orElseThrow().getKey();
            chosen.put(bar, lane);
        });
        return notes.stream().filter(note -> note.lane.equals(chosen.get((int) (note.start / BAR)))).toList();
    }

    private static final class Register {
        double duration, weightedPitch, sustainedDuration, sustainedPitch;

        void add(int pitch, double seconds) {
            duration += seconds;
            weightedPitch += seconds * pitch;
            if (seconds + EPSILON >= SUSTAINED) {
                sustainedDuration += seconds;
                sustainedPitch += seconds * pitch;
            }
        }

        double pitch(boolean sustained) {
            return sustained ? sustainedPitch / sustainedDuration : weightedPitch / duration;
        }
    }
}
