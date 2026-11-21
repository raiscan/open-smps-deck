package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchClock;
import com.opensmpsdeck.headless.LeadGap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;

final class BenchLadderTrace {
    record Note(String id, int part, double start, double end, int pitch) {
        BenchMetrics.Event event(DoubleUnaryOperator clock) {
            return new BenchMetrics.Event(Integer.toString(part), clock.applyAsDouble(start),
                    clock.applyAsDouble(end), pitch, null, null);
        }
    }
    record MidiNote(int partId, int pitch, long startUs, long endUs) {}
    record Reduction(MidiNote note, String change, String id, String reason) {}
    record Score(BenchReconstruction.Midi source, List<Note> notes, List<Reduction> reductions) {}
    record Role(Note note, String role, String reason) {}
    record Roles(List<Role> notes) {}
    record Entry(Note note, String lane, String outcome, String reason, Integer startTick, Integer endTick) {}
    record Allocation(List<Entry> notes) {}
    record Bar(String id, double start, double end) {}
    record Section(String id, List<String> bars) {}
    record Structure(List<Bar> bars, List<Section> sections, List<Note> notes, List<?> repeats, List<?> phrases) {}
    record FinalReport(List<Reduction> reductions, int presentations, Integer echoNotes, Boolean echoBankLimit) {}
    record Example(String lane, int pitch, double second, String reason) {}
    record Loss(int count, Map<String, Integer> reasons, List<Example> examples) {}
    record Waterfall(int truth, int survived, Map<String, Loss> lost) {}
    record Window(String section, double start, double end, double noteF1, double melodyF1, double leadAttackF1,
                  double noteF1WithLayers, double melodyF1WithLayers, double leadAttackF1WithLayers) {}

    private record SectionTones(List<BenchMetrics.Event> all, List<BenchMetrics.Event> melody, List<BenchMetrics.Event> lead) {
        static SectionTones estimate(List<BenchMetrics.Event> events) {
            return new SectionTones(events, BenchMetrics.melody(events),
                    events.stream().filter(note -> note.lane().equals("FM1")).toList());
        }

        SectionTones within(double start, double end) {
            return new SectionTones(BenchLadderTrace.within(all, start, end), BenchLadderTrace.within(melody, start, end),
                    BenchLadderTrace.within(lead, start, end));
        }

        double noteF1(SectionTones estimate) { return pitchF1(all, estimate.all); }
        double melodyF1(SectionTones estimate) { return pitchF1(melody, estimate.melody); }

        double leadAttackF1(SectionTones estimate) {
            return new BenchMetrics.F1(lead.size(), estimate.lead.size(),
                    BenchMetrics.attacks(lead, estimate.lead, .050).size()).f1();
        }

        private static double pitchF1(List<BenchMetrics.Event> expected, List<BenchMetrics.Event> actual) {
            return new BenchMetrics.F1(expected.size(), actual.size(), BenchMetrics.notes(expected, actual, false).size()).f1();
        }
    }
    record Detail(Waterfall allNotes, Waterfall lead, List<Window> sections, int inferredSections,
                  int repeats, int phrases) {}

    private final Score score;
    private final Structure structure;
    private final Map<String, Role> roles;
    private final Map<String, Entry> placed;
    private final Map<String, String> reasons;
    private final Map<String, String> preparedReductions;
    private final Map<String, Note> structured;
    private final DoubleUnaryOperator projectToTruth;
    private final DoubleUnaryOperator inputToTruth;

    private BenchLadderTrace(Path work, DoubleUnaryOperator projectToTruth, DoubleUnaryOperator inputToTruth)
            throws IOException {
        this.projectToTruth = projectToTruth;
        this.inputToTruth = inputToTruth;
        score = BenchFiles.read(work.resolve("score.json"), Score.class);
        structure = BenchFiles.read(work.resolve("structure.json"), Structure.class);
        roles = BenchFiles.read(work.resolve("roles.json"), Roles.class).notes().stream()
                .collect(Collectors.toMap(role -> role.note().id(), role -> role));
        placed = BenchFiles.read(work.resolve("allocation.json"), Allocation.class).notes().stream()
                .collect(Collectors.toMap(entry -> entry.note().id(), entry -> entry));
        reasons = new HashMap<>();
        for (var reduction : BenchFiles.read(work.resolve("report.json"), FinalReport.class).reductions()) {
            if (reduction.id() != null) reasons.put(reduction.id(), reason(reduction.reason(), reduction.change()));
        }
        preparedReductions = new HashMap<>();
        for (var reduction : score.reductions()) {
            var n = reduction.note();
            preparedReductions.put(midiKey(n.partId(), n.pitch(), n.startUs()), reason(reduction.reason(), reduction.change()));
        }
        structured = structure.notes().stream().collect(Collectors.toMap(Note::id, note -> note));
    }

    static Detail read(Path work, List<BenchMetrics.Event> truth, List<BenchMetrics.Event> nativeEvents,
                       List<BenchMetrics.Event> nativeEventsWithLayers, BenchLadder.Input input,
                       Integer leadPart, DoubleUnaryOperator projectToTruth,
                       DoubleUnaryOperator inputToTruth) throws IOException {
        var trace = new BenchLadderTrace(work, projectToTruth, inputToTruth);
        var lead = truth.stream().filter(note -> note.lane().equals(input.leadLane())).toList();
        var assembled = trace.assembled(nativeEvents);
        return new Detail(trace.waterfall(truth, assembled, false, null, input.rung() == BenchLadder.Rung.EXACT),
                trace.waterfall(lead, assembled, true, leadPart, false),
                input.torture() ? trace.sections(truth, lead, nativeEvents, nativeEventsWithLayers) : List.of(),
                trace.structure.sections().size(), trace.structure.repeats().size(),
                trace.structure.phrases() == null ? 0 : trace.structure.phrases().size());
    }

    private java.util.Set<String> assembled(List<BenchMetrics.Event> events) {
        var candidates = placed.values().stream().filter(entry -> entry.lane() != null && entry.startTick() != null
                && !entry.outcome().equals("dropped")).sorted(
                        java.util.Comparator.comparing(entry -> entry.note().id())).toList();
        var expected = candidates.stream().map(entry -> new BenchMetrics.Event(entry.lane(), entry.startTick() / 60.0,
                entry.endTick() / 60.0, entry.note().pitch(), null, null)).toList();
        return BenchMetrics.laneNotes(expected, events, 1.0 / 60).stream()
                .map(pair -> candidates.get(pair.truth()).note().id()).collect(Collectors.toSet());
    }

    private Waterfall waterfall(List<BenchMetrics.Event> truth, java.util.Set<String> assembled, boolean lead,
                               Integer leadPart, boolean exactChannels) {
        var parts = score.source().parts().stream().collect(Collectors.toMap(BenchReconstruction.Part::id, part -> part));
        var raw = score.source().notes().stream().filter(note -> tonalPart(parts, note.partId(), lead, leadPart)).toList();
        var rawEvents = raw.stream().map(note -> new BenchMetrics.Event(parts.get(note.partId()).name(),
                inputToTruth.applyAsDouble(note.startUs() / 1e6), inputToTruth.applyAsDouble(note.endUs() / 1e6),
                note.pitch(), null, null)).toList();
        var prepared = score.notes().stream().filter(note -> tonalPart(parts, note.part(), lead, leadPart)).toList();
        var preparedEvents = prepared.stream().map(note -> new BenchMetrics.Event(parts.get(note.part()).name(),
                inputToTruth.applyAsDouble(note.start()), inputToTruth.applyAsDouble(note.end()),
                note.pitch(), null, null)).toList();
        var preparedIds = prepared.stream().map(Note::id).collect(Collectors.toSet());
        var additions = structure.notes().stream().filter(note -> tonalPart(parts, note.part(), lead, leadPart))
                .filter(note -> !preparedIds.contains(note.id())).toList();
        var rawMatches = byTruth(exactChannels ? BenchMetrics.laneNotes(truth, rawEvents, .050)
                : BenchMetrics.notes(truth, rawEvents, false));
        var preparedMatches = byTruth(exactChannels ? BenchMetrics.laneNotes(truth, preparedEvents, .050)
                : BenchMetrics.notes(truth, preparedEvents, false));
        // Structure may insert a note or replace a prepared ID. Credit a
        // correction only to an unmatched truth attack on the same clock.
        var unmatched = new ArrayList<Integer>();
        for (int i = 0; i < truth.size(); i++) {
            Integer match = preparedMatches.get(i);
            if (match == null || !structured.containsKey(prepared.get(match).id())) unmatched.add(i);
        }
        var addedEvents = additions.stream().map(note -> new BenchMetrics.Event(parts.get(note.part()).name(),
                projectToTruth.applyAsDouble(note.start()), projectToTruth.applyAsDouble(note.end()),
                note.pitch(), null, null)).toList();
        var unpaired = unmatched.stream().map(truth::get).toList();
        var corrected = new HashMap<Integer, Note>();
        for (var pair : exactChannels ? BenchMetrics.laneNotes(unpaired, addedEvents, .050)
                : BenchMetrics.notes(unpaired, addedEvents, false)) {
            corrected.put(unmatched.get(pair.truth()), additions.get(pair.estimate()));
        }

        int survived = 0;
        var losses = new java.util.LinkedHashMap<String, MutableLoss>();
        for (int i = 0; i < truth.size(); i++) {
            var expected = truth.get(i);
            Integer match = preparedMatches.get(i);
            Note note = corrected.get(i);
            if (note == null && match != null) note = prepared.get(match);
            if (note == null) {
                Integer original = rawMatches.get(i);
                String detail = "missing-input-attack";
                if (original != null) {
                    var n = raw.get(original);
                    detail = preparedReductions.getOrDefault(midiKey(n.partId(), n.pitch(), n.startUs()), "missing-prepared-attack");
                }
                add(losses, "transcribe", expected, detail);
                continue;
            }
            if (!structured.containsKey(note.id())) {
                add(losses, "transcribe", expected, "structure-replaced-attack");
                continue;
            }
            var role = roles.get(note.id());
            if (role == null || role.role().equals("drop") || lead && !role.role().equals("melody")) {
                add(losses, "roles", expected, role == null ? "missing-role"
                        : reason(role.reason(), role.role().equals("drop") ? "role-drop" : "role-" + role.role()));
                continue;
            }
            var entry = placed.get(note.id());
            if (entry == null || entry.outcome().equals("dropped") || entry.lane() == null || lead && !entry.lane().equals("FM1")) {
                add(losses, "allocation", expected, entry == null ? "missing-allocation"
                        : reason(entry.reason(), entry.lane() == null ? "allocation-drop" : "allocated-" + entry.lane()));
                continue;
            }
            if (!assembled.contains(note.id())) {
                add(losses, "voicing/assemble", expected, reasons.getOrDefault(note.id(), "missing-native-attack"));
                continue;
            }
            survived++;
        }
        var output = new java.util.LinkedHashMap<String, Loss>();
        for (String stage : List.of("transcribe", "roles", "allocation", "voicing/assemble")) {
            var row = losses.getOrDefault(stage, new MutableLoss());
            output.put(stage, new Loss(row.count, row.reasons, row.examples));
        }
        return new Waterfall(truth.size(), survived, output);
    }

    private static boolean tonalPart(Map<Integer, BenchReconstruction.Part> parts, int part, boolean lead, Integer leadPart) {
        var value = parts.get(part);
        return value != null && !value.drums() && (!lead || leadPart != null && part == leadPart);
    }

    private static String midiKey(int part, int pitch, long start) {
        return part + ":" + pitch + ":" + start;
    }

    private static Map<Integer, Integer> byTruth(List<BenchMetrics.Pair> pairs) {
        return pairs.stream().collect(Collectors.toMap(BenchMetrics.Pair::truth, BenchMetrics.Pair::estimate));
    }

    private List<Window> sections(List<BenchMetrics.Event> truth, List<BenchMetrics.Event> lead,
                                  List<BenchMetrics.Event> nativeEvents, List<BenchMetrics.Event> nativeEventsWithLayers) {
        var bars = structure.bars().stream().collect(Collectors.toMap(Bar::id, bar -> bar));
        var expected = new SectionTones(truth, BenchMetrics.melody(truth), lead);
        var dry = SectionTones.estimate(nativeEvents.stream().map(note -> map(note, projectToTruth)).toList());
        var layered = SectionTones.estimate(nativeEventsWithLayers.stream().map(note -> map(note, projectToTruth)).toList());
        var result = new ArrayList<Window>();
        for (var section : structure.sections()) {
            var span = section.bars().stream().map(bars::get).filter(java.util.Objects::nonNull).toList();
            if (span.isEmpty()) continue;
            double a = projectToTruth.applyAsDouble(span.getFirst().start());
            double b = projectToTruth.applyAsDouble(span.getLast().end());
            var target = expected.within(a, b);
            var actual = dry.within(a, b);
            var full = layered.within(a, b);
            result.add(new Window(section.id(), a, b, target.noteF1(actual), target.melodyF1(actual),
                    target.leadAttackF1(actual), target.noteF1(full), target.melodyF1(full), target.leadAttackF1(full)));
        }
        return List.copyOf(result);
    }

    private static List<BenchMetrics.Event> within(List<BenchMetrics.Event> notes, double start, double end) {
        return notes.stream().filter(note -> note.start() >= start && note.start() < end).toList();
    }

    static BenchMetrics.Event map(BenchMetrics.Event note, DoubleUnaryOperator clock) {
        return new BenchMetrics.Event(note.lane(), clock.applyAsDouble(note.start()), clock.applyAsDouble(note.end()),
                note.pitch(), note.hash(), note.drum(), note.legacyDrum());
    }

    static BenchGold.Score leadScore(List<BenchMetrics.Event> truth, List<BenchMetrics.Event> estimate,
                                     String lane, double seconds) {
        var labels = truth.stream().filter(note -> note.lane().equals(lane))
                .map(note -> new BenchGold.Label(note.pitch(), note.start(), note.end(), "lead")).toList();
        return BenchGold.score(List.of(new BenchGold.Region("full", 0, seconds, labels)),
                estimate.stream().filter(note -> note.lane().equals("FM1")).toList());
    }

    static Double leadGap(Path work, List<BenchMetrics.Event> truth, List<BenchMetrics.Event> nativeEvents,
                          String lane, BenchClock clock, DoubleUnaryOperator truthToInput) throws IOException {
        var notes = truth.stream().filter(note -> note.lane().equals(lane))
                .map(note -> new LeadGap.Interval(truthToInput.applyAsDouble(note.start()),
                        truthToInput.applyAsDouble(note.end()))).toList();
        if (notes.isEmpty()) return null;
        return LeadGap.measure(work, BenchRun.melodyGates(nativeEvents),
                List.of(new LeadGap.Interval(0, clock.sourceDuration())), notes, null).leadGap();
    }

    private static String reason(String text, String fallback) { return text == null ? fallback : text; }

    private static void add(Map<String, MutableLoss> losses, String stage, BenchMetrics.Event event, String reason) {
        var loss = losses.computeIfAbsent(stage, ignored -> new MutableLoss());
        loss.count++;
        loss.reasons.merge(reason, 1, Integer::sum);
        if (loss.examples.size() < 3) loss.examples.add(new Example(event.lane(), event.pitch(), event.start(), reason));
    }

    private static final class MutableLoss {
        int count;
        final Map<String, Integer> reasons = new TreeMap<>();
        final List<Example> examples = new ArrayList<>();
    }
}
