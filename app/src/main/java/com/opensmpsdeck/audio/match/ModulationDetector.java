package com.opensmpsdeck.audio.match;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures pitch modulation (vibrato) in a window slice by tracking the
 * fundamental over short hops. Undetected vibrato breaks spectral matching
 * twice over: it smears harmonic peaks (deflating their measured level,
 * increasingly with harmonic number) and pushes energy into the inter-harmonic
 * valleys (making a clean tone look noisy). Detected vibrato is instead
 * reproduced on the candidate side so both spectra smear identically.
 */
public final class ModulationDetector {

    /** @param depthCents peak-to-peak depth; 0 = none/untrackable */
    public record Modulation(double depthCents, double rateHz) {
        public static final Modulation NONE = new Modulation(0, 0);

        public boolean significant() {
            return depthCents >= 15; // below ~15 cents the ±3% bands absorb it
        }
    }

    private static final double HOP_SEC = 0.04;
    private static final double WIN_SEC = 0.10;
    private static final int MIN_TRACK_POINTS = 6;

    private ModulationDetector() {}

    public static Modulation measure(float[] audio, int sampleRate, double f0) {
        int hop = (int) (HOP_SEC * sampleRate);
        int win = (int) (WIN_SEC * sampleRate);
        List<Double> track = new ArrayList<>();
        for (int off = 0; off + win <= audio.length; off += hop) {
            float[] frame = java.util.Arrays.copyOfRange(audio, off, off + win);
            double f = WindowValidator.estimateF0(frame, sampleRate, f0 * 0.85, f0 * 1.18);
            if (f > 0) track.add(f);
        }
        if (track.size() < MIN_TRACK_POINTS) return Modulation.NONE;

        // depth: robust peak-to-peak via the 10th/90th percentile of the track
        List<Double> sorted = new ArrayList<>(track);
        sorted.sort(Double::compare);
        double p10 = sorted.get((int) (0.1 * (sorted.size() - 1)));
        double p90 = sorted.get((int) (0.9 * (sorted.size() - 1)));
        if (p10 <= 0) return Modulation.NONE;
        double depthCents = 1200 * Math.log(p90 / p10) / Math.log(2);
        // percentiles of a sinusoid span ~95% of the true peak-to-peak
        depthCents /= 0.95;

        // rate: mean-crossing count of the track / 2 / duration
        double mean = track.stream().mapToDouble(Double::doubleValue).average().orElse(f0);
        int crossings = 0;
        for (int i = 1; i < track.size(); i++) {
            if ((track.get(i - 1) < mean) != (track.get(i) < mean)) crossings++;
        }
        double duration = track.size() * HOP_SEC;
        double rateHz = crossings / 2.0 / duration;
        return new Modulation(Math.max(0, depthCents), rateHz);
    }
}
