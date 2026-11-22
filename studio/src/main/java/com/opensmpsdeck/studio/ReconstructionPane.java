package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioKind;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioSource;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.headless.ReconstructionView;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.project.ProjectDocument;
import com.opensmpsdeck.project.TargetGame;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reconstruction workspace with synced playback and a separate reconstruction edit history. */
final class ReconstructionPane implements AutoCloseable {
    private final Stage stage = new Stage();
    private final BorderPane root = new BorderPane();
    private final Label status = new Label("Loading reconstruction…");
    private final Label clock = new Label();
    private final StudioPlayback player = new StudioPlayback();
    private final ReviewTransport transport = new ReviewTransport();
    private final ExecutorService loader = worker("reconstruction-load");
    private final ExecutorService bendLoader = worker("reconstruction-bends");
    private final ExecutorService audioControl = worker("reconstruction-audio-control");
    // Only the loader touches this cache. Playback holds the current immutable waveform.
    private final LinkedHashMap<AudioSource, ReferenceWave> waves = new LinkedHashMap<>(4, .75f, true);
    private final ComboBox<AudioSource> sources = new ComboBox<>();
    private final Button play = new Button("Play");
    private final ToggleButton soloLane = new ToggleButton("Solo lane");
    private final ToggleButton soloNote = new ToggleButton("Solo note");
    private final Button clearRange = new Button("Clear loop");
    private final Label loopLabel = new Label("Shift-drag to loop");
    private final Slider zoom = new Slider(0, 9, 0);
    private final AnimationTimer playheadTimer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (playing && Double.isFinite(devicePosition) && devicePosition != projectPosition)
                updatePosition(devicePosition, true);
        }
    };

    private ReconstructionInspection model;
    private ReconstructionCanvas view;
    private ReconstructionEditor editor;
    private MenuButton laneFilters;
    private long sourceEpoch;
    private ReviewTransport.Range range;
    private Overlay note;
    private AudioSource lastReference;
    private String lane;
    private Future<?> waveRead;
    private StudioPlayback.Samples loadedSamples;
    private volatile StudioPlayback.Samples monitorSamples;
    private volatile double devicePosition = Double.NaN;
    private double projectPosition;
    private volatile long epoch;
    private boolean playing;
    private boolean playWhenLoaded;
    private boolean closed;

    ReconstructionPane(Window owner, Path work) {
        this(owner, work, null, () -> null);
    }

    ReconstructionPane(Window owner, Path work, Runnable openArrangement, Supplier<ProjectDocument> openProject) {
        if (owner != null) stage.initOwner(owner);
        var screen = Screen.getPrimary().getVisualBounds();
        var scene = new Scene(root, Math.min(1510, screen.getWidth() * .96), Math.min(920, screen.getHeight() * .93));
        scene.getStylesheets().add(getClass().getResource("studio.css").toExternalForm());
        String folder = work.getFileName() == null ? work.toString() : work.getFileName().toString();
        stage.setTitle("Reconstruction · " + folder);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(970, screen.getWidth() * .9));
        stage.setMinHeight(Math.min(670, screen.getHeight() * .9));
        stage.setOnHidden(event -> close());
        status.getStyleClass().add("status");
        var footer = new HBox(12, status, spacer(), clock);
        footer.getStyleClass().add("statusbar");
        root.setBottom(footer);
        var heading = new Label("Reconstruction");
        heading.getStyleClass().add("project-title");
        var project = new Label(folder);
        project.getStyleClass().add("muted");
        var title = new HBox(14, heading, spacer(), project);
        var arrangement = work.resolve(ReconstructionReview.ARRANGEMENT).toAbsolutePath().normalize();
        var output = new Button("Export…");
        output.setId("reconstruction-export");
        output.setOnAction(event -> {
            var open = openProject.get();
            var snapshot = open != null && arrangement.equals(open.path()) ? open.project() : null;
            var fallback = model.project() == null ? TargetGame.S3K : model.project().target();
            ExportDialog.show(stage, snapshot, arrangement, work, fallback, RomLibrary.userLibrary(), loader, status::setText);
        });
        title.getChildren().add(output);
        if (openArrangement != null) {
            var edit = new Button("Open as arrangement");
            edit.setId("open-as-arrangement");
            edit.setOnAction(event -> openArrangement.run());
            title.getChildren().add(edit);
        }
        title.getStyleClass().add("topbar");
        root.setTop(title);
        stage.show();
        loader.submit(() -> {
            try {
                var snapshot = ReconstructionEditor.Snapshot.read(work);
                Platform.runLater(() -> {
                    if (!closed) initialize(work, snapshot, title, openProject);
                });
            } catch (Exception failure) {
                Platform.runLater(() -> {
                    if (!closed) status.setText("Could not open reconstruction · " + failure.getMessage());
                });
            }
        });
    }

    Stage stage() { return stage; }

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(runnable -> Thread.ofPlatform().daemon().name(name).unstarted(runnable));
    }

    private void initialize(Path work, ReconstructionEditor.Snapshot snapshot, HBox title,
                            Supplier<ProjectDocument> openProject) {
        model = snapshot.inspection();
        view = new ReconstructionCanvas(model, status::setText);
        status.setId("reconstruction-status");
        editor = new ReconstructionEditor(work, stage, view, snapshot, status::setText, this::refresh, position -> {
            if (playing || playWhenLoaded) stopPlayback();
            updatePosition(position, true);
        }, openProject);
        stage.setOnCloseRequest(event -> { if (!editor.allowClose()) event.consume(); });
        view.onNote = selected -> {
            note = selected;
            editor.show(selected);
        };
        view.onLane = selected -> {
            lane = selected;
            refreshSolo();
        };
        view.onSeek = seconds -> {
            updatePosition(model.toProject(seconds), false);
            if (playing) startPlayback();
        };
        view.onRange = selected -> {
            range = selected;
            updateLoop();
        };
        sources.getItems().setAll(model.sources());
        sources.setId("reconstruction-source");
        sources.setPrefWidth(216);
        sources.setPromptText("No source audio");
        sources.setTooltip(new Tooltip("Press R to compare the selected reference with the chip render"));
        sources.setDisable(model.sources().isEmpty());
        sources.valueProperty().addListener((ignored, previous, next) -> choose(next));
        play.setId("reconstruction-play");
        play.setDisable(true);
        play.setOnAction(event -> togglePlayback());
        var playback = new FlowPane(8, 6, play, new Label("Source"), sources, soloLane, soloNote, clearRange, loopLabel);
        playback.getStyleClass().addAll("panelbar", "reconstruction-controls");
        playback.setAlignment(Pos.CENTER_LEFT);

        soloLane.setOnAction(event -> {
            soloNote.setSelected(false);
            soloChanged();
        });
        soloNote.setOnAction(event -> {
            soloLane.setSelected(false);
            if (soloNote.isSelected()) updatePosition(note.interval().projectStart(), false);
            soloChanged();
        });
        clearRange.setOnAction(event -> view.clearRange());
        clearRange.setDisable(true);

        var detail = new ToggleButton("Detail");
        detail.setId("reconstruction-detail-view");
        var roles = new ToggleButton("Roles");
        roles.setId("reconstruction-roles-view");
        var views = new ToggleGroup();
        detail.setToggleGroup(views);
        roles.setToggleGroup(views);
        views.selectToggle(detail);
        var roleControls = roleControls();
        views.selectedToggleProperty().addListener((ignored, old, next) -> {
            if (next == null) {
                views.selectToggle(old);
                return;
            }
            boolean showRoles = next == roles;
            view.rolesView(showRoles);
            editor.rolesView(showRoles);
            laneFilters.setVisible(!showRoles);
        });
        view.onDetailJump = notes -> {
            views.selectToggle(detail);
            zoom.setValue(view.detailZoom(notes));
            double start = notes.stream().mapToDouble(note -> note.interval().sourceStart()).min().orElse(0);
            double end = notes.stream().mapToDouble(note -> note.interval().sourceEnd()).max().orElse(start);
            view.center((start + end) / 2);
        };
        playback.getChildren().addAll(detail, roles);
        root.setTop(new VBox(title, playback, editor.toolbar(), displayControls(), roleControls));
        root.setRight(editor.inspector());
        root.setCenter(new BorderPane(view.scroll, null, view.pitchScroll, null, null));
        stage.getScene().addEventFilter(KeyEvent.KEY_PRESSED, this::key);
        updatePosition(0, false);
        refreshSolo();
        loadBends(model);
        if (model.sources().isEmpty()) status.setText("No recorded audio found · note trace is still available");
        else sources.getSelectionModel().selectFirst();
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
        var previousSource = sources.getValue();
        double sourcePosition = model.toSource(projectPosition);
        stopPlayback();
        model = next;
        if (waveRead != null) waveRead.cancel(true);
        loadedSamples = null;
        monitorSamples = null;
        loader.execute(waves::clear);
        view.refresh(next);
        refreshLaneFilters();
        sources.getItems().setAll(model.sources());
        sources.setDisable(model.sources().isEmpty());
        lastReference = model.sources().stream().filter(source -> source.equals(lastReference)).findFirst()
                .orElseGet(() -> model.sources().stream().filter(source -> source.kind() != AudioKind.CHIP)
                        .findFirst().orElse(null));
        var selected = model.sources().stream().filter(source -> source.equals(previousSource)).findFirst()
                .orElseGet(() -> model.sources().stream().findFirst().orElse(null));
        if (java.util.Objects.equals(sources.getValue(), selected)) choose(selected);
        else sources.setValue(selected);
        updatePosition(model.toProject(sourcePosition), false);
        refreshSolo();
        updateLoop();
        loadBends(next);
    }

    private FlowPane displayControls() {
        zoom.setId("reconstruction-zoom");
        zoom.setPrefWidth(160);
        zoom.setBlockIncrement(1);
        zoom.valueProperty().addListener((ignored, previous, next) -> view.zoom(next.doubleValue()));
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
        project.selectedProperty().addListener((ignored, previous, selected) -> view.projectRuler(selected));
        var row = new FlowPane(9, 6, new Label("Time zoom"), zoom, fit, pitch, new Label("Spectrum"), db, dbLabel,
                ridges, project);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("panelbar", "reconstruction-controls");
        return row;
    }

    private FlowPane roleControls() {
        var roles = new FlowPane(12, 6, new Label("Notes"));
        roles.setAlignment(Pos.CENTER_LEFT);
        for (var role : Role.values()) {
            String name = ReconstructionCanvas.roleName(role);
            var filter = new CheckBox();
            filter.setAccessibleText("Show " + name + " notes");
            filter.setTooltip(new Tooltip("Show " + name + " notes"));
            filter.setSelected(true);
            filter.selectedProperty().addListener((ignored, previous, visible) -> view.roleVisible(role, visible));
            var select = new Button(name);
            select.setId("reconstruction-role-" + role.name().toLowerCase(java.util.Locale.ROOT));
            select.setStyle("-fx-text-fill: #%06x".formatted(role.rgb()));
            select.setTooltip(new Tooltip("Select all " + name + " notes. Shift-click or Ctrl-click to add."));
            select.setOnAction(event -> view.selectRole(role, false));
            select.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() == MouseButton.PRIMARY
                        && (event.isShiftDown() || event.isControlDown() || event.isMetaDown())) {
                    view.selectRole(role, true);
                    event.consume();
                }
            });
            var controls = new HBox(2, filter, select);
            controls.setAlignment(Pos.CENTER_LEFT);
            roles.getChildren().add(controls);
        }
        laneFilters = new MenuButton("Lanes");
        refreshLaneFilters();
        var detail = editor.visibility();
        detail.visibleProperty().bind(laneFilters.visibleProperty());
        roles.getChildren().addAll(laneFilters, detail);
        roles.getStyleClass().addAll("panelbar", "reconstruction-controls");
        return roles;
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

    private void key(KeyEvent event) {
        if (stage.getScene().getFocusOwner() instanceof TextInputControl || event.isAltDown()) return;
        if (editor.key(event)) {
            event.consume();
            return;
        }
        if (event.isShortcutDown()) return;
        if (event.getCode() == KeyCode.SPACE && !(stage.getScene().getFocusOwner() instanceof javafx.scene.control.Control))
            togglePlayback();
        else if (event.getCode() == KeyCode.R) {
            var current = sources.getValue();
            if (current != null && current.kind() == AudioKind.CHIP) {
                if (lastReference != null) sources.setValue(lastReference);
            } else {
                chip().ifPresent(sources::setValue);
            }
        } else if (event.getCode() == KeyCode.ESCAPE && range != null) view.clearRange();
        else return;
        event.consume();
    }

    private void choose(AudioSource source) {
        long request = ++sourceEpoch;
        if (source == null) {
            view.source(null);
            play.setDisable(true);
            return;
        }
        if (source.kind() != AudioKind.CHIP) lastReference = source;
        view.source(source);
        loadedSamples = null;
        if (waveRead != null) waveRead.cancel(true);
        play.setDisable(false);
        status.setText("Loading " + source.label() + "…");
        waveRead = loader.submit(() -> {
            try {
                var wave = waves.get(source);
                if (wave == null) {
                    wave = ReferenceWave.read(source.path());
                    waves.put(source, wave);
                    if (waves.size() > 3) waves.pollFirstEntry();
                }
                var ready = wave;
                Platform.runLater(() -> {
                    if (closed || request != sourceEpoch) return;
                    loadedSamples = (seconds, channel) -> ready.sample(source.projectClock() ? seconds : model.toSource(seconds), channel);
                    updateMonitor();
                    status.setText(playing ? "Playing · " + source.label() : source.label() + " ready");
                    if (playWhenLoaded) startPlayback();
                });
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted() || failure instanceof java.io.InterruptedIOException) return;
                Platform.runLater(() -> {
                    if (closed || request != sourceEpoch) return;
                    stopPlayback();
                    status.setText("Playback unavailable · " + failure.getMessage());
                });
            }
        });
        updateMonitor();
    }

    private void updateMonitor() {
        if (sources.getValue().kind() == AudioKind.CHIP && soloActive()) monitorSamples = null;
        else if (loadedSamples != null) monitorSamples = loadedSamples;
    }

    private boolean soloActive() { return soloLane.isSelected() || soloNote.isSelected(); }

    private void refreshSolo() {
        boolean hasLane = model.project() != null && lane != null && chip().isPresent()
                && (note == null || !note.fate().startsWith("Pending"));
        boolean hadSolo = soloActive();
        soloLane.setDisable(!hasLane);
        soloNote.setDisable(!hasLane || note == null || !note.sounds());
        if (soloLane.isDisabled()) soloLane.setSelected(false);
        if (soloNote.isDisabled()) soloNote.setSelected(false);
        if (hadSolo) {
            updateLoop();
            if (sources.getValue() != null) updateMonitor();
        }
    }

    private Optional<AudioSource> chip() {
        return model.sources().stream().filter(source -> source.kind() == AudioKind.CHIP).findFirst();
    }

    private void soloChanged() {
        updateLoop();
        if (soloActive()) chip().ifPresent(sources::setValue);
        if (sources.getValue() != null) updateMonitor();
        if (playing) startPlayback();
    }

    private void updateLoop() {
        if (soloNote.isSelected()) {
            var time = note.interval();
            transport.loop = new ReviewTransport.Range(Math.max(0, time.projectStart()), time.projectEnd());
            loopLabel.setText("Looping selected note");
        } else {
            transport.loop = range == null ? null
                    : new ReviewTransport.Range(model.toProject(range.start()), model.toProject(range.end()));
            loopLabel.setText(range == null ? "Shift-drag to loop" : String.format(Locale.ROOT,
                    "%.2f–%.2f s loop", range.start(), range.end()));
        }
        clearRange.setDisable(range == null);
    }

    private void updatePosition(double seconds, boolean follow) {
        projectPosition = Math.clamp(seconds, 0, model.projectDuration());
        double source = model.toSource(projectPosition);
        view.movePlayhead(source, follow);
        clock.setText(String.format(Locale.ROOT, "%.2f s source  /  %.2f s project", source, projectPosition));
    }

    private void togglePlayback() {
        if (sources.getValue() == null) return;
        if (playing || playWhenLoaded) stopPlayback();
        else startPlayback();
    }

    private void startPlayback() {
        if (editor != null) editor.stopAudition();
        if (loadedSamples == null && !(soloActive() && sources.getValue().kind() == AudioKind.CHIP)) {
            playWhenLoaded = true;
            play.setText("Cancel");
            return;
        }
        playWhenLoaded = false;
        long request = ++epoch;
        playing = true;
        play.setText("Stop");
        devicePosition = Double.NaN;
        double start = projectPosition;
        var project = soloActive() ? model.project() : null;
        var mask = ReconstructionView.laneMask(project == null ? List.of() : List.of(lane), List.of());
        audioControl.execute(() -> {
            if (request != epoch) return;
            transport.seconds = start;
            player.playMonitor(project, transport, mask, model.projectDuration(), () -> monitorSamples,
                    message -> Platform.runLater(() -> {
                        if (closed || request != epoch) return;
                        status.setText(message);
                        if (!message.startsWith("Playing")) {
                            if (Double.isFinite(devicePosition)) updatePosition(devicePosition, false);
                            playbackStopped();
                        }
                    }), position -> {
                        if (request == epoch) devicePosition = position;
                    });
        });
        playheadTimer.start();
    }

    private void stopPlayback() {
        epoch++;
        audioControl.execute(player::stop);
        playbackStopped();
    }

    private void playbackStopped() {
        playing = false;
        playWhenLoaded = false;
        play.setText("Play");
        playheadTimer.stop();
    }

    private static Region spacer() {
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        epoch++;
        playheadTimer.stop();
        if (editor != null) editor.close();
        if (view != null) view.close();
        loader.shutdownNow();
        bendLoader.shutdownNow();
        audioControl.execute(player::close);
        audioControl.shutdown();
        stage.hide();
    }
}
