package com.opensmpsdeck.headless;

import java.awt.image.BufferedImage;
import java.util.ArrayList;

/** Centered Hann analysis on native audio time, sampled on a semitone display grid. */
final class EvidenceSpectrum {
    static final double CONTEXT = .21;
    private static final double LOG_2 = Math.log(2);

    record Audio(float[] samples, int rate, double offset, double duration) {}
    private record Level(float[] samples, double rate) {
        Level halfRate() {
            var down = new float[(samples.length + 1) / 2];
            for (int i = 0; i < down.length; i++) {
                int center = 2 * i;
                double sum = 0;
                for (int k = -3; k <= 3; k++) {
                    if (center + k >= 0 && center + k < samples.length)
                        sum += (double) LOW_PASS[k + 3] * samples[center + k];
                }
                down[i] = (float) (sum / 64);
            }
            return new Level(down, rate / 2);
        }
    }

    private record Band(int firstRow, int endRow, Level audio, double[] window, LeadEffectMatcher.Spectrum fft) {}

    // Symmetric binomial low-pass before decimation, so sample zero stays
    // aligned to the original WAV. Bands stay comfortably below Nyquist.
    private static final int[] LOW_PASS = {1, 6, 15, 20, 15, 6, 1};

    private EvidenceSpectrum() {}

    static double hz(double pitch) {
        return 440 * Math.pow(2, (pitch - 69) / 12);
    }

    static double harmonicPitch(int pitch, int multiple) {
        return pitch + 12 * Math.log(multiple) / LOG_2;
    }

    static BufferedImage analyze(Audio audio, double[] times, int minPitch, int maxPitch,
                                 int height, double span, boolean emphasize) {
        double semitones = maxPitch - minPitch + 1;
        double[] frequencies = new double[height];
        for (int y = 0; y < height; y++)
            frequencies[y] = hz(maxPitch + .5 - (y + .5) * semitones / height);
        var bands = new ArrayList<Band>();
        // Longer bass windows separate neighboring low notes. Treble uses shorter
        // windows so the same plot can show several closely spaced attacks.
        var levelAudio = new Level(audio.samples, audio.rate);
        for (int from = 0; from < height;) {
            int end = from + 1;
            while (end < height && frequencies[from] < frequencies[end] * 2) end++;
            double minimumWindow = span <= 3 ? .025 : .040;
            double seconds = Math.clamp(22 / frequencies[end - 1], minimumWindow, 2 * CONTEXT);
            double oversampling = emphasize ? 24 : 10;
            while (levelAudio.rate / 2 >= oversampling * frequencies[from])
                levelAudio = levelAudio.halfRate();
            int length = Math.max(3, (int) Math.round(seconds * levelAudio.rate) | 1);
            var window = new double[length];
            for (int i = 0; i < length; i++)
                window[i] = (1 - Math.cos(Math.TAU * i / (length - 1))) / (length - 1);
            int bins = Integer.highestOneBit(length) << 2;
            bands.add(new Band(from, end, levelAudio, window, new LeadEffectMatcher.Spectrum(bins)));
            from = end;
        }
        float[] power = new float[times.length * height];
        double peak = 0;
        for (int x = 0; x < times.length; x++) {
            if (times[x] < 0 || times[x] >= audio.duration) continue;
            for (var band : bands) {
                int center = (int) Math.round((times[x] - audio.offset) * band.audio.rate);
                band.fft.transform(band.audio.samples, center - band.window.length / 2, band.window);
                double binsPerHz = band.fft.real.length / band.audio.rate;
                for (int y = band.firstRow; y < band.endRow; y++) {
                    double bin = frequencies[y] * binsPerHz;
                    double fundamental = power(band.fft, bin);
                    double energy = fundamental;
                    if (emphasize && fundamental > 0) {
                        // Weight in partials only where some energy supports the fundamental.
                        for (int k = 2; k <= 6; k++)
                            energy += Math.min(power(band.fft, bin * k), fundamental * 4) / k;
                    }
                    power[y * times.length + x] = (float) energy;
                    peak = Math.max(peak, energy);
                }
            }
        }
        var image = new BufferedImage(times.length, height, BufferedImage.TYPE_INT_RGB);
        var pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        if (peak > 0) {
            for (int i = 0; i < power.length; i++) {
                double db = 10 * Math.log10(power[i] / peak);
                // Darken the bottom of the 30 dB window to keep ridges distinct.
                double level = Math.clamp((db + 30) / 30, 0, 1);
                int gray = (int) Math.round(255 * level * level);
                pixels[i] = gray * 0x010101;
            }
        }
        return image;
    }

    private static double power(LeadEffectMatcher.Spectrum fft, double bin) {
        int lower = (int) bin;
        if (lower >= fft.real.length / 2) return 0;
        double fraction = bin - lower;
        return fft.power(lower) * (1 - fraction) + fft.power(lower + 1) * fraction;
    }
}
