package com.opensmps.smps;

import java.util.Map;

/**
 * Holds DAC (Digital-to-Analog Converter) sample data for SMPS playback.
 *
 * <p>Contains the raw PCM samples indexed by sample ID, a mapping from
 * DAC note numbers to sample/rate pairs, and the base CPU cycle count
 * used to calculate the DAC playback rate from the Z80 djnz loop timing.
 */
public class DacData {
    public final Map<Integer, byte[]> samples;
    public final Map<Integer, DacEntry> mapping;
    public final int baseCycles;

    public DacData(Map<Integer, byte[]> samples, Map<Integer, DacEntry> mapping) {
        this(samples, mapping, 288);
    }

    public DacData(Map<Integer, byte[]> samples, Map<Integer, DacEntry> mapping, int baseCycles) {
        this.samples = samples;
        this.mapping = mapping;
        this.baseCycles = baseCycles;
    }

    public static class DacEntry {
        public final int sampleId;
        public final int rate;

        public DacEntry(int sampleId, int rate) {
            this.sampleId = sampleId;
            this.rate = rate;
        }
    }
}
