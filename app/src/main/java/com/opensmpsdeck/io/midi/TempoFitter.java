package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.SmpsMode;

import java.util.ArrayList;
import java.util.List;

/** Flattens a MIDI tempo map and fits SMPS tempo byte + dividing timing to it. */
public final class TempoFitter {

    private static final double FRAME_RATE = 60.0;
    private static final int MAX_DIVIDING_TIMING = 32;
    private static final int MAX_UNITS_PER_SIXTEENTH = 16;

    public record TempoFit(double bpm, int tempoByte, int dividingTiming,
                           int unitsPerSixteenth, double errorPercent) {}

    private TempoFitter() {}

    public static TempoFit fit(List<MidiStem.TempoEvent> tempoMap, long totalTicks,
                               int ppq, SmpsMode mode) {
        double bpm = weightedMedianBpm(tempoMap, totalTicks, ppq);
        double idealSixteenthSec = 15.0 / bpm;

        TempoFit best = null;
        for (int div = 1; div <= MAX_DIVIDING_TIMING; div++) {
            for (int tempo = 1; tempo <= 0xFF; tempo++) {
                double tpf = TempoMath.ticksPerFrame(mode, tempo);
                if (tpf <= 0) continue;
                double secondsPerUnit = div / (FRAME_RATE * tpf);
                int units = (int) Math.round(idealSixteenthSec / secondsPerUnit);
                if (units < 1 || units > MAX_UNITS_PER_SIXTEENTH) continue;
                double actual = units * secondsPerUnit;
                double errPct = Math.abs(actual - idealSixteenthSec) / idealSixteenthSec * 100;
                // prefer lower error; tie-break toward smaller duration bytes
                double score = errPct + units * 0.01 + div * 0.001;
                if (best == null || score < bestScore(best)) {
                    best = new TempoFit(bpm, tempo, div, units, errPct);
                }
            }
        }
        return best;
    }

    private static double bestScore(TempoFit f) {
        return f.errorPercent() + f.unitsPerSixteenth() * 0.01 + f.dividingTiming() * 0.001;
    }

    /** BPM whose tempo-map segments cover the median tick (duration-weighted). */
    static double weightedMedianBpm(List<MidiStem.TempoEvent> tempoMap, long totalTicks, int ppq) {
        if (tempoMap.isEmpty()) return 120.0;
        record Seg(double bpm, long ticks) {}
        List<Seg> segs = new ArrayList<>();
        for (int i = 0; i < tempoMap.size(); i++) {
            long start = tempoMap.get(i).tick();
            long end = i + 1 < tempoMap.size() ? tempoMap.get(i + 1).tick() : totalTicks;
            if (end <= start) continue;
            segs.add(new Seg(60e6 / tempoMap.get(i).microsecondsPerQuarter(), end - start));
        }
        segs.sort((a, b) -> Double.compare(a.bpm(), b.bpm()));
        long half = segs.stream().mapToLong(Seg::ticks).sum() / 2;
        long acc = 0;
        for (Seg s : segs) {
            acc += s.ticks();
            if (acc >= half) return s.bpm();
        }
        return segs.get(segs.size() - 1).bpm();
    }
}
