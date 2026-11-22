package com.opensmpsdeck.headless;

import java.io.IOException;

/** Measured held-note decay and magnitude-weighted spectral centroid for raw FM patches. */
final class CatalogueSustain {
    private static final int FRAMES = 48;
    private static final int FFT = 4096;
    private static final int PITCH = 69;

    record Filter(Double minCentroidHz, Double maxCentroidHz, Double maxDecayDb) {
        Filter {
            if (minCentroidHz != null && (!Double.isFinite(minCentroidHz) || minCentroidHz < 0)
                    || maxCentroidHz != null && (!Double.isFinite(maxCentroidHz) || maxCentroidHz < 0)
                    || minCentroidHz != null && maxCentroidHz != null && minCentroidHz > maxCentroidHz)
                throw new IllegalArgumentException("Expected an ordered, nonnegative centroid range in Hz");
            if (maxDecayDb != null && !Double.isFinite(maxDecayDb))
                throw new IllegalArgumentException("Expected finite max-decay-db");
        }

        boolean accepts(Measurement probe) {
            return probe != null && (minCentroidHz == null || probe.centroidHz() >= minCentroidHz)
                    && (maxCentroidHz == null || probe.centroidHz() <= maxCentroidHz)
                    && (maxDecayDb == null || probe.decayDb() <= maxDecayDb);
        }
    }

    record Measurement(int midi, double centroidHz, double decayDb) {}

    private CatalogueSustain() {}

    static Measurement probe(InstrumentCatalogue catalogue, InstrumentCatalogue.Match match) throws IOException {
        // Raw patch, no recipe transposition, effects or attenuation. Keep the gate open for the full probe.
        var project = CataloguePlayback.matchProbe(catalogue, match.variant().id(), PITCH, 90, true, match.variant().target());
        return measure(VoiceWindows.renderMono(project, FRAMES), VoiceSpectra.RATE);
    }

    static Measurement measure(float[] pcm, int rate) {
        int earlyStart = rate / 10, earlyEnd = 3 * rate / 10;
        int lateStart = pcm.length - FFT;
        double early = power(pcm, earlyStart, earlyEnd);
        double held = power(pcm, lateStart, pcm.length);
        if (held < 1e-10) return null;
        var fft = new LeadEffectMatcher.Spectrum(FFT);
        var window = new double[FFT];
        for (int i = 0; i < FFT; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (FFT - 1));
        fft.transform(pcm, lateStart, window);
        double weights = 0, moment = 0;
        for (int bin = 1; bin < FFT / 2; bin++) {
            double magnitude = Math.sqrt(fft.power(bin));
            weights += magnitude;
            moment += magnitude * bin;
        }
        if (weights == 0) return null;
        return new Measurement(PITCH, moment / weights * rate / FFT, 10 * Math.log10(Math.max(1e-10, early) / held));
    }

    private static double power(float[] pcm, int from, int to) {
        double squares = 0;
        for (int i = from; i < to; i++) squares += (double) pcm[i] * pcm[i];
        return squares / (to - from);
    }
}
