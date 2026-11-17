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

/** Strict version-one source boundary. Unknown native features are rejected, never discarded. */
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
            JsonObject o = object(tree, "project", "formatVersion", "name", "mode", "ticksPerBeat", "tempo", "voices", "sequences", "clips");
            StudioProject project = new StudioProject(integer(o,"formatVersion"),string(o,"name"),string(o,"mode"),integer(o,"ticksPerBeat"),integer(o,"tempo"),
                    list(o,"voices",ProjectFormat::voice),list(o,"sequences",ProjectFormat::sequence),list(o,"clips",ProjectFormat::clip));
            validate(project);
            return project;
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("Invalid project JSON: " + e.getMessage(), e);
        }
    }

    public static String write(StudioProject project) {
        validate(project);
        JsonObject o = new JsonObject();
        o.addProperty("formatVersion",project.formatVersion()); o.addProperty("name",project.name()); o.addProperty("mode",project.mode());
        o.addProperty("ticksPerBeat",project.ticksPerBeat()); o.addProperty("tempo",project.tempo());
        JsonArray voices = new JsonArray();
        for (Voice v : project.voices()) { JsonObject x=new JsonObject(); x.addProperty("id",v.id()); x.addProperty("name",v.name()); JsonArray b=new JsonArray(); v.bytes().forEach(b::add); x.add("bytes",b); voices.add(x); }
        o.add("voices",voices);
        JsonArray sequences = new JsonArray();
        for (Sequence s : project.sequences()) {
            JsonObject x=new JsonObject(); x.addProperty("id",s.id()); x.addProperty("name",s.name()); x.addProperty("type",s.type()); x.addProperty("lengthTicks",s.lengthTicks()); x.addProperty("voiceId",s.voiceId()); x.addProperty("volume",s.volume());
            JsonArray notes = new JsonArray();
            for (NoteEvent n : s.notes()) { JsonObject y=new JsonObject(); y.addProperty("id",n.id()); y.addProperty("tick",n.tick()); y.addProperty("duration",n.duration()); y.addProperty("pitch",n.pitch()); notes.add(y); }
            x.add("notes",notes); sequences.add(x);
        }
        o.add("sequences",sequences);
        JsonArray clips = new JsonArray();
        for (Clip c : project.clips()) { JsonObject x=new JsonObject(); x.addProperty("id",c.id()); x.addProperty("sequenceId",c.sequenceId()); x.addProperty("lane",c.lane()); x.addProperty("startTick",c.startTick()); x.addProperty("lengthTicks",c.lengthTicks()); clips.add(x); }
        o.add("clips",clips);
        return GSON.toJson(o) + "\n";
    }

    public static void validate(StudioProject project) {
        if (project == null) throw invalid("project", "document is required");
        if (project.formatVersion()!=1) throw invalid("formatVersion", "only version 1 is supported");
        text(project.name(),"name");
        if (!"S3K".equals(project.mode())) throw invalid("mode", "only S3K is supported");
        range(project.ticksPerBeat(),1,Integer.MAX_VALUE,"ticksPerBeat"); range(project.tempo(),0,255,"tempo");
        if (project.voices().size()>256) throw invalid("voices", "at most 256 local voices can be addressed");
        Map<String,Voice> voices = unique(project.voices(), Voice::id, "voices");
        for (Voice v : project.voices()) {
            text(v.name(),"voice '"+v.id()+"'.name");
            if (v.bytes().size()!=25) throw invalid("voice '"+v.id()+"'.bytes", "exactly 25 native-order bytes are required");
            for (int b : v.bytes()) range(b,0,255,"voice '"+v.id()+"'.bytes");
        }
        Map<String,Sequence> sequences = unique(project.sequences(), Sequence::id, "sequences");
        for (Sequence s : project.sequences()) {
            String at="sequence '"+s.id()+"'"; text(s.name(),at+".name");
            if (!"FM".equals(s.type()) && !"PSG".equals(s.type())) throw invalid(at+".type","use FM or PSG; DAC, groups and raw commands are not supported");
            range(s.lengthTicks(),1,Integer.MAX_VALUE,at+".lengthTicks"); range(s.volume(),0,127,at+".volume");
            if (s.type().equals("FM")) {
                if (!voices.containsKey(s.voiceId())) throw invalid(at+".voiceId","undefined FM voice '"+s.voiceId()+"'");
            } else if (s.voiceId()!=null) throw invalid(at+".voiceId","PSG must use null; external envelopes are not supported");
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
        }
        unique(project.clips(), Clip::id, "clips");
        Map<String,List<Clip>> lanes=new HashMap<>();
        for (Clip c : project.clips()) {
            String at="clip '"+c.id()+"'";
            Sequence s=sequences.get(c.sequenceId());
            if (s==null) throw invalid(at+".sequenceId","undefined sequence '"+c.sequenceId()+"'");
            if (c.lane()==null || !c.lane().matches("FM[1-5]|PSG[1-3]")) throw invalid(at+".lane","use FM1..FM5 or PSG1..PSG3; DAC/FM6 are not supported");
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
    private static Sequence sequence(JsonElement e) {
        JsonObject o=object(e,"sequence","id","name","type","lengthTicks","voiceId","volume","notes");
        String voice=o.get("voiceId").isJsonNull()?null:string(o,"voiceId");
        return new Sequence(string(o,"id"),string(o,"name"),string(o,"type"),integer(o,"lengthTicks"),voice,integer(o,"volume"),list(o,"notes",ProjectFormat::note));
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
