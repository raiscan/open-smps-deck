package com.opensmpsdeck.headless;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** The separator's original mix and sample-aligned source stems. */
public final class ReconstructionStems {
    private static final double PCM_CEILING = 32767 / 32768.0;

    private final Path folder;
    private final List<String> names;
    private final ReferenceAudio.WaveLayout layout;

    private ReconstructionStems(Path folder, List<String> names, ReferenceAudio.WaveLayout layout) {
        this.folder = folder;
        this.names = names;
        this.layout = layout;
    }

    static ReconstructionStems open(Path mix) throws IOException {
        if (mix == null || !mix.getFileName().toString().equals("mix.wav")) return null;
        Path folder = mix.toAbsolutePath().normalize().getParent();
        if (!Files.isRegularFile(folder.resolve(ExternalSeparators.PROVENANCE))) return null;
        var provenance = ExternalSeparators.readProvenance(folder);
        ExternalSeparators.validateAudio(folder, provenance);
        var stems = provenance.getAsJsonArray("stemOrder").asList().stream().map(item -> item.getAsString()).toList();
        return new ReconstructionStems(folder, stems, ReferenceAudio.layout(mix, SunoImportAudio.MAX_SECONDS));
    }

    public List<String> names() { return names; }

    public Path path(String name) {
        if (!names.contains(name)) throw new IllegalArgumentException("Unknown source stem: " + name);
        return folder.resolve("stems").resolve(name + ".wav");
    }

    /**
     * Sums native-rate samples, retaining their levels unless the subset exceeds PCM headroom.
     * One gain across the entire subset avoids clipping without changing the stereo balance.
     */
    public Mix mix(List<String> selected) throws IOException {
        Set<String> enabled = Set.copyOf(selected);
        if (!names.containsAll(enabled)) throw new IllegalArgumentException("Unknown source stem");
        var paths = names.stream().filter(enabled::contains).map(this::path).toList();
        int rate = layout.sampleRate();
        int channels = layout.channels();
        int frames = Math.toIntExact(layout.frames());
        if (paths.isEmpty()) return new Mix(new float[0], rate, channels);
        var pcm = new float[Math.multiplyExact(frames, channels)];
        var block = new double[rate * channels];
        double positivePeak = 0, negativePeak = 0;
        // Average into the scratch block to keep even very large finite float WAV samples representable.
        for (int first = 0; first < frames; first += rate) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int count = Math.min(rate, frames - first);
            java.util.Arrays.fill(block, 0, count * channels, 0);
            for (var path : paths) {
                var input = ReferenceAudio.stereoRange(path, rate, first, count);
                for (int frame = 0; frame < count; frame++) {
                    for (int side = 0; side < channels; side++)
                        block[frame * channels + side] += input[frame * 2 + side] / (double) paths.size();
                }
            }
            for (int i = 0; i < count * channels; i++) {
                float sample = (float) block[i];
                pcm[first * channels + i] = sample;
                positivePeak = Math.max(positivePeak, sample);
                negativePeak = Math.max(negativePeak, -sample);
            }
        }
        double gain = Math.min(paths.size(), Math.min(PCM_CEILING / positivePeak, 1 / negativePeak));
        for (int i = 0; i < pcm.length; i++) {
            if ((i & 65535) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            pcm[i] = (float) (pcm[i] * gain);
        }
        return new Mix(pcm, rate, channels);
    }

    public static final class Mix {
        private final float[] pcm;
        private final int rate;
        private final int channels;

        private Mix(float[] pcm, int rate, int channels) {
            this.pcm = pcm;
            this.rate = rate;
            this.channels = channels;
        }

        public double sample(double seconds, int channel) {
            double frame = seconds * rate;
            int frames = pcm.length / channels;
            if (frame < 0 || frame >= frames) return 0;
            int low = (int) frame;
            int side = channel % channels;
            double first = pcm[low * channels + side];
            double next = pcm[Math.min(low + 1, frames - 1) * channels + side];
            return first + (next - first) * (frame - low);
        }
    }
}
