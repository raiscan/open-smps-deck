package com.opensmpsdeck.project;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** Pure source edits. A failed edit leaves the original immutable document intact. */
public final class ProjectEdits {
    private ProjectEdits() {}
    public static StudioProject setNativeProgram(StudioProject p, NativeProgram program) {
        return checked(new StudioProject(5,p.name(),p.mode(),p.ticksPerBeat(),program.tempo(),
                p.voices(),p.sequences(),p.clips(),p.envelopes(),p.samples(),program,p.references(),p.notes()).withDerivedFrom(p.derivedFrom()).withTarget(p.target()));
    }

    public static StudioProject addReference(StudioProject project, AudioReference reference) {
        var references = new ArrayList<>(project.references());
        references.add(reference);
        return checked(project.withReview(references, project.notes()));
    }

    public static StudioProject offsetReference(StudioProject project, String id, double seconds) {
        return checked(project.withReview(project.references().stream()
                .map(ref -> ref.id().equals(id) ? ref.withOffset(seconds) : ref).toList(), project.notes()));
    }

    public static StudioProject addReviewNote(StudioProject project, ReviewNote note) {
        var notes = new ArrayList<>(project.notes());
        notes.add(note);
        return checked(project.withReview(project.references(), notes));
    }

    public static StudioProject resolveReviewNote(StudioProject project, String id, ReviewNote.Reply reply) {
        if (project.notes().stream().noneMatch(note -> note.id().equals(id))) {
            throw new IllegalArgumentException("Unknown review note: " + id);
        }
        return checked(project.withReview(project.references(), project.notes().stream()
                .map(note -> note.id().equals(id) ? note.resolve(reply) : note).toList()));
    }

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
        return changeSequence(p,sequenceId,s->{ List<CommandEvent> commands=new ArrayList<>(s.commands()); commands.add(event); return withCommands(s,commands); },commandVersion(event));
    }
    public static StudioProject updateCommand(StudioProject p,String sequenceId,CommandEvent event) {
        return changeCommand(p,sequenceId,event.id(),old->event,commandVersion(event));
    }
    public static StudioProject deleteCommand(StudioProject p,String sequenceId,String commandId) { return changeCommand(p,sequenceId,commandId,event->null,2); }
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
        return changeSequence(p,sequenceId,s->new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),s.notes(),s.commands(),state,s.psgEnvelopeId(),s.modulationEnvelopeId(),s.triggers()),true);
    }
    public static StudioProject setSourceSettings(StudioProject p,String sequenceId,String voiceId,int volume) {
        return changeSequence(p,sequenceId,s->new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),voiceId,volume,s.notes(),s.commands(),s.repeatState(),s.psgEnvelopeId(),s.modulationEnvelopeId(),s.triggers()));
    }
    public static StudioProject setSourceEnvelopes(StudioProject p,String sequenceId,String psgId,String modulationId) {
        return changeSequence(p,sequenceId,s->new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),s.notes(),s.commands(),s.repeatState(),psgId,modulationId,s.triggers()),3);
    }

    public static StudioProject addSequence(StudioProject p,Sequence sequence) {
        ProjectFormat.validate(p);
        List<Sequence> sequences=new ArrayList<>(p.sequences());
        sequences.add(sequence);
        int version="DAC".equals(sequence.type())?Math.max(4,p.arrangementVersion()):p.arrangementVersion();
        return checked(p.withArrangement(version,p.voices(),sequences,p.clips(),p.envelopes(),p.samples()));
    }

    public static StudioProject addSample(StudioProject p,DacSample sample) {
        ProjectFormat.validate(p);
        List<DacSample> samples=new ArrayList<>(p.samples());
        samples.add(sample);
        return withSamples(p,samples);
    }
    public static StudioProject updateSample(StudioProject p,DacSample sample) {
        return changeSample(p,sample.id(),old->sample);
    }
    public static StudioProject deleteSample(StudioProject p,String sampleId) {
        ProjectFormat.validate(p);
        if(p.sequences().stream().flatMap(s->s.triggers().stream()).anyMatch(t->t.sampleId().equals(sampleId)))
            throw new IllegalArgumentException("Sample '"+sampleId+"' is referenced by a source");
        return changeSample(p,sampleId,old->null);
    }
    private static StudioProject changeSample(StudioProject p,String id,UnaryOperator<DacSample> change) {
        ProjectFormat.validate(p);
        List<DacSample> samples=new ArrayList<>();
        boolean found=false;
        for(DacSample sample : p.samples()) {
            if(sample.id().equals(id)) { found=true; sample=change.apply(sample); }
            if(sample!=null) samples.add(sample);
        }
        if(!found) throw new IllegalArgumentException("Unknown sample '"+id+"'");
        return withSamples(p,samples);
    }
    private static StudioProject withSamples(StudioProject p,List<DacSample> samples) {
        return checked(p.withArrangement(Math.max(4,p.arrangementVersion()),p.voices(),p.sequences(),p.clips(),p.envelopes(),samples));
    }

    public static StudioProject addTrigger(StudioProject p,String sequenceId,SampleTrigger trigger) {
        return changeSequence(p,sequenceId,s->{
            List<SampleTrigger> triggers=new ArrayList<>(s.triggers());
            triggers.add(trigger);
            return withTriggers(s,triggers);
        },4);
    }
    public static StudioProject updateTrigger(StudioProject p,String sequenceId,SampleTrigger trigger) {
        return changeTrigger(p,sequenceId,trigger.id(),old->trigger);
    }
    public static StudioProject deleteTrigger(StudioProject p,String sequenceId,String triggerId) {
        return changeTrigger(p,sequenceId,triggerId,old->null);
    }
    private static StudioProject changeTrigger(StudioProject p,String sequenceId,String triggerId,UnaryOperator<SampleTrigger> change) {
        return changeSequence(p,sequenceId,s->{
            List<SampleTrigger> triggers=new ArrayList<>();
            boolean found=false;
            for(SampleTrigger trigger : s.triggers()) {
                if(trigger.id().equals(triggerId)) { found=true; trigger=change.apply(trigger); }
                if(trigger!=null) triggers.add(trigger);
            }
            if(!found) throw new IllegalArgumentException("Unknown trigger '"+triggerId+"' in sequence '"+sequenceId+"'");
            return withTriggers(s,triggers);
        },4);
    }
    private static Sequence withTriggers(Sequence s,List<SampleTrigger> triggers) {
        return new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),s.notes(),s.commands(),s.repeatState(),s.psgEnvelopeId(),s.modulationEnvelopeId(),triggers);
    }

    public static StudioProject addVoice(StudioProject p,Voice voice) {
        ProjectFormat.validate(p);List<Voice> voices=new ArrayList<>(p.voices());voices.add(voice);
        return withAssets(p,p.arrangementVersion(),voices,p.envelopes());
    }
    public static StudioProject updateVoice(StudioProject p,Voice voice) {
        return changeVoice(p,voice.id(),old->voice);
    }
    public static StudioProject deleteVoice(StudioProject p,String voiceId) {
        if (p.nativeProgram()!=null) throw new IllegalArgumentException("Native global voice indices must be preserved");
        ProjectFormat.validate(p);
        if(p.sequences().stream().anyMatch(s->voiceId.equals(s.voiceId()) || s.commands().stream()
                .anyMatch(event->event.command().asset()==NativeCommand.Asset.VOICE && voiceId.equals(event.assetId())))) throw new IllegalArgumentException("Voice '"+voiceId+"' is referenced by a source");
        return changeVoice(p,voiceId,old->null);
    }
    public static StudioProject addEnvelope(StudioProject p,Envelope envelope) {
        ProjectFormat.validate(p);List<Envelope> envelopes=new ArrayList<>(p.envelopes());envelopes.add(envelope);
        return withAssets(p,3,p.voices(),envelopes);
    }
    public static StudioProject updateEnvelope(StudioProject p,Envelope envelope) {
        return changeEnvelope(p,envelope.id(),old->envelope);
    }
    public static StudioProject deleteEnvelope(StudioProject p,String envelopeId) {
        ProjectFormat.validate(p);
        if(p.sequences().stream().anyMatch(s->envelopeId.equals(s.psgEnvelopeId()) || envelopeId.equals(s.modulationEnvelopeId())
                || s.commands().stream().anyMatch(event->event.command().asset()!=NativeCommand.Asset.VOICE && envelopeId.equals(event.assetId()))))
            throw new IllegalArgumentException("Envelope '"+envelopeId+"' is referenced by a source");
        return changeEnvelope(p,envelopeId,old->null);
    }

    private static StudioProject changeVoice(StudioProject p,String id,UnaryOperator<Voice> change) {
        ProjectFormat.validate(p);List<Voice> voices=new ArrayList<>();boolean found=false;
        for(Voice voice:p.voices()) {
            if(voice.id().equals(id)) { found=true;voice=change.apply(voice); }
            if(voice!=null) voices.add(voice);
        }
        if(!found) throw new IllegalArgumentException("Unknown voice '"+id+"'");
        return withAssets(p,p.arrangementVersion(),voices,p.envelopes());
    }
    private static StudioProject changeEnvelope(StudioProject p,String id,UnaryOperator<Envelope> change) {
        ProjectFormat.validate(p);List<Envelope> envelopes=new ArrayList<>();boolean found=false;
        for(Envelope envelope:p.envelopes()) {
            if(envelope.id().equals(id)) { found=true;envelope=change.apply(envelope); }
            if(envelope!=null) envelopes.add(envelope);
        }
        if(!found) throw new IllegalArgumentException("Unknown envelope '"+id+"'");
        return withAssets(p,3,p.voices(),envelopes);
    }
    private static StudioProject withAssets(StudioProject p,int version,List<Voice> voices,List<Envelope> envelopes) {
        return checked(p.withArrangement(Math.max(p.arrangementVersion(),version),voices,p.sequences(),p.clips(),envelopes,p.samples()));
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
    private static int commandVersion(CommandEvent event) {
        return event.command()==null ? 2 : event.command().minimumVersion();
    }
    private static StudioProject changeCommand(StudioProject p,String sequenceId,String commandId,UnaryOperator<CommandEvent> change,int minimumVersion) {
        return changeSequence(p,sequenceId,s->{ List<CommandEvent> commands=new ArrayList<>(); boolean found=false;
            for(CommandEvent event:s.commands()) { if(event.id().equals(commandId)) { found=true; event=change.apply(event); } if(event!=null) commands.add(event); }
            if(!found) throw new IllegalArgumentException("Unknown command '"+commandId+"' in sequence '"+sequenceId+"'"); return withCommands(s,commands);
        },minimumVersion);
    }
    private static StudioProject changeSequence(StudioProject p,String id,UnaryOperator<Sequence> change) {
        return changeSequence(p,id,change,false);
    }
    private static StudioProject changeSequence(StudioProject p,String id,UnaryOperator<Sequence> change,boolean upgrade) {
        return changeSequence(p,id,change,upgrade?2:1);
    }
    private static StudioProject changeSequence(StudioProject p,String id,UnaryOperator<Sequence> change,int minimumVersion) {
        ProjectFormat.validate(p); List<Sequence> sequences=new ArrayList<>(); boolean found=false, changed=false;
        for(Sequence s : p.sequences()) { if(s.id().equals(id)) { found=true; Sequence next=change.apply(s); changed=!next.equals(s); s=next; } sequences.add(s); }
        if(!found) throw new IllegalArgumentException("Unknown sequence '"+id+"'");
        return checked(p.withArrangement(changed?Math.max(minimumVersion,p.arrangementVersion()):p.arrangementVersion(),p.voices(),sequences,p.clips(),p.envelopes(),p.samples()));
    }
    private static Sequence withNotes(Sequence s,List<NoteEvent> notes) { return new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),notes,s.commands(),s.repeatState(),s.psgEnvelopeId(),s.modulationEnvelopeId(),s.triggers()); }
    private static Sequence withCommands(Sequence s,List<CommandEvent> commands) { return new Sequence(s.id(),s.name(),s.type(),s.lengthTicks(),s.voiceId(),s.volume(),s.notes(),commands,s.repeatState(),s.psgEnvelopeId(),s.modulationEnvelopeId(),s.triggers()); }
    private static StudioProject withClips(StudioProject p,List<Clip> clips) { return checked(p.withArrangement(p.arrangementVersion(),p.voices(),p.sequences(),clips,p.envelopes(),p.samples())); }
    private static StudioProject checked(StudioProject p) { ProjectFormat.validate(p); return p; }
}
