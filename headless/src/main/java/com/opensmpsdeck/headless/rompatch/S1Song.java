package com.opensmpsdeck.headless.rompatch;

import java.util.Map;
import java.util.TreeMap;

/**
 * Resolved S1 portable bank at base and header offset zero. The local voice
 * table is already at the end of the bank in S1 operator order. PSG keys are
 * package slots, and DAC keys are virtual note IDs regardless of retail ID.
 */
public record S1Song(byte[] bank, int localVoiceCount, Map<Integer, byte[]> psgEnvelopes,
                     Map<Integer, Dac> dacBindings) {
    public record Dac(byte[] pcm, int rate) {
        public Dac {
            if (pcm.length == 0 || pcm.length > 65535 || rate < 0 || rate > 255)
                throw new IllegalArgumentException("Sonic 1 DAC requires 1–65535 unsigned PCM bytes and a byte rate");
            pcm = pcm.clone();
        }

        @Override public byte[] pcm() { return pcm.clone(); }
    }

    public S1Song {
        bank = bank.clone();
        psgEnvelopes = copyEnvelopes(psgEnvelopes);
        dacBindings = Map.copyOf(dacBindings);
    }

    @Override public byte[] bank() { return bank.clone(); }

    @Override public Map<Integer, byte[]> psgEnvelopes() { return copyEnvelopes(psgEnvelopes); }

    private static Map<Integer, byte[]> copyEnvelopes(Map<Integer, byte[]> envelopes) {
        var copy = new TreeMap<Integer, byte[]>();
        envelopes.forEach((slot, bytes) -> copy.put(slot, bytes.clone()));
        return Map.copyOf(copy);
    }
}
