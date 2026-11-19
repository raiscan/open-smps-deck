package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Bounded-memory onset and chroma features on a common 10 ms time axis. */
final class ReconstructionRhythmAudio {
    private static final double HOP = .01;
    private static final int RATE = 8192;
    private static final int FFT_SIZE = 1024;

    record Onset(double time, double weight) {}
    private record Frame(double energy, double[] chroma) {}

    private final List<Frame> frames;

    private ReconstructionRhythmAudio(List<Frame> frames) {
        this.frames = frames;
    }

    static ReconstructionRhythmAudio read(Path path, double seconds) throws IOException {
        return read(path, seconds, true);
    }

    static ReconstructionRhythmAudio read(Path path, double seconds, boolean chords) throws IOException {
        var frames = new ArrayList<Frame>();
        for (int second = 0; second < seconds; second++) {
            double start = Math.max(0, second - .08);
            double end = Math.min(seconds, second + 1.08);
            var audio = ReferenceAudio.read(path, start, end - start);
            int first = second * 100;
            for (int i = first; i < first + 100 && i * HOP < seconds; i++)
                frames.add(frame(audio, i * HOP - start, chords));
        }
        return new ReconstructionRhythmAudio(frames);
    }

    static ReconstructionRhythmAudio extract(ReferenceAudio audio) {
        var frames = new ArrayList<Frame>();
        for (int i = 0; i * HOP < audio.samples().length / (double) audio.sampleRate(); i++)
            frames.add(frame(audio, i * HOP, true));
        return new ReconstructionRhythmAudio(frames);
    }

    List<Onset> onsets() {
        var peaks = new ArrayList<Onset>();
        double[] flux = new double[frames.size()];
        for (int i = 1; i < frames.size(); i++) {
            flux[i] = Math.max(0, Math.sqrt(frames.get(i).energy) - Math.sqrt(frames.get(i - 1).energy));
        }
        for (int i = 2; i + 2 < flux.length; i++) {
            if (flux[i] < .001 || flux[i] <= flux[i - 1] || flux[i] < flux[i + 1]
                    || flux[i] < flux[i - 2] || flux[i] < flux[i + 2]) continue;
            peaks.add(new Onset(i * HOP, Math.sqrt(flux[i])));
        }
        return peaks;
    }

    /** Compare corresponding beats, pooling unit chroma frames over the interior of each beat. */
    double similarity(double first, double second, double lengthA, double lengthB, int beats) {
        double similarity = 0;
        int measured = 0;
        for (int i = 0; i < beats; i++) {
            double[] a = beat(first + i * lengthA / beats, lengthA / beats);
            double[] b = beat(second + i * lengthB / beats, lengthB / beats);
            double dot = 0, normA = 0, normB = 0;
            for (int k = 0; k < 12; k++) {
                dot += a[k] * b[k];
                normA += a[k] * a[k];
                normB += b[k] * b[k];
            }
            if (normA + normB == 0) continue;
            if (normA * normB > 0) similarity += dot / Math.sqrt(normA * normB);
            measured++;
        }
        return measured == 0 ? 0 : Math.min(1, similarity / measured);
    }

    private double[] beat(double start, double length) {
        double[] result = new double[12];
        int first = Math.max(0, (int) Math.ceil((start + .15 * length) / HOP));
        int end = Math.min(frames.size(), (int) Math.ceil((start + .85 * length) / HOP));
        for (int i = first; i < end; i++) {
            var chroma = frames.get(i).chroma;
            for (int k = 0; k < 12; k++) result[k] += chroma[k];
        }
        return result;
    }

    private static Frame frame(ReferenceAudio audio, double center, boolean chords) {
        double energy = 0;
        int midpoint = (int) Math.round(center * audio.sampleRate());
        int half = Math.max(1, audio.sampleRate() / 200);
        for (int i = Math.max(0, midpoint - half); i < Math.min(midpoint + half, audio.samples().length); i++) {
            double sample = audio.samples()[i];
            energy += sample * sample / (2 * half);
        }
        return new Frame(energy, chords ? chroma(audio, center) : null);
    }

    private static double[] chroma(ReferenceAudio audio, double center) {
        double[] real = new double[FFT_SIZE], imaginary = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            int source = (int) Math.round((center + (i - FFT_SIZE / 2.0) / RATE) * audio.sampleRate());
            if (source >= 0 && source < audio.samples().length) real[i] = audio.samples()[source] * WINDOW[i];
        }
        fft(real, imaginary);
        double[] chroma = new double[12];
        double norm = 0;
        for (int i = 7; i < 250; i++) {
            double left = Math.hypot(real[i - 1], imaginary[i - 1]);
            double peak = Math.hypot(real[i], imaginary[i]);
            double right = Math.hypot(real[i + 1], imaginary[i + 1]);
            if (peak <= left || peak < right || peak < .015) continue;
            double bin = i + .5 * (left - right) / (left - 2 * peak + right);
            int midi = (int) Math.round(69 + 12 * Math.log(bin * RATE / FFT_SIZE / 440) / Math.log(2));
            chroma[Math.floorMod(midi, 12)] += peak;
        }
        for (double value : chroma) norm += value * value;
        if (norm > 0) {
            double scale = 1 / Math.sqrt(norm);
            for (int i = 0; i < 12; i++) chroma[i] *= scale;
        }
        return chroma;
    }

    private static final double[] WINDOW = new double[FFT_SIZE];
    private static final double[] COS = new double[FFT_SIZE / 2];
    private static final double[] SIN = new double[FFT_SIZE / 2];
    static {
        for (int i = 0; i < FFT_SIZE; i++) WINDOW[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
        for (int i = 0; i < COS.length; i++) {
            COS[i] = Math.cos(-2 * Math.PI * i / FFT_SIZE);
            SIN[i] = Math.sin(-2 * Math.PI * i / FFT_SIZE);
        }
    }

    private static void fft(double[] real, double[] imaginary) {
        for (int i = 1; i < FFT_SIZE; i++) {
            int j = Integer.reverse(i) >>> (32 - Integer.numberOfTrailingZeros(FFT_SIZE));
            if (j <= i) continue;
            double saved = real[j];
            real[j] = real[i];
            real[i] = saved;
        }
        for (int size = 2; size <= FFT_SIZE; size *= 2) {
            for (int first = 0; first < FFT_SIZE; first += size) {
                for (int i = 0; i < size / 2; i++) {
                    int k = i * FFT_SIZE / size;
                    int a = first + i, b = a + size / 2;
                    double re = COS[k] * real[b] - SIN[k] * imaginary[b];
                    double im = COS[k] * imaginary[b] + SIN[k] * real[b];
                    real[b] = real[a] - re;
                    imaginary[b] = imaginary[a] - im;
                    real[a] += re;
                    imaginary[a] += im;
                }
            }
        }
    }
}
