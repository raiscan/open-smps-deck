package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.SmpsMode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestTempoFitter {

    @Test
    void overflowModeAddsExtraTicks() {
        // S3K: tempo 0x80 → overflow every 2 frames → 1.5 ticks/frame
        assertEquals(1.5, TempoMath.ticksPerFrame(SmpsMode.S3K, 0x80), 0.01);
    }

    @Test
    void overflow2ModeSkipsFrames() {
        // S2: tempo 0x80 → skip every 2nd frame → 0.5 ticks/frame
        assertEquals(0.5, TempoMath.ticksPerFrame(SmpsMode.S2, 0x80), 0.01);
    }

    @Test
    void timeoutModeStallsPeriodically() {
        // S1: tempo 4 → every 4th frame stalls → 0.75 ticks/frame
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
    void fitFindsLowErrorCombo() {
        var map = List.of(new MidiStem.TempoEvent(0, 545454)); // 110 BPM
        TempoFitter.TempoFit fit = TempoFitter.fit(map, 10000, 480, SmpsMode.S2);
        assertTrue(fit.errorPercent() < 2.0, "residual error was " + fit.errorPercent());
        assertTrue(fit.unitsPerSixteenth() >= 1);
        assertTrue(fit.dividingTiming() >= 1 && fit.dividingTiming() <= 32);
        assertEquals(110.0, fit.bpm(), 0.5);
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
