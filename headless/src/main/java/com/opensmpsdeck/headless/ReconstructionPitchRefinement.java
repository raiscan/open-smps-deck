package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recovers short omissions when an aligned WAV establishes a stable, unambiguous pitch. */
final class ReconstructionPitchRefinement {
    private static final long HALF_WINDOW_US = 32_000;
    private static final long HOP_US = 10_000;
    private static final long MAX_TRANSITION_US = 50_000;
    private static final long MIN_CHANGE_US = 90_000;
    private static final long MAX_CHANGE_US = 450_000;
    private static final long MAX_GAP_US = 350_000;
    private static final int MIN_ANCHOR_FRAMES = 4;
    private static final int MIN_CHANGE_FRAMES = 7;
    private static final int MAX_INTERVAL = 7;
    private static final int LOW_PITCH = 38;
    private static final int HIGH_PITCH = 88;
    private static final double STEM_HARMONIC_ENERGY = .74;
    private static final double MIX_HARMONIC_ENERGY = .87;
    private static final Comparator<MidiScore.Note> NOTE_ORDER = Comparator
            .comparingLong(MidiScore.Note::startUs).thenComparingInt(MidiScore.Note::partId)
            .thenComparingInt(MidiScore.Note::pitch);

    private ReconstructionPitchRefinement() {}

    record Change(int partId, int originalPitch, int pitch, long startUs, long endUs, String reason,
                  String source, boolean stem, int supportingFrames, double meanHarmonicEnergy) {}

    record Result(MidiScore score, List<Change> changes) {
        Result {
            changes = List.copyOf(changes);
        }
    }

    static Result refine(MidiScore score, Path mix, Map<Integer, Path> stems, long limitUs) throws IOException {
        var sources = new HashMap<Path, Analysis>();
        var notes = new ArrayList<MidiScore.Note>();
        var changes = new ArrayList<Change>();
        var parts = new HashMap<Integer, List<MidiScore.Note>>();
        Path mixSource = normalized(mix);
        var stemSources = new HashMap<Integer, Path>();
        stems.forEach((part, path) -> stemSources.put(part, normalized(path)));
        for (var note : score.notes()) {
            parts.computeIfAbsent(note.partId(), unused -> new ArrayList<>()).add(note);
        }
        for (var part : score.parts()) {
            var partNotes = parts.remove(part.id());
            if (partNotes == null) {
                continue;
            }
            Path source = stemSources.getOrDefault(part.id(), mixSource);
            if (part.drums() || source == null || limitUs <= 0) {
                notes.addAll(partNotes);
                continue;
            }
            var analysis = sources.get(source);
            if (analysis == null) {
                analysis = new Analysis(source, Math.min(limitUs, ReferenceAudio.durationMicros(source)));
                sources.put(source, analysis);
            }
            var competing = competingNotes(score, part.id(), source, mixSource, stemSources);
            partNotes.sort(NOTE_ORDER);
            boolean stem = !source.equals(mixSource);
            long priorEnd = 0;
            for (int i = 0; i < partNotes.size(); i++) {
                var current = partNotes.get(i);
                var next = i + 1 < partNotes.size() ? partNotes.get(i + 1) : null;
                if (priorEnd > current.startUs() || next != null && next.startUs() < current.endUs()
                        || current.endUs() > analysis.endUs || !clear(competing, current.startUs(), current.endUs())) {
                    notes.add(current);
                } else {
                    split(current, analysis, stem, notes, changes);
                    if (next != null && (i + 2 >= partNotes.size()
                            || partNotes.get(i + 2).startUs() >= next.endUs())) {
                        fillGap(current, next, analysis, stem, competing, notes, changes);
                    }
                }
                priorEnd = Math.max(priorEnd, current.endUs());
            }
        }
        parts.values().forEach(notes::addAll);
        if (changes.isEmpty()) {
            return new Result(score, List.of());
        }
        notes.sort(NOTE_ORDER);
        changes.sort(Comparator.comparingLong(Change::startUs).thenComparingInt(Change::partId)
                .thenComparingInt(Change::pitch));
        return new Result(new MidiScore(score.parts(), notes, score.warnings()), changes);
    }

    private static Path normalized(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static List<MidiScore.Note> competingNotes(MidiScore score, int id, Path source, Path mix,
                                                       Map<Integer, Path> stems) {
        Set<Integer> parts = new HashSet<>();
        for (var other : score.parts()) {
            if (!other.drums() && other.id() != id
                    && (source.equals(mix) || source.equals(stems.get(other.id())))) {
                parts.add(other.id());
            }
        }
        return score.notes().stream().filter(note -> parts.contains(note.partId())).sorted(NOTE_ORDER).toList();
    }

    private static boolean clear(List<MidiScore.Note> competing, long startUs, long endUs) {
        for (var note : competing) {
            if (note.startUs() >= endUs) {
                break;
            }
            if (note.endUs() > startUs) {
                return false;
            }
        }
        return true;
    }

    private static void split(MidiScore.Note note, Analysis analysis, boolean stem, List<MidiScore.Note> notes,
                              List<Change> changes) throws IOException {
        if (!validPitch(note.pitch()) || note.endUs() - note.startUs() < 2 * MIN_CHANGE_US) {
            notes.add(note);
            return;
        }
        var runs = analysis.runs(note.startUs(), note.endUs(), stem);
        long cursor = note.startUs();
        for (int i = 1; i < runs.size(); i++) {
            var before = runs.get(i - 1);
            var candidate = runs.get(i);
            if (!anchor(before, note.pitch()) || !nearby(candidate.pitch(), note.pitch())
                    || candidate.frames() < MIN_CHANGE_FRAMES
                    || candidate.firstUs() - before.lastUs() > MAX_TRANSITION_US) {
                continue;
            }
            if (before.firstUs() > cursor + HALF_WINDOW_US + MAX_TRANSITION_US) {
                continue;
            }
            long start = midpoint(before.lastUs(), candidate.firstUs());
            long end = note.endUs();
            if (i + 1 == runs.size()) {
                if (end - candidate.lastUs() > HALF_WINDOW_US + HOP_US) {
                    continue;
                }
            } else {
                var after = runs.get(i + 1);
                if (!anchor(after, note.pitch()) || after.firstUs() - candidate.lastUs() > MAX_TRANSITION_US) {
                    continue;
                }
                end = midpoint(candidate.lastUs(), after.firstUs());
            }
            if (start - cursor < MIN_ANCHOR_FRAMES * HOP_US || end - start < MIN_CHANGE_US
                    || end - start > MAX_CHANGE_US
                    || end < note.endUs() && note.endUs() - end < MIN_ANCHOR_FRAMES * HOP_US) {
                continue;
            }
            notes.add(new MidiScore.Note(note.partId(), note.pitch(), note.velocity(), cursor, start));
            notes.add(new MidiScore.Note(note.partId(), candidate.pitch(), note.velocity(), start, end));
            changes.add(change(note.partId(), note.pitch(), start, end, "held-note-pitch-change", analysis, stem,
                    candidate));
            cursor = end;
        }
        if (cursor < note.endUs() || cursor == note.startUs()) {
            notes.add(cursor == note.startUs() ? note
                    : new MidiScore.Note(note.partId(), note.pitch(), note.velocity(), cursor, note.endUs()));
        }
    }

    private static void fillGap(MidiScore.Note previous, MidiScore.Note next, Analysis analysis, boolean stem,
                                List<MidiScore.Note> competing, List<MidiScore.Note> notes, List<Change> changes)
            throws IOException {
        long start = previous.endUs();
        long end = next.startUs();
        long duration = end - start;
        long anchorDuration = HALF_WINDOW_US * 2 + MIN_ANCHOR_FRAMES * HOP_US;
        if (duration < MIN_CHANGE_US || duration > MAX_GAP_US || !validPitch(previous.pitch())
                || !validPitch(next.pitch()) || next.endUs() > analysis.endUs
                || previous.endUs() - previous.startUs() < anchorDuration
                || next.endUs() - next.startUs() < anchorDuration
                || !clear(competing, start - anchorDuration, end + anchorDuration)) {
            return;
        }
        var before = analysis.runs(start - anchorDuration, start, stem);
        var after = analysis.runs(end, end + anchorDuration, stem);
        var gap = analysis.runs(start, end, stem);
        if (before.size() != 1 || !anchor(before.getFirst(), previous.pitch())
                || after.size() != 1 || !anchor(after.getFirst(), next.pitch()) || gap.size() != 1) {
            return;
        }
        var candidate = gap.getFirst();
        if (candidate.frames() < MIN_CHANGE_FRAMES || !nearby(candidate.pitch(), previous.pitch())
                || !nearby(candidate.pitch(), next.pitch()) || candidate.firstUs() - start > HALF_WINDOW_US + HOP_US
                || end - candidate.lastUs() > HALF_WINDOW_US + HOP_US) {
            return;
        }
        notes.add(new MidiScore.Note(previous.partId(), candidate.pitch(), (previous.velocity() + next.velocity()) / 2,
                start, end));
        changes.add(change(previous.partId(), -1, start, end, "short-gap-pitch", analysis, stem, candidate));
    }

    private static boolean anchor(Run run, int pitch) {
        return run.pitch() == pitch && run.frames() >= MIN_ANCHOR_FRAMES;
    }

    private static boolean nearby(int candidate, int anchor) {
        return candidate != anchor && Math.abs(candidate - anchor) <= MAX_INTERVAL;
    }

    private static boolean validPitch(int pitch) {
        return pitch >= LOW_PITCH && pitch <= HIGH_PITCH;
    }

    private static long midpoint(long left, long right) {
        return left + (right - left) / 2;
    }

    private static Change change(int part, int original, long start, long end, String reason,
                                 Analysis analysis, boolean stem, Run run) {
        return new Change(part, original, run.pitch(), start, end, reason, analysis.source.getFileName().toString(), stem, run.frames(),
                run.harmonicEnergy() / run.frames());
    }

    private record Frame(int pitch, double harmonicEnergy) {
        private static final Frame UNPITCHED = new Frame(-1, 0);
    }

    private record Run(int pitch, long firstUs, long lastUs, int frames, double harmonicEnergy) {
        Run extend(long center, double energy) {
            return new Run(pitch, firstUs, center, frames + 1, harmonicEnergy + energy);
        }
    }

    private static final class Analysis {
        private static final long CHUNK_US = 1_000_000;
        private static final int FFT_SIZE = 4_096;
        private final Path source;
        private final long endUs;
        private final Map<Long, Frame> frames = new HashMap<>();
        private final double[] real = new double[FFT_SIZE];
        private final double[] imaginary = new double[FFT_SIZE];
        private final double[] power = new double[FFT_SIZE / 2];
        private ReferenceAudio chunk;
        private long chunkStart = -1;
        private long chunkBucket = -1;

        private Analysis(Path source, long endUs) {
            this.source = source;
            this.endUs = endUs;
        }

        List<Run> runs(long start, long end, boolean stem) throws IOException {
            var runs = new ArrayList<Run>();
            if (start < 0 || end > endUs || end - start < 2 * HALF_WINDOW_US) {
                return runs;
            }
            Run run = null;
            double threshold = stem ? STEM_HARMONIC_ENERGY : MIX_HARMONIC_ENERGY;
            long firstCenter = start + HALF_WINDOW_US;
            firstCenter += (HOP_US - firstCenter % HOP_US) % HOP_US;
            for (long center = firstCenter; center <= end - HALF_WINDOW_US; center += HOP_US) {
                Frame frame = frames.get(center);
                if (frame == null) {
                    frame = frame(center);
                    frames.put(center, frame);
                }
                if (frame.harmonicEnergy() < threshold) {
                    if (run != null) {
                        runs.add(run);
                        run = null;
                    }
                } else if (run != null && run.pitch() == frame.pitch()) {
                    run = run.extend(center, frame.harmonicEnergy());
                } else {
                    if (run != null) {
                        runs.add(run);
                    }
                    run = new Run(frame.pitch(), center, center, 1, frame.harmonicEnergy());
                }
            }
            if (run != null) {
                runs.add(run);
            }
            return runs;
        }

        private Frame frame(long center) throws IOException {
            long bucket = center / CHUNK_US;
            if (chunkBucket != bucket) {
                chunkStart = Math.max(0, bucket * CHUNK_US - HALF_WINDOW_US);
                long end = Math.min(endUs, (bucket + 1) * CHUNK_US + HALF_WINDOW_US);
                chunk = ReferenceAudio.read(source, chunkStart / 1_000_000.0, (end - chunkStart) / 1_000_000.0);
                chunkBucket = bucket;
            }
            int rate = chunk.sampleRate();
            if (rate < 8_000) {
                return Frame.UNPITCHED;
            }
            int stride = Math.max(1, rate / 12_000);
            int start = (int) ((center - HALF_WINDOW_US - chunkStart) * rate / 1_000_000);
            int length = (int) (2 * HALF_WINDOW_US * rate / 1_000_000) / stride;
            float[] samples = chunk.samples();
            length = Math.min(length, (samples.length - start) / stride);
            Arrays.fill(real, 0);
            Arrays.fill(imaginary, 0);
            double squareSum = 0;
            for (int index = 0; index < length; index++) {
                double value = 0;
                for (int i = 0; i < stride; i++) {
                    value += samples[start + index * stride + i];
                }
                value /= stride;
                squareSum += value * value;
                real[index] = value * (.5 - .5 * Math.cos(2 * Math.PI * index / (length - 1)));
            }
            if (squareSum < length * .000_025) {
                return Frame.UNPITCHED;
            }
            fft(real, imaginary);
            for (int i = 0; i < power.length; i++) {
                power[i] = real[i] * real[i] + imaginary[i] * imaginary[i];
            }
            return estimate(rate / (double) stride / FFT_SIZE, length);
        }

        // Require a measurable fundamental and enough energy in that complete harmonic series.
        private Frame estimate(double binHz, int windowSamples) {
            int firstBin = (int) Math.ceil(35 / binHz);
            int strongest = firstBin;
            double total = 0;
            for (int i = 0; i < power.length; i++) {
                total += power[i];
                if (i >= firstBin && power[i] > power[strongest]) {
                    strongest = i;
                }
            }
            if (strongest <= firstBin || strongest >= power.length - 1) {
                return Frame.UNPITCHED;
            }
            Frame best = Frame.UNPITCHED;
            double strongestHz = peakFrequency(strongest, binHz);
            int radius = (int) Math.ceil(1.5 * FFT_SIZE / windowSamples);
            for (int i = firstBin + 1; i <= strongest; i++) {
                if (power[i] < .12 * power[strongest] || power[i] <= power[i - 1] || power[i] <= power[i + 1]) {
                    continue;
                }
                double hz = peakFrequency(i, binHz);
                double midi = 69 + 12 * Math.log(hz / 440.0) / Math.log(2);
                int pitch = (int) Math.round(midi);
                if (!validPitch(pitch) || Math.abs(pitch - midi) > .27) {
                    continue;
                }

                int strongestHarmonic = (int) Math.round(strongestHz / hz);
                if (strongestHarmonic > 8 || !nearHarmonic(strongestHz, hz, strongestHarmonic)) {
                    continue;
                }
                double explained = energy(i, radius, firstBin);
                for (int harmonic = 2; harmonic <= 8; harmonic++) {
                    int expected = (int) Math.round(harmonic * hz / binHz);
                    if (expected >= power.length - radius - 1) {
                        break;
                    }
                    int peak = expected;
                    int tolerance = (int) Math.ceil(expected * .023);
                    for (int k = Math.max(firstBin + 1, expected - tolerance);
                         k <= Math.min(power.length - 2, expected + tolerance); k++) {
                        if (power[k] > power[peak]) {
                            peak = k;
                        }
                    }
                    if (power[peak] > power[peak - 1] && power[peak] > power[peak + 1]
                            && nearHarmonic(peakFrequency(peak, binHz), hz, harmonic)) {
                        explained += energy(peak, radius, firstBin);
                    }
                }
                double fraction = Math.min(1, explained / total);
                if (fraction > best.harmonicEnergy()) {
                    best = new Frame(pitch, fraction);
                }
            }
            return best;
        }

        private double peakFrequency(int bin, double binHz) {
            double left = Math.log(Math.max(power[bin - 1], Double.MIN_NORMAL));
            double top = Math.log(Math.max(power[bin], Double.MIN_NORMAL));
            double right = Math.log(Math.max(power[bin + 1], Double.MIN_NORMAL));
            double curvature = left - 2 * top + right;
            double offset = curvature == 0 ? 0 : .5 * (left - right) / curvature;
            return (bin + Math.clamp(offset, -.5, .5)) * binHz;
        }

        private static boolean nearHarmonic(double actual, double fundamental, int harmonic) {
            return Math.abs(actual / (fundamental * harmonic) - 1) <= .023;
        }

        private double energy(int peak, int radius, int firstBin) {
            double sum = 0;
            for (int i = Math.max(firstBin, peak - radius); i <= Math.min(power.length - 1, peak + radius); i++) {
                sum += power[i];
            }
            return sum;
        }

        /** In-place radix-2 Cooley–Tukey transform with bit-reversed input. */
        private static void fft(double[] real, double[] imaginary) {
            for (int i = 1, j = 0; i < real.length; i++) {
                int bit = real.length >> 1;
                while ((j & bit) != 0) {
                    j ^= bit;
                    bit >>= 1;
                }
                j ^= bit;
                if (i < j) {
                    double value = real[i];
                    real[i] = real[j];
                    real[j] = value;
                }
            }
            for (int length = 2; length <= real.length; length *= 2) {
                double phase = -2 * Math.PI / length;
                double phaseReal = Math.cos(phase);
                double phaseImaginary = Math.sin(phase);
                for (int offset = 0; offset < real.length; offset += length) {
                    double wReal = 1;
                    double wImaginary = 0;
                    for (int i = offset; i < offset + length / 2; i++) {
                        int right = i + length / 2;
                        double deltaReal = wReal * real[right] - wImaginary * imaginary[right];
                        double deltaImaginary = wReal * imaginary[right] + wImaginary * real[right];
                        real[right] = real[i] - deltaReal;
                        imaginary[right] = imaginary[i] - deltaImaginary;
                        real[i] += deltaReal;
                        imaginary[i] += deltaImaginary;
                        double nextReal = wReal * phaseReal - wImaginary * phaseImaginary;
                        wImaginary = wReal * phaseImaginary + wImaginary * phaseReal;
                        wReal = nextReal;
                    }
                }
            }
        }
    }
}
