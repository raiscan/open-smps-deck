package com.opensmpsdeck.project;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compiles editable arrangements to finite, bank-relative S3K music. */
public final class ProjectCompiler {
    public static final int MAX_BANK_BYTES=0x8000;
    // Native S3K dialect: transpose-set is biased by 0x40, absolute volume is E4.
    private static final int REST=0x80;
    private ProjectCompiler() {}

    public enum Encoding { LINEAR, COMPACT }

    public static CompiledProject compileWithAssets(StudioProject project) {
        return compileWithAssets(project, Encoding.COMPACT);
    }

    public static CompiledProject compileWithAssets(StudioProject project, Encoding encoding) {
        return new CompiledProject(compile(project, encoding),project.envelopes(),project.samples(),
                project.nativeProgram(),project.nativeProgram()==null?List.of():project.voices());
    }

    public record BankLayout(byte[] bytes, List<Integer> relocations) {
        public BankLayout { bytes = bytes.clone(); relocations = List.copyOf(relocations); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public static BankLayout compileArrangementLayout(StudioProject project) {
        if (project.nativeProgram() != null) throw new IllegalArgumentException("Expected an editable arrangement");
        var relocations = new java.util.TreeSet<Integer>();
        byte[] bytes = compile(project, Encoding.COMPACT, relocations);
        return new BankLayout(bytes, List.copyOf(relocations));
    }

    public static byte[] compile(StudioProject project) {
        return compile(project, Encoding.COMPACT);
    }

    public static byte[] compile(StudioProject project, Encoding encoding) {
        return compile(project, encoding, null);
    }

    private static byte[] compile(StudioProject project, Encoding encoding, java.util.Set<Integer> relocations) {
        List<Clip> clips = ProjectFormat.validatedClips(project);
        if (project.nativeProgram()!=null) return project.nativeProgram().bank();
        if (project.formatVersion() == 6) {
            project = new StudioProject(project.arrangementVersion(), project.name(), project.mode(),
                    project.ticksPerBeat(), project.tempo(), project.voices(), project.sequences(), clips,
                    project.envelopes(), project.samples());
        }
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
        Bank out=new Bank(relocations); for(int i=0;i<header;i++) out.put(0);
        out.set(2,fm); out.set(3,psg); out.set(4,1); out.set(5,project.tempo());
        boolean compact = encoding == Encoding.COMPACT;
        dacLane(out,project,sources,compact); // DAC must always be the first positional slot.
        for(int channel=1;channel<fm;channel++) lane(out,project,"FM"+channel,6+4*channel,sources,notes,voices,psgEnvelopes,modulationEnvelopes,compact);
        for(int channel=1;channel<=psg;channel++) lane(out,project,"PSG"+channel,6+4*fm+6*(channel-1),sources,notes,voices,psgEnvelopes,modulationEnvelopes,compact);
        if(!project.voices().isEmpty()) {
            out.word(0,out.size());
            for(Voice v : project.voices()) for(int b : v.bytes()) out.put(b);
        }
        if (out.size() > MAX_BANK_BYTES) throw new BankLimitException(out.size(), encoding);
        return out.bytes();
    }

    /** Exclusive DAC stop tick, including the final surviving trigger's playback tail. */
    public static long dacEndTick(StudioProject project) {
        var clips = ProjectFormat.validatedClips(project);
        Map<String,Sequence> sources=new HashMap<>();
        for(Sequence source : project.sequences()) sources.put(source.id(),source);
        return dacEndTick(project,sources,dacClips(clips));
    }

    private static List<Clip> dacClips(List<Clip> clips) {
        return clips.stream().filter(c->c.lane().equals("DAC"))
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

    private static void dacLane(Bank bank,StudioProject project,Map<String,Sequence> sources,boolean compact) {
        bank.word(6,bank.size());
        var out = new TrackCompactor(bank, compact);
        List<Clip> clips=dacClips(project.clips());
        Map<String,Integer> sampleNotes=new HashMap<>();
        for(DacSample sample : project.samples()) sampleNotes.put(sample.id(),DacSample.FIRST_NOTE+sampleNotes.size());
        int cursor=0;
        for(Clip clip : clips) {
            rest(out,clip.startTick()-cursor);
            Sequence source=sources.get(clip.sequenceId());
            var triggers=source.triggers().stream().sorted(Comparator.comparingInt(SampleTrigger::tick)).toList();
            if(triggers.isEmpty()) {
                rest(out,clip.lengthTicks());
            } else {
                int cycle = source.lengthTicks();
                out.repeat(clip.lengthTicks() / cycle, () -> dacUnits(out, triggers, sampleNotes, cycle));
                int tail = clip.lengthTicks() % cycle;
                if (tail > 0) dacUnits(out, triggers, sampleNotes, tail);
            }
            cursor=clip.startTick()+clip.lengthTicks();
        }
        rest(out,dacEndTick(project,sources,clips)-cursor);
        out.finish();
    }

    private static void dacUnits(TrackCompactor out, List<SampleTrigger> triggers,
                                 Map<String,Integer> sampleNotes, int length) {
        int at=0;
        for(int i=0;i<triggers.size();i++) {
            SampleTrigger trigger=triggers.get(i);
            if(trigger.tick()>=length) break;
            rest(out,trigger.tick()-at);
            int next=i+1<triggers.size()?Math.min(length,triggers.get(i+1).tick()):length;
            int duration=Math.min(127,next-trigger.tick());
            out.event(sampleNotes.get(trigger.sampleId()),duration);
            at=trigger.tick()+duration;
        }
        rest(out,length-at);
    }

    private static void lane(Bank bank,StudioProject project,String lane,int pointer,
                             Map<String,Sequence> sources,Map<String,List<NoteEvent>> notes,Map<String,Integer> voices,
                             Map<String,Integer> psgEnvelopes,Map<String,Integer> modulationEnvelopes,boolean compact) {
        bank.word(pointer,bank.size());
        var out = new TrackCompactor(bank, compact);
        List<Clip> clips=project.clips().stream().filter(c->c.lane().equals(lane)).sorted(Comparator.comparingInt(Clip::startTick)).toList();
        int cursor=0, version=project.formatVersion();
        var activeEffects=EnumSet.noneOf(NativeCommand.class);
        for(Clip clip : clips) {
            rest(out,clip.startTick()-cursor);
            Sequence source=sources.get(clip.sequenceId());
            List<NoteEvent> sourceNotes = notes.get(source.id());
            List<CommandEvent> commands=source.commands().stream().sorted(Comparator.comparingInt(CommandEvent::tick)).toList();
            int cycle = source.lengthTicks();
            int first = Math.min(clip.lengthTicks(), cycle);
            boolean resetRepeats = version == 1 || source.repeatState().equals("RESET");
            resetIteration(out,source,voices,version,psgEnvelopes,modulationEnvelopes,activeEffects);
            toneUnits(out,source,sourceNotes,commands,first,version,voices,psgEnvelopes,modulationEnvelopes,activeEffects);

            int remaining = clip.lengthTicks() - first;
            int repeats = remaining / cycle;
            out.repeat(repeats, () -> {
                if (resetRepeats) resetIteration(out,source,voices,version,psgEnvelopes,modulationEnvelopes,activeEffects);
                toneUnits(out,source,sourceNotes,commands,cycle,version,voices,psgEnvelopes,modulationEnvelopes,activeEffects);
            });
            int tail = remaining % cycle;
            if (tail > 0) {
                if (resetRepeats) resetIteration(out,source,voices,version,psgEnvelopes,modulationEnvelopes,activeEffects);
                toneUnits(out,source,sourceNotes,commands,tail,version,voices,psgEnvelopes,modulationEnvelopes,activeEffects);
            }
            cursor=clip.startTick()+clip.lengthTicks();
        }
        out.finish();
    }

    private static void resetIteration(TrackCompactor out,Sequence source,Map<String,Integer> voices,int version,
                                       Map<String,Integer> psgEnvelopes,Map<String,Integer> modulationEnvelopes,
                                       EnumSet<NativeCommand> activeEffects) {
        if(activeEffects.remove(NativeCommand.PSG_NOISE)) {
            out.put(0xF3); out.put(0);
        }
        if(activeEffects.remove(NativeCommand.FM_VOLUME_ENVELOPE)) {
            out.put(0xFF); out.put(0x06); out.put(0); out.put(0);
        }
        reset(out,source,voices,version,psgEnvelopes,modulationEnvelopes);
    }

    private static void toneUnits(TrackCompactor out, Sequence source, List<NoteEvent> notes, List<CommandEvent> commands,
                                  int length, int version, Map<String,Integer> voices, Map<String,Integer> psgEnvelopes,
                                  Map<String,Integer> modulationEnvelopes, EnumSet<NativeCommand> activeEffects) {
        if (version >= 2) {
            units(out,source,notes,commands,length,voices,psgEnvelopes,modulationEnvelopes,activeEffects);
            return;
        }
        int at=0;
        for(NoteEvent note : notes) {
            if(note.tick()>=length) break;
            rest(out,note.tick()-at);
            int duration=Math.min(note.duration(),length-note.tick());
            note(out,source.type(),note.pitch(),duration);
            at=note.tick()+duration;
        }
        rest(out,length-at);
    }

    private static void reset(TrackCompactor out,Sequence source,Map<String,Integer> voices,int version,
                              Map<String,Integer> psgEnvelopes,Map<String,Integer> modulationEnvelopes) {
        if(source.type().equals("FM")) { out.put(SmpsCoordFlags.SET_VOICE); out.put(voices.get(source.voiceId())); }
        out.put(SmpsCoordFlags.ABSOLUTE_VOLUME); out.put(source.volume());
        out.state(NativeCommand.VOLUME, source.volume());
        if(source.type().equals("FM")) { out.put(0xE0); out.put(0xC0); }
        out.put(0xED); out.put(0x40);
        out.state(NativeCommand.TRANSPOSE, 0x40);
        if(version>=2) { out.put(SmpsCoordFlags.DETUNE); out.put(0); out.put(0xE8); out.put(0); out.put(SmpsCoordFlags.MODULATION_OFF); }
        if(version>=3) {
            if(source.type().equals("PSG")) { out.put(SmpsCoordFlags.PSG_INSTRUMENT);out.put(source.psgEnvelopeId()==null?0:psgEnvelopes.get(source.psgEnvelopeId())); }
            out.put(SmpsCoordFlags.MODULATION_ENVELOPE);out.put(source.modulationEnvelopeId()==null?0:modulationEnvelopes.get(source.modulationEnvelopeId()));
        }
    }
    private static Map<String,Integer> envelopeIndices(List<Envelope> envelopes,String type) {
        Map<String,Integer> indices=new HashMap<>();
        for(Envelope envelope:envelopes) if(type.equals(envelope.type())) indices.put(envelope.id(),indices.size()+1);
        return indices;
    }
    /** Merge only source events, streaming bounded time units straight into the bank. */
    private static void units(TrackCompactor out,Sequence source,List<NoteEvent> notes,List<CommandEvent> commands,int length,
                              Map<String,Integer> voices,Map<String,Integer> psgEnvelopes,
                              Map<String,Integer> modulationEnvelopes,EnumSet<NativeCommand> activeEffects) {
        int at=0, noteIndex=0, commandIndex=0;
        while(at<length) {
            while(noteIndex<notes.size() && notes.get(noteIndex).tick()+notes.get(noteIndex).duration()<=at) noteIndex++;
            NoteEvent note=noteIndex<notes.size() ? notes.get(noteIndex) : null;
            boolean sounding=note!=null && note.tick()<=at;
            while(commandIndex<commands.size() && commands.get(commandIndex).tick()==at) {
                CommandEvent event=commands.get(commandIndex++);
                NativeCommand command=event.command();
                out.state(command, event.arguments().isEmpty() ? 0 : event.arguments().getFirst());
                out.put(command.opcode());
                if(command.subOpcode()>=0) out.put(command.subOpcode());
                String asset=event.assetId();
                switch(command.asset()) {
                    case NONE -> { }
                    case VOICE -> {
                        int index=voices.get(asset);
                        if(index>=128) throw new IllegalArgumentException("Command '"+event.id()+"': banked voice references need future asset support");
                        out.put(index);
                    }
                    case PSG_VOLUME -> out.put(asset==null ? 0 : psgEnvelopes.get(asset));
                    case MODULATION -> out.put(asset==null ? 0 : modulationEnvelopes.get(asset));
                }
                for(int argument:event.arguments()) out.put(argument);
                switch(command) {
                    case PSG_NOISE -> {
                        if(event.arguments().getFirst()==0) activeEffects.remove(command);
                        else activeEffects.add(command);
                    }
                    case FM_VOLUME_ENVELOPE -> {
                        if(asset==null || (event.arguments().getFirst() & 0x0F)==0) activeEffects.remove(command);
                        else activeEffects.add(command);
                    }
                    default -> { }
                }
                if(event.command()==NativeCommand.MODULATION_OFF && source.modulationEnvelopeId()!=null) {
                    // Retail FA clears custom modulation's bit. Clear the
                    // selected envelope too for the arrangement command.
                    out.put(SmpsCoordFlags.MODULATION_ENVELOPE); out.put(0);
                }
            }
            int next=length;
            if(note!=null) next=Math.min(next,sounding ? note.tick()+note.duration() : note.tick());
            if(commandIndex<commands.size()) next=Math.min(next,commands.get(commandIndex).tick());
            int duration = next - at;
            if(!sounding) rest(out,duration);
            else {
                if(note.tick()==at) {
                    int attack = Math.min(127,duration);
                    out.event(encodedPitch(source.type(),note.pitch()),attack);
                    duration -= attack;
                }
                held(out,-1,duration);
            }
            at=next;
        }
    }

    private static void rest(TrackCompactor out,long ticks) {
        if (ticks >= 127) {
            out.event(REST,127); // Includes any commands queued for this rest.
            out.repeat((int)(ticks / 127) - 1, () -> out.event(REST,127));
        }
        if (ticks % 127 > 0) out.event(REST,(int)(ticks % 127));
    }

    private static void note(TrackCompactor out,String type,int pitch,int ticks) {
        // FM byte 81 is C0 (MIDI12), but S3K's PSG table starts at C2 (MIDI36).
        int encoded=encodedPitch(type,pitch);
        int attack = Math.min(127,ticks);
        out.event(encoded,attack);
        held(out,encoded,ticks-attack);
    }

    private static void held(TrackCompactor out,int note,int ticks) {
        if (ticks <= 0) return;
        // Flush any commands waiting for this first continuation before bulk writing.
        int first = Math.min(127,ticks);
        out.heldEvent(note,first);
        int remaining = ticks - first;
        out.repeat(remaining / 127, () -> out.heldEvent(note,127));
        if (remaining % 127 > 0) out.heldEvent(note,remaining % 127);
    }

    private static int encodedPitch(String type,int pitch) { return 0x81+pitch-(type.equals("PSG")?36:12); }

    public static final class BankLimitException extends IllegalArgumentException {
        private final long compiledBytes;

        private BankLimitException(long compiledBytes, Encoding encoding) {
            super("Compiled project is " + compiledBytes + " bytes. The 32 KiB S3K bank limit is 32,768 bytes. "
                    + (encoding == Encoding.COMPACT ? "Loops and calls were already applied. " : "Linear encoding was requested. ")
                    + "Shorten the song or reduce layering, voices, or notes.");
            this.compiledBytes = compiledBytes;
        }

        /** Classic compilers report the first attempted write beyond the bank. */
        public BankLimitException(long compiledBytes, TargetGame target) {
            super(target.id() + ": compiled arrangement exceeds one 32 KiB bank (attempted "
                    + compiledBytes + " bytes). Shorten the song or reduce layering, voices, or notes.");
            this.compiledBytes = compiledBytes;
        }

        public long compiledBytes() { return compiledBytes; }
        public int limitBytes() { return MAX_BANK_BYTES; }

        /** ProjectFiles wraps compile errors in an IOException while loading or saving. */
        public static BankLimitException from(Throwable failure) {
            if (failure instanceof BankLimitException limit) return limit;
            return failure.getCause() instanceof BankLimitException limit ? limit : null;
        }
    }

    /** Retain writable bytes up to the bank limit and count the full output past it. */
    static final class Bank {
        private final java.util.Set<Integer> relocations;
        private byte[] data = new byte[64];
        private long size;

        Bank() { this(null); }

        /** Records the offset of every bank pointer written with {@link #word}. */
        Bank(java.util.Set<Integer> relocations) { this.relocations = relocations; }

        long size() { return size; }

        void put(int value) { set(size++,value); }

        void putBytes(byte[] bytes) { append(bytes,bytes.length); }

        void append(Bank source) { append(source.data,source.size); }

        private void append(byte[] bytes,long length) {
            int written = (int)Math.max(0,Math.min(length,MAX_BANK_BYTES-size));
            if (written > 0) {
                grow((int)size + written);
                System.arraycopy(bytes,0,data,(int)size,written);
            }
            size += length;
        }

        // Bulk counting also uses this for output that cannot fit even in part.
        void skip(long length) { size += length; }

        void set(long at,int value) {
            if (at >= MAX_BANK_BYTES) return;
            grow((int)at + 1);
            data[(int)at]=(byte)value;
        }

        void word(long at,long value) {
            if (relocations != null && at < MAX_BANK_BYTES) relocations.add((int)at);
            set(at,(int)value);
            set(at+1,(int)(value>>>8));
        }

        void word(long value) {
            word(size, value);
            size += 2;
        }

        private void grow(int required) {
            if (required > data.length) data = Arrays.copyOf(data,Math.max(required,data.length * 2));
        }

        byte[] bytes() { return Arrays.copyOf(data,(int)size); }
    }
}
