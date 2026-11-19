package com.opensmpsdeck.headless;

import java.util.ArrayList;

final class VoiceSpectra {
    static final int RATE = 12_000;
    static final int SAMPLES_PER_TICK = RATE / S3kRenderer.PRESENTATIONS_PER_SECOND;
    private static final int HOP = 400;
    private static final double[] HANN = new double[1_024];
    private static final int[] EDGES;

    static {
        for (int i = 0; i < HANN.length; i++) HANN[i] = .5 - .5 * Math.cos(Math.TAU * i / (HANN.length - 1));
        // Keep narrow bass bins for separating nearby fundamentals. Widen at
        // higher frequencies to tolerate detuning and small pitch errors.
        var edges = new ArrayList<Integer>();
        for (int bin = 12; bin < 1_900; bin += Math.max(4, bin / 60)) edges.add(bin);
        EDGES = edges.stream().mapToInt(Integer::intValue).toArray();
    }

    private final LeadEffectMatcher.Spectrum fft = new LeadEffectMatcher.Spectrum();

    float[] extract(float[] input) {
        if (input.length < HANN.length) input = java.util.Arrays.copyOf(input, HANN.length);
        int bands = EDGES.length - 1;
        var values = new float[(1 + (input.length - HANN.length) / HOP) * bands];
        int at = 0;
        for (int start = 0; start + HANN.length <= input.length; start += HOP) {
            fft.transform(input, start, HANN);
            for (int band = 0; band < bands; band++) {
                double energy = 0;
                for (int bin = EDGES[band]; bin < EDGES[band + 1]; bin++) energy += fft.power(bin);
                values[at++] = (float) Math.sqrt(energy);
            }
        }
        return values;
    }

    float[] average(float[] input) {
        float[] frames = extract(input);
        int bands = EDGES.length - 1;
        var average = new float[bands];
        for (int i = 0; i < frames.length; i++) average[i % bands] += frames[i];
        for (int i = 0; i < bands; i++) average[i] /= frames.length / bands;
        return average;
    }

    static float[] resample(ReferenceAudio audio, int length) {
        var output = new float[length];
        var source = audio.samples();
        for (int i = 0; i < length; i++) {
            if (audio.sampleRate() >= RATE * 2) {
                int start = (int) ((long) i * audio.sampleRate() / RATE);
                int end = Math.min(source.length, (int) ((long) (i + 1) * audio.sampleRate() / RATE));
                if (end <= start) break;
                for (int index = start; index < end; index++) output[i] += source[index] / (end - start);
            } else {
                double position = (long) i * audio.sampleRate() / (double) RATE;
                int low = (int) position;
                if (low >= source.length) break;
                int high = Math.min(low + 1, source.length - 1);
                output[i] = (float) (source[low] + (position - low) * (source[high] - source[low]));
            }
        }
        return output;
    }
}
