package com.opensmpsdeck.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionEditing;
import com.opensmpsdeck.headless.ReconstructionEditCommand;
import com.opensmpsdeck.headless.ReconstructionProposals;
import com.opensmpsdeck.headless.RenderComparison;
import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.BendPoint;
import com.opensmpsdeck.headless.ReconstructionLaneType;
import com.opensmpsdeck.headless.ReconstructionVoicePlan;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.headless.ReconstructionReview.EditResult;
import com.opensmpsdeck.headless.ReconstructionReview.Revision;
import com.opensmpsdeck.headless.ReconstructionLivePreview;
import com.opensmpsdeck.headless.ReconstructionSections;
import com.opensmpsdeck.project.ProjectWatcher;
import com.opensmpsdeck.project.ProjectDocument;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetProfile;
import com.opensmpsdeck.studio.ReconstructionTools.Mode;
import com.opensmpsdeck.studio.ReconstructionTools.Placement;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.opensmpsdeck.studio.ReconstructionDraft.text;

/** Owns draft history, note controls and the two watched reconstruction journals. */
final class ReconstructionEditor implements AutoCloseable {
    record Snapshot(ReconstructionInspection inspection, Revision revision,
                    Map<String, List<EditResult>> outcomes, BasicFileAttributes report, ReconstructionEditing editing,
                    ReconstructionVoicePlan.Result voices, List<ReconstructionProposals.Result> proposals) {
        static Snapshot read(Path work) throws IOException {
            var revision = ReconstructionReview.revision(work);
            revision.entries();
            var report = report(work);
            var inspection = ReconstructionInspection.open(work);
            var outcomes = ReconstructionReview.outcomes(work);
            var editing = ReconstructionEditing.open(work);
            var voices = ReconstructionReview.voices(work, revision.entries());
            var proposals = ReconstructionProposals.results(work);
            if (!revision.equals(ReconstructionReview.revision(work))) throw new ReconstructionReview.Changed();
            return new Snapshot(inspection, revision, outcomes, report, editing, voices, proposals);
        }

        Snapshot journalsFromDisk(Path work, Revision incoming, boolean proposalsOnly) throws IOException {
            var refreshedVoices = proposalsOnly ? voices : ReconstructionReview.voices(work, incoming.entries());
            var proposals = ReconstructionProposals.results(work);
            if (!incoming.equals(ReconstructionReview.revision(work))) throw new ReconstructionReview.Changed();
            return new Snapshot(inspection, incoming, outcomes, report, editing, refreshedVoices, proposals);
        }

        private static BasicFileAttributes report(Path work) throws IOException {
            try {
                return Files.readAttributes(work.resolve("report.json"), BasicFileAttributes.class);
            } catch (java.nio.file.NoSuchFileException absent) {
                return null;
            }
        }
    }

    private record EditRow(int index, String heading, String detail) {
        @Override public String toString() { return heading + "\n" + detail; }
    }

    private static final String EMPTY_TRACE = "Select a note in the spectrogram or lane strip.";
    private final Path work;
    private final Window owner;
    private final ReconstructionCanvas view;
    private final Consumer<String> status;
    private final Consumer<ReconstructionInspection> refresh;
    private final DoubleConsumer previewPosition;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().daemon().name("reconstruction-editor").unstarted(task));
    private final ExecutorService canonicalWorker = Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().daemon().name("reconstruction-replay").unstarted(task));
    private final ExecutorService refitWorker = Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().daemon().name("reconstruction-refit").unstarted(task));
    private java.util.concurrent.Future<?> refitJob;
    private long comparisonGeneration;
    private final ReconstructionReview.LiveReplay canonicalReplay = new ReconstructionReview.LiveReplay();
    private volatile Thread canonicalThread;
    private final List<ProjectWatcher> watchers = new ArrayList<>();
    private final ReconstructionDraft draft;
    private ReconstructionTransport liveTransport;
    Runnable onCompare = () -> {};
    Runnable onJournalState = () -> {};
    private int branchCount;
    private final Map<JsonArray, JsonArray> branchJournals = new LinkedHashMap<>();
    private ReconstructionLivePreview live;
    private String liveUnavailable;
    private JsonArray liveBase;
    private String liveVoiceJournal;
    private boolean liveSaving;
    private volatile long liveGeneration;
    private boolean preparingLive;
    private LiveCandidate queuedCanonical;
    private LiveRequest queuedLiveEdit;

    private record LiveRequest(long generation, long eventNanos, JsonArray entries,
                               ReconstructionTransport.LiveEdit playback, String author) {}

    private record LiveCandidate(LiveRequest request, Revision expected,
                                 ReconstructionLivePreview.Prepared playback) {}

    private record LiveComparison(ReconstructionLivePreview.Prepared current,
                                  ReconstructionLivePreview.Prepared a,
                                  ReconstructionLivePreview.Prepared b,
                                  JsonObject choice, String label) {}
    private LiveComparison liveComparison;
    private final Button switchAB = new Button("Hear B");
    private final Button endAB = new Button("End A/B");
    private final Button keepB = new Button("Apply B");
    private boolean hearingB;

    private final MenuButton lanePolicies = new MenuButton("Lane policies");
    private final Button apply = new Button("Apply");
    private final Button cancel = new Button("Cancel");
    private final MenuItem undo = new MenuItem("Undo");
    private final MenuItem redo = new MenuItem("Redo");
    private final MenuButton history = new MenuButton("Changes", null, undo, redo);
    private final Label nextStep = new Label();
    private final VBox noteInspector = new VBox();
    private final VBox roleInspector = new VBox(8);
    private final VBox assignment = new VBox(8);
    private final Label assignmentHint = new Label();
    private final VBox placement = new VBox(8);
    private ScrollPane noteDetail;
    private FlowPane roleChooser;
    private final Label emptyNotes = new Label("Click a note to inspect it, or drag across the spectrogram to select several.");
    private List<String> inspectedSelection = List.of();
    private int proposalCount;
    Runnable onSelectionChanged = () -> {};
    Runnable onVoicesChanged = () -> {};
    private final Button conflict = new Button("Resolve conflict…");
    private final ProgressBar progress = new ProgressBar(0);
    private final CheckBox outline = new CheckBox("Outlines (O)");
    private record InsertVoice(ReconstructionInspection.Part part, String id, String label) {
        @Override public String toString() { return label; }
    }

    private final ComboBox<InsertVoice> parts = new ComboBox<>();
    private final Label noteTitle = new Label("Note trace");
    private final Label noteSummary = new Label(EMPTY_TRACE);
    private final TextArea rawTrace = new TextArea();
    private final TitledPane raw = new TitledPane("Raw trace JSON", rawTrace);
    private final ComboBox<String> role = new ComboBox<>(FXCollections.observableArrayList(
            "melody", "counter", "harmony", "bass", "drums", "drop"));
    private final ComboBox<String> lane = new ComboBox<>(FXCollections.observableArrayList(TimelineView.LANES));
    private final Spinner<Integer> velocity = new Spinner<>(1, 127, 96);
    private final Button noteDelete = new Button("Delete note");
    private final ListView<EditRow> journal = new ListView<>();
    private final Button reviseEdit = new Button("Revise selected edit…");
    private final Button remove = new Button("Remove selected edit");
    private final Label comparison = new Label("Apply a change to compare reconstruction output.");
    private final Tab editTab = new Tab("Edits");
    private final Tab diffTab = new Tab("Trace diff");
    private final Tab proposalTab = new Tab("Proposals");
    private final VBox proposalList = new VBox(8);
    private final CheckBox showDecidedProposals = new CheckBox("Show decided");
    private final java.util.Set<String> selectedProposals = new java.util.HashSet<>();
    private final Button stopPreview = new Button("Stop preview");
    private final TabPane tabs = new TabPane(proposalTab, editTab, diffTab);
    private final ReconstructionBulk bulk;
    private Map<String, List<JsonObject>> draftPitchEdits = Map.of();
    private final ReconstructionVoiceLegend voiceLegend;
    private final ReconstructionVoices.Palette voicePalette;
    private final StudioPlayback audition;

    private Snapshot snapshot;
    private List<ReconstructionSections.Section> sections;
    private Snapshot external;
    private Overlay selected;
    private boolean busy;
    private boolean closed;
    private boolean externalError;
    private boolean externalUnbuilt;
    private boolean refreshQueued;
    private boolean checkAgain;
    private final Object jobLock = new Object();
    private Thread running;
    private boolean cancelling;
    private boolean publishing;
    private long auditionEpoch;
    private boolean previewPlaying;
    private final Map<String, JsonObject> noteModulation = new LinkedHashMap<>();
    private Map<String, ReconstructionBulk.Voice> draftVoices = Map.of();
    private Map<String, String> draftMembership = Map.of();
    private static final Set<String> DETAIL_EDITS = Set.of("insertNote", "deleteNote", "setNote", "setVelocity",
            "splitNote", "mergeNotes", "retrigger", "bend", "glide", "noteVoicing", "layer", "arpeggio", "chordInstrument");

    private final Supplier<ProjectDocument> openProject;
    private record CopiedNote(double offset, double length, int pitch, int velocity) {}
    private List<CopiedNote> clipboard = List.of();
    DoubleConsumer playFrom = ignored -> {};
    Runnable screenshotLasso = () -> {};

    ReconstructionEditor(Path work, Window owner, ReconstructionCanvas view, Snapshot initial,
                         Consumer<String> status, Consumer<ReconstructionInspection> refresh,
                         DoubleConsumer previewPosition, Supplier<ProjectDocument> openProject,
                         StudioPlayback.LineFactory audioLines) {
        audition = new StudioPlayback(audioLines);
        this.openProject = openProject;
        this.work = work;
        this.owner = owner;
        this.view = view;
        this.snapshot = initial;
        this.status = status;
        this.refresh = refresh;
        this.previewPosition = previewPosition;
        sections = initial.inspection().sections().sections();
        draft = new ReconstructionDraft(accepted(initial.revision().entries()));
        bulk = new ReconstructionBulk(view::filterSelection, this::addBatch, this::transpose, this::audition, this::stopAudition, this::previewLayer);
        voicePalette = new ReconstructionVoices.Palette(work);
        voiceLegend = new ReconstructionVoiceLegend(view, this::assignVoice, this::createLegendVoice, this::voiceActions, this::editVoiceTag);
        view.onSelection = this::selectionChanged;
        view.onEffect = this::gesture;
        view.attack = this::attack;
        view.vibrato = this::vibrato;
        view.onInsert = this::insert;
        view.onPlacement = this::move;
        view.onSectionEdit = this::section;
        view.onSectionMenu = this::sectionMenu;
        view.onContext = this::contextMenu;
        for (String filename : List.of("edits.json", "sections.json", ReconstructionVoicePlan.JOURNAL, "report.json")) {
            try {
                watchers.add(new ProjectWatcher(work.resolve(filename), () -> Platform.runLater(this::readExternal)));
            } catch (IOException failure) {
                status.accept("Could not watch " + filename + " · " + describe(failure));
            }
        }
    }

    FlowPane toolbar() {
        var buttons = new ToggleGroup();
        var bar = new FlowPane(8, 6);
        bar.getStyleClass().addAll("panelbar", "reconstruction-controls");
        bar.setId("reconstruction-note-tools");
        bar.setAlignment(Pos.CENTER_LEFT);
        for (var mode : Mode.values()) {
            var button = new ToggleButton(mode.label);
            button.setId("reconstruction-tool-" + mode.name().toLowerCase(Locale.ROOT));
            button.setToggleGroup(buttons);
            button.setTooltip(new Tooltip(switch (mode) {
                case SELECT -> "Drag from empty space to box-select. Shift adds. Ctrl toggles. Drag a note to move it.";
                case INSERT -> "Click an empty pitch row to insert";
                case CUT -> "Click inside a note. Alt-click at a new pitch to retune the second half.";
                case BEND -> "Drag to bend a note or edit a point. Hold Shift to snap to semitones.";
                case VIBRATO -> "Drag from a point on an FM note. Horizontal sets rate, vertical sets depth. Alt-click a marker to remove it.";
            }));
            bar.getChildren().add(button);
        }
        buttons.selectToggle(buttons.getToggles().getFirst());
        buttons.selectedToggleProperty().addListener((ignored, old, next) -> {
            if (next == null) buttons.selectToggle(old);
            else view.mode(Mode.values()[buttons.getToggles().indexOf(next)]);
        });
        parts.setId("reconstruction-part");
        parts.setPromptText("Choose part");
        parts.setPrefWidth(185);
        parts.valueProperty().addListener((ignored, old, next) -> view.insertAvailable(next != null));
        var grid = new ComboBox<>(FXCollections.observableArrayList(ReconstructionInspection.Grid.values()));
        grid.setValue(ReconstructionInspection.Grid.BEAT);
        grid.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ReconstructionInspection.Grid value) {
                return value == null ? "" : value == ReconstructionInspection.Grid.BEAT ? "Beat" : "Bar";
            }
            @Override public ReconstructionInspection.Grid fromString(String text) { return null; }
        });
        grid.setTooltip(new Tooltip("Hold Alt during placement to bypass snapping"));
        grid.valueProperty().addListener((ignored, old, next) -> view.grid(next));
        bar.getChildren().addAll(new Label("Insert voice"), parts, new Label("Snap"), grid);
        return bar;
    }

    HBox changeControls() {
        apply.setId("reconstruction-apply");
        apply.setOnAction(event -> apply(null));
        lanePolicies.setId("reconstruction-lane-policies");
        var target = snapshot.inspection().target();
        var layering = new MenuItem(target.name() + " layering");
        layering.setOnAction(event -> apply(target.id() + "-layering"));
        var off = new MenuItem("Off");
        off.setOnAction(event -> apply("off"));
        lanePolicies.getItems().setAll(layering, off);
        cancel.setOnAction(event -> cancel());
        undo.setOnAction(event -> history(false));
        redo.setOnAction(event -> history(true));
        conflict.setId("reconstruction-conflict");
        conflict.setOnAction(event -> resolve());
        progress.setPrefWidth(108);
        for (Control control : List.of(progress, cancel, conflict)) control.managedProperty().bind(control.visibleProperty());
        history.setId("reconstruction-history");
        history.setTooltip(new Tooltip("Undo or redo reconstruction edits"));
        history.setDisable(true);
        apply.setDisable(true);
        apply.managedProperty().bind(apply.visibleProperty());
        history.managedProperty().bind(history.visibleProperty());
        apply.getStyleClass().add("primary-action");
        var compareHistory = new MenuItem("Compare before last N edits…");
        compareHistory.setOnAction(event -> compareHistory());
        var stock = new MenuItem("Compare stock voices for selected part…");
        stock.setOnAction(event -> comparePart(false));
        var refit = new MenuItem("Refit voice for this part");
        refit.setOnAction(event -> comparePart(true));
        var compare = new MenuButton("A/B", null, compareHistory, stock, refit);
        compare.setId("reconstruction-live-compare");
        switchAB.setId("reconstruction-live-switch");
        switchAB.setOnAction(event -> switchComparison());
        endAB.setOnAction(event -> endComparison(true));
        keepB.setOnAction(event -> {
            var choice = liveComparison.choice();
            endComparison(true);
            add(choice.deepCopy());
        });
        for (var button : List.of(switchAB, endAB, keepB)) {
            button.managedProperty().bind(button.visibleProperty());
            button.setVisible(false);
        }
        var changes = new HBox(4, compare, switchAB, endAB, keepB, apply, history, progress, cancel, conflict);
        changes.setAlignment(Pos.CENTER_RIGHT);
        return changes;
    }

    FlowPane visibility() {
        var opacity = new Slider(0, 1, .34);
        opacity.setPrefWidth(100);
        opacity.valueProperty().addListener((ignored, old, next) -> view.opacity(next.doubleValue()));
        outline.setOnAction(event -> view.outline(outline.isSelected()));
        var row = new FlowPane(8, 6, new Label("Note opacity"), opacity, outline);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    Label nextStep() { return nextStep; }
    VBox rolesInspector() { return roleInspector; }
    VBox notesInspector() { return noteInspector; }
    TabPane reviewInspector() { return tabs; }

    Revision branchRevision() { return snapshot.revision(); }

    boolean readyForBranch() {
        return editable() && !liveSaving && !preparingLive && !draft.pending();
    }

    void branchCount(int count) {
        branchCount = count;
        updateControls();
    }

    boolean auditionBranch(ReconstructionLivePreview.Prepared variant, String label) {
        var playhead = liveTransport.liveEdit();
        if (!readyForBranch() || playhead == null) return false;
        endComparison(true);
        liveGeneration++; // Invalidate a legacy A/B still preparing.
        return liveTransport.compareLive(playhead, variant, label);
    }

    void applyBranch(Revision expected, JsonArray entries) {
        if (!readyForBranch() || !expected.equals(snapshot.revision())) {
            status.accept("Main changed · refresh the merge and finish any pending changes");
            return;
        }
        var merged = accepted(entries);
        if (merged.equals(draft.entries())) return;
        branchJournals.put(draft.entries(), expected.entries());
        branchJournals.put(merged, entries.deepCopy());
        draft.change(next -> {
            next.asList().clear();
            merged.asList().forEach(next::add);
        });
        journalChanged();
        if (liveTransport.liveEdit() == null) apply(null);
    }

    void initializeInspectors() {
        noteTitle.getStyleClass().add("inspector-title");
        noteTitle.setWrapText(true);
        noteTitle.setId("reconstruction-note-title");
        noteSummary.setWrapText(true);
        noteSummary.setId("reconstruction-note-summary");
        rawTrace.setId("reconstruction-trace");
        rawTrace.setEditable(false);
        rawTrace.setWrapText(true);
        rawTrace.setPrefRowCount(12);
        raw.setId("reconstruction-raw-trace");
        raw.setExpanded(false);
        role.setId("reconstruction-assign-role");
        role.setPromptText("Role");
        lane.setPromptText("Lane");
        velocity.setId("reconstruction-velocity");
        velocity.setEditable(true);
        velocity.setPrefWidth(95);
        var setRole = new Button("Set role");
        setRole.setId("reconstruction-set-role");
        setRole.setOnAction(event -> {
            if (role.getValue() != null)
                setRole(view.selectedNotes().stream().filter(ReconstructionEditor::classifiable).toList(), role.getValue());
        });
        role.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String value) { return value == null ? "" : readableKey(value); }
            @Override public String fromString(String text) { return null; }
        });
        var pin = new Button("Pin lane");
        pin.setOnAction(event -> {
            if (selected != null && lane.getValue() != null)
                target("pinLane", "lane", lane.getValue(), "Pin lane in Studio");
        });
        var setVelocity = new Button("Set velocity");
        setVelocity.setOnAction(event -> {
            if (selected == null || !editableScore(selected)) return;
            try {
                int value = Integer.parseInt(velocity.getEditor().getText().trim());
                if (value < 1 || value > 127) throw new NumberFormatException();
                var intent = single("setNote", selected, "Set velocity in Studio");
                intent.addProperty("velocity", value);
                add(intent);
            } catch (NumberFormatException invalid) {
                status.accept("Velocity must be a whole number from 1 to 127.");
            }
        });
        placement.getChildren().addAll(new FlowPane(6, 5, lane, pin),
                new FlowPane(6, 5, velocity, setVelocity), noteDelete);
        placement.managedProperty().bind(placement.visibleProperty());
        setRole.disableProperty().bind(role.disabledProperty());
        pin.disableProperty().bind(lane.disabledProperty());
        setVelocity.disableProperty().bind(velocity.disabledProperty());
        noteDelete.setOnAction(event -> delete());

        var noteContent = new VBox(12, noteTitle);
        noteContent.getStyleClass().add("inspector");
        var evidence = new TitledPane("Note evidence", new VBox(10, noteSummary, raw));
        evidence.setExpanded(false);
        evidence.setAnimated(false);
        evidence.setId("reconstruction-note-evidence");
        var selectedNote = new VBox(10, placement, evidence);
        emptyNotes.setWrapText(true);
        emptyNotes.managedProperty().bind(emptyNotes.visibleProperty());
        noteContent.getChildren().addAll(emptyNotes, selectedNote, bulk, lanePolicies);
        selectedNote.visibleProperty().bind(placement.visibleProperty());
        selectedNote.managedProperty().bind(selectedNote.visibleProperty());
        bulk.managedProperty().bind(bulk.visibleProperty());
        noteDetail = scroll(noteContent);
        noteDetail.setMinHeight(0);
        VBox.setVgrow(noteDetail, Priority.ALWAYS);
        noteInspector.getChildren().add(noteDetail);

        roleInspector.getStyleClass().add("reconstruction-role-inspector");
        assignmentHint.setWrapText(true);
        assignment.getStyleClass().add("inspector");
        assignmentHint.setId("reconstruction-role-selection");
        assignmentHint.setMinHeight(Region.USE_PREF_SIZE);
        roleChooser = new FlowPane(6, 5, role, setRole);
        roleChooser.managedProperty().bind(roleChooser.visibleProperty());
        assignment.getChildren().addAll(assignmentHint, roleChooser);
        roleInspector.getChildren().addAll(assignment, voiceLegend);
        VBox.setVgrow(voiceLegend, Priority.ALWAYS);
        voiceLegend.rolesView(true);
        journal.setId("reconstruction-edit-list");
        journal.setCellFactory(ignored -> new ListCell<>() {
            private final Label label = new Label();
            {
                label.setWrapText(true);
                label.maxWidthProperty().bind(widthProperty().subtract(20));
            }
            @Override protected void updateItem(EditRow item, boolean empty) {
                super.updateItem(item, empty);
                label.setText(empty || item == null ? "" : item.toString());
                setGraphic(empty ? null : label);
            }
        });
        journal.getSelectionModel().selectedItemProperty().addListener((ignored, old, next) -> updateControls());
        remove.setOnAction(event -> {
            var row = journal.getSelectionModel().getSelectedItem();
            if (row == null || row.index() < 0 || busy || external != null) return;
            draft.remove(row.index());
            journalChanged();
        });
        reviseEdit.setOnAction(event -> reviseJournalEdit());
        var editList = new VBox(8, journal, new FlowPane(5, 4, reviseEdit, remove));
        editList.getStyleClass().add("inspector");
        VBox.setVgrow(journal, Priority.ALWAYS);
        editTab.setContent(editList);
        proposalTab.setId("reconstruction-proposals-tab");
        proposalList.setId("reconstruction-proposals");
        proposalList.getStyleClass().add("inspector");
        stopPreview.setOnAction(event -> stopAudition());
        showDecidedProposals.setOnAction(event -> updateProposals());
        var proposalPanel = new VBox(8, new FlowPane(5, 4, stopPreview, showDecidedProposals), scroll(proposalList));
        VBox.setVgrow(proposalPanel.getChildren().getLast(), Priority.ALWAYS);
        proposalTab.setContent(proposalPanel);
        comparison.setWrapText(true);
        comparison.setId("reconstruction-diff-summary");
        var summary = new VBox(comparison);
        summary.getStyleClass().add("inspector");
        diffTab.setContent(scroll(summary));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setId("reconstruction-inspector-tabs");
        tabs.setMinHeight(0);
        updateDraft();
        readExternal();
        selectionChanged();
    }

    private static ScrollPane scroll(VBox content) {
        var pane = new ScrollPane(content);
        pane.getStyleClass().add("reconstruction-inspector-scroll");
        pane.setFitToWidth(true);
        return pane;
    }

    void show(Overlay note) {
        selected = note;
        var inspection = snapshot.inspection();
        String json = note == null ? "" : inspection.trace(note.id());
        raw.setVisible(!json.isEmpty());
        raw.setManaged(raw.isVisible());
        if (note == null) {
            noteTitle.setText("No notes selected");
            noteSummary.setText(EMPTY_TRACE);
            rawTrace.clear();
            updateControls();
            return;
        }
        noteTitle.setText(view.noteLabel(note));
        var detail = inspection.noteDetails(note.id());
        var time = note.interval();
        String body = String.format(Locale.ROOT, "%s%s%n%nRole  %s%nLane  %s%nInstrument  %s"
                        + "%n%nSource  %.3f–%.3f s%nProject  %.3f–%.3f s%n%nCompeting notes  %s%nEdits applied  %s",
                note.fate(), note.reason() == null ? "" : "\n" + note.reason(), ReconstructionCanvas.roleName(note.role()),
                orDash(note.lane()), orDash(note.patch()), time.sourceStart(), time.sourceEnd(),
                time.projectStart(), time.projectEnd(), names(note.competitors()), names(detail.edits()));
        String stockReason = inspection.stockVoiceReason(note);
        if (stockReason != null) body += "\n\nAutomatic FM fit\n" + stockReason;
        var vibration = vibrato(note);
        if (!vibration.isEmpty()) body += "\n\nVibrato\n" + vibration.stream().map(ReconstructionCanvas::vibratoLabel)
                .collect(Collectors.joining("\n"));
        for (var layer : inspection.noteLayers(note.id())) {
            String result = layer.lane() == null ? layer.reason() : layer.lane() + " · " + layer.delayFrames()
                    + " frames · " + layer.attenuation() + " TL";
            body += "\n\nLayer  " + layer.mode() + " · " + layer.status() + "\n" + result;
        }
        noteSummary.setText(body);
        rawTrace.setText(json);
        rawTrace.positionCaret(0);
        role.setValue(switch (note.role()) {
            case MELODY, COUNTER, HARMONY, BASS, DRUMS -> note.role().name().toLowerCase(Locale.ROOT);
            default -> null;
        });
        lane.setValue(note.lane());
        velocity.getValueFactory().setValue(pendingVelocity(note, detail.velocity() == null ? 96 : detail.velocity()));
        velocity.getEditor().setText(Integer.toString(velocity.getValue()));
        updateControls();
    }

    private void selectionChanged() {
        updateBulk();
        var notes = view.selectedNotes();
        int count = notes.size();
        placement.setVisible(count == 1);
        bulk.setVisible(count != 0);
        emptyNotes.setVisible(count == 0);
        noteTitle.setVisible(count <= 1);
        noteTitle.setManaged(count <= 1);
        var ids = notes.stream().map(Overlay::id).toList();
        if (!ids.equals(inspectedSelection)) noteDetail.setVvalue(0);
        inspectedSelection = ids;
        assignmentHint.setText(count == 0
                ? "Select notes on the canvas or from the legend to assign a role or voice."
                : count + (count == 1 ? " note selected" : " notes selected"));
        boolean canAssign = notes.stream().anyMatch(ReconstructionEditor::classifiable);
        roleChooser.setVisible(canAssign);
        if (count > 1) role.setValue(null);
        role.setDisable(!canAssign || busy || external != null || externalError);
        onSelectionChanged.run();
    }

    private void updateVoiceProjection() {
        var voices = new LinkedHashMap<String, ReconstructionBulk.Voice>();
        var membership = new LinkedHashMap<String, String>();
        for (var voice : snapshot.voices().voices()) {
            voices.put(voice.id(), new ReconstructionBulk.Voice(voice.id(), voice.name(), voice.kind(), voice.assignment()));
            for (String id : voice.noteIds()) membership.put(id, voice.id());
        }
        for (var entry : draft.entries()) {
            if (!draft.changed(entry) || ReconstructionReview.validationIssue(entry) != null) continue;
            String op = text(entry, "op", "");
            if (op.equals("splitNote")) {
                String owner = membership.get(targetId(entry));
                String prefix = "edit-" + text(entry, "id", "");
                if (owner != null) {
                    membership.put(prefix + "-left", owner);
                    membership.put(prefix + "-right", owner);
                }
            }
            if (!op.equals("voiceEdit")) continue;
            var body = entry.getAsJsonObject().getAsJsonObject("voiceEdit");
            String id = body.get("voiceId").getAsString();
            switch (body.get("op").getAsString()) {
                case "create" -> voices.put(id, new ReconstructionBulk.Voice(id, body.get("name").getAsString(),
                        ReconstructionVoicePlan.Kind.valueOf(body.get("kind").getAsString()), null));
                case "move" -> {
                    if (body.has("noteIds")) body.getAsJsonArray("noteIds").forEach(note -> membership.put(note.getAsString(), id));
                }
                case "rename" -> {
                    var voice = voices.get(id);
                    if (voice != null) voices.put(id, new ReconstructionBulk.Voice(id, body.get("name").getAsString(),
                            voice.kind(), voice.assignment()));
                }
                case "merge" -> {
                    String target = body.get("targetId").getAsString();
                    var source = voices.get(id);
                    var destination = voices.get(target);
                    if (source == null || destination == null) continue;
                    if (destination.assignment() == null)
                        voices.put(target, new ReconstructionBulk.Voice(target, destination.name(), destination.kind(), source.assignment()));
                    voices.remove(id);
                    membership.replaceAll((note, owner) -> owner.equals(id) ? target : owner);
                }
                case "split" -> {
                    var source = voices.get(id);
                    if (source == null || !body.has("noteIds")) continue;
                    String target = body.get("targetId").getAsString();
                    voices.put(target, new ReconstructionBulk.Voice(target, body.get("name").getAsString(),
                            source.kind(), source.assignment()));
                    body.getAsJsonArray("noteIds").forEach(note -> membership.put(note.getAsString(), target));
                }
                case "assign" -> {
                    var voice = voices.get(id);
                    if (voice == null) continue;
                    var assignment = body.getAsJsonObject("assignment");
                    var value = assignment == null ? null : new ReconstructionVoicePlan.Assignment(
                            ReconstructionLaneType.valueOf(assignment.get("laneType").getAsString()),
                            assignment.get("instrument").getAsString());
                    voices.put(id, new ReconstructionBulk.Voice(id, voice.name(), voice.kind(), value));
                }
                default -> { }
            }
        }
        draftVoices = Map.copyOf(voices);
        draftMembership = Map.copyOf(membership);
    }

    private void updateBulk() {
        var owners = new LinkedHashMap<String, ReconstructionBulk.Voice>();
        var notes = view.selectedNotes().stream().filter(ReconstructionEditor::classifiable).toList();
        for (var note : notes) {
            String ownerId = draftMembership.get(note.id());
            var owner = ownerId == null ? null : draftVoices.get(ownerId);
            if (owner != null) owners.put(note.id(), owner);
        }
        bulk.show(notes, draftVoices.values().stream().sorted(java.util.Comparator.comparing(ReconstructionBulk.Voice::name)).toList(), owners, snapshot.editing(), snapshot.inspection()::laneType);
    }

    private static boolean classifiable(Overlay note) {
        return note.part() >= 0 && !note.fate().equals("Pending deletion") && !note.fate().equals("Superseded draft");
    }

    private void editVoiceTag(ReconstructionVoices.Entry entry) {
        var dialog = new TextInputDialog(entry.tag());
        dialog.initOwner(owner);
        dialog.setTitle("Edit voice tag");
        dialog.setHeaderText(entry.name());
        dialog.setContentText("Tag (up to 6 letters or digits, blank for automatic)");
        dialog.getDialogPane().setId("reconstruction-voice-tag");
        var field = dialog.getEditor();
        field.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("[\\p{L}\\p{N}]{0,6}") ? change : null));
        dialog.showAndWait().ifPresent(tag -> {
            var projection = view.voices();
            if (!tag.isEmpty() && projection.entries().stream()
                    .anyMatch(other -> !entry.id().equals(other.id()) && tag.equalsIgnoreCase(other.tag()))) {
                status.accept("Voice tag is already in use · " + tag);
                return;
            }
            voicePalette.tag(entry.id(), tag);
            var updated = ReconstructionVoices.of(snapshot.inspection().overlays(), overlays(),
                    draftVoices, draftMembership, voicePalette);
            view.voiceTags(updated);
            voiceLegend.show(updated.entries());
            updateInsertVoices(updated);
            onVoicesChanged.run();
        });
    }

    private boolean assignVoice(ReconstructionBulk.Voice voice) {
        String issue = bulk.moveTo(voice);
        if (issue != null) status.accept(issue);
        return issue == null;
    }

    private void createLegendVoice(ReconstructionVoicePlan.Kind emptyKind) {
        voiceName("New voice", "").ifPresent(name -> {
            String issue = bulk.createVoice(name, emptyKind);
            if (issue != null) status.accept(issue);
        });
    }

    private ContextMenu voiceActions(ReconstructionBulk.Voice voice) {
        var rename = new MenuItem("Rename voice…");
        rename.setOnAction(event -> voiceName("Rename voice", voice.name()).ifPresent(name ->
                add(ReconstructionBulk.voiceEdit("rename", voice.id(), "Rename voice to " + name,
                        body -> body.addProperty("name", name)))));
        var notes = new JsonArray();
        for (var note : view.selectedNotes()) if (voice.id().equals(draftMembership.get(note.id()))) notes.add(note.id());
        var split = new MenuItem("Split selected notes to new voice…");
        split.setDisable(notes.isEmpty());
        split.setOnAction(event -> voiceName("Split voice", "").ifPresent(name ->
                add(ReconstructionBulk.voiceEdit("split", voice.id(), "Split voice " + voice.name(),
                        body -> {
                            body.add("noteIds", notes);
                            body.addProperty("name", name);
                            body.addProperty("targetId", "studio-voice-" + java.util.UUID.randomUUID());
                        }))));
        var merge = new Menu("Merge voice into");
        draftVoices.values().stream().filter(target -> !target.id().equals(voice.id()) && target.kind() == voice.kind())
                .sorted(java.util.Comparator.comparing(ReconstructionBulk.Voice::name)).forEach(target -> {
                    var item = new MenuItem(target.name());
                    item.setDisable(voice.assignment() != null && target.assignment() != null
                            && !voice.assignment().equals(target.assignment()));
                    item.setOnAction(event -> add(ReconstructionBulk.voiceEdit("merge", voice.id(),
                            "Merge " + voice.name() + " into " + target.name(),
                            body -> body.addProperty("targetId", target.id()))));
                    merge.getItems().add(item);
                });
        merge.setDisable(merge.getItems().isEmpty());
        return new ContextMenu(rename, split, merge);
    }

    private java.util.Optional<String> voiceName(String title, String value) {
        var dialog = new TextInputDialog(value);
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText("Voice name");
        dialog.getDialogPane().setId("reconstruction-voice-name");
        return dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty());
    }

    private ReconstructionEditing.Attack attack(Overlay note) {
        var original = snapshot.editing().attack(note.id());
        if (original == null && !note.fate().startsWith("Pending")) return null;
        if (note.fate().equals("Superseded draft") || note.fate().equals("Pending deletion")) return null;
        if (!note.fate().startsWith("Pending") && original != null) return original;
        boolean drums = snapshot.inspection().parts().stream().anyMatch(part -> part.id() == note.part() && part.drums());
        var pendingAttack = note.fate().equals("Pending insertion") || note.fate().equals("Pending cut");
        if (pendingAttack || original == null)
            return new ReconstructionEditing.Attack(note.interval().sourceStart(), note.interval().sourceEnd(),
                    note.pitch(), drums, original == null || original.single(), note.bend());
        var place = new Placement(original.start(), original.end(), original.pitch());
        for (var json : draftPitchEdits.getOrDefault(note.id(), List.of())) {
            String op = text(json, "op", "");
            if (op.equals("setNote")) {
                place = new Placement(number(json, "start", place.start()), number(json, "end", place.end()),
                        (int) number(json, "pitch", place.pitch()));
            } else if (op.equals("transpose")) {
                place = new Placement(place.start(), place.end(), place.pitch() + json.get("semitones").getAsInt());
            }
        }
        return new ReconstructionEditing.Attack(place.start(), place.end(), place.pitch(), drums, original.single(), note.bend());
    }

    private List<ReconstructionEditing.VibratoSegment> vibrato(Overlay note) {
        var edit = noteModulation.get(note.id());
        if (edit == null || note.lane() == null || !note.lane().startsWith("FM")) return List.of();
        if (edit.has("vibratoEnvelope")) return ReconstructionEditing.vibratoEnvelope(edit);
        if (edit.has("modulationOff")) return List.of();
        var nativeMod = edit.getAsJsonObject("vibrato");
        var legacy = snapshot.editing().vibrato(note.pitch(), note.lane(), nativeMod.get("rate").getAsInt(),
                nativeMod.get("delta").getAsInt(), nativeMod.get("steps").getAsInt());
        int onset = nativeMod.get("delay").getAsInt() + legacy.rate() - 2;
        return List.of(new ReconstructionEditing.VibratoSegment(onset, legacy.hz(), Math.min(200, legacy.cents())));
    }

    private void audition(List<String> ids, ReconstructionEditing.Instrument instrument) {
        if (busy || external != null || externalError) return;
        stopAudition();
        long request = auditionEpoch;
        status.accept("Preparing selection audition…");
        worker.execute(() -> {
            try {
                var sample = ReconstructionEditing.audition(work, java.util.Set.copyOf(ids), instrument);
                Platform.runLater(() -> {
                    if (closed || busy || request != auditionEpoch) return;
                    var transport = new ReviewTransport();
                    transport.seconds = sample.start();
                    // Stop at the end of the selected excerpt, including its release.
                    audition.playMonitor(sample.project(), transport, S3kRenderer.ChannelMask.NONE, sample.end(), () -> null,
                            message -> Platform.runLater(() -> {
                                if (!closed && request == auditionEpoch) status.accept(message);
                            }), position -> {
                                if (position >= sample.end()) Platform.runLater(() -> {
                                    if (request == auditionEpoch) stopAudition();
                                });
                            });
                });
            } catch (IOException | IllegalArgumentException failure) {
                Platform.runLater(() -> {
                    if (!closed && request == auditionEpoch) status.accept("Audition unavailable · " + describe(failure));
                });
            }
        });
    }

    void stopAudition() {
        previewPlaying = false;
        stopPreview.setDisable(true);
        auditionEpoch++;
        audition.stop();
    }

    private boolean copyForAgent() {
        var notes = view.selectedNotes();
        if (notes.isEmpty() || view.reviewing()) return false;
        var local = noteDraft();
        return AgentClipboard.copy(snapshot.inspection().copyNotes(notes, view.rolesView(), draft.pending(),
                snapshot.revision(), draftMembership, local.velocities(), local.entries(), snapshot.outcomes(), snapshot.proposals()), status);
    }

    com.opensmpsdeck.headless.NoteHandoff.Capture lasso(javafx.geometry.Rectangle2D box) {
        var notes = view.lassoNotes(box);
        var local = noteDraft();
        return snapshot.inspection().captureNotes(notes, view.rolesView(), draft.pending(), snapshot.revision(),
                draftMembership, local.velocities(), local.entries(), snapshot.outcomes(), snapshot.proposals(),
                view.lassoRange(box, notes));
    }

    private record NoteDraft(JsonArray entries, Map<String, Integer> velocities) {}

    private NoteDraft noteDraft() {
        var entries = draft.entries();
        var velocities = new java.util.HashMap<String, Integer>();
        if (draft.pending()) {
            for (var entry : entries) {
                if (!draft.changed(entry) || !entry.isJsonObject()) continue;
                var edit = entry.getAsJsonObject();
                if (edit.has("velocity") && ReconstructionReview.validationIssue(entry) == null) {
                    for (String target : targetIds(entry)) velocities.put(target, edit.get("velocity").getAsInt());
                    if ("insertNote".equals(edit.get("op").getAsString()))
                        velocities.put("edit-" + edit.get("id").getAsString(), edit.get("velocity").getAsInt());
                }
            }
        }
        return new NoteDraft(journalForDraft(snapshot.revision().entries(), entries), velocities);
    }

    private int pendingVelocity(Overlay note, int fallback) {
        int value = fallback;
        for (var entry : draft.entries()) {
            if (!draft.changed(entry) || !targetIds(entry).contains(note.id()) || ReconstructionReview.validationIssue(entry) != null)
                continue;
            var edit = entry.getAsJsonObject();
            if (edit.has("velocity")) value = edit.get("velocity").getAsInt();
            else if (text(edit, "op", "").equals("setVelocity") && edit.has("scale"))
                value = (int) Math.clamp(Math.round(value * edit.get("scale").getAsDouble()), 1, 127);
        }
        return value;
    }

    private static String orDash(String value) { return value == null ? "—" : value; }
    private static String names(List<String> values) { return values == null || values.isEmpty() ? "—" : String.join(", ", values); }

    private boolean editableScore(Overlay note) {
        return !note.fate().equals("Superseded draft")
                && (snapshot.inspection().noteDetails(note.id()).scored() || localInsert(note.id())
                    || note.fate().equals("Pending cut"));
    }

    private boolean localInsert(String noteId) {
        for (var entry : draft.entries()) {
            if (draft.changed(entry) && "insertNote".equals(text(entry, "op", ""))
                    && noteId.equals(targetId(entry))) return true;
        }
        return false;
    }

    private boolean selectableNote() {
        if (selected == null || selected.part() < 0) return false;
        var detail = snapshot.inspection().noteDetails(selected.id());
        return editableScore(selected) || detail.generated();
    }

    private void updateInsertVoices(ReconstructionVoices projection) {
        var previous = parts.getValue();
        var sourceParts = snapshot.inspection().parts();
        var targets = new ArrayList<InsertVoice>();
        for (var entry : projection.entries()) {
            if (entry.voice() == null) continue;
            var counts = entry.notes().stream().collect(Collectors.groupingBy(Overlay::part, Collectors.counting()));
            sourceParts.stream().filter(part -> counts.containsKey(part.id()) && part.drums() == entry.drum())
                    .max(java.util.Comparator.comparingLong(part -> counts.get(part.id())))
                    .ifPresent(part -> targets.add(new InsertVoice(part, entry.id(), entry.shortName())));
        }
        for (var part : sourceParts) targets.add(new InsertVoice(part, null,
                part.name().isBlank() ? "Part " + part.id() : part.name() + " (source)"));
        parts.getItems().setAll(targets);
        var next = previous == null ? null : targets.stream()
                .filter(item -> item.part().id() == previous.part().id() && Objects.equals(item.id(), previous.id()))
                .findFirst().orElse(null);
        if (next == null) next = targets.stream().filter(item -> item.id() == null && !item.part().drums())
                .findFirst().orElse(targets.isEmpty() ? null : targets.getFirst());
        parts.setValue(next);
    }

    private void insert(Placement location) {
        var voice = parts.getValue();
        if (busy || external != null || externalError || voice == null) return;
        if (!voice.part().drums() && (location.pitch() < 12 || location.pitch() > 106)) {
            status.accept("Choose a tonal pitch between MIDI 12 and 106.");
            return;
        }
        var intent = ReconstructionDraft.insert(voice.part().id(), location.start(), location.end(), location.pitch());
        String id = targetId(intent);
        var intents = new ArrayList<>(List.of(intent));
        assignInsertedNotes(intents, voice);
        addBatch(intents);
        overlays().stream().filter(note -> note.id().equals(id)).findFirst().ifPresent(note -> view.select(note, null));
    }

    private static void assignInsertedNotes(List<JsonObject> intents, InsertVoice voice) {
        if (voice.id() == null) return;
        var ids = new JsonArray();
        intents.stream().map(ReconstructionEditor::targetId).forEach(ids::add);
        intents.add(ReconstructionBulk.voiceEdit("move", voice.id(), "Assign inserted notes to " + voice.label(),
                body -> body.add("noteIds", ids)));
    }

    private void move(Overlay note, Placement placement) {
        if (busy || external != null) return;
        if (!editableScore(note)) {
            status.accept("This note has no editable score attack. You can change its role or drop it.");
            return;
        }
        var before = Placement.of(note);
        var intent = single("setNote", note, "Move or resize note in Studio");
        // A pitch-only drag keeps the authored gate. A resize writes both displayed bounds.
        if (before.start() != placement.start() || before.end() != placement.end()) {
            intent.addProperty("start", placement.start());
            intent.addProperty("end", placement.end());
        }
        if (before.pitch() != placement.pitch()) intent.addProperty("pitch", placement.pitch());
        add(intent);
    }

    private void target(String op, String field, String value, String reason) {
        if (!selectableNote()) return;
        add(ReconstructionDraft.target(selected.id(), op, field, value, reason));
    }

    private boolean editable() { return !busy && external == null && !externalError; }

    private List<Overlay> deletableNotes() {
        return view.selectedNotes().stream().filter(ReconstructionEditor::classifiable)
                .filter(note -> editableScore(note) || snapshot.inspection().noteDetails(note.id()).generated())
                .toList();
    }

    private void delete() {
        if (!editable()) return;
        var notes = deletableNotes();
        if (notes.isEmpty()) return;
        addBatch(notes.stream().map(note -> single("deleteNote", note, "Delete note in Studio")).toList());
        int count = notes.size();
        status.accept("Deleted " + count + (count == 1 ? " note" : " notes") + " · Undo");
    }

    private static JsonObject selectionEdit(List<Overlay> notes, String op, String reason) {
        var edit = ReconstructionDraft.human(op, reason);
        var ids = new JsonArray();
        notes.forEach(note -> ids.add(note.id()));
        edit.add("noteIds", ids);
        return edit;
    }

    private void setRole(List<Overlay> notes, String value) {
        if (notes.isEmpty()) return;
        var edit = selectionEdit(notes, "setRole", "Set role in Studio");
        edit.addProperty("role", value);
        add(edit);
    }

    private String editIssue(String issue) {
        return editable() ? issue : "Finish the rebuild or resolve external changes to edit.";
    }

    private void selectNotes(java.util.function.Predicate<Overlay> filter) {
        view.selectNotes(view.selectableOverlays().stream().filter(filter).toList());
        view.canvas.requestFocus();
    }

    private void contextMenu(ContextMenuEvent event, ReconstructionCanvas.ContextTarget target) {
        StudioContextMenu menu;
        if (view.reviewing()) {
            if (target.proposal() == null) return;
            var proposal = snapshot.proposals().stream().filter(item -> item.id().equals(target.proposal().id()))
                    .findFirst().orElse(null);
            if (proposal == null) return;
            menu = proposalMenu(proposal);
        } else if (target.note() != null) {
            menu = noteMenu(target.note(), target.time());
        } else if (!view.rolesView()) {
            menu = new StudioContextMenu();
            var place = target.insertion();
            if (place != null) {
                var voice = parts.getValue();
                String issue = voice == null ? "Choose an insert voice first."
                        : voice.part().drums() || place.pitch() >= 12 && place.pitch() <= 106
                                ? null : "Choose a pitch between MIDI 12 and 106.";
                menu.add("Insert note here", null, editIssue(issue), () -> insert(place))
                        .add("Paste", StudioContextMenu.shortcut("V"), editIssue(pasteIssue(place.start())),
                                () -> paste(place.start()));
            }
            menu.add("Play from here", () -> playFrom.accept(target.time()))
                    .add("Zoom to fit", view::zoomToFit)
                    .add("Select all", StudioContextMenu.shortcut("A"), null, () -> selectNotes(note -> true));
        } else {
            menu = new StudioContextMenu().add("Play from here", () -> playFrom.accept(target.time()));
        }
        if (!view.reviewing() && target.note() == null)
            ScreenshotLasso.addTo(menu, screenshotLasso);
        menu.show(view.canvas, event);
    }

    private StudioContextMenu noteMenu(Overlay hit, double time) {
        var notes = view.selectedNotes().stream().filter(ReconstructionEditor::classifiable).toList();
        var menu = new StudioContextMenu();
        var roles = role.getItems().stream().filter(value -> !notes.isEmpty()).map(value -> {
            var color = value.equals("drop") ? null : ReconstructionCanvas.color(Role.valueOf(value.toUpperCase(Locale.ROOT)));
            String issue = value.equals("drop") || notes.stream().allMatch(note -> drumPart(note) == value.equals("drums"))
                    ? null : "Select notes from compatible tonal or percussion parts.";
            return StudioContextMenu.item(readableKey(value), null, editIssue(issue), color, () -> setRole(notes, value));
        }).toList();
        String deleteIssue = editIssue(deletableNotes().isEmpty() ? "Select a scored or generated note." : null);
        if (view.rolesView()) {
            var voices = view.voices().entries().stream().filter(entry -> entry.voice() != null && !notes.isEmpty())
                    .map(entry -> StudioContextMenu.item(entry.shortName(), null, editIssue(bulk.moveIssue(entry.voice())),
                            entry.style().color(), () -> assignVoice(entry.voice()))).toList();
            menu.submenu("Set role", roles)
                    .submenu("Assign to voice", voices)
                    .add("Delete", "Delete / Backspace", deleteIssue, this::delete)
                    .add("Select all with this role", () -> { view.selectRole(hit.role(), false); view.canvas.requestFocus(); })
                    .add("Play from here", () -> playFrom.accept(time));
            AgentClipboard.addTo(menu, this::copyForAgent);
            return menu;
        }
        menu.add("Delete", "Delete / Backspace", deleteIssue, this::delete);
        var offsets = List.of(1, -1, 12, -12).stream().map(step -> StudioContextMenu.item(
                "%+d".formatted(step), TransposeKeys.label(step), editIssue(transposeIssue(step)), null, () -> transpose(step))).toList();
        menu.submenu("Transpose", offsets);
        var data = attack(hit);
        var model = snapshot.inspection();
        long tick = Math.round(model.toProject(time) * 60);
        boolean split = notes.size() == 1 && data != null && data.single()
                && tick > Math.round(model.toProject(data.start()) * 60)
                && tick < Math.round(model.toProject(data.end()) * 60);
        boolean tonalAttacks = !notes.isEmpty() && notes.stream().allMatch(note -> {
            var attack = attack(note);
            return attack != null && attack.single() && !attack.drums();
        });
        boolean merge = tonalAttacks && notes.size() > 1 && notes.stream()
                .allMatch(note -> note.part() == hit.part() && note.pitch() == hit.pitch());
        boolean glide = tonalAttacks && notes.size() == 2 && notes.getFirst().part() == notes.getLast().part()
                && notes.getFirst().pitch() != notes.getLast().pitch()
                && Math.abs(attackTick(notes.getFirst()) - attackTick(notes.getLast())) >= 4;
        menu.add("Split at cursor", null, editIssue(split ? null : "Point inside a single note, at least one tick from both ends."),
                    () -> gesture(ReconstructionTools.cut(hit.id(), time, null)))
                .add("Merge selected", null, editIssue(merge ? null : "Select two or more single tonal attacks of the same pitch and part."),
                    () -> add(selectionEdit(notes, "mergeNotes", "Merge notes in Studio")))
                .add("Set velocity…", null, editIssue(notes.isEmpty() || notes.stream().anyMatch(note -> !editableScore(note))
                        ? "Select scored notes." : null), () -> setVelocity(notes))
                .add("Add bend/glide…", null, editIssue(tonalAttacks && notes.size() == 1 || glide
                        ? null : "Select one tonal attack or two different pitches in the same part at least four ticks apart."),
                        () -> pitchMotion(notes))
                .add("Vibrato…", null, editIssue(notes.size() == 1 && hit.sounds() && hit.lane() != null
                        && hit.lane().startsWith("FM") && model.target() == com.opensmpsdeck.project.TargetGame.S3K
                        ? null : "Select a single allocated S3K FM note."), () -> setVibrato(hit));
        menu.submenu("Pin to lane", TimelineView.LANES.stream().filter(value -> !notes.isEmpty()).map(value -> {
            String issue = notes.stream().map(note -> snapshot.editing().laneIssue(note, null, value))
                    .filter(Objects::nonNull).findFirst().orElse(null);
            return StudioContextMenu.item(value, null, editIssue(issue), null, () -> {
                var edit = selectionEdit(notes, "pinLane", "Pin notes in Studio");
                edit.addProperty("lane", value);
                add(edit);
            });
        }).toList());
        menu.submenu("Set role", roles)
                .add("Select all in part", () -> selectNotes(note -> note.part() == hit.part()))
                .add("Select all in bar", null, model.barStart(time).isPresent() ? null : "No bar at this position.",
                        () -> selectNotes(note -> model.barStart(note.interval().sourceStart()).equals(model.barStart(time))))
                .add("Play from here", () -> playFrom.accept(time));
        if (notes.stream().anyMatch(this::editableScore))
            menu.add("Copy notes", this::copyNotes);
        AgentClipboard.addTo(menu, this::copyForAgent);
        return menu;
    }

    private boolean drumPart(Overlay note) {
        return snapshot.inspection().parts().stream().anyMatch(part -> part.id() == note.part() && part.drums());
    }

    private long attackTick(Overlay note) {
        return Math.round(snapshot.inspection().toProject(attack(note).start()) * 60);
    }

    private java.util.Optional<String> prompt(String title, String label, String initial) {
        var dialog = new TextInputDialog(initial);
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(label);
        return dialog.showAndWait().map(String::strip);
    }

    private void setVelocity(List<Overlay> notes) {
        prompt("Set velocity", "MIDI velocity (1–127)", "96").ifPresent(value -> {
            try {
                int amount = Integer.parseInt(value);
                if (amount < 1 || amount > 127) throw new NumberFormatException();
                var edit = selectionEdit(notes, "setVelocity", "Set selection velocity in Studio");
                edit.addProperty("velocity", amount);
                add(edit);
            } catch (NumberFormatException invalid) {
                status.accept("Velocity must be a whole number from 1 to 127.");
            }
        });
    }

    private void pitchMotion(List<Overlay> notes) {
        if (notes.size() == 2) {
            var ordered = notes.stream().sorted(java.util.Comparator.comparingLong(this::attackTick)).toList();
            prompt("Add glide", "Approach duration in source seconds (blank for automatic)", "").ifPresent(value -> {
                var edit = ReconstructionDraft.human("glide", "Glide to selected pitch in Studio");
                edit.addProperty("fromNoteId", ordered.getFirst().id());
                edit.addProperty("toNoteId", ordered.getLast().id());
                if (!value.isEmpty()) {
                    try {
                        double duration = Double.parseDouble(value);
                        double gap = attack(ordered.getLast()).start() - attack(ordered.getFirst()).start();
                        if (!Double.isFinite(duration) || duration <= 0 || duration > gap) throw new NumberFormatException();
                        edit.addProperty("seconds", duration);
                    } catch (NumberFormatException invalid) {
                        status.accept("Glide duration must be positive and fit between the attacks.");
                        return;
                    }
                }
                add(edit);
            });
        } else {
            var note = notes.getFirst();
            var attack = attack(note);
            prompt("Add bend", "Final pitch offset in semitones", "1").ifPresent(value -> {
                try {
                    double delta = Double.parseDouble(value);
                    var range = TargetProfile.of(snapshot.inspection().target()).fm();
                    double endPitch = attack.pitch() + delta;
                    if (!Double.isFinite(delta) || endPitch < range.low() || endPitch > range.high())
                        throw new NumberFormatException();
                    gesture(ReconstructionTools.bend(note.id(), attack.pitch(), List.of(
                            new BendPoint(attack.start(), attack.pitch()), new BendPoint(attack.end(), endPitch))));
                } catch (NumberFormatException invalid) {
                    status.accept("Enter a semitone offset that stays in the FM pitch range.");
                }
            });
        }
    }

    private void setVibrato(Overlay note) {
        var stages = vibrato(note);
        var initial = stages.isEmpty() ? new ReconstructionEditing.VibratoSegment(0, 5, 25) : stages.getFirst();
        prompt("Set vibrato", "Rate in Hz, depth in cents (comma separated)",
                initial.rateHz() + ", " + initial.depthCents()).ifPresent(value -> {
            try {
                var fields = value.split(",", -1);
                if (fields.length != 2) throw new NumberFormatException();
                double hz = Double.parseDouble(fields[0]);
                double depth = Double.parseDouble(fields[1]);
                if (!Double.isFinite(hz) || !Double.isFinite(depth)) throw new NumberFormatException();
                var next = new ArrayList<>(stages);
                if (!next.isEmpty()) next.removeFirst();
                next.addFirst(new ReconstructionEditing.VibratoSegment(initial.startOffset(), hz, depth));
                add(ReconstructionTools.vibrato(note.id(), next));
            } catch (NumberFormatException invalid) {
                status.accept("Enter two numbers, for example 5, 25.");
            }
        });
    }

    private void copyNotes() {
        var notes = view.selectedNotes().stream().filter(ReconstructionEditor::classifiable).filter(this::editableScore).toList();
        if (notes.isEmpty()) return;
        double start = notes.stream().mapToDouble(note -> note.interval().sourceStart()).min().orElseThrow();
        clipboard = notes.stream().map(note -> {
            var interval = note.interval();
            var detail = snapshot.inspection().noteDetails(note.id());
            return new CopiedNote(interval.sourceStart() - start, interval.sourceEnd() - interval.sourceStart(),
                    note.pitch(), pendingVelocity(note, detail.velocity() == null ? 96 : detail.velocity()));
        }).toList();
        status.accept("Copied " + clipboard.size() + (clipboard.size() == 1 ? " note" : " notes"));
    }

    private String pasteIssue(double time) {
        var voice = parts.getValue();
        if (clipboard.isEmpty()) return "Copy notes first.";
        if (voice == null) return "Choose an insert voice first.";
        if (clipboard.stream().anyMatch(note -> time + note.offset() + note.length() > snapshot.inspection().duration() + 1e-7))
            return "The copied notes extend beyond the song.";
        if (!voice.part().drums() && clipboard.stream().anyMatch(note -> note.pitch() < 12 || note.pitch() > 106))
            return "The insert voice requires MIDI pitches between 12 and 106.";
        return null;
    }

    private void paste(double time) {
        String issue = editIssue(pasteIssue(time));
        if (issue != null) {
            status.accept(issue);
            return;
        }
        var intents = new ArrayList<JsonObject>();
        for (var note : clipboard) {
            double start = time + note.offset();
            var edit = ReconstructionDraft.insert(parts.getValue().part().id(), start, start + note.length(), note.pitch());
            edit.addProperty("velocity", note.velocity());
            intents.add(edit);
        }
        assignInsertedNotes(intents, parts.getValue());
        addBatch(intents);
    }

    private static JsonObject single(String op, Overlay note, String reason) {
        var intent = ReconstructionDraft.human(op, reason);
        intent.addProperty("noteId", note.id());
        return intent;
    }

    private void gesture(JsonObject intent) {
        String op = text(intent, "op", "");
        if (op.equals("bend") && selected != null && (selected.lane() == null || !selected.lane().startsWith("FM"))
                && selected.id().equals(targetId(intent))) {
            addBatch(List.of(intent, ReconstructionDraft.target(selected.id(), "pinLane", "laneType", "FM",
                    "Route the pitch slide to FM")));
        } else add(intent);
        if (!op.equals("splitNote")) return;
        String left = "edit-" + text(intent, "id", "") + "-left";
        overlays().stream().filter(note -> note.id().equals(left)).findFirst()
                .ifPresent(note -> view.select(note, note.lane()));
    }

    private void transpose(int semitones) {
        if (busy || external != null || externalError) return;
        var groups = new LinkedHashMap<ReconstructionLaneType, List<String>>();
        int percussion = 0;
        int unscored = 0;
        for (var note : view.selectedNotes()) {
            var type = transposeLane(note);
            var attack = attack(note);
            if (note.role() == Role.DRUMS || type == ReconstructionLaneType.DAC || type == ReconstructionLaneType.NOISE
                    || attack != null && attack.drums()) {
                percussion++;
                continue;
            }
            if (attack == null) {
                unscored++;
                continue;
            }
            String issue = transposeIssue(note, attack, type, semitones);
            if (issue != null) {
                status.accept("Transpose refused · " + type + " · " + issue + " (" + note.id() + ")");
                return;
            }
            groups.computeIfAbsent(type, ignored -> new ArrayList<>()).add(note.id());
        }
        var intents = groups.entrySet().stream().map(group ->
                ReconstructionDraft.transpose(group.getValue(), semitones, group.getKey())).toList();
        if (!intents.isEmpty()) addBatch(intents);
        int count = groups.values().stream().mapToInt(List::size).sum();
        String message = count == 0 ? "Select a scored tonal note to transpose"
                : "Transposed " + count + (count == 1 ? " note" : " notes") + " by "
                        + "%+d".formatted(semitones) + (Math.abs(semitones) == 1 ? " semitone" : " semitones");
        if (percussion != 0) message += " · skipped drums or noise (" + percussion + ")";
        if (unscored != 0) message += " · skipped notes without an editable score attack (" + unscored + ")";
        status.accept(message);
    }

    private String transposeIssue(int semitones) {
        boolean tonal = false;
        for (var note : view.selectedNotes()) {
            var attack = attack(note);
            var type = transposeLane(note);
            if (attack == null || attack.drums() || note.role() == Role.DRUMS
                    || type == ReconstructionLaneType.DAC || type == ReconstructionLaneType.NOISE) continue;
            tonal = true;
            String issue = transposeIssue(note, attack, type, semitones);
            if (issue != null) return issue;
        }
        return tonal ? null : "Select a scored tonal note.";
    }

    private String transposeIssue(Overlay note, ReconstructionEditing.Attack attack, ReconstructionLaneType type, int semitones) {
        var profile = TargetProfile.of(snapshot.inspection().target());
        var range = type == ReconstructionLaneType.PSG ? profile.psg() : profile.fm();
        String issue = snapshot.editing().transposeIssue(note.id(), attack.pitch() + semitones, type);
        if (issue == null && note.bend().stream().anyMatch(point ->
                point.pitch() + semitones < range.low() || point.pitch() + semitones > range.high()))
            return "Pitch curve must stay in MIDI " + range.low() + "–" + range.high();
        return issue;
    }

    private ReconstructionLaneType transposeLane(Overlay note) {
        var type = snapshot.inspection().laneType(note);
        for (var entry : draftPitchEdits.getOrDefault(note.id(), List.of())) {
            if (!"pinLane".equals(text(entry, "op", ""))) continue;
            String laneType = text(entry, "laneType", null);
            String lane = text(entry, "lane", null);
            if (laneType != null) type = ReconstructionLaneType.valueOf(laneType);
            else if (lane != null) {
                if (lane.equals("DAC")) type = ReconstructionLaneType.DAC;
                else if (lane.startsWith("FM")) type = ReconstructionLaneType.FM;
                else if (type != ReconstructionLaneType.NOISE || !lane.equals("PSG3")) type = ReconstructionLaneType.PSG;
            }
        }
        return type == null ? ReconstructionLaneType.FM : type;
    }

    private void add(JsonObject intent) { addBatch(List.of(intent)); }

    private void addBatch(List<JsonObject> intents) {
        long eventNanos = System.nanoTime();
        if (intents.isEmpty() || busy || external != null || externalError) return;
        for (var intent : intents) {
            var error = ReconstructionReview.validationIssue(intent);
            if (error != null) {
                status.accept(error);
                return;
            }
        }
        if (intents.stream().anyMatch(intent -> {
                    String op = text(intent, "op", "");
                    return op.equals("voiceEdit") || view.rolesView() && Set.of("setRole", "pinLane").contains(op);
                })
                && draft.entries().asList().stream().anyMatch(entry -> DETAIL_EDITS.contains(text(entry, "op", "")))) {
            previewClassification(intents);
        } else stageBatch(intents, eventNanos);
    }

    private void stageBatch(List<JsonObject> intents, long eventNanos) {
        draft.change(entries -> intents.forEach(entries::add));
        journalChanged(eventNanos);
    }

    private void previewClassification(List<JsonObject> intents) {
        var before = draft.entries();
        var after = before.deepCopy();
        intents.forEach(after::add);
        setBusy();
        cancel.setDisable(true);
        status.accept("Checking detail edits against the proposed classification…");
        worker.execute(() -> {
            try {
                var issues = ReconstructionReview.newOrphanedDetails(work, before, after);
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    if (!issues.isEmpty()) {
                        var proceed = new ButtonType("Queue classification", ButtonBar.ButtonData.OK_DONE);
                        var alert = new Alert(Alert.AlertType.CONFIRMATION, String.join("\n", issues.stream().limit(8).toList())
                                + (issues.size() > 8 ? "\nAnd " + (issues.size() - 8) + " more." : ""), proceed, ButtonType.CANCEL);
                        alert.initOwner(owner);
                        alert.setHeaderText("This change would orphan " + issues.size() + " detail edit target(s)");
                        if (alert.showAndWait().orElse(ButtonType.CANCEL) != proceed) {
                            status.accept("Classification cancelled");
                            updateControls();
                            readExternal();
                            return;
                        }
                    }
                    status.accept("Classification queued");
                    stageBatch(intents, System.nanoTime());
                    readExternal();
                });
            } catch (Throwable failure) {
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    status.accept("Classification check failed · " + describe(failure));
                    updateControls();
                    readExternal();
                });
            }
        });
    }

    private static String targetId(JsonElement entry) {
        if (text(entry, "op", "").equals("insertNote")) return "edit-" + text(entry, "id", "");
        if (!entry.isJsonObject()) return null;
        var ids = entry.getAsJsonObject().get("noteIds");
        if (ids != null && ids.isJsonArray() && ids.getAsJsonArray().size() == 1
                && ids.getAsJsonArray().get(0).isJsonPrimitive()) return ids.getAsJsonArray().get(0).getAsString();
        return text(entry, "noteId", null);
    }

    private static List<String> targetIds(JsonElement entry) {
        if (!entry.isJsonObject()) return List.of();
        var json = entry.getAsJsonObject();
        if (json.has("voiceEdit") && json.get("voiceEdit").isJsonObject()) json = json.getAsJsonObject("voiceEdit");
        var ids = json.get("noteIds");
        if (ids != null && ids.isJsonArray())
            return ids.getAsJsonArray().asList().stream().filter(JsonElement::isJsonPrimitive)
                    .map(JsonElement::getAsString).toList();
        String id = targetId(entry);
        return id == null ? List.of() : List.of(id);
    }

    private List<Overlay> overlays() {
        var pending = new LinkedHashMap<String, Overlay>();
        var inspection = snapshot.inspection();
        // Lookup by trace ID so lane and pitch representations of the same attack share a draft.
        var built = new LinkedHashMap<String, Overlay>();
        for (var note : inspection.overlays()) built.putIfAbsent(note.id(), note);
        for (var removed : draft.removed()) {
            var results = snapshot.outcomes().getOrDefault(text(removed, "id", ""), List.of());
            for (var result : results) for (String id : result.noteIds()) {
                var note = built.get(id);
                if (note != null) pending.put(id, preview(note, Placement.of(note), note.role(), note.lane(),
                        "Pending edit removal", text(removed, "reason", ""), note.sounds()));
            }
        }
        for (var entry : draft.entries()) {
            if (!draft.changed(entry) || ReconstructionReview.validationIssue(entry) != null) continue;
            var json = entry.getAsJsonObject();
            var op = text(entry, "op", "");
            for (String id : targetIds(entry)) {
                var note = pending.getOrDefault(id, built.get(id));
                if (op.equals("insertNote")) {
                    var part = inspection.parts().stream().filter(p -> p.id() == json.get("part").getAsInt())
                            .findFirst().orElse(null);
                    if (part == null) continue;
                    var place = new Placement(json.get("start").getAsDouble(), json.get("end").getAsDouble(),
                            json.get("pitch").getAsInt());
                    note = new Overlay(id, id, part.id(), part.name(), part.drums() ? Role.DRUMS : Role.UNASSIGNED,
                            null, place.pitch(), null, place.interval(inspection), List.of(), false,
                            ReconstructionInspection.Style.SOLID, "Pending insertion", text(entry, "reason", ""), List.of());
                    pending.put(id, note);
                    continue;
                }
                if (note == null || note.fate().equals("Superseded draft")) continue;
                var place = Placement.of(note);
                String reason = text(entry, "reason", "");
                if (op.equals("splitNote")) {
                    var original = attack(note);
                    double at = json.get("at").getAsDouble();
                    double start = original == null ? place.start() : original.start();
                    double end = original == null ? place.end() : original.end();
                    int pitch = original == null ? place.pitch() : original.pitch();
                    String prefix = "edit-" + text(entry, "id", "");
                    pending.put(id, preview(note, place, note.role(), note.lane(), "Superseded draft", reason, false));
                    pending.put(prefix + "-left", half(note, prefix + "-left", new Placement(start, at, pitch), reason));
                    pending.put(prefix + "-right", half(note, prefix + "-right",
                            new Placement(at, end, (int) number(json, "pitch", pitch)), reason));
                    continue;
                }
                var next = op.equals("setNote")
                        ? new Placement(number(json, "start", place.start()), number(json, "end", place.end()),
                            (int) number(json, "pitch", place.pitch())) : place;
                int shift = op.equals("transpose") ? json.get("semitones").getAsInt() : 0;
                if (shift != 0) {
                    int pitch = place.pitch() + shift;
                    if (note.role() == Role.DRUMS || pitch < 0 || pitch > 127) continue;
                    next = new Placement(place.start(), place.end(), pitch);
                }
                String nextRole = text(entry, "role", null);
                boolean drop = op.equals("deleteNote") || "drop".equals(nextRole)
                        || nextRole == null && note.fate().equals("Pending deletion");
                var color = nextRole == null || drop ? note.role() : Role.valueOf(nextRole.toUpperCase(Locale.ROOT));
                String fate = drop ? "Pending deletion" : note.fate().equals("Pending insertion")
                        || note.fate().equals("Pending cut") ? note.fate() : "Pending change";
                String nextLane = text(entry, "lane", note.lane());
                if (op.equals("pinLane") && json.has("laneType")) reason += " · " + json.get("laneType").getAsString();
                var overlay = preview(note, next, color, nextLane, fate, reason, !drop && nextLane != null);
                if (op.equals("bend")) {
                    int pitch = note.pitch();
                    var points = json.getAsJsonArray("points").asList().stream().map(JsonElement::getAsJsonArray)
                            .map(pair -> new BendPoint(pair.get(0).getAsDouble(), pitch + pair.get(1).getAsDouble())).toList();
                    overlay = withBend(overlay, points);
                } else if (shift != 0 && !overlay.bend().isEmpty()) {
                    overlay = withBend(overlay, overlay.bend().stream()
                            .map(point -> new BendPoint(point.sourceSeconds(), point.pitch() + shift)).toList());
                }
                pending.put(id, overlay);
            }
        }
        return List.copyOf(pending.values());
    }

    private static Overlay withBend(Overlay overlay, List<BendPoint> points) {
        return new Overlay(overlay.id(), overlay.eventId(), overlay.part(), overlay.partName(), overlay.role(),
                overlay.lane(), overlay.pitch(), overlay.patch(), overlay.interval(), points, overlay.sounds(),
                overlay.style(), overlay.fate(), overlay.reason(), overlay.competitors());
    }

    private Overlay half(Overlay note, String id, Placement place, String reason) {
        return new Overlay(id, id, note.part(), note.partName(), note.role(), note.lane(), place.pitch(),
                note.patch(), place.interval(snapshot.inspection()), List.of(), false,
                ReconstructionInspection.Style.SOLID, "Pending cut", reason, note.competitors());
    }

    private static double number(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }

    private Overlay preview(Overlay source, Placement place, Role role, String lane, String fate, String reason,
                            boolean sounds) {
        return new Overlay(source.id(), source.eventId(), source.part(), source.partName(), role, lane, place.pitch(),
                source.patch(), place.interval(snapshot.inspection()), source.bend(), sounds,
                ReconstructionInspection.Style.SOLID, fate, reason, source.competitors());
    }

    static JsonArray accepted(JsonArray journal) {
        var entries = new JsonArray();
        journal.asList().stream().filter(entry -> !ReconstructionProposals.isInactive(entry)).forEach(entries::add);
        return entries;
    }

    private JsonArray journalForDraft(JsonArray journal, JsonArray local) {
        var branch = branchJournals.get(local);
        if (branch != null && branchJournals.containsValue(journal)) return branch.deepCopy();
        var combined = forApply(journal, local);
        if (branchJournals.isEmpty()) return combined;
        // Branch plans may intentionally reorder inherited intents. Keep their order
        // while preserving unrelated proposals that arrived after the plan.
        var ordered = new JsonArray();
        var active = local.iterator();
        for (var entry : combined) {
            if (ReconstructionProposals.isInactive(entry)) ordered.add(entry);
            else ordered.add(active.next());
        }
        return ordered;
    }

    static JsonArray forApply(JsonArray journal, JsonArray local) {
        var byId = new LinkedHashMap<String, JsonElement>();
        for (var entry : local) byId.put(text(entry, "id", ""), entry);
        var combined = new JsonArray();
        for (var entry : journal) {
            var replacement = byId.remove(text(entry, "id", ""));
            if (replacement != null) combined.add(replacement);
            else if (ReconstructionProposals.isInactive(entry)) combined.add(entry);
        }
        byId.values().forEach(combined::add);
        return combined;
    }

    private void updateProposals() {
        var drafted = draft.entries().asList().stream().map(edit -> text(edit, "id", ""))
                .collect(Collectors.toSet());
        var groups = new LinkedHashMap<String, List<ReconstructionProposals.Result>>();
        var marks = new ArrayList<ReconstructionCanvas.ProposalMark>();
        for (var proposal : snapshot.proposals()) {
            if (drafted.contains(proposal.id())) continue;
            groups.computeIfAbsent(text(proposal.edit(), "group", null), ignored -> new ArrayList<>()).add(proposal);
            String reason = text(proposal.edit(), "reason", "");
            for (var marker : proposal.markers())
                marks.add(new ReconstructionCanvas.ProposalMark(marker, "Agent proposal · " + reason, proposal.id()));
        }
        view.proposals(marks);
        proposalList.getChildren().clear();
        var visible = groups.values().stream().flatMap(List::stream).toList();
        proposalCount = visible.size();
        var builtNotes = new java.util.HashMap<String, Overlay>();
        if (!visible.isEmpty()) for (var note : snapshot.inspection().overlays()) builtNotes.putIfAbsent(note.id(), note);
        selectedProposals.retainAll(visible.stream().map(ReconstructionProposals.Result::id).collect(Collectors.toSet()));
        var acceptSelected = new Button("Accept selected");
        var rejectSelected = new Button("Reject selected");
        Runnable selectionChanged = () -> {
            acceptSelected.setDisable(selectedProposals.isEmpty());
            rejectSelected.setDisable(selectedProposals.isEmpty());
        };
        acceptSelected.setOnAction(event -> acceptProposals(visible.stream()
                .filter(proposal -> selectedProposals.contains(proposal.id())).toList()));
        rejectSelected.setOnAction(event -> rejectProposals(visible.stream()
                .filter(proposal -> selectedProposals.contains(proposal.id())).toList()));
        selectionChanged.run();
        if (!visible.isEmpty()) proposalList.getChildren().add(new FlowPane(5, 4, acceptSelected, rejectSelected));
        if (groups.isEmpty()) proposalList.getChildren().add(new Label("No proposals to review"));
        groups.forEach((group, proposals) -> {
            var heading = new Label(group == null ? "Individual proposals" : readableKey(group));
            heading.setWrapText(true);
            heading.getStyleClass().add("panel-heading");
            proposalList.getChildren().add(heading);
            if (group != null) {
                var acceptGroup = new Button("Accept all");
                acceptGroup.setOnAction(event -> acceptProposals(proposals));
                var rejectGroup = new Button("Reject all");
                // A group may already have locally accepted entries. Reject only the still-visible proposals.
                rejectGroup.setOnAction(event -> rejectProposals(proposals));
                proposalList.getChildren().add(new FlowPane(5, 4, acceptGroup, rejectGroup));
            }
            for (var proposal : proposals) {
                var edit = proposal.edit();
                String description = text(edit, "reason", "");
                var title = new CheckBox(proposalAction(edit));
                title.setWrapText(true);
                title.setSelected(selectedProposals.contains(proposal.id()));
                title.setOnAction(event -> {
                    if (title.isSelected()) selectedProposals.add(proposal.id());
                    else selectedProposals.remove(proposal.id());
                    selectionChanged.run();
                });
                var detail = new java.util.LinkedHashSet<String>();
                var diagnostics = new java.util.LinkedHashSet<String>();
                diagnostics.add("Proposal " + proposal.id());
                diagnostics.add("Operation " + text(edit, "op", ""));
                if (!description.isBlank()) diagnostics.add(description);
                var target = builtNotes.get(targetId(edit));
                if (target != null) {
                    detail.add(view.noteLabel(target));
                    detail.add(String.format(Locale.ROOT, "%.2f s", target.interval().sourceStart()));
                } else if (!proposal.markers().isEmpty()) {
                    detail.add(String.format(Locale.ROOT, "%.2f s", proposal.markers().getFirst().start()));
                }
                for (var result : proposal.outcomes()) {
                    String outcome = switch (result.status()) {
                        case "applied" -> "Would apply";
                        case "satisfied" -> "Already satisfied";
                        case "conflict" -> "Conflict";
                        case "orphaned" -> "Target missing";
                        case "invalid" -> "Invalid";
                        default -> "Awaiting preview";
                    };
                    detail.add(outcome);
                    if (result.reason() != null && !description.equals(result.reason()))
                        diagnostics.add(result.reason());
                }
                if (proposal.roles() != null) {
                    proposal.roles().stream().filter(role -> role.from() != null && role.to() != null && !role.satisfied())
                            .map(role -> role.from() + " → " + role.to()).distinct().limit(3)
                            .forEach(detail::add);
                }
                if (edit.has("evidence")) {
                    var evidence = edit.getAsJsonObject("evidence");
                    String evidenceText = text(evidence, "text", "");
                    if (!evidenceText.isBlank() && !evidenceText.equals(description)) diagnostics.add(evidenceText);
                    String path = text(evidence, "path", "");
                    if (!path.isBlank()) diagnostics.add(path);
                }
                var reason = new Label(String.join(" · ", detail));
                reason.setWrapText(true);
                reason.getStyleClass().add("muted");
                var diagnostic = new Label(String.join("\n", diagnostics));
                diagnostic.setWrapText(true);
                var disclosure = new TitledPane("Details", diagnostic);
                disclosure.setExpanded(false);
                disclosure.setAnimated(false);
                var preview = new Button("Preview A/B");
                preview.setOnAction(event -> previewProposal(proposal.id()));
                var accept = new Button("Accept");
                accept.setOnAction(event -> acceptProposals(List.of(proposal)));
                var reject = new Button("Reject");
                reject.setOnAction(event -> rejectProposals(List.of(proposal)));
                var revise = new Button("Revise");
                revise.setOnAction(event -> reviseProposal(proposal));
                var card = new VBox(7, title, reason, new FlowPane(5, 4, preview, revise, accept, reject), disclosure);
                card.getStyleClass().add("reconstruction-proposal");
                card.setOnContextMenuRequested(event -> proposalMenu(proposal).show(card, event));
                proposalList.getChildren().add(card);
            }
        });
        if (showDecidedProposals.isSelected()) {
            var entries = snapshot.revision().entries();
            for (var entry : entries) {
                if (!entry.isJsonObject()) continue;
                var edit = entry.getAsJsonObject();
                String decision = text(edit, "status", "");
                if (!decision.equals("accepted") && !decision.equals("rejected")) continue;
                var row = new Label(readableKey(decision) + " · " + text(edit, "reason", readableKey(text(edit, "op", "Edit"))));
                row.setWrapText(true);
                var details = new TitledPane("Details", new Label(text(edit, "id", "")));
                details.setExpanded(false);
                proposalList.getChildren().addAll(row, details);
            }
        }
    }

    private StudioContextMenu proposalMenu(ReconstructionProposals.Result proposal) {
        var ids = new java.util.HashSet<>(targetIds(proposal.edit()));
        proposal.markers().forEach(marker -> ids.add(marker.noteId()));
        var notes = view.selectableOverlays().stream().filter(note -> ids.contains(note.id())).toList();
        return new StudioContextMenu()
                .add("Accept", null, editIssue(null), () -> acceptProposals(List.of(proposal)))
                .add("Reject", null, editIssue(null), () -> rejectProposals(List.of(proposal)))
                .add("Show in Detail", null, notes.isEmpty() && proposal.markers().isEmpty() ? "No note position is available." : null,
                        () -> {
                            view.selectNotes(notes);
                            view.onDetailJump.accept(notes);
                            if (notes.isEmpty()) view.center(proposal.markers().getFirst().start());
                        });
    }

    static String proposalAction(JsonObject edit) {
        String operation = text(edit, "op", "Edit notes");
        return switch (operation) {
            case "setNote" -> {
                double pitch = number(edit, "pitch", -1);
                if (pitch >= 0 && pitch <= 127) yield "Change pitch to " + ReconstructionCanvas.pitchName((int) pitch);
                if (edit.has("velocity")) yield "Change note velocity";
                yield "Adjust note timing";
            }
            case "pinLane" -> "Move notes to another chip lane";
            case "voiceEdit" -> "Change voice assignment";
            case "setRole" -> "Assign musical role";
            case "noteVoicing" -> "Change note sound";
            case "layer" -> "Change note layers";
            default -> readableKey(operation);
        };
    }

    private static String readableKey(String key) {
        String words = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('-', ' ').strip();
        return words.isEmpty() ? "Edit notes" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private JsonObject editJson(String title, JsonElement entry) {
        var dialog = new Dialog<String>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        var area = new TextArea(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(entry));
        area.setPrefColumnCount(58);
        area.setPrefRowCount(17);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? area.getText() : null);
        var replacement = dialog.showAndWait();
        if (replacement.isEmpty()) return null;
        try {
            return com.google.gson.JsonParser.parseString(replacement.get()).getAsJsonObject();
        } catch (com.google.gson.JsonParseException | IllegalStateException failure) {
            status.accept("Revision needs a JSON intent object");
            return null;
        }
    }

    private void reviseJournalEdit() {
        var row = journal.getSelectionModel().getSelectedItem();
        if (row == null || row.index() < 0 || busy || external != null || externalError) return;
        var entry = draft.entries().get(row.index());
        var revised = editJson("Revise edit", entry);
        if (revised == null) return;
        String issue = !Objects.equals(text(entry, "id", null), text(revised, "id", null))
                ? "Keep the edit ID when revising its target" : ReconstructionReview.validationIssue(revised);
        if (issue != null) {
            status.accept(issue);
            return;
        }
        draft.change(entries -> entries.set(row.index(), revised));
        journalChanged();
    }

    private void reviseProposal(ReconstructionProposals.Result proposal) {
        if (busy || external != null || externalError) return;
        var intent = editJson("Revise proposal", proposal.edit());
        if (intent == null) return;
        var expected = snapshot.revision();
        setBusy();
        cancel.setDisable(true);
        worker.execute(() -> {
            String message;
            try {
                ReconstructionEditCommand.revise(work, proposal.id(), intent, expected);
                message = "Proposal revised";
            } catch (IOException | IllegalArgumentException failure) {
                message = "Revision failed · " + describe(failure);
            }
            String detail = message;
            Platform.runLater(() -> {
                if (closed) return;
                busy = false;
                updateControls();
                status.accept(detail);
                readExternal();
            });
        });
    }

    private void acceptProposals(List<ReconstructionProposals.Result> proposals) {
        addBatch(proposals.stream().map(proposal -> {
            var edit = proposal.edit().deepCopy();
            edit.addProperty("status", "accepted");
            return edit;
        }).toList());
        status.accept("Accepted into pending edits · Apply to rebuild");
    }

    private void rejectProposals(List<ReconstructionProposals.Result> proposals) {
        if (busy || external != null || externalError) return;
        var expected = snapshot.revision();
        var ids = proposals.stream().map(ReconstructionProposals.Result::id).toList();
        setBusy();
        cancel.setDisable(true);
        worker.execute(() -> {
            try {
                ReconstructionEditCommand.rejectProposals(work, ids, expected);
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    updateControls();
                    status.accept("Rejected " + ids.size() + (ids.size() == 1 ? " proposal" : " proposals"));
                    readExternal();
                });
            } catch (IOException | IllegalArgumentException failure) {
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    updateControls();
                    status.accept("Reject failed · " + describe(failure));
                    readExternal();
                });
            }
        });
    }

    private record PreviewAudio(RenderComparison.Result comparison, ReferenceWave wave) {}

    private void previewProposal(String id) {
        if (liveTransport != null && liveTransport.liveEdit() != null) {
            var proposal = snapshot.revision().entries().asList().stream()
                    .filter(entry -> id.equals(text(entry, "id", "")) && ReconstructionProposals.isProposed(entry))
                    .findFirst().orElse(null);
            if (proposal != null) {
                var choice = proposal.getAsJsonObject().deepCopy();
                choice.addProperty("status", "accepted");
                compareLive("accepted proposal", (expected, built, playback, current) -> {
                    var entries = forApply(expected.entries(), draftWith(expected, choice));
                    return new LiveComparison(current, current, previewLive(expected, built, entries, playback), choice, "accepted proposal");
                });
                return;
            }
        }
        previewAB("proposal", expected -> ReconstructionReview.preview(work, id, expected, this::reportProgress));
    }

    private static JsonArray draftWith(Revision expected, JsonObject edit) {
        var entries = accepted(expected.entries());
        entries.add(edit);
        return entries;
    }

    private void compareHistory() {
        if (comparisonPlayhead() == null) return;
        var entries = accepted(snapshot.revision().entries());
        if (entries.isEmpty()) {
            status.accept("There are no accepted edits to compare");
            return;
        }
        var dialog = new javafx.scene.control.TextInputDialog("1");
        dialog.initOwner(owner);
        dialog.setHeaderText("How many recent edits should B omit?");
        dialog.showAndWait().ifPresent(value -> {
            int count;
            try {
                count = Integer.parseInt(value.strip());
            } catch (NumberFormatException invalid) {
                count = 0;
            }
            if (count < 1 || count > entries.size()) {
                status.accept("Enter a count from 1 to " + entries.size());
                return;
            }
            int omit = count;
            String label = "before " + count + (count == 1 ? " edit" : " edits");
            compareLive(label, (expected, built, playback, current) -> {
                var earlier = accepted(expected.entries());
                if (omit > earlier.size()) throw new IOException("The accepted journal changed");
                for (int i = 0; i < omit; i++) earlier.remove(earlier.size() - 1);
                return new LiveComparison(current, current, previewLive(expected, built, earlier, playback),
                        null, label);
            });
        });
    }

    private void comparePart(boolean refit) {
        if (comparisonPlayhead() == null) return;
        var selected = view.selectedNotes().stream().map(Overlay::part).distinct().toList();
        if (selected.size() != 1) {
            status.accept("Select notes from one part");
            return;
        }
        int part = selected.getFirst();
        if (refit) {
            compareLive("stock refit", refitWorker, (expected, built, playback, current) -> {
                var variant = ReconstructionReview.refitStockVoice(work, current, part);
                var choice = ReconstructionReview.stockVoiceEdit(work, current, part, variant);
                var next = previewLive(expected, built, draftWith(expected, choice), playback);
                return new LiveComparison(current, current, next, choice, "stock refit");
            });
            return;
        }
        var expected = snapshot.revision();
        worker.execute(() -> {
            try {
                var voices = ReconstructionReview.stockVoices(work);
                Platform.runLater(() -> {
                    if (closed || !snapshot.revision().equals(expected)) return;
                    if (voices.size() < 2) {
                        status.accept("Two stock FM voices are required");
                        return;
                    }
                    var a = chooseStock("Choose stock voice A", voices);
                    if (a == null) return;
                    var b = chooseStock("Choose stock voice B", voices.stream().filter(voice -> !voice.equals(a)).toList());
                    if (b == null) return;
                    compareLive(b.name(), (revision, built, playback, current) -> {
                        var left = ReconstructionReview.stockVoiceEdit(work, current, part, a.id());
                        var right = ReconstructionReview.stockVoiceEdit(work, current, part, b.id());
                        return new LiveComparison(current,
                                previewLive(revision, built, draftWith(revision, left), playback),
                                previewLive(revision, built, draftWith(revision, right), playback), right, b.name());
                    });
                });
            } catch (IOException | IllegalArgumentException failure) {
                Platform.runLater(() -> status.accept("Stock voices unavailable · " + describe(failure)));
            }
        });
    }

    private ReconstructionReview.StockVoice chooseStock(String title, List<ReconstructionReview.StockVoice> voices) {
        var dialog = new javafx.scene.control.ChoiceDialog<>(voices.getFirst(), voices);
        dialog.initOwner(owner);
        dialog.setHeaderText(title);
        return dialog.showAndWait().orElse(null);
    }

    private ReconstructionTransport.LiveEdit comparisonPlayhead() {
        if (!editable() || liveSaving || preparingLive || draft.pending()) {
            status.accept("Finish pending changes before comparing");
            return null;
        }
        var edit = liveTransport == null ? null : liveTransport.liveEdit();
        if (edit == null) status.accept("Start Chip playback to compare at the playhead");
        return edit;
    }

    @FunctionalInterface
    private interface BuildComparison {
        LiveComparison build(Revision expected, JsonArray built, ReconstructionTransport.LiveEdit playback,
                             ReconstructionLivePreview.Prepared current) throws IOException;
    }

    private void compareLive(String label, BuildComparison builder) {
        compareLive(label, worker, builder);
    }

    private void compareLive(String label, ExecutorService executor, BuildComparison builder) {
        var edit = comparisonPlayhead();
        if (edit == null) return;
        onCompare.run();
        endComparison(true);
        var expected = snapshot.revision();
        var built = liveBase;
        var reconstruction = executor == worker && Objects.equals(liveVoiceJournal, expected.voices()) ? live : null;
        long generation = liveGeneration;
        long comparison = comparisonGeneration;
        status.accept("Preparing A/B · " + label);
        var job = executor.submit(() -> {
            try {
                var current = previewLive(expected, built, expected.entries(), edit, reconstruction);
                var result = builder.build(expected, built, edit, current);
                Platform.runLater(() -> {
                    if (closed || generation != liveGeneration || comparison != comparisonGeneration
                            || !snapshot.revision().equals(expected)) return;
                    if (!liveTransport.compareLive(edit, result.a(), "A/B · A")) return;
                    liveComparison = result;
                    switchAB.setText("Hear B");
                    switchAB.setVisible(true);
                    endAB.setVisible(true);
                    keepB.setVisible(result.choice() != null);
                });
            } catch (IOException | IllegalArgumentException failure) {
                Platform.runLater(() -> {
                    if (!closed && generation == liveGeneration && comparison == comparisonGeneration)
                        status.accept("A/B unavailable · " + describe(failure));
                });
            }
        });
        if (executor == refitWorker) refitJob = job;
    }

    private ReconstructionLivePreview.Prepared previewLive(Revision expected, JsonArray built, JsonArray entries,
                                                            ReconstructionTransport.LiveEdit edit) throws IOException {
        return previewLive(expected, built, entries, edit, null);
    }

    private ReconstructionLivePreview.Prepared previewLive(Revision expected, JsonArray built, JsonArray entries,
                                                            ReconstructionTransport.LiveEdit edit,
                                                            ReconstructionLivePreview reconstruction) throws IOException {
        if (reconstruction != null) {
            try {
                var window = edit.window();
                var result = reconstruction.prepare(entries, window.start(), window.end(), edit.playhead());
                result.requireCurrent(expected);
                return result;
            } catch (ReconstructionLivePreview.RebuildNeeded fallback) {
                // The variant or edit history changes upstream decisions.
            }
        }
        return ReconstructionReview.liveRebuild(work, expected, built, entries, (done, total, message) -> {});
    }

    private void switchComparison() {
        var edit = liveTransport.liveEdit();
        if (edit == null || liveComparison == null) return;
        boolean next = !hearingB;
        if (liveTransport.compareLive(edit, next ? liveComparison.b() : liveComparison.a(),
                next ? "A/B · B · " + liveComparison.label() : "A/B · A")) {
            hearingB = next;
            switchAB.setText(next ? "Hear A" : "Hear B");
        }
    }

    private void endComparison(boolean restore) {
        comparisonGeneration++;
        cancelRefit();
        if (liveComparison == null) return;
        var edit = restore ? liveTransport.liveEdit() : null;
        if (edit != null) liveTransport.compareLive(edit, liveComparison.current(),
                externalUnbuilt ? "Live preview · Saved edits" : "");
        liveComparison = null;
        hearingB = false;
        switchAB.setVisible(false);
        endAB.setVisible(false);
        keepB.setVisible(false);
    }

    private void previewLayer(JsonObject layer) {
        previewAB(layer.get("mode").getAsString().equals("off") ? "layer off" : "selected layer",
                expected -> ReconstructionReview.previewLayer(work, layer, expected, this::reportProgress));
    }

    @FunctionalInterface
    private interface Preview {
        ReconstructionReview.ProposalPreview run(Revision expected) throws IOException;
    }

    private void previewAB(String label, Preview preview) {
        if (busy || external != null || externalError) return;
        setBusy();
        var expected = snapshot.revision();
        worker.execute(() -> {
            var audio = new ArrayList<PreviewAudio>();
            String failure = null;
            try {
                synchronized (jobLock) {
                    running = Thread.currentThread();
                    if (cancelling) throw new java.util.concurrent.CancellationException();
                }
                var result = preview.run(expected);
                for (var comparison : result.comparisons())
                    audio.add(new PreviewAudio(comparison, ReferenceWave.read(Path.of(comparison.output()))));
            } catch (Throwable error) {
                failure = cancelling ? "Preview cancelled" : "Preview unavailable · " + describe(error);
            } finally {
                synchronized (jobLock) { running = null; }
                Thread.interrupted();
            }
            String problem = failure;
            Platform.runLater(() -> {
                if (closed) return;
                busy = false;
                if (problem != null || audio.isEmpty())
                    status.accept(problem == null ? "No affected span to preview" : problem);
                else {
                    previewPlaying = true;
                    playPreview(audio, 0, auditionEpoch, label);
                }
                updateControls();
                readExternal();
            });
        });
    }

    private void playPreview(List<PreviewAudio> audio, int index, long request, String label) {
        var clip = audio.get(index);
        var comparison = clip.comparison();
        previewPosition.accept(comparison.projectStart());
        status.accept("Preview A/B · built, then " + label);
        audition.playMonitor(null, new ReviewTransport(), S3kRenderer.ChannelMask.NONE, clip.wave().duration(),
                () -> clip.wave()::sample, message -> Platform.runLater(() -> {
                    if (closed || request != auditionEpoch) return;
                    if (message.equals("Playback finished") && index + 1 < audio.size()) {
                        playPreview(audio, index + 1, request, label);
                    } else if (!message.equals("Playing")) {
                        stopAudition();
                        status.accept(message);
                    }
                }), position -> Platform.runLater(() -> {
                    if (!closed && request == auditionEpoch) {
                        boolean built = position < comparison.splitSeconds();
                        previewPosition.accept(comparison.projectStart() + (built ? position : position - comparison.splitSeconds()));
                        status.accept(built ? "Preview A · built" : "Preview B · " + label);
                    }
                }));
    }

    void liveTransport(ReconstructionTransport transport) {
        liveTransport = transport;
        prepareLive();
    }

    private void prepareLive() {
        live = null;
        liveUnavailable = null;
        var current = snapshot;
        liveBase = new JsonArray();
        liveVoiceJournal = current.revision().voices();
        accepted(current.revision().entries()).forEach(entry -> {
            if (current.outcomes().containsKey(text(entry, "id", ""))) liveBase.add(entry);
        });
        if (current.inspection().project() == null)
            return;
        canonicalWorker.execute(() -> {
            try {
                canonicalReplay.warm(work);
            } catch (IOException | IllegalArgumentException ignored) {
                // Replay validates the inputs again when an edit requests them.
            }
        });
        worker.execute(() -> {
            ReconstructionLivePreview prepared = null;
            String problem = null;
            try {
                prepared = ReconstructionLivePreview.open(work, current.revision());
            } catch (Exception failure) {
                problem = describe(failure);
            }
            var result = prepared;
            var detail = problem;
            Platform.runLater(() -> {
                if (closed || snapshot.inspection() != current.inspection()) return;
                live = result;
                liveUnavailable = detail;
                if (queuedLiveEdit != null) saveLive();
            });
        });
    }

    private void journalChanged() { journalChanged(System.nanoTime()); }

    private void journalChanged(long eventNanos) {
        endComparison(false);
        long generation = supersedeLive();
        if (liveTransport != null && liveTransport.chipPlaying()) {
            var playback = liveTransport.liveEdit();
            if (playback == null) liveTransport.rebuildNeeded("Use an active Chip play range");
            else queueLive(generation, eventNanos, draft.entries(), playback, null);
        } else if (liveTransport != null && liveTransport.hasSavedPreview() && draft.pending()) {
            liveTransport.rebuildNeeded("Pending changes · Apply & rebuild");
        }
        updateDraft();
    }

    private void queueLive(long generation, long eventNanos, JsonArray entries,
                           ReconstructionTransport.LiveEdit playback, String author) {
        queuedLiveEdit = new LiveRequest(generation, eventNanos, entries, playback, author);
        liveTransport.livePreparing();
        saveLive();
    }

    private void auditionIncoming(JsonArray before, JsonArray after) {
        if (liveTransport == null) return;
        endComparison(false);
        var edit = liveTransport.liveEdit();
        if (edit == null) {
            liveTransport.rebuildNeeded("Saved changes from disk · Apply & rebuild");
            return;
        }
        String author = after.asList().stream().filter(entry -> !before.contains(entry))
                .map(entry -> text(entry, "author", "external")).distinct().collect(Collectors.joining(", "));
        queueLive(supersedeLive(), System.nanoTime(), after, edit, author.isEmpty() ? "external" : author);
    }

    private void cancelRefit() {
        if (refitJob == null) return;
        refitJob.cancel(true);
        refitJob = null;
    }

    private long supersedeLive() {
        long generation = ++liveGeneration;
        queuedLiveEdit = null;
        queuedCanonical = null;
        cancelRefit();
        var replay = canonicalThread;
        if (replay != null) replay.interrupt();
        return generation;
    }

    private void saveLive() {
        if (preparingLive || queuedLiveEdit == null || busy || external != null || externalError) return;
        if (live == null && liveUnavailable == null) return;
        var request = queuedLiveEdit;
        queuedLiveEdit = null;
        preparingLive = true;
        var expected = snapshot.revision();
        boolean local = request.author() == null;
        var entries = local ? journalForDraft(expected.entries(), request.entries()) : expected.entries();
        var reconstruction = Objects.equals(liveVoiceJournal, expected.voices()) ? live : null;
        var unavailable = reconstruction == null && live != null ? "Voice assignments changed" : liveUnavailable;
        worker.execute(() -> {
            ReconstructionLivePreview.Prepared preview = null;
            String fallback = unavailable;
            if (reconstruction != null) {
                try {
                    var window = request.playback().window();
                    var prepared = reconstruction.prepareProvisional(entries, window.start(), window.end(), request.playback().playhead());
                    prepared.requireCurrent(expected);
                    preview = prepared;
                } catch (IOException | IllegalArgumentException failure) {
                    fallback = describe(failure);
                }
            }
            var prepared = preview;
            var detail = fallback;
            Platform.runLater(() -> {
                preparingLive = false;
                if (closed) return;
                if (request.generation() == liveGeneration) {
                    queuedCanonical = new LiveCandidate(request, expected, prepared);
                    if (prepared != null) liveTransport.liveAudition(request.playback(), prepared, request.eventNanos());
                    else liveTransport.liveFallback(detail);
                    settleLive();
                }
                saveLive();
                updateDraft();
            });
        });
    }

    private void settleLive() {
        if (liveSaving || queuedCanonical == null || busy || external != null || externalError) return;
        var candidate = queuedCanonical;
        queuedCanonical = null;
        var request = candidate.request();
        var expected = snapshot.revision();
        boolean local = request.author() == null;
        var entries = local ? journalForDraft(expected.entries(), request.entries()) : expected.entries();
        var built = liveBase;
        liveSaving = true;
        canonicalWorker.execute(() -> {
            Revision written = null;
            ReconstructionLivePreview.Prepared playback = null;
            String problem = null;
            try {
                canonicalThread = Thread.currentThread();
                if (request.generation() != liveGeneration) throw new java.util.concurrent.CancellationException();
                var preview = candidate.playback();
                if (preview != null && !preview.provisional() && expected.equals(candidate.expected())) {
                    if (local) written = preview.save(expected);
                    else preview.requireCurrent(expected);
                    playback = preview;
                } else {
                    var rebuilt = canonicalReplay.rebuild(work, expected, built, entries, local,
                            (done, total, message) -> {});
                    playback = rebuilt.playback();
                    if (local) written = rebuilt.revision();
                }
            } catch (Exception failure) {
                problem = describe(failure);
            } finally {
                canonicalThread = null;
                Thread.interrupted();
            }
            var revision = written;
            var result = playback;
            var detail = problem;
            Platform.runLater(() -> {
                if (closed) return;
                liveSaving = false;
                if (revision != null) {
                    snapshot = new Snapshot(snapshot.inspection(), revision, snapshot.outcomes(), snapshot.report(),
                            snapshot.editing(), snapshot.voices(), snapshot.proposals());
                    var accepted = accepted(revision.entries());
                    draft.saved(accepted);
                    externalUnbuilt = !liveBase.equals(accepted);
                }
                if (request.generation() == liveGeneration) {
                    if (detail != null) liveTransport.rebuildNeeded(detail);
                    else liveTransport.liveSaved(request.playback(), result, request.eventNanos(), externalUnbuilt,
                            request.author(), candidate.playback() != null);
                }
                settleLive();
                saveLive();
                updateDraft();
                readExternal();
            });
        });
    }

    private void updateDraft() {
        noteModulation.clear();
        var pitchEdits = new LinkedHashMap<String, List<JsonObject>>();
        for (var entry : draft.entries()) {
            String op = text(entry, "op", "");
            if (!(op.equals("setNote") || op.equals("transpose") || op.equals("pinLane")) || !draft.changed(entry)
                    || ReconstructionReview.validationIssue(entry) != null) continue;
            for (String id : targetIds(entry))
                pitchEdits.computeIfAbsent(id, ignored -> new ArrayList<>()).add(entry.getAsJsonObject());
        }
        draftPitchEdits = pitchEdits;
        var rows = new ArrayList<EditRow>();
        var entries = draft.entries();
        var idCounts = entries.asList().stream().collect(Collectors.groupingBy(e -> text(e, "id", ""), Collectors.counting()));
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String id = text(entry, "id", "");
            String issue = ReconstructionReview.validationIssue(entry);
            if (issue == null && idCounts.get(id) > 1) issue = "Duplicate edit ID";
            if (issue == null && "noteVoicing".equals(text(entry, "op", ""))) {
                var edit = entry.getAsJsonObject();
                if (edit.has("vibrato") || edit.has("vibratoEnvelope") || edit.has("modulationOff"))
                    for (String noteId : targetIds(entry)) noteModulation.put(noteId, edit);
            }
            var results = snapshot.outcomes().getOrDefault(id, List.of());
            String state = issue != null ? "invalid" : draft.changed(entry) || results.isEmpty()
                    ? draft.saved(entry) ? "saved · rebuild needed" : "pending"
                    : results.stream().map(result -> result.status().equals("satisfied") ? "applied" : result.status())
                        .distinct().collect(Collectors.joining(", "));
            String detail = issue != null ? issue : results.stream().map(EditResult::reason).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" · "));
            rows.add(row(i, entry, state, detail));
        }
        for (var removed : draft.removed()) rows.add(row(-1, removed, "pending removal", ""));
        journal.getItems().setAll(rows);
        updateVoiceProjection();
        var pending = overlays();
        view.pending(pending);
        var edited = new java.util.HashSet<String>();
        for (var entry : draft.entries()) if (DETAIL_EDITS.contains(text(entry, "op", ""))) {
            edited.addAll(targetIds(entry));
            var outcomes = snapshot.outcomes().getOrDefault(text(entry, "id", ""), List.of());
            for (var result : outcomes) edited.addAll(result.noteIds());
            if (text(entry, "op", "").equals("splitNote")) {
                String prefix = "edit-" + text(entry, "id", "");
                edited.add(prefix + "-left");
                edited.add(prefix + "-right");
            }
        }
        var projection = ReconstructionVoices.of(snapshot.inspection().overlays(), pending, draftVoices, draftMembership, voicePalette);
        view.voices(projection, Set.copyOf(edited));
        voiceLegend.show(projection.entries());
        updateInsertVoices(projection);
        onVoicesChanged.run();
        updateProposals();
        updateControls();
    }

    private static EditRow row(int index, JsonElement entry, String state, String detail) {
        return new EditRow(index, text(entry, "op", "Unreadable edit") + " · " + text(entry, "author", "agent")
                + " · " + state, text(entry, "id", "—") + "\n" + text(entry, "reason", "No reason supplied")
                + (detail.isBlank() ? "" : "\n" + detail));
    }

    private void updateControls() {
        boolean editing = editable();
        view.editing(editing);
        bulk.setDisable(!editing);
        voiceLegend.controls(editing, view.selectedNotes().stream().anyMatch(ReconstructionEditor::classifiable));
        proposalList.setDisable(!editing);
        stopPreview.setDisable(!previewPlaying);
        var entries = draft.entries();
        boolean unbuilt = entries.asList().stream().anyMatch(e -> !snapshot.outcomes().containsKey(text(e, "id", "")));
        apply.setDisable(!editing || liveSaving || preparingLive || !draft.pending() && !unbuilt && !externalUnbuilt);
        lanePolicies.setDisable(!editing || liveSaving || preparingLive || snapshot.report() == null || draft.pending() || unbuilt || externalUnbuilt);
        undo.setDisable(!editing || !draft.canUndo(false));
        redo.setDisable(!editing || !draft.canUndo(true));
        cancel.setVisible(busy);
        progress.setVisible(busy);
        conflict.setVisible(external != null);
        long changes = draft.unsavedChanges();
        String count = changes + (changes == 1 ? " unsaved change" : " unsaved changes");
        history.setDisable(undo.isDisable() && redo.isDisable());
        history.setVisible(draft.canUndo(false) || draft.canUndo(true));
        apply.setVisible(draft.pending() || unbuilt || externalUnbuilt);
        apply.setText(changes == 0 ? "Apply & rebuild" : count + " · Apply & rebuild");
        nextStep.setText(busy ? "Rebuilding the arrangement…"
                : external != null ? "Resolve the external edit conflict to continue."
                : externalError ? "Fix the invalid external edits to continue."
                : draft.pending() ? "Rebuild needed · Apply & rebuild to hear pending changes."
                : unbuilt || externalUnbuilt ? "Apply & rebuild to include the saved edits in exports."
                : branchCount > 0 ? "Review " + branchCount + (branchCount == 1 ? " edit branch" : " edit branches")
                : proposalCount > 0 ? "Open Review · " + proposalCount
                        + (proposalCount == 1 ? " agent proposal to review" : " agent proposals to review")
                : snapshot.inspection().project() == null ? "Check which voices are assigned to your notes."
                : "Ready to export · You can still adjust roles and notes.");
        noteDelete.setDisable(!editing || deletableNotes().isEmpty());
        role.setDisable(!editing || view.selectedNotes().stream().noneMatch(ReconstructionEditor::classifiable));
        lane.setDisable(!editing || !selectableNote());
        velocity.setDisable(!editing || selected == null || !editableScore(selected));
        var edit = journal.getSelectionModel().getSelectedItem();
        remove.setDisable(!editing || edit == null || edit.index() < 0);
        reviseEdit.setDisable(remove.isDisabled());
        onJournalState.run();
    }

    private void history(boolean forward) {
        if (busy || external != null || externalError) return;
        if (!draft.canUndo(forward)) return;
        long eventNanos = System.nanoTime();
        draft.undo(forward);
        journalChanged(eventNanos);
    }

    boolean key(KeyEvent event, boolean editorFocused) {
        int semitones = TransposeKeys.semitones(event);
        var code = event.getCode();
        if (semitones != 0 && view.canvas.isFocused() && !view.reviewing()) {
            transpose(semitones);
        } else if (event.isShortcutDown() && (code == KeyCode.Z || code == KeyCode.Y)) {
            history(code == KeyCode.Y || event.isShiftDown());
        } else if (editorFocused && !view.reviewing() && !event.isShortcutDown()
                && (code == KeyCode.DELETE || code == KeyCode.BACK_SPACE)) {
            delete();
        } else if (view.canvas.isFocused() && !view.reviewing() && AgentClipboard.shortcut(event)) {
            if (!view.rolesView()) copyNotes();
            return copyForAgent();
        } else if (view.canvas.isFocused() && !view.reviewing() && !view.rolesView() && event.isShortcutDown()) {
            switch (code) {
                case A -> selectNotes(note -> true);
                case V -> paste(view.playhead());
                default -> { return false; }
            }
        } else if (!event.isShortcutDown() && code == KeyCode.O) {
            outline.setSelected(!outline.isSelected());
            view.outline(outline.isSelected());
        } else return false;
        return true;
    }

    java.util.concurrent.Callable<com.opensmpsdeck.headless.ProjectExport.Source> exportJournal(StudioProject open) {
        if (open != null && open.detachedFromReconstruction()) return null;
        if (busy || liveSaving || external != null || externalError)
            throw new IllegalStateException("Finish the current reconstruction update before exporting");
        var expected = snapshot.revision();
        var entries = forApply(expected.entries(), draft.entries());
        var built = liveBase;
        if (built.equals(accepted(entries)) && !externalUnbuilt) return null;
        return () -> ReconstructionReview.exportSource(work, expected, built, entries, (done, total, message) -> {});
    }

    private void apply(String lanePolicyPreset) {
        DetachedArrangement.Approval approval;
        try {
            var decision = DetachedArrangement.confirm(owner, work, openProject.get());
            if (decision.isEmpty() || closed || (lanePolicyPreset == null ? apply : lanePolicies).isDisabled()) return;
            approval = decision.get();
        } catch (IOException | IllegalArgumentException failure) {
            status.accept("Apply blocked · " + describe(failure));
            return;
        }
        setBusy();
        var expected = snapshot.revision();
        var intents = journalForDraft(expected.entries(), draft.entries());
        var built = liveBase;
        worker.execute(() -> {
            JsonObject diff = null;
            Snapshot next = null;
            Throwable failure = null;
            try {
                synchronized (jobLock) {
                    running = Thread.currentThread();
                    if (cancelling) throw new java.util.concurrent.CancellationException();
                }
                diff = lanePolicyPreset == null
                        ? ReconstructionReview.applySaved(work, expected, built, intents, approval.revision(), this::reportProgress)
                        : ReconstructionReview.applyLanePolicyPreset(work, expected, lanePolicyPreset, approval.revision(), this::reportProgress);
                next = Snapshot.read(work);
            } catch (Throwable error) {
                failure = error;
            } finally {
                synchronized (jobLock) { running = null; }
                Thread.interrupted();
            }
            var result = diff;
            var fresh = next;
            var error = failure;
            Platform.runLater(() -> finish(result, fresh, error));
        });
    }

    private void reportProgress(int completed, int total, String message) {
        if (completed == total) {
            synchronized (jobLock) {
                if (cancelling) throw new java.util.concurrent.CancellationException();
                publishing = true;
            }
        }
        Platform.runLater(() -> {
            if (closed) return;
            progress.setProgress(completed / (double) total);
            if (completed == total) cancel.setDisable(true);
            synchronized (jobLock) { if (!cancelling) status.accept(message); }
        });
    }

    private void finish(JsonObject diff, Snapshot next, Throwable error) {
        if (closed) return;
        busy = false;
        if (diff != null) {
            var published = next != null ? next.revision().entries() : journalForDraft(snapshot.revision().entries(), draft.entries());
            draft.applied(accepted(published));
            externalUnbuilt = false;
            comparison.setText(summary(diff));
            tabs.getSelectionModel().select(diffTab);
            if (next != null) adopt(next);
            status.accept(error == null ? "Reconstruction applied from " + diff.get("from").getAsString()
                    : "Applied, but refresh failed · " + describe(error));
        } else if (error != null) {
            status.accept(cancelling ? "Apply cancelled · edits are still pending" : "Apply failed · " + describe(error));
        }
        updateDraft();
        readExternal();
    }

    /** A readable message for any worker failure, including errors whose own message is null. */
    static String describe(Throwable failure) {
        var root = failure;
        while (root.getMessage() == null && root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    private void cancel() {
        synchronized (jobLock) {
            if (publishing) return;
            cancelling = true;
            if (running != null) running.interrupt();
        }
        cancel.setDisable(true);
        status.accept("Cancelling reconstruction…");
    }

    private void setBusy() {
        endComparison(true);
        supersedeLive();
        busy = true;
        stopAudition();
        synchronized (jobLock) {
            cancelling = false;
            publishing = false;
        }
        progress.setProgress(0);
        cancel.setDisable(false);
        updateControls();
    }

    private static String summary(JsonObject diff) {
        var text = new StringBuilder("Rebuilt from ").append(diff.get("from").getAsString()).append("\n\n")
                .append(diff.get("changedNotes").getAsInt()).append(" changed notes");
        if (diff.has("sourceStart")) text.append(String.format(Locale.ROOT, " · %d outside %.3f–%.3f s",
                diff.get("outsideSpanChanges").getAsInt(), diff.get("sourceStart").getAsDouble(),
                diff.get("sourceEnd").getAsDouble()));
        var permutations = diff.getAsJsonArray("lanePermutations");
        long moved = permutations.asList().stream().map(JsonElement::getAsJsonObject)
                .mapToLong(group -> group.get("noteCount").getAsLong()).sum();
        text.append("\n\nLane permutations\n").append(permutations.isEmpty() ? "None"
                : permutations.size() + " groups · " + moved + " note moves");
        var events = diff.getAsJsonObject("compiledTimeline");
        var pcm = diff.getAsJsonObject("renderedPcm");
        text.append("\n\nCompiled timeline\n").append(events.get("available").getAsBoolean()
                ? events.get("changedEvents").getAsInt() + " changed events" : "Unavailable");
        if (events.has("firstTick")) text.append(" · first project tick ").append(events.get("firstTick").getAsInt());
        text.append("\n\nRendered PCM\n").append(pcm.get("available").getAsBoolean()
                ? pcm.get("changedFrames").getAsLong() + " changed sample frames" : "Unavailable");
        if (pcm.has("firstFrame")) text.append(" · first frame ").append(pcm.get("firstFrame").getAsLong());
        text.append("\n\nPSG tail\n");
        var tail = pcm.getAsJsonArray("psgTail");
        if (tail == null || tail.isEmpty()) text.append("No lane tail difference reported");
        else for (var item : tail) {
            var entry = item.getAsJsonObject();
            text.append(entry.get("lane").getAsString()).append(" · ").append(entry.get("changedFrames").getAsLong())
                    .append(" frames · ").append(entry.get("cause").getAsString()).append("\n");
        }
        if (!permutations.isEmpty()) {
            text.append("\n\nLane permutation details\n");
            for (var item : permutations) {
                var group = item.getAsJsonObject();
                var lanes = group.getAsJsonObject("lanes").entrySet().stream()
                        .map(entry -> entry.getKey() + " → " + entry.getValue().getAsString()).collect(Collectors.joining(", "));
                text.append(String.format(Locale.ROOT, "%s · %d notes, %.3f–%.3f s%n", lanes, group.get("noteCount").getAsInt(),
                        group.get("startSeconds").getAsDouble(), group.get("endSeconds").getAsDouble()));
            }
        }
        return text.toString();
    }

    private void readExternal() {
        if (closed || busy || liveSaving) return;
        if (refreshQueued) {
            checkAgain = true;
            return;
        }
        refreshQueued = true;
        checkAgain = false;
        var current = snapshot;
        worker.execute(() -> {
            try {
                var revision = ReconstructionReview.revision(work);
                var report = Snapshot.report(work);
                boolean rebuilt = report == null ? current.report() != null : current.report() == null
                        || !report.lastModifiedTime().equals(current.report().lastModifiedTime())
                        || !Objects.equals(report.fileKey(), current.report().fileKey());
                boolean proposalsOnly = !rebuilt && Objects.equals(current.revision().sections(), revision.sections())
                        && Objects.equals(current.revision().voices(), revision.voices())
                        && accepted(current.revision().entries()).equals(accepted(revision.entries()));
                boolean journalsOnly = !rebuilt && Objects.equals(current.revision().sections(), revision.sections());
                var next = !rebuilt && revision.equals(current.revision()) ? current
                        : journalsOnly ? current.journalsFromDisk(work, revision, proposalsOnly) : Snapshot.read(work);
                Platform.runLater(() -> {
                    refreshQueued = false;
                    if (closed || busy || liveSaving) return;
                    if (snapshot != current) {
                        readExternal();
                        return;
                    }
                    externalError = false;
                    if (next == current) {
                        external = null;
                        updateControls();
                        if (checkAgain) readExternal();
                        return;
                    }
                    boolean sectionsChanged = !Objects.equals(current.revision().sections(), next.revision().sections());
                    var incoming = next.revision().entries();
                    var previous = current.revision().entries();
                    boolean acceptedChanged = !accepted(previous).equals(accepted(incoming))
                            || !Objects.equals(current.revision().voices(), next.revision().voices());
                    var local = draft.entries();
                    boolean superseded = previous.asList().stream().filter(ReconstructionProposals::isProposed)
                            .anyMatch(entry -> !incoming.contains(entry)
                                    && local.asList().stream().anyMatch(edit -> text(edit, "id", "").equals(text(entry, "id", ""))));
                    if (draft.adopt(accepted(incoming), sectionsChanged || superseded || acceptedChanged && draft.pending())) {
                        if (sectionsChanged || !accepted(previous).equals(accepted(incoming))) branchJournals.clear();
                        external = null;
                        if (rebuilt) externalUnbuilt = false;
                        else if (acceptedChanged) externalUnbuilt = true;
                        adopt(next);
                        if (acceptedChanged && !rebuilt) auditionIncoming(accepted(previous), accepted(incoming));
                        status.accept("Adopted reconstruction changes from disk");
                    } else {
                        external = next;
                        view.editing(false);
                        status.accept("Edits or sections changed on disk · resolve the conflict to continue");
                        updateControls();
                    }
                    if (checkAgain) readExternal();
                });
            } catch (Throwable failure) {
                Platform.runLater(() -> {
                    refreshQueued = false;
                    if (closed || busy || liveSaving) return;
                    if (snapshot != current) {
                        readExternal();
                        return;
                    }
                    externalError = true;
                    status.accept("External reconstruction update unavailable · " + describe(failure));
                    updateControls();
                    if (checkAgain) readExternal();
                });
            }
        });
    }

    private void adopt(Snapshot next) {
        boolean rebuilt = snapshot.inspection() != next.inspection();
        snapshot = next;
        external = null;
        if (rebuilt) {
            endComparison(false);
            supersedeLive();
            sections = next.inspection().sections().sections();
            refresh.accept(next.inspection());
            prepareLive();
            if (externalUnbuilt) liveTransport.rebuildNeeded("Saved changes from disk · Apply & rebuild");
        }
        updateDraft();
    }

    private void resolve() {
        if (external == null) return;
        var reload = new ButtonType("Discard pending and reload", ButtonBar.ButtonData.OK_DONE);
        var prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "The edit or sections journal changed on disk. Reload it and discard your pending edits, or keep them in this window.",
                reload, new ButtonType("Keep pending", ButtonBar.ButtonData.CANCEL_CLOSE));
        prompt.initOwner(owner);
        prompt.setHeaderText("Reconstruction conflict");
        if (prompt.showAndWait().orElse(ButtonType.CANCEL) == reload && external != null) {
            externalUnbuilt = !accepted(snapshot.revision().entries()).equals(accepted(external.revision().entries()))
                    || !Objects.equals(snapshot.revision().voices(), external.revision().voices());
            draft.discard(accepted(external.revision().entries()));
            branchJournals.clear();
            var previous = accepted(snapshot.revision().entries());
            var next = external;
            boolean journalsOnly = snapshot.inspection() == next.inspection();
            adopt(next);
            if (externalUnbuilt && journalsOnly) auditionIncoming(previous, accepted(next.revision().entries()));
            status.accept("Reloaded reconstruction journals");
            readExternal();
        }
    }

    private void sectionMenu(ReconstructionCanvas.SectionPoint point) {
        var section = point.section();
        var rename = new MenuItem("Rename " + section.level());
        rename.setOnAction(event -> {
            var dialog = new TextInputDialog(section.label());
            dialog.initOwner(owner);
            dialog.setTitle("Rename " + section.level());
            dialog.setHeaderText(null);
            dialog.setContentText("Name");
            dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty())
                    .ifPresent(name -> section(SectionEdits.rename(section, name)));
        });
        var split = new MenuItem("Split here");
        split.setDisable(point.time() <= section.sourceStart() + 1e-6 || point.time() >= section.sourceEnd() - 1e-6);
        split.setOnAction(event -> section(SectionEdits.split(section, point.time())));
        var merge = new MenuItem("Merge with next " + section.level());
        var next = SectionEdits.neighbor(sections, section, true);
        merge.setDisable(next == null || !Objects.equals(next.parentId(), section.parentId()));
        merge.setOnAction(event -> section(SectionEdits.merge(section, next)));
        new ContextMenu(rename, split, merge).show(owner, point.screenX(), point.screenY());
    }

    private void section(SectionEdits.Mutation mutation) {
        if (busy || external != null || externalError) return;
        var expected = snapshot.revision();
        setBusy();
        cancel.setDisable(true);
        status.accept("Saving musical sections…");
        worker.execute(() -> {
            try {
                var result = ReconstructionReview.section(work, expected, mutation.action(), mutation.entries());
                var updated = snapshot.journalsFromDisk(work, result.revision(), true);
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    snapshot = updated;
                    sections = result.result().sections();
                    externalUnbuilt = true;
                    view.sections(sections);
                    status.accept("Musical sections saved");
                    updateDraft();
                    readExternal();
                });
            } catch (Throwable failure) {
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    status.accept("Section change failed · " + describe(failure));
                    updateControls();
                    readExternal();
                });
            }
        });
    }

    boolean allowClose() {
        if (busy || liveSaving) {
            status.accept("Wait for the reconstruction job to finish, or cancel Apply.");
            return false;
        }
        if (!draft.pending()) return true;
        var discard = new ButtonType("Discard pending", ButtonBar.ButtonData.OK_DONE);
        var prompt = new Alert(Alert.AlertType.CONFIRMATION, "Discard pending reconstruction edits?",
                discard, ButtonType.CANCEL);
        prompt.initOwner(owner);
        prompt.setHeaderText(null);
        return prompt.showAndWait().orElse(ButtonType.CANCEL) == discard;
    }

    @Override public void close() {
        audition.close();
        closed = true;
        watchers.forEach(ProjectWatcher::close);
        synchronized (jobLock) {
            if (running != null && !publishing) running.interrupt();
        }
        supersedeLive();
        canonicalWorker.shutdownNow();
        refitWorker.shutdownNow();
        worker.shutdown();
    }
}
