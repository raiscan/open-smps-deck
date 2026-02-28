package com.opensmps.deck.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes normalized ADSR envelope curves from YM2612 FM operator parameters.
 * Pure model class with no UI dependencies.
 *
 * <p>The YM2612 envelope has four phases:
 * <ol>
 *   <li><b>Attack</b> (AR 0-31): Level rises from silence to full volume.
 *       AR=0 means never attacks. AR=31 is instant.</li>
 *   <li><b>Decay 1</b> (D1R 0-31): Level falls from 1.0 toward D1L target.
 *       D1R=0 means sustain at 1.0.</li>
 *   <li><b>Decay 2</b> (D2R 0-31): Level falls from D1L toward silence.
 *       D2R=0 means sustain at D1L forever.</li>
 *   <li><b>Release</b> (RR 0-15): After key-off, level falls from current to silence.</li>
 * </ol>
 */
public final class AdsrEnvelopeCalculator {

    /** Number of points in the output envelope curve. */
    private static final int RESOLUTION = 128;

    /**
     * Maximum normalized duration for rate-to-duration mapping.
     * Represents the longest possible phase in the [0, keyOffFrac] window.
     */
    private static final double MAX_PHASE_FRACTION = 0.8;

    private AdsrEnvelopeCalculator() {
        // Utility class
    }

    /**
     * Computes the ADSR envelope curve for one operator.
     *
     * @param ar          attack rate (0-31)
     * @param d1r         decay 1 rate (0-31)
     * @param d2r         decay 2 rate (0-31)
     * @param d1l         decay 1 level (0-15)
     * @param rr          release rate (0-15)
     * @param keyOffFrac  normalized time at which key-off occurs (0.0-1.0)
     * @return list of {normalizedTime, normalizedLevel} points, time 0-1, level 0-1
     */
    public static List<double[]> compute(int ar, int d1r, int d2r, int d1l, int rr, double keyOffFrac) {
        ar = Math.max(0, Math.min(31, ar));
        d1r = Math.max(0, Math.min(31, d1r));
        d2r = Math.max(0, Math.min(31, d2r));
        d1l = Math.max(0, Math.min(15, d1l));
        rr = Math.max(0, Math.min(15, rr));
        keyOffFrac = Math.max(0.0, Math.min(1.0, keyOffFrac));

        List<double[]> points = new ArrayList<>(RESOLUTION);

        // AR=0 special case: never attacks, all levels 0
        if (ar == 0) {
            for (int i = 0; i < RESOLUTION; i++) {
                double t = (double) i / (RESOLUTION - 1);
                points.add(new double[]{t, 0.0});
            }
            return points;
        }

        double d1lNorm = d1lToNormalized(d1l);

        // Compute intrinsic phase durations as fractions of the keyOffFrac window.
        // Each rate maps to a fraction of keyOffFrac: high rate = short duration, low rate = long.
        double attackFrac = rateToDuration(ar, 31) * keyOffFrac;
        double decay1Frac = (d1r == 0) ? 0.0 : rateToDuration(d1r, 31) * keyOffFrac;
        double decay2Frac = (d2r == 0) ? 0.0 : rateToDuration(d2r, 31) * keyOffFrac;
        double releaseFrac = rateToDuration(rr, 15) * (1.0 - keyOffFrac);

        // Compute phase boundaries. If attack + decay1 + decay2 exceed keyOffFrac, scale to fit.
        double preKeyOffTotal = attackFrac + decay1Frac + decay2Frac;
        if (preKeyOffTotal > keyOffFrac && preKeyOffTotal > 0) {
            double scale = keyOffFrac / preKeyOffTotal;
            attackFrac *= scale;
            decay1Frac *= scale;
            decay2Frac *= scale;
        }

        double attackEnd = attackFrac;
        double decay1End = attackEnd + decay1Frac;
        double decay2End = decay1End + decay2Frac;

        // Compute level at key-off for the release phase
        double levelAtKeyOff = computeLevelAtTime(keyOffFrac, attackEnd, decay1End, decay2End,
                d1lNorm, d1r, d2r);

        // Release phase: from keyOffFrac to keyOffFrac + releaseFrac, clamped to 1.0
        double releaseEnd = Math.min(keyOffFrac + releaseFrac, 1.0);
        if (releaseFrac <= 0) {
            releaseEnd = 1.0; // Instant release fills remaining time
        }

        for (int i = 0; i < RESOLUTION; i++) {
            double t = (double) i / (RESOLUTION - 1);
            double level;

            if (t <= keyOffFrac) {
                level = computeLevelAtTime(t, attackEnd, decay1End, decay2End,
                        d1lNorm, d1r, d2r);
            } else if (t < releaseEnd) {
                // Release phase: linear fall from levelAtKeyOff to 0
                double releaseSpan = releaseEnd - keyOffFrac;
                double releaseProgress = (t - keyOffFrac) / releaseSpan;
                level = levelAtKeyOff * (1.0 - releaseProgress);
            } else {
                level = 0.0;
            }

            points.add(new double[]{t, Math.max(0.0, Math.min(1.0, level))});
        }

        return points;
    }

    /**
     * Converts D1L (0-15) to normalized level.
     * <ul>
     *   <li>D1L=0 -> 1.0 (0 dB attenuation, no decay)</li>
     *   <li>D1L=1 -> ~0.71 (-3 dB)</li>
     *   <li>D1L=2 -> ~0.50 (-6 dB)</li>
     *   <li>...</li>
     *   <li>D1L=14 -> ~0.008 (-42 dB)</li>
     *   <li>D1L=15 -> ~0.022 (-93 dB, near silence)</li>
     * </ul>
     *
     * @param d1l decay 1 level (0-15)
     * @return normalized level (0.0-1.0)
     */
    public static double d1lToNormalized(int d1l) {
        if (d1l == 0) {
            return 1.0;
        }
        // D1L=1-14: -3 dB to -42 dB in 3 dB steps
        // D1L=15: -93 dB (special case on real hardware)
        double dB;
        if (d1l == 15) {
            dB = -93.0;
        } else {
            dB = -3.0 * d1l;
        }
        return Math.pow(10.0, dB / 20.0);
    }

    /**
     * Maps a rate value to a normalized duration fraction.
     * Higher rate = shorter duration (exponential mapping).
     *
     * @param rate    the rate value
     * @param maxRate maximum rate for this parameter (31 for AR/D1R/D2R, 15 for RR)
     * @return duration as a fraction (0.0 for max rate, up to MAX_PHASE_FRACTION for rate=1)
     */
    private static double rateToDuration(int rate, int maxRate) {
        if (rate <= 0) {
            return MAX_PHASE_FRACTION;
        }
        if (rate >= maxRate) {
            return 0.0;
        }
        // Exponential mapping: higher rate = much shorter duration
        double normalized = (double) rate / maxRate;
        return MAX_PHASE_FRACTION * Math.pow(1.0 - normalized, 3.0);
    }

    /**
     * Computes the envelope level at a given time within the pre-key-off region.
     * After all active phases complete, the level sustains at the final phase level.
     */
    private static double computeLevelAtTime(double t, double attackEnd, double decay1End,
                                              double decay2End, double d1lNorm,
                                              int d1r, int d2r) {
        if (attackEnd > 0 && t < attackEnd) {
            // Attack phase: quadratic curve from 0 to 1 (concave shape like YM2612)
            double progress = t / attackEnd;
            return 1.0 - (1.0 - progress) * (1.0 - progress);
        } else if (d1r > 0 && decay1End > attackEnd && t < decay1End) {
            // Decay 1 phase: linear fall from 1.0 to d1lNorm
            double progress = (t - attackEnd) / (decay1End - attackEnd);
            return 1.0 + (d1lNorm - 1.0) * progress;
        } else if (d2r > 0 && decay2End > decay1End && t < decay2End) {
            // Decay 2 phase: linear fall from d1lNorm toward 0
            double progress = (t - decay1End) / (decay2End - decay1End);
            return d1lNorm * (1.0 - progress);
        } else {
            // Sustain at the level reached after completed phases
            if (d1r == 0) {
                return 1.0; // No decay, sustain at full
            }
            if (d2r == 0) {
                return d1lNorm; // After decay1, sustain at D1L
            }
            return 0.0; // After decay2 completes, silence
        }
    }
}
