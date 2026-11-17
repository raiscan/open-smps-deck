package com.opensmpsdeck.project;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** Pure source edits. A failed edit leaves the original immutable document intact. */
public final class ProjectEdits {
    private ProjectEdits() {}
    public static StudioProject addClip(StudioProject p,Clip clip) {
        ProjectFormat.validate(p); List<Clip> clips=new ArrayList<>(p.clips()); clips.add(clip); return withClips(p,clips);
    }
    public static StudioProject replaceClip(StudioProject p,Clip clip) {
        ProjectFormat.validate(p); return changeClip(p,clip.id(),old->clip);
    }
    public static StudioProject moveClip(StudioProject p,String clipId,String lane,int startTick) {
        return changeClip(p,clipId,c->new Clip(c.id(),c.sequenceId(),lane,startTick,c.lengthTicks()));
    }
    public static StudioProject resizeClip(StudioProject p,String clipId,int lengthTicks) {
        return changeClip(p,clipId,c->new Clip(c.id(),c.sequenceId(),c.lane(),c.startTick(),lengthTicks));
    }
    public static StudioProject deleteClip(StudioProject p,String clipId) { return changeClip(p,clipId,c->null); }
    public static StudioProject addNote(StudioProject p,String sequenceId,NoteEvent note) {
        return changeSequence(p,sequenceId,s->{ List<NoteEvent> notes=new ArrayList<>(s.notes()); notes.add(note); return withNotes(s,notes); });
    }
    public static StudioProject updateNote(StudioProject p,String sequenceId,NoteEvent note) {
        return changeNote(p,sequenceId,note.id(),old->note);
    }
    public static StudioProject deleteNote(StudioProject p,String sequenceId,String noteId) { return changeNote(p,sequenceId,noteId,n->null); }

    private static StudioProject changeClip(StudioProject p,String id,UnaryOperator<Clip> change) {
        ProjectFormat.validate(p); List<Clip> clips=new ArrayList<>(); boolean found=false;
        for(Clip c : p.clips()) { if(c.id().equals(id)) { found=true; c=change.apply(c); } if(c!=null) clips.add(c); }
        if(!found) throw new IllegalArgumentException("Unknown clip '"+id+"'"); return withClips(p,clips);
    }
    private static StudioProject changeNote(StudioProject p,String sequenceId,String noteId,UnaryOperator<NoteEvent> change) {
        return changeSequence(p,sequenceId,s->{ List<NoteEvent> notes=new ArrayList<>(); boolean found=false;
            for(NoteEvent n : s.notes()) { if(n.id().equals(noteId)) { found=true; n=change.apply(n); } if(n!=null) notes.add(n); }
            if(!found) throw new IllegalArgumentException("Unknown note '"+noteId+"' in sequence '"+sequenceId+"'"); return withNotes(s,notes);
        });
    }
    private static StudioProject changeSequence(StudioProject p,String id,UnaryOperator<Sequence> change) {
        ProjectFormat.validate(p); List<Sequence> sequences=new ArrayList<>(); boolean found=false;
        for(Sequence s : p.sequences()) { if(s.id().equals(id)) { found=true; s=change.apply(s); } sequences.add(s); }
        if(!found) throw new IllegalArgumentException("Unknown sequence '"+id+"'");
        return checked(new StudioProject(p.formatVersion(),p.name(),p.mode(),p.ticksPerBeat(),p.tempo(),p.voices(),sequences,p.clips()));
    }
    private static Sequence withNotes(Sequence s,List<NoteEvent> notes) { return new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),notes); }
    private static StudioProject withClips(StudioProject p,List<Clip> clips) { return checked(new StudioProject(p.formatVersion(),p.name(),p.mode(),p.ticksPerBeat(),p.tempo(),p.voices(),p.sequences(),clips)); }
    private static StudioProject checked(StudioProject p) { ProjectFormat.validate(p); return p; }
}
