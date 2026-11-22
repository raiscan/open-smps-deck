package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectCompilerTest {
    static final Voice VOICE = new Voice("v", "Native slots", List.of(7,1,2,3,4,31,30,29,28,0,1,2,3,4,5,6,7,15,14,13,12,32,40,48,56));
    static StudioProject project(List<Sequence> sequences, List<Clip> clips) {
        return new StudioProject(1,"Fixture","S3K",24,128,List.of(VOICE),sequences,clips, TargetGame.S3K, null);
    }
    static Sequence sequence(String id, String type, int length, List<NoteEvent> notes) {
        return new Sequence(id,id,type,length,type.equals("FM") ? "v" : null,100,notes);
    }
    static int word(byte[] b, int at) { return (b[at]&255) | (b[at+1]&255)<<8; }
    static byte[] bytes(int... values) { byte[] b=new byte[values.length]; for(int i=0;i<values.length;i++) b[i]=(byte)values[i]; return b; }

    @Test void sparsePhysicalLanesAndNativeVoiceBytesArePreservedExactly() {
        var fm = sequence("fm","FM",12,List.of(new NoteEvent("a",0,12,12)));
        var psg = sequence("psg","PSG",12,List.of(new NoteEvent("b",0,12,106)));
        byte[] b = ProjectCompiler.compile(project(List.of(fm,psg),List.of(new Clip("c","fm","FM3",0,12),new Clip("d","psg","PSG2",0,12))));
        assertEquals(4,b[2]); assertEquals(2,b[3]); assertEquals(1,b[4]); assertEquals(128,b[5]&255);
        assertEquals(34,word(b,6)); // complete DAC/FM1/FM2/FM3/PSG1/PSG2 header
        assertEquals(0xF2,b[word(b,6)]&255); assertEquals(0xF2,b[word(b,10)]&255);
        assertEquals(0xF2,b[word(b,14)]&255); assertEquals(0xF2,b[word(b,22)]&255);
        assertArrayEquals(bytes(0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,0x81,12,0xF2),Arrays.copyOfRange(b,word(b,18),word(b,22)));
        assertArrayEquals(bytes(0xE4,100,0xED,0x40,0xC7,12,0xF2),Arrays.copyOfRange(b,word(b,28),word(b,0)));
        assertArrayEquals(VOICE.bytes().stream().collect(java.io.ByteArrayOutputStream::new,(o,n)->o.write(n), (a,c)->a.writeBytes(c.toByteArray())).toByteArray(),Arrays.copyOfRange(b,word(b,0),b.length));
    }
    @Test void midiMiddleCUsesTheNativePitchOriginForEachChip() {
        var fm=sequence("fm","FM",12,List.of(new NoteEvent("f",0,12,60)));
        var psg=sequence("psg","PSG",12,List.of(new NoteEvent("p",0,12,60)));
        byte[] b=ProjectCompiler.compile(project(List.of(fm,psg),List.of(new Clip("f","fm","FM1",0,12),new Clip("p","psg","PSG1",0,12))));
        assertArrayEquals(bytes(0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,0xB1,12,0xF2),Arrays.copyOfRange(b,word(b,10),word(b,14)));
        assertArrayEquals(bytes(0xE4,100,0xED,0x40,0x99,12,0xF2),Arrays.copyOfRange(b,word(b,14),word(b,0)));
    }
    @Test void psgUsableTableEndpointsAreEncodedWithoutClampingOrZeroPeriods() {
        var psg=sequence("psg","PSG",24,List.of(new NoteEvent("low",0,12,45),new NoteEvent("high",12,12,117)));
        byte[] b=ProjectCompiler.compile(project(List.of(psg),List.of(new Clip("p","psg","PSG1",0,24))));
        assertArrayEquals(bytes(0xE4,100,0xED,0x40,0x8A,12,0xD2,12,0xF2),Arrays.copyOfRange(b,word(b,10),word(b,0)));
    }
    @Test void repetitionsResetAbsoluteStateAndTruncateFinalNotesAndRests() {
        var s=sequence("s","FM",10,List.of(new NoteEvent("n",2,6,60)));
        byte[] b=ProjectCompiler.compile(project(List.of(s),List.of(new Clip("c","s","FM1",3,25))), ProjectCompiler.Encoding.LINEAR);
        int start=word(b,10), end=word(b,0);
        assertArrayEquals(bytes(0x80,3,
                0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,0x80,2,0xB1,6,0x80,2,
                0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,0x80,2,0xB1,6,0x80,2,
                0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,0x80,2,0xB1,3,0xF2),Arrays.copyOfRange(b,start,end));
    }
    @Test void longNotesAreSustainedAndLongRestsSplitWithoutWrapping() {
        var s=sequence("s","FM",600,List.of(new NoteEvent("n",255,300,60)));
        byte[] b=ProjectCompiler.compile(project(List.of(s),List.of(new Clip("c","s","FM1",0,600))));
        assertArrayEquals(bytes(0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,
                0x80,127,0x80,127,0x80,1,0xB1,127,0xE7,0xB1,127,0xE7,0xB1,46,0x80,45,0xF2),Arrays.copyOfRange(b,word(b,10),word(b,0)));
    }
    @Test void hugeGapsAndRepeatsFailWithinOneBankWithoutExpandingTheSource() {
        var s=sequence("s","FM",1,List.of(new NoteEvent("n",0,1,60)));
        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(project(List.of(s),List.of(new Clip("c","s","FM1",0,Integer.MAX_VALUE))))).getMessage().contains("32 KiB"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(project(List.of(s),List.of(new Clip("c","s","FM1",Integer.MAX_VALUE-1,1))))).getMessage().contains("32 KiB"));
    }
    @Test void adjacentWholeEventsUseOneCountedLoopWithABankRelativeTarget() {
        var phrase = sequence("s", "FM", 10, List.of(new NoteEvent("n", 0, 10, 60)));
        var song = project(List.of(phrase), List.of(new Clip("c", "s", "FM1", 0, 100)));
        byte[] bank = ProjectCompiler.compile(song);
        int start = word(bank, 10), end = word(bank, 0);
        assertArrayEquals(bytes(0xEF,0,0xE4,100,0xE0,0xC0,0xED,0x40,0xB1,10,
                0xF7,0,10,start,0,0xF2), Arrays.copyOfRange(bank,start,end));
        assertArrayEquals(bank, ProjectCompiler.compile(song));
    }

    @Test void exactBankBoundaryIsAcceptedButOneMoreEventIsRejected() {
        // Header14 + DAC1 + reset8 + two bytes per note + stop1 + voice25.
        int count=(32768-49)/2;
        var notes=new java.util.ArrayList<NoteEvent>();
        for(int i=0;i<count;i++) notes.add(new NoteEvent("n"+i,i,1,60));
        var s=sequence("s","FM",count,notes);
        byte[] b=ProjectCompiler.compile(project(List.of(s),List.of(new Clip("c","s","FM1",0,count))), ProjectCompiler.Encoding.LINEAR);
        assertEquals(32767,b.length);
        var exactNotes=new java.util.ArrayList<NoteEvent>();
        exactNotes.add(new NoteEvent("n0",0,128,60));
        for(int i=1;i<count-1;i++) exactNotes.add(new NoteEvent("n"+i,i+127,1,60));
        var exact=sequence("s","FM",count+126,exactNotes);
        byte[] full=ProjectCompiler.compile(project(List.of(exact),List.of(new Clip("c","s","FM1",0,count+126))), ProjectCompiler.Encoding.LINEAR);
        assertEquals(32768,full.length); assertEquals(full.length-25,word(full,0));
        notes.add(new NoteEvent("last",count,1,60));
        var larger=sequence("s","FM",count+1,notes);
        assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(project(List.of(larger),List.of(new Clip("c","s","FM1",0,count+1))), ProjectCompiler.Encoding.LINEAR));
    }

    @Test void anArrangementAboveTheLinearLimitCanFitWithCountedLoops() {
        var phrase = sequence("s", "FM", 1, List.of(new NoteEvent("n", 0, 1, 60)));
        var song = project(List.of(phrase), List.of(new Clip("c", "s", "FM1", 0, 20_000)));
        var failure = assertThrows(ProjectCompiler.BankLimitException.class,
                () -> ProjectCompiler.compile(song, ProjectCompiler.Encoding.LINEAR));
        assertEquals(200_041, failure.compiledBytes());
        assertTrue(ProjectCompiler.compile(song).length < 1_500);
    }

    @Test void compactBankLimitReportsTotalEvenBeyondTheFirstTrack() {
        int duration = 127 * 20_000;
        var s = new Sequence("s", "s", "FM", duration, "v", 100,
                List.of(new NoteEvent("long", 0, duration, 60)));
        var song = new StudioProject(2, "Two long held notes", "S3K", 24, 0, List.of(VOICE), List.of(s),
                List.of(new Clip("a", "s", "FM1", 0, duration), new Clip("b", "s", "FM2", 0, duration)), TargetGame.S3K, null);
        var error = assertThrows(ProjectCompiler.BankLimitException.class, () -> ProjectCompiler.compile(song));
        // Header, DAC stop, two channels (reset, attack, held durations, stop), voice.
        assertEquals(18 + 1 + 2 * (13 + 2 * 20_000 + 1) + 25, error.compiledBytes());
        assertEquals(32_768, error.limitBytes());
        assertTrue(error.getMessage().contains("80072 bytes"));
        assertTrue(error.getMessage().contains("32,768 bytes"));
        assertTrue(error.getMessage().contains("Loops and calls were already applied"));
        assertTrue(error.getMessage().contains("reduce layering, voices, or notes"));
    }

    @Test void refusesSharingAcrossRelativeOrDifferentAbsoluteState() {
        for (var command : List.of(NativeCommand.TRANSPOSE_ADD, NativeCommand.FM_VOLUME,
                NativeCommand.TRANSPOSE, NativeCommand.VOLUME)) {
            var notes = new java.util.ArrayList<NoteEvent>();
            for (int i = 0; i < 15; i++) notes.add(new NoteEvent("n" + i, i, 1, 60 + i % 5));
            var commands = List.of(new CommandEvent("change1", 5, command, List.of(66)),
                    new CommandEvent("change2", 10, command, List.of(67)));
            var s = new Sequence("s","s","FM",15,"v",100,notes,commands,"CARRY");
            var song = new StudioProject(2,"State","S3K",24,0,List.of(VOICE),List.of(s),
                    List.of(new Clip("c","s","FM1",0,15)), TargetGame.S3K, null);
            assertArrayEquals(ProjectCompiler.compile(song, ProjectCompiler.Encoding.LINEAR),
                    ProjectCompiler.compile(song), command.name());
        }
    }

    @Test void refusesSharingADurationOnlyContinuationOrTieAcrossABoundary() {
        var notes = List.of(new NoteEvent("a", 0, 900, 60), new NoteEvent("b", 900, 900, 61),
                new NoteEvent("c", 1800, 900, 60));
        var s = new Sequence("s","s","FM",2700,"v",100,notes,List.of(),"CARRY");
        var song = new StudioProject(2,"Ties","S3K",24,0,List.of(VOICE),List.of(s),
                List.of(new Clip("c","s","FM1",0,2700)), TargetGame.S3K, null);
        assertArrayEquals(ProjectCompiler.compile(song, ProjectCompiler.Encoding.LINEAR), ProjectCompiler.compile(song));
    }
}
