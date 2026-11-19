package com.opensmpsdeck.project;

/** MIDI pitch at an integer tick: FM supports 12..106; native S3K PSG supports 45..117. */
public record NoteEvent(String id, int tick, int duration, int pitch) {}
