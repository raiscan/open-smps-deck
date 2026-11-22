package com.opensmpsdeck.headless;

import java.util.Arrays;

/** Source-stem measurements at the actual sung register. */
final class VocalStemEvidence {
    private static final int FFT_SIZE = 32768;
    private static final double QUIET_DB = -30;
    private final ReferenceAudio audio;
    private final LeadEffectMatcher.Spectrum spectrum = new LeadEffectMatcher.Spectrum(FFT_SIZE);
    private final double[] levels;
    private final double activeDb;

    record Profile(double rmsDb, Double subharmonicDb, Double oddHarmonicDb,
                   boolean supportsLowerOctave, boolean supportsSungOctave) {}

    VocalStemEvidence(ReferenceAudio audio) {
        this.audio = audio;
        int frames = audio.samples().length * 100 / audio.sampleRate();
        levels = new double[frames];
        for (int i = 0; i < frames; i++) levels[i] = rms(i / 100.0, (i + 1) / 100.0);
        var audible = Arrays.stream(levels).filter(db -> db > -50).sorted().toArray();
        // Relative gate for quiet stems, with an absolute upper bound for loud stems.
        activeDb = audible.length == 0 ? -50 : Math.clamp(audible[(int) (audible.length * .85)] - 19, -50, -33);
    }

    boolean active(double seconds) {
        int frame = (int) Math.floor(seconds * 100 + 1e-8);
        return frame >= 0 && frame < levels.length && levels[frame] >= activeDb;
    }

    boolean quiet(double start, double end, boolean hasSyllable) {
        double db = rms(start, end);
        if (db < activeDb) return true;
        // Preserve quiet pick-ups and tails that connect to an active line.
        return !hasSyllable && db < QUIET_DB && !active(start - .10) && !active(end + .10);
    }

    Profile profile(double start, double end, int sungPitch) {
        double rms = rms(start, end);
        double hz = ReconstructionLeadAudio.hz(sungPitch);
        int length = Math.min(samples(end) - samples(start), audio.sampleRate() / 2);
        // Resolving a half-frequency peak requires several cycles of that partial.
        if (rms < activeDb || length < Math.max(audio.sampleRate() * .075, 6 * audio.sampleRate() / hz))
            return new Profile(rms, null, null, false, false);
        int from = samples((start + end) / 2) - length / 2;
        var window = new double[length];
        for (int i = 0; i < length; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (length - 1));
        spectrum.transform(audio.samples(), from, window);
        int low = bin(60), high = bin(2000);
        var noise = new double[high - low];
        for (int i = 0; i < noise.length; i++) noise[i] = spectrum.power(low + i);
        Arrays.sort(noise);
        double floor = powerDb(noise[noise.length / 2]);
        double fundamental = peak(hz) - floor;
        double upperOdd = Math.max(peak(3 * hz), peak(5 * hz)) - floor;
        double half = peak(.5 * hz) - floor, threeHalves = peak(1.5 * hz) - floor;
        double sub = Math.min(half, threeHalves);
        double odd = (fundamental + upperOdd) / 2;
        boolean lower = sub >= 17 && sub >= fundamental - 22 && sub >= odd - 11;
        boolean sung = fundamental >= 16 && (sub < 12 || sub < odd - 13);
        return new Profile(rms, sub, odd, lower, sung);
    }

    private int bin(double hz) { return (int) Math.round(hz * FFT_SIZE / audio.sampleRate()); }

    private double peak(double hz) {
        double power = 0;
        for (int bin = Math.max(1, bin(hz * .97)); bin <= Math.min(FFT_SIZE / 2 - 1, bin(hz * 1.03)); bin++)
            power = Math.max(power, spectrum.power(bin));
        return powerDb(power);
    }

    double rms(double start, double end) {
        int from = samples(start), to = samples(end);
        if (from >= to) return -120;
        double sum = 0, squares = 0;
        for (int i = from; i < to; i++) {
            double sample = audio.samples()[i];
            sum += sample;
            squares += sample * sample;
        }
        double mean = sum / (to - from);
        return powerDb(squares / (to - from) - mean * mean);
    }

    private int samples(double seconds) {
        return Math.clamp((int) Math.round(seconds * audio.sampleRate()), 0, audio.samples().length);
    }

    private static double powerDb(double value) { return 10 * Math.log10(Math.max(1e-12, value)); }
}
