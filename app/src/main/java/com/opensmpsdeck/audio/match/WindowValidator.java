package com.opensmpsdeck.audio.match;

/**
 * Audio-domain validation of a candidate matching window. The MIDI tells us a
 * note *should* be isolated here; this class checks the audio actually contains
 * that note (stems carry reverb/delay tails of other notes, sub-octave bass
 * layers, and slight misalignment), and anchors the fingerprint to the pitch
 * the audio really plays.
 */
public final class WindowValidator {

    /** @param anchoredHz the measured fundamental to fingerprint against */
    public record Validation(boolean usable, double anchoredHz, double harmonicity,
                             String reason) {
        static Validation rejected(String reason) {
            return new Validation(false, 0, 0, reason);
        }
    }

    /** Accept measured F0 within this factor of the label (≈ half a semitone). */
    private static final double PITCH_TOLERANCE = 1.03;
    /** Octave candidates: sub-bass layers and octave-up doublings are common. */
    private static final double[] OCTAVE_FACTORS = {1.0, 0.5, 2.0};
    private static final double MIN_HARMONICITY = 0.45;
    /** Energy at h1 relative to the strongest harmonic band (-20 dB). */
    private static final double MIN_FUNDAMENTAL_PRESENCE = 0.01;
    private static final double ONSET_FRACTION = 0.15; // of slice peak RMS

    private WindowValidator() {}

    /**
     * Checks a window slice against its MIDI-labeled pitch. Accepts the label
     * itself or an octave above/below (anchoring to the measured fundamental);
     * rejects slices whose dominant pitch is unrelated or whose energy is not
     * harmonic (reverb mush, bleed, noise).
     */
    public static Validation validate(float[] slice, int sampleRate, double labeledHz) {
        double f0 = estimateF0(slice, sampleRate,
                Math.max(25, labeledHz * 0.4), Math.min(2500, labeledHz * 2.6));
        if (f0 <= 0) return Validation.rejected("no stable pitch in window");

        double matchedHz = -1;
        for (double factor : OCTAVE_FACTORS) {
            double expected = labeledHz * factor;
            double ratio = f0 > expected ? f0 / expected : expected / f0;
            if (ratio <= PITCH_TOLERANCE) {
                matchedHz = f0;
                break;
            }
        }
        if (matchedHz < 0) {
            return Validation.rejected(String.format(
                    "window audio plays %.0f Hz, not the labeled %.0f Hz", f0, labeledHz));
        }
        double harm = harmonicity(slice, sampleRate, matchedHz);
        if (harm < MIN_HARMONICITY) {
            return Validation.rejected(String.format(
                    "window audio is not harmonic enough (%.2f)", harm));
        }
        // an unrelated tone can masquerade as a subharmonic (e.g. 258 Hz audio
        // "matching" an 86 Hz candidate via its 3rd harmonic) — require real
        // energy at the fundamental itself
        if (fundamentalPresence(slice, sampleRate, matchedHz) < MIN_FUNDAMENTAL_PRESENCE) {
            return Validation.rejected(String.format(
                    "no energy at the matched fundamental %.0f Hz", matchedHz));
        }
        return new Validation(true, matchedHz, harm, null);
    }

    /**
     * Fundamental estimate via normalized autocorrelation over the post-attack
     * region — robust for low bass where FFT bins smear adjacent harmonics.
     * Returns -1 if no clear periodicity.
     */
    public static double estimateF0(float[] audio, int sampleRate, double minHz, double maxHz) {
        int start = Math.min((int) (0.05 * sampleRate), Math.max(0, audio.length / 4));
        int win = Math.min(audio.length - start, sampleRate / 2); // up to 0.5 s
        int maxLag = (int) (sampleRate / minHz);
        int minLag = Math.max(2, (int) (sampleRate / maxHz));
        if (win < maxLag * 2) {
            win = audio.length; // short slice: use everything
            start = 0;
            if (win < maxLag * 2) maxLag = win / 2;
        }
        if (maxLag <= minLag) return -1;

        double energy = 0;
        for (int i = start; i < start + win; i++) energy += audio[i] * audio[i];
        if (energy < 1e-9) return -1;

        // normalized autocorrelation, compensated for the shrinking overlap so
        // long lags aren't penalized relative to short ones
        double[] corrs = new double[maxLag + 1];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double corr = 0;
            for (int i = start; i < start + win - lag; i++) {
                corr += audio[i] * audio[i + lag];
            }
            corrs[lag] = corr / energy * win / (double) (win - lag);
        }
        // smooth signals correlate highly at ALL small lags — only start the
        // peak search after the correlation has dipped once (standard ACF trick)
        int searchFrom = minLag;
        while (searchFrom <= maxLag && corrs[searchFrom] > 0.7) searchFrom++;
        if (searchFrom > maxLag) return -1; // never dips: no periodicity in range

        int bestLag = -1;
        double bestCorr = 0;
        for (int lag = searchFrom; lag <= maxLag; lag++) {
            if (corrs[lag] > bestCorr) { bestCorr = corrs[lag]; bestLag = lag; }
        }
        if (bestLag < 0 || bestCorr < 0.3) return -1;
        // octave-error guard: prefer the shortest lag whose correlation is
        // nearly as strong as the best (autocorrelation peaks at multiples)
        for (int lag = searchFrom; lag < bestLag; lag++) {
            if (bestLag / lag >= 2
                    && Math.abs(bestLag - lag * Math.round((double) bestLag / lag)) <= 2
                    && corrs[lag] > bestCorr * 0.9) {
                bestLag = lag;
                bestCorr = corrs[lag];
                break;
            }
        }
        // parabolic interpolation around the peak for sub-sample lag precision
        // (integer lags quantize pitch to ~6 cents at 150 Hz — too coarse for
        // vibrato tracking)
        double refined = bestLag;
        if (bestLag > minLag && bestLag < maxLag
                && corrs[bestLag - 1] > 0 && corrs[bestLag + 1] > 0) {
            double y0 = corrs[bestLag - 1], y1 = corrs[bestLag], y2 = corrs[bestLag + 1];
            double denom = y0 - 2 * y1 + y2;
            if (Math.abs(denom) > 1e-12) {
                double delta = 0.5 * (y0 - y2) / denom;
                if (Math.abs(delta) < 1) refined = bestLag + delta;
            }
        }
        return sampleRate / refined;
    }

    /**
     * Fraction of spectral energy (30 Hz – 8 kHz) that lies within ±3% of the
     * first 16 harmonics of f0. Clean tones approach 1; reverb mush and noise
     * fall well below {@link #MIN_HARMONICITY}.
     */
    public static double harmonicity(float[] audio, int sampleRate, double f0) {
        int n = 8192;
        if (audio.length < n) n = Integer.highestOneBit(Math.max(2, audio.length));
        double[] re = new double[n];
        double[] im = new double[n];
        int start = Math.max(0, (audio.length - n) / 2);
        for (int i = 0; i < n; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            re[i] = audio[start + i] * hann;
        }
        Fft.transform(re, im);
        double binHz = (double) sampleRate / n;
        int lo = Math.max(1, (int) (30 / binHz));
        int hi = Math.min(n / 2 - 1, (int) (8000 / binHz));
        double total = 0;
        double[] mag2 = new double[hi + 1];
        for (int b = lo; b <= hi; b++) {
            mag2[b] = re[b] * re[b] + im[b] * im[b];
            total += mag2[b];
        }
        if (total < 1e-12) return 0;
        double harmonic = 0;
        for (int h = 1; h <= 16; h++) {
            double hz = f0 * h;
            int hLo = Math.max(lo, (int) ((hz * 0.97) / binHz));
            int hHi = Math.min(hi, (int) Math.ceil((hz * 1.03) / binHz));
            for (int b = hLo; b <= hHi; b++) {
                harmonic += mag2[b];
                mag2[b] = 0; // avoid double counting overlapping harmonic bands
            }
        }
        return harmonic / total;
    }

    /**
     * Energy within ±3% of f0 itself, relative to the strongest harmonic band
     * of f0 — distinguishes a true fundamental from a subharmonic ghost.
     */
    static double fundamentalPresence(float[] audio, int sampleRate, double f0) {
        int n = 8192;
        if (audio.length < n) n = Integer.highestOneBit(Math.max(2, audio.length));
        double[] re = new double[n];
        double[] im = new double[n];
        int start = Math.max(0, (audio.length - n) / 2);
        for (int i = 0; i < n; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            re[i] = audio[start + i] * hann;
        }
        Fft.transform(re, im);
        double binHz = (double) sampleRate / n;
        double h1 = 0, strongest = 1e-12;
        for (int h = 1; h <= 16; h++) {
            double hz = f0 * h;
            int lo = Math.max(1, (int) ((hz * 0.97) / binHz));
            int hi = Math.min(n / 2 - 1, (int) Math.ceil((hz * 1.03) / binHz));
            double band = 0;
            for (int b = lo; b <= hi; b++) band += re[b] * re[b] + im[b] * im[b];
            if (h == 1) h1 = band;
            strongest = Math.max(strongest, band);
        }
        return h1 / strongest;
    }

    /**
     * First sample index where short-window RMS reaches {@link #ONSET_FRACTION}
     * of the slice's peak RMS — used to trim leading silence from misaligned
     * windows.
     */
    public static int findOnset(float[] audio, int sampleRate) {
        int hop = sampleRate / 100; // 10 ms
        int hops = Math.max(1, audio.length / hop);
        double[] rms = new double[hops];
        double peak = 1e-12;
        for (int i = 0; i < hops; i++) {
            double sum = 0;
            int n = Math.min(hop, audio.length - i * hop);
            for (int j = 0; j < n; j++) {
                double v = audio[i * hop + j];
                sum += v * v;
            }
            rms[i] = Math.sqrt(sum / Math.max(1, n));
            peak = Math.max(peak, rms[i]);
        }
        for (int i = 0; i < hops; i++) {
            if (rms[i] >= peak * ONSET_FRACTION) return i * hop;
        }
        return 0;
    }
}
