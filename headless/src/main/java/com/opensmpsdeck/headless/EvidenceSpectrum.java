package com.opensmpsdeck.headless;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Centered Hann analysis on native audio time, sampled on a semitone display grid. */
final class EvidenceSpectrum {
    static final double CONTEXT = .21;
    /** Reassignment keeps bins within 50 dB of the band's peak. */
    private static final double QUIET = 1e-5;
    /** Hann's main lobe half-width, in bins of the four-times zero-padded FFT. */
    private static final double LOBE_BINS = 8;
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

    private record Band(int firstRow, int endRow, Level audio, double[] window, LeadEffectMatcher.Spectrum fft,
                        double[] slope, LeadEffectMatcher.Spectrum slopeFft, double normal) {}

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

    record Peak(double pitch, double strength) {}
    record Spectrum(float[] powers, int columns, int height, int minPitch, int maxPitch, double peak,
                    boolean reassigned) {
        Spectrum(float[] powers, int columns, int height, int minPitch, int maxPitch, double peak) {
            this(powers, columns, height, minPitch, maxPitch, peak, false);
        }

        Double peakDb() { return peak > 0 ? db(peak) : null; }

        BufferedImage image(double ceiling) {
            var image = new BufferedImage(columns, height, BufferedImage.TYPE_INT_RGB);
            var pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
            if (ceiling > 0) {
                for (int i = 0; i < powers.length; i++) {
                    double db = 10 * Math.log10(powers[i] / ceiling);
                    // Darken the bottom of the 30 dB window to keep ridges distinct.
                    double level = Math.clamp((db + 30) / 30, 0, 1);
                    int gray = (int) Math.round(255 * level * level);
                    pixels[i] = gray * 0x010101;
                }
            }
            return image;
        }

        List<Peak> peaks(int column, int count) {
            var peaks = new ArrayList<Peak>();
            for (int row = 0; row < height; row++) {
                var peak = peak(column, row);
                if (peak != null) peaks.add(peak);
            }
            return peaks.stream().sorted(java.util.Comparator.comparingDouble(Peak::strength).reversed())
                    .limit(count).toList();
        }

        Peak near(int column, double expected, double tolerance) {
            int first = Math.max(0, (int) Math.floor(row(expected + tolerance)));
            int last = Math.min(height - 1, (int) Math.ceil(row(expected - tolerance)));
            Peak best = null;
            for (int row = first; row <= last; row++) {
                var peak = peak(column, row);
                if (peak != null && Math.abs(peak.pitch - expected) <= tolerance
                        && (best == null || peak.strength > best.strength)) best = peak;
            }
            return best;
        }

        Peak at(int column, double pitch) {
            double row = row(pitch);
            if (row < 0 || row > height - 1) return null;
            int lower = (int) row;
            double a = value(column, lower);
            double b = value(column, Math.min(lower + 1, height - 1));
            double power = a + (row - lower) * (b - a);
            return power > 0 ? new Peak(pitch, db(power)) : null;
        }

        private double row(double pitch) {
            return (maxPitch + .5 - pitch) * height / (maxPitch - minPitch + 1) - .5;
        }

        private float value(int column, int row) { return powers[row * columns + column]; }

        private Peak peak(int column, int row) {
            double center = value(column, row);
            if (center <= 0) return null;
            double left = row == 0 ? 0 : value(column, row - 1);
            double right = row == height - 1 ? 0 : value(column, row + 1);
            if (center <= left || center < right) return null;
            double offset = 0;
            double level = db(center);
            if (reassigned) {
                // Reassigned energy is already concentrated, so sum it and take its centroid.
                double sum = left + center + right;
                offset = (right - left) / sum;
                level = db(sum);
            } else if (left > 0 && right > 0) {
                double a = db(left), b = db(right);
                double curvature = a - 2 * level + b;
                if (curvature < 0) {
                    offset = (a - b) / (2 * curvature);
                    level -= (a - b) * offset / 4;
                }
            }
            double pitch = pitch(minPitch, maxPitch, row, height) - offset * (maxPitch - minPitch + 1.0) / height;
            return new Peak(pitch, level);
        }

        // The Hann window has unit coherent gain. The positive-frequency FFT
        // holds half of a real sine's amplitude, so full-scale sine power is 1/4.
        static double db(double power) { return 10 * Math.log10(4 * power); }
    }

    static BufferedImage analyze(Audio audio, double[] times, int minPitch, int maxPitch,
                                 int height, double span, boolean emphasize) {
        var spectrum = measure(audio, times, minPitch, maxPitch, height, span, emphasize);
        return spectrum.image(spectrum.peak());
    }

    private static double pitch(int min, int max, int row, int height) {
        return max + .5 - (row + .5) * (max - min + 1.0) / height;
    }

    static Spectrum measure(Audio audio, double[] times, int minPitch, int maxPitch,
                            int height, double span, boolean emphasize) {
        return measure(audio, times, minPitch, maxPitch, height, span, emphasize, false);
    }

    /**
     * Measures power on the display grid.
     *
     * <p>With {@code reassigned}, each FFT bin moves to its measured instantaneous
     * frequency, so a steady partial draws as a thin line instead of the window's
     * main lobe. Bin powers are scaled so a full-scale sine keeps the same peak
     * level in both modes. This is a display measurement only.
     */
    static Spectrum measure(Audio audio, double[] times, int minPitch, int maxPitch,
                            int height, double span, boolean emphasize, boolean reassigned) {
        double[] frequencies = new double[height];
        for (int y = 0; y < height; y++)
            frequencies[y] = hz(pitch(minPitch, maxPitch, y, height));
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
            double[] slope = null;
            double normal = 1;
            if (reassigned) {
                // The window's derivative gives each bin's frequency offset from its centre.
                slope = new double[length];
                double sum = 0, squares = 0;
                for (int i = 0; i < length; i++) {
                    slope[i] = Math.TAU / (length - 1) * Math.sin(Math.TAU * i / (length - 1)) / (length - 1);
                    sum += window[i];
                    squares += window[i] * window[i];
                }
                // A sine's bins sum to bins * sum(w^2) times its amplitude; its peak bin holds sum(w)^2.
                normal = bins * squares / (sum * sum);
            }
            bands.add(new Band(from, end, levelAudio, window, new LeadEffectMatcher.Spectrum(bins),
                    slope, reassigned ? new LeadEffectMatcher.Spectrum(bins) : null, normal));
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
                if (band.slopeFft != null) {
                    band.slopeFft.transform(band.audio.samples, center - band.window.length / 2, band.slope);
                    peak = Math.max(peak, reassign(band, binsPerHz, minPitch, maxPitch, height, power, x, times.length));
                    continue;
                }
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
        return new Spectrum(power, times.length, height, minPitch, maxPitch, peak, reassigned);
    }

    /** Moves each bin in a band to its instantaneous frequency and returns the band's strongest row. */
    private static double reassign(Band band, double binsPerHz, int minPitch, int maxPitch, int height,
                                   float[] power, int column, int columns) {
        int size = band.fft.real.length;
        double rowsPerPitch = height / (maxPitch - minPitch + 1.0);
        double top = pitch(minPitch, maxPitch, band.firstRow, height) + .5 / rowsPerPitch;
        double bottom = pitch(minPitch, maxPitch, band.endRow - 1, height) - .5 / rowsPerPitch;
        // Hann's main lobe spans two bins either side, four times that after zero padding.
        int first = Math.max(1, (int) Math.floor(hz(bottom) * binsPerHz) - 8);
        int last = Math.min(size / 2 - 1, (int) Math.ceil(hz(top) * binsPerHz) + 8);
        double loudest = 0;
        for (int bin = first; bin <= last; bin++) loudest = Math.max(loudest, band.fft.power(bin));
        double strongest = 0;
        for (int bin = first; bin <= last; bin++) {
            double re = band.fft.real[bin], im = band.fft.imag[bin];
            double energy = re * re + im * im;
            // Bins far below the band's peak are sidelobes and noise, whose reassignment scatters.
            if (energy < 1e-14 || energy < loudest * QUIET) continue;
            double offset = (band.slopeFft.imag[bin] * re - band.slopeFft.real[bin] * im) / energy;
            double shift = offset * size / Math.TAU;
            // A bin inside a partial's main lobe moves at most the lobe's half-width.
            if (Math.abs(shift) > LOBE_BINS) continue;
            double hz = (bin - shift) / binsPerHz;
            if (hz <= 0) continue;
            double pitch = 69 + 12 * Math.log(hz / 440) / LOG_2;
            if (pitch > top || pitch <= bottom) continue;
            double row = (maxPitch + .5 - pitch) * rowsPerPitch - .5;
            int lower = (int) Math.floor(row);
            double fraction = row - lower;
            double share = energy / band.normal;
            if (lower >= band.firstRow) strongest = Math.max(strongest,
                    power[lower * columns + column] += (float) (share * (1 - fraction)));
            if (lower + 1 < band.endRow) strongest = Math.max(strongest,
                    power[(lower + 1) * columns + column] += (float) (share * fraction));
        }
        return strongest;
    }

    private static double power(LeadEffectMatcher.Spectrum fft, double bin) {
        int lower = (int) bin;
        if (lower >= fft.real.length / 2) return 0;
        double fraction = bin - lower;
        return fft.power(lower) * (1 - fraction) + fft.power(lower + 1) * fraction;
    }
}
