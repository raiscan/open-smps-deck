package com.opensmpsdeck.io.midi;

import java.util.List;
import java.util.Set;

/** Neutral model of one parsed .mid file (one stem). */
public record MidiStem(String name, int ppq, List<TempoEvent> tempoMap,
                       TimeSignature timeSignature, List<MidiNoteTrack> tracks) {

    public record TempoEvent(long tick, int microsecondsPerQuarter) {}

    public record TimeSignature(int numerator, int denominator) {}

    public record MidiNoteTrack(boolean drumTrack, Set<Integer> programs, List<NoteEvent> notes) {}

    /** Last tick of any note across all tracks. */
    public long totalTicks() {
        return tracks.stream()
                .flatMap(t -> t.notes().stream())
                .mapToLong(NoteEvent::endTick)
                .max().orElse(0);
    }
}
