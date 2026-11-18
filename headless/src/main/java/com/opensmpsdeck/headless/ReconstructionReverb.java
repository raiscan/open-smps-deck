package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Measures diffuse post-note decay in score gaps. A mono envelope cannot identify its physical source. */
final class ReconstructionReverb {
    private static final int BIN_US = 20_000;
    private static final int TAIL_BINS = 16;
    private static final int TAIL_US = TAIL_BINS * BIN_US;
    private static final int LEAD_US = 50_000;
    private static final int MAX_PROBES = 64;
    private static final int MIN_PROBES = 4;

    record Estimate(int partId, boolean stem, String status, int probes, int dryProbes, int tailProbes,
                    int decayProbes, Double decayTimeSeconds, Double tailAmplitudeRatio) {}
    private record Decay(double seconds, double amplitudeRatio) {}
    private record Probe(boolean tail, Decay decay) {}

    private ReconstructionReverb() {}

    static Map<Integer, Estimate> detect(MidiScore score, Path mix, Map<Integer, Path> stems,
                                         long limitUs) throws IOException {
        Path mixPath = mix.toAbsolutePath().normalize();
        var paths = new LinkedHashMap<Integer, Path>();
        stems.forEach((id, path) -> paths.put(id, path.toAbsolutePath().normalize()));
        var partsByPath = new LinkedHashMap<Path, List<Integer>>();
        for (var part : score.parts()) {
            if (part.drums()) continue;
            partsByPath.computeIfAbsent(paths.getOrDefault(part.id(), mixPath), ignored -> new ArrayList<>())
                    .add(part.id());
        }

        var result = new LinkedHashMap<Integer, Estimate>();
        for (var source : partsByPath.entrySet()) {
            Path audio = source.getKey();
            boolean mixed = audio.equals(mixPath);
            var sharing = paths.entrySet().stream().filter(entry -> audio.equals(entry.getValue()))
                    .map(Map.Entry::getKey).toList();
            long end = Math.min(limitUs, ReferenceAudio.durationMicros(audio));
            var notes = score.notes().stream().filter(note -> note.startUs() < end
                            && (mixed || sharing.contains(note.partId())))
                    .sorted(Comparator.comparingLong(MidiScore.Note::startUs)).toList();
            var candidates = isolated(notes, end);
            boolean stem = !mixed && sharing.size() == 1;
            for (int partId : source.getValue()) {
                var available = candidates.getOrDefault(partId, List.of());
                var probes = new ArrayList<Probe>();
                for (int i = 0, count = Math.min(MAX_PROBES, available.size()); i < count; i++) {
                    var note = available.get((int) ((long) i * available.size() / count));
                    Probe probe = observe(audio, note);
                    if (probe != null) probes.add(probe);
                }
                result.put(partId, estimate(partId, stem, probes));
            }
        }
        return result;
    }

    private static Map<Integer, List<MidiScore.Note>> isolated(List<MidiScore.Note> notes, long end) {
        var candidates = new LinkedHashMap<Integer, List<MidiScore.Note>>();
        long previousEnd = 0;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            long nextStart = i + 1 < notes.size() ? notes.get(i + 1).startUs() : end;
            if (note.startUs() - previousEnd >= LEAD_US && note.endUs() - note.startUs() >= LEAD_US
                    && Math.min(nextStart, end) - note.endUs() >= TAIL_US) {
                candidates.computeIfAbsent(note.partId(), ignored -> new ArrayList<>()).add(note);
            }
            previousEnd = Math.max(previousEnd, note.endUs());
        }
        return candidates;
    }

    private static Probe observe(Path audio, MidiScore.Note note) throws IOException {
        var body = ReferenceAudio.read(audio, (note.endUs() - LEAD_US) / 1e6, .04);
        if (body.sampleRate() < 4_000) return null;
        double active = rms(body.samples(), 0, body.samples().length);
        if (active < .006) return null;
        var lead = ReferenceAudio.read(audio, (note.startUs() - LEAD_US) / 1e6, .04);
        double noise = rms(lead.samples(), 0, lead.samples().length);
        if (noise > .04 * active) return null;
        var tail = ReferenceAudio.read(audio, note.endUs() / 1e6, TAIL_US / 1e6);
        double[] levels = new double[TAIL_BINS];
        for (int bin = 0; bin < levels.length; bin++) {
            levels[bin] = rms(tail.samples(), frame(bin, tail.sampleRate()), frame(bin + 1, tail.sampleRate()));
        }
        double floor = Math.max(.0005, Math.max(.025 * active, 3 * noise));
        if (Arrays.stream(levels).max().orElse(0) < floor) return new Probe(false, null);
        // The first bin may contain a rounded-off key-off or the leading edge of the tail.
        int start = 1;
        int end = start;
        while (end < levels.length && levels[end] >= floor) end++;
        if (end - start < 8 || levels[start] < .07 * active) return new Probe(true, null);
        if (end < levels.length && levels[end - 1] > 1.7 * floor) return new Probe(true, null);
        for (int bin = start + 1; bin < levels.length; bin++) {
            if (levels[bin] >= floor && (bin >= end || levels[bin] > 1.35 * levels[bin - 1])) {
                return new Probe(true, null);
            }
        }
        double hz = 440 * Math.pow(2, (note.pitch() - 69) / 12.0);
        if (hz < 35 || hz >= tail.sampleRate() / 2.0 || periodicTail(tail, hz, start, end)) {
            return new Probe(true, null);
        }
        double seconds = decayTime(levels, start, end);
        if (!Double.isFinite(seconds)) return new Probe(true, null);
        return new Probe(true, new Decay(seconds, levels[start] / active));
    }

    private static int frame(int bin, int rate) {
        return (int) ((long) bin * BIN_US * rate / 1_000_000);
    }

    private static double rms(float[] samples, int from, int to) {
        double squares = 0;
        for (int i = from; i < to; i++) squares += (double) samples[i] * samples[i];
        return Math.sqrt(squares / (to - from));
    }

    private static boolean periodicTail(ReferenceAudio audio, double hz, int start, int end) {
        int rate = audio.sampleRate();
        double period = rate / hz;
        for (int bin = start; bin < Math.min(end - 2, start + 6); bin++) {
            int from = frame(bin, rate);
            int to = frame(bin + 3, rate);
            // Look for periodic continuation near the score pitch and its subharmonics.
            for (int multiple = 1; multiple <= 4; multiple++) {
                double lag = multiple * period;
                if (lag < 2 || lag > (to - from) / 2.0) continue;
                for (int step = -2; step <= 2; step++) {
                    if (correlation(audio.samples(), from, to, lag * (1 + step * .02)) > .60) return true;
                }
            }
        }
        return false;
    }

    private static double correlation(float[] samples, int from, int to, double lag) {
        int offset = (int) lag;
        double fraction = lag - offset;
        double cross = 0, firstEnergy = 0, secondEnergy = 0;
        for (int i = from; i + offset + 1 < to; i++) {
            double first = samples[i];
            double second = samples[i + offset] * (1 - fraction) + samples[i + offset + 1] * fraction;
            cross += first * second;
            firstEnergy += first * first;
            secondEnergy += second * second;
        }
        return cross / Math.sqrt(firstEnergy * secondEnergy);
    }

    private static double decayTime(double[] levels, int start, int end) {
        if (20 * Math.log10(levels[start] / levels[end - 1]) < 12) return Double.NaN;
        double center = (start + end - 1) / 2.0;
        double mean = 0, covariance = 0, xVariance = 0;
        for (int bin = start; bin < end; bin++) {
            double db = 20 * Math.log10(levels[bin]);
            double centered = bin - center;
            mean += db;
            covariance += centered * db;
            xVariance += centered * centered;
        }
        mean /= end - start;
        double slope = covariance / xVariance;
        double residuals = 0;
        for (int bin = start; bin < end; bin++) {
            double error = 20 * Math.log10(levels[bin]) - mean - slope * (bin - center);
            residuals += error * error;
        }
        if (slope >= 0 || residuals > 4 * (end - start)) return Double.NaN;
        return -60 * BIN_US / (1e6 * slope);
    }

    private static Estimate estimate(int partId, boolean stem, List<Probe> probes) {
        int tails = (int) probes.stream().filter(Probe::tail).count();
        int dry = probes.size() - tails;
        var decay = probes.stream().map(Probe::decay).filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(Decay::seconds)).toList();
        if (!decay.isEmpty()) {
            double median = decay.get(decay.size() / 2).seconds();
            var agreement = decay.stream().filter(value -> value.seconds() >= median / 1.5
                    && value.seconds() <= median * 1.5).toList();
            if (agreement.size() >= MIN_PROBES && agreement.size() * 2 > probes.size()) {
                var ratios = agreement.stream().map(Decay::amplitudeRatio).sorted().toList();
                return new Estimate(partId, stem, "decay-supported", probes.size(), dry, tails,
                        agreement.size(), median, ratios.get(ratios.size() / 2));
            }
        }
        String status = "insufficient-evidence";
        if (tails > 0) status = "tail-unresolved";
        else if (dry >= MIN_PROBES) status = "dry-like";
        return new Estimate(partId, stem, status, probes.size(), dry, tails, decay.size(), null, null);
    }
}
