package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.project.*;
import com.opensmpsdeck.project.Sequence;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

/** Human editor over exactly the project files external tools edit. */
public final class StudioWindow implements AutoCloseable {
    private final Stage stage;
    private final BorderPane root=new BorderPane();
    private final Label title=new Label(), fileState=new Label(), status=new Label("Ready"), detail=new Label("Choose a sequence"), linked=new Label();
    private final ListView<Sequence> browser=new ListView<>();
    private final VBox inspector=new VBox(12);
    private final FlowPane noteInspector=new FlowPane(8,8);
    final TimelineView timeline=new TimelineView();
    final PianoRollView piano=new PianoRollView();
    private final StudioPlayback playback=new StudioPlayback();
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"studio-files");t.setDaemon(true);return t;});
    private final Deque<StudioProject> undo=new ArrayDeque<>(), redo=new ArrayDeque<>();
    private ResponsiveEditors editors;
    private ProjectDocument document;
    private ProjectWatcher watcher;
    private String selectedClip, selectedSequence, selectedNote;
    private boolean updating;
    private volatile boolean closed;
    private long playbackEpoch;

    public StudioWindow(Stage stage,Path file) throws IOException {
        this.stage=stage;
        build();
        var area=Screen.getPrimary().getVisualBounds();
        Scene scene=new Scene(root,Math.min(1440,area.getWidth()*.94),Math.min(900,area.getHeight()*.90));
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("studio.css")).toExternalForm());
        scene.heightProperty().addListener((o,a,b)->adaptHeight(b.doubleValue()));adaptHeight(scene.getHeight());
        stage.setScene(scene);stage.setMinWidth(Math.min(1050,area.getWidth()*.90));stage.setMinHeight(Math.min(700,area.getHeight()*.85));
        scene.addEventFilter(KeyEvent.KEY_PRESSED,this::key);
        stage.setOnCloseRequest(event->{if(!confirmLeave())event.consume();else close();});
        load(file);
    }
    void showNotes(){editors.showNotes();}
    private void adaptHeight(double height){boolean compact=height<700;editors.setCompact(compact);root.getStyleClass().remove("compact");if(compact)root.getStyleClass().add("compact");}
    ProjectDocument document(){return document;}
    Scene scene(){return stage.getScene();}
    String selectedClip(){return selectedClip;}
    private Button button(String text,Runnable action){Button b=new Button(text);b.setOnAction(e->action.run());return b;}
    private Label label(String text,String style){Label l=new Label(text);l.getStyleClass().add(style);return l;}
    private Region spacer(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    private void build() {
        Label brand=label("OPEN / SMPS","brand");
        HBox top=new HBox(10,brand,label("STUDIO","tag"),spacer(),button("New",this::newProject),button("Open…",this::open),button("Save",this::save),button("Save as…",this::saveAs));
        top.getStyleClass().add("topbar");
        title.getStyleClass().add("project-title");fileState.getStyleClass().add("file-state");
        HBox projectLine=new HBox(14,title,spacer(),button("Song settings…",this::settings),button("▶  Play",this::play),button("■  Stop",this::stop),button("Export WAV…",()->export(true)),button("Export SMPS…",()->export(false)));
        projectLine.getStyleClass().add("projectbar");
        Button reload=button("Reload file + keep local recovery",this::reload);reload.setId("reload-file");
        HBox fileLine=new HBox(12,fileState,spacer(),reload);fileLine.getStyleClass().add("filebar");
        root.setTop(new VBox(top,projectLine,fileLine));
        browser.setId("sequence-browser");
        browser.setCellFactory(ignored->new ListCell<>(){
            @Override protected void updateItem(Sequence s,boolean empty){
                super.updateItem(s,empty);setText(null);setGraphic(null);
                if(!empty&&s!=null){VBox card=new VBox(5,label(s.name(),"sequence-name"),label(s.type()+"  /  "+s.lengthTicks()+" ticks  /  "+s.notes().size()+" notes","muted"));card.setPadding(new Insets(8,4,8,4));setGraphic(card);}
            }
        });
        browser.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
            if(!updating&&b!=null){selectedSequence=b.id();selectedClip=null;selectedNote=null;refreshView();}
        });
        browser.setOnMouseClicked(e->{if(e.getClickCount()==2)showNotes();});
        browser.setOnDragDetected(e->{var s=browser.getSelectionModel().getSelectedItem();if(s!=null){var board=browser.startDragAndDrop(TransferMode.COPY);var content=new ClipboardContent();content.putString("smps-sequence:"+s.id());board.setContent(content);e.consume();}});
        VBox library=new VBox(12,label("SEQUENCES","section"),label("Reusable musical sources","muted"),browser,button("+ Place on timeline",this::place),button("+ New sequence",this::newSequence));
        library.getStyleClass().add("library");library.setMinWidth(170);VBox.setVgrow(browser,Priority.ALWAYS);
        timeline.openNotes=this::showNotes;
        timeline.selection=id->{selectedClip=id;selectedNote=null;if(id!=null)selectedSequence=clip().sequenceId();refreshView();};
        timeline.edit=c->change(p->ProjectEdits.replaceClip(p,c));
        timeline.placement=c->{if(change(p->ProjectEdits.addClip(p,c))){selectedClip=c.id();selectedSequence=c.sequenceId();refreshView();}};
        Slider zoom=new Slider(1,10,3);zoom.setPrefWidth(125);zoom.setId("timeline-zoom");
        timeline.scale=zoom.getValue();zoom.valueProperty().addListener((o,a,b)->{timeline.scale=b.doubleValue();if(document!=null)timeline.setProject(document.project(),selectedClip);});
        HBox arrangeHeader=new HBox(10,label("ARRANGEMENT","section"),label("Drag clips · drag their right edge to repeat","muted"),spacer(),label("ZOOM","muted"),zoom);
        arrangeHeader.getStyleClass().add("panelbar");
        VBox arrangement=new VBox(arrangeHeader,timeline.scroll);VBox.setVgrow(timeline.scroll,Priority.ALWAYS);
        piano.selection=id->{selectedNote=id;refreshNoteInspector();};
        piano.edit=n->change(p->ProjectEdits.updateNote(p,selectedSequence,n));
        piano.addition=n->{if(change(p->ProjectEdits.addNote(p,selectedSequence,n))){selectedNote=n.id();refreshView();}};
        detail.getStyleClass().add("section");linked.getStyleClass().add("muted");
        HBox pianoHeader=new HBox(12,detail,spacer(),linked);pianoHeader.getStyleClass().add("panelbar");
        noteInspector.getStyleClass().add("note-inspector");
        VBox notes=new VBox(pianoHeader,piano.scroll,noteInspector);VBox.setVgrow(piano.scroll,Priority.ALWAYS);
        editors=new ResponsiveEditors(arrangement,notes);
        inspector.getStyleClass().add("inspector");inspector.setMinWidth(0);inspector.setPrefWidth(240);
        ScrollPane inspectorScroll=new ScrollPane(inspector);inspectorScroll.setFitToWidth(true);inspectorScroll.setMinWidth(210);inspectorScroll.getStyleClass().add("editor-scroll");
        SplitPane layout=new SplitPane(library,editors,inspectorScroll);layout.setDividerPositions(.15,.82);root.setCenter(layout);
        status.getStyleClass().add("status");status.setWrapText(true);
        HBox bottom=new HBox(12,status,spacer(),label("S3&K  ·  LIVE FILES  ·  NATIVE PLAYBACK","muted"));bottom.getStyleClass().add("statusbar");root.setBottom(bottom);
    }
    private void load(Path path) throws IOException {
        ProjectDocument replacement=ProjectDocument.open(path);
        ProjectWatcher replacementWatcher=new ProjectWatcher(path,()->watchChanged(replacement));
        if(watcher!=null)watcher.close();
        playback.stop();document=replacement;watcher=replacementWatcher;
        document.refresh(); // close the open/register race
        selectedClip=null;selectedSequence=document.project().sequences().isEmpty()?null:document.project().sequences().getFirst().id();selectedNote=null;
        undo.clear();redo.clear();refreshView();
    }
    private void watchChanged(ProjectDocument expected) {
        if(closed)return;
        // Adopt disk versions on the same thread as UI read/transform/edit. A
        // background adoption between project() and edit() could lose an update.
        Platform.runLater(()->{
            if(closed||document!=expected)return;
            boolean changed=expected.refresh();
            if(changed){stop();undo.clear();redo.clear();refreshView();}else refreshStatus();
        });
    }
    private void refreshStatus(){
        if(document==null)return;
        title.setText(document.project().name()+(document.dirty()?"  *":""));
        stage.setTitle(document.project().name()+(document.dirty()?" *":"")+" — OpenSMPS Studio");
        boolean invalid=document.message().startsWith("Cannot refresh:")||document.message().startsWith("Save failed:");
        fileState.setText((document.conflicted()?"CONFLICT  ·  ":invalid?"INVALID FILE  ·  ":document.dirty()?"UNSAVED  ·  ":"LIVE  ·  ")+document.path().getFileName()+"  —  "+document.message());
        fileState.setStyle(document.conflicted()||invalid?"-fx-text-fill: #ffcb77;":"");
    }
    private void refreshView(){
        if(document==null)return;
        updating=true;
        StudioProject p=document.project();
        if(clip()==null)selectedClip=null;
        if(clip()!=null)selectedSequence=clip().sequenceId();
        if(source()==null){selectedSequence=null;selectedNote=null;}
        if(source()==null||source().notes().stream().noneMatch(n->n.id().equals(selectedNote)))selectedNote=null;
        browser.getSelectionModel().clearSelection();browser.getItems().setAll(p.sequences());
        if(source()!=null)browser.getSelectionModel().select(source());
        timeline.setProject(p,selectedClip);piano.setSequence(source(),selectedNote,p.ticksPerBeat());
        detail.setText(source()==null?"NOTE EDITOR":source().name().toUpperCase(Locale.ROOT));
        long uses=p.clips().stream().filter(c->c.sequenceId().equals(selectedSequence)).count();
        linked.setText(source()==null?"Double-click to add notes":"Linked source · "+uses+" instance"+(uses==1?"":"s")+" · double-click to add notes");
        refreshInspector();
        for(var node:inspector.getChildren()){if(node instanceof Label l){l.setWrapText(true);l.setMinWidth(0);}if(node instanceof Button b){b.setWrapText(true);b.setMinWidth(0);b.setMaxWidth(Double.MAX_VALUE);}}
        refreshNoteInspector();refreshStatus();updating=false;
    }
    private void refreshInspector(){
        inspector.getChildren().clear();inspector.getChildren().add(label("CLIP INSPECTOR","section"));
        StudioProject p=document.project();
        inspector.getChildren().add(label(String.format(Locale.ROOT,"≈ %.1f BPM · native delay %d\n%d ticks / beat · 4 beats / bar",3600.0*(256-p.tempo())/(256.0*p.ticksPerBeat()),p.tempo(),p.ticksPerBeat()),"muted"));
        Clip c=clip();
        if(c==null){Label help=label("Select a clip to edit its position and repeated length.\n\nDrag a sequence onto a hardware lane, or use Place on timeline.","help");help.setWrapText(true);inspector.getChildren().add(help);return;}
        inspector.getChildren().add(label(source().name(),"inspector-title"));
        ComboBox<String> lane=new ComboBox<>();lane.getItems().setAll(TimelineView.LANES);lane.setValue(c.lane());lane.setMaxWidth(Double.MAX_VALUE);lane.setId("clip-lane");
        TextField start=field(c.startTick(),"clip-start"),length=field(c.lengthTicks(),"clip-length");
        inspector.getChildren().addAll(label("HARDWARE LANE","muted"),lane,label("START TICK","muted"),start,label("LENGTH IN TICKS","muted"),length);
        Label repeat=label(String.format(Locale.ROOT,"%.2f repetitions · %d ticks / source",(double)c.lengthTicks()/source().lengthTicks(),source().lengthTicks()),"muted");repeat.setWrapText(true);inspector.getChildren().add(repeat);
        Button apply=button("Apply clip changes",()->{try{change(pj->ProjectEdits.replaceClip(pj,new Clip(c.id(),c.sequenceId(),lane.getValue(),Integer.parseInt(start.getText()),Integer.parseInt(length.getText()))));}catch(IllegalArgumentException e){error(e);}});apply.setId("apply-clip");
        inspector.getChildren().addAll(apply,button("Duplicate linked clip",()->{Clip copy=new Clip("clip-"+UUID.randomUUID(),c.sequenceId(),c.lane(),c.startTick()+c.lengthTicks(),c.lengthTicks());if(change(pj->ProjectEdits.addClip(pj,copy))){selectedClip=copy.id();refreshView();}}),button("Delete clip",this::deleteClip),new Separator(),label("Shared source","section"));
        Label help=label("Notes below belong to the sequence. Editing them changes every linked clip. Extending a clip repeats its source; it does not stretch the notes.","help");help.setWrapText(true);inspector.getChildren().add(help);
    }
    private TextField field(int value,String id){TextField f=new TextField(String.valueOf(value));f.setId(id);f.setPrefColumnCount(5);return f;}
    private void refreshNoteInspector(){
        noteInspector.getChildren().clear();NoteEvent n=source()==null?null:source().notes().stream().filter(x->x.id().equals(selectedNote)).findFirst().orElse(null);
        if(n==null){noteInspector.getChildren().add(label("Double-click empty piano roll to add a note · drag to move · drag right edge to resize","muted"));return;}
        piano.selected=n.id();piano.draw();
        TextField tick=field(n.tick(),"note-tick"),duration=field(n.duration(),"note-duration"),pitch=field(n.pitch(),"note-pitch");
        Button apply=button("Apply note",()->{try{change(p->ProjectEdits.updateNote(p,selectedSequence,new NoteEvent(n.id(),Integer.parseInt(tick.getText()),Integer.parseInt(duration.getText()),Integer.parseInt(pitch.getText()))));}catch(IllegalArgumentException e){error(e);}});apply.setId("apply-note");
        noteInspector.getChildren().addAll(label(PianoRollView.noteName(n.pitch()),"section"),new HBox(6,label("Tick","muted"),tick),new HBox(6,label("Length","muted"),duration),new HBox(6,label("MIDI pitch","muted"),pitch),apply,button("Delete",this::deleteNote));
    }
    private Clip clip(){return document==null?null:document.project().clips().stream().filter(c->c.id().equals(selectedClip)).findFirst().orElse(null);}
    private Sequence source(){return document==null?null:document.project().sequences().stream().filter(s->s.id().equals(selectedSequence)).findFirst().orElse(null);}
    private boolean change(UnaryOperator<StudioProject> transform){
        try{StudioProject before=document.project(),after=transform.apply(before);if(!after.equals(before)){document.edit(after);undo.push(before);while(undo.size()>100)undo.removeLast();redo.clear();stop();}refreshView();return true;}
        catch(IllegalArgumentException e){error(e);return false;}
    }
    private void place(){
        Sequence s=source();if(s==null)return;
        String lane=timeline.selectedLane;if(!lane.startsWith(s.type()))lane=s.type()+"1";
        String chosen=lane;int end=document.project().clips().stream().filter(c->c.lane().equals(chosen)).mapToInt(c->c.startTick()+c.lengthTicks()).max().orElse(0);
        Clip c=new Clip("clip-"+UUID.randomUUID(),s.id(),chosen,end,s.lengthTicks());
        if(change(p->ProjectEdits.addClip(p,c))){selectedClip=c.id();refreshView();}
    }

    private void settings(){
        StudioProject baseline=document.project();
        TextField name=new TextField(baseline.name()),tempo=field(baseline.tempo(),"song-tempo"),beat=field(baseline.ticksPerBeat(),"song-beat");
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("Song settings");dialog.initOwner(stage);
        dialog.getDialogPane().setContent(new VBox(10,new Label("Song name"),name,new Label("Native S3K tempo delay (0–255; larger is slower)"),tempo,new Label("Ticks per beat (changes ruler, not note durations)"),beat));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
        if(document.project()!=baseline){error(new IllegalArgumentException("Project changed while settings were open; reopen settings to edit the current version"));return;}
        try {int nativeTempo=Integer.parseInt(tempo.getText()),ticks=Integer.parseInt(beat.getText());
            change(p->new StudioProject(p.formatVersion(),name.getText(),p.mode(),ticks,nativeTempo,p.voices(),p.sequences(),p.clips()));
        }catch(IllegalArgumentException e){error(e);}
    }

    private void newSequence(){
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("New linked sequence");dialog.initOwner(stage);
        TextField name=new TextField("New phrase");ComboBox<String> type=new ComboBox<>();type.getItems().setAll("FM","PSG");type.setValue("FM");
        dialog.getDialogPane().setContent(new VBox(10,new Label("Name"),name,new Label("Channel type"),type));dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
        try{StudioProject p=document.project();String voice=type.getValue().equals("FM")?p.voices().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Add a native FM voice in the project file first")).id():null;
            Sequence s=new Sequence("seq-"+UUID.randomUUID(),name.getText(),type.getValue(),(int)Math.min(Integer.MAX_VALUE,p.ticksPerBeat()*4L),voice,100,List.of());
            if(change(old->{List<Sequence> seq=new ArrayList<>(old.sequences());seq.add(s);return new StudioProject(old.formatVersion(),old.name(),old.mode(),old.ticksPerBeat(),old.tempo(),old.voices(),seq,old.clips());})){selectedSequence=s.id();selectedClip=null;selectedNote=null;refreshView();}
        }catch(IllegalArgumentException e){error(e);}
    }
    private void deleteClip(){if(clip()!=null){String id=selectedClip;if(change(p->ProjectEdits.deleteClip(p,id))){selectedClip=null;refreshView();}}}
    private void deleteNote(){if(selectedNote!=null&&source()!=null){String id=selectedNote;if(change(p->ProjectEdits.deleteNote(p,selectedSequence,id))){selectedNote=null;refreshView();}}}
    private void undo(boolean backwards){Deque<StudioProject> from=backwards?undo:redo,to=backwards?redo:undo;if(!from.isEmpty()){StudioProject next=from.pop();to.push(document.project());document.edit(next);stop();refreshView();}}
    private void key(KeyEvent e){
        if(e.isShortcutDown()&&e.getCode()==KeyCode.S){save();e.consume();return;}
        if(e.getTarget() instanceof TextInputControl)return;
        if(e.isShortcutDown()&&e.getCode()==KeyCode.Z){undo(!e.isShiftDown());e.consume();}
        else if(e.isShortcutDown()&&e.getCode()==KeyCode.Y){undo(false);e.consume();}
        else if(e.getCode()==KeyCode.SPACE){if(playback.playing())stop();else play();e.consume();}
        else if(e.getCode()==KeyCode.DELETE||e.getCode()==KeyCode.BACK_SPACE){if(piano.canvas.isFocused())deleteNote();else deleteClip();e.consume();}
    }
    private void play(){long epoch=++playbackEpoch;StudioProject snapshot=document.project();playback.play(snapshot,text->Platform.runLater(()->{if(!closed&&epoch==playbackEpoch)status.setText(text);}),tick->Platform.runLater(()->{if(!closed&&epoch==playbackEpoch){timeline.playhead=tick;timeline.draw();}}));}
    private void stop(){playbackEpoch++;playback.stop();timeline.playhead=-1;timeline.draw();status.setText("Stopped");}
    private boolean save(){try{document.save();refreshStatus();status.setText("Saved "+document.path().getFileName());return true;}catch(IOException|IllegalArgumentException e){error(e);refreshStatus();return false;}}
    private void reload(){try{Path recovery=document.reloadKeepingRecovery();stop();undo.clear();redo.clear();refreshView();status.setText(recovery==null?"Reloaded file":"Local edits preserved in "+recovery.getFileName());}catch(IOException|IllegalArgumentException e){error(e);refreshStatus();}}
    private FileChooser chooser(String heading,String extension){FileChooser chooser=new FileChooser();chooser.setTitle(heading);chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extension.equals("json")?"SMPS Studio project":"Export ("+extension+")","*."+extension));return chooser;}
    private void open(){if(!confirmLeave())return;var file=chooser("Open shared project","json").showOpenDialog(stage);if(file!=null)try{load(file.toPath());}catch(IOException|IllegalArgumentException e){error(e);}}
    private void saveAs(){var chooser=chooser("Save a project copy (choose a new file)","json");chooser.setInitialFileName("project-copy.smps.json");var file=chooser.showSaveDialog(stage);if(file!=null)try{ProjectFiles.save(file.toPath(),document.project(),null);load(file.toPath());status.setText("Saved project copy");}catch(IOException|IllegalArgumentException e){error(e);}}
    private void newProject(){if(!confirmLeave())return;var chooser=chooser("Create a shared project","json");chooser.setInitialFileName("untitled.smps.json");var file=chooser.showSaveDialog(stage);if(file!=null)try{StudioProject demo=DemoProject.create();ProjectFiles.save(file.toPath(),new StudioProject(1,"Untitled","S3K",demo.ticksPerBeat(),demo.tempo(),demo.voices(),List.of(),List.of()),null);selectedClip=null;selectedSequence=null;selectedNote=null;load(file.toPath());}catch(IOException|IllegalArgumentException e){error(e);}}
    private boolean confirmLeave(){
        if(document==null||!document.dirty())return true;
        ButtonType save=new ButtonType("Save"),discard=new ButtonType("Discard local edits");Alert prompt=new Alert(Alert.AlertType.CONFIRMATION,"This project has unsaved edits.",save,discard,ButtonType.CANCEL);prompt.initOwner(stage);prompt.setHeaderText("Keep your changes?");var choice=prompt.showAndWait().orElse(ButtonType.CANCEL);return choice.equals(discard)||(choice.equals(save)&&save());
    }
    private void export(boolean wav){
        var chooser=chooser(wav?"Export WAV (choose a new file)":"Export baked SMPS (choose a new file)",wav?"wav":"bin");chooser.setInitialFileName(wav?"track.wav":"track.bin");var file=chooser.showSaveDialog(stage);if(file==null)return;
        StudioProject snapshot=document.project();status.setText("Exporting…");
        io.submit(()->{try{byte[] bytes=ProjectCompiler.compile(snapshot);if(wav)WaveFile.render(bytes,file.toPath(),StudioPlayback.presentationCount(snapshot));else Files.write(file.toPath(),bytes,StandardOpenOption.CREATE_NEW);Platform.runLater(()->{if(!closed)status.setText("Exported "+file.getName());});}catch(Exception e){Platform.runLater(()->{if(!closed)error(e);});}});
    }
    private void error(Exception e){status.setText("Could not apply: "+e.getMessage());}
    @Override public void close(){if(closed)return;closed=true;if(watcher!=null)watcher.close();playback.close();io.shutdownNow();}
}
