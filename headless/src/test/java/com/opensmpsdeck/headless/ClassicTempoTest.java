package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassicTempoTest {
    @Test void choosesNearestRepresentableRateForEverySourceByte() {
        for (int source = 0; source <= 255; source++) {
            var s1 = ClassicPackageDriver.S1.convertTempo(source);
            var s2 = ClassicPackageDriver.S2.convertTempo(source);
            double rate = 1 - source / 256.0;

            int nearest = 2;
            for (int candidate = 3; candidate <= 255; candidate++) {
                if (Math.abs(s1Rate(candidate) - rate) < Math.abs(s1Rate(nearest) - rate))
                    nearest = candidate;
            }
            assertEquals(nearest, s1.targetByte(), "S1 source " + source);
            assertEquals(rate == s1Rate(nearest), s1.exact(), "S1 source " + source);
            assertEquals(100 * (s1Rate(nearest) - rate) / rate, s1.relativeErrorPercent(), 1e-11);
            // The widest interior S1 rate gap is between countdowns 2 and 3.
            // Below the minimum rate the endpoint is the nearest byte.
            double errorBound = rate < 0.5 ? 0.5 - rate : 1 / 12.0;
            assertTrue(Math.abs(s1Rate(nearest) - rate) <= errorBound);

            assertEquals(source == 0 ? 255 : 256 - source, s2.targetByte());
            assertEquals(source != 0, s2.exact());
            assertEquals(source == 0 ? -100 / 256.0 : 0, s2.relativeErrorPercent());
        }
    }

    @Test void checksPhaseOverAFullAccumulatorPeriodWheneverRatesMatch() {
        for (int source = 1; source <= 255; source++) {
            var s1 = ClassicPackageDriver.S1.convertTempo(source);
            var s2 = ClassicPackageDriver.S2.convertTempo(source);
            int s3kAccumulator = source;
            int s2Accumulator = s2.targetByte();
            int s1Countdown = s1.targetByte();
            int s3kTicks = 0;
            int s2Ticks = 0;
            boolean s2PhasesMatch = true;
            for (int frame = 1; frame <= 256; frame++) {
                // The S3K load service advances tracks before this song's first TempoWait.
                boolean s3kTick = frame == 1 || s3kAccumulator + source < 256;
                if (frame > 1) s3kAccumulator = (s3kAccumulator + source) & 255;
                // The S2 load service runs TempoWait using the header's seed.
                s2Accumulator += s2.targetByte();
                boolean s2Tick = s2Accumulator >= 256;
                s2Accumulator &= 255;
                if (s3kTick) s3kTicks++;
                if (s2Tick) s2Ticks++;
                s2PhasesMatch &= s3kTick == s2Tick;
                assertTrue(Math.abs(s3kTicks - s2Ticks) <= 1);

                if (s1.exact()) {
                    boolean s1Tick = --s1Countdown != 0;
                    if (!s1Tick) s1Countdown = s1.targetByte();
                    assertEquals(s3kTick, s1Tick, "S1 source " + source + ", frame " + frame);
                }
            }
            assertEquals(s3kTicks, s2Ticks, "S2 source " + source);
            assertEquals(256 % source == 0, s2PhasesMatch, "S2 source " + source);
        }
    }

    private static double s1Rate(int countdown) {
        return (countdown - 1.0) / countdown;
    }
}
