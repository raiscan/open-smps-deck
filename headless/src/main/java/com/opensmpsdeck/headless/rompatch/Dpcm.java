package com.opensmpsdeck.headless.rompatch;

import java.util.Arrays;

final class Dpcm {
    record Metrics(double rmsError, int peakError, Double snrDb) {}

    private Dpcm() {}

    /** Minimum total squared error over the 256 possible decoder accumulator states. */
    static byte[] encode(byte[] pcm, byte[] deltas) {
        long unreachable = Long.MAX_VALUE / 2;
        long[] previous = new long[256], next = new long[256];
        Arrays.fill(previous, unreachable);
        previous[128] = 0;
        // For each ending state the selected nibble uniquely determines the previous state.
        byte[] back = new byte[Math.multiplyExact(pcm.length, 256)];
        for (int i = 0; i < pcm.length; i++) {
            int target = pcm[i] & 255;
            for (int state = 0; state < 256; state++) {
                long best = unreachable;
                int choice = 0;
                for (int nibble = 0; nibble < 16; nibble++) {
                    long cost = previous[(state - deltas[nibble]) & 255];
                    if (cost < best) {
                        best = cost;
                        choice = nibble;
                    }
                }
                int error = state - target;
                next[state] = best + error * error;
                back[256 * i + state] = (byte) choice;
            }
            long[] swap = previous;
            previous = next;
            next = swap;
        }
        int state = 0;
        for (int i = 1; i < 256; i++) if (previous[i] < previous[state]) state = i;
        byte[] encoded = new byte[(pcm.length + 1) / 2];
        for (int i = pcm.length - 1; i >= 0; i--) {
            int nibble = back[256 * i + state];
            encoded[i / 2] |= (byte) (nibble << ((i & 1) == 0 ? 4 : 0));
            state = (state - deltas[nibble]) & 255;
        }
        return encoded;
    }

    static byte[] decode(byte[] encoded, byte[] deltas) {
        byte[] pcm = new byte[encoded.length * 2];
        int accumulator = 128;
        for (int i = 0; i < pcm.length; i++) {
            int nibble = ((encoded[i / 2] & 255) >>> ((i & 1) == 0 ? 4 : 0)) & 15;
            accumulator = (accumulator + deltas[nibble]) & 255;
            pcm[i] = (byte) accumulator;
        }
        return pcm;
    }

    static Metrics compare(byte[] expected, byte[] actual) {
        long energy = 0, squared = 0;
        int peak = 0;
        int count = Math.max(expected.length, actual.length);
        for (int i = 0; i < count; i++) {
            int signal = i < expected.length ? (expected[i] & 255) - 128 : 0;
            int sample = i < actual.length ? (actual[i] & 255) - 128 : 0;
            int error = signal - sample;
            energy += signal * signal;
            squared += error * error;
            peak = Math.max(peak, Math.abs(error));
        }
        Double snr = squared == 0 || energy == 0 ? null : 10 * Math.log10((double) energy / squared);
        return new Metrics(Math.sqrt((double) squared / count), peak, snr);
    }
}
