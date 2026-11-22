package com.opensmpsdeck.headless;

import com.openggf.audio.synth.PsgChip;
import com.opensmpsdeck.project.TargetProfile;

/** F3 E3 clocks the 16-bit periodic noise register from PSG tone 3. */
final class ReconstructionPitchedNoise {
    static final int COMMAND = 0xe3;

    private ReconstructionPitchedNoise() {}

    private static double idealPeriod(int pitch) {
        double frequency = 440 * Math.pow(2, (pitch - 69) / 12.0);
        // The counter toggles every period. The LFSR shifts on rising edges
        // and the single set bit repeats every 16 shifts.
        return PsgChip.TICK_RATE_HZ / (2 * 16 * frequency);
    }

    static boolean playable(int pitch) {
        double ideal = idealPeriod(pitch);
        long period = Math.round(ideal);
        return period >= 2 && period <= ReconstructionCymbals.TONE_PERIOD + Byte.MAX_VALUE
                && Math.abs(1200 * Math.log(ideal / period) / Math.log(2)) <= 50;
    }

    static int detune(int pitch) {
        return detune(pitch, TargetProfile.S3K);
    }

    static int detune(int pitch, TargetProfile profile) {
        return ReconstructionCymbals.detune((int) Math.round(idealPeriod(pitch)), profile);
    }
}
