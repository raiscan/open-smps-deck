package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectCompilerTest {
    static final Voice VOICE = new Voice("v", "Native slots", List.of(7,1,2,3,4,31,30,29,28,0,1,2,3,4,5,6,7,15,14,13,12,32,40,48,56));
    static StudioProject project(List<Sequence> sequences, List<Clip> clips) {
        return new StudioProject(1,"Fixture","S3K",24,128,List.of(VOICE),sequences,clips);
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
        byte[] b=ProjectCompiler.compile(project(List.of(s),List.of(new Clip("c","s","FM1",3,25))));
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
    @Test void exactBankBoundaryIsAcceptedButOneMoreEventIsRejected() {
        // Header14 + DAC1 + reset8 + two bytes per note + stop1 + voice25.
        int count=(32768-49)/2;
        var notes=new java.util.ArrayList<NoteEvent>();
        for(int i=0;i<count;i++) notes.add(new NoteEvent("n"+i,i,1,60));
        var s=sequence("s","FM",count,notes);
        byte[] b=ProjectCompiler.compile(project(List.of(s),List.of(new Clip("c","s","FM1",0,count))));
        assertEquals(32767,b.length);
        var exactNotes=new java.util.ArrayList<NoteEvent>();
        exactNotes.add(new NoteEvent("n0",0,128,60));
        for(int i=1;i<count-1;i++) exactNotes.add(new NoteEvent("n"+i,i+127,1,60));
        var exact=sequence("s","FM",count+126,exactNotes);
        byte[] full=ProjectCompiler.compile(project(List.of(exact),List.of(new Clip("c","s","FM1",0,count+126))));
        assertEquals(32768,full.length); assertEquals(full.length-25,word(full,0));
        notes.add(new NoteEvent("last",count,1,60));
        var larger=sequence("s","FM",count+1,notes);
        assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(project(List.of(larger),List.of(new Clip("c","s","FM1",0,count+1)))));
    }
}
