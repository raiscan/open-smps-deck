package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.MidiStem;
import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestMonophonicWindowFinder {

    // 480 ppq at 120 BPM → 1 tick = 1/960 s; 480 ticks = 0.5 s
    private static final TickTimeMapper MAP = new TickTimeMapper(480,
            List.of(new MidiStem.TempoEvent(0, 500000)));

    @Test
    void findsIsolatedSustainedNote() {
        var notes = List.of(
                new NoteEvent(0, 480, 60, 100),       // isolated, 0.5 s — good
                new NoteEvent(960, 480, 64, 100),     // overlapped below
                new NoteEvent(960, 480, 67, 100));
        var windows = MonophonicWindowFinder.find(notes, MAP, 3);
        assertEquals(1, windows.size());
        assertEquals(60, windows.get(0).midiPitch());
        assertEquals(0.0, windows.get(0).startSec(), 1e-6);
        assertEquals(0.5, windows.get(0).lengthSec(), 1e-6);
    }

    @Test
    void shortNotesAreRejected() {
        // 96 ticks = 0.1 s < 250 ms minimum
        var windows = MonophonicWindowFinder.find(
                List.of(new NoteEvent(0, 96, 60, 100)), MAP, 3);
        assertTrue(windows.isEmpty());
    }

    @Test
    void ranksLongerLouderWindowsFirst() {
        var notes = List.of(
                new NoteEvent(0, 480, 60, 50),        // quiet
                new NoteEvent(1920, 960, 64, 120));   // long and loud → first
        var windows = MonophonicWindowFinder.find(notes, MAP, 3);
        assertEquals(64, windows.get(0).midiPitch());
    }

    @Test
    void drumModeRequiresTemporalIsolation() {
        var kicks = List.of(
                new NoteEvent(0, 48, 36, 100),
                new NoteEvent(960, 48, 36, 100));     // isolated from the other class
        var hats = List.of(new NoteEvent(30, 48, 42, 100)); // crowds the first kick
        var windows = MonophonicWindowFinder.findDrumHits(kicks, hats, MAP, 0.06);
        assertEquals(1, windows.size());
        assertEquals(1.0, windows.get(0).startSec(), 1e-3); // tick 960 at 120 BPM/480ppq = 1.0 s
    }
}
