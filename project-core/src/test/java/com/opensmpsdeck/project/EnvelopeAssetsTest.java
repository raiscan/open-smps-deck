package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.opensmpsdeck.project.ProjectCompilerTest.*;

class EnvelopeAssetsTest {
    private static Envelope envelope(String id,String type,Integer... bytes) { return new Envelope(id,id,type,List.of(bytes)); }
    private static Sequence source(String type,String state,String psg,String mod) {
        return new Sequence("s","Source",type,4,type.equals("FM")?"v":null,100,List.of(new NoteEvent("n",0,4,60)),List.of(),state,psg,mod);
    }
    private static StudioProject project(Sequence source,List<Envelope> envelopes,int length) {
        return new StudioProject(3,"Assets","S3K",24,128,List.of(VOICE),List.of(source),List.of(new Clip("c","s",source.type()+"1",0,length)),envelopes, TargetGame.S3K, null);
    }

    @Test void validatesTheBoundedSafeNativeEnvelopeProfile() {
        for(String type:List.of("PSG_VOLUME","MODULATION")) {
            for(int terminal:List.of(0x80,0x81,0x83)) EnvelopeProgram.validate(type,List.of(0,terminal));
            EnvelopeProgram.validate(type,List.of(0x81));EnvelopeProgram.validate(type,List.of(0x83));
            List<Integer> longest=new ArrayList<>(java.util.Collections.nCopies(255,0));longest.add(0x80);
            EnvelopeProgram.validate(type,longest);
            for(List<Integer> invalid:List.of(List.<Integer>of(),List.of(0x80),List.of(0x80,0,0x83),List.of(0),List.of(-1,0x81),List.of(256,0x81),List.of(0x82,0,0x81),List.of(0x84,0,0x81),List.of(0x85,0x81),java.util.Collections.nCopies(257,0x81)))
                assertThrows(IllegalArgumentException.class,()->EnvelopeProgram.validate(type,invalid),type+invalid);
            for(int opcode=0x85;opcode<=0xFF;opcode++) {
                int unsupported=opcode;
                assertThrows(IllegalArgumentException.class,()->EnvelopeProgram.validate(type,List.of(unsupported,0x81)));
            }
        }
        EnvelopeProgram.validate("MODULATION",List.of(0x7F,0x83));
        EnvelopeProgram.validate("PSG_VOLUME",List.of(0x0F,0x81));
        assertThrows(IllegalArgumentException.class,()->EnvelopeProgram.validate("PSG_VOLUME",List.of(0x10,0x81)));
        assertThrows(IllegalArgumentException.class,()->EnvelopeProgram.validate("OTHER",List.of(0,0x81)));
    }

    @Test void versionThreeRequiresAssetFieldsAndRejectsLossyOlderDocuments() {
        var env=envelope("e","PSG_VOLUME",0,1,0x83);
        var p=project(source("PSG","RESET","e",null),List.of(env),4);
        String json=ProjectFormat.write(p);
        assertEquals(p,ProjectFormat.read(json));assertEquals(json,ProjectFormat.write(ProjectFormat.read(json)));
        for(String invalid:List.of(json.replace("\"envelopes\": [","\"assets\": ["),json.replace("\"psgEnvelopeId\": \"e\",",""),json.replace("\"modulationEnvelopeId\": null","\"modulationEnvelopeId\": 1"),
                json.replace("\"type\": \"PSG_VOLUME\"","\"type\": \"OTHER\""),json.replace("\"formatVersion\": 3","\"formatVersion\": 2"),json.replace("\"formatVersion\": 3","\"formatVersion\": 1"),
                json.replace("\"psgEnvelopeId\": \"e\"","\"psgEnvelopeId\": \"missing\""),json.replace("\"modulationEnvelopeId\": null","\"modulationEnvelopeId\": \"e\"")))
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(invalid),invalid);
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.write(new StudioProject(2,p.name(),p.mode(),p.ticksPerBeat(),p.tempo(),p.voices(),p.sequences(),p.clips(),p.envelopes(), TargetGame.S3K, null)));
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(source("FM","RESET","e",null),List.of(env),4)));
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(source("PSG","RESET",null,"e"),List.of(env),4)));
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(source("PSG","RESET","e",null),List.of(env,env),4)));
    }

    @Test void compilationAllocatesSeparateNativeIndicesAndPreservesDeclaredUnusedAssets() {
        var envelopes=List.of(envelope("m1","MODULATION",1,0x83),envelope("p1","PSG_VOLUME",0,0x81),
                envelope("m2","MODULATION",2,0x83),envelope("p2","PSG_VOLUME",3,0x83));
        var p=project(source("PSG","RESET","p2","m2"),envelopes,4);
        CompiledProject compiled=ProjectCompiler.compileWithAssets(p);
        assertEquals(Map.of(1,List.of(0,0x81),2,List.of(3,0x83)),compiled.psgEnvelopes());
        assertEquals(Map.of(1,List.of(1,0x83),2,List.of(2,0x83)),compiled.modulationEnvelopes());
        byte[] song=compiled.song();
        assertArrayEquals(bytes(0xE4,100,0xED,0x40,0xE1,0,0xE8,0,0xFA,0xF5,2,0xF4,2,0x99,4,0xF2),Arrays.copyOfRange(song,word(song,10),word(song,0)));
        assertArrayEquals(song,ProjectCompiler.compile(p));assertEquals(envelopes,compiled.envelopes());assertTrue(compiled.requiresAssets());
        byte[] retained=compiled.song();song[0]=99;assertArrayEquals(retained,compiled.song());
        assertThrows(UnsupportedOperationException.class,()->compiled.envelopes().clear());
        assertThrows(UnsupportedOperationException.class,()->compiled.psgEnvelopes().clear());
        assertThrows(UnsupportedOperationException.class,()->compiled.modulationEnvelopes().get(1).clear());
        assertTrue(ProjectCompiler.compileWithAssets(project(source("FM","RESET",null,null),envelopes,4)).requiresAssets());
        var raw=new byte[]{1,2};var copied=new CompiledProject(raw,envelopes);raw[0]=9;assertEquals(1,copied.song()[0]);
        var mutable=new ArrayList<>(List.of(0,0x81));var env=new Envelope("p","P","PSG_VOLUME",mutable);mutable.clear();assertEquals(List.of(0,0x81),env.bytes());
    }

    @Test void versionThreeResetAndCarryEmitEnvelopeSelectionOnlyAtTheDefinedBoundaries() {
        var envs=List.of(envelope("m","MODULATION",1,0x83));
        byte[] reset=bytes(0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,0xE1,0,0xE8,0,0xFA,0xF4,1);
        for(String policy:List.of("RESET","CARRY")) {
            var p=project(source("FM",policy,null,"m"),envs,8);
            p=ProjectEdits.addClip(p,new Clip("d","s","FM1",8,2));
            byte[] song=ProjectCompiler.compile(p, ProjectCompiler.Encoding.LINEAR);
            assertArrayEquals(NativeCommandsTest.join(reset,bytes(0xB1,4),policy.equals("RESET")?reset:new byte[0],bytes(0xB1,4),reset,bytes(0xB1,2,0xF2)),Arrays.copyOfRange(song,word(song,10),word(song,0)));
        }
        var noAssets=project(source("PSG","RESET",null,null),List.of(),4);
        byte[] empty=ProjectCompiler.compile(noAssets);
        assertArrayEquals(bytes(0xE4,100,0xED,0x40,0xE1,0,0xE8,0,0xFA,0xF5,0,0xF4,0,0x99,4,0xF2),Arrays.copyOfRange(empty,word(empty,10),word(empty,0)));
        assertFalse(ProjectCompiler.compileWithAssets(noAssets).requiresAssets());
    }

    @Test void envelopeCountsUsePerKindNativeLimitsIncludingUnusedAssets() {
        for(String type:List.of("PSG_VOLUME","MODULATION")) {
            int max=type.equals("PSG_VOLUME")?255:127;
            var envs=new ArrayList<Envelope>();
            for(int i=0;i<max;i++) envs.add(envelope("e"+i,type,0,0x81));
            var p=project(source("FM","RESET",null,null),envs,4);
            ProjectFormat.validate(p);assertEquals(max,ProjectCompiler.compileWithAssets(p).envelopes().size());
            envs.add(envelope("too-many",type,0,0x81));
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(source("FM","RESET",null,null),envs,4)));
        }
    }

    @Test void assetEditsRefuseReferencedDeletionAndPreserveAllFieldsAcrossExistingEdits() {
        var legacy=ProjectFormatTest.fixture();
        Voice extra=NativeFmVoice.initial("other","Other");
        var p=ProjectEdits.addVoice(legacy,extra);assertEquals(1,p.formatVersion());
        p=ProjectEdits.updateVoice(p,NativeFmVoice.with(extra,3,NativeFmVoice.Parameter.TOTAL_LEVEL,12));
        assertEquals(12,NativeFmVoice.read(p.voices().getLast(),3,NativeFmVoice.Parameter.TOTAL_LEVEL));
        var referenced=p;assertThrows(IllegalArgumentException.class,()->ProjectEdits.deleteVoice(referenced,"v"));
        p=ProjectEdits.deleteVoice(p,"other");assertEquals(1,p.formatVersion());
        var m=envelope("m","MODULATION",1,0x83);p=ProjectEdits.addEnvelope(p,m);assertEquals(3,p.formatVersion());
        p=ProjectEdits.setSourceEnvelopes(p,"s",null,"m");
        p=ProjectEdits.updateEnvelope(p,envelope("m","MODULATION",2,0x83));
        var used=p;assertThrows(IllegalArgumentException.class,()->ProjectEdits.deleteEnvelope(used,"m"));
        p=ProjectEdits.addCommand(p,"s",new CommandEvent("pan",0,NativeCommand.PAN,List.of(128)));
        p=ProjectEdits.updateCommand(p,"s",new CommandEvent("pan",0,NativeCommand.PAN,List.of(64)));
        p=ProjectEdits.moveCommand(p,"s","pan",1);p=ProjectEdits.setRepeatState(p,"s","CARRY");
        p=ProjectEdits.setSourceSettings(p,"s","v",101);p=ProjectEdits.updateNote(p,"s",new NoteEvent("n",0,10,64));
        p=ProjectEdits.addNote(p,"s",new NoteEvent("a",12,4,67));p=ProjectEdits.deleteNote(p,"s","a");
        p=ProjectEdits.moveClip(p,"c","FM2",0);p=ProjectEdits.resizeClip(p,"c",24);
        p=ProjectEdits.replaceClip(p,new Clip("c","s","FM3",0,24));p=ProjectEdits.addClip(p,new Clip("d","s","FM4",0,12));p=ProjectEdits.deleteClip(p,"d");
        p=ProjectEdits.deleteCommand(p,"s","pan");
        assertEquals(3,p.formatVersion());assertEquals("m",p.sequences().getFirst().modulationEnvelopeId());
        assertEquals(List.of(envelope("m","MODULATION",2,0x83)),p.envelopes());
        assertEquals(p,ProjectFormat.read(ProjectFormat.write(p)));
        p=ProjectEdits.setSourceEnvelopes(p,"s",null,null);p=ProjectEdits.deleteEnvelope(p,"m");assertEquals(3,p.formatVersion());
        var unchanged=p;
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.updateVoice(unchanged,extra));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.updateEnvelope(unchanged,m));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.deleteEnvelope(unchanged,"missing"));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.setSourceEnvelopes(unchanged,"s",null,"missing"));
        assertEquals(1,ProjectEdits.setSourceEnvelopes(legacy,"s",null,null).formatVersion());
    }

    @Test void oldVersionsKeepTheirOriginalSourceFieldsAndNativeBytes() {
        var legacy=ProjectFormatTest.fixture();
        var v2=ProjectEdits.addCommand(legacy,"s",new CommandEvent("pan",6,NativeCommand.PAN,List.of(128)));
        for(var p:List.of(legacy,v2)) {
            String json=ProjectFormat.write(p);assertFalse(json.contains("envelopes"));assertFalse(json.contains("EnvelopeId"));
            assertEquals(p,ProjectFormat.read(json));assertEquals(json,ProjectFormat.write(ProjectFormat.read(json)));
            assertFalse(ProjectCompiler.compileWithAssets(p).requiresAssets());
            byte[] song=ProjectCompiler.compileWithAssets(p, ProjectCompiler.Encoding.LINEAR).song();
            byte[] reset=p.formatVersion()==1?bytes(0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40):NativeCommandsTest.fmReset();
            byte[] repetition=p.formatVersion()==1?bytes(0xB1,12,0x80,12):bytes(0xB1,6,0xE0,128,0xE7,6,0x80,12);
            assertArrayEquals(NativeCommandsTest.join(reset,repetition,reset,repetition,bytes(0xF2)),Arrays.copyOfRange(song,word(song,10),word(song,0)));
        }
    }
}
