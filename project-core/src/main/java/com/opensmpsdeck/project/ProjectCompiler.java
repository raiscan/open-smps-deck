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

    public static byte[] compile(StudioProject project) {
        ProjectFormat.validate(project);
        Map<String,Sequence> sources=new HashMap<>(); Map<String,List<NoteEvent>> notes=new HashMap<>();
        for (Sequence s : project.sequences()) { sources.put(s.id(),s); notes.put(s.id(),s.notes().stream().sorted(Comparator.comparingInt(NoteEvent::tick)).toList()); }
        Map<String,Integer> voices=new HashMap<>(); for(int i=0;i<project.voices().size();i++) voices.put(project.voices().get(i).id(),i);
        int fm=1, psg=0;
        for(Clip c : project.clips()) {
            if(c.lane().startsWith("FM")) fm=Math.max(fm,1+Integer.parseInt(c.lane().substring(2)));
            else psg=Math.max(psg,Integer.parseInt(c.lane().substring(3)));
        }
        int header=6+4*fm+6*psg;
        Bank out=new Bank(); for(int i=0;i<header;i++) out.put(0);
        out.set(2,fm); out.set(3,psg); out.set(4,1); out.set(5,project.tempo());
        out.word(6,out.size()); out.put(STOP); // DAC must always be the first positional slot.
        for(int channel=1;channel<fm;channel++) lane(out,project,"FM"+channel,6+4*channel,sources,notes,voices);
        for(int channel=1;channel<=psg;channel++) lane(out,project,"PSG"+channel,6+4*fm+6*(channel-1),sources,notes,voices);
        if(!project.voices().isEmpty()) {
            out.word(0,out.size());
            for(Voice v : project.voices()) for(int b : v.bytes()) out.put(b);
        }
        return out.bytes();
    }

    private static void lane(Bank out,StudioProject project,String lane,int pointer,
                             Map<String,Sequence> sources,Map<String,List<NoteEvent>> notes,Map<String,Integer> voices) {
        out.word(pointer,out.size());
        List<Clip> clips=project.clips().stream().filter(c->c.lane().equals(lane)).sorted(Comparator.comparingInt(Clip::startTick)).toList();
        int cursor=0;
        for(Clip clip : clips) {
            rest(out,clip.startTick()-cursor);
            Sequence source=sources.get(clip.sequenceId());
            int remaining=clip.lengthTicks();
            while(remaining>0) {
                int length=Math.min(remaining,source.lengthTicks());
                reset(out,source,voices);
                int at=0;
                for(NoteEvent note : notes.get(source.id())) {
                    if(note.tick()>=length) break;
                    rest(out,note.tick()-at);
                    int duration=Math.min(note.duration(),length-note.tick());
                    note(out,source.type(),note.pitch(),duration);
                    at=note.tick()+duration;
                }
                rest(out,length-at);
                remaining-=length;
            }
            cursor=clip.startTick()+clip.lengthTicks();
        }
        out.put(STOP);
    }

    private static void reset(Bank out,Sequence source,Map<String,Integer> voices) {
        if(source.type().equals("FM")) { out.put(0xEF); out.put(voices.get(source.voiceId())); }
        out.put(0xE4); out.put(source.volume());
        if(source.type().equals("FM")) { out.put(0xE0); out.put(0xC0); }
        out.put(0xED); out.put(0x40);
    }
    private static void rest(Bank out,int ticks) {
        while(ticks>0) { int duration=Math.min(ticks,127); out.put(REST); out.put(duration); ticks-=duration; }
    }
    private static void note(Bank out,String type,int pitch,int ticks) {
        // FM byte 81 is C0 (MIDI12), but S3K's PSG table starts at C2
        // (MIDI36). Sharing the FM bias raises every PSG note two octaves.
        int encoded=0x81+pitch-(type.equals("PSG")?36:12); boolean continuation=false;
        while(ticks>0) { int duration=Math.min(ticks,127); if(continuation) out.put(HOLD); out.put(encoded); out.put(duration); ticks-=duration; continuation=true; }
    }

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
