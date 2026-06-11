package com.opensmpsdeck.audio.match;

import java.util.Arrays;

/**
 * Timbre fingerprint of an audio slice with a known fundamental:
 * log-magnitude levels of the first 16 harmonics (dB, peak-normalized to 0)
 * plus a peak-normalized RMS envelope (10 ms hops, fixed 64 points).
 */
public record SpectralTarget(double[] harmonicLevels, double[] rmsEnvelope,
                             double fundamentalHz) {

    public static final int HARMONICS = 16;
    public static final int ENVELOPE_POINTS = 64;
    private static final int FFT_SIZE = 2048;
    private static final int HOP = 1024;
    private static final double ATTACK_SKIP_SEC = 0.05;
    private static final double DB_FLOOR = -60.0;
    private static final double PEAK_SEARCH = 0.03; // ±3% around each harmonic

    public static SpectralTarget extract(float[] audio, int sampleRate, double fundamentalHz) {
        // --- averaged magnitude spectrum over the sustain (post-attack) ---
        double[] avgMag = new double[FFT_SIZE / 2];
        int start = (int) (ATTACK_SKIP_SEC * sampleRate);
        int frameCount = 0;
        for (int off = start; off + FFT_SIZE <= audio.length; off += HOP) {
            double[] re = new double[FFT_SIZE];
            double[] im = new double[FFT_SIZE];
            for (int i = 0; i < FFT_SIZE; i++) {
                double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
                re[i] = audio[off + i] * hann;
            }
            Fft.transform(re, im);
            for (int i = 0; i < FFT_SIZE / 2; i++) avgMag[i] += Math.hypot(re[i], im[i]);
            frameCount++;
        }
        if (frameCount == 0) frameCount = 1;
        for (int i = 0; i < avgMag.length; i++) avgMag[i] /= frameCount;

        // --- harmonic levels with ±3% peak search ---
        double binHz = (double) sampleRate / FFT_SIZE;
        double[] levels = new double[HARMONICS];
        for (int h = 1; h <= HARMONICS; h++) {
            double targetHz = fundamentalHz * h;
            int lo = (int) Math.max(1, (targetHz * (1 - PEAK_SEARCH)) / binHz);
            int hi = (int) Math.min(avgMag.length - 1, (targetHz * (1 + PEAK_SEARCH)) / binHz);
            double peak = 0;
            for (int b = lo; b <= hi; b++) peak = Math.max(peak, avgMag[b]);
            levels[h - 1] = 20 * Math.log10(Math.max(peak, 1e-12));
        }
        double max = Arrays.stream(levels).max().orElse(0);
        for (int i = 0; i < levels.length; i++) {
            levels[i] = Math.max(levels[i] - max, DB_FLOOR);
        }

        // --- RMS envelope, resampled to a fixed point count ---
        int hopSamples = sampleRate / 100; // 10 ms
        int hops = Math.max(1, audio.length / hopSamples);
        double[] rms = new double[hops];
        double peakRms = 1e-12;
        for (int i = 0; i < hops; i++) {
            double sum = 0;
            int n = Math.min(hopSamples, audio.length - i * hopSamples);
            for (int j = 0; j < n; j++) {
                double v = audio[i * hopSamples + j];
                sum += v * v;
            }
            rms[i] = Math.sqrt(sum / Math.max(1, n));
            peakRms = Math.max(peakRms, rms[i]);
        }
        double[] envelope = new double[ENVELOPE_POINTS];
        for (int i = 0; i < ENVELOPE_POINTS; i++) {
            envelope[i] = rms[Math.min(hops - 1, i * hops / ENVELOPE_POINTS)] / peakRms;
        }
        return new SpectralTarget(levels, envelope, fundamentalHz);
    }

    /** Weighted distance: harmonic dB MSE (normalized) + envelope MSE. */
    public static double distance(SpectralTarget a, SpectralTarget b) {
        double spec = 0;
        for (int i = 0; i < HARMONICS; i++) {
            double d = a.harmonicLevels()[i] - b.harmonicLevels()[i];
            spec += d * d;
        }
        spec /= HARMONICS * DB_FLOOR * DB_FLOOR; // normalize to ~[0,1]
        double env = 0;
        for (int i = 0; i < ENVELOPE_POINTS; i++) {
            double d = a.rmsEnvelope()[i] - b.rmsEnvelope()[i];
            env += d * d;
        }
        env /= ENVELOPE_POINTS;
        return 0.7 * spec + 0.3 * env;
    }
}
