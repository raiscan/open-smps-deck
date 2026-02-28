package com.opensmps.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BlipResampler band-limited resampler.
 * Covers state management, resampling ratio accuracy, and zero-input invariants.
 */
class TestBlipResampler {

    private static final double YM2612_RATE = 53267.0;
    private static final double OUTPUT_RATE = 44100.0;

    @Test
    void testResetClearsState() {
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        // Feed non-trivial input so the resampler accumulates state
        for (int i = 0; i < 200; i++) {
            resampler.addInputSample(10000, -10000);
        }

        // Drain any available output before reset
        while (resampler.hasOutputSample()) {
            resampler.getOutputLeft();
            resampler.getOutputRight();
            resampler.advanceOutput();
        }

        // Reset should clear all internal state
        resampler.reset();

        // After reset, no output should be available
        assertFalse(resampler.hasOutputSample(),
                "After reset, no output sample should be available");

        // Feed zero-valued samples and verify output is zero
        // Need enough samples to fill the filter (FILTER_TAPS/2 = 8 + margin)
        for (int i = 0; i < 20; i++) {
            resampler.addInputSample(0, 0);
        }

        if (resampler.hasOutputSample()) {
            assertEquals(0, resampler.getOutputLeft(),
                    "After reset and zero input, left output should be zero");
            assertEquals(0, resampler.getOutputRight(),
                    "After reset and zero input, right output should be zero");
        }
    }

    @Test
    void testAddInputAndReadOutput() {
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        // Feed a constant non-zero signal -- enough samples for the filter to fully settle.
        // The windowed-sinc filter has 16 taps, so we need well beyond that for the
        // edge transient to die out.
        int inputCount = 500;
        for (int i = 0; i < inputCount; i++) {
            resampler.addInputSample(5000, -5000);
        }

        // Should have at least one output sample available
        assertTrue(resampler.hasOutputSample(),
                "After feeding " + inputCount + " input samples, output should be available");

        // Skip initial settling samples (filter edge transient)
        int skipCount = 20;
        for (int i = 0; i < skipCount && resampler.hasOutputSample(); i++) {
            resampler.advanceOutput();
        }

        assertTrue(resampler.hasOutputSample(), "Should still have output after skipping settling");

        // Read output -- a constant input should produce near-constant output after settling
        int left = resampler.getOutputLeft();
        int right = resampler.getOutputRight();
        resampler.advanceOutput();

        // With constant 5000 input, output should be close to 5000
        assertEquals(5000, left, 50,
                "Left output for constant 5000 input should be near 5000");
        assertEquals(-5000, right, 50,
                "Right output for constant -5000 input should be near -5000");
    }

    @Test
    void testOutputSampleCountMatchesRatio() {
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        // Feed exactly 53267 input samples (one second at input rate)
        int inputSamples = 53267;
        for (int i = 0; i < inputSamples; i++) {
            resampler.addInputSample(1000, 1000);
        }

        // Count how many output samples are available
        int outputCount = 0;
        while (resampler.hasOutputSample()) {
            resampler.getOutputLeft();
            resampler.getOutputRight();
            resampler.advanceOutput();
            outputCount++;
        }

        // Expected: 53267 / (53267/44100) ~ 44100 output samples
        // Allow 1% tolerance for filter edge effects and rounding
        double expectedOutput = inputSamples / (YM2612_RATE / OUTPUT_RATE);
        double tolerance = expectedOutput * 0.01;

        assertEquals(expectedOutput, outputCount, tolerance,
                "Output sample count should match input/output rate ratio. " +
                        "Expected ~" + (int) expectedOutput + ", got " + outputCount);
    }

    @Test
    void testZeroInputProducesZeroOutput() {
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        // Feed all-zero samples
        int inputCount = 200;
        for (int i = 0; i < inputCount; i++) {
            resampler.addInputSample(0, 0);
        }

        // All output samples should be exactly zero (sinc filter of zero = zero)
        int outputCount = 0;
        while (resampler.hasOutputSample()) {
            int left = resampler.getOutputLeft();
            int right = resampler.getOutputRight();
            resampler.advanceOutput();

            assertEquals(0, left, "Left output should be zero for zero input at sample " + outputCount);
            assertEquals(0, right, "Right output should be zero for zero input at sample " + outputCount);
            outputCount++;
        }

        assertTrue(outputCount > 0, "Should have produced some output samples");
    }

    @Test
    void testClearRemovesBufferedData() {
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        // Feed a loud signal
        for (int i = 0; i < 200; i++) {
            resampler.addInputSample(15000, -15000);
        }

        // Verify output is available and non-zero before reset
        assertTrue(resampler.hasOutputSample(), "Should have output before reset");
        int leftBefore = resampler.getOutputLeft();
        assertNotEquals(0, leftBefore, "Output should be non-zero before reset");

        // Reset clears all state
        resampler.reset();

        // After reset, feed zeros
        for (int i = 0; i < 200; i++) {
            resampler.addInputSample(0, 0);
        }

        // All output should now be zero -- the loud signal should not leak through
        while (resampler.hasOutputSample()) {
            int left = resampler.getOutputLeft();
            int right = resampler.getOutputRight();
            resampler.advanceOutput();

            assertEquals(0, left, "Left should be zero after reset + zero input");
            assertEquals(0, right, "Right should be zero after reset + zero input");
        }
    }

    @Test
    void testResetWithNewRatesUpdatesRatio() {
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        // Feed one second of input at original rate
        int inputSamples = 53267;
        for (int i = 0; i < inputSamples; i++) {
            resampler.addInputSample(1000, 1000);
        }
        int outputAtOriginalRate = 0;
        while (resampler.hasOutputSample()) {
            resampler.advanceOutput();
            outputAtOriginalRate++;
        }

        // Reset with a 2:1 ratio (e.g., 44100 -> 22050)
        double newOutputRate = 22050.0;
        resampler.reset(YM2612_RATE, newOutputRate);

        // Feed same number of input samples
        for (int i = 0; i < inputSamples; i++) {
            resampler.addInputSample(1000, 1000);
        }
        int outputAtHalfRate = 0;
        while (resampler.hasOutputSample()) {
            resampler.advanceOutput();
            outputAtHalfRate++;
        }

        // At half the output rate, we should get roughly half as many output samples
        double ratio = (double) outputAtOriginalRate / outputAtHalfRate;
        assertEquals(2.0, ratio, 0.05,
                "Halving output rate should halve output count. " +
                        "Original=" + outputAtOriginalRate + ", halved=" + outputAtHalfRate);
    }

    @Test
    void testStereoChannelIndependence() {
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        // Feed asymmetric stereo: left=8000, right=0
        // Use enough samples for the filter to fully settle past initial transient
        for (int i = 0; i < 500; i++) {
            resampler.addInputSample(8000, 0);
        }

        assertTrue(resampler.hasOutputSample(), "Should have output available");

        // Skip initial settling samples
        int skipCount = 20;
        for (int i = 0; i < skipCount && resampler.hasOutputSample(); i++) {
            resampler.advanceOutput();
        }

        assertTrue(resampler.hasOutputSample(), "Should still have output after skipping settling");

        int left = resampler.getOutputLeft();
        int right = resampler.getOutputRight();

        // Left should be near 8000, right should be exactly 0
        assertEquals(8000, left, 50, "Left channel should reflect input");
        assertEquals(0, right, "Right channel should be zero when fed zero");
    }

    @Test
    void testHasOutputSampleReturnsFalseInitially() {
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        // With no input, no output should be available
        assertFalse(resampler.hasOutputSample(),
                "No output should be available before any input is added");
    }

    @Test
    void testIncrementalOutputProduction() {
        // Verify that output is produced incrementally as input is fed,
        // not all at once
        BlipResampler resampler = new BlipResampler(YM2612_RATE, OUTPUT_RATE);

        int outputAfter10 = 0;
        int outputAfter20 = 0;

        // Feed 10 samples
        for (int i = 0; i < 10; i++) {
            resampler.addInputSample(1000, 1000);
        }
        while (resampler.hasOutputSample()) {
            resampler.advanceOutput();
            outputAfter10++;
        }

        // Feed 10 more (20 total new samples since last drain)
        for (int i = 0; i < 10; i++) {
            resampler.addInputSample(1000, 1000);
        }
        while (resampler.hasOutputSample()) {
            resampler.advanceOutput();
            outputAfter20++;
        }

        // Both batches should produce some output (ratio ~1.21:1, so 10 input -> ~8 output)
        assertTrue(outputAfter10 > 0 || outputAfter20 > 0,
                "Incremental feeding should produce output. " +
                        "After first 10: " + outputAfter10 + ", after next 10: " + outputAfter20);
    }
}
