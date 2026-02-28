package com.opensmps.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BlipDeltaBuffer: delta accumulation, frame management,
 * and sample output behaviour.
 *
 * Uses Genesis NTSC PSG clock (3579545 Hz) and 44100 Hz sample rate
 * as representative values.
 */
class TestBlipDeltaBuffer {

    private static final double PSG_CLOCK = 3_579_545.0;
    private static final double SAMPLE_RATE = 44_100.0;

    /**
     * Number of clock cycles per audio frame (~1/60s NTSC).
     * 3579545 / 60 = 59659 clocks.
     */
    private static final int CLOCKS_PER_FRAME = 59659;

    /**
     * Expected samples per frame at 44100 Hz / 60 fps = 735.
     */
    private static final int SAMPLES_PER_FRAME = 735;

    // ---------------------------------------------------------------
    // 1. Clear produces zero output
    // ---------------------------------------------------------------

    @Test
    void testClearProducesZeroOutput() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        // End a frame so readSamples has something to consume
        buf.endFrame(CLOCKS_PER_FRAME);

        int[] left = new int[SAMPLES_PER_FRAME];
        int[] right = new int[SAMPLES_PER_FRAME];
        buf.readSamples(left, right, SAMPLES_PER_FRAME);

        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            assertEquals(0, left[i], "Left sample at index " + i + " should be zero after clear");
            assertEquals(0, right[i], "Right sample at index " + i + " should be zero after clear");
        }
    }

    // ---------------------------------------------------------------
    // 2. addDelta produces non-zero output
    // ---------------------------------------------------------------

    @Test
    void testAddDeltaProducesNonZeroOutput() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        // Insert a large positive delta at t=0
        buf.addDelta(0, 8000, 8000);

        buf.endFrame(CLOCKS_PER_FRAME);

        int[] left = new int[SAMPLES_PER_FRAME];
        int[] right = new int[SAMPLES_PER_FRAME];
        buf.readSamples(left, right, SAMPLES_PER_FRAME);

        boolean hasNonZeroL = false;
        boolean hasNonZeroR = false;
        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            if (left[i] != 0) hasNonZeroL = true;
            if (right[i] != 0) hasNonZeroR = true;
        }
        assertTrue(hasNonZeroL, "Left channel should contain non-zero samples after addDelta");
        assertTrue(hasNonZeroR, "Right channel should contain non-zero samples after addDelta");
    }

    // ---------------------------------------------------------------
    // 3. addDeltaFast produces non-zero output
    // ---------------------------------------------------------------

    @Test
    void testAddDeltaFastProducesNonZeroOutput() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        // Insert a large positive delta via the fast (linear interpolation) path
        buf.addDeltaFast(0, 8000, 8000);

        buf.endFrame(CLOCKS_PER_FRAME);

        int[] left = new int[SAMPLES_PER_FRAME];
        int[] right = new int[SAMPLES_PER_FRAME];
        buf.readSamples(left, right, SAMPLES_PER_FRAME);

        boolean hasNonZeroL = false;
        boolean hasNonZeroR = false;
        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            if (left[i] != 0) hasNonZeroL = true;
            if (right[i] != 0) hasNonZeroR = true;
        }
        assertTrue(hasNonZeroL, "Left channel should contain non-zero samples after addDeltaFast");
        assertTrue(hasNonZeroR, "Right channel should contain non-zero samples after addDeltaFast");
    }

    // ---------------------------------------------------------------
    // 4. endFrame advances position (more samples become available)
    // ---------------------------------------------------------------

    @Test
    void testEndFrameAdvancesPosition() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        // Before endFrame: reading should produce at most a handful of samples
        // because offsetFp starts at factorFp/2 which is very small.
        int[] leftBefore = new int[SAMPLES_PER_FRAME];
        int[] rightBefore = new int[SAMPLES_PER_FRAME];

        // Insert a delta so we can detect output differences
        buf.addDelta(0, 5000, 5000);

        // Read without endFrame -- the available count should be ~0
        // (offsetFp >> 40 is tiny), so output arrays stay zero
        buf.readSamples(leftBefore, rightBefore, SAMPLES_PER_FRAME);

        long energyBefore = 0;
        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            energyBefore += Math.abs(leftBefore[i]) + Math.abs(rightBefore[i]);
        }

        // Now create a fresh buffer, add the same delta, but call endFrame
        BlipDeltaBuffer buf2 = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf2.clear();
        buf2.addDelta(0, 5000, 5000);
        buf2.endFrame(CLOCKS_PER_FRAME);

        int[] leftAfter = new int[SAMPLES_PER_FRAME];
        int[] rightAfter = new int[SAMPLES_PER_FRAME];
        buf2.readSamples(leftAfter, rightAfter, SAMPLES_PER_FRAME);

        long energyAfter = 0;
        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            energyAfter += Math.abs(leftAfter[i]) + Math.abs(rightAfter[i]);
        }

        assertTrue(energyAfter > energyBefore,
                "endFrame should make more samples available; energyBefore=" + energyBefore
                        + ", energyAfter=" + energyAfter);
    }

    // ---------------------------------------------------------------
    // 5. readSamples reduces available samples
    // ---------------------------------------------------------------

    @Test
    void testReadSamplesReducesAvailable() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        // Insert deltas across the frame to create a detectable signal
        buf.addDelta(0, 4000, 4000);
        buf.addDelta(CLOCKS_PER_FRAME / 2, -4000, -4000);
        buf.endFrame(CLOCKS_PER_FRAME);

        // First read: should get signal
        int[] left1 = new int[SAMPLES_PER_FRAME];
        int[] right1 = new int[SAMPLES_PER_FRAME];
        buf.readSamples(left1, right1, SAMPLES_PER_FRAME);

        long energy1 = 0;
        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            energy1 += Math.abs(left1[i]) + Math.abs(right1[i]);
        }

        // Second read without another endFrame: should produce zero or near-zero
        // because offsetFp was decremented by the first read
        int[] left2 = new int[SAMPLES_PER_FRAME];
        int[] right2 = new int[SAMPLES_PER_FRAME];
        buf.readSamples(left2, right2, SAMPLES_PER_FRAME);

        long energy2 = 0;
        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            energy2 += Math.abs(left2[i]) + Math.abs(right2[i]);
        }

        assertTrue(energy1 > 0, "First read should produce non-zero samples");
        assertTrue(energy2 < energy1,
                "Second read (without endFrame) should produce less output than first; "
                        + "energy1=" + energy1 + ", energy2=" + energy2);
    }

    // ---------------------------------------------------------------
    // 6. Multiple frames accumulate correctly
    // ---------------------------------------------------------------

    @Test
    void testMultipleFramesAccumulate() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        long totalEnergy = 0;

        // Process 5 frames, each with a positive-then-negative delta pair
        // (simulates a square wave toggle within each frame)
        for (int frame = 0; frame < 5; frame++) {
            buf.addDelta(0, 6000, 6000);
            buf.addDelta(CLOCKS_PER_FRAME / 2, -6000, -6000);
            buf.endFrame(CLOCKS_PER_FRAME);

            int[] left = new int[SAMPLES_PER_FRAME];
            int[] right = new int[SAMPLES_PER_FRAME];
            buf.readSamples(left, right, SAMPLES_PER_FRAME);

            long frameEnergy = 0;
            for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
                frameEnergy += Math.abs(left[i]) + Math.abs(right[i]);
            }
            assertTrue(frameEnergy > 0, "Frame " + frame + " should produce non-zero output");
            totalEnergy += frameEnergy;
        }

        assertTrue(totalEnergy > 0, "Accumulated energy across 5 frames should be non-zero");
    }

    // ---------------------------------------------------------------
    // 7. Zero deltas produce no output (early-exit optimisation)
    // ---------------------------------------------------------------

    @Test
    void testZeroDeltasProduceNoOutput() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        // Insert zero deltas (both paths should early-exit)
        buf.addDelta(0, 0, 0);
        buf.addDeltaFast(1000, 0, 0);
        buf.endFrame(CLOCKS_PER_FRAME);

        int[] left = new int[SAMPLES_PER_FRAME];
        int[] right = new int[SAMPLES_PER_FRAME];
        buf.readSamples(left, right, SAMPLES_PER_FRAME);

        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            assertEquals(0, left[i], "Left sample at index " + i + " should be zero for zero deltas");
            assertEquals(0, right[i], "Right sample at index " + i + " should be zero for zero deltas");
        }
    }

    // ---------------------------------------------------------------
    // 8. Stereo independence: left-only delta does not bleed to right
    // ---------------------------------------------------------------

    @Test
    void testStereoIndependence() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        // Delta only on left channel
        buf.addDelta(0, 8000, 0);
        buf.endFrame(CLOCKS_PER_FRAME);

        int[] left = new int[SAMPLES_PER_FRAME];
        int[] right = new int[SAMPLES_PER_FRAME];
        buf.readSamples(left, right, SAMPLES_PER_FRAME);

        boolean leftHasSignal = false;
        for (int s : left) {
            if (s != 0) { leftHasSignal = true; break; }
        }
        assertTrue(leftHasSignal, "Left channel should have signal");

        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            assertEquals(0, right[i],
                    "Right channel should be zero when only left delta was added (index " + i + ")");
        }
    }

    // ---------------------------------------------------------------
    // 9. reset() clears accumulated state
    // ---------------------------------------------------------------

    @Test
    void testResetClearsState() {
        BlipDeltaBuffer buf = new BlipDeltaBuffer(PSG_CLOCK, SAMPLE_RATE);
        buf.clear();

        // Accumulate some signal
        buf.addDelta(0, 10000, 10000);
        buf.endFrame(CLOCKS_PER_FRAME);

        // Read to advance integrators
        int[] tmpL = new int[SAMPLES_PER_FRAME];
        int[] tmpR = new int[SAMPLES_PER_FRAME];
        buf.readSamples(tmpL, tmpR, SAMPLES_PER_FRAME);

        // Reset should zero everything
        buf.reset(PSG_CLOCK, SAMPLE_RATE);

        buf.endFrame(CLOCKS_PER_FRAME);

        int[] left = new int[SAMPLES_PER_FRAME];
        int[] right = new int[SAMPLES_PER_FRAME];
        buf.readSamples(left, right, SAMPLES_PER_FRAME);

        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
            assertEquals(0, left[i], "Left should be zero after reset (index " + i + ")");
            assertEquals(0, right[i], "Right should be zero after reset (index " + i + ")");
        }
    }
}
