package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.SmpsMode;

/**
 * Effective sequencer ticks per 60 Hz frame for each SMPS tempo mode,
 * computed by simulating the driver's accumulator logic.
 *
 * <p>Semantics verified against {@code SmpsSequencer.processTempoFrame()}
 * (synth-core, lines ~1039-1121; {@code tempoModBase = 0x100}) — that code
 * is authoritative:
 * <ul>
 *   <li><b>OVERFLOW2 (S2):</b> {@code acc += tempo}; the track ticks ONLY when
 *       the accumulator overflows ({@code acc >= 0x100}, then
 *       {@code acc -= 0x100}). Effective rate = tempo/256; higher tempo is
 *       faster. A tempo byte of 0 never ticks at all.</li>
 *   <li><b>OVERFLOW (S3K):</b> {@code acc += tempo}; an overflow frame is
 *       SKIPPED (delay), every other frame ticks once. Effective rate =
 *       1 - tempo/256; higher tempo is slower.</li>
 *   <li><b>TIMEOUT (S1):</b> the sequencer ticks every frame, but a countdown
 *       (decrement-then-check, reloaded to the tempo byte, so its period is
 *       exactly tempo frames) extends all active note durations by 1 on
 *       expiry. Each extension cancels that frame's duration progress, so the
 *       long-run effective rate is (tempo - 1)/tempo (e.g. tempo 4 = 0.75),
 *       confirmed by simulating the sequencer's exact code path over a note
 *       stream. Note this is (t-1)/t, not t/(t+1): extensions recur every t
 *       real frames, so a nominal duration d plays for d*t/(t-1) frames.
 *       A tempo byte of 1 extends every frame and freezes the music
 *       (rate 0).</li>
 * </ul>
 */
public final class TempoMath {

    private static final int SIM_FRAMES = 1 << 16;
    private static final int TEMPO_MOD_BASE = 0x100; // SmpsSequencerConfig default

    private TempoMath() {}

    public static double ticksPerFrame(SmpsMode mode, int tempoByte) {
        int ticks = 0;
        switch (mode) {
            case S1 -> { // TIMEOUT: every frame ticks; expiry extends durations,
                         // cancelling that frame's progress
                int counter = tempoByte;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    counter--;
                    if (counter <= 0) counter = tempoByte; // extension frame: net 0
                    else ticks++;
                }
            }
            case S2 -> { // OVERFLOW2: tick only when the accumulator overflows
                int acc = 0;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    acc += tempoByte;
                    if (acc >= TEMPO_MOD_BASE) { acc -= TEMPO_MOD_BASE; ticks++; }
                }
            }
            case S3K -> { // OVERFLOW: overflow frame is skipped, others tick
                int acc = 0;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    acc += tempoByte;
                    if (acc >= TEMPO_MOD_BASE) acc -= TEMPO_MOD_BASE;
                    else ticks++;
                }
            }
        }
        return (double) ticks / SIM_FRAMES;
    }
}
