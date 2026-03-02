package com.opensmpsdeck.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Picks the best grid resolution for the unrolled timeline view.
 *
 * <p>{@link #calculate(List)} finds the largest candidate step size where
 * at least 90% of note events have durations evenly divisible by it.
 * {@link #zoomLevels(int)} returns the available zoom multipliers for a
 * given base resolution.
 */
public final class GridResolutionCalculator {

    /** Candidate resolutions tried largest-first. */
    private static final int[] CANDIDATES = {48, 24, 12, 6, 3, 2, 1};

    /** Minimum fraction of events that must be divisible by a candidate. */
    private static final double THRESHOLD = 0.90;

    /** Maximum zoom multiplier returned by {@link #zoomLevels(int)}. */
    private static final int MAX_ZOOM = 16;

    private GridResolutionCalculator() {}

    /**
     * Tolerant practical-GCD algorithm. Picks the largest candidate grid step
     * where at least 90% of note events have durations divisible by it.
     *
     * @param durations list of note/rest durations in ticks
     * @return the best grid resolution, or 1 as fallback
     */
    public static int calculate(List<Integer> durations) {
        if (durations.isEmpty()) {
            return 1;
        }
        int total = durations.size();
        for (int candidate : CANDIDATES) {
            int count = 0;
            for (int d : durations) {
                if (d % candidate == 0) {
                    count++;
                }
            }
            if (count >= THRESHOLD * total) {
                return candidate;
            }
        }
        return 1;
    }

    /**
     * Compute available zoom multipliers for a given base resolution.
     *
     * <p>Returns all integers N where {@code 1 <= N <= min(resolution, 16)}
     * and {@code resolution % N == 0}, sorted ascending.
     *
     * @param resolution the base grid resolution
     * @return sorted list of zoom multipliers
     */
    public static List<Integer> zoomLevels(int resolution) {
        int cap = Math.min(resolution, MAX_ZOOM);
        List<Integer> levels = new ArrayList<>();
        for (int n = 1; n <= cap; n++) {
            if (resolution % n == 0) {
                levels.add(n);
            }
        }
        return Collections.unmodifiableList(levels);
    }
}
