package com.opensmpsdeck.project;

import java.util.List;

/** Reusable monophonic source; a PSG source has an explicitly null voiceId. */
public record Sequence(String id, String name, String type, int lengthTicks, String voiceId,
                       int volume, List<NoteEvent> notes, List<CommandEvent> commands, String repeatState,
                       String psgEnvelopeId, String modulationEnvelopeId, List<SampleTrigger> triggers) {
    public Sequence { notes = List.copyOf(notes); commands=List.copyOf(commands); triggers=List.copyOf(triggers); }
    public Sequence(String id, String name, String type, int lengthTicks, String voiceId, int volume,
                    List<NoteEvent> notes, List<CommandEvent> commands, String repeatState,
                    String psgEnvelopeId,String modulationEnvelopeId) {
        this(id,name,type,lengthTicks,voiceId,volume,notes,commands,repeatState,psgEnvelopeId,modulationEnvelopeId,List.of());
    }
    public Sequence(String id, String name, String type, int lengthTicks, String voiceId, int volume,
                    List<NoteEvent> notes, List<CommandEvent> commands, String repeatState) {
        this(id,name,type,lengthTicks,voiceId,volume,notes,commands,repeatState,null,null);
    }
    public Sequence(String id, String name, String type, int lengthTicks, String voiceId, int volume, List<NoteEvent> notes) {
        this(id,name,type,lengthTicks,voiceId,volume,notes,List.of(),"RESET");
    }
}
