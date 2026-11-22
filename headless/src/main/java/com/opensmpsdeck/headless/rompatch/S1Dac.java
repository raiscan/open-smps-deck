package com.opensmpsdeck.headless.rompatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.rompatch.S1Revision.*;

final class S1Dac {
    record Binding(int note, int mappedNote, String kind, String stockSample, int requestedRate,
                   int rate, int dpcmBytes, boolean exact, Dpcm.Metrics metrics, Double distance) {}
    record Plan(List<Binding> bindings, Map<Integer, Byte> writes) {}

    private record Sample(byte[] pcm, int rate) {
        boolean matches(Sample other) { return rate == other.rate && Arrays.equals(pcm, other.pcm); }
        int encodedLength() { return (pcm.length + 1) / 2; }
    }

    private record Installed(int note, int length, boolean exact, Dpcm.Metrics metrics) {}

    private record Stock(int note, String name, Sample sample, Shape shape) {
        Stock(int note, String name, Sample sample) { this(note, name, sample, Shape.of(sample)); }
    }

    private S1Dac() {}

    static Plan plan(byte[] rom, byte[] driver, byte[] bank, List<Integer> operands,
                     Map<Integer, S1Song.Dac> bindings) {
        if (operands.isEmpty()) return new Plan(List.of(), Map.of());
        var requested = new TreeMap<Integer, Sample>();
        for (int at : operands) {
            int note = u8(bank, at);
            var binding = bindings.get(note);
            S1Bank.require(binding != null, at, "undefined package DAC note 0x%02X".formatted(note));
            requested.computeIfAbsent(note, ignored -> new Sample(binding.pcm(), binding.rate()));
        }

        byte[] deltas = Arrays.copyOfRange(driver, DAC_DELTAS, DAC_DELTAS + 16);
        List<Stock> stock = stock(rom, driver, deltas);
        var exact = new TreeMap<Integer, Stock>();
        var custom = new ArrayList<Sample>();
        for (var entry : requested.entrySet()) {
            var sample = entry.getValue();
            Stock match = stock.stream().filter(candidate -> candidate.sample().matches(sample)).findFirst().orElse(null);
            if (match != null) exact.put(entry.getKey(), match);
            else if (custom.stream().noneMatch(sample::matches)) custom.add(sample);
        }

        boolean fits = custom.size() <= CUSTOM_DAC_ENTRIES
                && custom.stream().mapToInt(Sample::encodedLength).sum() <= Z80_RESERVED - DAC_PAYLOAD;
        var writes = new LinkedHashMap<Integer, Byte>();
        var installed = fits ? install(driver, deltas, custom, writes) : List.<Installed>of();
        var result = new TreeMap<Integer, Binding>();
        requested.forEach((note, sample) -> {
            Stock match = exact.get(note);
            Binding mapped;
            if (match != null) {
                mapped = stockBinding(note, sample, match, 0, true);
            } else if (fits) {
                int index = 0;
                while (!sample.matches(custom.get(index))) index++;
                var item = installed.get(index);
                mapped = new Binding(note, item.note(), "custom", null, sample.rate(), sample.rate(),
                        item.length(), item.exact(), item.metrics(), null);
            } else {
                mapped = nearest(note, sample, stock);
            }
            result.put(note, mapped);
        });
        for (int at : operands) bank[at] = (byte) result.get(u8(bank, at)).mappedNote();
        return new Plan(List.copyOf(result.values()), writes);
    }

    private static List<Stock> stock(byte[] rom, byte[] driver, byte[] deltas) {
        var stock = new ArrayList<Stock>();
        for (int i = 0; i < STOCK_DAC_ENTRIES - 1; i++) {
            var range = STOCK_DAC.get(i);
            byte[] pcm = Dpcm.decode(Arrays.copyOfRange(driver, range.start(), range.end()), deltas);
            int rate = u8(driver, DAC_TABLE + i * DAC_ENTRY_SIZE + 4);
            stock.add(new Stock(0x81 + i, range.name(), new Sample(pcm, rate)));
        }
        // 0x83's pitch depends on the last alias played. The 0x88–0x8C
        // commands set their own rates; 0x8D duplicates 0x8C.
        var range = STOCK_DAC.getLast();
        byte[] pcm = Dpcm.decode(Arrays.copyOfRange(driver, range.start(), range.end()), deltas);
        for (int i = 0; i < 5; i++)
            stock.add(new Stock(0x88 + i, range.name(), new Sample(pcm, u8(rom, TIMPANI_RATES + i))));
        return stock;
    }

    private static List<Installed> install(byte[] driver, byte[] deltas, List<Sample> custom,
                                         Map<Integer, Byte> writes) {
        if (custom.isEmpty()) return List.of();
        // Relocate only the lookup. The bytes at 0xEE onward are kick data,
        // not spare entries. The stock table and sample data stay in place.
        word(writes, DAC_TABLE_OP + 2, NEW_DAC_TABLE);
        for (int i = 0; i < STOCK_DAC_ENTRIES * DAC_ENTRY_SIZE; i++)
            writes.put(NEW_DAC_TABLE + i, driver[DAC_TABLE + i]);
        var installed = new ArrayList<Installed>();
        int payload = DAC_PAYLOAD;
        for (int i = 0; i < CUSTOM_DAC_ENTRIES; i++) {
            int entry = NEW_DAC_TABLE + (STOCK_DAC_ENTRIES + i) * DAC_ENTRY_SIZE;
            if (i >= custom.size()) {
                for (int j = 0; j < DAC_ENTRY_SIZE; j++) writes.put(entry + j, driver[DAC_TABLE + j]);
                continue;
            }
            var sample = custom.get(i);
            byte[] encoded = Dpcm.encode(sample.pcm(), deltas);
            word(writes, entry, payload);
            word(writes, entry + 2, encoded.length);
            word(writes, entry + 4, sample.rate());
            word(writes, entry + 6, 0);
            for (byte value : encoded) writes.put(payload++, value);
            var metrics = Dpcm.compare(sample.pcm(), Dpcm.decode(encoded, deltas));
            boolean exact = (sample.pcm().length & 1) == 0 && metrics.peakError() == 0;
            installed.add(new Installed(0x84 + i, encoded.length, exact, metrics));
        }
        return installed;
    }

    private static void word(Map<Integer, Byte> writes, int at, int value) {
        writes.put(at, (byte) value);
        writes.put(at + 1, (byte) (value >>> 8));
    }

    private static Binding nearest(int note, Sample sample, List<Stock> stock) {
        var shape = Shape.of(sample);
        Stock best = null;
        double min = Double.POSITIVE_INFINITY;
        for (var candidate : stock) {
            double distance = shape.distance(candidate.shape());
            if (distance < min) {
                min = distance;
                best = candidate;
            }
        }
        return stockBinding(note, sample, best, min, false);
    }

    private static Binding stockBinding(int note, Sample source, Stock stock, double distance, boolean exact) {
        return new Binding(note, stock.note(), "stock", stock.name(), source.rate(), stock.sample().rate(),
                0, exact, null, distance);
    }

    private record Shape(double hz, double seconds, double[] envelope, double[] spectrum) {
        private static final int BINS = 12;
        private static final int ANALYSIS_HZ = 8000;
        private static final int WINDOW = 128;

        static Shape of(Sample sample) {
            int rate = sample.rate() == 0 ? 256 : sample.rate();
            double hz = 7_159_090.0 / (301 + 26 * (rate - 1));
            double seconds = sample.pcm().length / hz;
            var envelope = new double[BINS];
            var counts = new int[BINS];
            for (int i = 0; i < sample.pcm().length; i++) {
                int bin = i * BINS / sample.pcm().length;
                double value = u8(sample.pcm(), i) - 128;
                envelope[bin] += value * value;
                counts[bin]++;
            }
            double peak = 0;
            for (int i = 0; i < BINS; i++) {
                envelope[i] = Math.sqrt(envelope[i] / Math.max(1, counts[i]));
                peak = Math.max(peak, envelope[i]);
            }
            if (peak != 0) for (int i = 0; i < BINS; i++) envelope[i] /= peak;

            // Windowed Goertzel power at logarithmically spaced physical frequencies.
            // Evaluate at most 64 windows, spread over the sample.
            var spectrum = new double[BINS];
            var window = new double[WINDOW];
            int frames = Math.max(1, (int) Math.ceil(seconds * ANALYSIS_HZ / WINDOW));
            int stride = (frames + 63) / 64;
            for (int frame = 0; frame < frames; frame += stride) {
                for (int n = 0; n < WINDOW; n++) {
                    double at = (frame * WINDOW + n) * hz / ANALYSIS_HZ;
                    window[n] = interpolate(sample.pcm(), at) * (0.5 - 0.5 * Math.cos(2 * Math.PI * n / WINDOW));
                }
                for (int bin = 0; bin < BINS; bin++) {
                    double coefficient = 2 * Math.cos(2 * Math.PI * 63 * Math.pow(1.45, bin) / ANALYSIS_HZ);
                    double previous = 0, before = 0;
                    for (double value : window) {
                        double next = value + coefficient * previous - before;
                        before = previous;
                        previous = next;
                    }
                    spectrum[bin] += Math.max(0, previous * previous + before * before - coefficient * previous * before);
                }
            }
            double total = Arrays.stream(spectrum).sum();
            if (total > 0) for (int i = 0; i < BINS; i++) spectrum[i] = Math.sqrt(spectrum[i] / total);
            return new Shape(hz, seconds, envelope, spectrum);
        }

        private static double interpolate(byte[] pcm, double at) {
            int i = (int) at;
            if (i >= pcm.length) return 0;
            double first = u8(pcm, i) - 128;
            double second = i + 1 < pcm.length ? u8(pcm, i + 1) - 128 : 0;
            return first + (second - first) * (at - i);
        }

        double distance(Shape other) {
            double spectral = 0, temporal = 0;
            for (int i = 0; i < BINS; i++) {
                spectral += square(spectrum[i] - other.spectrum[i]);
                temporal += square(envelope[i] - other.envelope[i]);
            }
            return spectral + temporal / BINS + 0.25 * square(Math.log(seconds / other.seconds))
                    + 0.1 * square(Math.log(hz / other.hz));
        }

        private static double square(double value) { return value * value; }
    }
}
