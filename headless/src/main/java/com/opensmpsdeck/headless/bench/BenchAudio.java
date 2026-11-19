package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.S3kRenderer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

final class BenchAudio {
    enum Variant { dry, room, lofi }
    record Pcm(short[] stereo, int rate) {
        int frames() { return stereo.length / 2; }
    }
    record Distance(double logMel, double onsetCorrelation) {}

    private static final int RATE = S3kRenderer.SAMPLE_RATE / 2;
    private static final int HOP = RATE / 100;
    private static final int MELS = 40;
    private static final List<Mel> RESOLUTIONS = List.of(new Mel(512), new Mel(1024), new Mel(2048));

    private BenchAudio() {}

    static Pcm read(Path path) throws IOException {
        try (var in = AudioSystem.getAudioInputStream(path.toFile())) {
            var format = in.getFormat();
            if (format.getChannels() != 2 || format.getSampleSizeInBits() != 16
                    || !format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED))
                throw new IOException("Expected stereo PCM16 benchmark WAV: " + path);
            var data = ByteBuffer.wrap(in.readAllBytes())
                    .order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            short[] samples = new short[data.remaining()];
            data.get(samples);
            return new Pcm(samples, (int) format.getSampleRate());
        } catch (UnsupportedAudioFileException failure) {
            throw new IOException("Invalid benchmark WAV: " + path, failure);
        }
    }

    static void write(Pcm pcm, Path path) throws IOException {
        var bytes = ByteBuffer.allocate(pcm.stereo.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(pcm.stereo);
        var format = new AudioFormat(pcm.rate, 16, 2, true, false);
        try (var in = new AudioInputStream(new ByteArrayInputStream(bytes.array()), format, pcm.frames())) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    static Pcm degrade(Pcm pcm, Variant variant) {
        return switch (variant) {
            case dry -> new Pcm(pcm.stereo.clone(), pcm.rate);
            case room -> room(pcm);
            case lofi -> lofi(pcm);
        };
    }

    private static Pcm room(Pcm pcm) {
        var output = new short[pcm.stereo.length];
        for (int channel = 0; channel < 2; channel++) {
            int[] millis = channel == 0 ? new int[]{29, 37, 43, 53} : new int[]{31, 41, 47, 59};
            double[][] delay = new double[millis.length][];
            double[] feedback = new double[millis.length];
            for (int tap = 0; tap < millis.length; tap++) {
                delay[tap] = new double[Math.max(1, pcm.rate * millis[tap] / 1_000)];
                feedback[tap] = Math.pow(.001, delay[tap].length / (.8 * pcm.rate));
            }
            for (int frame = 0; frame < pcm.frames(); frame++) {
                int index = 2 * frame + channel;
                double dry = pcm.stereo[index];
                double wet = 0;
                for (int tap = 0; tap < delay.length; tap++) {
                    int cursor = frame % delay[tap].length;
                    double previous = delay[tap][cursor];
                    delay[tap][cursor] = dry + previous * feedback[tap];
                    wet += previous;
                }
                output[index] = quantize(.75 * dry + .25 * wet / delay.length);
            }
        }
        return new Pcm(output, pcm.rate);
    }

    private static Pcm lofi(Pcm pcm) {
        var output = new short[pcm.stereo.length];
        double cutoff = 2 * Math.PI * Math.min(8_000, pcm.rate * .45) / pcm.rate;
        double cos = Math.cos(cutoff);
        double alpha = Math.sin(cutoff) / Math.sqrt(2);
        double b0 = (1 - cos) / (2 * (1 + alpha));
        double a1 = -2 * cos / (1 + alpha);
        double a2 = (1 - alpha) / (1 + alpha);
        for (int channel = 0; channel < 2; channel++) {
            double s1 = 0, s2 = 0;
            for (int i = channel; i < output.length; i += 2) {
                double x = pcm.stereo[i];
                double y = b0 * x + s1;
                s1 = 2 * b0 * x - a1 * y + s2;
                s2 = b0 * x - a2 * y;
                double compressed = Math.min(Math.abs(y), 8192) + Math.max(0, Math.abs(y) - 8192) / 2;
                output[i] = quantize(Math.copySign(compressed, y));
            }
        }
        return new Pcm(output, pcm.rate);
    }

    private static short quantize(double sample) {
        return (short) Math.clamp(Math.round(sample), Short.MIN_VALUE, Short.MAX_VALUE);
    }

    static float[] mono(Pcm pcm) {
        if (pcm.rate != 2 * RATE) throw new IllegalArgumentException("Unexpected benchmark sample rate: " + pcm.rate);
        float[] samples = new float[pcm.frames() / 2];
        for (int i = 0; i < samples.length; i++) {
            int index = 4 * i;
            samples[i] = (pcm.stereo[index] + pcm.stereo[index + 1]
                    + pcm.stereo[index + 2] + pcm.stereo[index + 3]) / 131072.0f;
        }
        return samples;
    }

    static Distance compare(float[] reference, float[] rendered) {
        if (reference.length != rendered.length) throw new IllegalArgumentException("Render length differs from reference");
        int steps = (reference.length + HOP - 1) / HOP;
        double error = 0;
        for (Mel mel : RESOLUTIONS) {
            var first = mel.workspace();
            var second = mel.workspace();
            for (int step = 0; step < steps; step++) {
                first.compute(reference, step * HOP);
                second.compute(rendered, step * HOP);
                for (int i = 0; i < MELS; i++) error += Math.abs(first.bands[i] - second.bands[i]);
            }
        }
        return new Distance(error / (RESOLUTIONS.size() * MELS * steps),
                correlation(onsets(reference, steps), onsets(rendered, steps)));
    }

    private static double[] onsets(float[] audio, int steps) {
        double[] out = new double[steps];
        double previous = 0;
        for (int step = 0; step < steps; step++) {
            double energy = 0;
            int start = step * HOP;
            int end = Math.min(audio.length, start + HOP);
            for (int i = start; i < end; i++) energy += audio[i] * audio[i];
            double rms = Math.sqrt(energy / (end - start));
            out[step] = Math.max(0, rms - previous);
            previous = rms;
        }
        return out;
    }

    private static double correlation(double[] a, double[] b) {
        double meanA = java.util.Arrays.stream(a).average().orElse(0);
        double meanB = java.util.Arrays.stream(b).average().orElse(0);
        double cross = 0, va = 0, vb = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i] - meanA;
            double y = b[i] - meanB;
            cross += x * y;
            va += x * x;
            vb += y * y;
        }
        if (va == 0 || vb == 0) return java.util.Arrays.equals(a, b) ? 1 : 0;
        return Math.clamp(cross / Math.sqrt(va * vb), -1, 1);
    }

    // Pitch-projected mean-square energy in the 100 ms after the truth gate.
    // Stop before a subsequent same-pitch attack so it cannot become the tail.
    static double tailEnergy(float[] audio, BenchMetrics.Event note, double nextAttack) {
        int start = Math.min(audio.length, (int) Math.round(note.end() * RATE));
        int end = Math.min(audio.length, (int) Math.round(Math.min(note.end() + .100, nextAttack) * RATE));
        double angle = 2 * Math.PI * 440 * Math.pow(2, (note.pitch() - 69) / 12.0) / RATE;
        double energy = 0;
        int blocks = 0;
        for (int i = start; i + HOP <= end; i += HOP) {
            double real = 0, imag = 0;
            for (int j = 0; j < HOP; j++) {
                real += audio[i + j] * Math.cos(angle * j);
                imag += audio[i + j] * Math.sin(angle * j);
            }
            energy += 2 * (real * real + imag * imag) / (HOP * HOP);
            blocks++;
        }
        return blocks == 0 ? Double.NaN : energy / blocks;
    }

    private static final class Mel {
        final int n;
        final int[] reversal;
        final double[] window, cos, sin;
        final int[] lower;
        final double[] fraction;

        Mel(int n) {
            this.n = n;
            reversal = new int[n];
            window = new double[n];
            cos = new double[n / 2];
            sin = new double[n / 2];
            lower = new int[n / 2 + 1];
            fraction = new double[lower.length];
            double ceiling = mel(RATE / 2.0);
            for (int i = 0; i < n; i++) {
                reversal[i] = Integer.reverse(i) >>> (32 - Integer.numberOfTrailingZeros(n));
                window[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (n - 1));
            }
            for (int i = 0; i < n / 2; i++) {
                cos[i] = Math.cos(-2 * Math.PI * i / n);
                sin[i] = Math.sin(-2 * Math.PI * i / n);
            }
            for (int i = 0; i < lower.length; i++) {
                double position = mel(i * (double) RATE / n) * (MELS + 1) / ceiling - 1;
                lower[i] = (int) Math.floor(position);
                fraction[i] = position - lower[i];
            }
        }

        private static double mel(double hz) { return Math.log1p(hz / 700); }

        Spectrum workspace() { return new Spectrum(); }

        final class Spectrum {
            final double[] real = new double[n], imag = new double[n], bands = new double[MELS];

            void compute(float[] audio, int center) {
                for (int i = 0; i < n; i++) {
                    int sample = center - n / 2 + i;
                    real[reversal[i]] = sample >= 0 && sample < audio.length ? audio[sample] * window[i] : 0;
                    imag[i] = 0;
                }
                for (int size = 2; size <= n; size *= 2) {
                    int half = size / 2;
                    for (int base = 0; base < n; base += size) {
                        for (int j = 0; j < half; j++) {
                            int even = base + j, odd = even + half, phase = j * n / size;
                            double r = cos[phase] * real[odd] - sin[phase] * imag[odd];
                            double i = sin[phase] * real[odd] + cos[phase] * imag[odd];
                            real[odd] = real[even] - r;
                            imag[odd] = imag[even] - i;
                            real[even] += r;
                            imag[even] += i;
                        }
                    }
                }
                java.util.Arrays.fill(bands, 0);
                for (int i = 0; i < lower.length; i++) {
                    double power = (real[i] * real[i] + imag[i] * imag[i]) / ((double) n * n);
                    int bin = lower[i];
                    if (bin >= 0 && bin < MELS) bands[bin] += power * (1 - fraction[i]);
                    if (bin + 1 < MELS) bands[bin + 1] += power * fraction[i];
                }
                for (int b = 0; b < MELS; b++) bands[b] = Math.log1p(1000 * Math.sqrt(bands[b]));
            }
        }
    }
}
