package com.opensmpsdeck.project;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Finite linear S3K compiler. Reuse remains linked in source and is baked inline here. */
public final class ProjectCompiler {
    public static final int MAX_BANK_BYTES=0x8000;
    // Native S3K dialect: transpose-set is biased by 0x40, absolute volume is E4.
    private static final int REST=0x80, STOP=0xF2, HOLD=0xE7;
    private ProjectCompiler() {}

    public static CompiledProject compileWithAssets(StudioProject project) {
        return new CompiledProject(compile(project),project.envelopes(),project.samples(),
                project.nativeProgram(),project.nativeProgram()==null?List.of():project.voices());
    }

    public static byte[] compile(StudioProject project) {
        ProjectFormat.validate(project);
        if (project.nativeProgram()!=null) return project.nativeProgram().bank();
        Map<String,Sequence> sources=new HashMap<>(); Map<String,List<NoteEvent>> notes=new HashMap<>();
        for (Sequence s : project.sequences()) { sources.put(s.id(),s); notes.put(s.id(),s.notes().stream().sorted(Comparator.comparingInt(NoteEvent::tick)).toList()); }
        Map<String,Integer> voices=new HashMap<>(); for(int i=0;i<project.voices().size();i++) voices.put(project.voices().get(i).id(),i);
        if(project.formatVersion()>=2) for(Clip clip:project.clips()) {
            Sequence source=sources.get(clip.sequenceId());
            if(source.type().equals("FM") && voices.get(source.voiceId())>=128)
                throw new IllegalArgumentException("Sequence '"+source.id()+"': banked voice references need future asset support (local voice index "+voices.get(source.voiceId())+")");
        }
        Map<String,Integer> psgEnvelopes=envelopeIndices(project.envelopes(),Envelope.PSG_VOLUME);
        Map<String,Integer> modulationEnvelopes=envelopeIndices(project.envelopes(),Envelope.MODULATION);
        int fm=1, psg=0;
        for(Clip c : project.clips()) {
            if(c.lane().startsWith("FM")) fm=Math.max(fm,1+Integer.parseInt(c.lane().substring(2)));
            else if(c.lane().startsWith("PSG")) psg=Math.max(psg,Integer.parseInt(c.lane().substring(3)));
        }
        int header=6+4*fm+6*psg;
        Bank out=new Bank(); for(int i=0;i<header;i++) out.put(0);
        out.set(2,fm); out.set(3,psg); out.set(4,1); out.set(5,project.tempo());
        dacLane(out,project,sources); // DAC must always be the first positional slot.
        for(int channel=1;channel<fm;channel++) lane(out,project,"FM"+channel,6+4*channel,sources,notes,voices,psgEnvelopes,modulationEnvelopes);
        for(int channel=1;channel<=psg;channel++) lane(out,project,"PSG"+channel,6+4*fm+6*(channel-1),sources,notes,voices,psgEnvelopes,modulationEnvelopes);
        if(!project.voices().isEmpty()) {
            out.word(0,out.size());
            for(Voice v : project.voices()) for(int b : v.bytes()) out.put(b);
        }
        return out.bytes();
    }

    /** Exclusive DAC stop tick, including the final surviving trigger's playback tail. */
    public static long dacEndTick(StudioProject project) {
        ProjectFormat.validate(project);
        Map<String,Sequence> sources=new HashMap<>();
        for(Sequence source : project.sequences()) sources.put(source.id(),source);
        return dacEndTick(project,sources,dacClips(project));
    }

    private static List<Clip> dacClips(StudioProject project) {
        return project.clips().stream().filter(c->c.lane().equals("DAC"))
                .sorted(Comparator.comparingInt(Clip::startTick)).toList();
    }

    private static long dacEndTick(StudioProject project,Map<String,Sequence> sources,List<Clip> clips) {
        if(clips.isEmpty()) return 0;
        Clip last=clips.getLast();
        long end=(long)last.startTick()+last.lengthTicks();
        // Later attacks replace earlier samples. Walk back to the last clip with a surviving attack.
        for(Clip clip : clips.reversed()) {
            Sequence source=sources.get(clip.sequenceId());
            long finalOffset=-1;
            SampleTrigger finalTrigger=null;
            for(SampleTrigger trigger : source.triggers()) {
                long available=(long)clip.lengthTicks()-1-trigger.tick();
                if(available<0) continue;
                long offset=available/source.lengthTicks()*source.lengthTicks()+trigger.tick();
                if(offset>finalOffset) {
                    finalOffset=offset;
                    finalTrigger=trigger;
                }
            }
            if(finalTrigger!=null) {
                String sampleId=finalTrigger.sampleId();
                DacSample sample=project.samples().stream().filter(s->s.id().equals(sampleId)).findFirst().orElseThrow();
                long cyclesPerByte=ceilDivide(5L*DacSample.periodCycles(sample.rate()),28)+37;
                long tailFrames=ceilDivide((sample.pcm().length+1L)*cyclesPerByte,21334)+2;
                long tailTicks=ceilDivide(tailFrames*(256-project.tempo()),256)+2;
                return Math.max(end,clip.startTick()+finalOffset+tailTicks);
            }
        }
        return end;
    }

    private static long ceilDivide(long numerator,long divisor) {
        return (numerator+divisor-1)/divisor;
    }

    private static void dacLane(Bank out,StudioProject project,Map<String,Sequence> sources) {
        out.word(6,out.size());
        List<Clip> clips=dacClips(project);
        Map<String,Integer> sampleNotes=new HashMap<>();
        for(DacSample sample : project.samples()) sampleNotes.put(sample.id(),DacSample.FIRST_NOTE+sampleNotes.size());
        int cursor=0;
        for(Clip clip : clips) {
            rest(out,clip.startTick()-cursor);
            Sequence source=sources.get(clip.sequenceId());
            var triggers=source.triggers().stream().sorted(Comparator.comparingInt(SampleTrigger::tick)).toList();
            if(triggers.isEmpty()) {
                rest(out,clip.lengthTicks());
                cursor=clip.startTick()+clip.lengthTicks();
                continue;
            }
            int remaining=clip.lengthTicks();
            while(remaining>0) {
                int length=Math.min(remaining,source.lengthTicks());
                int at=0;
                for(int i=0;i<triggers.size();i++) {
                    SampleTrigger trigger=triggers.get(i);
                    if(trigger.tick()>=length) break;
                    rest(out,trigger.tick()-at);
                    int next=i+1<triggers.size()?Math.min(length,triggers.get(i+1).tick()):length;
                    int duration=Math.min(127,next-trigger.tick());
                    out.put(sampleNotes.get(trigger.sampleId()));
                    out.put(duration);
                    at=trigger.tick()+duration;
                }
                rest(out,length-at);
                remaining-=length;
            }
            cursor=clip.startTick()+clip.lengthTicks();
        }
        rest(out,dacEndTick(project,sources,clips)-cursor);
        out.put(STOP);
    }

    private static void lane(Bank out,StudioProject project,String lane,int pointer,
                             Map<String,Sequence> sources,Map<String,List<NoteEvent>> notes,Map<String,Integer> voices,
                             Map<String,Integer> psgEnvelopes,Map<String,Integer> modulationEnvelopes) {
        out.word(pointer,out.size());
        List<Clip> clips=project.clips().stream().filter(c->c.lane().equals(lane)).sorted(Comparator.comparingInt(Clip::startTick)).toList();
        int cursor=0;
        for(Clip clip : clips) {
            rest(out,clip.startTick()-cursor);
            Sequence source=sources.get(clip.sequenceId());
            int remaining=clip.lengthTicks();
            boolean first=true;
            List<CommandEvent> commands=source.commands().stream().sorted(Comparator.comparingInt(CommandEvent::tick)).toList();
            while(remaining>0) {
                int length=Math.min(remaining,source.lengthTicks());
                if(first || project.formatVersion()==1 || source.repeatState().equals("RESET")) reset(out,source,voices,project.formatVersion(),psgEnvelopes,modulationEnvelopes);
                if(project.formatVersion()==1) {
                    int at=0;
                    for(NoteEvent note : notes.get(source.id())) {
                        if(note.tick()>=length) break;
                        rest(out,note.tick()-at);
                        int duration=Math.min(note.duration(),length-note.tick());
                        note(out,source.type(),note.pitch(),duration);
                        at=note.tick()+duration;
                    }
                    rest(out,length-at);
                } else {
                    units(out,source,notes.get(source.id()),commands,length);
                }
                remaining-=length;
                first=false;
            }
            cursor=clip.startTick()+clip.lengthTicks();
        }
        out.put(STOP);
    }

    private static void reset(Bank out,Sequence source,Map<String,Integer> voices,int version,
                              Map<String,Integer> psgEnvelopes,Map<String,Integer> modulationEnvelopes) {
        if(source.type().equals("FM")) { out.put(0xEF); out.put(voices.get(source.voiceId())); }
        out.put(0xE4); out.put(source.volume());
        if(source.type().equals("FM")) { out.put(0xE0); out.put(0xC0); }
        out.put(0xED); out.put(0x40);
        if(version>=2) { out.put(0xE1); out.put(0); out.put(0xE8); out.put(0); out.put(0xFA); }
        if(version>=3) {
            if(source.type().equals("PSG")) { out.put(0xF5);out.put(source.psgEnvelopeId()==null?0:psgEnvelopes.get(source.psgEnvelopeId())); }
            out.put(0xF4);out.put(source.modulationEnvelopeId()==null?0:modulationEnvelopes.get(source.modulationEnvelopeId()));
        }
    }
    private static Map<String,Integer> envelopeIndices(List<Envelope> envelopes,String type) {
        Map<String,Integer> indices=new HashMap<>();
        for(Envelope envelope:envelopes) if(type.equals(envelope.type())) indices.put(envelope.id(),indices.size()+1);
        return indices;
    }
    /** Merge only source events, streaming bounded time units straight into the bank. */
    private static void units(Bank out,Sequence source,List<NoteEvent> notes,List<CommandEvent> commands,int length) {
        int at=0, noteIndex=0, commandIndex=0;
        while(at<length) {
            while(noteIndex<notes.size() && notes.get(noteIndex).tick()+notes.get(noteIndex).duration()<=at) noteIndex++;
            NoteEvent note=noteIndex<notes.size() ? notes.get(noteIndex) : null;
            boolean sounding=note!=null && note.tick()<=at;
            while(commandIndex<commands.size() && commands.get(commandIndex).tick()==at) {
                CommandEvent event=commands.get(commandIndex++);
                out.put(event.command().opcode()); for(int argument:event.arguments()) out.put(argument);
                if(event.command()==NativeCommand.MODULATION_OFF && source.modulationEnvelopeId()!=null) {
                    // Retail FA clears custom modulation's bit. Clear the
                    // selected envelope too for the arrangement command.
                    out.put(0xF4); out.put(0);
                }
            }
            int next=at+(int)Math.min(127L,(long)length-at);
            if(note!=null) next=Math.min(next,sounding ? note.tick()+note.duration() : note.tick());
            if(commandIndex<commands.size()) next=Math.min(next,commands.get(commandIndex).tick());
            if(sounding) {
                if(note.tick()==at) out.put(encodedPitch(source.type(),note.pitch()));
                else out.put(HOLD);
            } else out.put(REST);
            out.put(next-at);
            at=next;
        }
    }
    private static void rest(Bank out,long ticks) {
        while(ticks>0) { int duration=(int)Math.min(ticks,127); out.put(REST); out.put(duration); ticks-=duration; }
    }
    private static void note(Bank out,String type,int pitch,int ticks) {
        // FM byte 81 is C0 (MIDI12), but S3K's PSG table starts at C2
        // (MIDI36). Sharing the FM bias raises every PSG note two octaves.
        int encoded=encodedPitch(type,pitch); boolean continuation=false;
        while(ticks>0) { int duration=Math.min(ticks,127); if(continuation) out.put(HOLD); out.put(encoded); out.put(duration); ticks-=duration; continuation=true; }
    }
    private static int encodedPitch(String type,int pitch) { return 0x81+pitch-(type.equals("PSG")?36:12); }

    /** Fixed-size writer bounds work even for billion-tick input; no expanded event list. */
    private static final class Bank {
        private final byte[] data=new byte[MAX_BANK_BYTES]; private int size;
        int size() { return size; }
        void put(int value) { if(size==data.length) throw new IllegalArgumentException("Compiled project exceeds one 32 KiB S3K bank; shorten clips or reduce notes/voices"); data[size++]=(byte)value; }
        void set(int at,int value) { data[at]=(byte)value; }
        void word(int at,int value) {
            if(value<0 || value>=MAX_BANK_BYTES) throw new IllegalArgumentException("Compiled project pointer exceeds one 32 KiB S3K bank");
            set(at,value); set(at+1,value>>>8);
        }
        byte[] bytes() { return Arrays.copyOf(data,size); }
    }
}
