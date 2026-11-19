package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/** Estimates a grid in elapsed seconds. MIDI playback times remain authoritative. */
final class ReconstructionTempo {
    record Grid(double bpm, double beatPhase, double barOffset, int beatsPerBar, double evidence) {
        double barLength() { return 60 * beatsPerBar / bpm; }
    }
    record Estimate(Grid selected, double confidence, double midiEvidence, double audioEvidence,
                    List<Grid> alternatives, String source) {}
    record Options(boolean force, @ReconstructionStages.Nullable Double bpm,
                   @ReconstructionStages.Nullable Double offset) {
        Options {
            if (bpm != null && !(bpm >= 20 && bpm <= 400))
                throw new IllegalArgumentException("--tempo must be between 20 and 400 BPM");
            if (offset != null && (bpm == null || !Double.isFinite(offset)))
                throw new IllegalArgumentException("--bar-offset needs --tempo and a finite value in seconds");
        }
    }
    private record Pulse(double strength, double phase) {}
    private record Candidate(double bpm, int subdivision, Pulse midi, double audio, double strength) {}
    private record Accent(double time, double weight, int kind) {}
    private record Meter(int beats, int phase, double evidence) {}

    private ReconstructionTempo() {}

    static Estimate estimate(MidiScore score, double duration, List<ReconstructionRhythmAudio.Onset> audio, int meter) {
        var attacks = new TreeMap<Long, Double>();
        for (var note : score.notes()) {
            if (note.startUs() >= duration * 1e6) continue;
            // Cap coincident attacks at one pulse. A chord should not outweigh a single onset.
            attacks.merge(Math.round(note.startUs() / 5000.0), 1.0, Math::max);
        }
        var midi = attacks.entrySet().stream()
                .map(a -> new ReconstructionRhythmAudio.Onset(a.getKey() * .005, a.getValue())).toList();
        var candidates = new ArrayList<Candidate>();
        for (double bpm = 65; bpm <= 200; bpm += .1) candidates.add(candidate(midi, audio, bpm));
        candidates.sort(Comparator.comparingDouble(Candidate::strength).reversed());
        var peaks = new ArrayList<Candidate>();
        for (var coarse : candidates) {
            if (peaks.stream().anyMatch(p -> Math.abs(p.bpm - coarse.bpm) < 2)) continue;
            Candidate best = coarse;
            for (int i = -10; i <= 10; i++) {
                var next = candidate(midi, audio, coarse.bpm + .01 * i);
                if (next.strength > best.strength) best = next;
            }
            peaks.add(best);
            if (peaks.size() == 5) break;
        }
        peaks.sort(Comparator.comparingDouble(Candidate::strength).reversed());
        var grids = peaks.stream().map(p -> grid(score, p, duration, meter)).toList();
        var best = peaks.getFirst();
        double margin = best.strength - peaks.get(1).strength;
        double confidence = Math.clamp(.62 * best.midi.strength + .24 * best.audio
                + .65 * margin, 0, 1) * Math.min(1, midi.size() / 24.0);
        return new Estimate(grids.getFirst(), confidence, best.midi.strength, best.audio,
                grids.subList(1, grids.size()), "estimated");
    }

    static Estimate override(Options options, int meter) {
        int beats = meter == 0 ? 4 : meter;
        double period = 60 / options.bpm;
        double offset = modulo(options.offset == null ? 0 : options.offset, period * beats);
        return new Estimate(new Grid(options.bpm, modulo(offset, period), offset, beats, 1),
                1, 0, 0, List.of(), "override");
    }

    private static Candidate candidate(List<ReconstructionRhythmAudio.Onset> midi,
                                       List<ReconstructionRhythmAudio.Onset> audio, double bpm) {
        Pulse fourth = pulse(midi, 15 / bpm), sixth = pulse(midi, 10 / bpm);
        double eighth = pulse(midi, 30 / bpm).strength, beat = pulse(midi, 60 / bpm).strength;
        double triplet = pulse(midi, 20 / bpm).strength;
        double audioEighth = pulse(audio, 30 / bpm).strength;
        double audioBeat = pulse(audio, 60 / bpm).strength;
        double audioFourth = pulse(audio, 15 / bpm).strength, audioSixth = pulse(audio, 10 / bpm).strength;
        double binary = .64 * fourth.strength + .16 * eighth + .10 * beat
                + .10 * (.4 * audioFourth + .35 * audioEighth + .25 * audioBeat);
        // Test a six-way subdivision as well, so a triplet grid need not be
        // interpreted as a straight grid at one and a half times the tempo.
        double ternary = .58 * sixth.strength + .23 * eighth + .06 * triplet + .10 * beat
                + .10 * (.4 * audioSixth + .35 * audioEighth + .25 * audioBeat);
        if (ternary > binary)
            return new Candidate(bpm, 6, sixth, Math.max(audioSixth, Math.max(audioEighth, audioBeat)), ternary);
        return new Candidate(bpm, 4, fourth, Math.max(audioFourth, Math.max(audioEighth, audioBeat)), binary);
    }

    private static Pulse pulse(List<ReconstructionRhythmAudio.Onset> events, double period) {
        double re = 0, im = 0, total = 0;
        double angular = 2 * Math.PI / period;
        for (var event : events) {
            re += event.weight() * Math.cos(angular * event.time());
            im += event.weight() * Math.sin(angular * event.time());
            total += event.weight();
        }
        return total == 0 ? new Pulse(0, 0) : new Pulse(Math.min(1, Math.hypot(re, im) / total),
                modulo(Math.atan2(im, re) / angular, period));
    }

    private static Grid grid(MidiScore score, Candidate candidate, double duration, int specifiedMeter) {
        double quarter = 60 / candidate.bpm;
        int subdivision = candidate.subdivision;
        double step = quarter / subdivision;
        var accents = accents(score, duration);
        double finePhase = candidate.midi.phase;
        double[] beat = new double[subdivision];
        for (var accent : accents) {
            int tick = nearestTick(accent.time, finePhase, step);
            if (Math.abs(accent.time - (finePhase + tick * step)) <= step * .30)
                beat[Math.floorMod(tick, subdivision)] += accent.weight;
        }
        int beatSlot = 0;
        for (int i = 1; i < subdivision; i++) if (beat[i] > beat[beatSlot]) beatSlot = i;
        double beatPhase = modulo(finePhase + beatSlot * step, quarter);
        var four = meter(accents, beatPhase, quarter, specifiedMeter == 0 ? 4 : specifiedMeter);
        var result = four;
        if (specifiedMeter == 0) {
            var three = meter(accents, beatPhase, quarter, 3);
            if (three.evidence > four.evidence + .12) result = three;
        }
        int slot = result.phase;
        if (!accents.isEmpty()) {
            double first = accents.getFirst().time;
            int tick = nearestTick(first, beatPhase, quarter);
            if (first >= .10 && first < result.beats * quarter && Math.abs(first - beatPhase - tick * quarter) < quarter * .09
                    && openingParts(score, first) >= 2) {
                slot = Math.floorMod(tick, result.beats);
            }
        }
        double offset = modulo(beatPhase + slot * quarter, result.beats * quarter);
        return new Grid(candidate.bpm, beatPhase, offset, result.beats, candidate.strength);
    }

    private static long openingParts(MidiScore score, double time) {
        return score.notes().stream().filter(n -> Math.abs(n.startUs() / 1e6 - time) <= .028)
                .mapToInt(MidiScore.Note::partId).distinct().count();
    }

    private static List<Accent> accents(MidiScore score, double duration) {
        var drumParts = new HashSet<Integer>();
        score.parts().stream().filter(MidiScore.Part::drums).forEach(p -> drumParts.add(p.id()));
        var notes = score.notes().stream().filter(n -> n.startUs() < duration * 1e6)
                .sorted(Comparator.comparingLong(MidiScore.Note::startUs).thenComparingInt(MidiScore.Note::pitch)).toList();
        var result = new ArrayList<Accent>();
        var previous = new HashMap<Integer, Integer>();
        var previousTime = new HashMap<Integer, Long>();
        for (var note : notes) {
            double time = note.startUs() / 1e6;
            if (drumParts.contains(note.partId())) {
                double weight = switch (note.pitch()) {
                    case 35, 36 -> 3;
                    case 38, 40 -> .6;
                    case 49, 57 -> 3;
                    default -> .25;
                };
                result.add(new Accent(time, weight, 128 + note.pitch()));
            } else if (note.startUs() - previousTime.getOrDefault(note.partId(), -100_000L) > 25_000) {
                var last = previous.put(note.partId(), note.pitch());
                previousTime.put(note.partId(), note.startUs());
                double weight = last == null || Math.floorMod(last - note.pitch(), 12) != 0 ? 1 : .20;
                result.add(new Accent(time, note.pitch() < 55 ? weight : weight * .55, note.pitch() % 12));
            }
        }
        return result;
    }

    private static Meter meter(List<Accent> accents, double phase, double beat, int beats) {
        double[] weights = new double[beats];
        var kinds = new HashMap<Integer, double[]>();
        for (var accent : accents) {
            int tick = nearestTick(accent.time, phase, beat);
            if (Math.abs(accent.time - phase - tick * beat) > beat * .10) continue;
            int index = Math.floorMod(tick, beats);
            weights[index] += accent.weight;
            kinds.computeIfAbsent(accent.kind, ignored -> new double[beats])[index] += accent.weight;
        }
        int best = 0;
        double bestWeight = -1;
        for (int i = 0; i < beats; i++) {
            // A downbeat is typically accented, and the first attack resolves rotational ties.
            double first = accents.isEmpty() ? phase : accents.getFirst().time;
            double leadIn = modulo(first - phase - i * beat + beat * .10, beat * beats);
            double weight = weights[i] * (1 + .018 * (1 - leadIn / (beat * beats)));
            if (weight > bestWeight) {
                best = i;
                bestWeight = weight;
            }
        }
        if (beats == 1) return new Meter(1, 0, 0);
        double excess = 0, total = 0;
        for (var distribution : kinds.values()) {
            double sum = java.util.Arrays.stream(distribution).sum();
            double peak = java.util.Arrays.stream(distribution).max().orElse(0);
            excess += (peak - sum / beats) * beats / (beats - 1);
            total += sum;
        }
        return new Meter(beats, best, total == 0 ? 0 : excess / total);
    }

    static List<ReconstructionStructure.Bar> bars(Grid grid, double duration) {
        var bars = new ArrayList<ReconstructionStructure.Bar>();
        double start = 0, length = grid.barLength(), offset = grid.barOffset;
        boolean complete = offset < 1e-6 || length - offset < 1e-6;
        if (complete) offset = 0;
        for (int i = complete ? 1 : 0; start < duration; i++) {
            double boundary = offset + i * length;
            bars.add(new ReconstructionStructure.Bar("b" + (bars.size() + 1), start,
                    Math.min(boundary, duration), (complete || i > 0) && boundary <= duration));
            start = boundary;
        }
        return List.copyOf(bars);
    }

    static double modulo(double value, double period) {
        double remainder = value % period;
        return remainder < 0 ? remainder + period : Math.abs(remainder);
    }

    private static int nearestTick(double time, double phase, double step) {
        return (int) Math.round((time - phase) / step);
    }
}
