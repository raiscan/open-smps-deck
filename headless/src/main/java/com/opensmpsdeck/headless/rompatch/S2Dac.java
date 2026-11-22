package com.opensmpsdeck.headless.rompatch;

import com.opensmpsdeck.project.DacSample;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.rompatch.S2Revision.*;
import static com.opensmpsdeck.headless.rompatch.SongLayout.word;

/** Matches package drums to the actual REV01 sample/rate combinations. */
final class S2Dac {
    record Binding(int note, int mappedNote, String sample, String stockSample, int requestedRate,
                   int rate, boolean exact, double distance) {}
    private record Stock(int note, String name, int rate, byte[] pcm, Shape shape) {}
    private static final List<String> NAMES = List.of("kick", "snare", "clap", "scratch", "timpani", "tom", "bongo");

    private S2Dac() {}

    static List<Binding> map(byte[] rom, byte[] driver, byte[] song, Set<Integer> offsets,
                             Map<Integer, DacSample> drums) {
        if (offsets.isEmpty()) return List.of();
        var stock = stock(rom, driver);
        var bindings = new TreeMap<Integer, Binding>();
        for (int at : offsets) {
            int note = u8(song, at);
            var binding = bindings.computeIfAbsent(note, key -> {
                var sample = drums.get(key);
                if (sample == null) throw new IllegalArgumentException("Undefined S2 DAC note 0x%02X at 0x%04X"
                        .formatted(key, at));
                return nearest(key, sample, stock);
            });
            song[at] = (byte) binding.mappedNote();
        }
        return List.copyOf(bindings.values());
    }

    private static List<Stock> stock(byte[] rom, byte[] driver) {
        var samples = new byte[NAMES.size()][];
        byte[] deltas = Arrays.copyOfRange(driver, DAC_DELTAS, DAC_DELTAS + 16);
        for (int i = 0; i < samples.length; i++) {
            int entry = DAC_POINTERS + 4 * i;
            int pointer = word(driver, entry), length = word(driver, entry + 2);
            if (pointer < WINDOW || length == 0 || pointer + length > 2 * WINDOW)
                throw new IllegalArgumentException("Invalid Sonic 2 stock DAC sample " + NAMES.get(i));
            int start = DAC_BANK_ROM + pointer - WINDOW;
            samples[i] = Dpcm.decode(Arrays.copyOfRange(rom, start, start + length), deltas);
        }
        var result = new ArrayList<Stock>();
        for (int note = DAC_FIRST_NOTE; note <= DAC_LAST_NOTE; note++) {
            int entry = DAC_PLAYLIST + 2 * (note - DAC_FIRST_NOTE);
            int index = u8(driver, entry) - DAC_FIRST_NOTE, rate = u8(driver, entry + 1);
            if (index < 0 || index >= samples.length)
                throw new IllegalArgumentException("Invalid Sonic 2 stock DAC note 0x%02X".formatted(note));
            result.add(new Stock(note, NAMES.get(index), rate, samples[index], Shape.of(samples[index], rate)));
        }
        return result;
    }

    private static Binding nearest(int note, DacSample sample, List<Stock> stock) {
        DacSample.sampleRateHz(sample.rate()); // Validate the caller-supplied rate byte.
        byte[] pcm = sample.pcm();
        var shape = Shape.of(pcm, sample.rate());
        Stock best = stock.getFirst();
        double minimum = Double.POSITIVE_INFINITY;
        boolean exact = false;
        for (var candidate : stock) {
            if (sample.rate() == candidate.rate() && Arrays.equals(pcm, candidate.pcm())) {
                best = candidate;
                minimum = 0;
                exact = true;
                break;
            }
            double distance = shape.distance(candidate.shape());
            if (distance < minimum) {
                minimum = distance;
                best = candidate;
            }
        }
        return new Binding(note, best.note(), sample.id(), best.name(), sample.rate(), best.rate(), exact, minimum);
    }

    private record Shape(double hz, double seconds, double[] envelope, double[] spectrum) {
        private static final int BINS = 12, WINDOW_SAMPLES = 128, ANALYSIS_HZ = 8000;

        static Shape of(byte[] pcm, int rate) {
            int loops = rate == 0 ? 256 : rate;
            double hz = (53_693_175.0 / 15) * 2 / (DAC_BASE_CYCLES + 26 * (loops - 1));
            double seconds = pcm.length / hz;
            return new Shape(hz, seconds, rmsEnvelope(pcm), spectrum(pcm, hz, seconds));
        }

        private static double[] rmsEnvelope(byte[] pcm) {
            var rms = new double[BINS];
            var count = new int[BINS];
            for (int i = 0; i < pcm.length; i++) {
                int bin = i * BINS / pcm.length;
                rms[bin] += square(u8(pcm, i) - 128);
                count[bin]++;
            }
            for (int i = 0; i < rms.length; i++) rms[i] = Math.sqrt(rms[i] / Math.max(1, count[i]));
            double peak = Arrays.stream(rms).max().orElseThrow();
            if (peak > 0) for (int i = 0; i < rms.length; i++) rms[i] /= peak;
            return rms;
        }

        private static double[] spectrum(byte[] pcm, double hz, double seconds) {
            var power = new double[BINS];
            var coefficient = new double[BINS];
            for (int bin = 0; bin < BINS; bin++)
                coefficient[bin] = 2 * Math.cos(2 * Math.PI * 63 * Math.pow(1.45, bin) / ANALYSIS_HZ);
            var window = new double[WINDOW_SAMPLES];
            int windows = (int) Math.ceil(seconds * ANALYSIS_HZ / WINDOW_SAMPLES);
            int stride = Math.max(1, (windows + 63) / 64);
            for (int frame = 0; frame < windows; frame += stride) {
                for (int i = 0; i < window.length; i++) {
                    double at = (frame * window.length + i) * hz / ANALYSIS_HZ;
                    window[i] = interpolate(pcm, at) * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / window.length));
                }
                for (int bin = 0; bin < BINS; bin++) {
                    double previous = 0, before = 0;
                    for (double value : window) {
                        double next = value + coefficient[bin] * previous - before;
                        before = previous;
                        previous = next;
                    }
                    power[bin] += Math.max(0, square(previous) + square(before) - coefficient[bin] * previous * before);
                }
            }
            double total = Arrays.stream(power).sum();
            if (total > 0) for (int i = 0; i < power.length; i++) power[i] = Math.sqrt(power[i] / total);
            return power;
        }

        private static double interpolate(byte[] pcm, double at) {
            int i = (int) at;
            if (i >= pcm.length) return 0;
            double a = u8(pcm, i) - 128;
            double b = i + 1 < pcm.length ? u8(pcm, i + 1) - 128 : 0;
            return a + (b - a) * (at - i);
        }

        double distance(Shape other) {
            double spectral = 0, temporal = 0;
            for (int i = 0; i < BINS; i++) {
                spectral += square(spectrum[i] - other.spectrum[i]);
                temporal += square(envelope[i] - other.envelope[i]);
            }
            return spectral + temporal / BINS + 0.25 * square(Math.log(seconds / other.seconds))
                    + 0.10 * square(Math.log(hz / other.hz));
        }

        private static double square(double value) { return value * value; }
    }
}
