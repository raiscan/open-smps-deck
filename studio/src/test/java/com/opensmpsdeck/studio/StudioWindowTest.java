package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.S3kRomImporter;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.project.*;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
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

    @Test void sunoImportBlocksErrorsUntilAnOverrideIsChecked() throws Exception {
        Path source = Files.createDirectory(folder.resolve("download"));
        Path work = folder.resolve("work");
        var sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        var track = sequence.createTrack();
        track.add(new javax.sound.midi.MidiEvent(
                new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_ON, 0, 69, 96), 0));
        track.add(new javax.sound.midi.MidiEvent(
                new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_OFF, 0, 69, 0), 480));
        javax.sound.midi.MidiSystem.write(sequence, 0, source.resolve("suno.mid").toFile());
        com.opensmpsdeck.headless.WaveFile.write(source.resolve("mix.wav"), 60, sink -> {
            var samples = new short[com.opensmpsdeck.headless.S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
            Arrays.fill(samples, (short) 1024);
            for (int i = 0; i < 60; i++) sink.accept(samples);
        });
        Files.writeString(source.resolve("bass.wav"), "invalid WAV");

        Stage preview = fx(() -> {
            assertEquals("Import Suno folder…", button("import-suno").getText());
            var window = new Stage();
            var pane = new SunoImportPane(source, work, result -> fail("Reconstruction must not have started"));
            window.setScene(new javafx.scene.Scene(pane, 960, 500));
            window.show();
            return window;
        });
        try {
            var scene = preview.getScene();
            await(() -> ((Label) scene.lookup("#suno-import-status")).getText().startsWith("✗"));
            fx(() -> {
                assertTrue(scene.lookup("#suno-import-start").isDisabled());
                var table = (TableView<?>) scene.lookup("#suno-import-files");
                table.applyCss();
                table.layout();
                var choices = (ComboBox<?>) table.lookupAll(".combo-box").stream()
                        .filter(Node::isVisible).findFirst().orElseThrow();
                choices.getSelectionModel().select(1); // Exclude
                assertTrue(scene.lookup("#suno-import-start").isDisabled());
                ((Button) scene.lookup("#suno-import-check")).fire();
                return null;
            });
            await(() -> !scene.lookup("#suno-import-start").isDisabled());
            assertTrue(Files.readString(work.resolve("import.json")).contains("excluded"));
        } finally {
            fx(() -> { ((SunoImportPane) preview.getScene().getRoot()).close(); preview.close(); return null; });
        }
    }

    @Test void groupAndUngroupAcrossLanesWithUndoAndRedo() throws Exception {
        fx(()->{
            var original=window.document().project();
            clickTimeline(9,0,false,1);
            clickTimeline(56,5,true,1);
            key(window.timeline.canvas,KeyCode.G,true);
            var grouped=window.document().project();
            assertEquals(2,grouped.groupSequences().getFirst().clips().size());
            assertEquals(1,grouped.groupPlacements().size());
            assertArrayEquals(ProjectCompiler.compile(original),ProjectCompiler.compile(grouped));
            key(window.timeline.canvas,KeyCode.Z,true);
            assertEquals(original,window.document().project());
            shortcut(KeyCode.Z,true);
            assertEquals(grouped,window.document().project());
            clickTimeline(9,0,false,1);
            shortcut(KeyCode.G,true);
            var flat=window.document().project();
            assertTrue(flat.groupPlacements().isEmpty());
            assertEquals(Set.copyOf(original.clips()),Set.copyOf(flat.clips()));
            key(window.timeline.canvas,KeyCode.Z,true);
            assertEquals(grouped,window.document().project());
            return null;
        });
    }

    @Test void editingInsideOneGroupUpdatesBothLinkedPlacements() throws Exception {
        prepareGroups();
        fx(()->{
            clickTimeline(9,0,false,2);
            assertEquals("Chorus",button("arrangement-level-1").getText());
            clickTimeline(9,0,false,1);
            ((TextField)window.scene().lookup("#clip-start")).setText("6");
            ((TextField)window.scene().lookup("#clip-length")).setText("90");
            button("apply-clip").fire();
            var project=window.document().project();
            var resolver=new GroupArrangement(project);
            for (var placement: project.groupPlacements()) {
                var lead=resolver.expand(placement).stream().filter(c->c.lane().equals("FM1")).findFirst().orElseThrow();
                assertEquals(placement.startTick()+6,lead.startTick());
                assertEquals(90,lead.lengthTicks());
            }
            key(window.timeline.canvas,KeyCode.ESCAPE,false);
            assertNull(window.scene().lookup("#arrangement-level-1"));
            return null;
        });
    }

    @Test void resizingGroupRepeatsWithAPartialFinalRepeat() throws Exception {
        prepareGroups();
        fx(()->{
            var zoom=(Slider)window.scene().lookup("#timeline-zoom");
            zoom.setValue(1.1);
            layout();
            double edge=timelineX(480)-3, y=TimelineView.RULER+15;
            var canvas=window.timeline.canvas;
            mouse(canvas,MouseEvent.MOUSE_PRESSED,edge,y,true);
            mouse(canvas,MouseEvent.MOUSE_DRAGGED,edge+27*window.timeline.scale,y,true);
            mouse(canvas,MouseEvent.MOUSE_RELEASED,edge+27*window.timeline.scale,y,false);
            var project=window.document().project();
            var instance=project.groupPlacements().getLast();
            assertEquals(123,instance.lengthTicks());
            assertTrue(new GroupArrangement(project).expand(instance).stream()
                    .anyMatch(c->c.startTick()==480 && c.lengthTicks()==27));
            return null;
        });
    }

    @Test void draggingGroupAcrossAnOccupiedLaneIsRefused() throws Exception {
        prepareGroups();
        fx(()->{
            var unchanged=window.document().project();
            double x=timelineX(9), y=TimelineView.RULER+15;
            var canvas=window.timeline.canvas;
            mouse(canvas,MouseEvent.MOUSE_PRESSED,x,y,true);
            mouse(canvas,MouseEvent.MOUSE_DRAGGED,x+48*window.timeline.scale,y,true);
            mouse(canvas,MouseEvent.MOUSE_RELEASED,x+48*window.timeline.scale,y,false);
            assertEquals(unchanged,window.document().project());
            assertTrue(window.scene().getRoot().lookupAll(".status").stream().filter(Label.class::isInstance)
                    .map(Label.class::cast).anyMatch(l->l.getText().contains("lane conflict on FM1")));
            return null;
        });
    }

    @Test void externalRefreshInsideGroupRetainsPathAndCancelsStaleDrag() throws Exception {
        prepareGroups();
        fx(()->{
            clickTimeline(9,0,false,2);
            clickTimeline(9,0,false,1);
            mouse(window.timeline.canvas,MouseEvent.MOUSE_PRESSED,timelineX(9),TimelineView.RULER+20,true);
            mouse(window.timeline.canvas,MouseEvent.MOUSE_DRAGGED,timelineX(15),TimelineView.RULER+20,true);
            return null;
        });
        var loaded=ProjectFiles.load(file);
        var external=GroupEdits.updateSource(loaded.project(),"chorus","Revised chorus",96);
        ProjectFiles.save(file,external,loaded.revision());
        await(()->window.document().project().equals(external));
        fx(()->{
            assertEquals("Revised chorus",button("arrangement-level-1").getText());
            assertEquals("clip-a1",window.selectedClip());
            mouse(window.timeline.canvas,MouseEvent.MOUSE_RELEASED,timelineX(15),TimelineView.RULER+20,false);
            assertEquals(external,window.document().project());
            assertFalse(window.document().dirty());
            return null;
        });
        loaded=ProjectFiles.load(file);
        var removed=GroupEdits.remove(loaded.project(),null,"chorus-placement");
        ProjectFiles.save(file,removed,loaded.revision());
        await(()->window.document().project().equals(removed));
        fx(()->{
            assertNull(window.scene().lookup("#arrangement-level-1"));
            assertNull(window.selectedClip());
            return null;
        });
    }

    private void prepareGroups() throws Exception {
        var loaded=ProjectFiles.load(file);
        var grouped=GroupEdits.create(loaded.project(),null,"chorus","Chorus",Set.of("clip-a1","clip-answer1"));
        var linked=GroupEdits.place(grouped,null,new GroupPlacement("second","chorus",384,96));
        ProjectFiles.save(file,linked,loaded.revision());
        await(()->window.document().project().equals(linked));
    }

    private double timelineX(int tick) {
        return TimelineView.GUTTER+tick*window.timeline.scale-window.timeline.offset;
    }

    private void clickTimeline(int tick,int row,boolean add,int count) {
        var canvas=window.timeline.canvas;
        double x=timelineX(tick), y=TimelineView.RULER+row*TimelineView.ROW+15;
        for (var type:List.of(MouseEvent.MOUSE_PRESSED,MouseEvent.MOUSE_RELEASED,MouseEvent.MOUSE_CLICKED)) {
            Event.fireEvent(canvas,new MouseEvent(type,x,y,x,y,MouseButton.PRIMARY,count,false,add,false,false,
                    type==MouseEvent.MOUSE_PRESSED,false,false,false,false,true,
                    new PickResult(canvas,new javafx.geometry.Point3D(x,y,0),0)));
        }
    }

    private void shortcut(KeyCode code,boolean shift) {
        Event.fireEvent(window.timeline.canvas,new KeyEvent(KeyEvent.KEY_PRESSED,"","",code,shift,true,false,false));
    }

    @Test void instrumentCataloguePagesFiltersAndAppliesWithProjectUndo() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(InstrumentCatalogue.Kind.PSG_VOLUME, "Test envelope",
                new byte[]{0, 1, (byte) 0x83});
        var occurrence = new InstrumentCatalogue.Occurrence("fixture.s3k", "S3K", "review",
                "Evening clock", 42, 3, "PSG1", 0, true);
        for (int i = 1; i <= 31; i++)
            builder.variant(asset, "Test variant %02d".formatted(i),
                    new InstrumentCatalogue.Recipe(null, null, 12, i, null, null, List.of(), List.of()),
                    occurrence);
        Path index = folder.resolve("instruments.json");
        builder.build().save(index);
        StudioProject original = fx(() -> {
            window.openCatalogue(index);
            return window.document().project();
        });

        await(() -> catalogueResultCount() == 30 && !button("catalogue-next").isDisabled());
        fx(() -> { button("catalogue-next").fire(); return null; });
        await(() -> catalogueResultCount() == 1 && button("catalogue-next").isDisabled());
        fx(() -> {
            var kinds = (ComboBox<?>) window.scene().lookup("#catalogue-kind");
            kinds.getSelectionModel().select(InstrumentCatalogue.Kind.FM_VOICE.ordinal() + 1);
            return null;
        });
        await(() -> catalogueResultCount() == 0 && !window.scene().lookup("#catalogue-search").isDisabled());
        fx(() -> {
            var kinds = (ComboBox<?>) window.scene().lookup("#catalogue-kind");
            kinds.getSelectionModel().select(InstrumentCatalogue.Kind.PSG_VOLUME.ordinal() + 1);
            ((TextField) window.scene().lookup("#catalogue-search")).setText("variant 18");
            return null;
        });
        await(() -> catalogueResultCount() == 1 && button("catalogue-previous").isDisabled());
        fx(() -> {
            button("catalogue-inspect").fire();
            layout();
            var detail = (TextArea) window.scene().lookup("#catalogue-details");
            assertTrue(detail.getText().contains("Test variant 18"));
            assertTrue(detail.getText().contains("fixture.s3k"));
            assertTrue(detail.getText().contains("Evening clock"));
            assertTrue(detail.getText().contains("18 ticks"));
            button("catalogue-apply").fire();
            assertEquals(original.clips(), window.document().project().clips());
            assertEquals(original.envelopes().size() + 1, window.document().project().envelopes().size());
            assertTrue(window.document().dirty());
            key(window.scene().getRoot(), KeyCode.Z, true);
            assertEquals(original, window.document().project());
            return null;
        });
    }

    private int catalogueResultCount() {
        var list = (ListView<?>) window.scene().lookup("#catalogue-results");
        return list == null ? -1 : list.getItems().size();
    }

    @Test void completedImportOpensAnotherWatchedWindowAndKeepsSourceEdits() throws Exception {
        Path imported = folder.resolve("s3k-81.smps.json");
        byte[] bank = {0,0,1,0,1,64,10,(byte)128,0,0,(byte)0xf2};
        var program = NativeProgram.of(0x8000, 0, 0, 120, bank, List.of(), List.of());
        var fixture = new StudioProject(5, "Imported fixture", "S3K", 12, 64,
                List.of(), List.of(), List.of(), List.of(), List.of(), program);
        ProjectFiles.save(imported, fixture, null);
        Stage importedStage = fx(() -> {
            assertTrue(window.scene().lookup("#import-s3k").isVisible());
            var source = window.document();
            var song = new S3kRomImporter.Song(0x81, "Synthetic song");
            int windowCount = Window.getWindows().size();
            window.openImportedProject(ProjectDocument.open(file), song, imported);
            assertEquals(windowCount, Window.getWindows().size());

            var canvas = window.timeline.canvas;
            mouse(canvas, MouseEvent.MOUSE_PRESSED, TimelineView.GUTTER + 20, TimelineView.RULER + 20, true);
            mouse(canvas, MouseEvent.MOUSE_RELEASED, TimelineView.GUTTER + 20, TimelineView.RULER + 20, false);
            var pending = (TextField) window.scene().lookup("#clip-start");
            pending.setText("19");
            var edited = ProjectEdits.moveClip(source.project(), "clip-a1", "FM2", 0);
            source.edit(edited);
            window.openImportedProject(source, song, imported);
            assertEquals("19", pending.getText());
            assertSame(pending, window.scene().lookup("#clip-start"));
            assertSame(source, window.document());
            assertSame(edited, source.project());
            assertTrue(source.dirty());
            Stage opened = Window.getWindows().stream()
                    .filter(Stage.class::isInstance).map(Stage.class::cast)
                    .filter(candidate -> candidate.getTitle().startsWith("Imported fixture"))
                    .findFirst().orElseThrow();
            assertNotNull(opened.getScene().lookup("#native-program-tabs"));
            return opened;
        });
        try {
            var refreshed = new StudioProject(5, "Imported watcher revision", "S3K", 12, 64,
                    List.of(), List.of(), List.of(), List.of(), List.of(), program);
            ProjectFiles.save(imported, refreshed, ProjectFiles.load(imported).revision());
            await(() -> importedStage.getTitle().startsWith("Imported watcher revision"));
        } finally {
            fx(() -> {
                importedStage.fireEvent(new WindowEvent(importedStage, WindowEvent.WINDOW_CLOSE_REQUEST));
                return null;
            });
        }
    }

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

    @Test void shortLogicalWorkAreaUsesReachableEditorTabs() throws Exception {
        fx(()->{stage.setHeight(610);return null;});await(()->window.scene().lookup("#compact-editor-tabs")!=null);
        fx(()->{window.showNotes();window.scene().getRoot().applyCss();window.scene().getRoot().layout();assertTrue(window.piano.scroll.getViewportBounds().getHeight()>80,"compact notes editor needs usable height");return null;});
        fx(()->{stage.setHeight(900);return null;});await(()->window.scene().lookup("#compact-editor-tabs")==null);
    }

    @Test void nativeCommandsShareUndoSaveAndKeyboardRouting() throws Exception {
        fx(()->{
            mouse(window.timeline.canvas,MouseEvent.MOUSE_PRESSED,TimelineView.GUTTER+20,TimelineView.RULER+20,true);
            mouse(window.timeline.canvas,MouseEvent.MOUSE_RELEASED,TimelineView.GUTTER+20,TimelineView.RULER+20,false);
            window.showCommands();layout();
            commandForm(NativeCommand.PAN,6,"80");button("add-command").fire();
            assertEquals(2,window.document().project().formatVersion());
            assertEquals(List.of(128),source().commands().getFirst().arguments());
            commandForm(NativeCommand.VOLUME,6,"60");button("add-command").fire();
            String volumeId=source().commands().getLast().id();
            button("command-up").fire();
            assertEquals(volumeId,source().commands().getFirst().id());
            key(window.scene().getRoot(),KeyCode.Z,true);
            assertEquals(volumeId,source().commands().getLast().id(),"command order is undoable");
            window.commands.selectCommand(volumeId);
            TableView<?> table=(TableView<?>)window.scene().lookup("#command-table");table.requestFocus();
            assertTrue(table.isFocused());
            key(table,KeyCode.DELETE,false);
            assertEquals(1,source().commands().size());assertEquals(8,window.document().project().clips().size());
            ((ComboBox<?>)window.scene().lookup("#repeat-state")).getSelectionModel().select(1);
            ((TextField)window.scene().lookup("#source-volume")).setText("105");button("apply-source").fire();
            assertEquals("CARRY",source().repeatState());assertEquals(105,source().volume());
            String target=System.getProperty("studio.snapshot");
            if(target!=null){layout();WritableImage image=window.scene().snapshot(null);ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",Path.of(target+".commands.png").toFile());}
            window.document().save();return null;
        });
        StudioProject saved=ProjectFiles.load(file).project();
        assertEquals(2,saved.formatVersion());assertEquals(1,saved.sequences().getFirst().commands().size());
    }

    @Test void timedEnvelopeCanBeAssignedByNameAndTurnedOff() throws Exception {
        var envelope = new Envelope("vibrato", "Slow vibrato", Envelope.MODULATION, List.of(0, 1, 0x81));
        replace(ProjectFormat.write(ProjectEdits.addEnvelope(DemoProject.create(), envelope)));
        await(() -> window.document().project().envelopes().size() == 1);
        fx(() -> {
            window.showCommands();
            layout();
            commandForm(NativeCommand.MODULATION_ENVELOPE, 6, "");
            @SuppressWarnings("unchecked")
            ComboBox<String> asset = (ComboBox<String>) window.scene().lookup("#command-asset");
            assertTrue(asset.getItems().contains(envelope.id()));
            assertTrue(asset.getConverter().toString(envelope.id()).contains(envelope.name()));
            asset.getSelectionModel().select(envelope.id());
            button("add-command").fire();
            assertEquals(envelope.id(), source().commands().getFirst().assetId());
            snapshot(".timed-envelope.png");
            asset.getSelectionModel().select(0);
            button("apply-command").fire();
            assertNull(source().commands().getFirst().assetId());
            window.document().save();
            return null;
        });
        var command = ProjectFiles.load(file).project().sequences().getFirst().commands().getFirst();
        assertEquals(NativeCommand.MODULATION_ENVELOPE, command.command());
        assertNull(command.assetId());
    }

    @Test void compactCommandBrowserNavigatesAndReordersTheSameEvents() throws Exception {
        var project = DemoProject.create();
        for (int i = 0; i < 12; i++) {
            project = ProjectEdits.addCommand(project, "seq-lead-a",
                    new CommandEvent("pan-" + i, 6, NativeCommand.PAN, List.of(128)));
        }
        replace(ProjectFormat.write(project));
        await(() -> source().commands().size() == 12);
        fx(() -> {
            stage.setMinHeight(400);
            stage.setHeight(600);
            window.showCommands();
            return null;
        });
        await(() -> window.scene().lookup("#browse-commands") instanceof ToggleButton toggle && toggle.isVisible());
        fx(() -> {
            layout();
            var browse = (ToggleButton) window.scene().lookup("#browse-commands");
            var operands = (TextField) window.scene().lookup("#command-bytes");
            window.commands.selectCommand("pan-0");
            operands.setText("40");
            assertEquals("Browse (12)", browse.getText());
            browse.fire();
            assertTrue(browse.isSelected());
            assertFalse(button("apply-command").isVisible());
            browse.fire();
            assertEquals("40", operands.getText(), "Switching views keeps uncommitted input");
            browse.fire();
            var table = (TableView<?>) window.scene().lookup("#command-table");
            key(table, KeyCode.END, false);
            assertEquals("pan-11", window.commands.selectedCommandId());
            button("command-up").fire();
            assertEquals("pan-11", source().commands().get(10).id());
            browse.fire();
            assertEquals("pan-11", window.commands.selectedCommandId());
            operands.setText("C0");
            button("apply-command").fire();
            window.document().save();
            return null;
        });
        var saved = ProjectFiles.load(file).project().sequences().getFirst().commands();
        assertEquals("pan-11", saved.get(10).id());
        assertEquals(List.of(192), saved.get(10).arguments());
    }

    @Test void nativeDraftSurvivesExternalRefreshAndCannotOverwriteIt() throws Exception {
        StudioProject withCommand=ProjectEdits.addCommand(DemoProject.create(),"seq-lead-a",new CommandEvent("pan-1",6,NativeCommand.PAN,List.of(128)));
        replace(ProjectFormat.write(withCommand));await(()->window.document().project().formatVersion()==2);
        fx(()->{
            window.showNotes();layout();
            mouse(window.piano.canvas,MouseEvent.MOUSE_PRESSED,PianoRollView.KEYBOARD+6*window.piano.scale,PianoRollView.HEADER+2,true);
            layout();assertEquals("pan-1",window.commands.selectedCommandId(),"marker selects its native event");
            ((TextField)window.scene().lookup("#command-bytes")).setText("40");return null;
        });
        StudioProject external=ProjectEdits.updateCommand(withCommand,"seq-lead-a",new CommandEvent("pan-1",6,NativeCommand.PAN,List.of(192)));
        replace(ProjectFormat.write(external));await(()->source().commands().getFirst().arguments().equals(List.of(192)));
        fx(()->{
            assertEquals("pan-1",window.commands.selectedCommandId());
            assertEquals("40",((TextField)window.scene().lookup("#command-bytes")).getText());
            button("apply-command").fire();
            assertEquals(List.of(192),source().commands().getFirst().arguments());assertFalse(window.document().dirty());
            button("reload-command").fire();assertEquals("C0",((TextField)window.scene().lookup("#command-bytes")).getText());
            ((TextField)window.scene().lookup("#command-bytes")).setText("40");button("apply-command").fire();
            assertEquals(List.of(64),source().commands().getFirst().arguments());assertTrue(window.document().dirty());return null;
        });
    }

    @Test void nativeVoiceControlsPreserveBytesAndUseCommonUndoAndSave() throws Exception {
        fx(()->{
            window.showAssets();layout();window.voices.selectVoice("voice-lead");
            Voice before=window.document().project().voices().getFirst();
            @SuppressWarnings("unchecked") ComboBox<Integer> algorithm=(ComboBox<Integer>)window.scene().lookup("#voice-algorithm");algorithm.setValue(7);
            ((CheckBox)window.scene().lookup("#voice-0-track-volume")).setSelected(true);
            button("apply-voice").fire();
            Voice after=window.document().project().voices().getFirst();
            assertEquals(7,after.bytes().getFirst()&7);assertEquals(before.bytes().get(21)|128,after.bytes().get(21));
            assertEquals(before.bytes().subList(1,21),after.bytes().subList(1,21));
            assertEquals(1,window.document().project().formatVersion());
            key(window.scene().getRoot(),KeyCode.Z,true);assertEquals(before,window.document().project().voices().getFirst());
            button("duplicate-voice").fire();assertEquals(3,window.document().project().voices().size());
            String copyId=window.voices.selectedVoiceId();assertNotEquals(before.id(),copyId);
            ListView<?> list=(ListView<?>)window.scene().lookup("#voice-list");list.requestFocus();key(list,KeyCode.DELETE,false);
            assertEquals(2,window.document().project().voices().size());assertEquals(8,window.document().project().clips().size());
            window.voices.selectVoice("voice-lead");button("delete-voice").fire();assertEquals(2,window.document().project().voices().size(),"referenced voices cannot be deleted");
            button("add-voice").fire();assertEquals(3,window.document().project().voices().size());
            assertEquals(0x98,window.document().project().voices().getLast().bytes().get(21));
            window.document().save();snapshot(".voices.png");return null;
        });
        assertEquals(3,ProjectFiles.load(file).project().voices().size());
    }

    @Test void envelopeAssignmentSavesAndAssetDraftsSurviveAgentUpdatesAndRemoval() throws Exception {
        String id=fx(()->{
            window.showEnvelopes();layout();button("add-psg-envelope").fire();
            String created=window.envelopes.selectedEnvelopeId();assertNotNull(created);assertEquals(3,window.document().project().formatVersion());
            ((ListView<?>)window.scene().lookup("#sequence-browser")).getSelectionModel().select(3);
            window.showCommands();layout();
            @SuppressWarnings("unchecked") ComboBox<String> assignment=(ComboBox<String>)window.scene().lookup("#source-psg-envelope");assignment.setValue(created);
            button("apply-source").fire();assertEquals(created,window.document().project().sequences().getLast().psgEnvelopeId());
            button("edit-source-psg-envelope").fire();layout();assertEquals(created,window.envelopes.selectedEnvelopeId());
            ((TextField)window.scene().lookup("#envelope-name")).setText("Human pluck");button("apply-envelope").fire();
            window.document().save();
            ((TextArea)window.scene().lookup("#envelope-bytes")).setText("00 07 83");return created;
        });
        StudioProject original=ProjectFiles.load(file).project();
        StudioProject external=ProjectEdits.updateEnvelope(original,new Envelope(id,"Agent pluck","PSG_VOLUME",List.of(0,10,0x83)));
        replace(ProjectFormat.write(external));await(()->window.document().project().envelopes().getFirst().name().equals("Agent pluck"));
        fx(()->{
            assertEquals(id,window.envelopes.selectedEnvelopeId());assertEquals("00 07 83",((TextArea)window.scene().lookup("#envelope-bytes")).getText());
            button("apply-envelope").fire();assertFalse(window.document().dirty());assertEquals(List.of(0,10,0x83),window.document().project().envelopes().getFirst().bytes());
            button("reload-envelope").fire();assertEquals("00 0A 83",((TextArea)window.scene().lookup("#envelope-bytes")).getText());
            ((TextArea)window.scene().lookup("#envelope-bytes")).setText("00 05 83");snapshot(".envelopes.png");return null;
        });
        StudioProject removed=ProjectEdits.deleteEnvelope(ProjectEdits.setSourceEnvelopes(external,"seq-answer",null,null),id);
        replace(ProjectFormat.write(removed));await(()->window.document().project().envelopes().isEmpty());
        fx(()->{assertEquals(id,window.envelopes.selectedEnvelopeId());assertEquals("00 05 83",((TextArea)window.scene().lookup("#envelope-bytes")).getText());button("apply-envelope").fire();assertFalse(window.document().dirty());assertTrue(window.document().project().envelopes().isEmpty());return null;});
    }

    @Test void voiceDraftsSurviveExternalChangesAndExplicitDocumentResetClearsThem() throws Exception {
        Voice original=DemoProject.create().voices().getFirst();
        fx(()->{
            window.showAssets();layout();window.voices.selectVoice(original.id());
            ((Hyperlink) window.scene().lookup("#show-voice-bytes")).fire();
            layout();
            var rawEditor = (TextArea) window.scene().lookup("#voice-bytes");
            var viewport = ((ScrollPane) window.voices.getCenter()).lookup(".viewport");
            assertTrue(rawEditor.isFocused());
            assertTrue(viewport.localToScene(viewport.getBoundsInLocal())
                    .intersects(rawEditor.localToScene(rawEditor.getBoundsInLocal())));
            ((TextField)window.scene().lookup("#voice-name")).setText("Human draft");
            ((TextArea)window.scene().lookup("#voice-bytes")).setText("F7"+VoiceEditorPane.formatVoiceBytes(original.bytes()).substring(2));return null;
        });
        var changedBytes=new ArrayList<>(original.bytes());changedBytes.set(0,7);
        StudioProject external=ProjectEdits.updateVoice(DemoProject.create(),new Voice(original.id(),"Agent voice",changedBytes));
        replace(ProjectFormat.write(external));await(()->window.document().project().voices().getFirst().name().equals("Agent voice"));
        fx(()->{
            assertEquals("Human draft",((TextField)window.scene().lookup("#voice-name")).getText());
            assertTrue(((TextArea)window.scene().lookup("#voice-bytes")).getText().startsWith("F7"));
            button("apply-voice").fire();button("apply-voice-bytes").fire();assertFalse(window.document().dirty());assertEquals(7,window.document().project().voices().getFirst().bytes().getFirst());
            button("reload-voice").fire();assertEquals("Agent voice",((TextField)window.scene().lookup("#voice-name")).getText());
            ((TextArea)window.scene().lookup("#voice-bytes")).setText("F7"+VoiceEditorPane.formatVoiceBytes(changedBytes).substring(2));button("apply-voice-bytes").fire();
            Voice raw=window.document().project().voices().getFirst();assertEquals(0xF7,raw.bytes().getFirst());assertEquals("Agent voice",raw.name());
            button("apply-voice").fire();assertEquals(raw,window.document().project().voices().getFirst(),"unchanged parameters preserve raw reserved bits");
            window.document().save();
            // StudioWindow explicitly resets panes when another document opens.
            ((TextField)window.scene().lookup("#voice-name")).setText("Old document draft");window.voices.setProject(null);window.voices.setProject(DemoProject.create());
            assertEquals("voice-lead",window.voices.selectedVoiceId());assertEquals("Glass keys",((TextField)window.scene().lookup("#voice-name")).getText());return null;
        });
    }

    @Test void dacGridAndExactEditsShareTheTimelineAndUndo() throws Exception {
        Path raw=Files.write(folder.resolve("Kick.pcm"),new byte[]{(byte)128,(byte)220,60,(byte)128});
        StudioProject project=ProjectEdits.addSequence(DemoProject.create(),new Sequence("drums","Drums","DAC",24,null,127,List.of()));
        replace(ProjectFormat.write(project));
        await(()->window.document().project().formatVersion()==4);
        fx(()->{window.showSamples();layout();window.samples.importFile(raw);return null;});
        await(()->window.document().project().samples().size()==1);
        DacSample imported=fx(()->window.document().project().samples().getFirst());
        assertArrayEquals(Files.readAllBytes(raw),imported.pcm());
        assertEquals(0x0c,imported.rate());
        fx(()->{
            ((ListView<?>)window.scene().lookup("#sequence-browser")).getSelectionModel().select(4);
            window.showNotes();layout();
            button("add-trigger").fire();
            assertEquals(0,window.document().project().sequences().getLast().triggers().getFirst().tick());
            ((TextField)window.scene().lookup("#trigger-tick")).setText("6");
            button("apply-trigger").fire();
            assertEquals(6,window.document().project().sequences().getLast().triggers().getFirst().tick());
            button("place-sequence").fire();
            assertEquals("DAC",window.document().project().clips().getLast().lane());
            assertTrue(TimelineView.LANES.contains("DAC"));
            key(window.scene().getRoot(),KeyCode.Z,true);
            assertEquals(8,window.document().project().clips().size());
            window.document().save();snapshot(".dac.png");return null;
        });
        assertEquals(imported.id(),ProjectFiles.load(file).project().sequences().getLast().triggers().getFirst().sampleId());
    }

    @Test void dacTriggerDraftCannotOverwriteAnAgentChange() throws Exception {
        StudioProject base=ProjectEdits.addSample(DemoProject.create(),
                DacSample.of("kick","Kick",12,new byte[]{(byte)128,(byte)220}));
        base=ProjectEdits.addSequence(base,new Sequence("drums","Drums","DAC",24,null,127,List.of()));
        base=ProjectEdits.addTrigger(base,"drums",new SampleTrigger("hit",0,"kick"));
        replace(ProjectFormat.write(base));await(()->window.document().project().formatVersion()==4);
        fx(()->{
            ((ListView<?>)window.scene().lookup("#sequence-browser")).getSelectionModel().select(4);
            window.showNotes();layout();
            mouse(window.triggers.grid.canvas,MouseEvent.MOUSE_PRESSED,DacTriggerPane.GUTTER+1,DacTriggerPane.HEADER+12,true);
            ((TextField)window.scene().lookup("#trigger-tick")).setText("9");return null;
        });
        replace(ProjectFormat.write(ProjectEdits.updateTrigger(base,"drums",new SampleTrigger("hit",12,"kick"))));
        await(()->window.document().project().sequences().getLast().triggers().getFirst().tick()==12);
        fx(()->{
            assertEquals("9",((TextField)window.scene().lookup("#trigger-tick")).getText());
            button("apply-trigger").fire();
            assertFalse(window.document().dirty());
            assertEquals(12,window.document().project().sequences().getLast().triggers().getFirst().tick());
            return null;
        });
    }

    @Test void nativeArrangementShowsReadOnlyNotesAndKeepsTheSourceDocument() throws Exception {
        byte[] bank={0,0,2,0,1,64,14,(byte)128,0,0,15,(byte)128,0,0,(byte)0xf2,(byte)0x99,12,(byte)0xf2};
        NativeProgram program=NativeProgram.of(0x8000,0,0,120,bank,List.of(),List.of());
        var voice = new Voice("voice", "Original fixture voice", java.util.Collections.nCopies(25, 0));
        StudioProject project=new StudioProject(5,"Native arrangement","S3K",12,64,List.of(voice),List.of(),List.of(),List.of(),List.of(),program);
        replace(ProjectFormat.write(project));
        await(()->window.nativeArrangement.timeline.project!=null);
        fx(()->{
            layout();
            var tabs=(TabPane)window.scene().lookup("#native-program-tabs");
            assertEquals("Arrangement",tabs.getSelectionModel().getSelectedItem().getText());
            assertFalse(window.nativeArrangement.piano.sequence.notes().isEmpty());
            var timeline=window.nativeArrangement.timeline;
            var clip=timeline.project.clips().getFirst();
            double x=TimelineView.GUTTER+clip.startTick()*timeline.scale+1;
            double y=TimelineView.RULER+TimelineView.LANES.indexOf(clip.lane())*TimelineView.ROW+20;
            mouse(timeline.canvas,MouseEvent.MOUSE_PRESSED,x,y,true);
            mouse(timeline.canvas,MouseEvent.MOUSE_DRAGGED,x+30,y+TimelineView.ROW,true);
            mouse(timeline.canvas,MouseEvent.MOUSE_RELEASED,x+30,y+TimelineView.ROW,false);
            assertEquals(clip.id(),timeline.selected);
            assertEquals(clip,timeline.project.clips().getFirst());
            assertEquals(project,window.document().project());
            assertFalse(window.document().dirty());
            return null;
        });
    }

    @Test void editableCopyOpensInAnotherWindowAndCanSaveTimelineEdits() throws Exception {
        byte[] bank = {0,0,2,0,1,64,14,(byte)128,0,0,15,(byte)128,0,0,(byte)0xf2,(byte)0x99,12,(byte)0xf2};
        var program = NativeProgram.of(0x8000, 0, 0, 120, bank, List.of(), List.of());
        var voice = new Voice("voice", "Fixture voice", Collections.nCopies(25, 0));
        var project = new StudioProject(5, "Editable copy fixture", "S3K", 12, 64,
                List.of(voice), List.of(), List.of(), List.of(), List.of(), program);
        replace(ProjectFormat.write(project));
        await(() -> window.nativeArrangement.timeline.project != null);

        Path destination = folder.resolve("editable.smps.json");
        List<Window> originalWindows = fx(() -> {
            layout();
            assertNotNull(button("save-editable-copy"));
            var originals = List.copyOf(Window.getWindows());
            window.saveEditableCopy(destination);
            return originals;
        });
        await(() -> Window.getWindows().stream().anyMatch(candidate -> !originalWindows.contains(candidate)));
        Stage opened = fx(() -> Window.getWindows().stream()
                .filter(candidate -> !originalWindows.contains(candidate))
                .filter(Stage.class::isInstance).map(Stage.class::cast).findFirst().orElseThrow());
        try {
            var saved = ProjectFiles.load(destination).project();
            assertNull(saved.nativeProgram());
            assertFalse(saved.sequences().getFirst().notes().isEmpty());
            fx(() -> {
                var root = opened.getScene().getRoot();
                root.applyCss();
                root.layout();
                assertNull(root.lookup("#native-program-tabs"));
                ((Button) root.lookup("#place-sequence")).fire();
                key(root, KeyCode.S, true);
                assertEquals(project, window.document().project());
                assertFalse(window.document().dirty());
                return null;
            });
            assertEquals(saved.clips().size() + 1, ProjectFiles.load(destination).project().clips().size());
            assertEquals(project, ProjectFiles.load(file).project());
        } finally {
            fx(() -> {
                opened.fireEvent(new WindowEvent(opened, WindowEvent.WINDOW_CLOSE_REQUEST));
                return null;
            });
        }
    }

    @Test void nativeBankKeepsExactAddressesThroughEditUndoAndExternalRefresh() throws Exception {
        byte[] bank={0,0,1,0,1,64,10,(byte)128,0,0,(byte)0xf2};
        NativeProgram program=NativeProgram.of(0x8000,0,0,120,bank,List.of(),List.of());
        StudioProject project=new StudioProject(5,"Native fixture","S3K",12,64,List.of(),List.of(),List.of(),List.of(),List.of(),program);
        replace(ProjectFormat.write(project));
        await(()->window.document().project().nativeProgram()!=null);
        fx(()->{
            layout();
            var tabs=(TabPane)window.scene().lookup("#native-program-tabs");
            tabs.getSelectionModel().select(1);
            layout();
            assertEquals(1,((TableView<?>)window.scene().lookup("#native-events")).getItems().size());
            TextArea raw=(TextArea)window.scene().lookup("#native-bank-bytes");
            raw.setText(raw.getText().replace("40","50"));
            button("apply-native-bank").fire();
            assertEquals(80,window.document().project().tempo());
            key(window.scene().getRoot(),KeyCode.Z,true);
            assertArrayEquals(bank,window.document().project().nativeProgram().bank());
            raw.setText(raw.getText().replace("40","60"));
            window.document().save();
            return null;
        });
        byte[] newer=bank.clone();
        newer[5]=70;
        replace(ProjectFormat.write(ProjectEdits.setNativeProgram(project,program.withBank(newer))));
        await(()->window.document().project().tempo()==70);
        fx(()->{
            assertTrue(((TextArea)window.scene().lookup("#native-bank-bytes")).getText().contains("60"));
            button("apply-native-bank").fire();
            assertArrayEquals(newer,window.document().project().nativeProgram().bank());
            button("reload-native-bank").fire();
            assertTrue(((TextArea)window.scene().lookup("#native-bank-bytes")).getText().contains("46"));
            snapshot(".native-bank.png");
            return null;
        });
    }

    private void snapshot(String suffix)throws Exception{
        String target=System.getProperty("studio.snapshot");if(target==null)return;
        layout();ImageIO.write(SwingFXUtils.fromFXImage(window.scene().snapshot(null),null),"png",Path.of(target+suffix).toFile());
    }

    private com.opensmpsdeck.project.Sequence source(){return window.document().project().sequences().getFirst();}
    private Button button(String id){return (Button)window.scene().lookup("#"+id);}
    @SuppressWarnings("unchecked") private void commandForm(NativeCommand type,int tick,String bytes){
        ((ComboBox<NativeCommand>)window.scene().lookup("#command-type")).setValue(type);
        ((TextField)window.scene().lookup("#command-tick")).setText(Integer.toString(tick));
        ((TextField)window.scene().lookup("#command-bytes")).setText(bytes);
    }
    private void layout(){window.scene().getRoot().applyCss();window.scene().getRoot().layout();}
    private static void key(Node node,KeyCode code,boolean shortcut){Event.fireEvent(node,new KeyEvent(KeyEvent.KEY_PRESSED,"","",code,false,shortcut,false,false));}

    @Test void referenceLaneAppearsAndABSwitchKeepsTransportPosition() throws Exception {
        Path wav = ReferenceWaveTest.wav(folder.resolve("guide.wav"), 16, 1, 24_000, -1000, 1000);
        fx(() -> {
            window.addReference(wav);
            layout();
            assertTrue(window.scene().lookup("#reference-lane").isVisible());
            assertEquals("guide.wav", window.document().project().references().getFirst().path());
            return null;
        });
        await(() -> window.timeline.referenceLane.wave() != null);
        fx(() -> {
            mouse(window.timeline.canvas, MouseEvent.MOUSE_PRESSED, TimelineView.GUTTER + 45, 20, true);
            double position = window.transport().seconds;
            assertEquals(1, position);
            key(window.timeline.canvas, KeyCode.R, false);
            assertTrue(window.transport().referenceAudible());
            assertEquals(position, window.transport().seconds);
            key(window.timeline.canvas, KeyCode.R, false);
            assertFalse(window.transport().referenceAudible());
            assertEquals(position, window.transport().seconds);
            return null;
        });
    }

    @Test void referenceLaneDragAndKeyboardNudgeEditOffset() throws Exception {
        Path wav = ReferenceWaveTest.wav(folder.resolve("guide.wav"), 16, 1, 24_000, -1000, 1000);
        fx(() -> {
            window.addReference(wav);
            layout();
            return null;
        });
        await(() -> window.timeline.referenceLane.wave() != null);
        fx(() -> {
            var waveCanvas = window.timeline.referenceLane.getChildrenUnmodifiable().getFirst();
            mouse(waveCanvas, MouseEvent.MOUSE_PRESSED, 200, 32, true);
            mouse(waveCanvas, MouseEvent.MOUSE_DRAGGED, 191, 32, true);
            mouse(waveCanvas, MouseEvent.MOUSE_RELEASED, 191, 32, false);
            assertEquals(-0.2, window.document().project().references().getFirst().offsetSeconds(), 1e-9);
            key(waveCanvas, KeyCode.RIGHT, false);
            assertEquals(-0.19, window.document().project().references().getFirst().offsetSeconds(), 1e-9);
            return null;
        });
    }

    @Test void addsAndResolvesReviewNoteThroughSharedEdits() throws Exception {
        fx(() -> {
            key(window.scene().getRoot(), KeyCode.N, false);
            layout();
            ((TextArea) window.scene().lookup("#review-note-text")).setText("Check the attack");
            button("submit-review-note").fire();
            var note = window.document().project().notes().getFirst();
            assertEquals(0, note.startSeconds());
            assertEquals("human", note.author());
            assertTrue(note.isOpen());
            reviewList().getSelectionModel().select(0);
            assertEquals(0, window.transport().loop.start());
            assertEquals(1.5, window.transport().loop.end());
            button("resolve-review-note").fire();
            assertFalse(window.document().project().notes().getFirst().isOpen());
            assertTrue(reviewList().getItems().isEmpty());
            key(window.scene().getRoot(), KeyCode.S, true);
            return null;
        });
        assertFalse(ProjectFiles.load(file).project().notes().getFirst().isOpen());
    }

    @Test void externalNotesCommandRefreshesOpenListAndKeepsComposerDraft() throws Exception {
        fx(() -> {
            key(window.scene().getRoot(), KeyCode.N, false);
            layout();
            ((TextArea) window.scene().lookup("#review-note-text")).setText("Local draft");
            return null;
        });
        cliNote("Agent marker");
        await(() -> reviewList().getItems().size() == 1);
        fx(() -> {
            assertEquals("agent", reviewList().getItems().getFirst().author());
            assertEquals("Local draft", ((TextArea) window.scene().lookup("#review-note-text")).getText());
            button("submit-review-note").fire();
            assertTrue(window.document().dirty());
            return null;
        });
        cliNote("New agent revision");
        await(() -> window.document().conflicted());
        fx(() -> {
            assertTrue(window.document().project().notes().stream().anyMatch(note -> note.text().equals("Local draft")));
            assertThrows(java.io.IOException.class, () -> window.document().save());
            Path recovered = window.document().reloadKeepingRecovery();
            assertTrue(ProjectFiles.load(recovered).project().notes().stream().anyMatch(n -> n.text().equals("Local draft")));
            assertTrue(window.document().project().notes().stream().anyMatch(n -> n.text().equals("New agent revision")));
            return null;
        });
    }

    @Test void arrangementSoloAndMuteSetTheHardwareRenderMask() throws Exception {
        fx(() -> {
            assertEquals(new com.opensmpsdeck.headless.S3kRenderer.ChannelMask(0, 0), window.renderMask());
            mouse(window.timeline.canvas, MouseEvent.MOUSE_PRESSED, 62, TimelineView.RULER + 27, true);
            assertEquals(new com.opensmpsdeck.headless.S3kRenderer.ChannelMask(62, 15), window.renderMask());
            mouse(window.timeline.canvas, MouseEvent.MOUSE_PRESSED, 85, TimelineView.RULER + 27, true);
            assertEquals(new com.opensmpsdeck.headless.S3kRenderer.ChannelMask(63, 15), window.renderMask());
            mouse(window.timeline.canvas, MouseEvent.MOUSE_PRESSED, 62, TimelineView.RULER + 27, true);
            assertEquals(new com.opensmpsdeck.headless.S3kRenderer.ChannelMask(1, 0), window.renderMask());
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private ListView<ReviewNote> reviewList() {
        return (ListView<ReviewNote>) window.scene().lookup("#open-review-notes");
    }

    private void cliNote(String text) {
        var output = new java.io.ByteArrayOutputStream();
        var stream = new java.io.PrintStream(output);
        assertEquals(0, com.opensmpsdeck.headless.Main.run(new String[]{"notes", "add", file.toString(),
                "--at", "1.5", "--text", text, "--author", "agent"}, stream, stream), output::toString);
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
