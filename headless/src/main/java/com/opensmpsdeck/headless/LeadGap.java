package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Source-time seconds of reference lead missed by FM1, per evaluation minute. */
public final class LeadGap {
    public record Interval(double start, double end) {
        boolean contains(double time) { return start <= time && time < end; }
    }

    public record Measurement(double leadGap, double missedSeconds, double activeSeconds, double evaluatedSeconds) {}

    private LeadGap() {}

    public static Measurement measure(Path work, List<Interval> gates, List<Interval> regions,
                                      List<Interval> annotatedLead, Path stem) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var timeline = ReconstructionTimeMap.of(score);
        var sourceGates = gates.stream()
                .map(gate -> new Interval(timeline.source(gate.start), timeline.source(gate.end))).toList();
        var track = stem == null ? null : ReconstructionLeadAudio.Track.read(stem, timeline.sourceDuration());
        boolean[] reference = track == null ? null : track.referenceActivity();
        double duration = track == null ? timeline.sourceDuration() : track.duration();
        double evaluated = 0, active = 0, missed = 0;
        int gate = 0;
        for (int frame = 0; frame * ReconstructionLeadAudio.HOP < duration; frame++) {
            double start = frame * ReconstructionLeadAudio.HOP;
            double width = Math.min(ReconstructionLeadAudio.HOP, duration - start);
            double center = start + width / 2;
            if (reference == null && regions.stream().noneMatch(region -> region.contains(center))) continue;
            evaluated += width;
            if (reference == null ? annotatedLead.stream().noneMatch(note -> note.contains(center)) : !reference[frame])
                continue;
            active += width;
            while (gate < sourceGates.size() && sourceGates.get(gate).end <= center) gate++;
            if (gate == sourceGates.size() || !sourceGates.get(gate).contains(center)) missed += width;
        }
        if (evaluated == 0) throw new IllegalArgumentException("Lead gap needs a nonempty evaluation interval");
        return new Measurement(60 * missed / evaluated, missed, active, evaluated);
    }
}
