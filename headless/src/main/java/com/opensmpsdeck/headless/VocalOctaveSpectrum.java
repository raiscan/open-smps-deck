package com.opensmpsdeck.headless;

import java.util.Arrays;

import static com.opensmpsdeck.headless.ReconstructionLeadAudio.HIGH;
import static com.opensmpsdeck.headless.ReconstructionLeadAudio.LOW;
import static com.opensmpsdeck.headless.ReconstructionLeadAudio.hz;

/** Compare octave templates in windows wholly inside a vocal gate. */
final class VocalOctaveSpectrum {
    private static final int BINS = 8192;
    private final LeadEffectMatcher.Spectrum fft = new LeadEffectMatcher.Spectrum(BINS);
    private final double[] power = new double[BINS / 2];
    private final float[] samples;
    private final double rate;

    VocalOctaveSpectrum(float[] samples, double rate) {
        this.samples = samples;
        this.rate = rate;
    }

    double[] measure(double start, double end) {
        var scores = new double[HIGH - LOW + 1];
        int first = Math.clamp((int) Math.ceil((start + .01) * rate), 0, samples.length);
        int last = Math.clamp((int) Math.floor((end - .01) * rate), first, samples.length);
        int length = Math.min(last - first, (int) (.12 * rate));
        if (length < .075 * rate) return scores;

        var window = new double[length];
        for (int i = 0; i < length; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (length - 1));
        Arrays.fill(power, 0);
        int steps = Math.min(6, (last - first - length) / Math.max(1, length / 2));
        for (int step = 0; step <= steps; step++) {
            int from = steps == 0 ? (first + last - length) / 2
                    : first + step * (last - first - length) / steps;
            fft.transform(samples, from, window);
            for (int bin = 1; bin < power.length; bin++) power[bin] += fft.power(bin);
        }

        for (int lower = LOW; lower + 12 <= HIGH; lower++) {
            double frequency = hz(lower);
            double fundamental = peak(frequency), second = peak(2 * frequency);
            double odd = (peak(3 * frequency) + peak(5 * frequency) + peak(7 * frequency)) / 3;
            double even = (peak(4 * frequency) + peak(6 * frequency) + peak(8 * frequency)) / 3;
            double reference = Math.max(second, even);
            double oddStrength = Math.max(fundamental - second - 5, odd - even);
            // A lower octave has energy on its own odd partials. A spectrum with
            // no appreciable energy at the shared harmonics casts no vote.
            double energy = Math.clamp((reference - 4) / 15, 0, 1);
            double lowerVote = energy * Math.clamp((oddStrength + 7) / 9, 0, 1);
            double upperVote = energy * Math.clamp((-oddStrength - 13) / 15, 0, 1);
            scores[lower - LOW] += lowerVote - upperVote;
            scores[lower + 12 - LOW] += upperVote - lowerVote;
        }
        return scores;
    }

    private double peak(double frequency) {
        double center = frequency * BINS / rate;
        int first = Math.max(1, (int) Math.floor(center * .97));
        int last = Math.min(power.length - 1, (int) Math.ceil(center * 1.03));
        double value = 0;
        for (int bin = first; bin <= last; bin++) value = Math.max(value, power[bin]);
        return 10 * Math.log10(Math.max(1e-12, value));
    }
}
