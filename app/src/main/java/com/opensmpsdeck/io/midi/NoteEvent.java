package com.opensmpsdeck.io.midi;

/** A single MIDI note in absolute ticks. */
public record NoteEvent(long startTick, long durationTicks, int pitch, int velocity) {

    public long endTick() {
        return startTick + durationTicks;
    }
}
