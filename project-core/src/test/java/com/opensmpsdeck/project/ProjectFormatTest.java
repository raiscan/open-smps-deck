package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectFormatTest {
    static StudioProject fixture() {
        return ProjectCompilerTest.project(List.of(ProjectCompilerTest.sequence("s","FM",24,List.of(new NoteEvent("n",0,12,60)))),List.of(new Clip("c","s","FM1",0,48)));
    }
    @Test void provenanceIsOptionalAndStrictWithoutChangingTheVersion() {
        var before = fixture();
        var voice = before.voices().getFirst();
        var ref = new StockRef("s3k", "global-fm", null, 3, null, null, "a".repeat(64));
        var declared = new StudioProject(1, before.name(), before.mode(), before.ticksPerBeat(),
                before.tempo(), List.of(voice.withOrigin(AssetOrigin.stock(ref))), before.sequences(), before.clips())
                .withDerivedFrom(new RetailSongOrigin("retail-song", "s3k", 13, "b".repeat(64)));
        String json = ProjectFormat.write(declared);
        assertEquals(declared, ProjectFormat.read(json));
        assertEquals(json, ProjectFormat.write(ProjectFormat.read(json)));
        assertEquals(1, ProjectFormat.read(json).formatVersion());
        assertEquals(ProjectFormat.write(before), ProjectFormat.write(ProjectFormat.read(ProjectFormat.write(before))));
        for (String invalid : List.of(json.replace("\"slot\": 3", "\"slot\": 3, \"note\": 129"),
                json.replace("\"slot\": 3", "\"slot\": 3, \"slot\": 4"),
                json.replace("\"slot\": 3", "\"bogus\": 3"),
                json.replace("\"type\": \"stock\"", "\"type\": \"original\""),
                json.replace("\"songId\": 13", "\"songId\": \"13\""))) {
            assertThrows(IllegalArgumentException.class, () -> ProjectFormat.read(invalid));
        }
    }
    @Test void deterministicRoundTripAndDefensiveLists() {
        var source=new ArrayList<NoteEvent>(); source.add(new NoteEvent("n",0,1,60));
        var s=ProjectCompilerTest.sequence("s","FM",24,source); source.clear();
        assertEquals(1,s.notes().size()); assertThrows(UnsupportedOperationException.class,()->s.notes().clear());
        String json=ProjectFormat.write(fixture());
        assertEquals(fixture(),ProjectFormat.read(json)); assertEquals(json,ProjectFormat.write(ProjectFormat.read(json)));
        assertTrue(json.contains("\n  \"formatVersion\""));
    }
    @Test void rejectsUnknownMissingDuplicateAndWrongPrimitiveFieldsAtEveryLevel() {
        String json=ProjectFormat.write(fixture());
        for(String bad : List.of(
                json.replace("\"formatVersion\": 1,","\"formatVersion\": 1, \"rawCommands\": [],"),
                json.replace("\"formatVersion\": 1,",""),
                json.replace("\"formatVersion\": 1,","\"formatVersion\": 1, \"formatVersion\": 1,"),
                json.replace("\"tick\": 0,","\"tick\": 0, \"tick\": 1,"),
                json.replace("\"tempo\": 128","\"tempo\": \"128\""),
                json.replace("\"tempo\": 128","\"tempo\": 128.5"),
                json.replace("\"pitch\": 60","\"pitch\": 2147483648"),
                json.replace("\"pitch\": 60","\"pitch\": 6e1"),
                json.replace("\"id\": \"n\"","\"id\": 1"),
                json.replace("\"id\": \"n\"","\"id\": \"n\", \"commands\": []"),
                json.replace("\"id\": \"c\"","\"id\": \"c\", \"transpose\": 4"),
                json.replace("\"id\": \"v\"","\"id\": \"v\", \"envelope\": 1"),
                json.replace("\"volume\": 100","\"volume\": null"),
                json.replace("\"notes\": [","\"notes\": null, \"extra\": ["),
                json.replace("\"duration\": 12,",""),
                json+" {}", json.replace("\"name\": \"Fixture\"","\"name\": true"))) {
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(bad),bad);
        }
    }
    @Test void rejectsNonJsonSyntaxAndExcessiveNestingWithoutLenientRecovery() {
        String json=ProjectFormat.write(fixture());
        for(String bad : List.of("// comment\n"+json,json.replace("\"tempo\": 128","\"tempo\": NaN"),json.replace("\"tempo\": 128","tempo: 128"),json.substring(0,json.length()-2)+",}\n", "[".repeat(40)+"0"+"]".repeat(40))) {
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(bad));
        }
    }
    @Test void rejectsSchemaAndHardwareErrorsWithoutSilentClipping() {
        String json=ProjectFormat.write(fixture());
        for(String bad : List.of(json.replace("\"formatVersion\": 1","\"formatVersion\": 2"),
                json.replace("\"mode\": \"S3K\"","\"mode\": \"S2\""),
                json.replace("\"pitch\": 60","\"pitch\": 107"),
                json.replace("\"lane\": \"FM1\"","\"lane\": \"FM6\""),
                json.replace("\"sequenceId\": \"s\"","\"sequenceId\": \"absent\""),
                json.replace("\"voiceId\": \"v\"","\"voiceId\": null"),
                json.replace("\"duration\": 12","\"duration\": 25"),
                json.replace("\"startTick\": 0","\"startTick\": 2147483647"))) {
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(bad),bad);
        }
    }
    @Test void rejectsOverlapsDuplicateIdsAndWrongLaneTypes() {
        var p=fixture();
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.addClip(p,new Clip("c2","s","FM1",24,24)));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.addClip(p,new Clip("c","s","FM2",0,24)));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.moveClip(p,"c","PSG1",0));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.addNote(p,"s",new NoteEvent("overlap",6,4,60)));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.addNote(p,"s",new NoteEvent("n",18,4,60)));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.deleteClip(p,"missing"));
        assertEquals(0,p.clips().getFirst().startTick());
    }
    @Test void pureEditsRetainLinkedSourcesAndValidateEveryResult() {
        var p=fixture();
        p=ProjectEdits.addClip(p,new Clip("c2","s","FM3",72,24));
        p=ProjectEdits.moveClip(p,"c2","FM2",96);
        p=ProjectEdits.resizeClip(p,"c2",48);
        p=ProjectEdits.updateNote(p,"s",new NoteEvent("n",6,12,64));
        assertEquals(2,p.clips().size()); assertEquals(1,p.sequences().size()); assertEquals(64,p.sequences().getFirst().notes().getFirst().pitch());
        p=ProjectEdits.deleteNote(p,"s","n"); assertTrue(p.sequences().getFirst().notes().isEmpty());
        p=ProjectEdits.deleteClip(p,"c2"); assertEquals(1,p.clips().size());
    }
    @Test void psgRequiresExplicitNullVoiceAndSupportsNoExternalEnvelope() {
        var s=ProjectCompilerTest.sequence("psg","PSG",24,List.of(new NoteEvent("n",0,12,60)));
        var p=new StudioProject(1,"PSG","S3K",24,128,List.of(),List.of(s),List.of(new Clip("c","psg","PSG1",0,24)));
        String json=ProjectFormat.write(p); assertTrue(json.contains("\"voiceId\": null")); assertEquals(p,ProjectFormat.read(json));
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(json.replace("\"voiceId\": null","\"voiceId\": \"envelope\"")));
    }
    @Test void rejectsPitchesOutsideEachNativeChipRangeWithActionableBounds() {
        for(String type:List.of("FM","PSG")) {
            int low=type.equals("FM")?12:45,high=type.equals("FM")?106:117;
            for(int pitch:List.of(low,high)) {
                var s=ProjectCompilerTest.sequence("s",type,12,List.of(new NoteEvent("n",0,12,pitch)));
                var p=ProjectCompilerTest.project(List.of(s),List.of(new Clip("c","s",type+"1",0,12)));
                assertEquals(p,ProjectFormat.read(ProjectFormat.write(p)));
            }
            for(int pitch:List.of(low-1,high+1)) {
                var s=ProjectCompilerTest.sequence("s",type,12,List.of(new NoteEvent("n",0,12,pitch)));
                var p=ProjectCompilerTest.project(List.of(s),List.of(new Clip("c","s",type+"1",0,12)));
                var error=assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(p));
                assertTrue(error.getMessage().contains(low+".."+high)); assertTrue(error.getMessage().contains(type));
                assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(p));
            }
        }
    }
    @Test void demoIsOriginalLinkedEightBarSourceWithGapsAndSparseLanes() {
        var demo=DemoProject.create(); ProjectFormat.validate(demo);
        assertEquals(demo,ProjectFormat.read(ProjectFormat.write(demo)));
        assertTrue(demo.clips().stream().anyMatch(c->c.lane().equals("FM3")));
        assertEquals(8*4*demo.ticksPerBeat(),demo.clips().stream().mapToInt(c->c.startTick()+c.lengthTicks()).max().orElseThrow());
        assertTrue(ProjectCompiler.compile(demo).length<32768);
    }
}
