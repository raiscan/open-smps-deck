package com.opensmpsdeck.project;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GroupArrangementTest {
    @Test void groupAndUngroupPreserveClipsAndLegacyCompilation() {
        StudioProject original = DemoProject.create();
        var grouped = GroupEdits.create(original, null, "chorus", "Chorus",
                Set.copyOf(original.clips().stream().map(Clip::id).toList()));
        assertEquals(6, grouped.formatVersion());
        assertEquals(original.formatVersion(), grouped.arrangementVersion());
        assertArrayEquals(ProjectCompiler.compile(original), ProjectCompiler.compile(grouped));
        assertEquals(grouped, ProjectFormat.read(ProjectFormat.write(grouped)));
        var ungrouped = GroupEdits.ungroup(grouped, null, "chorus-placement");
        assertEquals(Set.copyOf(original.clips()), Set.copyOf(ungrouped.clips()));
        assertArrayEquals(ProjectCompiler.compile(original), ProjectCompiler.compile(ungrouped));
    }

    @Test void reportsLongCyclesIncludingUnusedDefinitions() {
        var definitions = new ArrayList<GroupSequence>();
        for (int i=0;i<6;i++) definitions.add(group("g"+i,"g"+((i+1)%6)));
        var failure=assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(definitions,List.of(),List.of())));
        assertTrue(failure.getMessage().contains("cycle: g0 → g1 → g2 → g3 → g4 → g5 → g0"));
    }

    @Test void acceptsFourLevelsAndRejectsFiveWithSharedDefinitions() {
        var definitions=new ArrayList<>(List.of(group("g1",null),group("g2","g1"),group("g3","g2"),group("g4","g3")));
        assertDoesNotThrow(()->ProjectFormat.validate(project(definitions,List.of(new GroupPlacement("four","g4",0,24)),List.of())));
        definitions.add(group("g5","g4"));
        var failure=assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(definitions,List.of(),List.of())));
        assertTrue(failure.getMessage().contains("depth exceeds 4 at 'g5'"));
    }

    @Test void detectsNestedConflictsButLeavesGapsAndTrimmedLanesAvailable() {
        var lead=new Clip("lead","seq-lead-a","FM1",4,6);
        var bass=new Clip("bass","seq-bass","FM3",15,9);
        var inner=new GroupSequence("inner","Inner",24,List.of(lead,bass),List.of());
        var definitions=List.of(inner,group("outer","inner"));
        var nested=new GroupPlacement("nested","outer",10,32);
        // The second repeat plays FM1 only through tick 41. FM3 is omitted there.
        var gap=new Clip("gap","seq-lead-a","FM1",20,18);
        var trimmed=new Clip("trimmed","seq-bass","FM3",46,8);
        assertDoesNotThrow(()->ProjectFormat.validate(project(definitions,List.of(nested),List.of(gap,trimmed))));
        var conflict=new Clip("overlap","seq-lead-a","FM1",40,4);
        var failure=assertThrows(IllegalArgumentException.class,
                ()->ProjectFormat.validate(project(definitions,List.of(nested),List.of(conflict))));
        assertTrue(failure.getMessage().contains("lane conflict on FM1 at tick 40"));
        assertTrue(failure.getMessage().contains("nested[2]/inner-part[1]/lead"));
    }

    @Test void linkedEditChecksEveryPlacementAndUnusedSourceScopes() {
        var lead=new Clip("lead","seq-lead-a","FM1",0,8);
        var group=new GroupSequence("lead","Phrase",24,List.of(lead),List.of());
        var placements=List.of(new GroupPlacement("first","lead",0,24),new GroupPlacement("second","lead",24,24));
        StudioProject p=project(List.of(group),placements,List.of(new Clip("song","seq-lead-a","FM1",39,4)));
        var failure=assertThrows(IllegalArgumentException.class,
                ()->GroupEdits.replace(p,"lead",new Clip("lead","seq-lead-a","FM1",0,16)));
        assertTrue(failure.getMessage().contains("second[1]/lead"));
        assertEquals(List.of(lead),p.groupSequences().getFirst().clips());
        assertThrows(IllegalArgumentException.class,
                ()->GroupEdits.place(p,"lead",new Clip("sibling","seq-lead-a","FM1",6,8)));
    }

    @Test void rejectsUnboundedExpansionAndSkipsSilentGroups() {
        var child=new Clip("lead","seq-lead-a","FM1",0,1);
        var source=new GroupSequence("source","Phrase",1,List.of(child),List.of());
        var instance=new GroupPlacement("song","source",0,Integer.MAX_VALUE);
        var failure=assertThrows(IllegalArgumentException.class,
                ()->ProjectFormat.validate(project(List.of(source),List.of(instance),List.of())));
        assertTrue(failure.getMessage().contains("expansion exceeds"));
        assertDoesNotThrow(()->ProjectFormat.validate(project(List.of(group("source",null)),List.of(instance),List.of())));
    }

    @Test void ungroupingRepeatedPartialInstancePreservesChildLinksAndSound() {
        var a=new GroupSequence("a","Phrase",24,List.of(new Clip("lead","seq-lead-a","FM1",4,16)),List.of());
        var b=group("b","a");
        var p=project(List.of(a,b),List.of(new GroupPlacement("song","b",4,38)),List.of());
        var once=GroupEdits.ungroup(p,null,"song");
        assertEquals(List.of(24,14),once.groupPlacements().stream().map(GroupPlacement::lengthTicks).toList());
        assertArrayEquals(ProjectCompiler.compile(p),ProjectCompiler.compile(once));
        var flat=GroupEdits.ungroup(GroupEdits.ungroup(once,null,"a-part"),null,"song-2-a-part");
        assertEquals(List.of(16,10),flat.clips().stream().map(Clip::lengthTicks).toList());
        assertArrayEquals(ProjectCompiler.compile(p),ProjectCompiler.compile(flat));
    }

    @TestFactory List<DynamicTest> allExamplesStillLoadCompileAndRoundTrip() throws Exception {
        try (var files=Files.walk(Path.of("../examples"))) {
            return files.filter(Files::isRegularFile).filter(f->f.toString().endsWith(".smps.json")).sorted().map(file->
                DynamicTest.dynamicTest(file.getFileName().toString(),()->{
                    StudioProject project=ProjectFiles.load(file).project();
                    var reopened=ProjectFormat.read(ProjectFormat.write(project));
                    assertEquals(project,reopened);
                    assertArrayEquals(ProjectCompiler.compile(project),ProjectCompiler.compile(reopened));
                })).toList();
        }
    }

    private static GroupSequence group(String id,String child) {
        return new GroupSequence(id,id,24,List.of(),child==null ? List.of()
                : List.of(new GroupPlacement(child+"-part",child,0,24)));
    }

    private static StudioProject project(List<GroupSequence> groups,List<GroupPlacement> placements,List<Clip> clips) {
        var demo=DemoProject.create();
        return new StudioProject(6,demo.name(),demo.mode(),demo.ticksPerBeat(),demo.tempo(),demo.voices(),
                demo.sequences(),clips,List.of(),List.of(),null,List.of(),List.of(),1,groups,placements);
    }
}
