package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Coarse mono levels and timing of level rises at a shared time origin. */
final class ReconstructionAudioCheck {
    private static final int BIN_MICROS = 20_000;
    private static final int BINS_PER_SECOND = 50;
    private static final int BINS_PER_SECTION = 500;
    private static final int MATCH_BINS = 5;

    record Section(double startSeconds, double endSeconds, double referenceRmsDbfs,
                   double renderedRmsDbfs, int referenceOnsets, int renderedOnsets, int pairedOnsets) {}

    private ReconstructionAudioCheck() {}

    static List<Section> compare(Path source, Path rendered, int presentations) throws IOException {
        long duration = Math.min(ReferenceAudio.durationMicros(source), ReferenceAudio.durationMicros(rendered));
        duration = Math.min(duration, presentations * 1_000_000L / S3kRenderer.PRESENTATIONS_PER_SECOND);
        int bins = Math.toIntExact(duration / BIN_MICROS);
        double[] referenceEnergy = energy(source, bins);
        double[] renderedEnergy = energy(rendered, bins);
        List<Integer> referenceOnsets = onsets(referenceEnergy);
        List<Integer> renderedOnsets = onsets(renderedEnergy);
        var sections = new ArrayList<Section>();
        for (int start = 0; start < bins; start += BINS_PER_SECTION) {
            int end = Math.min(start + BINS_PER_SECTION, bins);
            int from = start, to = end;
            var reference = referenceOnsets.stream().filter(i -> i >= from && i < to).toList();
            var actual = renderedOnsets.stream().filter(i -> i >= from && i < to).toList();
            sections.add(new Section(start / (double) BINS_PER_SECOND, end / (double) BINS_PER_SECOND,
                    dbfs(referenceEnergy, start, end), dbfs(renderedEnergy, start, end),
                    reference.size(), actual.size(), countPairs(reference, actual)));
        }
        return List.copyOf(sections);
    }

    private static double[] energy(Path path, int bins) throws IOException {
        var energy = new double[bins];
        for (int base = 0; base < bins; base += BINS_PER_SECOND) {
            int count = Math.min(BINS_PER_SECOND, bins - base);
            var audio = ReferenceAudio.read(path, base / (double) BINS_PER_SECOND,
                    Math.nextUp(count / (double) BINS_PER_SECOND));
            int rate = audio.sampleRate();
            if (rate < BINS_PER_SECOND) throw new IOException("Audio check requires at least 50 samples per second");
            for (int bin = 0; bin < count; bin++) {
                int from = bin * rate / BINS_PER_SECOND;
                int to = (bin + 1) * rate / BINS_PER_SECOND;
                double squares = 0;
                for (int i = from; i < to; i++) {
                    double value = audio.samples()[i];
                    squares += value * value;
                }
                energy[base + bin] = squares / (to - from);
            }
        }
        return energy;
    }

    private static double dbfs(double[] energy, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) sum += energy[i];
        return powerDb(sum / (end - start));
    }

    private static double powerDb(double energy) {
        return 10 * Math.log10(Math.max(1e-12, energy));
    }

    private static List<Integer> onsets(double[] energy) {
        var positions = new ArrayList<Integer>();
        for (int i = 2; i + 1 < energy.length; i++) {
            double rise = powerDb(energy[i]) - powerDb(energy[i - 2]);
            double nextRise = powerDb(energy[i + 1]) - powerDb(energy[i - 1]);
            if (energy[i] >= 1e-5 && rise >= 6 && rise >= nextRise) {
                positions.add(i);
                i += MATCH_BINS - 1;
            }
        }
        return positions;
    }

    private static int countPairs(List<Integer> reference, List<Integer> actual) {
        int paired = 0, i = 0, j = 0;
        while (i < reference.size() && j < actual.size()) {
            int delta = actual.get(j) - reference.get(i);
            if (Math.abs(delta) <= MATCH_BINS) {
                paired++;
                i++;
                j++;
            } else if (delta < 0) {
                j++;
            } else {
                i++;
            }
        }
        return paired;
    }
}
