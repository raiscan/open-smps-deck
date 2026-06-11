package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestVoiceSeparator {

    private static NoteEvent n(long start, long dur, int pitch) {
        return new NoteEvent(start, dur, pitch, 100);
    }

    @Test
    void chordSplitsSkylineOrder() {
        // C major triad: line 0 gets the top note
        var r = VoiceSeparator.separate(List.of(n(0, 480, 60), n(0, 480, 64), n(0, 480, 67)), 4, 15);
        assertEquals(3, r.lines().size());
        assertEquals(67, r.lines().get(0).notes().get(0).pitch());
        assertEquals(64, r.lines().get(1).notes().get(0).pitch());
        assertEquals(60, r.lines().get(2).notes().get(0).pitch());
        assertEquals(0, r.droppedNotes());
    }

    @Test
    void monophonicStaysOneLine() {
        var r = VoiceSeparator.separate(List.of(n(0, 480, 60), n(480, 480, 62), n(960, 480, 64)), 4, 15);
        assertEquals(1, r.lines().size());
        assertEquals(3, r.lines().get(0).notes().size());
    }

    @Test
    void overlappingNoteGoesToNearestFreeLine() {
        // line0 holds 72 long; a new 60 overlapping it must land on another line;
        // then 62 (nearer to 60 than to 72's history) continues that second line
        var r = VoiceSeparator.separate(List.of(
                n(0, 960, 72), n(240, 240, 60), n(960, 240, 62)), 4, 15);
        assertEquals(2, r.lines().size());
        assertEquals(List.of(62), r.lines().get(1).notes().stream()
                .skip(1).map(NoteEvent::pitch).toList());
    }

    @Test
    void overflowNotesAreDroppedAndCounted() {
        var r = VoiceSeparator.separate(List.of(
                n(0, 480, 60), n(0, 480, 64), n(0, 480, 67), n(0, 480, 72), n(0, 480, 76)), 4, 15);
        assertEquals(4, r.lines().size());
        assertEquals(1, r.droppedNotes());
    }

    @Test
    void chordEpsilonGroupsNearSimultaneousOnsets() {
        // onsets 0 and 10 ticks apart (within epsilon 15) are one chord
        var r = VoiceSeparator.separate(List.of(n(0, 480, 60), n(10, 470, 67)), 2, 15);
        assertEquals(67, r.lines().get(0).notes().get(0).pitch());
        assertEquals(60, r.lines().get(1).notes().get(0).pitch());
        assertEquals(0, r.droppedNotes());
    }
}
