package com.opensmpsdeck.headless;

/** Gain-independent spectral balance at the original audio rate. */
final class VoiceSpectralBalance {
    static final int SIZE = 2048;
    private static final double[] WINDOW = new double[SIZE];
    private static final double UPPER_TOLERANCE_DB = 6;

    static {
        for (int i = 0; i < SIZE; i++) WINDOW[i] = .5 - .5 * Math.cos(Math.TAU * i / SIZE);
    }

    private final LeadEffectMatcher.Spectrum fft = new LeadEffectMatcher.Spectrum(SIZE);

    record Profile(double upperDb, double presenceDb, double harmonicDb) {
        double excess(Profile candidate) {
            return Math.max(0, candidate.upperDb - upperDb - UPPER_TOLERANCE_DB);
        }

        double distance(Profile candidate) {
            return Math.abs(upperDb - candidate.upperDb)
                    + .25 * Math.abs(presenceDb - candidate.presenceDb)
                    + .15 * Math.abs(harmonicDb - candidate.harmonicDb);
        }
    }

    Profile measure(float[] samples, int offset, int rate, double fundamental) {
        fft.transform(samples, offset, WINDOW);
        double total = 0, upper = 0, presence = 0, root = 0, partials = 0;
        double spacing = rate / (double) SIZE;
        double radius = Math.min(fundamental * .46, Math.max(2 * spacing, fundamental * .04));
        for (int bin = 1; bin < SIZE / 2; bin++) {
            double frequency = bin * spacing;
            double energy = fft.power(bin);
            total += energy;
            if (frequency >= 5000) upper += energy;
            if (frequency >= 2000) presence += energy;
            int harmonic = (int) Math.round(frequency / fundamental);
            if (Math.abs(frequency - harmonic * fundamental) > radius) continue;
            if (harmonic == 1) root += energy;
            if (harmonic >= 2 && harmonic <= 16) partials += energy;
        }
        return new Profile(share(upper, total), share(presence, total),
                Math.clamp(10 * Math.log10((partials + total * 1e-4 + 1e-12) / (root + total * 1e-4 + 1e-12)), -32, 32));
    }

    private static double share(double band, double total) {
        return 10 * Math.log10(Math.max(1e-5, band / Math.max(1e-12, total)));
    }
}
