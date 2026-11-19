package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

final class ReconstructionEcho {
    private static final int MIN_DELAY = 5;
    private static final int MAX_DELAY = 27;
    private static final int PROBE_TICKS = MAX_DELAY + 4;
    private static final int MAX_PROBES = 64;
    private static final double TICK_US = 1_000_000.0 / PRESENTATIONS_PER_SECOND;

    record Estimate(int partId, boolean stem, String status, int probes, int tailProbes,
                    Integer delayTicks, Double amplitudeRatio, int fitSupport, int checkSupport) {}
    private record Probe(double[] hits, boolean[] eligible, boolean tail) {}
    private record Support(int hits, int eligible, List<Double> amplitude) {
        boolean meets(int minimum) { return hits >= minimum && hits * 2 >= eligible; }
    }

    private ReconstructionEcho() {}

    static int fmVolumeFloor(byte[] patch) {
        int floor = 0;
        // S3K adds attenuation only to carriers, and wraps the result at 128.
        for (int i = 21; i < 25; i++)
            if ((patch[i] & 128) != 0) floor = Math.max(floor, patch[i] & 127);
        return floor;
    }

    static int scaleVolume(int volume, double gain) {
        return gain == 0 ? 0 : Math.clamp(volume + (int) Math.round(20 * Math.log10(gain) / .75), 0, 127);
    }

    static Map<Integer, Estimate> detect(MidiScore score, Path mix, Map<Integer, Path> stems,
                                         long limitUs) throws IOException {
        var result = new LinkedHashMap<Integer, Estimate>();
        for (var part : score.parts()) {
            if (part.drums() || (!stems.containsKey(part.id()) && (part.program() < 80 || part.program() > 87))) continue;
            Path audio = stems.getOrDefault(part.id(), mix).toAbsolutePath().normalize();
            boolean mixed = audio.equals(mix.toAbsolutePath().normalize());
            List<Integer> sharing = stems.entrySet().stream()
                    .filter(entry -> audio.equals(entry.getValue().toAbsolutePath().normalize()))
                    .map(Map.Entry::getKey).toList();
            boolean stem = !mixed && sharing.size() == 1;
            long end = Math.min(limitUs, ReferenceAudio.durationMicros(audio));
            var notes = score.notes().stream().filter(n -> n.startUs() < end
                    && (mixed || sharing.contains(n.partId()))).toList();
            var candidates = notes.stream().filter(n -> n.partId() == part.id() && n.pitch() >= 53 && n.pitch() <= 106
                    && n.startUs() >= TICK_US && n.startUs() + PROBE_TICKS * TICK_US < end
                    && n.endUs() - n.startUs() >= 40_000 && n.endUs() - n.startUs() <= 220_000).toList();
            var sampled = new ArrayList<Probe>();
            long previous = -1_000_000;
            for (int i = 0, count = Math.min(MAX_PROBES, candidates.size()); i < count; i++) {
                var note = candidates.get(i * candidates.size() / count);
                if (note.startUs() - previous < 500_000) continue;
                Probe probe = observe(audio, notes, note, stem);
                if (probe != null) {
                    sampled.add(probe);
                    previous = note.startUs();
                }
            }
            result.put(part.id(), estimate(part.id(), stem, sampled));
        }
        return result;
    }

    private static Probe observe(Path path, List<MidiScore.Note> score, MidiScore.Note note, boolean stem)
            throws IOException {
        long start = note.startUs();
        var window = ReferenceAudio.read(path, (start - TICK_US) / 1e6, PROBE_TICKS * TICK_US / 1e6);
        double[] levels = new double[PROBE_TICKS - 1];
        double hz = 440 * Math.pow(2, (note.pitch() - 69) / 12.0);
        if (hz * 2 >= window.sampleRate()) return null;
        for (int t = 0; t < levels.length; t++) {
            int first = (int) Math.round((t + .5) * window.sampleRate() / PRESENTATIONS_PER_SECOND);
            int end = (int) Math.round((t + 1.5) * window.sampleRate() / PRESENTATIONS_PER_SECOND);
            levels[t] = amplitude(window.samples(), first, end, hz, window.sampleRate());
        }
        int off = (int) Math.ceil((note.endUs() - start) / TICK_US);
        double dry = Arrays.stream(levels, 0, off).max().orElse(0);
        if (dry < .008) return null;
        double[] hits = new double[MAX_DELAY + 1];
        boolean[] eligible = new boolean[MAX_DELAY + 1];
        boolean tail = false;
        for (int delay = off + 1; delay <= MAX_DELAY; delay++) {
            long time = start + Math.round(delay * TICK_US);
            if (contaminated(score, note, time, stem)) continue;
            if (delay <= off + 3 && levels[delay] > dry * .12) tail = true;
            if (delay < MIN_DELAY || delay <= off + 2) continue;
            eligible[delay] = true;
            double ratio = levels[delay] / dry;
            double previous = Math.max(levels[delay - 1], levels[delay - 2]);
            if (ratio >= .09 && ratio <= .65 && levels[delay] >= 1.65 * previous
                    && levels[delay + 1] >= .60 * levels[delay]) {
                hits[delay] = Math.min(.65, Math.max(levels[delay], levels[delay + 1]) / dry);
            }
        }
        return new Probe(hits, eligible, tail);
    }

    private static boolean contaminated(List<MidiScore.Note> score, MidiScore.Note note, long time, boolean stem) {
        int active = 0;
        long end = time + Math.round(TICK_US * 2);
        for (var other : score) {
            if (other.startUs() >= end) break;
            if (other == note || other.endUs() <= time - TICK_US) continue;
            if (other.pitch() == note.pitch() || (!stem && other.pitch() + 12 == note.pitch())) return true;
            if (++active > (stem ? 1 : 2)) return true;
        }
        return false;
    }

    private static double amplitude(float[] samples, int start, int end, double hz, int rate) {
        double re = 0, im = 0;
        int length = end - start;
        double step = 2 * Math.PI * hz / rate;
        for (int i = 0; i < length; i++) {
            double sample = samples[start + i] * (.5 - .5 * Math.cos(2 * Math.PI * i / length));
            re += sample * Math.cos(step * i);
            im += sample * Math.sin(step * i);
        }
        return 4 * Math.hypot(re, im) / length;
    }

    private static Estimate estimate(int partId, boolean stem, List<Probe> probes) {
        int best = MIN_DELAY;
        Support fit = support(probes, best, 0);
        for (int tick = MIN_DELAY + 1; tick <= MAX_DELAY; tick++) {
            Support candidate = support(probes, tick, 0);
            if (candidate.meets(3) && (!fit.meets(3) || candidate.hits() > fit.hits())) {
                best = tick;
                fit = candidate;
            }
        }
        Support check = support(probes, best, 1);
        int tails = (int) probes.stream().filter(Probe::tail).count();
        if (!fit.meets(3) || !check.meets(2)) {
            String status = tails >= 3 ? "tail-unresolved" : "no-repeat-evidence";
            return new Estimate(partId, stem, status, probes.size(), tails, null, null, fit.hits(), check.hits());
        }
        var ratios = new ArrayList<>(fit.amplitude());
        ratios.addAll(check.amplitude());
        ratios.sort(Double::compare);
        return new Estimate(partId, stem, "repeat-supported", probes.size(), tails, best,
                ratios.get(ratios.size() / 2), fit.hits(), check.hits());
    }

    private static Support support(List<Probe> probes, int tick, int parity) {
        int eligible = 0;
        var hits = new ArrayList<Double>();
        for (int i = parity; i < probes.size(); i += 2) {
            var probe = probes.get(i);
            if (!probe.eligible()[tick]) continue;
            eligible++;
            double strength = Math.max(probe.hits()[tick],
                    Math.max(probe.hits()[tick - 1], probe.hits()[Math.min(tick + 1, MAX_DELAY)]));
            if (strength > 0) hits.add(strength);
        }
        return new Support(hits.size(), eligible, hits);
    }
}
