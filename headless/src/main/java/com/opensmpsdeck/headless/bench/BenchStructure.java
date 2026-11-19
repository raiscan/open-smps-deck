package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BenchStructure {
    record Grid(double bpm, int offsetFrames, int ticksPerBeat) {
        double barLength() { return 240 / bpm; }
        double offset() { return offsetFrames / 60.0; }
    }
    private record Bar(String id, double start, double end, boolean complete) {}
    private record Repeat(List<String> bars) {}
    private record Instance(List<String> bars) {}
    private record Phrase(List<Instance> instances) {}
    private record Structure(List<Bar> bars, List<Repeat> repeats, List<Phrase> phrases) {}
    private record TempoGrid(double bpm, double barOffset) {}
    private record Tempo(TempoGrid selected, String source) {}
    private record Score(Tempo tempo) {}
    private record Pair(int length, long first, long second) {}
    private record Attack(String lane, int pitch, long onset, long release) {}

    private BenchStructure() {}

    static Grid nativeGrid(StudioProject song, int id, int frames) {
        var program = song.nativeProgram();
        int divider = Math.max(1, Byte.toUnsignedInt(program.bank()[program.headerOffset() + 4]));
        // In the S3K clock, each overflow holds duration counters for one presentation.
        double bpm = 3600 * (1 - program.tempo() / 256.0) / (song.ticksPerBeat() * divider);
        return new Grid(bpm, BenchMidi.offsetFrames(id, 240 / bpm, frames), song.ticksPerBeat());
    }

    static BenchTimeline.Truth shift(BenchTimeline.Truth truth, Grid grid, double seconds) {
        double delay = grid.offset();
        return new BenchTimeline.Truth(truth.channels().stream().map(channel -> new BenchTimeline.Channel(channel.kind(),
                channel.notes().stream().filter(n -> n.start() + delay < seconds)
                        .map(n -> new BenchTimeline.Note(n.start() + delay, Math.min(seconds, n.end() + delay),
                                n.pitch(), n.voiceHash(), n.envelopeId())).toList(),
                channel.hits().stream().filter(h -> h.time() + delay < seconds)
                        .map(h -> new BenchTimeline.Hit(h.time() + delay, h.sampleHash(), h.slot())).toList())).toList());
    }

    static void measure(Path output, Grid grid, BenchTimeline.Truth truth, double seconds,
                        Map<String, Double> metrics) throws IOException {
        var score = BenchFiles.read(output.resolve("score.json"), Score.class);
        var structure = BenchFiles.read(output.resolve("structure.json"), Structure.class);
        double bpm = 120, phase = 0;
        if (score.tempo != null && !score.tempo.source.equals("midi")) {
            bpm = score.tempo.selected.bpm;
            phase = score.tempo.selected.barOffset;
        }
        metrics.put("tempoErrorPercent", Math.abs(bpm / grid.bpm - 1) * 100);
        double bar = grid.barLength();
        double delta = phase - grid.offset();
        metrics.put("barPhaseErrorMs", 1000 * Math.abs(delta - Math.rint(delta / bar) * bar));

        var expected = truthPairs(grid, truth, seconds);
        var actual = new HashSet<Pair>();
        var unmatched = new HashSet<List<String>>();
        var bars = new HashMap<String, Bar>();
        for (var entry : structure.bars) bars.put(entry.id, entry);
        if (structure.phrases != null) for (var phrase : structure.phrases) {
            predicted(phrase.instances.stream().map(Instance::bars).toList(), bars, grid, seconds, actual, unmatched);
        }
        for (var repeat : structure.repeats) {
            predicted(repeat.bars.stream().map(List::of).toList(), bars, grid, seconds, actual, unmatched);
        }
        long truePositives = actual.stream().filter(expected::contains).count();
        int predictions = actual.size() + unmatched.size();
        if (predictions > 0) metrics.put("repeatPrecision", truePositives / (double) predictions);
        if (!expected.isEmpty()) metrics.put("repeatRecall", truePositives / (double) expected.size());
    }

    private static void predicted(List<List<String>> instances, Map<String, Bar> bars, Grid grid, double seconds,
                                  Set<Pair> actual, Set<List<String>> unmatched) {
        for (int i = 0; i < instances.size(); i++) for (int j = i + 1; j < instances.size(); j++) {
            var first = instances.get(i);
            var second = instances.get(j);
            int length = first.size();
            long a = truthIndex(first, bars, grid, seconds);
            long b = truthIndex(second, bars, grid, seconds);
            if (a < 0 || b < 0 || Math.abs(b - a) < length) {
                String left = first.getFirst(), right = second.getFirst();
                unmatched.add(left.compareTo(right) < 0 ? List.of(left, right, Integer.toString(length))
                        : List.of(right, left, Integer.toString(length)));
            } else {
                actual.add(new Pair(length, Math.min(a, b), Math.max(a, b)));
            }
        }
    }

    private static long truthIndex(List<String> span, Map<String, Bar> bars, Grid grid, double seconds) {
        double length = grid.barLength();
        double start = bars.get(span.getFirst()).start;
        double end = bars.get(span.getLast()).end;
        long index = Math.round((start - grid.offset()) / length);
        double aligned = grid.offset() + index * length;
        double alignedEnd = aligned + span.size() * length;
        return index >= 0 && alignedEnd <= seconds && Math.abs(start - aligned) <= length / 8
                && Math.abs(end - alignedEnd) <= length / 8 ? index : -1;
    }

    private static Set<Pair> truthPairs(Grid grid, BenchTimeline.Truth truth, double seconds) {
        var result = new HashSet<Pair>();
        double bar = grid.barLength();
        double tick = bar / (4 * grid.ticksPerBeat());
        for (int length : new int[]{1, 2, 4}) {
            var signatures = new HashMap<List<Attack>, List<Integer>>();
            for (int index = 0; grid.offset() + (index + length) * bar <= seconds; index++) {
                var signature = new ArrayList<Attack>();
                long limit = (long) length * 4 * grid.ticksPerBeat();
                double start = grid.offset() + index * bar;
                for (var channel : truth.channels()) {
                    if (channel.kind().equals("noise")) continue;
                    for (var note : channel.notes()) {
                        long attack = Math.round((note.start() - start) / tick);
                        if (attack >= 0 && attack < limit)
                            signature.add(new Attack(channel.kind(), note.pitch(), attack,
                                    Math.round((note.end() - start) / tick)));
                    }
                }
                if (signature.isEmpty()) continue;
                var previous = signatures.computeIfAbsent(signature, ignored -> new ArrayList<>());
                for (int earlier : previous) if (index - earlier >= length) result.add(new Pair(length, earlier, index));
                previous.add(index);
            }
        }
        return result;
    }
}
