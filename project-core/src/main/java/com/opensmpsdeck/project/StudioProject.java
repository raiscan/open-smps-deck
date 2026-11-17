package com.opensmpsdeck.project;

import java.util.List;

/** Versioned, linked source document. Use ProjectFormat.validate at trust boundaries. */
public record StudioProject(int formatVersion, String name, String mode, int ticksPerBeat,
                            int tempo, List<Voice> voices, List<Sequence> sequences, List<Clip> clips) {
    public StudioProject {
        voices = List.copyOf(voices);
        sequences = List.copyOf(sequences);
        clips = List.copyOf(clips);
    }
}
