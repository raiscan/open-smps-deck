package com.opensmpsdeck.project;

import java.util.List;

/** Reusable monophonic source; a PSG source has an explicitly null voiceId. */
public record Sequence(String id, String name, String type, int lengthTicks, String voiceId,
                       int volume, List<NoteEvent> notes) {
    public Sequence { notes = List.copyOf(notes); }
}
