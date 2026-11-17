package com.opensmpsdeck.project;

import java.util.List;

/** Reusable monophonic source; a PSG source has an explicitly null voiceId. */
public record Sequence(String id, String name, String type, int lengthTicks, String voiceId,
                       int volume, List<NoteEvent> notes, List<CommandEvent> commands, String repeatState) {
    public Sequence { notes = List.copyOf(notes); commands=List.copyOf(commands); }
    public Sequence(String id, String name, String type, int lengthTicks, String voiceId, int volume, List<NoteEvent> notes) {
        this(id,name,type,lengthTicks,voiceId,volume,notes,List.of(),"RESET");
    }
}
