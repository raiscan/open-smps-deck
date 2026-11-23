package com.opensmpsdeck.headless;

/** Gain-independent level and brightness during the first 50 ms of a key-on. */
final class VoiceAttackShape {
    private static final int BIN = VoiceSpectra.RATE / 200;
    static final int SAMPLES = 10 * BIN;
    private static final int FFT = 256;
    private static final double[] HANN = new double[4 * BIN];

    static {
        for (int i = 0; i < HANN.length; i++)
            HANN[i] = .5 - .5 * Math.cos(Math.TAU * i / (HANN.length - 1));
    }

    private final LeadEffectMatcher.Spectrum spectrum = new LeadEffectMatcher.Spectrum(FFT);

    record Profile(double[] relativeLevels, double[] centroids, double peak, int riseBin) {
        double distance(Profile other) {
            if (other.peak < 1e-6) return 1;
            return .2 * envelopeDistance(other) + .5 * brightnessDistance(other) + riseDistance(other);
        }

        private double envelopeDistance(Profile other) {
            double distance = 0;
            for (int i = 0; i < relativeLevels.length; i++)
                distance += Math.abs(relativeLevels[i] - other.relativeLevels[i]);
            return distance / relativeLevels.length;
        }

        double riseDistance(Profile other) {
            return Math.abs(riseBin - other.riseBin) / (double) (relativeLevels.length - 1);
        }

        private double brightnessDistance(Profile other) {
            double brightness = 0;
            for (int i = 0; i < centroids.length; i++) {
                double ratio = centroids[i] / other.centroids[i];
                brightness += Math.min(2, Math.abs(Math.log(ratio) / Math.log(2)));
            }
            return brightness / centroids.length;
        }
    }

    Profile measure(float[] audio) {
        var levels = new double[SAMPLES / BIN];
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
        // The first near-peak bin is stable on attacks that settle into a plateau.
        int riseBin = 0;
        while (levels[riseBin] < .9 * peak) riseBin++;
        if (peak > 0) {
            for (int i = 0; i < levels.length; i++) levels[i] /= peak;
        }

        // The 20 ms windows overlap so the centroid is stable even on low notes.
        var centroids = new double[3];
        for (int frame = 0; frame < centroids.length; frame++) {
            spectrum.transform(audio, frame * 3 * BIN, HANN);
            double sum = 0, weighted = 0;
            for (int bin = 2; bin < FFT / 2; bin++) {
                double weight = spectrum.power(bin);
                sum += weight;
                weighted += bin * weight;
            }
            centroids[frame] = sum > 0 ? VoiceSpectra.RATE * weighted / (FFT * sum) : 100;
        }
        return new Profile(levels, centroids, peak, riseBin);
    }
}
