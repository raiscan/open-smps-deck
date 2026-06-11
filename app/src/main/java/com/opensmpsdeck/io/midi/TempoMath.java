package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.SmpsMode;

/**
 * Effective sequencer ticks per 60 Hz frame for each SMPS tempo mode,
 * computed by simulating the driver's accumulator logic.
 * Semantics mirror SmpsSequencer (synth-core) — that code is authoritative.
 *
 * <p>Verified against {@code SmpsSequencer.processTempoFrame()} (synth-core,
 * 2026-06-11):
 * <ul>
 *   <li><b>OVERFLOW2 (S2):</b> {@code tempoAccumulator += tempoWeight; if
 *       (tempoAccumulator >= tempoModBase) { tempoAccumulator -= tempoModBase; }
 *       else tick();} with {@code tempoModBase = 0x100}. An overflow frame is
 *       skipped. The simulation's {@code acc > 0xFF} is identical to
 *       {@code acc >= 0x100}, and {@code acc &= 0xFF} equals {@code acc -= 0x100}
 *       because {@code acc} never reaches 0x200. Matches.</li>
 *   <li><b>OVERFLOW (S3K):</b> ticks every non-overflow frame; the sequencer
 *       expresses this as "skip on overflow", but a 60 Hz wrapper that ticks
 *       once per frame plus one extra on overflow yields the same effective rate.
 *       Same accumulator/modBase arithmetic as above. Matches.</li>
 *   <li><b>TIMEOUT (S1):</b> the sequencer literally ticks <i>every</i> frame and,
 *       when the countdown (reloaded from the tempo byte) reaches zero, instead
 *       <i>extends</i> all active note durations by one frame. That duration
 *       stretch is musically equivalent to stalling one frame out of every
 *       {@code tempoByte}: net musical progress is {@code (tempoByte - 1)} steps
 *       per {@code tempoByte} frames. This simulation models that effective
 *       progress rate (stall-the-frame), which is what tempo fitting needs, even
 *       though the literal sequencer mechanism differs.</li>
 * </ul>
 */
public final class TempoMath {

    private static final int SIM_FRAMES = 1 << 16;

    private TempoMath() {}

    public static double ticksPerFrame(SmpsMode mode, int tempoByte) {
        int ticks = 0;
        switch (mode) {
            case S1 -> { // TIMEOUT: countdown; expiry stalls the frame
                int counter = tempoByte;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    if (--counter == 0) counter = tempoByte;
                    else ticks++;
                }
            }
            case S2 -> { // OVERFLOW2: overflow frame is skipped
                int acc = 0;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    acc += tempoByte;
                    if (acc > 0xFF) acc &= 0xFF;
                    else ticks++;
                }
            }
            case S3K -> { // OVERFLOW: overflow frame double-ticks
                int acc = 0;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    ticks++;
                    acc += tempoByte;
                    if (acc > 0xFF) { acc &= 0xFF; ticks++; }
                }
            }
        }
        return (double) ticks / SIM_FRAMES;
    }
}
