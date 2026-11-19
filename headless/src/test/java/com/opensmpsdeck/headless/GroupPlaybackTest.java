package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroupPlaybackTest {
    @TempDir Path folder;

    @Test void repeatedGroupAcrossConstantTempoSong() throws Exception {
        compare("repeated", List.of(a()), new GroupPlacement("song-a","a",5,72),
                new int[]{5,29,53}, new int[]{24,24,24});
    }

    @Test void partialFinalRepeatCutsBothResetAndCarryChildren() throws Exception {
        compare("partial", List.of(a()), new GroupPlacement("song-a","a",5,60),
                new int[]{5,29,53}, new int[]{24,24,12});
    }

    @Test void twoLevelsRepeatAndTrimAtTheirOwnBoundaries() throws Exception {
        compare("two", List.of(a(),b()), new GroupPlacement("song-b","b",7,104),
                new int[]{10,34,62,86}, new int[]{24,15,24,15});
    }

    @Test void threeLevelsClipFromOutsideIn() throws Exception {
        var c = new GroupSequence("c","Section",91,List.of(),List.of(new GroupPlacement("b-part","b",4,78)));
        compare("three", List.of(a(),b(),c),new GroupPlacement("song-c","c",7,149),
                new int[]{14,38,66,105,129}, new int[]{24,15,23,24,15});
    }

    private void compare(String name, List<GroupSequence> groups, GroupPlacement placement,
                         int[] origins, int[] budgets) throws Exception {
        var voice = DemoProject.create().voices().getFirst();
        var reset = new Sequence("reset","Reset lead","FM",8,voice.id(),110,
                List.of(new NoteEvent("attack",0,8,69)),
                List.of(new CommandEvent("rise",0,NativeCommand.TRANSPOSE_ADD,List.of(1))),"RESET");
        var carry = new Sequence("carry","Carry answer","PSG",8,null,100,
                List.of(new NoteEvent("attack",0,8,76)),
                List.of(new CommandEvent("rise",0,NativeCommand.TRANSPOSE_ADD,List.of(2))),"CARRY");
        var drums = new Sequence("drums","Drum","DAC",8,null,127,List.of(),List.of(),"RESET",null,null,
                List.of(new SampleTrigger("hit",0,"kick")));
        var sample = DacSample.of("kick","Kick",12,new byte[]{(byte)128,(byte)230,(byte)180,85,118,(byte)128});
        var clips = new ArrayList<Clip>();
        var end = new Clip("coda","reset","FM1",204,16);
        List<Clip> songClips = name.equals("repeated") ? List.of() : List.of(end);
        clips.addAll(songClips);
        for (int i = 0; i < origins.length; i++) {
            clips.add(new Clip("lead-"+i,"reset","FM1",origins[i]+1,Math.min(18,budgets[i]-1)));
            clips.add(new Clip("answer-"+i,"carry","PSG1",origins[i]+2,Math.min(18,budgets[i]-2)));
            clips.add(new Clip("hit-"+i,"drums","DAC",origins[i]+4,Math.min(16,budgets[i]-4)));
        }
        var flat = new StudioProject(4,"Flat","S3K",8,48,List.of(voice),List.of(reset,carry,drums),
                clips,List.of(),List.of(sample));
        var nested = new StudioProject(6,"Grouped","S3K",8,48,flat.voices(),flat.sequences(),songClips,
                List.of(),flat.samples(),null,List.of(),List.of(),4,groups,List.of(placement));
        assertEquals(ProjectRenderTiming.presentationCount(flat),ProjectRenderTiming.presentationCount(nested));
        assertEquals(ProjectCompiler.dacEndTick(flat),ProjectCompiler.dacEndTick(nested));
        var expected = ProjectCompiler.compileWithAssets(flat);
        var actual = ProjectCompiler.compileWithAssets(nested);
        assertArrayEquals(expected.song(),actual.song());
        Path expectedWav=folder.resolve(name+"-flat.wav"), actualWav=folder.resolve(name+"-grouped.wav");
        WaveFile.render(expected,expectedWav,340);
        WaveFile.render(actual,actualWav,340);
        assertArrayEquals(Files.readAllBytes(expectedWav),Files.readAllBytes(actualWav));
    }

    private static GroupSequence a() {
        return new GroupSequence("a","Phrase",24,List.of(new Clip("lead","reset","FM1",1,18),
                new Clip("answer","carry","PSG1",2,18),new Clip("drum","drums","DAC",4,16)),List.of());
    }

    private static GroupSequence b() {
        return new GroupSequence("b","Fill",52,List.of(),List.of(new GroupPlacement("a-part","a",3,39)));
    }

    @Test void cliCanCreateNestPlaceValidateAndUngroupWithConflictRefusal() throws Exception {
        Path file = folder.resolve("song.json");
        var original = DemoProject.create();
        ProjectFiles.save(file,original,null);
        assertCli(0,"group","create",file.toString(),"phrase","Phrase","clip-a1","clip-bass");
        assertCli(0,"group","create",file.toString(),"lead","Lead","clip-a1","--in","phrase");
        String before = Files.readString(file);
        var conflict = assertCli(2,"group","place",file.toString(),"lead","overlap","0","96");
        assertTrue(conflict.contains("lane conflict on FM1"),conflict);
        assertEquals(before, Files.readString(file));
        assertCli(0,"group","place",file.toString(),"phrase","second","384","384");
        assertCli(0,"validate",file.toString());
        assertCli(0,"group","ungroup",file.toString(),"second");
        var ungrouped = ProjectFiles.load(file).project();
        assertTrue(ungrouped.groupPlacements().stream().anyMatch(p -> p.groupId().equals("lead")));
        // Bypass the checked write to simulate a third-party edit.
        Files.writeString(file,Files.readString(file).replace("\"startTick\": 384","\"startTick\": 0"));
        assertTrue(assertCli(2,"validate",file.toString()).contains("lane conflict on"));
    }

    private static String assertCli(int expected, String... args) {
        var buffer = new ByteArrayOutputStream();
        try(var output = new PrintStream(buffer)) {
            int status = Main.run(args,output,output);
            assertEquals(expected,status,buffer.toString());
        }
        return buffer.toString();
    }
}
