package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestTickTimeMapper {

    @Test
    void constantTempo() {
        // 120 BPM = 500000 us/quarter, 480 ppq → 1 quarter = 0.5 s
        var m = new TickTimeMapper(480, List.of(new MidiStem.TempoEvent(0, 500000)));
        assertEquals(0.0, m.secondsAt(0), 1e-9);
        assertEquals(0.5, m.secondsAt(480), 1e-9);
        assertEquals(2.0, m.secondsAt(1920), 1e-9);
    }

    @Test
    void tempoChangeMidway() {
        // 120 BPM for first quarter, then 60 BPM (1000000 us/q)
        var m = new TickTimeMapper(480, List.of(
                new MidiStem.TempoEvent(0, 500000),
                new MidiStem.TempoEvent(480, 1000000)));
        assertEquals(0.5, m.secondsAt(480), 1e-9);
        assertEquals(1.5, m.secondsAt(960), 1e-9);
    }

    @Test
    void emptyTempoMapDefaultsTo120() {
        var m = new TickTimeMapper(480, List.of());
        assertEquals(0.5, m.secondsAt(480), 1e-9);
    }
}
