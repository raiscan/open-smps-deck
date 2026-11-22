package com.opensmpsdeck.headless;

/** Gain-independent level and brightness during the first 50 ms of a key-on. */
final class VoiceAttackShape {
    private static final int BIN = VoiceSpectra.RATE / 100;
    static final int SAMPLES = 5 * BIN;
    private static final int FFT = 256;
    private static final double[] HANN = new double[2 * BIN];

    static {
        for (int i = 0; i < HANN.length; i++)
            HANN[i] = .5 - .5 * Math.cos(Math.TAU * i / (HANN.length - 1));
    }

    private final LeadEffectMatcher.Spectrum spectrum = new LeadEffectMatcher.Spectrum(FFT);

    record Profile(double[] relativeLevels, double[] centroids, double peak) {
        double distance(Profile other) {
            if (other.peak < 1e-6) return 1;
            double level = 0, brightness = 0;
            for (int i = 0; i < relativeLevels.length; i++)
                level += Math.abs(relativeLevels[i] - other.relativeLevels[i]);
            for (int i = 0; i < centroids.length; i++) {
                double ratio = centroids[i] / other.centroids[i];
                brightness += Math.min(2, Math.abs(Math.log(ratio) / Math.log(2)));
            }
            return level / relativeLevels.length + .25 * brightness / centroids.length;
        }
    }

    Profile measure(float[] audio) {
        var levels = new double[5];
        double peak = 0;
        for (int bin = 0; bin < levels.length; bin++) {
            int start = bin * BIN;
            double sum = 0, squares = 0;
            for (int i = start; i < start + BIN; i++) {
                sum += audio[i];
                squares += audio[i] * (double) audio[i];
            }
            double mean = sum / BIN;
            levels[bin] = Math.sqrt(Math.max(0, squares / BIN - mean * mean));
            peak = Math.max(peak, levels[bin]);
        }
        if (peak > 0) {
            for (int i = 0; i < levels.length; i++) levels[i] /= peak;
        }

        // The 20 ms windows overlap so the centroid is stable even on low notes.
        var centroids = new double[3];
        for (int frame = 0; frame < centroids.length; frame++) {
            spectrum.transform(audio, frame * 3 * BIN / 2, HANN);
            double sum = 0, weighted = 0;
            for (int bin = 2; bin < FFT / 2; bin++) {
                double weight = spectrum.power(bin);
                sum += weight;
                weighted += bin * weight;
            }
            centroids[frame] = sum > 0 ? VoiceSpectra.RATE * weighted / (FFT * sum) : 100;
        }
        return new Profile(levels, centroids, peak);
    }
}
