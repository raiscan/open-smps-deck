package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.project.ProjectDocument;
import com.opensmpsdeck.project.TargetGame;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reconstruction workspace with synced playback and a separate reconstruction edit history. */
final class ReconstructionPane implements AutoCloseable {
    private enum Workspace { Roles, Notes, Review, Export }
    private final Stage stage = new Stage();
    private final BorderPane root = new BorderPane();
    private final Label status = new Label("Loading reconstruction…");
    private final ExecutorService loader = worker("reconstruction-load");
    private final ExecutorService bendLoader = worker("reconstruction-bends");
    private final Label title = new Label();
    private final Label phase = new Label("Import ✓  ·  Review  ·  Export");
    private final Label nextStep = new Label("Loading reconstruction…");
    private final VBox header = new VBox(5);
    private final HBox titleRow = new HBox(12);
    private final Path work;
    private final Runnable openArrangement;
    private final Supplier<ProjectDocument> openProject;
    private final Slider zoom = new Slider(0, ReconstructionCanvas.MAX_TIME_ZOOM, 0);
    private ReconstructionInspection model;
    private ReconstructionCanvas view;
    private ReconstructionEditor editor;
    private MenuButton laneFilters;
    private ReconstructionTransport listen;
    private boolean closed;

    ReconstructionPane(Window owner, Path work) {
        this(owner, work, null, () -> null);
    }

    ReconstructionPane(Window owner, Path work, Runnable openArrangement, Supplier<ProjectDocument> openProject) {
        this.work = work;
        this.openArrangement = openArrangement;
        this.openProject = openProject;
        if (owner != null) stage.initOwner(owner);
        var screen = Screen.getPrimary().getVisualBounds();
        var scene = new Scene(root, Math.min(1510, screen.getWidth() * .96), Math.min(920, screen.getHeight() * .93));
        scene.getStylesheets().add(getClass().getResource("studio.css").toExternalForm());
        String folder = work.getFileName() == null ? "Reconstruction" : work.getFileName().toString();
        stage.setTitle("Reconstruction · " + folder);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(970, screen.getWidth() * .9));
        stage.setMinHeight(Math.min(670, screen.getHeight() * .9));
        stage.setOnHidden(event -> close());
        status.setId("reconstruction-status");
        status.getStyleClass().add("status");
        root.setBottom(status);
        title.setText(folder);
        title.setId("reconstruction-title");
        title.getStyleClass().add("project-title");
        phase.getStyleClass().add("reconstruction-phase");
        phase.setVisible(false);
        phase.managedProperty().bind(phase.visibleProperty());
        title.setTooltip(new Tooltip(folder));
        title.setMinWidth(100);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getChildren().addAll(title, phase, spacer());
        nextStep.getStyleClass().add("reconstruction-next-step");
        header.getChildren().addAll(titleRow, nextStep);
        header.getStyleClass().add("reconstruction-header");
        root.setTop(header);
        stage.show();
        loader.submit(() -> {
            try {
                var snapshot = ReconstructionEditor.Snapshot.read(work);
                Platform.runLater(() -> {
                    if (!closed) initialize(snapshot);
                });
            } catch (Exception failure) {
                Platform.runLater(() -> {
                    if (!closed) {
                        nextStep.setText("Could not open reconstruction");
                        status.setText("Could not open reconstruction · " + failure.getMessage());
                    }
                });
            }
        });
    }

    Stage stage() { return stage; }
    ReconstructionCanvas canvas() { return view; }
    ReconstructionInspection inspection() { return model; }

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(runnable -> Thread.ofPlatform().daemon().name(name).unstarted(runnable));
    }

    private void initialize(ReconstructionEditor.Snapshot snapshot) {
        model = snapshot.inspection();
        if (title.getText().equalsIgnoreCase("reconstruction") && model.project() != null
                && !model.project().name().isBlank() && !model.project().name().equalsIgnoreCase("reconstruction")) {
            title.setText(model.project().name());
            title.getTooltip().setText(title.getText());
        }
        view = new ReconstructionCanvas(model, status::setText);
        editor = new ReconstructionEditor(work, stage, view, snapshot, status::setText, this::refresh,
                position -> listen.cuePreview(position), openProject);
        listen = new ReconstructionTransport(stage, work, view, model, status, editor::stopAudition);
        view.onNote = editor::show;
        view.copyForAgent = editor::copyForAgent;
        var lasso = new ScreenshotLasso(view, editor::lasso, status::setText).controls();
        lasso.managedProperty().bind(lasso.visibleProperty());
        editor.onSelectionChanged = listen::selectionChanged;
        editor.onVoicesChanged = listen::refreshTargets;
        stage.setOnCloseRequest(event -> { if (!editor.allowClose()) event.consume(); });
        titleRow.getChildren().add(editor.changeControls());
        nextStep.textProperty().bind(editor.nextStep().textProperty());
        var tools = editor.toolbar();
        editor.initializeInspectors();
        var footer = listen.bar();
        var viewBar = displayControls();
        var viewToggle = new ToggleButton("View");
        viewToggle.setTooltip(new Tooltip("Show zoom, spectrogram and display settings"));
        viewToggle.setId("reconstruction-view-settings");
        viewBar.visibleProperty().bind(viewToggle.selectedProperty());
        viewBar.managedProperty().bind(viewBar.visibleProperty());

        var plot = new BorderPane(view.scroll, null, view.pitchScroll, null, null);
        var side = new StackPane(editor.rolesInspector(), editor.notesInspector(), editor.reviewInspector());
        side.setPrefWidth(372);
        side.setMinWidth(350);
        var workspace = new BorderPane(plot, null, side, null, null);
        var export = exportWorkspace();
        var content = new StackPane(workspace, export);
        root.setCenter(content);
        root.setBottom(footer);

        var choices = new ToggleGroup();
        var switcher = new HBox(5, new Label("Workspace"));
        switcher.getStyleClass().add("reconstruction-workspaces");
        switcher.setAlignment(Pos.CENTER_LEFT);
        var buttons = new java.util.EnumMap<Workspace, ToggleButton>(Workspace.class);
        for (Workspace item : Workspace.values()) {
            var button = new ToggleButton(item.name());
            String id = item == Workspace.Notes ? "detail" : item.name().toLowerCase(Locale.ROOT);
            button.setId("reconstruction-" + id + "-view");
            button.setToggleGroup(choices);
            button.setUserData(item);
            buttons.put(item, button);
            switcher.getChildren().add(button);
        }
        switcher.getChildren().addAll(spacer(), lasso, viewToggle);
        for (var node : List.of(tools, side, workspace, export, editor.rolesInspector(),
                editor.notesInspector(), editor.reviewInspector(), viewToggle))
            node.managedProperty().bind(node.visibleProperty());

        choices.selectedToggleProperty().addListener((ignored, old, next) -> {
            if (next == null) {
                choices.selectToggle(old);
                return;
            }
            var page = (Workspace) next.getUserData();
            boolean showRoles = page == Workspace.Roles;
            boolean showNotes = page == Workspace.Notes;
            boolean showReview = page == Workspace.Review;
            view.rolesView(showRoles);
            view.reviewing(showReview);
            tools.setVisible(showNotes);
            lasso.setVisible(showRoles || showNotes);
            workspace.setVisible(page != Workspace.Export);
            editor.rolesInspector().setVisible(showRoles);
            editor.notesInspector().setVisible(showNotes);
            editor.reviewInspector().setVisible(showReview);
            export.setVisible(page == Workspace.Export);
            viewToggle.setVisible(page != Workspace.Export);
            if (page == Workspace.Export) viewToggle.setSelected(false);
            phase.setText(switch (page) {
                case Roles, Notes -> "Import ✓  ·  Review  ·  Export";
                case Review -> "Import ✓  ·  ● Review  ·  Export";
                case Export -> "Import ✓  ·  Review  ·  ● Export";
            });
            if (page != Workspace.Export) view.canvas.requestFocus();
        });
        choices.selectToggle(buttons.get(Workspace.Roles));
        phase.setVisible(true);
        root.setTop(new VBox(header, switcher, viewBar, tools));
        view.onDetailJump = notes -> {
            choices.selectToggle(buttons.get(Workspace.Notes));
            zoom.setValue(view.detailZoom(notes));
            double start = notes.stream().mapToDouble(note -> note.interval().sourceStart()).min().orElse(0);
            double end = notes.stream().mapToDouble(note -> note.interval().sourceEnd()).max().orElse(start);
            view.center((start + end) / 2);
        };

        stage.getScene().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (stage.getScene().getFocusOwner() instanceof TextInputControl || event.isAltDown()) return;
            if (editor.key(event) || listen.key(event)) event.consume();
        });
        listen.initialize();
        loadBends(model);
    }

    private VBox exportWorkspace() {
        var heading = new Label("Use your reconstruction");
        heading.getStyleClass().add("project-title");
        var output = new Button("Export…");
        output.setId("reconstruction-export");
        output.getStyleClass().add("primary-action");
        output.setOnAction(event -> {
            var arrangement = work.resolve(ReconstructionReview.ARRANGEMENT).toAbsolutePath().normalize();
            var open = openProject.get();
            var snapshot = open != null && arrangement.equals(open.path()) ? open.project() : null;
            var fallback = model.project() == null ? TargetGame.S3K : model.project().target();
            ExportDialog.show(stage, snapshot, arrangement, work, fallback, RomLibrary.userLibrary(), loader, status::setText);
        });
        var card = new VBox(10, heading, new Label("Save audio or export a package for your target game."), output);
        if (openArrangement != null) {
            var edit = new Button("Open as arrangement");
            edit.setId("open-as-arrangement");
            edit.setOnAction(event -> openArrangement.run());
            card.getChildren().addAll(new Label("Continue editing chip sequences and instruments in Studio."), edit);
        }
        editor.nextStep().textProperty().addListener((ignored, old, next) -> {
            if (next.startsWith("Apply & rebuild")) heading.setText("Export the last build");
            else heading.setText("Use your reconstruction");
        });
        card.getStyleClass().add("reconstruction-export");
        card.setMaxSize(470, Region.USE_PREF_SIZE);
        return card;
    }

    private void loadBends(ReconstructionInspection inspection) {
        bendLoader.submit(() -> {
            try {
                var bends = inspection.assembledBends();
                if (!bends.isEmpty()) Platform.runLater(() -> {
                    if (!closed && model == inspection) view.assembledBends(bends);
                });
            } catch (Exception failure) {
                if (!Thread.currentThread().isInterrupted() && !(failure instanceof java.io.InterruptedIOException))
                    System.getLogger(ReconstructionPane.class.getName()).log(System.Logger.Level.WARNING,
                            "Could not replay assembled bend evidence", failure);
            }
        });
    }

    private void refresh(ReconstructionInspection next) {
        model = next;
        listen.refresh(next);
        refreshLaneFilters();
        loadBends(next);
    }

    private FlowPane displayControls() {
        zoom.setId("reconstruction-zoom");
        zoom.setPrefWidth(160);
        zoom.setBlockIncrement(1);
        zoom.valueProperty().addListener((ignored, previous, next) -> view.zoom(next.doubleValue()));
        view.onTimeZoom = zoom::setValue;
        var fit = new Button("Fit song");
        fit.setOnAction(event -> zoom.setValue(0));
        var pitchOut = new Button("−");
        pitchOut.setAccessibleText("Zoom pitch out");
        pitchOut.setTooltip(new Tooltip("Show more pitches"));
        pitchOut.setOnAction(event -> view.zoomPitch(1.4));
        var pitchIn = new Button("+");
        pitchIn.setAccessibleText("Zoom pitch in");
        pitchIn.setTooltip(new Tooltip("Show fewer pitches"));
        pitchIn.setOnAction(event -> view.zoomPitch(1 / 1.4));
        var fitPitch = new Button("Fit pitch");
        fitPitch.setId("reconstruction-fit-pitch");
        fitPitch.setTooltip(new Tooltip("Fit the tonal notes in the visible time window"));
        fitPitch.setOnAction(event -> view.fitPitch());
        var pitch = new HBox(4, new Label("Pitch zoom"), pitchOut, pitchIn, fitPitch);
        pitch.setAlignment(Pos.CENTER_LEFT);
        var db = new Slider(20, 85, 55);
        db.setPrefWidth(120);
        var dbLabel = new Label();
        Consumer<Number> setDb = value -> {
            int range = value.intValue();
            dbLabel.setText((ReconstructionTiles.CEILING_DB - range) + " to " + ReconstructionTiles.CEILING_DB + " dBFS");
            view.dbRange(range);
        };
        setDb.accept(db.getValue());
        db.valueProperty().addListener((ignored, previous, next) -> {
            if (!db.isValueChanging()) setDb.accept(next);
        });
        db.valueChangingProperty().addListener((ignored, previous, changing) -> {
            if (!changing) setDb.accept(db.getValue());
        });
        var ridges = new CheckBox("Ridges");
        ridges.setTooltip(new Tooltip("Show the three strongest pitch peaks per column"));
        ridges.selectedProperty().addListener((ignored, previous, selected) -> view.ridges(selected));
        var project = new CheckBox("Project time");
        project.selectedProperty().addListener((ignored, previous, selected) -> listen.projectTime(selected));
        var sources = listen.spectrumSource();
        laneFilters = new MenuButton("Show lanes");
        refreshLaneFilters();
        var row = new FlowPane(9, 6,
                new Label("Time zoom"), zoom, fit, pitch, new Label("Spectrum range"), db, dbLabel,
                new Label("Spectrum source"), sources, ridges, project, laneFilters, editor.visibility());
        row.getStyleClass().addAll("panelbar", "reconstruction-controls");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshLaneFilters() {
        laneFilters.getItems().clear();
        for (String channel : java.util.stream.Stream.concat(view.lanes().stream(),
                java.util.stream.Stream.of(ReconstructionCanvas.UNASSIGNED)).toList()) {
            var filter = new CheckMenuItem(channel);
            filter.setSelected(view.laneVisible(channel));
            filter.setOnAction(event -> view.laneVisible(channel, filter.isSelected()));
            laneFilters.getItems().add(filter);
        }
    }

    private static Region spacer() {
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (listen != null) listen.close();
        if (editor != null) editor.close();
        if (view != null) view.close();
        loader.shutdownNow();
        bendLoader.shutdownNow();
        stage.hide();
    }
}
