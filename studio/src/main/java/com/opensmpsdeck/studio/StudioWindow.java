package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.NativeArrangement;
import com.opensmpsdeck.headless.ProjectExport;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.CataloguePlayback;
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
    private record LibraryItem(String id, String name, String description, boolean group) {}
    private final ListView<LibraryItem> browser=new ListView<>();
    private record GroupFrame(String placementId, String groupId) {}
    private final List<GroupFrame> groupPath = new ArrayList<>();
    private final HBox breadcrumbs = new HBox(4);
    private final MenuItem makeGroup = new MenuItem("Group into sequence");
    private final MenuItem ungroup = new MenuItem("Ungroup");
    private String libraryGroup;
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
    private Button songSettings, romImport;
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
    private final EditHistory<StudioProject> history = new EditHistory<>();
    private ResponsiveEditors editors;
    private ProjectDocument document;
    private ProjectWatcher watcher;
    private String selectedClip, selectedSequence, selectedNote;
    private boolean updating;
    private volatile boolean closed;
    private long playbackEpoch;
    private ReconstructionPane reconstruction;

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
    ReconstructionPane reconstruction(){return reconstruction;}
    String selectedClip(){return selectedClip;}
    private Button button(String text,Runnable action){Button b=new Button(text);b.setOnAction(e->action.run());return b;}
    private Label label(String text,String style){Label l=new Label(text);l.getStyleClass().add(style);return l;}
    private Region spacer(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    private void build() {
        Label brand=label("OPEN / SMPS","brand");
        romImport=button("Import S3&K…",this::importRom);
        romImport.setId("import-s3k");
        Button sunoImport=button("Import Suno folder…",this::importSuno);
        sunoImport.setId("import-suno");
        var openReconstruction = button("Open reconstruction…", this::openReconstruction);
        openReconstruction.setId("open-reconstruction");
        var romSettings = button("ROM settings…", () -> RomSettingsDialog.show(stage, RomLibrary.userLibrary(), io));
        romSettings.setId("rom-settings");
        var fileActions=new FlowPane(8,6,button("New",this::newProject),button("Open…",this::open),romImport,sunoImport,
                openReconstruction,romSettings,button("Save",this::save),button("Save as…",this::saveAs));
        fileActions.setPrefWrapLength(720);
        fileActions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        for(Node action:fileActions.getChildren())((Button)action).setMinWidth(Region.USE_PREF_SIZE);
        HBox top=new HBox(10,brand,label("STUDIO","tag"),spacer(),fileActions);
        top.getStyleClass().add("topbar");
        title.getStyleClass().add("project-title");title.setMinWidth(0);fileState.getStyleClass().add("file-state");
        songSettings=button("Song settings…",this::settings);
        Button export = button("Export…", this::export);
        export.setId("export-project");
        Button playButton=button("▶  Play",this::play),stopButton=button("■  Stop",this::stop);
        playButton.getStyleClass().add("play-action");
        HBox projectActions=new HBox(8,songSettings,playButton,stopButton,export);
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
            @Override protected void updateItem(LibraryItem item,boolean empty){
                super.updateItem(item,empty);setText(null);setGraphic(null);
                if(!empty && item!=null){
                    VBox card=new VBox(5,label(item.name(),"sequence-name"),label(item.description(),"muted"));
                    card.setPadding(new Insets(8,4,8,4));setGraphic(card);
                }
            }
        });
        browser.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
            if (!updating && b!=null) {
                libraryGroup=b.group()?b.id():null;
                selectedSequence=b.group()?null:b.id();
                selectedClip=null;selectedNote=null;refreshView();
            }
        });
        browser.setOnMouseClicked(e->{
            if(e.getClickCount()==2 && libraryGroup==null) showNotes();
        });
        browser.setOnDragDetected(e->{
            var item=browser.getSelectionModel().getSelectedItem();
            if(item!=null){
                var board=browser.startDragAndDrop(TransferMode.COPY);
                var content=new ClipboardContent();
                content.putString((item.group() ? "smps-group:" : "smps-sequence:")+item.id());
                board.setContent(content);e.consume();
            }
        });
        Button assets=button("Voices & assets",this::showAssets);assets.setId("show-assets");
        Button place=button("+ Place on timeline",this::place);place.setId("place-sequence");
        VBox library=new VBox(12,label("SEQUENCES","section"),label("Reusable musical sources","muted"),browser,place,button("+ New sequence",this::newSequence),assets);
        library.getStyleClass().add("library");library.setMinWidth(170);VBox.setVgrow(browser,Priority.ALWAYS);
        timeline.openNotes=this::showNotes;
        timeline.openGroup=this::enterGroup;
        timeline.selection=id->{
            selectedClip=id;selectedNote=null;libraryGroup=null;
            if (clip()!=null) selectedSequence=clip().sequenceId();
            else if (groupPlacement()!=null) selectedSequence=null;
            refreshView();
        };
        timeline.edit=item->change(p->GroupEdits.replace(p,parentId(),item));
        timeline.placement=this::placeItem;
        timeline.onContext = this::timelineMenu;
        Slider zoom=new Slider(1,10,3);zoom.setPrefWidth(125);zoom.setId("timeline-zoom");
        timeline.scale=zoom.getValue();zoom.valueProperty().addListener((o,a,b)->{timeline.scale=b.doubleValue();if(document!=null)timeline.setProject(document.project(),selectedClip);});
        makeGroup.setId("group-selection");
        makeGroup.setOnAction(e -> groupSelected());
        ungroup.setId("ungroup-selection");
        ungroup.setOnAction(e -> ungroupSelected());
        MenuButton actions = new MenuButton("Groups", null, makeGroup, ungroup);
        breadcrumbs.setId("arrangement-breadcrumbs");
        breadcrumbs.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox arrangeHeader=new HBox(8,breadcrumbs,spacer(),actions,label("ZOOM","muted"),zoom);
        arrangeHeader.getStyleClass().add("panelbar");
        VBox arrangement=new VBox(arrangeHeader,timeline.scroll,timeline.referenceLane);VBox.setVgrow(timeline.scroll,Priority.ALWAYS);
        piano.commandSelection=id->{showCommands();commands.selectCommand(id);};
        commands.voiceEditor=id->{showAssets();assetKinds.getSelectionModel().select(0);if(id!=null)voices.selectVoice(id);};
        commands.envelopeEditor=id->{showEnvelopes();if(id!=null)envelopes.selectEnvelope(id);};
        piano.selection=id->{selectedNote=id;refreshNoteInspector();};
        piano.onContext = this::pianoMenu;
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
        HBox assetActions=new HBox(12,label("PROJECT VOICES & ASSETS","section"));
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
        groupPath.clear();libraryGroup=null;
        timeline.selectOnly(null);
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
        history.clear();refreshView();
    }
    private void watchChanged(ProjectDocument expected) {
        if(closed)return;
        // Adopt disk versions on the same thread as UI read/transform/edit. A
        // background adoption between project() and edit() could lose an update.
        Platform.runLater(()->{
            if(closed||document!=expected)return;
            boolean changed=expected.refresh();
            if(changed){stop();history.clear();refreshView();}else refreshStatus();
        });
    }
    private void refreshStatus(){
        if(document==null)return;
        title.setText(document.project().name()+(document.dirty()?"  *":""));
        stage.setTitle(document.project().name()+(document.dirty()?" *":"")+" — OpenSMPS Studio");
        boolean invalid=document.message().startsWith("Cannot refresh:")||document.message().startsWith("Save failed:");
        fileState.setText((document.conflicted()?"CONFLICT  ·  ":invalid?"INVALID FILE  ·  ":document.dirty()?"UNSAVED  ·  ":"LIVE  ·  ")+document.path().getFileName()+"  —  "+document.message()
                +(document.project().detachedFromReconstruction()?"  ·  Detached from reconstruction":""));
        fileState.setStyle(document.conflicted()||invalid?"-fx-text-fill: #ffcb77;":"");
    }
    private void refreshView(){
        if(document==null)return;
        updating=true;
        StudioProject p=document.project();
        boolean nativeMode=p.nativeProgram()!=null;
        catalogue.setEditable(!nativeMode);
        songSettings.setVisible(!nativeMode);
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
        long origin = refreshGroupPath(p);
        if (clip()==null && groupPlacement()==null) selectedClip=null;
        if (clip()!=null) selectedSequence=clip().sequenceId();
        if (groupPlacement()!=null) libraryGroup=groupPlacement().groupId();
        if (p.groupSequences().stream().noneMatch(group -> group.id().equals(libraryGroup))) libraryGroup=null;
        if(source()==null){selectedSequence=null;selectedNote=null;}
        if(source()==null||source().notes().stream().noneMatch(n->n.id().equals(selectedNote)))selectedNote=null;
        updateLibrary(p);
        Sequence current=source();
        boolean dac=current!=null&&current.type().equals("DAC");
        timeline.setArrangement(p,selectedClip,parentId(),origin);
        makeGroup.setDisable(timeline.selectedIds.isEmpty());
        ungroup.setDisable(groupPlacement()==null);
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
        long uses=GroupArrangement.authoredClips(p).filter(c->c.sequenceId().equals(selectedSequence)).count();
        linked.setText(current==null?"Double-click to add notes":"Linked source · "+uses+" instance"+(uses==1?"":"s")
                +(dac?" · DAC one-shots":" · double-click to add notes"));
        refreshInspector();
        for(var node:inspector.getChildren()){if(node instanceof Label l){l.setWrapText(true);l.setMinWidth(0);}if(node instanceof Button b){b.setWrapText(true);b.setMinWidth(0);b.setMaxWidth(Double.MAX_VALUE);}}
        refreshNoteInspector();refreshStatus();updating=false;
    }
    private void refreshInspector(){
        inspector.getChildren().clear();inspector.getChildren().add(label("CLIP INSPECTOR","section"));
        StudioProject p=document.project();
        var clock = p.clock();
        String tempoLabel = p.nativeTempo() == null ? "native delay" : p.target().name() + " native tempo";
        inspector.getChildren().add(label(String.format(Locale.ROOT,
                "≈ %.1f BPM · %s %d\n%d ticks / beat · 4 beats / bar",
                60 * clock.ticksPerSecond() / p.ticksPerBeat(), tempoLabel, clock.tempo(), p.ticksPerBeat()), "muted"));
        if (groupPlacement()!=null) {
            groupInspector(groupPlacement());
            return;
        }
        Clip c=clip();
        if (c==null && parentId()!=null) {
            groupSourceFields(GroupArrangement.source(p,parentId()));
            return;
        }
        if(c==null){Label help=label("Select a clip to edit its position and repeated length.\n\nDrag a sequence onto a hardware lane, or use Place on timeline.","help");help.setWrapText(true);inspector.getChildren().add(help);return;}
        inspector.getChildren().add(label(source().name(),"inspector-title"));
        ComboBox<String> lane=new ComboBox<>();lane.getItems().setAll(TimelineView.LANES.stream().filter(l->l.startsWith(source().type())).toList());lane.setValue(c.lane());lane.setMaxWidth(Double.MAX_VALUE);lane.setId("clip-lane");
        TextField start=field(c.startTick(),"clip-start"),length=field(c.lengthTicks(),"clip-length");
        inspector.getChildren().addAll(label("HARDWARE LANE","muted"),lane,label("START TICK","muted"),start,label("LENGTH IN TICKS","muted"),length);
        Label repeat=label(String.format(Locale.ROOT,"%.2f repetitions · %d ticks / source",(double)c.lengthTicks()/source().lengthTicks(),source().lengthTicks()),"muted");repeat.setWrapText(true);inspector.getChildren().add(repeat);
        Button apply=button("Apply clip changes",placementAction(c, () ->
                new Clip(c.id(),c.sequenceId(),lane.getValue(),Integer.parseInt(start.getText()),Integer.parseInt(length.getText()))));apply.setId("apply-clip");
        boolean dac=source().type().equals("DAC");
        Button editSource=dac?button("Edit DAC hits",this::showNotes):button("Source controls & commands",this::showCommands);
        inspector.getChildren().addAll(apply,button("Duplicate linked clip", () -> duplicateClip(c)),button("Delete clip",this::deleteClip),new Separator(),editSource,label("Shared source","section"));
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
    private String parentId() { return groupPath.isEmpty() ? null : groupPath.getLast().groupId(); }

    private Clip clip() {
        return document==null ? null : GroupArrangement.clips(document.project(),parentId()).stream()
                .filter(c->c.id().equals(selectedClip)).findFirst().orElse(null);
    }

    private GroupPlacement groupPlacement() {
        return document==null ? null : GroupArrangement.placements(document.project(),parentId()).stream()
                .filter(c->c.id().equals(selectedClip)).findFirst().orElse(null);
    }

    private void updateLibrary(StudioProject project) {
        var items = new ArrayList<LibraryItem>();
        for (Sequence s : project.sequences()) {
            String events=s.type().equals("DAC")?s.triggers().size()+" hits":s.notes().size()+" notes";
            if (!s.commands().isEmpty()) events+=" · "+s.commands().size()+" commands";
            items.add(new LibraryItem(s.id(),s.name(),s.type()+"  /  "+s.lengthTicks()+" ticks  /  "+events,false));
        }
        for (var group : project.groupSequences())
            items.add(new LibraryItem(group.id(),group.name(),"Group  /  "+group.lengthTicks()+" ticks",true));
        browser.getSelectionModel().clearSelection();
        if (!browser.getItems().equals(items)) browser.getItems().setAll(items);
        for (LibraryItem item : items) {
            if (item.group() ? item.id().equals(libraryGroup) : item.id().equals(selectedSequence)) {
                browser.getSelectionModel().select(item);
                break;
            }
        }
    }

    private long refreshGroupPath(StudioProject project) {
        long origin=0;
        String parent=null;
        int valid=0;
        for (GroupFrame frame : groupPath) {
            var item=GroupArrangement.placements(project,parent).stream()
                    .filter(p -> p.id().equals(frame.placementId()) && p.groupId().equals(frame.groupId()))
                    .findFirst().orElse(null);
            if (item==null) break;
            origin+=item.startTick();
            parent=item.groupId();
            valid++;
        }
        if (valid<groupPath.size()) {
            groupPath.subList(valid,groupPath.size()).clear();
            clearArrangementSelection();
        }
        var buttons=new ArrayList<Node>();
        buttons.add(crumb("Song",0));
        for (int i=0; i<groupPath.size(); i++) {
            var group=GroupArrangement.source(project,groupPath.get(i).groupId());
            buttons.add(label("›","muted"));
            buttons.add(crumb(group.name(),i+1));
        }
        breadcrumbs.getChildren().setAll(buttons);
        return origin;
    }

    private Button crumb(String title, int level) {
        var crumb=button(title,()->leaveGroup(level));
        crumb.setId("arrangement-level-"+level);
        crumb.setMaxWidth(132);
        crumb.setMinWidth(32);
        crumb.setTextOverrun(OverrunStyle.ELLIPSIS);
        return crumb;
    }

    private void clearArrangementSelection() {
        selectedClip=null;selectedSequence=null;selectedNote=null;libraryGroup=null;
        timeline.selectOnly(null);
    }

    private void enterGroup() {
        var instance=groupPlacement();
        if (instance==null) return;
        groupPath.add(new GroupFrame(instance.id(),instance.groupId()));
        clearArrangementSelection();
        refreshView();
    }

    private void leaveGroup(int depth) {
        if (depth>=groupPath.size()) return;
        var departed=groupPath.get(depth);
        groupPath.subList(depth,groupPath.size()).clear();
        clearArrangementSelection();
        selectedClip=departed.placementId();
        refreshView();
        timeline.canvas.requestFocus();
    }

    private void groupSelected() {
        if (document.project().nativeProgram()!=null || timeline.selectedIds.isEmpty()) return;
        var ids=Set.copyOf(timeline.selectedIds);
        int number=1;
        var names=document.project().groupSequences().stream().map(GroupSequence::name).collect(java.util.stream.Collectors.toSet());
        while(names.contains("Group sequence "+number)) number++;
        String id="group-"+UUID.randomUUID(), name="Group sequence "+number;
        if (change(p->GroupEdits.create(p,parentId(),id,name,ids))) {
            clearArrangementSelection();
            selectedClip=id+"-placement";
            refreshView();
        }
    }

    private void ungroupSelected() {
        var item=groupPlacement();
        if (item!=null && change(p->GroupEdits.ungroup(p,parentId(),item.id()))) {
            clearArrangementSelection();
            refreshView();
        }
    }

    private void groupInspector(GroupPlacement instance) {
        var group=GroupArrangement.source(document.project(),instance.groupId());
        inspector.getChildren().add(label(group.name(),"inspector-title"));
        TextField start=field(instance.startTick(),"group-start"), length=field(instance.lengthTicks(),"group-length");
        var apply=button("Apply group placement",placementAction(instance, () -> new GroupPlacement(instance.id(),
                instance.groupId(),Integer.parseInt(start.getText()),Integer.parseInt(length.getText()))));
        apply.setId("apply-group-placement");
        inspector.getChildren().addAll(label("START TICK","muted"),start,label("LENGTH IN TICKS","muted"),length,
                apply,button("Edit inside",this::enterGroup),button("Ungroup",this::ungroupSelected),
                button("Delete placement",this::deleteClip),new Separator());
        groupSourceFields(group);
    }

    private void groupSourceFields(GroupSequence group) {
        var baseline = document.project();
        var name=new TextField(group.name());
        name.setId("group-source-name");
        var length=field(group.lengthTicks(),"group-source-length");
        var apply=button("Apply group source",()->{
            try {
                if (document.project()!=baseline)
                    throw new IllegalArgumentException("The group form is outdated. Use the current inspector.");
                change(p->GroupEdits.updateSource(p,group.id(),name.getText(),Integer.parseInt(length.getText())));
            } catch (IllegalArgumentException failure) {error(failure);}
        });
        apply.setId("apply-group-source");
        inspector.getChildren().addAll(label("LINKED GROUP SOURCE","section"),label("NAME","muted"),name,
                label("SOURCE LENGTH IN TICKS","muted"),length,apply);
    }

    private Runnable placementAction(Placement expected, java.util.function.Supplier<Placement> updated) {
        var baseline = document.project();
        String parent = parentId();
        return () -> {
            try {
                var current = expected instanceof Clip ? clip() : groupPlacement();
                if (document.project()!=baseline || !Objects.equals(parent,parentId()) || !expected.equals(current))
                    throw new IllegalArgumentException("The placement is outdated. Use the current inspector.");
                var value=updated.get();
                change(p->GroupEdits.replace(p,parent,value));
            } catch (IllegalArgumentException failure) {error(failure);}
        };
    }
    private Sequence source(){return document==null?null:document.project().sequences().stream().filter(s->s.id().equals(selectedSequence)).findFirst().orElse(null);}
    private boolean change(UnaryOperator<StudioProject> transform) {
        try {
            var before = document.project();
            var after = transform.apply(before);
            if (!after.equals(before)) {
                boolean musical = !after.withReview(before.references(), before.notes()).equals(before);
                if (before.detachedFromReconstruction() || musical && reconstructionWork(document.path()) != null)
                    after = after.withDetachedFromReconstruction(true);
                document.edit(after);
                history.record(before);
                stop();
            }
            refreshView();
            return true;
        } catch (IllegalArgumentException failure) {
            error(failure);
            return false;
        }
    }

    private static Path reconstructionWork(Path project) {
        if (!project.getFileName().toString().equals(ReconstructionReview.ARRANGEMENT)) return null;
        Path work = project.getParent();
        return Files.isRegularFile(work.resolve("score.json")) ? work : null;
    }

    private boolean placeItem(Placement item) {
        if (!change(p->GroupEdits.place(p,parentId(),item))) return false;
        selectedClip=item.id();selectedNote=null;
        libraryGroup=item instanceof GroupPlacement group ? group.groupId() : null;
        refreshView();
        return true;
    }

    private void place(){
        var project=document.project();
        String parent=parentId();
        var clips=GroupArrangement.clips(project,parent);
        var groups=GroupArrangement.placements(project,parent);
        if (libraryGroup!=null) {
            var source=GroupArrangement.source(project,libraryGroup);
            int end=Math.max(clips.stream().mapToInt(Clip::endTick).max().orElse(0),
                    groups.stream().mapToInt(GroupPlacement::endTick).max().orElse(0));
            placeItem(new GroupPlacement("group-"+UUID.randomUUID(),source.id(),end,source.lengthTicks()));
            return;
        }
        Sequence s=source();if(s==null)return;
        String lane=timeline.selectedLane;
        if(!lane.startsWith(s.type()))lane=s.type().equals("DAC")?"DAC":s.type()+"1";
        String chosen=lane;
        var resolver=new GroupArrangement(project);
        int end=java.util.stream.Stream.concat(clips.stream(),groups.stream().flatMap(g->resolver.expand(g).stream()))
                .filter(c->c.lane().equals(chosen)).mapToInt(Clip::endTick).max().orElse(0);
        if (placeItem(new Clip("clip-"+UUID.randomUUID(),s.id(),chosen,end,s.lengthTicks())) && chosen.equals("DAC")) {
            timeline.selectedLane=chosen;
            timeline.scroll.setVvalue(1);
            timeline.draw();
        }
    }

    private void settings(){
        StudioProject baseline=document.project();
        boolean classicClock = baseline.nativeTempo() != null;
        String tempoLabel = classicClock ? baseline.target().name() + " native tempo byte (larger is faster)"
                : "Native S3K tempo delay (0–255; larger is slower)";
        TextField name=new TextField(baseline.name()),tempo=field(baseline.clock().tempo(),"song-tempo"),beat=field(baseline.ticksPerBeat(),"song-beat");
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("Song settings");dialog.initOwner(stage);
        dialog.getDialogPane().setContent(new VBox(10,new Label("Song name"),name,new Label(tempoLabel),tempo,new Label("Ticks per beat (changes ruler, not note durations)"),beat));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
        if(document.project()!=baseline){error(new IllegalArgumentException("Project changed while settings were open; reopen settings to edit the current version"));return;}
        try {int nativeTempo=Integer.parseInt(tempo.getText()),ticks=Integer.parseInt(beat.getText());
            change(p -> classicClock
                    ? p.withSettings(name.getText(), ticks, p.tempo()).withNativeTempo(nativeTempo)
                    : p.withSettings(name.getText(), ticks, nativeTempo));
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
                selectedSequence=s.id();selectedClip=null;selectedNote=null;libraryGroup=null;refreshView();
                if(dac) showNotes();
            }
        }catch(IllegalArgumentException e){error(e);}
    }
    private void duplicateClip(Clip clip) {
        if (clip.equals(clip())) placeItem(new Clip("clip-" + UUID.randomUUID(), clip.sequenceId(), clip.lane(),
                clip.endTick(), clip.lengthTicks()));
    }

    private void timelineMenu(ContextMenuEvent event, double time) {
        var menu = new StudioContextMenu();
        if (clip() != null) {
            var clip = clip();
            menu.add("Edit notes", this::showNotes).add("Duplicate clip", () -> duplicateClip(clip));
        } else if (groupPlacement() != null) {
            menu.add("Edit inside", this::enterGroup)
                    .add("Ungroup", StudioContextMenu.shortcut("Shift+G"), null, this::ungroupSelected);
        }
        if (selectedClip != null)
            menu.add("Delete", "Delete / Backspace", null, this::deleteClip);
        if (!makeGroup.isDisable())
            menu.add("Group into sequence", StudioContextMenu.shortcut("G"), null, this::groupSelected);
        menu.add("Play from here", () -> { setPosition(time); play(); }).show(timeline.canvas, event);
    }

    private void pianoMenu(ContextMenuEvent event, NoteEvent insertion) {
        var menu = new StudioContextMenu();
        if (selectedNote != null && source() != null) {
            menu.add("Delete", "Delete / Backspace", null, this::deleteNote);
            var note = source().notes().stream().filter(candidate -> candidate.id().equals(selectedNote)).findFirst().orElseThrow();
            var profile = TargetProfile.of(document.project().target());
            boolean psg = source().type().equals("PSG");
            var range = psg ? profile.psg() : profile.fm();
            menu.submenu("Transpose", List.of(1, -1, 12, -12).stream().map(step -> StudioContextMenu.item(
                    "%+d".formatted(step), TransposeKeys.label(step),
                    psg && PianoRollView.usesNoise(source(), note) ? "PSG noise hits have no tonal pitch."
                            : range.contains(note.pitch() + step) ? null : "Outside the chip pitch range.",
                    null, () -> piano.transpose(step, profile, status::setText))).toList());
        } else if (insertion != null) menu.add("Insert note here", () -> piano.addition.accept(insertion));
        menu.show(piano.canvas, event);
    }

    private void deleteClip(){
        String id=selectedClip;
        if (id!=null && change(p->GroupEdits.remove(p,parentId(),id))) {
            clearArrangementSelection();
            refreshView();
        }
    }
    private void deleteNote(){if(selectedNote!=null&&source()!=null){String id=selectedNote;if(change(p->ProjectEdits.deleteNote(p,selectedSequence,id))){selectedNote=null;refreshView();}}}
    private void undo(boolean backwards) {
        if (!history.canStep(!backwards)) return;
        document.edit(history.step(document.project(), !backwards));
        stop();
        refreshView();
    }
    private void key(KeyEvent e){
        if(e.isShortcutDown()&&e.getCode()==KeyCode.S){save();e.consume();return;}
        if(e.getCode()==KeyCode.ESCAPE && !groupPath.isEmpty() && document.project().nativeProgram()==null) {
            leaveGroup(groupPath.size()-1);e.consume();return;
        }
        Node focusOwner = stage.getScene().getFocusOwner();
        if (StudioContextMenu.editingText(e.getTarget()) || StudioContextMenu.editingText(focusOwner)) return;
        if (!e.isShortcutDown() && e.getCode() == KeyCode.R && !review.mode.isShowing() && !review.references.isShowing()) {
            toggleAB();
            e.consume();
            return;
        }
        if (focusOwner instanceof ComboBoxBase<?>) return;
        int semitones = TransposeKeys.semitones(e);
        if (semitones != 0 && piano.canvas.isFocused()) {
            piano.transpose(semitones, TargetProfile.of(document.project().target()), status::setText);
            e.consume();
            return;
        }
        if (semitones != 0 && triggers.grid.canvas.isFocused()) {
            status.setText("Transpose skipped · DAC hits");
            e.consume();
            return;
        }
        if(e.isShortcutDown()&&e.getCode()==KeyCode.Z){undo(!e.isShiftDown());e.consume();}
        else if(e.isShortcutDown()&&e.getCode()==KeyCode.Y){undo(false);e.consume();}
        else if(e.isShortcutDown()&&e.getCode()==KeyCode.G && document.project().nativeProgram()==null){
            if(e.isShiftDown())ungroupSelected();else groupSelected();
            e.consume();
        }
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
            var c = selectedTimelinePlacement();
            var project = document.project();
            var range = selectedRange;
            if (range == null && c != null) {
                double start = activeTimeline().origin + c.startTick();
                range = new ReviewTransport.Range(ReviewTransport.secondsAt(project, start),
                        ReviewTransport.secondsAt(project, start + c.lengthTicks()));
            }
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

    private Placement selectedTimelinePlacement() {
        var arrangement = activeTimeline();
        if (arrangement==timeline) return clip()!=null ? clip() : groupPlacement();
        return arrangement.project == null ? null : arrangement.project.clips().stream()
                .filter(c -> c.id().equals(arrangement.selected)).findFirst().orElse(null);
    }

    private void composeReviewNote() {
        showReview(true);
        var c = selectedTimelinePlacement();
        boolean onLane = activeTimeline().laneSelected;
        var range = transport.loop;
        var project = document.project();
        double tick = c == null ? 0 : activeTimeline().origin + c.startTick();
        double start = range != null ? range.start() : c != null ? ReviewTransport.secondsAt(project, tick) : transport.seconds;
        Double end = range != null ? Double.valueOf(range.end())
                : c == null ? null : Double.valueOf(ReviewTransport.secondsAt(project, tick + c.lengthTicks()));
        review.compose(new ReviewPane.Location(start, end, c instanceof Clip clip ? clip.lane()
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
        timeline.playhead = ReviewTransport.tickAt(document.project(), seconds);
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
        if(change(project->applyCatalogueVariant(index,variantId,project)))status.setText("Catalogue variant added to project");
    }

    private static StudioProject applyCatalogueVariant(InstrumentCatalogue index, String variantId, StudioProject project) {
        if (project.formatVersion()!=6)
            return CataloguePlayback.apply(index,variantId,project).withReview(project.references(),project.notes());
        // The catalogue installs assets against an arrangement profile. Fold those changes
        // back into the song, appending any audition clip after the group placements too.
        var profile=new StudioProject(project.arrangementVersion(),project.name(),project.mode(),project.ticksPerBeat(),
                project.tempo(),project.voices(),project.sequences(),project.clips(),project.envelopes(),project.samples(), project.target(), project.nativeTempo());
        var updated=CataloguePlayback.apply(index,variantId,profile);
        if (updated.equals(profile)) return project;
        var clips=new ArrayList<>(project.clips());
        for (Clip added : updated.clips().subList(profile.clips().size(),updated.clips().size())) {
            long start=GroupArrangement.endTick(project);
            if (start+added.lengthTicks()>Integer.MAX_VALUE)
                throw new IllegalArgumentException("The catalogue clip does not fit after the arrangement");
            String id="clip-"+UUID.randomUUID();
            clips.add(new Clip(id,added.sequenceId(),added.lane(),(int)start,added.lengthTicks()));
        }
        var result=new StudioProject(6,project.name(),project.mode(),project.ticksPerBeat(),project.tempo(),
                updated.voices(),updated.sequences(),clips,updated.envelopes(),updated.samples(),null,
                project.references(),project.notes(),updated.formatVersion(),project.groupSequences(),project.groupPlacements(),
                project.detachedFromReconstruction(), project.derivedFrom(), project.target(), project.nativeTempo());
        ProjectCompiler.compile(result);
        return result;
    }
    private void stopPlayback(){
        playbackEpoch++;
        playback.stop();
        arrangementPlaying = false;
    }
    private void stop(){stopPlayback();status.setText("Stopped");}
    private boolean save(){try{document.save();refreshStatus();status.setText("Saved "+document.path().getFileName());return true;}catch(IOException|IllegalArgumentException e){error(e);refreshStatus();return false;}}
    private void reload(){try{Path recovery=document.reloadKeepingRecovery();stop();history.clear();refreshView();status.setText(recovery==null?"Reloaded file":"Local edits preserved in "+recovery.getFileName());}catch(IOException|IllegalArgumentException e){error(e);refreshStatus();}}
    private void openReconstruction() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Open reconstruction work directory");
        var folder = chooser.showDialog(stage);
        if (folder != null) openReconstruction(folder.toPath());
    }

    void openReconstruction(Path work) {
        if (reconstruction != null && reconstruction.stage().isShowing()) {
            reconstruction.stage().close();
            if (reconstruction != null) return;
        }
        var pane = new ReconstructionPane(stage, work, () -> openArrangement(work), () -> document);
        reconstruction = pane;
        pane.stage().addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> {
            if (reconstruction == pane) reconstruction = null;
        });
    }

    private void openArrangement(Path work) {
        var source = work.resolve(ReconstructionReview.ARRANGEMENT).toAbsolutePath().normalize();
        if (!source.equals(document.path())) {
            if (!confirmLeave()) return;
            try {
                load(source);
            } catch (IOException | IllegalArgumentException failure) {
                error(failure);
                return;
            }
        }
        stage.toFront();
        stage.requestFocus();
    }

    private FileChooser chooser(String heading,String extension){FileChooser chooser=new FileChooser();chooser.setTitle(heading);chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extension.equals("json")?"SMPS Studio project":"Export ("+extension+")","*."+extension));return chooser;}
    private void open() {
        if (!confirmLeave()) return;
        var file = chooser("Open shared project", "json").showOpenDialog(stage);
        if (file == null) return;
        Path path = file.toPath();
        try {
            var project = ProjectFiles.loadSource(path);
            try {
                ProjectFormat.requireResolved(project);
            } catch (IllegalArgumentException redacted) {
                ExportDialog.showRedacted(stage, project, path, RomLibrary.userLibrary(), io, status::setText);
                return;
            }
            load(path);
        } catch (IOException | IllegalArgumentException failure) {
            error(failure);
        }
    }
    private void saveAs(){var chooser=chooser("Save a project copy (choose a new file)","json");chooser.setInitialFileName("project-copy.smps.json");var file=chooser.showSaveDialog(stage);if(file!=null)try{ProjectFiles.save(file.toPath(),relocateReview(document.project(),document.path(),file.toPath()),null);load(file.toPath());status.setText("Saved project copy");}catch(IOException|IllegalArgumentException e){error(e);}}
    static StudioProject relocateReview(StudioProject project, Path original, Path destination) {
        return project.withReview(project.references().stream().map(reference -> new AudioReference(reference.id(),
                reference.label(), AudioReference.pathFor(destination, reference.resolve(original)),
                reference.offsetSeconds(), reference.gainDb(), reference.role())).toList(), project.notes());
    }
    private void newProject(){if(!confirmLeave())return;var chooser=chooser("Create a shared project","json");chooser.setInitialFileName("untitled.smps.json");var file=chooser.showSaveDialog(stage);if(file!=null)try{StudioProject demo=DemoProject.create();ProjectFiles.save(file.toPath(),new StudioProject(1,"Untitled","S3K",demo.ticksPerBeat(),demo.tempo(),demo.voices(),List.of(),List.of(), TargetGame.S3K, null),null);selectedClip=null;selectedSequence=null;selectedNote=null;load(file.toPath());}catch(IOException|IllegalArgumentException e){error(e);}}
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

    private void importSuno() {
        var chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Choose the Suno download folder");
        var folder = chooser.showDialog(stage);
        if (folder == null) return;
        chooser.setTitle("Choose a separate reconstruction work directory");
        var work = chooser.showDialog(stage);
        if (work == null) return;
        openSunoImport(folder.toPath(),work.toPath());
    }

    Stage openSunoImport(Path folder,Path work) {
        var window = new Stage();
        window.initOwner(stage);
        window.setTitle("Import Suno folder");
        var pane = new SunoImportPane(folder, work, output -> {
            try {
                openSavedProject(output, "Reconstructed from Suno");
                window.close();
            } catch (IOException | IllegalArgumentException failure) {
                error(failure);
            }
        });
        var scene = new Scene(pane, 970, 520);
        scene.getStylesheets().setAll(stage.getScene().getStylesheets());
        window.setScene(scene);
        window.setOnHidden(event -> pane.close());
        window.show();
        return window;
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
    private void export() {
        var project = document.project();
        var work = reconstructionWork(document.path());
        ExportDialog.show(stage, project, document.path(), work, RomLibrary.userLibrary(), io, status::setText);
    }
    private void error(Exception e){status.setText("Could not apply: "+e.getMessage());}
    @Override public void close(){if(closed)return;closed=true;if(reconstruction!=null)reconstruction.close();if(watcher!=null)watcher.close();playback.close();timeline.referenceLane.close();nativeArrangement.timeline.referenceLane.close();samples.close();nativeArrangement.close();catalogue.close();io.shutdownNow();}
}
