package com.opensmpsdeck.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestAdsrEnvelopeCalculator {

    @Test
    void instantAttackMaxSustain() {
        // AR=31 (instant), D1R=0 (no decay), D2R=0, D1L=0 (0 dB), RR=15
        // Should jump to 1.0 immediately and sustain, then release after key-off
        List<double[]> points = AdsrEnvelopeCalculator.compute(31, 0, 0, 0, 15, 0.7);
        assertFalse(points.isEmpty());
        // Level at 10% should be ~1.0 (instant attack, no decay)
        assertEquals(1.0, findLevelAt(points, 0.1), 0.05);
        // Level at 69% should still be ~1.0 (before key-off)
        assertEquals(1.0, findLevelAt(points, 0.69), 0.05);
        // Level at 95% should be decaying (after key-off at 70%)
        assertTrue(findLevelAt(points, 0.95) < 0.5);
        // Last point at time 1.0
        assertEquals(1.0, points.get(points.size() - 1)[0], 0.001);
    }

    @Test
    void slowAttackReachesFullVolume() {
        // AR=5 (slow), no decay, should not be at full volume immediately
        List<double[]> points = AdsrEnvelopeCalculator.compute(5, 0, 0, 0, 15, 0.7);
        assertTrue(findLevelAt(points, 0.05) < 0.8); // Still attacking early on
        assertEquals(1.0, findLevelAt(points, 0.5), 0.1); // Eventually reaches full
    }

    @Test
    void decayToD1LLevel() {
        // AR=31, D1R=15 (fast decay), D1L=8 (~-24 dB)
        List<double[]> points = AdsrEnvelopeCalculator.compute(31, 15, 0, 8, 15, 0.7);
        double d1lNorm = AdsrEnvelopeCalculator.d1lToNormalized(8);
        assertEquals(d1lNorm, findLevelAt(points, 0.5), 0.15);
    }

    @Test
    void zeroAttackRateProducesNoSound() {
        // AR=0: never attacks, all levels should be 0
        List<double[]> points = AdsrEnvelopeCalculator.compute(0, 0, 0, 0, 15, 0.7);
        for (double[] point : points) {
            assertEquals(0.0, point[1], 0.001);
        }
    }

    @Test
    void d1lMappingBoundaries() {
        assertEquals(1.0, AdsrEnvelopeCalculator.d1lToNormalized(0), 0.001);
        assertTrue(AdsrEnvelopeCalculator.d1lToNormalized(15) < 0.05);
        double d1l1 = AdsrEnvelopeCalculator.d1lToNormalized(1);
        assertTrue(d1l1 > 0.6 && d1l1 < 0.8, "D1L=1 (-3 dB) should be ~0.71");
    }

    /**
     * Linearly interpolates the level at a given normalized time from the envelope points.
     */
    private static double findLevelAt(List<double[]> points, double time) {
        for (int i = 0; i < points.size() - 1; i++) {
            double t0 = points.get(i)[0];
            double t1 = points.get(i + 1)[0];
            if (time >= t0 && time <= t1) {
                if (t1 == t0) {
                    return points.get(i)[1];
                }
                double frac = (time - t0) / (t1 - t0);
                return points.get(i)[1] + frac * (points.get(i + 1)[1] - points.get(i)[1]);
            }
        }
        // If time is beyond the last point, return the last level
        return points.get(points.size() - 1)[1];
    }
}
