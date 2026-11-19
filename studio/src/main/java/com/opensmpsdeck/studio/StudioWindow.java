package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.NativeArrangement;
import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.CataloguePlayback;
import com.opensmpsdeck.headless.ProjectPackage;
import com.opensmpsdeck.headless.S3kRomImporter;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.project.*;
import com.opensmpsdeck.project.Sequence;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
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
    private static final int EXTRA_LIFE_ID = 0x2A;
    private final Stage stage;
    private final BorderPane root=new BorderPane();
    private final Label title=new Label(), fileState=new Label(), status=new Label("Ready"), detail=new Label("Choose a sequence"), linked=new Label();
    private final ListView<Sequence> browser=new ListView<>();
    private final VBox inspector=new VBox(12);
    private final FlowPane noteInspector=new FlowPane(8,8);
    final TimelineView timeline=new TimelineView();
    final PianoRollView piano=new PianoRollView();
    final CommandEditorPane commands=new CommandEditorPane(edit->change(edit),text->status.setText(text));
    final VoiceEditorPane voices=new VoiceEditorPane(edit->change(edit),text->status.setText(text));
    final EnvelopeEditorPane envelopes=new EnvelopeEditorPane(edit->change(edit),text->status.setText(text));
    final DacSampleEditorPane samples=new DacSampleEditorPane(edit->change(edit),status::setText);
    final DacTriggerPane triggers=new DacTriggerPane(edit->change(edit),status::setText,this::openSample);
    final NativeProgramPane nativeBank=new NativeProgramPane(edit->change(edit),status::setText);
    private final TabPane sourceModes=new TabPane();
    private final Tab noteMode=new Tab("Notes"),commandMode=new Tab("Commands"),assetMode=new Tab("Assets");
    private final TabPane assetKinds=new TabPane();
    final InstrumentCataloguePane catalogue=new InstrumentCataloguePane(this::auditionCatalogue,this::applyCatalogue);
    private final Tab catalogueTab=new Tab("Catalogue",catalogue);
    private final Tab nativeBankTab=new Tab("Native bank",nativeBank);
    final NativeArrangementPane nativeArrangement=new NativeArrangementPane(this::showNativeBank, this::chooseEditableCopy);
    private final Tab nativeAssets=new Tab("Voices & assets");
    private final TabPane nativeTabs=new TabPane(new Tab("Arrangement",nativeArrangement),nativeBankTab,nativeAssets);
    private SplitPane arrangementLayout;
    private StackPane arrangementSidebar;
    private ScrollPane inspectorScroll;
    private VBox assetBody;
    private Button songSettings, binaryExport, nativePackageExport, romImport;
    private VBox noteBody;
    private final StudioPlayback playback=new StudioPlayback();
    private final ReviewTransport transport = new ReviewTransport();
    private final ReviewPane review = new ReviewPane();
    private final ReviewTransport.Lanes monitor = new ReviewTransport.Lanes();
    private ReviewTransport.Range selectedRange;
    private boolean reviewOpen;
    private boolean reviewAutoOpened;
    private boolean arrangementPlaying;
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
    private void showNativeBank(){nativeTabs.getSelectionModel().select(nativeBankTab);}
    void showNotes(){sourceModes.getSelectionModel().select(noteMode);editors.showNotes();}
    void showCommands(){sourceModes.getSelectionModel().select(commandMode);editors.showNotes();}
    void showAssets(){
        if(root.getCenter()==nativeTabs)nativeTabs.getSelectionModel().select(nativeAssets);
        else {sourceModes.getSelectionModel().select(assetMode);editors.showNotes();}
    }
    void openCatalogue(Path path){
        showAssets();
        assetKinds.getSelectionModel().select(catalogueTab);
        catalogue.open(path);
    }
    void showEnvelopes(){showAssets();assetKinds.getSelectionModel().select(1);}
    void showSamples(){showAssets();assetKinds.getSelectionModel().select(2);}
    private void openSample(String id){showSamples();samples.selectSample(id);}
    private void adaptHeight(double height){boolean compact=height<700;editors.setCompact(compact);nativeArrangement.setCompact(compact);root.getStyleClass().remove("compact");if(compact)root.getStyleClass().add("compact");}
    ProjectDocument document(){return document;}
    Scene scene(){return stage.getScene();}
    String selectedClip(){return selectedClip;}
    private Button button(String text,Runnable action){Button b=new Button(text);b.setOnAction(e->action.run());return b;}
    private Label label(String text,String style){Label l=new Label(text);l.getStyleClass().add(style);return l;}
    private Region spacer(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    private void build() {
        Label brand=label("OPEN / SMPS","brand");
        romImport=button("Import S3&K…",this::importRom);
        romImport.setId("import-s3k");
        var fileActions=new FlowPane(8,6,button("New",this::newProject),button("Open…",this::open),romImport,
                button("Save",this::save),button("Save as…",this::saveAs));
        fileActions.setPrefWrapLength(560);
        fileActions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        for(Node action:fileActions.getChildren())((Button)action).setMinWidth(Region.USE_PREF_SIZE);
        HBox top=new HBox(10,brand,label("STUDIO","tag"),spacer(),fileActions);
        top.getStyleClass().add("topbar");
        title.getStyleClass().add("project-title");title.setMinWidth(0);fileState.getStyleClass().add("file-state");
        songSettings=button("Song settings…",this::settings);
        binaryExport=button("Export SMPS…",()->export(false));
        nativePackageExport=button("Export package…",this::exportPackage);
        Button playButton=button("▶  Play",this::play),stopButton=button("■  Stop",this::stop);
        playButton.getStyleClass().add("play-action");
        HBox projectActions=new HBox(8,songSettings,playButton,stopButton,
                button("Export WAV…",()->export(true)),binaryExport,nativePackageExport);
        projectActions.setMinWidth(Region.USE_PREF_SIZE);
        projectActions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        for(Node action:projectActions.getChildren()){
            action.managedProperty().bind(action.visibleProperty());
            ((Button)action).setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox projectLine=new HBox(12,title,projectActions);
        HBox.setHgrow(title,Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);
        projectLine.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        projectLine.getStyleClass().add("projectbar");
        Button reload=button("Reload file + keep local recovery",this::reload);reload.setId("reload-file");
        HBox fileLine=new HBox(12,fileState,spacer(),reload);fileLine.getStyleClass().add("filebar");
        var reviewButton = button("Review", () -> showReview(!reviewOpen));
        reviewButton.setId("show-review");
        fileLine.getChildren().add(1, reviewButton);
        review.toolbar.setVisible(false);
        review.toolbar.managedProperty().bind(review.toolbar.visibleProperty());
        root.setTop(new VBox(top,projectLine,fileLine,review.toolbar));
        configureReview();
        browser.setId("sequence-browser");
        browser.setCellFactory(ignored->new ListCell<>(){
            @Override protected void updateItem(Sequence s,boolean empty){
                super.updateItem(s,empty);setText(null);setGraphic(null);
                if(!empty&&s!=null){
                    int hits=s.triggers().size();
                    String events=s.type().equals("DAC")?hits+(hits==1?" hit":" hits"):s.notes().size()+" notes";
                    if(!s.commands().isEmpty()) events+=" · "+s.commands().size()+" commands";
                    VBox card=new VBox(5,label(s.name(),"sequence-name"),label(s.type()+"  /  "+s.lengthTicks()+" ticks  /  "+events,"muted"));
                    card.setPadding(new Insets(8,4,8,4));setGraphic(card);
                }
            }
        });
        browser.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
            if(!updating&&b!=null){selectedSequence=b.id();selectedClip=null;selectedNote=null;refreshView();}
        });
        browser.setOnMouseClicked(e->{if(e.getClickCount()==2)showNotes();});
        browser.setOnDragDetected(e->{var s=browser.getSelectionModel().getSelectedItem();if(s!=null){var board=browser.startDragAndDrop(TransferMode.COPY);var content=new ClipboardContent();content.putString("smps-sequence:"+s.id());board.setContent(content);e.consume();}});
        Button assets=button("Voices & assets",this::showAssets);assets.setId("show-assets");
        Button place=button("+ Place on timeline",this::place);place.setId("place-sequence");
        VBox library=new VBox(12,label("SEQUENCES","section"),label("Reusable musical sources","muted"),browser,place,button("+ New sequence",this::newSequence),assets);
        library.getStyleClass().add("library");library.setMinWidth(170);VBox.setVgrow(browser,Priority.ALWAYS);
        timeline.openNotes=this::showNotes;
        timeline.selection=id->{selectedClip=id;selectedNote=null;if(id!=null)selectedSequence=clip().sequenceId();refreshView();};
        timeline.edit=c->change(p->ProjectEdits.replaceClip(p,c));
        timeline.placement=c->{if(change(p->ProjectEdits.addClip(p,c))){selectedClip=c.id();selectedSequence=c.sequenceId();refreshView();}};
        Slider zoom=new Slider(1,10,3);zoom.setPrefWidth(125);zoom.setId("timeline-zoom");
        timeline.scale=zoom.getValue();zoom.valueProperty().addListener((o,a,b)->{timeline.scale=b.doubleValue();if(document!=null)timeline.setProject(document.project(),selectedClip);});
        HBox arrangeHeader=new HBox(10,label("ARRANGEMENT","section"),label("Drag clips · drag their right edge to repeat","muted"),spacer(),label("ZOOM","muted"),zoom);
        arrangeHeader.getStyleClass().add("panelbar");
        VBox arrangement=new VBox(arrangeHeader,timeline.scroll,timeline.referenceLane);VBox.setVgrow(timeline.scroll,Priority.ALWAYS);
        piano.commandSelection=id->{showCommands();commands.selectCommand(id);};
        commands.voiceEditor=id->{showAssets();assetKinds.getSelectionModel().select(0);if(id!=null)voices.selectVoice(id);};
        commands.envelopeEditor=id->{showEnvelopes();if(id!=null)envelopes.selectEnvelope(id);};
        piano.selection=id->{selectedNote=id;refreshNoteInspector();};
        piano.edit=n->change(p->ProjectEdits.updateNote(p,selectedSequence,n));
        piano.addition=n->{if(change(p->ProjectEdits.addNote(p,selectedSequence,n))){selectedNote=n.id();refreshView();}};
        detail.getStyleClass().add("section");linked.getStyleClass().add("muted");
        HBox pianoHeader=new HBox(12,detail,spacer(),linked);pianoHeader.getStyleClass().add("panelbar");
        noteInspector.getStyleClass().add("note-inspector");
        noteBody=new VBox(piano.scroll,noteInspector);VBox.setVgrow(piano.scroll,Priority.ALWAYS);
        Tab fmAssets=new Tab("FM voices",voices),envAssets=new Tab("Envelopes",envelopes),dacAssets=new Tab("DAC samples",samples);
        catalogueTab.setId("instrument-catalogue-tab");
        assetKinds.getTabs().setAll(fmAssets,envAssets,dacAssets,catalogueTab);
        assetKinds.getTabs().forEach(tab->tab.setClosable(false));assetKinds.setId("asset-editor-tabs");
        Button packageExport=button("Export package…",this::exportPackage);packageExport.setId("export-package");
        HBox assetActions=new HBox(12,label("PROJECT VOICES & ASSETS","section"),spacer(),packageExport);
        assetActions.visibleProperty().bind(assetKinds.getSelectionModel().selectedItemProperty().isNotEqualTo(catalogueTab));
        assetActions.managedProperty().bind(assetActions.visibleProperty());
        assetBody=new VBox(assetActions,assetKinds);VBox.setVgrow(assetKinds,Priority.ALWAYS);
        noteMode.setClosable(false);commandMode.setClosable(false);assetMode.setClosable(false);noteMode.setContent(noteBody);commandMode.setContent(commands);assetMode.setContent(assetBody);sourceModes.getTabs().setAll(noteMode,commandMode,assetMode);sourceModes.setId("source-editor-modes");
        pianoHeader.visibleProperty().bind(sourceModes.getSelectionModel().selectedItemProperty().isNotEqualTo(assetMode));
        pianoHeader.managedProperty().bind(pianoHeader.visibleProperty());
        VBox notes=new VBox(pianoHeader,sourceModes);VBox.setVgrow(sourceModes,Priority.ALWAYS);
        editors=new ResponsiveEditors(arrangement,notes);
        inspector.getStyleClass().add("inspector");inspector.setMinWidth(0);inspector.setPrefWidth(240);
        inspectorScroll=new ScrollPane(inspector);inspectorScroll.setFitToWidth(true);inspectorScroll.setMinWidth(210);inspectorScroll.getStyleClass().add("editor-scroll");
        arrangementSidebar = new StackPane(inspectorScroll);
        arrangementSidebar.setMinWidth(210);
        arrangementLayout=new SplitPane(library,editors,arrangementSidebar);
        arrangementLayout.setDividerPositions(.15,.82);
        nativeTabs.setId("native-program-tabs");
        nativeTabs.getTabs().forEach(tab->tab.setClosable(false));
        root.setCenter(arrangementLayout);
        status.getStyleClass().add("status");status.setWrapText(true);
        HBox bottom=new HBox(12,status,spacer(),label("S3&K  ·  LIVE FILES  ·  NATIVE PLAYBACK","muted"));bottom.getStyleClass().add("statusbar");root.setBottom(bottom);
    }
    private void load(Path path) throws IOException {
        ProjectDocument replacement=ProjectDocument.open(path);
        ProjectWatcher replacementWatcher=new ProjectWatcher(path,()->watchChanged(replacement));
        if(watcher!=null)watcher.close();
        stopPlayback();nativeArrangement.setProject(null);voices.setProject(null);envelopes.setProject(null);samples.setProject(null);
        triggers.setSource(null,null);commands.setSource(null,null);nativeBank.setProject(null);
        document=replacement;watcher=replacementWatcher;
        review.resetComposer();
        transport.seconds = 0;
        setLoop(null);
        selectedRange = null;
        monitor.clear();
        timeline.laneSelected = false;
        nativeArrangement.timeline.laneSelected = false;
        review.mode.setValue(ReviewTransport.Mode.RENDER);
        transport.mode(ReviewTransport.Mode.RENDER);
        reviewAutoOpened = false;
        showReview(false);
        setPosition(0);
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
        boolean nativeMode=p.nativeProgram()!=null;
        catalogue.setEditable(!nativeMode);
        songSettings.setVisible(!nativeMode);
        binaryExport.setVisible(!nativeMode);
        nativePackageExport.setVisible(nativeMode);
        if(nativeMode&&root.getCenter()!=nativeTabs){
            assetMode.setContent(null);
            nativeAssets.setContent(assetBody);
            nativeTabs.getSelectionModel().selectFirst();
            root.setCenter(nativeTabs);
        }else if(!nativeMode&&root.getCenter()!=arrangementLayout){
            nativeAssets.setContent(null);
            assetMode.setContent(assetBody);
            root.setCenter(arrangementLayout);
        }
        nativeBank.setProject(nativeMode?p:null);
        nativeArrangement.setProject(nativeMode ? p.withReview(List.of(), List.of()) : null);
        review.update(p);
        timeline.review(document.path(), nativeMode ? null : review.references.getValue(), nativeMode ? List.of() : p.notes());
        nativeArrangement.timeline.review(document.path(), nativeMode ? review.references.getValue() : null,
                nativeMode ? p.notes() : List.of());
        if (!reviewAutoOpened && (!p.references().isEmpty() || p.notes().stream().anyMatch(ReviewNote::isOpen))) {
            reviewAutoOpened = true;
            reviewOpen = true;
        }
        showReview(reviewOpen);
        if(nativeMode){
            voices.setProject(p);envelopes.setProject(p);samples.setProject(p);
            refreshStatus();updating=false;return;
        }
        if(clip()==null)selectedClip=null;
        if(clip()!=null)selectedSequence=clip().sequenceId();
        if(source()==null){selectedSequence=null;selectedNote=null;}
        if(source()==null||source().notes().stream().noneMatch(n->n.id().equals(selectedNote)))selectedNote=null;
        browser.getSelectionModel().clearSelection();browser.getItems().setAll(p.sequences());
        if(source()!=null)browser.getSelectionModel().select(source());
        Sequence current=source();
        boolean dac=current!=null&&current.type().equals("DAC");
        timeline.setProject(p,selectedClip);
        piano.setSequence(dac?null:current,selectedNote,p.ticksPerBeat());
        triggers.setSource(p,dac?current:null);
        commands.setSource(p,dac?null:selectedSequence);
        commandMode.setDisable(dac);
        if(dac&&sourceModes.getSelectionModel().getSelectedItem()==commandMode) showNotes();
        noteMode.setText(dac?"DAC hits":"Notes");
        Node editor=dac?triggers:noteBody;
        if(noteMode.getContent()!=editor) noteMode.setContent(editor);
        voices.setProject(p);envelopes.setProject(p);samples.setProject(p);
        detail.setText(current==null?"NOTE EDITOR":current.name().toUpperCase(Locale.ROOT));
        long uses=p.clips().stream().filter(c->c.sequenceId().equals(selectedSequence)).count();
        linked.setText(current==null?"Double-click to add notes":"Linked source · "+uses+" instance"+(uses==1?"":"s")
                +(dac?" · DAC one-shots":" · double-click to add notes"));
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
        ComboBox<String> lane=new ComboBox<>();lane.getItems().setAll(TimelineView.LANES.stream().filter(l->l.startsWith(source().type())).toList());lane.setValue(c.lane());lane.setMaxWidth(Double.MAX_VALUE);lane.setId("clip-lane");
        TextField start=field(c.startTick(),"clip-start"),length=field(c.lengthTicks(),"clip-length");
        inspector.getChildren().addAll(label("HARDWARE LANE","muted"),lane,label("START TICK","muted"),start,label("LENGTH IN TICKS","muted"),length);
        Label repeat=label(String.format(Locale.ROOT,"%.2f repetitions · %d ticks / source",(double)c.lengthTicks()/source().lengthTicks(),source().lengthTicks()),"muted");repeat.setWrapText(true);inspector.getChildren().add(repeat);
        Button apply=button("Apply clip changes",()->{try{change(pj->ProjectEdits.replaceClip(pj,new Clip(c.id(),c.sequenceId(),lane.getValue(),Integer.parseInt(start.getText()),Integer.parseInt(length.getText()))));}catch(IllegalArgumentException e){error(e);}});apply.setId("apply-clip");
        boolean dac=source().type().equals("DAC");
        Button editSource=dac?button("Edit DAC hits",this::showNotes):button("Source controls & commands",this::showCommands);
        inspector.getChildren().addAll(apply,button("Duplicate linked clip",()->{Clip copy=new Clip("clip-"+UUID.randomUUID(),c.sequenceId(),c.lane(),c.startTick()+c.lengthTicks(),c.lengthTicks());if(change(pj->ProjectEdits.addClip(pj,copy))){selectedClip=copy.id();refreshView();}}),button("Delete clip",this::deleteClip),new Separator(),editSource,label("Shared source","section"));
        Label help=label(dac?"Editing hits changes every linked clip. Extending a clip repeats its hits. A sample can finish after the clip ends.":"Editing notes changes every linked clip. Extending a clip repeats its notes at their original lengths.","help");help.setWrapText(true);inspector.getChildren().add(help);
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
        String lane=timeline.selectedLane;if(!lane.startsWith(s.type()))lane=s.type().equals("DAC")?"DAC":s.type()+"1";
        String chosen=lane;int end=document.project().clips().stream().filter(c->c.lane().equals(chosen)).mapToInt(c->c.startTick()+c.lengthTicks()).max().orElse(0);
        Clip c=new Clip("clip-"+UUID.randomUUID(),s.id(),chosen,end,s.lengthTicks());
        if(change(p->ProjectEdits.addClip(p,c))){
            selectedClip=c.id();refreshView();
            if(chosen.equals("DAC")){
                timeline.selectedLane=chosen;
                timeline.scroll.setVvalue(1);
                timeline.draw();
            }
        }
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
            change(p->new StudioProject(p.formatVersion(),name.getText(),p.mode(),ticks,nativeTempo,p.voices(),p.sequences(),p.clips(),p.envelopes(),p.samples(),p.nativeProgram(),p.references(),p.notes()));
        }catch(IllegalArgumentException e){error(e);}
    }

    private void newSequence(){
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("New linked sequence");dialog.initOwner(stage);
        TextField name=new TextField("New phrase");ComboBox<String> type=new ComboBox<>();type.getItems().setAll("FM","PSG","DAC");type.setValue("FM");
        dialog.getDialogPane().setContent(new VBox(10,new Label("Name"),name,new Label("Channel type"),type));dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
        try{StudioProject p=document.project();String voice=type.getValue().equals("FM")?p.voices().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Create an FM voice in Voices & assets first")).id():null;
            boolean dac=type.getValue().equals("DAC");
            Sequence s=new Sequence("seq-"+UUID.randomUUID(),name.getText(),type.getValue(),(int)Math.min(Integer.MAX_VALUE,p.ticksPerBeat()*4L),voice,dac?127:100,List.of());
            if(change(old->ProjectEdits.addSequence(old,s))){
                selectedSequence=s.id();selectedClip=null;selectedNote=null;refreshView();
                if(dac) showNotes();
            }
        }catch(IllegalArgumentException e){error(e);}
    }
    private void deleteClip(){if(clip()!=null){String id=selectedClip;if(change(p->ProjectEdits.deleteClip(p,id))){selectedClip=null;refreshView();}}}
    private void deleteNote(){if(selectedNote!=null&&source()!=null){String id=selectedNote;if(change(p->ProjectEdits.deleteNote(p,selectedSequence,id))){selectedNote=null;refreshView();}}}
    private void undo(boolean backwards){Deque<StudioProject> from=backwards?undo:redo,to=backwards?redo:undo;if(!from.isEmpty()){StudioProject next=from.pop();to.push(document.project());document.edit(next);stop();refreshView();}}
    private void key(KeyEvent e){
        if(e.isShortcutDown()&&e.getCode()==KeyCode.S){save();e.consume();return;}
        Node focusOwner = stage.getScene().getFocusOwner();
        if (e.getTarget() instanceof TextInputControl || focusOwner instanceof TextInputControl) return;
        if (!e.isShortcutDown() && e.getCode() == KeyCode.R && !review.mode.isShowing() && !review.references.isShowing()) {
            toggleAB();
            e.consume();
            return;
        }
        if (focusOwner instanceof ComboBoxBase<?>) return;
        if(e.isShortcutDown()&&e.getCode()==KeyCode.Z){undo(!e.isShiftDown());e.consume();}
        else if(e.isShortcutDown()&&e.getCode()==KeyCode.Y){undo(false);e.consume();}
        else if(!e.isShortcutDown() && e.getCode()==KeyCode.N){composeReviewNote();e.consume();}
        else if(e.getCode()==KeyCode.SPACE){
            Node focus=stage.getScene().getFocusOwner();
            if(focus instanceof ButtonBase && (catalogue.ownsFocus(focus) || review.ownsFocus(focus)))return;
            if(playback.playing())stop();else play();
            e.consume();
        }
        else if(e.getCode()==KeyCode.DELETE||e.getCode()==KeyCode.BACK_SPACE){
            Node focus=stage.getScene().getFocusOwner();
            if(samples.ownsFocus(focus)) samples.deleteSelected();
            else if(voices.ownsFocus(focus)) voices.deleteSelected();
            else if(envelopes.ownsFocus(focus)) envelopes.deleteSelected();
            else if(commands.ownsFocus(focus)) commands.deleteSelected();
            else if(triggers.grid.canvas.isFocused()) triggers.deleteSelected();
            else if(piano.canvas.isFocused()) deleteNote();
            else if(timeline.canvas.isFocused()) deleteClip();
            e.consume();
        }
    }
    private void configureReview() {
        for (var arrangement : List.of(timeline, nativeArrangement.timeline)) {
            arrangement.monitor = monitor;
            arrangement.maskChanged = this::restartArrangementPlayback;
            arrangement.seek = seconds -> {
                arrangement.selected = null;
                arrangement.selection.accept(null);
                setPosition(seconds);
                restartArrangementPlayback();
            };
            arrangement.chooseLoop = range -> {
                selectedRange = range;
                setLoop(range);
                if (!playback.playing()) setPosition(range.start());
            };
            arrangement.openReviewNote = this::jumpToNote;
            arrangement.referenceLane.offsetChanged = seconds -> {
                var reference = review.references.getValue();
                if (reference != null) change(p -> ProjectEdits.offsetReference(p, reference.id(), seconds));
            };
        }
        review.addReference.setOnAction(event -> {
            var file = chooser("Choose a reference WAV", "wav").showOpenDialog(stage);
            if (file != null) addReference(file.toPath());
        });
        review.references.valueProperty().addListener((obs, previous, next) -> {
            if (document == null || review.updating()) return;
            activeTimeline().review(document.path(), next, document.project().notes());
            restartArrangementPlayback();
        });
        review.mode.valueProperty().addListener((obs, previous, next) -> {
            if (next == null) return;
            if (next == ReviewTransport.Mode.REFERENCE && activeTimeline().referenceLane.wave() == null) {
                review.mode.setValue(ReviewTransport.Mode.RENDER);
                status.setText("Reference playback needs a loaded WAV.");
                return;
            }
            transport.mode(next);
            monitorLabel();
        });
        review.loop.setOnAction(event -> {
            if (transport.loop != null) {
                setLoop(null);
                return;
            }
            var c = selectedTimelineClip();
            double rate = ReviewTransport.ticksPerSecond(document.project());
            var range = selectedRange;
            if (range == null && c != null) range = new ReviewTransport.Range(c.startTick() / rate,
                    (c.startTick() + (double) c.lengthTicks()) / rate);
            if (range == null) {
                status.setText("Shift-drag the ruler or select a clip to set a loop.");
                return;
            }
            setLoop(range);
            if (!playback.playing()) setPosition(range.start());
        });
        review.addNote.setOnAction(event -> composeReviewNote());
        review.jump = this::jumpToNote;
        review.submit = note -> {
            if (change(p -> ProjectEdits.addReviewNote(p, note))) review.resetComposer();
        };
        review.resolveNote = note -> {
            if (note != null) change(p -> ProjectEdits.resolveReviewNote(p, note.id(), null));
        };
        setLoop(null);
    }

    void addReference(Path wav) {
        var reference = new AudioReference("ref-" + UUID.randomUUID(), wav.getFileName().toString(),
                AudioReference.pathFor(document.path(), wav), 0, 0, null);
        if (change(p -> ProjectEdits.addReference(p, reference))) review.references.setValue(reference);
    }

    private void showReview(boolean show) {
        reviewOpen = show;
        review.toolbar.setVisible(show);
        boolean nativeMode = root.getCenter() == nativeTabs;
        if (!nativeMode && root.getRight() != null) root.setRight(null);
        Node sidebar = show && !nativeMode ? review : inspectorScroll;
        if (arrangementSidebar.getChildren().getFirst() != sidebar) {
            arrangementSidebar.getChildren().setAll(sidebar);
        }
        if (nativeMode) root.setRight(show ? review : null);
    }

    private TimelineView activeTimeline() {
        return document.project().nativeProgram() == null ? timeline : nativeArrangement.timeline;
    }

    private Clip selectedTimelineClip() {
        var arrangement = activeTimeline();
        return arrangement.project == null ? null : arrangement.project.clips().stream()
                .filter(c -> c.id().equals(arrangement.selected)).findFirst().orElse(null);
    }

    private void composeReviewNote() {
        showReview(true);
        var c = selectedTimelineClip();
        boolean onLane = activeTimeline().laneSelected;
        var range = transport.loop;
        double rate = ReviewTransport.ticksPerSecond(document.project());
        double start = range != null ? range.start() : c != null ? c.startTick() / rate : transport.seconds;
        Double end = range != null ? Double.valueOf(range.end())
                : c == null ? null : Double.valueOf((c.startTick() + (double) c.lengthTicks()) / rate);
        review.compose(new ReviewPane.Location(start, end, c != null ? c.lane()
                : onLane ? activeTimeline().selectedLane : null, c == null ? null : c.id()));
    }

    private void jumpToNote(ReviewNote note) {
        double seconds = note.startSeconds();
        double start = Math.max(0, Math.min(seconds - 1.5, Math.nextDown(seconds)));
        double end = (note.endSeconds() == null ? seconds : note.endSeconds()) + 1.5;
        selectedRange = new ReviewTransport.Range(start, end);
        setLoop(selectedRange);
        setPosition(seconds);
        activeTimeline().reveal(seconds);
        restartArrangementPlayback();
    }

    private void setLoop(ReviewTransport.Range range) {
        transport.loop = range;
        review.loop.setText(range == null ? "Loop selection" : "Clear loop");
        timeline.setLoop(range);
        nativeArrangement.timeline.setLoop(range);
    }

    private void setPosition(double seconds) {
        transport.seconds = seconds;
        if (document == null) return;
        timeline.playhead = seconds * ReviewTransport.ticksPerSecond(document.project());
        timeline.draw();
        nativeArrangement.setPlayhead(seconds * 60);
    }

    private void toggleAB() {
        if (review.references.getValue() == null || activeTimeline().referenceLane.wave() == null) {
            status.setText("A/B needs a loaded reference WAV.");
            return;
        }
        showReview(true);
        boolean nextIsReference = !transport.referenceAudible();
        review.mode.setValue(ReviewTransport.Mode.AB);
        transport.ab(nextIsReference);
        monitorLabel();
    }

    private void monitorLabel() {
        review.abSide.setText(review.mode.getValue() == ReviewTransport.Mode.AB
                ? (transport.referenceAudible() ? "Reference" : "Render") + " · R to switch" : "");
    }

    ReviewTransport transport() { return transport; }

    S3kRenderer.ChannelMask renderMask() { return monitor.mask(); }

    private void restartArrangementPlayback() {
        if (arrangementPlaying && playback.playing()) play();
    }

    private void play(){
        StudioProject snapshot=document.project();
        var source = review.references.getValue();
        var wave = activeTimeline().referenceLane.wave();
        if (activeTimeline().referenceLane.loading()
                || transport.referenceAudible() && (source == null || wave == null)) {
            status.setText("Reference WAV unavailable. Add a WAV or wait for its waveform.");
            return;
        }
        long epoch = ++playbackEpoch;
        arrangementPlaying = true;
        var reference = source == null || wave == null ? null : new StudioPlayback.Reference(source, wave);
        playback.play(snapshot,transport,reference,renderMask(),text->Platform.runLater(()->{
            if(!closed&&epoch==playbackEpoch)status.setText(text);
        }),seconds->Platform.runLater(()->{
            if(!closed&&epoch==playbackEpoch)setPosition(seconds);
        }));
    }
    private void auditionCatalogue(InstrumentCatalogue index,String variantId){
        try{
            StudioProject probe=CataloguePlayback.audition(index,variantId);
            stopPlayback();
            long epoch=playbackEpoch;
            status.setText("Preparing instrument audition…");
            playback.play(probe,text->Platform.runLater(()->{
                if(!closed&&epoch==playbackEpoch)status.setText(text.startsWith("Playing")?"Auditioning instrument":text);
            }),ignored->{});
        }catch(IllegalArgumentException failure){error(failure);}
    }
    private void applyCatalogue(InstrumentCatalogue index,String variantId){
        if(document.project().nativeProgram()!=null)return;
        if(change(project->CataloguePlayback.apply(index,variantId,project).withReview(project.references(),project.notes())))status.setText("Catalogue variant added to project");
    }
    private void stopPlayback(){
        playbackEpoch++;
        playback.stop();
        arrangementPlaying = false;
    }
    private void stop(){stopPlayback();status.setText("Stopped");}
    private boolean save(){try{document.save();refreshStatus();status.setText("Saved "+document.path().getFileName());return true;}catch(IOException|IllegalArgumentException e){error(e);refreshStatus();return false;}}
    private void reload(){try{Path recovery=document.reloadKeepingRecovery();stop();undo.clear();redo.clear();refreshView();status.setText(recovery==null?"Reloaded file":"Local edits preserved in "+recovery.getFileName());}catch(IOException|IllegalArgumentException e){error(e);refreshStatus();}}
    private FileChooser chooser(String heading,String extension){FileChooser chooser=new FileChooser();chooser.setTitle(heading);chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extension.equals("json")?"SMPS Studio project":"Export ("+extension+")","*."+extension));return chooser;}
    private void open(){if(!confirmLeave())return;var file=chooser("Open shared project","json").showOpenDialog(stage);if(file!=null)try{load(file.toPath());}catch(IOException|IllegalArgumentException e){error(e);}}
    private void saveAs(){var chooser=chooser("Save a project copy (choose a new file)","json");chooser.setInitialFileName("project-copy.smps.json");var file=chooser.showSaveDialog(stage);if(file!=null)try{ProjectFiles.save(file.toPath(),relocateReview(document.project(),document.path(),file.toPath()),null);load(file.toPath());status.setText("Saved project copy");}catch(IOException|IllegalArgumentException e){error(e);}}
    private static StudioProject relocateReview(StudioProject project, Path original, Path destination) {
        return project.withReview(project.references().stream().map(reference -> new AudioReference(reference.id(),
                reference.label(), AudioReference.pathFor(destination, reference.resolve(original)),
                reference.offsetSeconds(), reference.gainDb(), reference.role())).toList(), project.notes());
    }
    private void newProject(){if(!confirmLeave())return;var chooser=chooser("Create a shared project","json");chooser.setInitialFileName("untitled.smps.json");var file=chooser.showSaveDialog(stage);if(file!=null)try{StudioProject demo=DemoProject.create();ProjectFiles.save(file.toPath(),new StudioProject(1,"Untitled","S3K",demo.ticksPerBeat(),demo.tempo(),demo.voices(),List.of(),List.of()),null);selectedClip=null;selectedSequence=null;selectedNote=null;load(file.toPath());}catch(IOException|IllegalArgumentException e){error(e);}}
    private void chooseEditableCopy() {
        var output = chooser("Save editable copy (choose a new file)", "json");
        output.setInitialFileName("editable-copy.smps.json");
        var file = output.showSaveDialog(stage);
        if (file != null && !closed) saveEditableCopy(file.toPath());
    }

    void saveEditableCopy(Path destination) {
        StudioProject snapshot = document.project();
        Path sourcePath = document.path();
        nativeArrangement.setSavingEditableCopy(true);
        status.setText("Building editable copy…");
        Task<Void> saveCopy = new Task<>() {
            @Override protected Void call() throws IOException, InterruptedException {
                var copy = NativeArrangement.editableCopy(snapshot).withReview(snapshot.references(), snapshot.notes());
                ProjectFiles.save(destination, relocateReview(copy, sourcePath, destination), null);
                return null;
            }
        };
        saveCopy.setOnSucceeded(event -> {
            if (closed) return;
            nativeArrangement.setSavingEditableCopy(false);
            String saved = "Saved editable copy to " + destination.getFileName();
            status.setText(saved);
            try {
                openSavedProject(destination, "Editable copy · playback may differ from the native preview");
            } catch (IOException | IllegalArgumentException error) {
                status.setText(saved + ". Could not open it: " + error.getMessage());
            }
        });
        saveCopy.setOnFailed(event -> {
            if (closed) return;
            nativeArrangement.setSavingEditableCopy(false);
            status.setText("Could not save editable copy: " + saveCopy.getException().getMessage());
        });
        io.execute(saveCopy);
    }

    private void importRom() {
        FileChooser cartridge = new FileChooser();
        cartridge.setTitle("Choose your locked-on Sonic 3 & Knuckles ROM");
        cartridge.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Mega Drive cartridge images", "*.bin", "*.md", "*.gen", "*.rom"),
                new FileChooser.ExtensionFilter("All files", "*"));
        var file = cartridge.showOpenDialog(stage);
        if (file == null || closed) return;

        Path rom = file.toPath();
        ProjectDocument source = document;
        romImport.setDisable(true);
        status.setText("Reading S3&K music list…");
        Task<List<S3kRomImporter.Song>> scan = new Task<>() {
            @Override protected List<S3kRomImporter.Song> call() throws IOException {
                return S3kRomImporter.listSongs(rom);
            }
        };
        scan.setOnSucceeded(event -> {
            if (closed) return;
            romImport.setDisable(false);
            if (source != document) {
                status.setText("S3&K import cancelled because a different project is open");
                return;
            }
            chooseRomSong(source, rom, scan.getValue());
        });
        scan.setOnFailed(event -> importFailed(scan.getException()));
        io.execute(scan);
    }

    private void chooseRomSong(ProjectDocument source, Path rom, List<S3kRomImporter.Song> songs) {
        if (songs.isEmpty()) {
            status.setText("No supported music IDs found in this cartridge");
            return;
        }
        status.setText("Choose a song to import");
        ListView<S3kRomImporter.Song> music = new ListView<>();
        music.getItems().setAll(songs);
        music.setPrefSize(380, 280);
        music.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(S3kRomImporter.Song song, boolean empty) {
                super.updateItem(song, empty);
                setText(empty ? null : String.format(Locale.ROOT, "$%02X  %s", song.id(), song.name()));
            }
        });
        music.getSelectionModel().selectFirst();
        ButtonType next = new ButtonType("Choose destination…", ButtonBar.ButtonData.OK_DONE);
        Dialog<S3kRomImporter.Song> selection = new Dialog<>();
        selection.initOwner(stage);
        selection.setTitle("Import S3&K music");
        selection.setHeaderText("Choose a song from " + rom.getFileName());
        Label context = new Label("Extra Life ($2A) needs a previous song for preview. "
                + "Use the headless renderer with --previous-project.");
        context.setWrapText(true);
        context.visibleProperty().bind(music.getSelectionModel().selectedItemProperty()
                .map(song -> song.id() == EXTRA_LIFE_ID).orElse(false));
        context.managedProperty().bind(context.visibleProperty());
        selection.getDialogPane().setContent(new VBox(8, music, context));
        selection.getDialogPane().getButtonTypes().addAll(next, ButtonType.CANCEL);
        selection.setResultConverter(button -> button == next ? music.getSelectionModel().getSelectedItem() : null);
        var song = selection.showAndWait().orElse(null);
        if (closed) return;
        if (song == null) {
            status.setText("S3&K import cancelled");
            return;
        }
        var output = chooser("Save imported S3&K project (choose a new file)", "json");
        output.setInitialFileName(String.format(Locale.ROOT, "s3k-%02X.smps.json", song.id()));
        var file = output.showSaveDialog(stage);
        if (closed) return;
        if (file == null) {
            status.setText("S3&K import cancelled");
            return;
        }
        saveRomSong(source, rom, song, file.toPath());
    }

    private void saveRomSong(ProjectDocument source, Path rom, S3kRomImporter.Song song, Path destination) {
        romImport.setDisable(true);
        status.setText("Importing " + song.name() + "…");
        Task<Void> saveImport = new Task<>() {
            @Override protected Void call() throws IOException {
                ProjectFiles.save(destination, S3kRomImporter.readSong(rom, song.id()), null);
                return null;
            }
        };
        saveImport.setOnSucceeded(event -> {
            if (closed) return;
            romImport.setDisable(false);
            openImportedProject(source, song, destination);
        });
        saveImport.setOnFailed(event -> importFailed(saveImport.getException()));
        io.execute(saveImport);
    }

    void openImportedProject(ProjectDocument source, S3kRomImporter.Song song, Path destination) {
        if (closed) return;
        if (document != source) {
            status.setText("Imported " + destination.getFileName() + " · use Open to switch to it");
            return;
        }
        status.setText("Imported " + destination.getFileName());
        try {
            openSavedProject(destination, song.id() == EXTRA_LIFE_ID
                    ? "Extra Life needs a previous song for preview. Use --previous-project in the headless renderer."
                    : "Imported " + song.name());
        } catch (IOException | IllegalArgumentException error) {
            status.setText("Imported " + destination.getFileName() + "; could not open it: " + error.getMessage());
        }
    }

    private static void openSavedProject(Path destination, String message) throws IOException {
        // Pending editor drafts belong to the source window, even when its document is clean.
        Stage newStage = new Stage();
        StudioWindow opened = new StudioWindow(newStage, destination);
        opened.status.setText(message);
        newStage.show();
    }

    private void importFailed(Throwable error) {
        if (closed) return;
        romImport.setDisable(false);
        status.setText("S3&K import failed: " + error.getMessage());
    }

    private boolean confirmLeave(){
        if(document==null||!document.dirty())return true;
        ButtonType save=new ButtonType("Save"),discard=new ButtonType("Discard local edits");Alert prompt=new Alert(Alert.AlertType.CONFIRMATION,"This project has unsaved edits.",save,discard,ButtonType.CANCEL);prompt.initOwner(stage);prompt.setHeaderText("Keep your changes?");var choice=prompt.showAndWait().orElse(ButtonType.CANCEL);return choice.equals(discard)||(choice.equals(save)&&save());
    }
    private void export(boolean wav){
        var chooser=chooser(wav?"Export WAV (choose a new file)":"Export baked SMPS (choose a new file)",wav?"wav":"bin");chooser.setInitialFileName(wav?"track.wav":"track.bin");var file=chooser.showSaveDialog(stage);if(file==null)return;
        StudioProject snapshot=document.project();status.setText("Exporting…");
        io.submit(()->{try{var compiled=ProjectCompiler.compileWithAssets(snapshot);if(wav)WaveFile.render(compiled,file.toPath(),StudioPlayback.presentationCount(snapshot));else {if(compiled.requiresAssets())throw new IllegalArgumentException("Use Export package to keep this project's native bank metadata and dependencies.");Files.write(file.toPath(),compiled.song(),StandardOpenOption.CREATE_NEW);}Platform.runLater(()->{if(!closed)status.setText("Exported "+file.getName());});}catch(Exception e){Platform.runLater(()->{if(!closed)error(e);});}});
    }
    private void exportPackage(){
        var chooser=chooser("Export native track and assets (choose a new file)","zip");chooser.setInitialFileName("track.smps.zip");var file=chooser.showSaveDialog(stage);if(file==null)return;
        StudioProject snapshot=document.project();status.setText("Exporting native package…");
        io.submit(()->{try{ProjectPackage.write(snapshot,file.toPath());Platform.runLater(()->{if(!closed)status.setText("Exported "+file.getName());});}catch(Exception e){Platform.runLater(()->{if(!closed)error(e);});}});
    }
    private void error(Exception e){status.setText("Could not apply: "+e.getMessage());}
    @Override public void close(){if(closed)return;closed=true;if(watcher!=null)watcher.close();playback.close();timeline.referenceLane.close();nativeArrangement.timeline.referenceLane.close();samples.close();nativeArrangement.close();catalogue.close();io.shutdownNow();}
}
