package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.ReconstructionAttackGrid;
import com.opensmpsdeck.project.TargetClock;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class BenchRolePlan {
    enum Mode { ORACLE, ORACLE_SECTION }

    // The native melody measurement selects a lane on this proxy grid.
    private static final double PROXY_BAR_SECONDS = 2;
    private record Score(Source source) {}
    private record Source(List<BenchReconstruction.Part> parts) {}
    private record Structure(List<Bar> bars, List<Section> sections, List<Note> notes) {}
    private record Bar(String id, double start, double end) {}
    private record Section(String id, List<String> bars) {}
    private record Note(String id, int part, double start, double end, int pitch) {
        BenchMetrics.Event event() {
            return new BenchMetrics.Event(Integer.toString(part), start, end, pitch, null, null);
        }
    }
    private record Span(double start, double end) {}
    private record Band(int part, String role, Integer pitchMin, Integer pitchMax) {}
    private record Rule(List<String> sections, int part, String role, String voice,
                        Integer pitchMin, Integer pitchMax, Double startSeconds, Double endSeconds, String texture) {}
    private record Plan(int version, List<Rule> rules) {}
    private record Input(Structure structure, Map<String, Bar> bars, List<Note> notes,
                         Set<BenchMetrics.Event> leadTruth, Set<Integer> lead, List<Integer> unassigned,
                         List<BenchMetrics.Event> bassAttacks) {
        Set<Integer> bassIn(List<BenchMetrics.Event> truth, double start, double end) {
            return map(bass(truth, leadTruth, start, end), bassAttacks).stream().map(unassigned::get)
                    .collect(Collectors.toSet());
        }
    }
    private record Allocation(List<Placement> notes) {}
    private record Placement(String lane, String role, Integer startTick, Integer endTick, Note note) {}

    private BenchRolePlan() {}

    static void write(Path work, List<BenchMetrics.Event> truth, Mode mode, BenchCli.Reconstruction run,
                      PrintStream out, TargetClock clock) throws IOException {
        var melody = BenchMetrics.melody(truth);
        Path path = work.resolve("roleplan.json");
        if (mode == Mode.ORACLE_SECTION) {
            BenchFiles.write(path, new Plan(1, deriveSection(work, truth, melody)));
            return;
        }
        var rules = derive(work, truth, melody);
        // Test the proposed line splits against the hardware lane budget. A native
        // lane can sustain while the nominated FM1 line changes part, and forcing
        // that handoff can lose attacks. Leave such passages to the heuristic if
        // the whole plan provides no gain on the allocated note timeline.
        List<Rule> best = List.of();
        double bestFit = fit(work, melody, run, out, clock);
        var leadOnly = rules.stream().filter(rule -> rule.role.equals("melody")).toList();
        for (var candidate : List.of(rules, leadOnly)) {
            if (candidate.isEmpty() || candidate.equals(best)) continue;
            BenchFiles.write(path, new Plan(1, candidate));
            double fit = fit(work, melody, run, out, clock);
            if (fit > bestFit) {
                bestFit = fit;
                best = candidate;
            }
        }
        BenchFiles.write(path, new Plan(1, best));
    }

    private static double fit(Path work, List<BenchMetrics.Event> melody, BenchCli.Reconstruction run,
                              PrintStream out, TargetClock clock) throws IOException {
        run.run(new String[]{"reconstruct", "roles", work.toString()}, out);
        run.run(new String[]{"reconstruct", "allocate", work.toString()}, out);
        var allocation = BenchFiles.read(work.resolve("allocation.json"), Allocation.class);
        var notes = allocation.notes.stream().filter(note -> note.lane != null && !note.role.equals("drums"))
                .map(note -> new BenchMetrics.Event(note.lane, clock.secondsAtTick(note.startTick), clock.secondsAtTick(note.endTick),
                        note.note.pitch, null, null)).toList();
        var estimate = BenchMetrics.melody(notes);
        return new BenchMetrics.F1(melody.size(), estimate.size(), BenchMetrics.notes(melody, estimate, false).size()).f1();
    }

    private static List<Rule> derive(Path work, List<BenchMetrics.Event> truth, List<BenchMetrics.Event> melody)
            throws IOException {
        var input = prepare(work, truth, melody);
        var rules = new ArrayList<Rule>();
        int next = 0;
        for (var section : input.structure.sections) {
            double start = input.bars.get(section.bars.getFirst()).start;
            double end = input.bars.get(section.bars.getLast()).end;
            var bass = input.bassIn(truth, start, end);
            var spans = new LinkedHashMap<Band, List<Span>>();
            for (double from = start; from < end;) {
                double to = Math.min(end, (1 + Math.floor(from / PROXY_BAR_SECONDS)) * PROXY_BAR_SECONDS);
                var counts = new TreeMap<Integer, TreeMap<Integer, int[]>>();
                while (next < input.notes.size() && input.notes.get(next).start < to) {
                    var note = input.notes.get(next);
                    int role = input.lead.contains(next) ? 0 : bass.contains(next) ? 1 : 2;
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
                            first.start == start ? null : first.start, to == end ? null : to, null));
                }
            });
        }
        return List.copyOf(rules);
    }

    private static List<Rule> deriveSection(Path work, List<BenchMetrics.Event> truth,
                                            List<BenchMetrics.Event> melody) throws IOException {
        var input = prepare(work, truth, melody);
        var chords = ReconstructionAttackGrid.chordNotes(work);
        var rules = new ArrayList<Rule>();
        int next = 0;
        for (var section : input.structure.sections) {
            double start = input.bars.get(section.bars.getFirst()).start;
            double end = input.bars.get(section.bars.getLast()).end;
            var low = input.bassIn(truth, start, end);
            var parts = new TreeMap<Integer, List<Integer>>();
            while (next < input.notes.size() && input.notes.get(next).start < end) {
                parts.computeIfAbsent(input.notes.get(next).part, ignored -> new ArrayList<>()).add(next);
                next++;
            }
            parts.forEach((part, indices) -> {
                addSectionRule(rules, section, part, indices, input.notes, low, chords, false);
                addSectionRule(rules, section, part, indices, input.notes, input.lead, chords, true);
            });
        }
        return List.copyOf(rules);
    }

    private static Input prepare(Path work, List<BenchMetrics.Event> truth, List<BenchMetrics.Event> melody)
            throws IOException {
        var score = BenchFiles.read(work.resolve("score.json"), Score.class);
        var structure = BenchFiles.read(work.resolve("structure.json"), Structure.class);
        var drums = score.source.parts.stream().filter(BenchReconstruction.Part::drums)
                .map(BenchReconstruction.Part::id).collect(Collectors.toSet());
        var input = structure.notes.stream().filter(note -> !drums.contains(note.part)).toList();
        var attacks = input.stream().map(Note::event).toList();
        var leadTruth = Set.copyOf(melody);
        var lead = mapMelody(truth, leadTruth, attacks);
        var unassigned = IntStream.range(0, input.size()).filter(i -> !lead.contains(i)).boxed().toList();
        var bars = new HashMap<String, Bar>();
        for (var bar : structure.bars) bars.put(bar.id, bar);
        return new Input(structure, bars, input, leadTruth, lead, unassigned,
                unassigned.stream().map(attacks::get).toList());
    }

    // Keep all mapped attacks eligible. Use a texture or register edge only when
    // it excludes accompaniment by inspection of the labeled input attacks.
    private static void addSectionRule(List<Rule> rules, Section section, int part, List<Integer> indices,
                                       List<Note> input, Set<Integer> wanted, Set<String> chords, boolean lead) {
        var target = indices.stream().filter(wanted::contains).map(input::get).toList();
        if (target.isEmpty()) return;
        var other = indices.stream().filter(i -> !wanted.contains(i)).map(input::get).toList();
        boolean chord = chords.contains(target.getFirst().id);
        String texture = null;
        if (target.stream().allMatch(note -> chords.contains(note.id) == chord)
                && other.stream().anyMatch(note -> chords.contains(note.id) != chord)) {
            texture = chord ? "chord" : "single";
            other = other.stream().filter(note -> chords.contains(note.id) == chord).toList();
        }
        int edge = lead ? target.stream().mapToInt(Note::pitch).min().orElseThrow()
                : target.stream().mapToInt(Note::pitch).max().orElseThrow();
        boolean useBound = other.stream().anyMatch(note -> outside(note, edge, lead));
        boolean trace = other.stream().anyMatch(note -> !outside(note, edge, lead));
        String voice = trace ? (lead ? "top" : "bottom") : "all";
        rules.add(new Rule(List.of(section.id), part, lead ? "melody" : "bass", voice,
                useBound && lead ? edge : null, useBound && !lead ? edge : null, null, null, texture));
    }

    private static boolean outside(Note note, int edge, boolean lead) {
        return lead ? note.pitch < edge : note.pitch > edge;
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
