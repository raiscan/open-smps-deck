package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MaximumWeightAssignmentTest {
    @Test void maximizesTheTotalRegardlessOfStemOrder() {
        // Taking .90 first leaves .15. Moving that stem to part 1 lets the other take .85.
        assertArrayEquals(new int[]{1, 0}, MaximumWeightAssignment.match(new double[][]{
                {.90, .70}, {.85, .15}
        }));
        assertArrayEquals(new int[]{0, 1}, MaximumWeightAssignment.match(new double[][]{
                {.85, .15}, {.90, .70}
        }));
    }

    @Test void leavesStemsWithoutAnAvailablePositivePairUnmatched() {
        assertArrayEquals(new int[]{-1, 0, -1}, MaximumWeightAssignment.match(new double[][]{
                {.20, 0}, {.30, 0}, {0, 0}
        }));
    }
}
