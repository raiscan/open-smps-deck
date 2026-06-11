package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.SmpsMode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestTempoFitter {

    @Test
    void overflowModeTicksOnNonOverflowFrames() {
        // S3K OVERFLOW: tick unless the accumulator overflows → 1 - tempo/256.
        // 0x40 → 0.75 (asymmetric: would be 0.25 if the semantics were inverted)
        assertEquals(0.75, TempoMath.ticksPerFrame(SmpsMode.S3K, 0x40), 0.01);
        assertEquals(0.5, TempoMath.ticksPerFrame(SmpsMode.S3K, 0x80), 0.01);
    }

    @Test
    void overflow2ModeTicksOnlyOnOverflow() {
        // S2 OVERFLOW2: tick only when the accumulator overflows → tempo/256.
        // 0x60 → 0.375 (asymmetric: would be 0.625 if the semantics were inverted)
        assertEquals(0.375, TempoMath.ticksPerFrame(SmpsMode.S2, 0x60), 0.01);
        assertEquals(0.5, TempoMath.ticksPerFrame(SmpsMode.S2, 0x80), 0.01);
    }

    @Test
    void timeoutModeStallsPeriodically() {
        // S1 TIMEOUT: SmpsSequencer ticks every frame but extends durations every
        // `tempo` frames (decrement-then-check countdown, period exactly tempo),
        // so the long-run effective rate is (tempo-1)/tempo: tempo 4 → 0.75.
        // Verified by simulating the sequencer's exact code path over a note stream.
        assertEquals(0.75, TempoMath.ticksPerFrame(SmpsMode.S1, 4), 0.01);
    }

    @Test
    void weightedMedianBpmIgnoresWobble() {
        // mostly 110 BPM (545454 us/q) with a brief 113 blip
        var map = List.of(
                new MidiStem.TempoEvent(0, 531000),      // ~113 for 100 ticks
                new MidiStem.TempoEvent(100, 545454));   // 110 for the rest
        double bpm = TempoFitter.weightedMedianBpm(map, 10000, 480);
        assertEquals(110.0, bpm, 0.5);
    }

    @Test
    void weightedMedianBpmHandlesZeroTotalTicks() {
        // every segment is zero-length → fall back to 120 BPM default
        var map = List.of(new MidiStem.TempoEvent(0, 545454));
        assertEquals(120.0, TempoFitter.weightedMedianBpm(map, 0, 480), 0.001);
    }

    @Test
    void fitFindsLowErrorCombo() {
        var map = List.of(new MidiStem.TempoEvent(0, 545454)); // 110 BPM
        TempoFitter.TempoFit fit = TempoFitter.fit(map, 10000, 480, SmpsMode.S2);
        assertTrue(fit.errorPercent() < 2.0, "residual error was " + fit.errorPercent());
        assertTrue(fit.tempoByte() >= 1 && fit.tempoByte() <= 255);
        assertTrue(fit.unitsPerSixteenth() >= 1);
        assertTrue(fit.dividingTiming() >= 1 && fit.dividingTiming() <= 32);
        assertEquals(110.0, fit.bpm(), 0.5);
        // Pinned optimum for 110 BPM in S2 (tpf = tempo/256): the score picks
        // units*div/tempo = 7/219, the best rational approximation of the ideal
        // ratio with tempo <= 255 — tempo 0xDB, dividing timing 7, 1 unit per
        // 16th, residual ~0.0102%.
        assertEquals(219, fit.tempoByte());
        assertEquals(7, fit.dividingTiming());
        assertEquals(1, fit.unitsPerSixteenth());
    }

    @Test
    void quantizerSnapsToSixteenthGrid() {
        // ppq 480 → 120 ticks per 16th
        var notes = List.of(
                new NoteEvent(5, 230, 60, 100),     // ≈ step 0, length 2
                new NoteEvent(475, 125, 62, 100));  // ≈ step 4, length 1
        var q = NoteQuantizer.quantize(notes, 480);
        assertEquals(0, q.get(0).startStep());
        assertEquals(2, q.get(0).lengthSteps());
        assertEquals(4, q.get(1).startStep());
        assertEquals(1, q.get(1).lengthSteps());
    }

    @Test
    void quantizerEnforcesMinimumLength() {
        var q = NoteQuantizer.quantize(List.of(new NoteEvent(0, 10, 60, 100)), 480);
        assertEquals(1, q.get(0).lengthSteps());
    }
}
