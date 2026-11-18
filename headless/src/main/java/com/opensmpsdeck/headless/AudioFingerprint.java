package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.List;

/** Gain-independent spectrum and real-time dynamics of one isolated mono sound. */
public final class AudioFingerprint {
    private static final double MIN_RMS = 1e-7;
    private static final double ONSET_RATIO = 0.02;
    private static final double TAIL_RATIO = 0.035;
    private static final double ENVELOPE_STEP_SECONDS = 0.005;
    private static final double ATTACK_BAND_SECONDS = 0.080;
    private static final double BAND_START_HZ = 36;
    private static final double BAND_STEP = Math.pow(2, 1.0 / 3);
    private static final double BAND_LIMIT_HZ = 14_000;
    private static final double BAND_Q = 3;
    private final List<Double> bands;
    private final List<Double> attackBands;
    private final List<Double> envelope;
    private final Summary summary;

    private AudioFingerprint(List<Double> bands, List<Double> attackBands, List<Double> envelope, Summary summary) {
        this.bands = List.copyOf(bands);
        this.attackBands = List.copyOf(attackBands);
        this.envelope = List.copyOf(envelope);
        this.summary = summary;
    }

    public record Summary(double centroidHz, double flatness, double brightness, double noisiness,
                          double attackSeconds, double decaySeconds, double effectiveDurationSeconds) {}
    public record Distance(double total, double spectral, double envelope) {}

    /** Fraction of energy per one-third-octave band, starting at 36 Hz. */
    public List<Double> bands() { return bands; }

    /** Fraction of band energy within the first 80 ms after onset. */
    public List<Double> attackBands() { return attackBands; }

    /** Relative RMS in 5 ms hops from onset, with trailing quiet hops removed. */
    public List<Double> envelope() { return envelope; }

    public Summary summary() { return summary; }

    /** Accepts at most three seconds at 8–192 kHz. */
    public static AudioFingerprint extract(float[] mono, int sampleRate) {
        if (sampleRate < 8_000 || sampleRate > 192_000)
            throw new IllegalArgumentException("Fingerprint sample rate must be 8000..192000 Hz");
        if (mono == null || mono.length < sampleRate / 100 || mono.length > sampleRate * 3)
            throw new IllegalArgumentException("Fingerprint audio must be between 10 ms and 3 seconds");

        double[] audio = removeDc(mono, sampleRate);
        double peakSample = 0;
        for (double value : audio) peakSample = Math.max(peakSample, Math.abs(value));
        if (peakSample < MIN_RMS) throw new IllegalArgumentException("Fingerprint audio is silent");
        // Anchor the RMS hops on the first active sample so adding part of a
        // hop of silence cannot change the alignment of a short sound.
        int firstSample = 0;
        double onsetThreshold = Math.max(MIN_RMS, peakSample * ONSET_RATIO);
        while (Math.abs(audio[firstSample]) < onsetThreshold) firstSample++;

        int hop = Math.max(1, (int) Math.round(sampleRate * ENVELOPE_STEP_SECONDS));
        double hopSeconds = (double) hop / sampleRate;
        double[] rms = hopRms(audio, firstSample, hop);
        double peak = 0;
        for (double value : rms) peak = Math.max(peak, value);
        if (peak < MIN_RMS) throw new IllegalArgumentException("Fingerprint audio is silent");

        int end = rms.length;
        double tailThreshold = Math.max(MIN_RMS, peak * TAIL_RATIO);
        while (rms[end - 1] < tailThreshold) end--;

        var envelope = new ArrayList<Double>(end);
        int attack = 0;
        while (rms[attack] < peak * 0.8) attack++;
        int decay = end - 1;
        while (decay > attack && rms[decay] < peak * 0.1) decay--;
        for (int i = 0; i < end; i++) envelope.add(rms[i] / peak);

        int endSample = Math.min(audio.length, firstSample + end * hop);
        var spectrum = spectrum(audio, sampleRate, firstSample, endSample);
        double centroid = 0;
        double logEnergy = 0;
        double brightness = 0;
        for (int i = 0; i < spectrum.all().size(); i++) {
            double center = BAND_START_HZ * Math.pow(BAND_STEP, i);
            double fraction = spectrum.all().get(i);
            centroid += fraction * center;
            brightness += fraction * Math.log(center / BAND_START_HZ) / Math.log(BAND_LIMIT_HZ / BAND_START_HZ);
            logEnergy += Math.log(Math.max(1e-9, fraction));
        }
        double flatness = Math.min(1, Math.exp(logEnergy / spectrum.all().size()) * spectrum.all().size());
        double noisiness = 1 - periodicity(audio, sampleRate, firstSample, endSample);
        var summary = new Summary(centroid, flatness, brightness, noisiness,
                attack * hopSeconds, (decay - attack) * hopSeconds,
                (endSample - firstSample) / (double) sampleRate);
        return new AudioFingerprint(spectrum.all(), spectrum.attack(), envelope, summary);
    }

    public Distance distance(AudioFingerprint other) {
        double broadband = spectralDistance(bands, other.bands);
        double onset = spectralDistance(attackBands, other.attackBands);
        double spectral = 0.69 * broadband + 0.16 * onset
                + 0.10 * Math.abs(summary.brightness() - other.summary.brightness())
                + 0.05 * Math.abs(summary.noisiness() - other.summary.noisiness());
        int hops = Math.max(envelope.size(), other.envelope.size());
        double shape = 0;
        for (int i = 0; i < hops; i++) {
            double a = i < envelope.size() ? envelope.get(i) : 0;
            double b = i < other.envelope.size() ? other.envelope.get(i) : 0;
            // Square roots retain the shape of the quiet decay.
            shape += Math.abs(Math.sqrt(a) - Math.sqrt(b));
        }
        double dynamics = 0.70 * shape / hops
                + 0.15 * timeDistance(summary.attackSeconds(), other.summary.attackSeconds(), 0.05)
                + 0.15 * timeDistance(summary.decaySeconds(), other.summary.decaySeconds(), 0.10);
        return new Distance(0.65 * spectral + 0.35 * dynamics, spectral, dynamics);
    }

    private static double[] removeDc(float[] mono, int rate) {
        double retain = Math.exp(-2 * Math.PI * 15 / rate);
        double previous = mono[0];
        double filtered = 0;
        var result = new double[mono.length];
        for (int i = 0; i < mono.length; i++) {
            double value = mono[i];
            if (!Double.isFinite(value))
                throw new IllegalArgumentException("Fingerprint audio contains non-finite samples");
            filtered = retain * (filtered + value - previous);
            result[i] = filtered;
            previous = value;
        }
        return result;
    }

    private static double[] hopRms(double[] audio, int onset, int hop) {
        double[] rms = new double[(audio.length - onset + hop - 1) / hop];
        for (int i = 0; i < rms.length; i++) {
            int first = onset + i * hop;
            int end = Math.min(audio.length, first + hop);
            double sum = 0;
            for (int j = first; j < end; j++) sum += audio[j] * audio[j];
            // Treat a partial final hop as zero-padded.
            rms[i] = Math.sqrt(sum / hop);
        }
        return rms;
    }

    private record Spectrum(List<Double> all, List<Double> attack) {}

    private static Spectrum spectrum(double[] audio, int rate, int first, int end) {
        var all = new ArrayList<Double>();
        var attack = new ArrayList<Double>();
        int attackEnd = Math.min(end, first + (int) Math.round(rate * ATTACK_BAND_SECONDS));
        double highest = Math.min(BAND_LIMIT_HZ, 0.43 * rate);
        for (double center = BAND_START_HZ; center <= highest; center *= BAND_STEP) {
            double angle = 2 * Math.PI * center / rate;
            double alpha = Math.sin(angle) / (2 * BAND_Q);
            double b = alpha / (1 + alpha);
            double a1 = 2 * Math.cos(angle) / (1 + alpha);
            double a2 = (alpha - 1) / (alpha + 1);
            double input1 = 0, input2 = 0, output1 = 0, output2 = 0;
            double total = 0, initial = 0;
            for (int i = first; i < end; i++) {
                double input = audio[i];
                double output = b * (input - input2) + a1 * output1 + a2 * output2;
                double energy = output * output;
                total += energy;
                if (i < attackEnd) initial += energy;
                input2 = input1;
                input1 = input;
                output2 = output1;
                output1 = output;
            }
            // Divide by bandwidth so white noise does not look artificially
            // bright just because upper octave filters span more Hertz.
            all.add(total / center);
            attack.add(initial / center);
        }
        return new Spectrum(normalize(all), normalize(attack));
    }

    private static List<Double> normalize(List<Double> values) {
        double sum = 0;
        for (double value : values) sum += value;
        var result = new ArrayList<Double>(values.size());
        for (double value : values) result.add(sum > 0 ? value / sum : 0);
        return result;
    }

    private static double spectralDistance(List<Double> a, List<Double> b) {
        // Ignore bands unavailable at the lower sample rate.
        int shared = Math.min(a.size(), b.size());
        double aTotal = 0, bTotal = 0;
        for (int i = 0; i < shared; i++) {
            aTotal += a.get(i);
            bTotal += b.get(i);
        }
        if (aTotal == 0 || bTotal == 0) return aTotal == bTotal ? 0 : 1;
        double sum = 0;
        for (int i = 0; i < shared; i++) {
            double difference = Math.sqrt(a.get(i) / aTotal) - Math.sqrt(b.get(i) / bTotal);
            sum += difference * difference;
        }
        return Math.sqrt(sum / 2);
    }

    /** Compare period candidates in a short window beyond the initial transient. */
    private static double periodicity(double[] audio, int rate, int first, int end) {
        int stride = Math.max(1, rate / 8000);
        int available = (end - first) / stride;
        int size = Math.min(available, rate / stride / 12);
        double[] signal = new double[size];
        int offset = first + Math.min((available - size) / 2, rate / stride / 20) * stride;
        for (int i = 0; i < size; i++) {
            double sum = 0;
            for (int j = 0; j < stride; j++) sum += audio[offset + i * stride + j];
            signal[i] = sum / stride;
        }

        double best = 0;
        int smallestLag = Math.max(2, rate / stride / 1800);
        int largestLag = Math.min(size / 3, rate / stride / 35);
        for (int lag = smallestLag; lag <= largestLag; lag++) {
            double dot = 0, left = 0, right = 0;
            for (int i = lag; i < size; i++) {
                double a = signal[i - lag];
                double b = signal[i];
                dot += a * b;
                left += a * a;
                right += b * b;
            }
            if (left > 0 && right > 0) best = Math.max(best, dot / Math.sqrt(left * right));
        }
        return Math.min(1, best);
    }

    private static double timeDistance(double a, double b, double scale) {
        return Math.abs(a - b) / (scale + Math.max(a, b));
    }
}
