package com.opensmpsdeck.audio.match;

import com.opensmps.synth.Ym2612Chip;

/**
 * Renders an FM voice headlessly through the real YM2612 emulator:
 * key-on at the given MIDI pitch, sustain, key-off, tail.
 * One instance per thread (the chip is stateful).
 *
 * <p>Register sequence verified against {@code Ym2612Chip} (com.opensmps.synth):
 * <ul>
 *   <li>{@code write(int port, int reg, int val)} — confirmed signature (Ym2612Chip:785).</li>
 *   <li>Key-on reg {@code 0x28}: {@code chIdx = val & 0x03} (raw index for ch 0-2),
 *       {@code mask = (val >> 4) & 0xF}. So {@code 0xF0 | channel} keys all four
 *       operators of channel 0 (Ym2612Chip:879-903).</li>
 *   <li>Frequency: reg {@code 0xA4} (block&lt;&lt;3 | fnum&gt;&gt;8) written BEFORE reg
 *       {@code 0xA0} (fnum low). Channel select is {@code addr & 0xFC}, so ch 0 uses
 *       {@code 0xA4+0} / {@code 0xA0+0} (Ym2612Chip:1033-1048).</li>
 *   <li>Pan reg {@code 0xB4}: bit7=left, bit6=right, so {@code 0xC0} = L+R
 *       (Ym2612Chip:1085-1091). NOTE: reset() actually initializes leftMask/rightMask
 *       non-zero (Ym2612Chip:626-627), so the channel is not strictly silent after
 *       reset — but setInstrument may not touch pan, so we write 0xC0 explicitly to
 *       guarantee both speakers. (Plan's "silent after reset" note is slightly
 *       inaccurate; the explicit write is still correct and harmless.)</li>
 *   <li>{@code renderStereo(int[],int[],int)} ACCUMULATES into the arrays
 *       ({@code leftBuf[i] += ...}, Ym2612Chip:1355/1373/1392) — they must be cleared
 *       per chunk. Internal per-channel clamp is ±8191 (Ym2612Chip:62-63); a single FM
 *       channel peaks at a few thousand, so {@code /32768f} lands in (0,1].</li>
 * </ul>
 */
public final class CandidateRenderer {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHUNK = 512;
    private static final int CHANNEL = 0;
    // Z80 driver F-number table, semitone C..B
    private static final int[] FNUM = {644, 683, 723, 766, 813, 860, 911, 965,
                                       1023, 1084, 1148, 1216};

    private final Ym2612Chip chip = new Ym2612Chip();

    public CandidateRenderer() {
        chip.setOutputSampleRate(SAMPLE_RATE);
    }

    public float[] render(byte[] voice25, int midiPitch, double keyOnSec, double tailSec) {
        chip.reset();
        chip.setOutputSampleRate(SAMPLE_RATE);
        chip.setInstrument(CHANNEL, voice25);
        chip.write(0, 0xB4 + CHANNEL, 0xC0); // pan L+R (silent after reset otherwise)

        int semitone = Math.floorMod(midiPitch, 12);
        int block = Math.max(0, Math.min(7, midiPitch / 12 - 1));
        int fnum = FNUM[semitone];
        chip.write(0, 0xA4 + CHANNEL, (block << 3) | (fnum >> 8)); // high byte FIRST
        chip.write(0, 0xA0 + CHANNEL, fnum & 0xFF);

        int keyOnSamples = (int) (keyOnSec * SAMPLE_RATE);
        int tailSamples = (int) (tailSec * SAMPLE_RATE);
        float[] out = new float[keyOnSamples + tailSamples];

        chip.write(0, 0x28, 0xF0 | CHANNEL); // all four operators on
        renderInto(out, 0, keyOnSamples);
        chip.write(0, 0x28, CHANNEL);        // key off
        renderInto(out, keyOnSamples, tailSamples);
        return out;
    }

    private void renderInto(float[] out, int offset, int count) {
        int[] left = new int[CHUNK];
        int[] right = new int[CHUNK];
        int done = 0;
        while (done < count) {
            int n = Math.min(CHUNK, count - done);
            java.util.Arrays.fill(left, 0, n, 0);   // renderStereo accumulates
            java.util.Arrays.fill(right, 0, n, 0);
            chip.renderStereo(left, right, n);
            for (int i = 0; i < n; i++) {
                out[offset + done + i] = (left[i] + right[i]) / 2f / 32768f;
            }
            done += n;
        }
    }
}
