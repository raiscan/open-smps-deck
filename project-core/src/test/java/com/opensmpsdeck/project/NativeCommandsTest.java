package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.opensmpsdeck.project.ProjectCompilerTest.bytes;
import static com.opensmpsdeck.project.ProjectCompilerTest.word;

class NativeCommandsTest {
    static CommandEvent event(String id,int tick,NativeCommand command,Integer... arguments) { return new CommandEvent(id,tick,command,List.of(arguments)); }
    static Sequence source(String type,int length,List<NoteEvent> notes,List<CommandEvent> commands,String state) {
        return new Sequence("s","Source",type,length,type.equals("FM")?"v":null,100,notes,commands,state);
    }
    static StudioProject project(Sequence sequence,int clipLength) {
        return new StudioProject(2,"Commands","S3K",24,128,List.of(ProjectCompilerTest.VOICE),List.of(sequence),List.of(new Clip("c","s",sequence.type()+"1",0,clipLength)));
    }
    static byte[] track(StudioProject project) {
        byte[] bank=ProjectCompiler.compile(project); return Arrays.copyOfRange(bank,word(bank,10),word(bank,0));
    }
    static byte[] fmReset() { return bytes(0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,0xE1,0,0xE8,0,0xFA); }
    static byte[] join(byte[]... parts) {
        var result=new java.io.ByteArrayOutputStream(); for(byte[] part:parts) result.writeBytes(part); return result.toByteArray();
    }
    @Test void readsVersionTwoCommandsWithoutWeakeningVersionOne() {
        String legacy = ProjectFormat.write(ProjectFormatTest.fixture());
        String v2 = legacy.replace("\"formatVersion\": 1", "\"formatVersion\": 2")
                .replace("\"notes\": [", "\"commands\": [{\"id\":\"pan\",\"tick\":6,\"command\":\"PAN\",\"arguments\":[128]}],\"repeatState\":\"RESET\",\"notes\": [");
        var project = ProjectFormat.read(v2);
        assertEquals(2, project.formatVersion());
        assertEquals(project, ProjectFormat.read(ProjectFormat.write(project)));
        assertThrows(IllegalArgumentException.class, () -> ProjectFormat.read(v2.replace("\"formatVersion\": 2", "\"formatVersion\": 1")));
        assertFalse(legacy.contains("commands"));
        assertFalse(legacy.contains("repeatState"));
    }

    @Test void interiorCommandsKeepStoredPitchAndDoNotRepeatTheNoteByte() {
        var source=source("FM",12,List.of(new NoteEvent("n",0,12,60)),List.of(
                event("pan",6,NativeCommand.PAN,128),event("transpose",6,NativeCommand.TRANSPOSE,0x4C)),"RESET");
        assertArrayEquals(join(fmReset(),bytes(0xB1,6,0xE0,0x80,0xED,0x4C,0xE7,6,0xF2)),track(project(source,12)));
    }

    @Test void sameTickOrderIsAuthoredOrderEvenWhenInputTicksAreUnsorted() {
        var source=source("FM",12,List.of(new NoteEvent("n",0,12,60)),List.of(
                event("late",6,NativeCommand.DETUNE,255),event("a",0,NativeCommand.FM_VOLUME,1),
                event("b",0,NativeCommand.VOLUME,127),event("c",0,NativeCommand.TRANSPOSE_ADD,255)),"RESET");
        assertArrayEquals(join(fmReset(),bytes(0xE6,1,0xE4,127,0xFB,255,0xB1,6,0xE1,255,0xE7,6,0xF2)),track(project(source,12)));
    }

    @Test void longHoldsUseDurationOnlyAndGapsStayExplicitRests() {
        var source=source("FM",420,List.of(new NoteEvent("n",130,280,60)),List.of(event("pan",260,NativeCommand.PAN,64)),"RESET");
        assertArrayEquals(join(fmReset(),bytes(0x80,127,0x80,3,0xB1,127,0xE7,3,0xE0,64,0xE7,127,0xE7,23,0x80,10,0xF2)),track(project(source,420)));
    }

    @Test void silentCommandsExecuteAndPartialRepeatsDropEventsOutsideTheirBoundary() {
        var source=source("PSG",10,List.of(),List.of(event("vol",3,NativeCommand.PSG_VOLUME,255),event("boundary",4,NativeCommand.TRANSPOSE,0x4C),event("detune",8,NativeCommand.DETUNE,1)),"RESET");
        byte[] reset=bytes(0xE4,100,0xED,0x40,0xE1,0,0xE8,0,0xFA);
        assertArrayEquals(join(reset,bytes(0x80,3,0xEC,255,0x80,1,0xED,0x4C,0x80,4,0xE1,1,0x80,2),reset,bytes(0x80,3,0xEC,255,0x80,1,0xF2)),track(project(source,14)));
    }

    @Test void commandsAtNoteEndsRunBeforeTheFollowingAttackOrRest() {
        var source=source("FM",18,List.of(new NoteEvent("a",0,6,60),new NoteEvent("b",6,6,64)),List.of(
                event("atAttack",6,NativeCommand.TRANSPOSE_ADD,1),event("atEnd",12,NativeCommand.DETUNE,255)),"RESET");
        assertArrayEquals(join(fmReset(),bytes(0xB1,6,0xFB,1,0xB5,6,0xE1,255,0x80,6,0xF2)),track(project(source,18)));
        assertArrayEquals(join(fmReset(),bytes(0xB1,6,0xFB,1,0xB5,3,0xF2)),track(project(source,9)));
    }

    @Test void resetAndCarryApplyPerClipAndEveryIndependentClipResets() {
        var notes=List.of(new NoteEvent("n",0,4,60));
        var commands=List.of(event("relative",0,NativeCommand.FM_VOLUME,1));
        byte[] repetition=bytes(0xE6,1,0xB1,4);
        assertArrayEquals(join(fmReset(),repetition,fmReset(),repetition,bytes(0xF2)),track(project(source("FM",4,notes,commands,"RESET"),8)));
        var carry=project(source("FM",4,notes,commands,"CARRY"),8);
        assertArrayEquals(join(fmReset(),repetition,repetition,bytes(0xF2)),track(carry));
        var two=new StudioProject(2,carry.name(),carry.mode(),carry.ticksPerBeat(),carry.tempo(),carry.voices(),carry.sequences(),List.of(new Clip("c","s","FM1",0,8),new Clip("d","s","FM1",8,4)));
        assertArrayEquals(join(fmReset(),repetition,repetition,fmReset(),repetition,bytes(0xF2)),track(two));
    }

    @Test void modulationHasExactFourByteSetupAndZeroByteOffAtAttackTicks() {
        var source=source("FM",12,List.of(new NoteEvent("n",0,12,60)),List.of(event("setup",0,NativeCommand.MODULATION,0,0,255,0),event("off",6,NativeCommand.MODULATION_OFF)),"RESET");
        assertArrayEquals(join(fmReset(),bytes(0xF0,0,0,255,0,0xB1,6,0xFA,0xE7,6,0xF2)),track(project(source,12)));
        var held=source("FM",12,source.notes(),List.of(event("held",6,NativeCommand.MODULATION,1,1,1,4)),"RESET");
        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(held,12))).getMessage().contains("attack"));
        var silent=source("FM",12,List.of(),List.of(event("silent",0,NativeCommand.MODULATION,1,1,1,4)),"RESET");
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(silent,12)));
    }

    @Test void strictVersionTwoRejectsUnknownCommandsMissingFieldsArityAndRawByteErrors() {
        var source=source("FM",12,List.of(new NoteEvent("n",0,12,60)),List.of(event("pan",6,NativeCommand.PAN,128)),"RESET");
        String json=ProjectFormat.write(project(source,12));
        for(String bad:List.of(json.replace("\"commands\": [","\"events\": ["),
                json.replace("\"repeatState\": \"RESET\"","\"repeatState\": \"UNKNOWN\""),
                json.replace("\"command\": \"PAN\"","\"command\": \"RAW\""),
                json.replace("\"command\": \"PAN\"","\"command\": \"MODULATION_OFF\""),
                json.replace("\"command\": \"PAN\"","\"command\": \"PSG_VOLUME\""),
                json.replace("\"tick\": 6","\"tick\": 12"),json.replace("128\n", "256\n"),
                json.replace("\"arguments\": [","\"arguments\": null, \"unknown\": ["),
                json.replace("\"tick\": 6,","\"tick\": 6, \"tick\": 6,"),
                json.replace("\"command\": \"PAN\"","\"command\": null"),
                json.replace("\"repeatState\": \"RESET\"", "\"repeatState\": null"))) {
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(bad),bad);
        }
        for(CommandEvent invalid:List.of(event("x",-1,NativeCommand.DETUNE,1),event("x",0,NativeCommand.DETUNE,-1),
                event("x",0,NativeCommand.DETUNE,256),event("x",0,NativeCommand.MODULATION,1,2,3))) {
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(source("FM",12,source.notes(),List.of(invalid),"RESET"),12)));
        }
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(source("FM",12,source.notes(),List.of(event("dup",0,NativeCommand.PAN,0),event("dup",6,NativeCommand.PAN,0)),"RESET"),12)));
        var v1=ProjectFormatTest.fixture();
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.write(new StudioProject(1,v1.name(),v1.mode(),v1.ticksPerBeat(),v1.tempo(),v1.voices(),List.of(source),v1.clips())));
    }

    @Test void immutableCommandsAndCatalogueCoverExactNativeWidths() {
        var args=new ArrayList<>(List.of(128)); var event=new CommandEvent("pan",0,NativeCommand.PAN,args); args.clear();
        assertEquals(List.of(128),event.arguments()); assertThrows(UnsupportedOperationException.class,()->event.arguments().clear());
        var commands=new ArrayList<>(List.of(event)); var source=source("FM",12,List.of(),commands,"RESET"); commands.clear();
        assertEquals(1,source.commands().size()); assertThrows(UnsupportedOperationException.class,()->source.commands().clear());
        for(NativeCommand command:NativeCommand.values()) {
            assertFalse(command.label().isBlank()); assertFalse(command.help().isBlank());
            assertTrue(command.opcode()>=0xE0); assertEquals(command.argumentCount(),command.defaultArguments().size());
            assertFalse(command.supports("DAC")); assertTrue(command.supports("FM")||command.supports("PSG"));
            assertThrows(UnsupportedOperationException.class,()->command.defaultArguments().add(0));
        }
        assertEquals(4,NativeCommand.MODULATION.argumentCount()); assertEquals(0,NativeCommand.MODULATION_OFF.argumentCount());
    }

    @Test void everyCatalogueCommandRoundTripsRawByteExtremesInBothSupportedTrackTypes() {
        var extraVoice = NativeFmVoice.initial("other", "Other");
        var volume = new Envelope("volume", "Volume", Envelope.PSG_VOLUME, List.of(0, 0x83));
        var modulation = new Envelope("mod", "Modulation", Envelope.MODULATION, List.of(0xFF, 0x82, 0));
        for(String type:List.of("FM","PSG")) {
            List<CommandEvent> commands=new ArrayList<>();
            for(NativeCommand command:NativeCommand.values()) if(command.supports(type)) {
                String asset = switch (command.asset()) {
                    case NONE -> null;
                    case VOICE -> extraVoice.id();
                    case PSG_VOLUME -> volume.id();
                    case MODULATION -> modulation.id();
                };
                commands.add(new CommandEvent(command.name(),0,command,
                        java.util.Collections.nCopies(command.argumentCount(),255),asset));
            }
            var p=new StudioProject(4,"Commands","S3K",24,128,
                    List.of(ProjectCompilerTest.VOICE,extraVoice),
                    List.of(source(type,12,List.of(new NoteEvent("n",0,12,60)),commands,"RESET")),
                    List.of(new Clip("c","s",type+"1",0,12)),List.of(volume,modulation),List.of());
            assertEquals(p,ProjectFormat.read(ProjectFormat.write(p)));
            byte[] compiled=track(p); assertEquals(0xF2,compiled[compiled.length-1]&255);
            assertThrows(IllegalArgumentException.class,()->ProjectEdits.deleteEnvelope(p,"mod"));
            if(type.equals("FM")) assertThrows(IllegalArgumentException.class,()->ProjectEdits.deleteVoice(p,"other"));
        }
        var empty=project(source("FM",12,List.of(),List.of(),"RESET"),12);
        String json=ProjectFormat.write(empty);
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(json.replace("\"commands\": [],", "")));
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(json.replaceAll(",\\s*\"repeatState\": \"RESET\"", "")));
    }

    @Test void commandEditsUpgradeOnlyNewFeaturesAndPreserveDataThroughEveryCopyPath() {
        var legacy=ProjectFormatTest.fixture();
        assertEquals(1,ProjectEdits.updateNote(legacy,"s",new NoteEvent("n",0,10,60)).formatVersion());
        assertEquals(1,ProjectEdits.setSourceSettings(legacy,"s","v",120).formatVersion());
        assertEquals(1,ProjectEdits.setRepeatState(legacy,"s","RESET").formatVersion());
        assertEquals(2,ProjectEdits.setRepeatState(legacy,"s","CARRY").formatVersion());
        var p=ProjectEdits.addCommand(legacy,"s",event("a",6,NativeCommand.PAN,128));
        p=ProjectEdits.addCommand(p,"s",event("b",0,NativeCommand.DETUNE,1));
        p=ProjectEdits.addCommand(p,"s",event("c",6,NativeCommand.FM_VOLUME,1));
        assertEquals(2,p.formatVersion());
        p=ProjectEdits.moveCommand(p,"s","c",-1);
        assertEquals(List.of("a","c","b"),p.sequences().getFirst().commands().stream().map(CommandEvent::id).toList());
        p=ProjectEdits.moveCommand(p,"s","a",-1); // edge is a no-op
        p=ProjectEdits.updateCommand(p,"s",event("a",6,NativeCommand.PAN,64));
        var expected=p.sequences().getFirst().commands();
        p=ProjectEdits.updateNote(p,"s",new NoteEvent("n",0,10,64));
        p=ProjectEdits.addNote(p,"s",new NoteEvent("new",12,6,67));
        p=ProjectEdits.deleteNote(p,"s","new");
        p=ProjectEdits.moveClip(p,"c","FM2",12); p=ProjectEdits.resizeClip(p,"c",24);
        p=ProjectEdits.addClip(p,new Clip("d","s","FM3",0,24)); p=ProjectEdits.replaceClip(p,new Clip("d","s","FM3",24,24)); p=ProjectEdits.deleteClip(p,"d");
        p=ProjectEdits.setSourceSettings(p,"s","v",110); p=ProjectEdits.setRepeatState(p,"s","CARRY");
        assertEquals(expected,p.sequences().getFirst().commands()); assertEquals("CARRY",p.sequences().getFirst().repeatState());
        var unchanged=p;
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.moveCommand(unchanged,"s","a",0));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.updateCommand(unchanged,"s",event("missing",0,NativeCommand.DETUNE,1)));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.deleteCommand(unchanged,"s","missing"));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.addCommand(unchanged,"s",event("a",0,NativeCommand.PAN,0)));
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.setSourceSettings(unchanged,"s","missing",100));
        p=ProjectEdits.deleteCommand(p,"s","a"); assertEquals(2,p.sequences().getFirst().commands().size());
        assertEquals(3,unchanged.sequences().getFirst().commands().size());
    }

    @Test void billionTickVersionTwoSourcesStillFailAtTheFixedBankLimit() {
        var source=source("FM",Integer.MAX_VALUE,List.of(new NoteEvent("long",0,Integer.MAX_VALUE,60)),List.of(event("start",0,NativeCommand.PAN,128)),"CARRY");
        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(project(source,Integer.MAX_VALUE))).getMessage().contains("32 KiB"));
        var repeated=source("FM",1,List.of(),List.of(event("every",0,NativeCommand.DETUNE,1)),"CARRY");
        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(project(repeated,Integer.MAX_VALUE))).getMessage().contains("32 KiB"));
    }

    @Test void bankedVoiceReferencesAreRejectedOnlyForVersionTwoReferencedSources() {
        List<Voice> voices=new ArrayList<>();
        for(int i=0;i<=128;i++) voices.add(new Voice("v"+i,"Voice "+i,ProjectCompilerTest.VOICE.bytes()));
        var source=new Sequence("s","Banked","FM",12,"v128",100,List.of(new NoteEvent("n",0,12,60)));
        var clips=List.of(new Clip("c","s","FM1",0,12));
        var v2=new StudioProject(2,"Voices","S3K",24,128,voices,List.of(source),clips);
        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(v2)).getMessage().contains("banked voice references need future asset support"));
        var v1=new StudioProject(1,v2.name(),v2.mode(),v2.ticksPerBeat(),v2.tempo(),voices,List.of(source),clips);
        byte[] legacy=ProjectCompiler.compile(v1); assertEquals(128,legacy[word(legacy,10)+1]&255);
        var low=ProjectEdits.setSourceSettings(v2,"s","v0",100);
        byte[] bank=ProjectCompiler.compile(low); assertEquals(0,bank[word(bank,10)+1]);
        assertEquals(129*25,bank.length-word(bank,0));
        var unused=new Sequence("unused","Unused banked","FM",12,"v128",100,List.of());
        assertDoesNotThrow(()->ProjectCompiler.compile(new StudioProject(2,v2.name(),v2.mode(),v2.ticksPerBeat(),v2.tempo(),voices,List.of(low.sequences().getFirst(),unused),clips)));
    }
}
