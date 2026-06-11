package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.model.DacSample;

/** Slices a drum one-shot out of stem audio into an unsigned 8-bit DacSample. */
public final class DrumSliceExtractor {

    private static final double SILENCE_DB = -48.0;
    private static final double SILENCE_HOLD_SEC = 0.03;
    private static final double NORMALIZE_PEAK = 0.89; // ≈ -1 dBFS

    // DAC playback-rate constants mirror the real driver math in
    // synth-core Ym2612Chip.playDac (lines 416-419, 1844-1847):
    //   cyclesPerBlock = baseCycles + DAC_LOOP_CYCLES * (rate - 1)
    //   cyclesPerSample = cyclesPerBlock / DAC_LOOP_SAMPLES
    //   rateHz = Z80_CLOCK / cyclesPerSample
    // The Z80 djnz loop runs (rate - 1) times; the first pass is in baseCycles.
    private static final double Z80_CLOCK = 3_579_545.0; // Ym2612Chip.Z80_CLOCK
    private static final double DAC_LOOP_CYCLES = 26.0;   // Ym2612Chip.DAC_LOOP_CYCLES
    private static final double DAC_LOOP_SAMPLES = 2.0;   // Ym2612Chip.DAC_LOOP_SAMPLES
    // S2 default; DacData.baseCycles is 301/288/297 for S1/S2/S3K. The slice
    // resample rate only sets sample-count granularity, so the S2 value is fine
    // as a fixed reference for one-shot extraction.
    private static final double DAC_BASE_CYCLES = 288.0; // DacData S2 default

    private DrumSliceExtractor() {}

    /**
     * Playback rate in Hz for a DAC rate byte, matching the driver math in
     * {@code Ym2612Chip.playDac} (synth-core, lines 1840-1847). For the
     * {@code InstrumentPanel} default rate {@code 0x0C} this yields ~12.5 kHz.
     */
    public static int dacPlaybackHz(int rateByte) {
        int effectiveRate = Math.max(1, rateByte & 0xFF);
        double cyclesPerBlock = DAC_BASE_CYCLES + DAC_LOOP_CYCLES * (effectiveRate - 1);
        double cyclesPerSample = cyclesPerBlock / DAC_LOOP_SAMPLES;
        return (int) (Z80_CLOCK / cyclesPerSample);
    }

    public static DacSample extract(float[] audio, int sampleRate, double onsetSec,
                                    double maxEndSec, String name, int rateByte) {
        int start = (int) (onsetSec * sampleRate);
        // backtrack to nearest zero crossing (≤ 5 ms) to avoid a click
        int limit = Math.max(0, start - sampleRate / 200);
        while (start > limit && !(audio[start - 1] <= 0 && audio[start] > 0)) start--;

        // end: silence floor held for 30 ms, or the hard cap
        int hardEnd = Math.min(audio.length, (int) (maxEndSec * sampleRate));
        int hop = sampleRate / 100; // 10 ms
        int silentHops = 0;
        int end = hardEnd;
        double silenceAmp = Math.pow(10, SILENCE_DB / 20);
        for (int p = start; p + hop <= hardEnd; p += hop) {
            double sum = 0;
            for (int i = 0; i < hop; i++) sum += audio[p + i] * audio[p + i];
            if (Math.sqrt(sum / hop) < silenceAmp) {
                if (++silentHops * hop >= SILENCE_HOLD_SEC * sampleRate) {
                    end = p;
                    break;
                }
            } else {
                silentHops = 0;
            }
        }
        if (end <= start) end = Math.min(start + hop, audio.length);

        // resample to the DAC playback rate, normalize, convert to unsigned 8-bit
        float[] slice = new float[end - start];
        System.arraycopy(audio, start, slice, 0, slice.length);
        slice = WavStemReader.resample(slice, sampleRate, dacPlaybackHz(rateByte));

        float peak = 1e-6f;
        for (float v : slice) peak = Math.max(peak, Math.abs(v));
        byte[] pcm = new byte[slice.length];
        for (int i = 0; i < slice.length; i++) {
            int v = (int) (slice[i] / peak * NORMALIZE_PEAK * 127) + 128;
            pcm[i] = (byte) Math.max(0, Math.min(255, v));
        }
        return new DacSample(name, pcm, rateByte);
    }
}
