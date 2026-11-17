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
    public static StudioProject addCommand(StudioProject p,String sequenceId,CommandEvent event) {
        return changeSequence(p,sequenceId,s->{ List<CommandEvent> commands=new ArrayList<>(s.commands()); commands.add(event); return withCommands(s,commands); },true);
    }
    public static StudioProject updateCommand(StudioProject p,String sequenceId,CommandEvent event) {
        return changeCommand(p,sequenceId,event.id(),old->event);
    }
    public static StudioProject deleteCommand(StudioProject p,String sequenceId,String commandId) { return changeCommand(p,sequenceId,commandId,event->null); }
    public static StudioProject moveCommand(StudioProject p,String sequenceId,String commandId,int direction) {
        if(direction!=-1 && direction!=1) throw new IllegalArgumentException("Command move direction must be -1 or +1");
        return changeSequence(p,sequenceId,s->{
            List<CommandEvent> commands=new ArrayList<>(s.commands()); int at=-1;
            for(int i=0;i<commands.size();i++) if(commands.get(i).id().equals(commandId)) { at=i; break; }
            if(at<0) throw new IllegalArgumentException("Unknown command '"+commandId+"' in sequence '"+sequenceId+"'");
            int next=at+direction;
            if(next<0 || next>=commands.size()) return s;
            java.util.Collections.swap(commands,at,next); return withCommands(s,commands);
        },true);
    }
    public static StudioProject setRepeatState(StudioProject p,String sequenceId,String state) {
        return changeSequence(p,sequenceId,s->new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),s.notes(),s.commands(),state),true);
    }
    public static StudioProject setSourceSettings(StudioProject p,String sequenceId,String voiceId,int volume) {
        return changeSequence(p,sequenceId,s->new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),voiceId,volume,s.notes(),s.commands(),s.repeatState()));
    }

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
    private static StudioProject changeCommand(StudioProject p,String sequenceId,String commandId,UnaryOperator<CommandEvent> change) {
        return changeSequence(p,sequenceId,s->{ List<CommandEvent> commands=new ArrayList<>(); boolean found=false;
            for(CommandEvent event:s.commands()) { if(event.id().equals(commandId)) { found=true; event=change.apply(event); } if(event!=null) commands.add(event); }
            if(!found) throw new IllegalArgumentException("Unknown command '"+commandId+"' in sequence '"+sequenceId+"'"); return withCommands(s,commands);
        },true);
    }
    private static StudioProject changeSequence(StudioProject p,String id,UnaryOperator<Sequence> change) {
        return changeSequence(p,id,change,false);
    }
    private static StudioProject changeSequence(StudioProject p,String id,UnaryOperator<Sequence> change,boolean upgrade) {
        ProjectFormat.validate(p); List<Sequence> sequences=new ArrayList<>(); boolean found=false, changed=false;
        for(Sequence s : p.sequences()) { if(s.id().equals(id)) { found=true; Sequence next=change.apply(s); changed=!next.equals(s); s=next; } sequences.add(s); }
        if(!found) throw new IllegalArgumentException("Unknown sequence '"+id+"'");
        return checked(new StudioProject(upgrade&&changed?2:p.formatVersion(),p.name(),p.mode(),p.ticksPerBeat(),p.tempo(),p.voices(),sequences,p.clips()));
    }
    private static Sequence withNotes(Sequence s,List<NoteEvent> notes) { return new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),notes,s.commands(),s.repeatState()); }
    private static Sequence withCommands(Sequence s,List<CommandEvent> commands) { return new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),s.notes(),commands,s.repeatState()); }
    private static StudioProject withClips(StudioProject p,List<Clip> clips) { return checked(new StudioProject(p.formatVersion(),p.name(),p.mode(),p.ticksPerBeat(),p.tempo(),p.voices(),p.sequences(),clips)); }
    private static StudioProject checked(StudioProject p) { ProjectFormat.validate(p); return p; }
}
