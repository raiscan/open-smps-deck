package com.opensmpsdeck.headless;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Source-clock GM attacks from an aligned drum stem. */
interface AudioDrumDetector {
    String id();
    String version();
    List<Hit> detect(Path stem, double seconds) throws IOException;

    record Hit(double seconds, int pitch, int velocity) {}

    static AudioDrumDetector select(String name) {
        if (name == null || name.equals("spectral")) return new SpectralDrumDetector();
        throw new IllegalArgumentException("Unknown drum detector: " + name + " (available: spectral)");
    }
}

final class SpectralDrumDetector implements AudioDrumDetector {
    private static final int FFT = 1024;
    private static final double STEP = .005;
    private static final int KICK = 0, SNARE = 1, HAT = 2;
    private static final int[] PITCH = {36, 38, 42};
    private static final int[] REFRACTORY = {14, 12, 9};
    private static final double[] WINDOW = new double[FFT];

    static {
        for (int i = 0; i < FFT; i++) WINDOW[i] = .5 - .5 * Math.cos(Math.TAU * i / FFT);
    }

    @Override public String id() { return "spectral"; }
    @Override public String version() { return "1"; }

    @Override public List<Hit> detect(Path stem, double seconds) throws IOException {
        return detect(ReconstructionLeadAudio.readMono(stem, seconds));
    }

    static List<Hit> detect(ReferenceAudio audio) throws InterruptedIOException {
        var spectrum = new LeadEffectMatcher.Spectrum(FFT);
        int hop = (int) Math.round(STEP * audio.sampleRate());
        int frames = (audio.samples().length + hop - 1) / hop;
        var levels = new double[3][frames];
        var flux = new double[3][frames];
        var bins = new int[FFT / 2];
        for (int k = 1; k < bins.length; k++) {
            double hz = k * (double) audio.sampleRate() / FFT;
            bins[k] = hz < 34 ? -1 : hz < 135 ? KICK : hz < 3100 ? SNARE : HAT;
        }
        for (int i = 0; i < frames; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Drum detection cancelled");
            spectrum.transform(audio.samples(), i * hop - FFT / 2, WINDOW);
            for (int k = 1; k < bins.length; k++) {
                int band = bins[k];
                if (band >= 0) levels[band][i] += spectrum.power(k);
            }
            for (int b = 0; b < levels.length; b++) {
                levels[b][i] = Math.sqrt(levels[b][i]) / FFT;
                flux[b][i] = Math.max(0, levels[b][i] - (i >= 2 ? levels[b][i - 2] : 0));
            }
        }

        var hits = new ArrayList<Hit>();
        for (int band = 0; band < flux.length; band++) {
            var envelope = flux[band];
            double strongest = java.util.Arrays.stream(envelope).max().orElse(0);
            double[] cumulative = new double[frames + 1];
            for (int i = 0; i < frames; i++) cumulative[i + 1] = cumulative[i] + envelope[i];
            int last = -REFRACTORY[band];
            for (int i = 0; i < frames; i++) {
                int from = Math.max(0, i - 30), to = Math.min(frames, i + 31);
                double floor = 3 * (cumulative[to] - cumulative[from]) / (to - from);
                double rise = envelope[i];
                if (rise < Math.max(.0015, Math.max(.045 * strongest, floor)) ||
                        i - last < REFRACTORY[band] || !peak(envelope, i)) continue;
                // Compare new energy, excluding the decay of preceding hits.
                double low = increase(levels[KICK], i);
                double mid = increase(levels[SNARE], i);
                double high = increase(levels[HAT], i);
                boolean plausible = switch (band) {
                    case KICK -> low >= .75 * mid;
                    case SNARE -> mid >= .54 * low && mid >= .75 * high;
                    default -> high >= 1.15 * mid || high >= .55 * mid && low >= 1.3 * mid;
                };
                if (!plausible) continue;
                double onset = Math.max(0, (i - 1) * hop / (double) audio.sampleRate());
                int velocity = Math.clamp((int) Math.round(48 + 72 * Math.sqrt(rise / strongest)), 1, 127);
                hits.add(new Hit(onset, PITCH[band], velocity));
                last = i;
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::seconds).thenComparingInt(Hit::pitch));
        return List.copyOf(hits);
    }

    private static boolean peak(double[] envelope, int i) {
        for (int j = Math.max(0, i - 3); j <= Math.min(envelope.length - 1, i + 3); j++) {
            if (envelope[j] > envelope[i] || (envelope[j] == envelope[i] && j < i)) return false;
        }
        return true;
    }

    private static double increase(double[] levels, int i) {
        double baseline = i < 5 ? 0 : levels[i - 5];
        double peak = levels[i];
        for (int j = i + 1; j < Math.min(levels.length, i + 4); j++) peak = Math.max(peak, levels[j]);
        return Math.max(0, peak - baseline);
    }
}
