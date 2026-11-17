package com.opensmpsdeck.project;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Strict versioned source boundary. Unknown native features are rejected, never discarded. */
public final class ProjectFormat {
    public static final int MAX_SOURCE_CHARS = 1_048_576;
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private ProjectFormat() {}

    public static StudioProject read(String json) {
        if (json == null) throw invalid("project", "JSON text is required");
        if (json.length() > MAX_SOURCE_CHARS) throw invalid("project", "source exceeds 1 MiB character limit");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement tree = readValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("project", "unexpected content after document");
            if(!tree.isJsonObject()) throw invalid("project","must be an object");
            if(!tree.getAsJsonObject().has("formatVersion")) throw invalid("project.formatVersion","required field is missing");
            int version=integer(tree.getAsJsonObject(),"formatVersion");
            if(version<1 || version>5) throw invalid("formatVersion", "only versions 1 through 5 are supported");
            JsonObject o = version==5 ? object(tree, "project", "formatVersion", "name", "mode", "ticksPerBeat", "voices", "envelopes", "samples", "nativeProgram")
                    : version==4 ? object(tree, "project", "formatVersion", "name", "mode", "ticksPerBeat", "tempo", "voices", "sequences", "clips", "envelopes", "samples")
                    : version==3 ? object(tree, "project", "formatVersion", "name", "mode", "ticksPerBeat", "tempo", "voices", "sequences", "clips", "envelopes")
                    : object(tree, "project", "formatVersion", "name", "mode", "ticksPerBeat", "tempo", "voices", "sequences", "clips");
            NativeProgram nativeProgram = version==5 ? nativeProgram(o.get("nativeProgram")) : null;
            StudioProject project = new StudioProject(version,string(o,"name"),string(o,"mode"),integer(o,"ticksPerBeat"),nativeProgram==null?integer(o,"tempo"):nativeProgram.tempo(),
                    list(o,"voices",ProjectFormat::voice),
                    version==5?List.of():list(o,"sequences",e->sequence(e,version)),
                    version==5?List.of():list(o,"clips",ProjectFormat::clip),
                    version>=3?list(o,"envelopes",ProjectFormat::envelope):List.of(),
                    version>=4?list(o,"samples",ProjectFormat::sample):List.of(),nativeProgram);
            validate(project);
            return project;
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("Invalid project JSON: " + e.getMessage(), e);
        }
    }

    private static NativeProgram nativeProgram(JsonElement element) {
        JsonObject o = object(element,"nativeProgram","bankBase","headerOffset","localVoiceCount","previewFrames",
                "bankBase64","envelopeSlots","dacSlots");
        return new NativeProgram(integer(o,"bankBase"),integer(o,"headerOffset"),integer(o,"localVoiceCount"),
                integer(o,"previewFrames"),string(o,"bankBase64"),
                list(o,"envelopeSlots",entry -> {
                    JsonObject slot = object(entry,"envelope slot","assetId","slot");
                    return new NativeProgram.EnvelopeSlot(string(slot,"assetId"),integer(slot,"slot"));
                }),
                list(o,"dacSlots",entry -> {
                    JsonObject slot = object(entry,"DAC slot","note","sampleId","rate");
                    return new NativeProgram.DacSlot(integer(slot,"note"),string(slot,"sampleId"),integer(slot,"rate"));
                }));
    }

    public static String write(StudioProject project) {
        validate(project);
        JsonObject o = new JsonObject();
        o.addProperty("formatVersion",project.formatVersion()); o.addProperty("name",project.name()); o.addProperty("mode",project.mode());
        o.addProperty("ticksPerBeat",project.ticksPerBeat());
        if (project.nativeProgram()==null) o.addProperty("tempo",project.tempo());
        JsonArray voices = new JsonArray();
        for (Voice v : project.voices()) { JsonObject x=new JsonObject(); x.addProperty("id",v.id()); x.addProperty("name",v.name()); JsonArray b=new JsonArray(); v.bytes().forEach(b::add); x.add("bytes",b); voices.add(x); }
        o.add("voices",voices);
        JsonArray sequences = new JsonArray();
        for (Sequence s : project.sequences()) {
            JsonObject x=new JsonObject(); x.addProperty("id",s.id()); x.addProperty("name",s.name()); x.addProperty("type",s.type()); x.addProperty("lengthTicks",s.lengthTicks()); x.addProperty("voiceId",s.voiceId()); x.addProperty("volume",s.volume());
            JsonArray notes = new JsonArray();
            for (NoteEvent n : s.notes()) { JsonObject y=new JsonObject(); y.addProperty("id",n.id()); y.addProperty("tick",n.tick()); y.addProperty("duration",n.duration()); y.addProperty("pitch",n.pitch()); notes.add(y); }
            x.add("notes",notes);
            if(project.formatVersion()>=2) {
                JsonArray commands=new JsonArray();
                for(CommandEvent event:s.commands()) {
                    JsonObject y=new JsonObject(); y.addProperty("id",event.id()); y.addProperty("tick",event.tick()); y.addProperty("command",event.command().name());
                    JsonArray arguments=new JsonArray(); event.arguments().forEach(arguments::add); y.add("arguments",arguments); commands.add(y);
                }
                x.add("commands",commands); x.addProperty("repeatState",s.repeatState());
            }
            if(project.formatVersion()>=3) {
                x.addProperty("psgEnvelopeId",s.psgEnvelopeId());x.addProperty("modulationEnvelopeId",s.modulationEnvelopeId());
            }
            if(project.formatVersion()==4) {
                JsonArray triggers=new JsonArray();
                for(SampleTrigger trigger:s.triggers()) {
                    JsonObject y=new JsonObject();y.addProperty("id",trigger.id());y.addProperty("tick",trigger.tick());y.addProperty("sampleId",trigger.sampleId());triggers.add(y);
                }
                x.add("triggers",triggers);
            }
            sequences.add(x);
        }
        if (project.nativeProgram()==null) o.add("sequences",sequences);
        JsonArray clips = new JsonArray();
        for (Clip c : project.clips()) { JsonObject x=new JsonObject(); x.addProperty("id",c.id()); x.addProperty("sequenceId",c.sequenceId()); x.addProperty("lane",c.lane()); x.addProperty("startTick",c.startTick()); x.addProperty("lengthTicks",c.lengthTicks()); clips.add(x); }
        if (project.nativeProgram()==null) o.add("clips",clips);
        if(project.formatVersion()>=3) {
            JsonArray envelopes=new JsonArray();
            for(Envelope envelope:project.envelopes()) {
                JsonObject x=new JsonObject();x.addProperty("id",envelope.id());x.addProperty("name",envelope.name());x.addProperty("type",envelope.type());
                JsonArray bytes=new JsonArray();envelope.bytes().forEach(bytes::add);x.add("bytes",bytes);envelopes.add(x);
            }
            o.add("envelopes",envelopes);
        }
        if(project.formatVersion()>=4) {
            JsonArray samples=new JsonArray();
            for(DacSample sample:project.samples()) {
                JsonObject x=new JsonObject();x.addProperty("id",sample.id());x.addProperty("name",sample.name());x.addProperty("rate",sample.rate());x.addProperty("pcmBase64",sample.pcmBase64());samples.add(x);
            }
            o.add("samples",samples);
        }
        if (project.nativeProgram()!=null) o.add("nativeProgram",GSON.toJsonTree(project.nativeProgram()));
        return GSON.toJson(o) + "\n";
    }

    public static void validate(StudioProject project) {
        if (project == null) throw invalid("project", "document is required");
        if (project.formatVersion()<1 || project.formatVersion()>5) throw invalid("formatVersion", "only versions 1 through 5 are supported");
        text(project.name(),"name");
        if (!"S3K".equals(project.mode())) throw invalid("mode", "only S3K is supported");
        range(project.ticksPerBeat(),1,Integer.MAX_VALUE,"ticksPerBeat"); range(project.tempo(),0,255,"tempo");
        NativeProgram nativeProgram = project.nativeProgram();
        if ((project.formatVersion()==5) != (nativeProgram!=null))
            throw invalid("nativeProgram","required in formatVersion 5 and unavailable in earlier versions");
        if (nativeProgram!=null) {
            if (!project.sequences().isEmpty() || !project.clips().isEmpty())
                throw invalid("nativeProgram","native projects cannot contain sequences or clips");
            if (project.tempo()!=nativeProgram.tempo())
                throw invalid("tempo","must match the native header");
            if (project.voices().size()>128)
                throw invalid("voices","at most 128 global voices can be addressed");
        }
        if (project.voices().size()>256) throw invalid("voices", "at most 256 local voices can be addressed");
        Map<String,Voice> voices = unique(project.voices(), Voice::id, "voices");
        for (Voice v : project.voices()) {
            text(v.name(),"voice '"+v.id()+"'.name");
            if (v.bytes().size()!=25) throw invalid("voice '"+v.id()+"'.bytes", "exactly 25 native-order bytes are required");
            for (int b : v.bytes()) range(b,0,255,"voice '"+v.id()+"'.bytes");
        }
        if(project.formatVersion()<3 && !project.envelopes().isEmpty()) throw invalid("envelopes","envelope assets require formatVersion 3");
        Map<String,Envelope> envelopes=unique(project.envelopes(),Envelope::id,"envelopes");
        int psgCount=0,modulationCount=0;
        for(Envelope envelope:project.envelopes()) {
            String at="envelope '"+envelope.id()+"'";text(envelope.name(),at+".name");
            boolean psg = Envelope.PSG_VOLUME.equals(envelope.type());
            if (nativeProgram!=null) {
                if (!psg && !Envelope.MODULATION.equals(envelope.type()))
                    throw invalid(at+".type","must be PSG_VOLUME or MODULATION");
                range(envelope.bytes().size(),1,256,at+".bytes length");
                for (int value : envelope.bytes()) range(value,0,255,at+".bytes");
            } else {
                try { EnvelopeProgram.validate(envelope.type(),envelope.bytes()); }
                catch(IllegalArgumentException e) { throw invalid(at+".bytes",e.getMessage()); }
            }
            if (psg) psgCount++; else modulationCount++;
        }
        if(psgCount>255) throw invalid("envelopes","at most 255 PSG volume envelopes can be addressed");
        if(modulationCount>127) throw invalid("envelopes","at most 127 modulation envelopes can be addressed; index 128 is reserved for custom modulation");
        if(project.formatVersion()<4 && !project.samples().isEmpty()) throw invalid("samples","DAC samples require formatVersion 4");
        DacSample.validateAll(project.samples());
        Map<String,DacSample> samples=unique(project.samples(),DacSample::id,"samples");
        if (nativeProgram!=null) {
            Set<Integer> psgSlots = new HashSet<>();
            Set<Integer> modulationSlots = new HashSet<>();
            for (NativeProgram.EnvelopeSlot slot : nativeProgram.envelopeSlots()) {
                Envelope envelope = envelopes.get(slot.assetId());
                if (envelope==null) throw invalid("envelopeSlots","undefined envelope '"+slot.assetId()+"'");
                boolean psg = Envelope.PSG_VOLUME.equals(envelope.type());
                range(slot.slot(),1,psg?255:127,"envelope slot");
                if (!(psg?psgSlots:modulationSlots).add(slot.slot()))
                    throw invalid("envelopeSlots","duplicate "+envelope.type()+" slot "+slot.slot());
            }
            for (NativeProgram.DacSlot slot : nativeProgram.dacSlots()) {
                if (!samples.containsKey(slot.sampleId()))
                    throw invalid("dacSlots","undefined sample '"+slot.sampleId()+"'");
            }
        }
        Map<String,Sequence> sequences = unique(project.sequences(), Sequence::id, "sequences");
        for (Sequence s : project.sequences()) {
            String at="sequence '"+s.id()+"'"; text(s.name(),at+".name");
            if (!"FM".equals(s.type()) && !"PSG".equals(s.type()) && !"DAC".equals(s.type())) throw invalid(at+".type","use FM, PSG or DAC; groups and raw commands are not supported");
            if(project.formatVersion()<4 && ("DAC".equals(s.type()) || !s.triggers().isEmpty())) throw invalid(at,"DAC sources and sample triggers require formatVersion 4");
            range(s.lengthTicks(),1,Integer.MAX_VALUE,at+".lengthTicks"); range(s.volume(),0,127,at+".volume");
            if(!"RESET".equals(s.repeatState()) && !"CARRY".equals(s.repeatState())) throw invalid(at+".repeatState","use RESET or CARRY");
            if(project.formatVersion()==1 && (!s.commands().isEmpty() || !"RESET".equals(s.repeatState()))) throw invalid(at,"commands and repeat-state changes require formatVersion 2");
            if(project.formatVersion()<3 && (s.psgEnvelopeId()!=null || s.modulationEnvelopeId()!=null)) throw invalid(at,"envelope references require formatVersion 3");
            if(s.type().equals("DAC")) {
                if(s.voiceId()!=null || s.volume()!=127 || !s.notes().isEmpty() || !s.commands().isEmpty()
                        || s.psgEnvelopeId()!=null || s.modulationEnvelopeId()!=null || !"RESET".equals(s.repeatState()))
                    throw invalid(at,"DAC sources require voiceId null, volume 127, empty notes/commands, null envelope references and repeatState RESET");
            } else if(!s.triggers().isEmpty()) throw invalid(at+".triggers","only DAC sources can contain sample triggers");
            unique(s.triggers(),SampleTrigger::id,at+".triggers");
            Set<Integer> triggerTicks=new HashSet<>();
            for(SampleTrigger trigger:s.triggers()) {
                String event=at+" trigger '"+trigger.id()+"'";
                range(trigger.tick(),0,s.lengthTicks()-1,event+".tick");
                if(!triggerTicks.add(trigger.tick())) throw invalid(event+".tick","another sample trigger already uses tick "+trigger.tick());
                if(!samples.containsKey(trigger.sampleId())) throw invalid(event+".sampleId","undefined sample '"+trigger.sampleId()+"'");
            }
            if(s.psgEnvelopeId()!=null) {
                if(!s.type().equals("PSG")) throw invalid(at+".psgEnvelopeId","PSG volume envelopes require a PSG source");
                envelopeReference(envelopes,s.psgEnvelopeId(),Envelope.PSG_VOLUME,at+".psgEnvelopeId");
            }
            if(s.modulationEnvelopeId()!=null) envelopeReference(envelopes,s.modulationEnvelopeId(),Envelope.MODULATION,at+".modulationEnvelopeId");
            if (s.type().equals("FM")) {
                if (!voices.containsKey(s.voiceId())) throw invalid(at+".voiceId","undefined FM voice '"+s.voiceId()+"'");
            } else if (s.voiceId()!=null) throw invalid(at+".voiceId",s.type()+" must use null; PSG uses psgEnvelopeId for a volume envelope");
            unique(s.notes(),NoteEvent::id,at+".notes");
            List<NoteEvent> sorted=s.notes().stream().sorted(Comparator.comparingInt(NoteEvent::tick)).toList();
            long end=0;
            for (NoteEvent n : sorted) {
                String note=at+" note '"+n.id()+"'";
                range(n.tick(),0,Integer.MAX_VALUE,note+".tick"); range(n.duration(),1,Integer.MAX_VALUE,note+".duration");
                // DEF_Z80_T2 has repeated/clamped periods at indices 0..8 and
                // zero periods at 82..83; usable PSG indices 9..81 map to MIDI45..117.
                range(n.pitch(),s.type().equals("PSG")?45:12,s.type().equals("PSG")?117:106,note+".pitch ("+s.type()+")");
                long noteEnd=(long)n.tick()+n.duration();
                if (noteEnd>s.lengthTicks()) throw invalid(note,"must end within sequence length "+s.lengthTicks());
                if (n.tick()<end) throw invalid(note,"overlaps another note; source sequences are monophonic");
                end=noteEnd;
            }
            unique(s.commands(),CommandEvent::id,at+".commands");
            Set<Integer> attacks=new HashSet<>(); s.notes().forEach(n->attacks.add(n.tick()));
            for(CommandEvent event:s.commands()) {
                String command=at+" command '"+event.id()+"'";
                range(event.tick(),0,s.lengthTicks()-1,command+".tick");
                if(event.command()==null) throw invalid(command+".command","a supported native command is required");
                if(!event.command().supports(s.type())) throw invalid(command+".command",event.command()+" does not support "+s.type());
                if(event.arguments().size()!=event.command().argumentCount()) throw invalid(command+".arguments","exactly "+event.command().argumentCount()+" raw byte arguments are required");
                for(int argument:event.arguments()) range(argument,0,255,command+".arguments");
                if(event.command()==NativeCommand.MODULATION && !attacks.contains(event.tick())) throw invalid(command,"MODULATION setup currently requires an explicit note attack tick; held setup has native history semantics");
            }
        }
        unique(project.clips(), Clip::id, "clips");
        Map<String,List<Clip>> lanes=new HashMap<>();
        for (Clip c : project.clips()) {
            String at="clip '"+c.id()+"'";
            Sequence s=sequences.get(c.sequenceId());
            if (s==null) throw invalid(at+".sequenceId","undefined sequence '"+c.sequenceId()+"'");
            if (c.lane()==null || !c.lane().matches("FM[1-5]|PSG[1-3]|DAC")) throw invalid(at+".lane","use FM1..FM5, PSG1..PSG3 or DAC; explicit FM6 is not supported");
            if (!c.lane().startsWith(s.type())) throw invalid(at+".lane","lane type must match sequence type "+s.type());
            range(c.startTick(),0,Integer.MAX_VALUE,at+".startTick"); range(c.lengthTicks(),1,Integer.MAX_VALUE,at+".lengthTicks");
            if ((long)c.startTick()+c.lengthTicks()>Integer.MAX_VALUE) throw invalid(at,"end tick exceeds integer timeline range");
            lanes.computeIfAbsent(c.lane(),ignored->new ArrayList<>()).add(c);
        }
        for (var entry : lanes.entrySet()) {
            entry.getValue().sort(Comparator.comparingInt(Clip::startTick)); long end=0;
            for (Clip c : entry.getValue()) { if (c.startTick()<end) throw invalid("clip '"+c.id()+"'","overlaps another clip on "+entry.getKey()); end=(long)c.startTick()+c.lengthTicks(); }
        }
    }

    private static Voice voice(JsonElement e) {
        JsonObject o=object(e,"voice","id","name","bytes");
        return new Voice(string(o,"id"),string(o,"name"),list(o,"bytes",x->integer(x,"voice.bytes[]")));
    }
    private static Envelope envelope(JsonElement e) {
        JsonObject o=object(e,"envelope","id","name","type","bytes");
        return new Envelope(string(o,"id"),string(o,"name"),string(o,"type"),list(o,"bytes",x->integer(x,"envelope.bytes[]")));
    }
    private static DacSample sample(JsonElement e) {
        JsonObject o=object(e,"sample","id","name","rate","pcmBase64");
        return new DacSample(string(o,"id"),string(o,"name"),integer(o,"rate"),string(o,"pcmBase64"));
    }
    private static SampleTrigger trigger(JsonElement e) {
        JsonObject o=object(e,"trigger","id","tick","sampleId");
        return new SampleTrigger(string(o,"id"),integer(o,"tick"),string(o,"sampleId"));
    }
    private static void envelopeReference(Map<String,Envelope> envelopes,String id,String type,String at) {
        Envelope envelope=envelopes.get(id);
        if(envelope==null) throw invalid(at,"undefined envelope '"+id+"'");
        if(!type.equals(envelope.type())) throw invalid(at,"requires a "+type+" envelope");
    }
    private static Sequence sequence(JsonElement e,int version) {
        JsonObject o=version==1 ? object(e,"sequence","id","name","type","lengthTicks","voiceId","volume","notes")
                : version==2 ? object(e,"sequence","id","name","type","lengthTicks","voiceId","volume","notes","commands","repeatState")
                : version==3 ? object(e,"sequence","id","name","type","lengthTicks","voiceId","volume","notes","commands","repeatState","psgEnvelopeId","modulationEnvelopeId")
                : object(e,"sequence","id","name","type","lengthTicks","voiceId","volume","notes","commands","repeatState","psgEnvelopeId","modulationEnvelopeId","triggers");
        String voice=o.get("voiceId").isJsonNull()?null:string(o,"voiceId");
        return new Sequence(string(o,"id"),string(o,"name"),string(o,"type"),integer(o,"lengthTicks"),voice,integer(o,"volume"),list(o,"notes",ProjectFormat::note),
                version==1?List.of():list(o,"commands",ProjectFormat::command),version==1?"RESET":string(o,"repeatState"),
                version>=3?nullableString(o,"psgEnvelopeId"):null,version>=3?nullableString(o,"modulationEnvelopeId"):null,
                version==4?list(o,"triggers",ProjectFormat::trigger):List.of());
    }
    private static CommandEvent command(JsonElement e) {
        JsonObject o=object(e,"command","id","tick","command","arguments");
        String name=string(o,"command"); NativeCommand command;
        try { command=NativeCommand.valueOf(name); } catch(IllegalArgumentException ex) { throw invalid("command.command","unsupported native command '"+name+"'"); }
        return new CommandEvent(string(o,"id"),integer(o,"tick"),command,list(o,"arguments",x->integer(x,"command.arguments[]")));
    }
    private static NoteEvent note(JsonElement e) {
        JsonObject o=object(e,"note","id","tick","duration","pitch"); return new NoteEvent(string(o,"id"),integer(o,"tick"),integer(o,"duration"),integer(o,"pitch"));
    }
    private static Clip clip(JsonElement e) {
        JsonObject o=object(e,"clip","id","sequenceId","lane","startTick","lengthTicks"); return new Clip(string(o,"id"),string(o,"sequenceId"),string(o,"lane"),integer(o,"startTick"),integer(o,"lengthTicks"));
    }
    private static JsonObject object(JsonElement value,String at,String... names) {
        if (!value.isJsonObject()) throw invalid(at,"must be an object");
        JsonObject o=value.getAsJsonObject(); Set<String> allowed=Set.of(names);
        for (String key : o.keySet()) if (!allowed.contains(key)) throw invalid(at+"."+key,"unknown field; unsupported data cannot be discarded");
        for (String key : names) if (!o.has(key)) throw invalid(at+"."+key,"required field is missing");
        return o;
    }
    private static String string(JsonObject o,String key) {
        JsonElement e=o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid(key,"must be a string");
        return e.getAsString();
    }
    private static String nullableString(JsonObject o,String key) { return o.get(key).isJsonNull()?null:string(o,key); }
    private static int integer(JsonObject o,String key) { return integer(o.get(key),key); }
    private static int integer(JsonElement e,String at) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw invalid(at,"must be an integer");
        return e.getAsInt(); // readValue already checked lexical integrality and exact int range
    }
    private static <T> List<T> list(JsonObject o,String key,Function<JsonElement,T> convert) {
        JsonElement value=o.get(key); if (!value.isJsonArray()) throw invalid(key,"must be an array");
        List<T> result=new ArrayList<>(); for (JsonElement e : value.getAsJsonArray()) result.add(convert.apply(e)); return result;
    }
    private static <T> Map<String,T> unique(List<T> values,Function<T,String> id,String at) {
        Map<String,T> result=new HashMap<>();
        for (T value : values) { String key=id.apply(value); text(key,at+".id"); if (result.putIfAbsent(key,value)!=null) throw invalid(at,"duplicate id '"+key+"'"); }
        return result;
    }
    private static void text(String value,String at) { if (value==null || value.isBlank()) throw invalid(at,"must be a nonblank string"); }
    private static void range(int value,int low,int high,String at) { if (value<low || value>high) throw invalid(at,"must be in "+low+".."+high+" (was "+value+")"); }
    private static IllegalArgumentException invalid(String at,String message) { return new IllegalArgumentException(at+": "+message); }

    /** Gson's tree parser accepts duplicate names; retain strict checks before making a tree. */
    private static JsonElement readValue(JsonReader r,int depth) throws IOException {
        if (depth>32) throw invalid(r.getPath(),"JSON nesting is too deep");
        return switch(r.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject o=new JsonObject(); Set<String> seen=new HashSet<>(); r.beginObject();
                while(r.hasNext()) { String name=r.nextName(); if(!seen.add(name)) throw invalid(r.getPath(),"duplicate field '"+name+"'"); o.add(name,readValue(r,depth+1)); }
                r.endObject(); yield o;
            }
            case BEGIN_ARRAY -> { JsonArray a=new JsonArray(); r.beginArray(); while(r.hasNext()) a.add(readValue(r,depth+1)); r.endArray(); yield a; }
            case STRING -> new JsonPrimitive(r.nextString());
            case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
            case NULL -> { r.nextNull(); yield JsonNull.INSTANCE; }
            case NUMBER -> {
                String path=r.getPath(), number=r.nextString();
                if (!number.matches("-?(0|[1-9][0-9]*)")) throw invalid(path,"must be an integer without fractional or exponential notation");
                try { yield new JsonPrimitive(Integer.parseInt(number)); } catch(NumberFormatException e) { throw invalid(path,"integer is outside 32-bit range"); }
            }
            default -> throw invalid(r.getPath(),"expected a JSON value");
        };
    }
}
