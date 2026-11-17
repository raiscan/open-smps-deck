package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Actual scene/event tests. Run under an isolated display with -Dstudio.ui.test=true. */
class StudioWindowTest {
    @TempDir Path folder;
    StudioWindow window;Stage stage;Path file;
    @BeforeAll static void toolkit() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("studio.ui.test"),"UI smoke requires an explicitly isolated display");
        var ready=new CountDownLatch(1);
        Platform.startup(()->{Platform.setImplicitExit(false);ready.countDown();});
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    @BeforeEach void open() throws Exception {
        file=folder.resolve("shared.smps.json");ProjectFiles.save(file,DemoProject.create(),null);
        fx(()->{stage=new Stage();window=new StudioWindow(stage,file);stage.show();stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();return null;});
    }
    @AfterEach void close() throws Exception {if(window!=null)fx(()->{window.close();stage.close();return null;});}

    @Test void mouseMoveRepeatAndNoteEditsSaveBackToSharedSource() throws Exception {
        // Move the first lead clip to empty FM2 using real canvas mouse events.
        fx(()->{
            var canvas=window.timeline.canvas;
            mouse(canvas,MouseEvent.MOUSE_PRESSED,TimelineView.GUTTER+20,TimelineView.RULER+20,true);
            mouse(canvas,MouseEvent.MOUSE_DRAGGED,TimelineView.GUTTER+20+36*window.timeline.scale,TimelineView.RULER+TimelineView.ROW+20,true);
            mouse(canvas,MouseEvent.MOUSE_RELEASED,TimelineView.GUTTER+20+36*window.timeline.scale,TimelineView.RULER+TimelineView.ROW+20,false);
            Clip c=clip("clip-a1");assertEquals("FM2",c.lane());assertEquals(36,c.startTick());
            // Extend by half a source. This repeats, rather than stretches, the notes.
            double edge=TimelineView.GUTTER+(c.startTick()+c.lengthTicks())*window.timeline.scale-3;
            double y=TimelineView.RULER+TimelineView.ROW+20;
            mouse(canvas,MouseEvent.MOUSE_PRESSED,edge,y,true);
            mouse(canvas,MouseEvent.MOUSE_DRAGGED,edge+48*window.timeline.scale,y,true);
            mouse(canvas,MouseEvent.MOUSE_RELEASED,edge+48*window.timeline.scale,y,false);
            assertEquals(144,clip("clip-a1").lengthTicks());
            // Select a note in the linked source and apply a precise pitch edit.
            var note=window.document().project().sequences().getFirst().notes().getFirst();
            double noteY=PianoRollView.HEADER+(window.piano.topPitch-note.pitch())*PianoRollView.NOTE_ROW+5;
            mouse(window.piano.canvas,MouseEvent.MOUSE_PRESSED,PianoRollView.KEYBOARD+12,noteY,true);
            mouse(window.piano.canvas,MouseEvent.MOUSE_RELEASED,PianoRollView.KEYBOARD+12,noteY,false);
            ((TextField)window.scene().lookup("#note-pitch")).setText("73");
            ((Button)window.scene().lookup("#apply-note")).fire();
            assertEquals(73,window.document().project().sequences().getFirst().notes().getFirst().pitch());
            assertTrue(window.document().dirty());window.document().save();return null;
        });
        LoadedProject saved=ProjectFiles.load(file);
        assertEquals(144,saved.project().clips().stream().filter(c->c.id().equals("clip-a1")).findFirst().orElseThrow().lengthTicks());
        assertEquals(73,saved.project().sequences().getFirst().notes().getFirst().pitch());
    }

    @Test void externalReplaceRefreshesWithoutLosingSelectionOrViewportAndBadWritesRecover() throws Exception {
        fx(()->{mouse(window.timeline.canvas,MouseEvent.MOUSE_PRESSED,TimelineView.GUTTER+20,TimelineView.RULER+20,true);mouse(window.timeline.canvas,MouseEvent.MOUSE_RELEASED,TimelineView.GUTTER+20,TimelineView.RULER+20,false);window.timeline.scroll.setHvalue(.4);return null;});
        double before=fx(()->window.timeline.offset);
        StudioProject original=DemoProject.create();
        List<Clip> clips=new ArrayList<>(original.clips());clips.add(new Clip("far","seq-lead-a","FM5",700,96));
        StudioProject changed=new StudioProject(1,"Agent revision","S3K",original.ticksPerBeat(),original.tempo(),original.voices(),original.sequences(),clips);
        replace(ProjectFormat.write(changed));
        await(()->window.document().project().name().equals("Agent revision"));
        // Wait for the FX refresh following background parsing, not merely model adoption.
        await(()->stage.getTitle().startsWith("Agent revision"));
        fx(()->{assertEquals("clip-a1",window.selectedClip());assertEquals(before,window.timeline.offset,1.0);return null;});
        replace("{\"formatVersion\":");
        await(()->window.document().message().startsWith("Cannot refresh:"));
        assertEquals("Agent revision",window.document().project().name());
        replace(ProjectFormat.write(original));await(()->stage.getTitle().startsWith("Lantern walk"));
        assertEquals("clip-a1",fx(()->window.selectedClip()));
    }

    @Test void dirtyConflictRetainsLocalEditsAndReloadWritesRecovery() throws Exception {
        fx(()->{window.document().edit(ProjectEdits.moveClip(window.document().project(),"clip-a1","FM2",0));return null;});
        var p=DemoProject.create();var external=new StudioProject(1,"External version","S3K",p.ticksPerBeat(),p.tempo(),p.voices(),p.sequences(),p.clips());
        replace(ProjectFormat.write(external));await(()->window.document().conflicted());
        assertEquals("FM2",window.document().project().clips().getFirst().lane());
        fx(()->{((Button)window.scene().lookup("#reload-file")).fire();return null;});
        assertEquals("External version",window.document().project().name());
        List<Path> recoveries;
        try(var files=Files.list(folder)){recoveries=files.filter(f->f.getFileName().toString().contains(".recovery-")).toList();}
        assertEquals(1,recoveries.size());assertEquals("FM2",ProjectFiles.load(recoveries.getFirst()).project().clips().getFirst().lane());
        assertFalse(window.document().dirty());
    }


    @Test void missingSelectedSourceClearsSelectionAndHugeUnusedSequencesStayBounded() throws Exception {
        fx(()->{mouse(window.timeline.canvas,MouseEvent.MOUSE_PRESSED,TimelineView.GUTTER+20,TimelineView.RULER+20,true);mouse(window.timeline.canvas,MouseEvent.MOUSE_RELEASED,TimelineView.GUTTER+20,TimelineView.RULER+20,false);return null;});
        var p=DemoProject.create();
        var remaining=new StudioProject(1,"Source removed","S3K",p.ticksPerBeat(),p.tempo(),p.voices(),p.sequences().stream().filter(q->!q.id().equals("seq-lead-a")).toList(),p.clips().stream().filter(c->!c.sequenceId().equals("seq-lead-a")).toList());
        replace(ProjectFormat.write(remaining));await(()->stage.getTitle().startsWith("Source removed"));
        fx(()->{assertNull(window.selectedClip());assertNull(window.piano.sequence);assertNull(((ListView<?>)window.scene().lookup("#sequence-browser")).getSelectionModel().getSelectedItem());return null;});
        var huge=new com.opensmpsdeck.project.Sequence("huge","Very long empty source","PSG",Integer.MAX_VALUE,null,100,List.of());
        var longProject=new StudioProject(1,"Long source","S3K",24,0,List.of(),List.of(huge),List.of());
        replace(ProjectFormat.write(longProject));await(()->stage.getTitle().startsWith("Long source"));
        fx(()->{((ListView<?>)window.scene().lookup("#sequence-browser")).getSelectionModel().select(0);window.piano.scroll.setHvalue(1);assertTrue(window.piano.canvas.getWidth()<2000);
            double x=window.piano.canvas.getWidth()-2,y=PianoRollView.HEADER+5*PianoRollView.NOTE_ROW;
            Event.fireEvent(window.piano.canvas,new MouseEvent(MouseEvent.MOUSE_PRESSED,x,y,x,y,MouseButton.PRIMARY,2,false,false,false,false,true,false,false,false,false,true,new PickResult(window.piano.canvas,new javafx.geometry.Point3D(x,y,0),0)));
            assertFalse(window.document().dirty(),"clicking padding past the huge source end must not wrap to tick zero");return null;});
        replace(ProjectFormat.write(new StudioProject(1,"Huge beat","S3K",Integer.MAX_VALUE,0,List.of(),List.of(huge),List.of())));await(()->stage.getTitle().startsWith("Huge beat"));
        fx(()->{window.timeline.scroll.setHvalue(1);window.piano.scroll.setHvalue(1);assertTrue(window.timeline.canvas.getWidth()<2000);return null;});
    }

    @Test void externalWriteCannotRebaseBetweenUiReadAndEdit() throws Exception {
        CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
        FutureTask<Void> editing=new FutureTask<>(()->{
            StudioProject before=window.document().project();reading.countDown();
            if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("edit release timed out");
            window.document().edit(ProjectEdits.moveClip(before,"clip-a1","FM2",0));return null;
        });
        Platform.runLater(editing);assertTrue(reading.await(3,TimeUnit.SECONDS));
        try {
            var p=DemoProject.create();replace(ProjectFormat.write(new StudioProject(1,"Concurrent agent","S3K",p.ticksPerBeat(),p.tempo(),p.voices(),p.sequences(),p.clips())));
            Thread.sleep(250); // allow the real directory watcher to post its debounced event
            assertEquals("Lantern walk",window.document().project().name(),"background watcher must not mutate the FX-owned document");
        } finally {release.countDown();}
        editing.get(3,TimeUnit.SECONDS);await(()->window.document().conflicted());
        assertEquals("FM2",window.document().project().clips().getFirst().lane());
        assertEquals("Concurrent agent",ProjectFiles.load(file).project().name());
        assertThrows(java.io.IOException.class,()->window.document().save());
    }


    @Test void adoptedExternalRevisionCancelsAnInProgressDragAndPreservesPianoScroll() throws Exception {
        fx(()->{mouse(window.timeline.canvas,MouseEvent.MOUSE_PRESSED,TimelineView.GUTTER+20,TimelineView.RULER+20,true);window.piano.scroll.setHvalue(.5);return null;});
        double before=fx(()->window.piano.offset);
        var p=DemoProject.create();
        List<Clip> clips=p.clips().stream().map(c->c.id().equals("clip-a1")?new Clip(c.id(),c.sequenceId(),"FM2",24,144):c).toList();
        var seq=p.sequences().getFirst();
        var longer=new com.opensmpsdeck.project.Sequence(seq.id(),seq.name(),seq.type(),192,seq.voiceId(),seq.volume(),seq.notes());
        List<com.opensmpsdeck.project.Sequence> sequences=p.sequences().stream().map(q->q.id().equals(seq.id())?longer:q).toList();
        replace(ProjectFormat.write(new StudioProject(1,"Agent changed drag target","S3K",p.ticksPerBeat(),p.tempo(),p.voices(),sequences,clips)));
        await(()->stage.getTitle().startsWith("Agent changed drag target"));
        fx(()->{mouse(window.timeline.canvas,MouseEvent.MOUSE_RELEASED,TimelineView.GUTTER+20,TimelineView.RULER+20,false);assertEquals("FM2",clip("clip-a1").lane());assertEquals(144,clip("clip-a1").lengthTicks());assertFalse(window.document().dirty());assertEquals(before,window.piano.offset,1.0);return null;});
    }

    @Test void snapshotRealEditor() throws Exception {
        String target=System.getProperty("studio.snapshot");Assumptions.assumeTrue(target!=null,"Optional visual QA artifact");
        fx(()->{
            mouse(window.timeline.canvas,MouseEvent.MOUSE_PRESSED,TimelineView.GUTTER+20,TimelineView.RULER+20,true);
            mouse(window.timeline.canvas,MouseEvent.MOUSE_RELEASED,TimelineView.GUTTER+20,TimelineView.RULER+20,false);
            stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
            WritableImage image=stage.getScene().snapshot(null);ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",Path.of(target).toFile());return null;
        });
        assertTrue(Files.size(Path.of(target))>10_000);
    }
    private Clip clip(String id){return window.document().project().clips().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();}
    private void replace(String json) throws Exception {Path staged=folder.resolve("incoming.json");Files.writeString(staged,json);Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private static void mouse(Node node,javafx.event.EventType<MouseEvent> type,double x,double y,boolean down){
        Event.fireEvent(node,new MouseEvent(type,x,y,x,y,MouseButton.PRIMARY,1,false,false,false,false,down,false,false,false,false,true,new PickResult(node,new javafx.geometry.Point3D(x,y,0),0)));
    }
    private static <T>T fx(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){if(fx(condition::getAsBoolean))return;Thread.sleep(25);}
        fail("UI did not observe file change within five seconds");
    }
}
