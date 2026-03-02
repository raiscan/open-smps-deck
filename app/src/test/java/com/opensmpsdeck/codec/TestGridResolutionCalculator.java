package com.opensmpsdeck.codec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGridResolutionCalculator {

    @Test
    void typicalSonic2DurationsPick6() {
        List<Integer> durations = new ArrayList<>();
        for (int i = 0; i < 80; i++) durations.add(6);
        for (int i = 0; i < 50; i++) durations.add(12);
        for (int i = 0; i < 20; i++) durations.add(24);
        for (int i = 0; i < 5; i++) durations.add(5);
        // 150 out of 155 are divisible by 6 => 96.7% >= 90%
        assertEquals(6, GridResolutionCalculator.calculate(durations));
    }

    @Test
    void allMultiplesOf3Picks3() {
        List<Integer> durations = List.of(3, 6, 9, 12, 3, 6, 3, 3, 12, 9);
        // 3 and 9 are not divisible by 6, so 6 fails; all are divisible by 3
        assertEquals(3, GridResolutionCalculator.calculate(durations));
    }

    @Test
    void manyOddballsFallBackTo1() {
        List<Integer> durations = new ArrayList<>();
        for (int i = 0; i < 5; i++) durations.add(6);
        for (int i = 0; i < 5; i++) durations.add(7);
        // 50% oddball for every candidate > 1 => falls back to 1
        assertEquals(1, GridResolutionCalculator.calculate(durations));
    }

    @Test
    void emptyDurationsReturns1() {
        assertEquals(1, GridResolutionCalculator.calculate(Collections.emptyList()));
    }

    @Test
    void singleDurationReturnsThatValue() {
        // All events same duration 48 => 100% divisible by 48 => picks 48
        assertEquals(48, GridResolutionCalculator.calculate(List.of(48, 48, 48)));
    }

    @Test
    void zoomLevelsForResolution6() {
        assertEquals(List.of(1, 2, 3, 6), GridResolutionCalculator.zoomLevels(6));
    }

    @Test
    void zoomLevelsForResolution12() {
        assertEquals(List.of(1, 2, 3, 4, 6, 12), GridResolutionCalculator.zoomLevels(12));
    }

    @Test
    void zoomLevelsForResolution1() {
        assertEquals(List.of(1), GridResolutionCalculator.zoomLevels(1));
    }

    @Test
    void zoomLevelsCappedAt16() {
        // resolution 48: divisors of 48 up to min(48,16)=16 are 1,2,3,4,6,8,12,16
        assertEquals(List.of(1, 2, 3, 4, 6, 8, 12, 16), GridResolutionCalculator.zoomLevels(48));
    }
}
