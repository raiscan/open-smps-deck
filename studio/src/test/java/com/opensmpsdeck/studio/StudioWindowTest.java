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

    @Test void nativeBankKeepsExactAddressesThroughEditUndoAndExternalRefresh() throws Exception {
        byte[] bank={0,0,1,0,1,64,10,(byte)128,0,0,(byte)0xf2};
        NativeProgram program=NativeProgram.of(0x8000,0,0,120,bank,List.of(),List.of());
        StudioProject project=new StudioProject(5,"Native fixture","S3K",12,64,List.of(),List.of(),List.of(),List.of(),List.of(),program);
        replace(ProjectFormat.write(project));
        await(()->window.document().project().nativeProgram()!=null);
        fx(()->{
            layout();
            assertNotNull(window.scene().lookup("#native-program-tabs"));
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
