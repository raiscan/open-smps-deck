package com.opensmpsdeck.headless.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class BenchRolePlan {
    // The native melody measurement selects a lane on this proxy grid.
    private static final double PROXY_BAR_SECONDS = 2;
    private record Score(Source source) {}
    private record Source(List<BenchReconstruction.Part> parts) {}
    private record Structure(List<Bar> bars, List<Section> sections, List<Note> notes) {}
    private record Bar(String id, double start, double end) {}
    private record Section(String id, List<String> bars) {}
    private record Note(int part, double start, double end, int pitch) {
        BenchMetrics.Event event() {
            return new BenchMetrics.Event(Integer.toString(part), start, end, pitch, null, null);
        }
    }
    private record Span(double start, double end) {}
    private record Band(int part, String role, Integer pitchMin, Integer pitchMax) {}
    private record Rule(List<String> sections, int part, String role, String voice,
                        Integer pitchMin, Integer pitchMax, Double startSeconds, Double endSeconds) {}
    private record Plan(int version, List<Rule> rules) {}
    private record Allocation(List<Placement> notes) {}
    private record Placement(String lane, String role, Integer startTick, Integer endTick, Note note) {}

    private BenchRolePlan() {}

    static void write(Path work, List<BenchMetrics.Event> truth, BenchCli.Reconstruction run, PrintStream out)
            throws IOException {
        var melody = BenchMetrics.melody(truth);
        var rules = derive(work, truth, melody);
        Path path = work.resolve("roleplan.json");
        // Test the proposed line splits against the hardware lane budget. A native
        // lane can sustain while the nominated FM1 line changes part, and forcing
        // that handoff can lose attacks. Leave such passages to the heuristic if
        // the whole plan provides no gain on the allocated note timeline.
        List<Rule> best = List.of();
        double bestFit = fit(work, melody, run, out);
        var leadOnly = rules.stream().filter(rule -> rule.role.equals("melody")).toList();
        for (var candidate : List.of(rules, leadOnly)) {
            if (candidate.isEmpty() || candidate.equals(best)) continue;
            BenchFiles.write(path, new Plan(1, candidate));
            double fit = fit(work, melody, run, out);
            if (fit > bestFit) {
                bestFit = fit;
                best = candidate;
            }
        }
        BenchFiles.write(path, new Plan(1, best));
    }

    private static double fit(Path work, List<BenchMetrics.Event> melody, BenchCli.Reconstruction run, PrintStream out)
            throws IOException {
        run.run(new String[]{"reconstruct", "roles", work.toString()}, out);
        run.run(new String[]{"reconstruct", "allocate", work.toString()}, out);
        var allocation = BenchFiles.read(work.resolve("allocation.json"), Allocation.class);
        var notes = allocation.notes.stream().filter(note -> note.lane != null && !note.role.equals("drums"))
                .map(note -> new BenchMetrics.Event(note.lane, note.startTick / 60.0, note.endTick / 60.0,
                        note.note.pitch, null, null)).toList();
        var estimate = BenchMetrics.melody(notes);
        return new BenchMetrics.F1(melody.size(), estimate.size(), BenchMetrics.notes(melody, estimate, false).size()).f1();
    }

    private static List<Rule> derive(Path work, List<BenchMetrics.Event> truth, List<BenchMetrics.Event> melody)
            throws IOException {
        var score = BenchFiles.read(work.resolve("score.json"), Score.class);
        var structure = BenchFiles.read(work.resolve("structure.json"), Structure.class);
        Set<Integer> drums = score.source.parts.stream().filter(BenchReconstruction.Part::drums)
                .map(BenchReconstruction.Part::id).collect(Collectors.toSet());
        var input = structure.notes.stream().filter(note -> !drums.contains(note.part)).toList();
        var attacks = input.stream().map(Note::event).toList();
        var leadTruth = Set.copyOf(melody);
        var lead = mapMelody(truth, leadTruth, attacks);
        var unassigned = IntStream.range(0, input.size()).filter(i -> !lead.contains(i)).boxed().toList();
        var bassInput = unassigned.stream().map(attacks::get).toList();
        var bars = new HashMap<String, Bar>();
        for (var bar : structure.bars) bars.put(bar.id, bar);

        var rules = new ArrayList<Rule>();
        int next = 0;
        for (var section : structure.sections) {
            double start = bars.get(section.bars.getFirst()).start;
            double end = bars.get(section.bars.getLast()).end;
            var bass = map(bass(truth, leadTruth, start, end), bassInput).stream().map(unassigned::get)
                    .collect(Collectors.toSet());
            var spans = new LinkedHashMap<Band, List<Span>>();
            for (double from = start; from < end;) {
                double to = Math.min(end, (1 + Math.floor(from / PROXY_BAR_SECONDS)) * PROXY_BAR_SECONDS);
                var counts = new TreeMap<Integer, TreeMap<Integer, int[]>>();
                while (next < input.size() && input.get(next).start < to) {
                    var note = input.get(next);
                    int role = lead.contains(next) ? 0 : bass.contains(next) ? 1 : 2;
                    counts.computeIfAbsent(note.part, ignored -> new TreeMap<>())
                            .computeIfAbsent(note.pitch, ignored -> new int[3])[role]++;
                    next++;
                }
                var window = new Span(from, to);
                counts.forEach((part, tally) -> {
                    for (var band : bands(part, tally))
                        spans.computeIfAbsent(band, ignored -> new ArrayList<>()).add(window);
                });
                from = to;
            }
            spans.forEach((band, windows) -> {
                for (int i = 0; i < windows.size();) {
                    var first = windows.get(i++);
                    double to = first.end;
                    while (i < windows.size() && windows.get(i).start == to) to = windows.get(i++).end;
                    rules.add(new Rule(List.of(section.id), band.part, band.role, "all", band.pitchMin, band.pitchMax,
                            first.start == start ? null : first.start, to == end ? null : to));
                }
            });
        }
        return List.copyOf(rules);
    }

    private static Set<Integer> mapMelody(List<BenchMetrics.Event> truth, Set<BenchMetrics.Event> melody,
                                          List<BenchMetrics.Event> input) {
        var lanes = truth.stream().collect(Collectors.groupingBy(BenchMetrics.Event::lane, TreeMap::new,
                Collectors.toList()));
        var parts = IntStream.range(0, input.size()).boxed().collect(Collectors.groupingBy(
                i -> input.get(i).lane(), TreeMap::new, Collectors.toList()));
        var found = new java.util.HashSet<Integer>();
        var unmatched = new ArrayList<BenchMetrics.Event>();
        for (var line : lanes.values()) {
            List<BenchMetrics.Event> lead = line.stream().filter(melody::contains).toList();
            if (lead.isEmpty()) continue;
            List<Integer> best = null;
            double floor = .90;
            for (var part : parts.values()) {
                var attacks = part.stream().map(input::get).toList();
                double overlap = new BenchMetrics.F1(line.size(), part.size(),
                        BenchMetrics.notes(line, attacks, false).size()).f1();
                if (overlap >= floor) {
                    floor = overlap;
                    best = part;
                }
            }
            if (best == null) {
                unmatched.addAll(lead);
            } else {
                var part = best.stream().filter(i -> !found.contains(i)).toList();
                for (int i : map(lead, part.stream().map(input::get).toList())) found.add(part.get(i));
            }
        }
        var rest = IntStream.range(0, input.size()).filter(i -> !found.contains(i)).boxed().toList();
        for (int i : map(unmatched, rest.stream().map(input::get).toList())) found.add(rest.get(i));
        return found;
    }

    private static Set<Integer> map(List<BenchMetrics.Event> truth, List<BenchMetrics.Event> input) {
        return BenchMetrics.notes(truth, input, false).stream()
                .map(BenchMetrics.Pair::estimate).collect(Collectors.toSet());
    }

    private static List<BenchMetrics.Event> bass(List<BenchMetrics.Event> truth, Set<BenchMetrics.Event> lead,
                                                  double start, double end) {
        var lanes = new TreeMap<String, List<BenchMetrics.Event>>();
        for (var note : truth) {
            if (note.start() >= start && note.start() < end && !lead.contains(note))
                lanes.computeIfAbsent(note.lane(), ignored -> new ArrayList<>()).add(note);
        }
        return lanes.values().stream().min(Comparator.comparingDouble(BenchRolePlan::register)).orElse(List.of());
    }

    private static double register(List<BenchMetrics.Event> notes) {
        double total = 0, pitch = 0;
        for (var note : notes) {
            double duration = Math.max(.001, note.end() - note.start());
            total += duration;
            pitch += duration * note.pitch();
        }
        return pitch / total;
    }

    // A merged part may carry multiple truth lanes. Group pitches that predominantly
    // overlap the same truth line, splitting the band wherever the evidence switches.
    private static List<Band> bands(int part, TreeMap<Integer, int[]> counts) {
        var result = new ArrayList<Band>();
        String role = null;
        int first = 0, last = 0;
        for (var entry : counts.entrySet()) {
            int[] count = entry.getValue();
            String next = count[0] > 0 && count[0] >= count[1] && count[0] >= count[2] ? "melody"
                    : count[1] > 0 && count[1] >= count[2] ? "bass" : null;
            if (!java.util.Objects.equals(role, next)) {
                if (role != null) result.add(new Band(part, role, first, last));
                role = next;
                first = entry.getKey();
            }
            last = entry.getKey();
        }
        if (role != null) result.add(new Band(part, role, first, last));
        if (result.size() == 1 && result.getFirst().pitchMin == counts.firstKey().intValue()
                && result.getFirst().pitchMax == counts.lastKey().intValue())
            return List.of(new Band(part, result.getFirst().role, null, null));
        return result;
    }
}
