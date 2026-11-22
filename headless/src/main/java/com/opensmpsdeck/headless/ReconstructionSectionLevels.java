package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fits part gain in the project clock against active windows in the source stem. */
final class ReconstructionSectionLevels {
    private static final double ACTIVITY_FLOOR = 1e-6; // -60 dBFS power
    private static final double RELATIVE_ACTIVITY = .01; // 20 dB below the section's loudest frame
    private static final double LIMIT_DB = 12;

    record Gain(int part, String section, double projectStart, double projectEnd, double db,
                int activeFrames, Double requestedDb) {}

    @FunctionalInterface
    interface SoloProbe {
        TargetPlayback prepare(int part) throws IOException;
    }

    private ReconstructionSectionLevels() {}

    static List<Gain> fit(Path work, ReconstructionStages.Score score, ReconstructionStructure.Result structure,
                          List<ReconstructionArrangement.SectionFit> rules, SoloProbe probe) throws IOException {
        if (rules.isEmpty()) return List.of();
        var timeline = ReconstructionTimeMap.of(score);
        var sections = ReconstructionSections.load(work, score, structure).sections().stream()
                .filter(section -> section.level() == ReconstructionSections.Level.section)
                .sorted(Comparator.comparingDouble(ReconstructionSections.Section::projectStart)
                        .thenComparing(ReconstructionSections.Section::id)).toList();
        if (sections.isEmpty()) return List.of();
        var result = new ArrayList<Gain>();
        for (var rule : rules) {
            var rendered = power(probe.prepare(rule.part()), timeline.frames());
            var stem = ReferenceAudio.evidenceRange(Path.of(score.inputs().stems().get(rule.part())),
                    0, timeline.sourceDuration());
            var reference = sourcePower(stem, timeline);
            Double previous = null;
            for (var section : sections) {
                int from = frame(section.projectStart(), rendered.length), to = frame(section.projectEnd(), rendered.length);
                double peak = 0;
                for (int i = from; i < to; i++) peak = Math.max(peak, reference[i]);
                double threshold = Math.max(ACTIVITY_FLOOR, peak * RELATIVE_ACTIVITY);
                double target = 0, actual = 0;
                int active = 0;
                for (int i = from; i < to; i++) {
                    if (reference[i] < threshold) continue;
                    target += reference[i];
                    actual += rendered[i];
                    active++;
                }
                Double requested = active == 0 || actual / active < 1e-10
                        ? null : 10 * Math.log10(target / actual);
                double fitted = boundedGain(requested, previous, rule.maxJumpDb());
                result.add(new Gain(rule.part(), section.id(), section.projectStart(), section.projectEnd(), fitted, active, requested));
                previous = fitted;
            }
        }
        return List.copyOf(result);
    }

    static double boundedGain(Double requested, Double previous, double maxJump) {
        double fit = requested == null ? (previous == null ? 0 : previous)
                : Math.clamp(requested, -LIMIT_DB, LIMIT_DB);
        return previous == null ? fit : Math.clamp(fit, previous - maxJump, previous + maxJump);
    }

    static Map<String, Double> noteGains(List<Gain> gains, List<ReconstructionAllocation.Entry> notes) {
        if (gains.isEmpty()) return Map.of();
        var result = new HashMap<String, Double>();
        for (var note : notes) {
            if (note.startTick() == null) continue;
            for (var gain : gains) {
                if (gain.part() == note.note().part() && note.note().start() >= gain.projectStart()
                        && note.note().start() < gain.projectEnd()) {
                    result.put(note.note().id(), Math.pow(10, gain.db() / 20));
                }
            }
        }
        return result;
    }

    private static int frame(double time, int frames) {
        return Math.clamp((int) Math.ceil(time * 60 - 1e-9), 0, frames);
    }

    private static double[] power(TargetPlayback playback, int frames) throws IOException {
        double[] levels = new double[frames];
        int[] frame = {0};
        playback.render(frames, pcm -> {
            double power = 0, sum = 0;
            for (int i = 0; i < pcm.length; i += 2) {
                double mono = (pcm[i] + (double) pcm[i + 1]) / 65536;
                sum += mono;
                power += mono * mono;
            }
            levels[frame[0]++] = variance(power, sum, pcm.length / 2);
        });
        return levels;
    }

    private static double[] sourcePower(EvidenceSpectrum.Audio audio, ReconstructionTimeMap timeline) {
        var levels = new double[timeline.frames()];
        int start = 0;
        for (int frame = 0; frame < levels.length; frame++) {
            int end = Math.clamp((int) Math.round(timeline.source((frame + 1) / 60.0) * audio.rate()),
                    start, audio.samples().length);
            double power = 0, sum = 0;
            for (int i = start; i < end; i++) {
                double sample = audio.samples()[i];
                sum += sample;
                power += sample * sample;
            }
            if (end > start) levels[frame] = variance(power, sum, end - start);
            start = end;
        }
        return levels;
    }
    private static double variance(double squares, double sum, int samples) {
        double mean = sum / samples;
        return Math.max(0, squares / samples - mean * mean);
    }

}
