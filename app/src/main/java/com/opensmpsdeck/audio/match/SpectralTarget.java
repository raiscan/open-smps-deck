package com.opensmpsdeck.audio.match;

import java.util.Arrays;

/**
 * Timbre fingerprint of an audio slice with a known fundamental:
 * log-magnitude levels of the first 16 harmonics, the inter-harmonic valley
 * levels between them (both dB, peak-normalized to 0), and a peak-normalized
 * RMS envelope (10 ms hops, fixed 64 points).
 *
 * <p>The valley levels are what make feedback noise and detune beating visible
 * to the distance metric — a maximally noisy patch and a clean one can have
 * near-identical harmonic peaks while differing wildly between them.
 */
public record SpectralTarget(double[] harmonicLevels, double[] valleyLevels,
                             double[] rmsEnvelope, double fundamentalHz) {

    public static final int HARMONICS = 16;
    public static final int VALLEYS = HARMONICS - 1;
    public static final int ENVELOPE_POINTS = 64;
    private static final int FFT_SIZE = 2048;
    /** Below this fundamental, 2048-point bins (21.5 Hz) smear adjacent harmonics. */
    private static final double LOW_PITCH_HZ = 150.0;
    private static final int FFT_SIZE_LOW = 8192;
    private static final double ATTACK_SKIP_SEC = 0.05;
    private static final double DB_FLOOR = -60.0;
    private static final double PEAK_SEARCH = 0.03; // ±3% around each harmonic

    private static final double W_SPEC = 0.5;
    private static final double W_VALLEY = 0.2;
    private static final double W_ENV = 0.3;

    public static SpectralTarget extract(float[] audio, int sampleRate, double fundamentalHz) {
        // --- averaged magnitude spectrum over the sustain (post-attack) ---
        int fftSize = fundamentalHz < LOW_PITCH_HZ ? FFT_SIZE_LOW : FFT_SIZE;
        int start = (int) (ATTACK_SKIP_SEC * sampleRate);
        // shrink for short slices so at least one frame fits
        while (fftSize > 256 && start + fftSize > audio.length) fftSize >>= 1;
        int hop = fftSize / 2;

        double[] avgMag = new double[fftSize / 2];
        int frameCount = 0;
        for (int off = start; off + fftSize <= audio.length; off += hop) {
            double[] re = new double[fftSize];
            double[] im = new double[fftSize];
            for (int i = 0; i < fftSize; i++) {
                double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (fftSize - 1));
                re[i] = audio[off + i] * hann;
            }
            Fft.transform(re, im);
            for (int i = 0; i < fftSize / 2; i++) avgMag[i] += Math.hypot(re[i], im[i]);
            frameCount++;
        }
        if (frameCount == 0) frameCount = 1;
        for (int i = 0; i < avgMag.length; i++) avgMag[i] /= frameCount;

        double binHz = (double) sampleRate / fftSize;
        double[] levels = new double[HARMONICS];
        for (int h = 1; h <= HARMONICS; h++) {
            levels[h - 1] = bandPeakDb(avgMag, fundamentalHz * h, binHz);
        }
        double[] valleys = new double[VALLEYS];
        for (int h = 1; h <= VALLEYS; h++) {
            valleys[h - 1] = bandPeakDb(avgMag, fundamentalHz * (h + 0.5), binHz);
        }
        double max = Arrays.stream(levels).max().orElse(0);
        for (int i = 0; i < levels.length; i++) {
            levels[i] = Math.max(levels[i] - max, DB_FLOOR);
        }
        for (int i = 0; i < valleys.length; i++) {
            valleys[i] = Math.max(valleys[i] - max, DB_FLOOR);
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
        return new SpectralTarget(levels, valleys, envelope, fundamentalHz);
    }

    /** Peak magnitude (dB) within ±3% of centerHz. */
    private static double bandPeakDb(double[] avgMag, double centerHz, double binHz) {
        int lo = (int) Math.max(1, (centerHz * (1 - PEAK_SEARCH)) / binHz);
        int hi = (int) Math.min(avgMag.length - 1, (centerHz * (1 + PEAK_SEARCH)) / binHz);
        double peak = 0;
        for (int b = lo; b <= hi; b++) peak = Math.max(peak, avgMag[b]);
        return 20 * Math.log10(Math.max(peak, 1e-12));
    }

    /** Weighted distance: harmonic dB MSE + valley dB MSE + envelope MSE. */
    public static double distance(SpectralTarget a, SpectralTarget b) {
        double spec = 0;
        for (int i = 0; i < HARMONICS; i++) {
            double d = a.harmonicLevels()[i] - b.harmonicLevels()[i];
            spec += d * d;
        }
        spec /= HARMONICS * DB_FLOOR * DB_FLOOR; // normalize to ~[0,1]
        double valley = 0;
        for (int i = 0; i < VALLEYS; i++) {
            double d = a.valleyLevels()[i] - b.valleyLevels()[i];
            valley += d * d;
        }
        valley /= VALLEYS * DB_FLOOR * DB_FLOOR;
        double env = 0;
        for (int i = 0; i < ENVELOPE_POINTS; i++) {
            double d = a.rmsEnvelope()[i] - b.rmsEnvelope()[i];
            env += d * d;
        }
        env /= ENVELOPE_POINTS;
        return W_SPEC * spec + W_VALLEY * valley + W_ENV * env;
    }
}
