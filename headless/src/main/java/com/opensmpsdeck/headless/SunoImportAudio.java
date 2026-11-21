package com.opensmpsdeck.headless;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SunoImportAudio {
    static final int RATE = 8_000;
    static final int MAX_SECONDS = S3kRenderer.MAX_PRESENTATIONS / S3kRenderer.PRESENTATIONS_PER_SECOND;
    private static final int HOP = 160;
    private static final int FFT_SIZE = 2048;
    private static final int MIN_PITCH = 28;
    private static final int MAX_PITCH = 103;
    private static final double[] WINDOW = new double[FFT_SIZE];

    static {
        for (int i = 0; i < WINDOW.length; i++) WINDOW[i] = .5 - .5 * Math.cos(Math.TAU * i / WINDOW.length);
    }

    private SunoImportAudio() {}

    record Match(int part, double onsetCorrelation, double pitchCorrelation, double score) {}
    private record Profile(MidiScore.Part part, BitSet onsets, Map<Integer, BitSet> pitches) {}

    static List<Match> matches(float[] audio, MidiScore midi) throws InterruptedIOException {
        int frames = (audio.length + HOP - 1) / HOP;
        var profiles = profiles(midi, frames);
        if (profiles.isEmpty()) return List.of();
        var spectrum = new LeadEffectMatcher.Spectrum(FFT_SIZE);
        var flux = new double[frames];
        var pitchEnergy = new double[MAX_PITCH - MIN_PITCH + 1][frames];
        var previous = new double[FFT_SIZE / 2];
        var pitchBins = new double[pitchEnergy.length];
        for (int i = 0; i < pitchBins.length; i++)
            pitchBins[i] = (440 * Math.pow(2, (i + MIN_PITCH - 69) / 12.0)) * FFT_SIZE / RATE;
        for (int t = 0; t < frames; t++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
            spectrum.transform(audio, t * HOP - FFT_SIZE / 2, WINDOW);
            for (int bin = 1; bin < previous.length; bin++) {
                double energy = Math.sqrt(spectrum.power(bin));
                flux[t] += Math.max(0, energy - previous[bin]);
                previous[bin] = energy;
            }
            for (int pitch = 0; pitch < pitchBins.length; pitch++) {
                double bin = pitchBins[pitch];
                int i = (int) bin;
                double fraction = bin - i;
                pitchEnergy[pitch][t] =
                        Math.sqrt(spectrum.power(i) * (1 - fraction) + spectrum.power(i + 1) * fraction);
            }
        }
        var onsets = new Energy(flux);
        var energies = new Energy[pitchEnergy.length];
        for (int i = 0; i < energies.length; i++) energies[i] = new Energy(pitchEnergy[i]);
        var matches = new ArrayList<Match>();
        for (var profile : profiles) {
            double onset = onsets.correlation(profile.onsets);
            double pitch = pitchCorrelation(profile, energies);
            double score = profile.part.drums() ? onset : .35 * onset + .65 * pitch;
            matches.add(new Match(profile.part.id(), onset, pitch, score));
        }
        return matches;
    }

    private static List<Profile> profiles(MidiScore midi, int frames) {
        var profiles = new HashMap<Integer, Profile>();
        for (var part : midi.parts()) profiles.put(part.id(), new Profile(part, new BitSet(), new java.util.TreeMap<>()));
        for (var note : midi.notes()) {
            long frame = note.startUs() / 20_000;
            if (frame >= frames) break;
            int start = (int) frame;
            var profile = profiles.get(note.partId());
            profile.onsets.set(Math.max(0, start - 2), Math.min(frames, start + 3));
            if (profile.part.drums() || note.pitch() < MIN_PITCH || note.pitch() > MAX_PITCH) continue;
            int end = (int) Math.min(frames, Math.max(start + 1, note.endUs() / 20_000));
            profile.pitches.computeIfAbsent(note.pitch(), ignored -> new BitSet()).set(start, end);
        }
        return midi.parts().stream().map(part -> profiles.get(part.id())).toList();
    }

    private static double pitchCorrelation(Profile part, Energy[] energies) {
        double weighted = 0;
        int count = 0;
        for (var entry : part.pitches.entrySet()) {
            int active = entry.getValue().cardinality();
            weighted += energies[entry.getKey() - MIN_PITCH].correlation(entry.getValue()) * active;
            count += active;
        }
        return count == 0 ? 0 : weighted / count;
    }

    private record Energy(double[] values, double mean, double variance) {
        Energy(double[] values) {
            this(values, java.util.Arrays.stream(values).average().orElse(0), variance(values));
        }

        private static double variance(double[] values) {
            double mean = java.util.Arrays.stream(values).average().orElse(0);
            return java.util.Arrays.stream(values).map(value -> (value - mean) * (value - mean)).sum();
        }

        double correlation(BitSet active) {
            int count = active.cardinality();
            double denominator = Math.sqrt(variance * count * (1 - count / (double) values.length));
            if (denominator <= 0) return 0;
            double dot = 0;
            for (int i = active.nextSetBit(0); i >= 0; i = active.nextSetBit(i + 1)) dot += values[i] - mean;
            return Math.clamp(dot / denominator, -1, 1);
        }
    }

    /** Positive lag means the summed stems arrive later than the mix. */
    static SunoImport.Alignment align(float[] mix, double[] sum, int stemCount) throws InterruptedIOException {
        // Keep the comparison bounded. Search a +/- 2 second lag on a 2 kHz waveform,
        // then refine to the native analysis grid. Every lag uses the same mix interval.
        int limit = Math.min(2 * RATE, Math.min(mix.length, sum.length) / 5);
        int end = Math.min(mix.length, sum.length) - limit;
        int step = Math.max(1, (end - limit) / 4096);
        double zero = correlation(mix, sum, 0, limit, end, step);
        double best = zero;
        int offset = 0;
        for (int lag = -limit; lag <= limit; lag += 4) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
            double score = correlation(mix, sum, lag, limit, end, step);
            if (score > best) {
                best = score;
                offset = lag;
            }
        }
        int coarse = offset;
        for (int lag = Math.max(-limit, coarse - 3); lag <= Math.min(limit, coarse + 3); lag++) {
            double score = correlation(mix, sum, lag, limit, end, step);
            if (score > best) {
                best = score;
                offset = lag;
            }
        }
        return new SunoImport.Alignment(offset / (double) RATE, best, zero, stemCount);
    }

    private static double correlation(float[] a, double[] b, int lag, int start, int end, int step) {
        double xx = 0, yy = 0, xy = 0, sx = 0, sy = 0;
        int count = 0;
        for (int i = start; i < end; i += step) {
            double x = a[i], y = b[i + lag];
            xx += x * x;
            yy += y * y;
            xy += x * y;
            sx += x;
            sy += y;
            count++;
        }
        double denominator = Math.sqrt(Math.max(0, xx - sx * sx / count) * Math.max(0, yy - sy * sy / count));
        return denominator > 0 ? Math.clamp((xy - sx * sy / count) / denominator, -1, 1) : 0;
    }
}
