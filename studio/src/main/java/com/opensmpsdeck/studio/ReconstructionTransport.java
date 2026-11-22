package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionEditing;
import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioKind;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioSource;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionSections.Section;
import com.opensmpsdeck.headless.ReconstructionView;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.project.StudioProject;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/** Audition controls and aligned playback shared by all reconstruction workspaces. */
final class ReconstructionTransport implements AutoCloseable {
    enum Scope { Song, Section, Selection, Voice, Lane }
    enum Hearing {
        ORIGINAL("Original"), CHIP("Chip"), AB("A/B");

        private final String label;
        Hearing(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    private final Stage stage;
    private final Path work;
    private final ReconstructionCanvas view;
    private final Label status;
    private final Runnable stopAudition;
    private final ExecutorService loader = worker("reconstruction-audio-load");
    private final Label clock = new Label();
    private final StudioPlayback player = new StudioPlayback();
    private final ReviewTransport transport = new ReviewTransport();
    private final ExecutorService audioControl = worker("reconstruction-audio-control");
    // Only the loader touches this cache. Playback holds the current immutable waveform.
    private final LinkedHashMap<AudioSource, ReferenceWave> waves = new LinkedHashMap<>(4, .75f, true);
    private final ComboBox<AudioSource> sources = new ComboBox<>();
    private final ComboBox<Scope> scopes = new ComboBox<>();
    private final ComboBox<Hearing> hearing = new ComboBox<>();
    private final ComboBox<String> voices = new ComboBox<>();
    private final ComboBox<String> lanes = new ComboBox<>();
    private final Button play = new Button("Play");
    private final Button setLoop = new Button("Set loop…");
    private final Button clearRange = new Button("Clear loop");
    private final ToggleButton abSide = new ToggleButton("A · Original");
    private final Label scopeHint = new Label();
    private final Label loopLabel = new Label();
    private final AnimationTimer playheadTimer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (playing && Double.isFinite(devicePosition) && devicePosition != projectPosition)
                updatePosition(devicePosition, true);
        }
    };

    private ReconstructionInspection model;
    private long sourceEpoch;
    private ReviewTransport.Range range;
    private Section section;
    private boolean projectTime;
    private final Tooltip clockHint = new Tooltip("Source time");
    private AudioSource referenceSource;
    private boolean updatingTargets;
    private boolean controlsReady;
    private String selectedLane;
    private String voiceAnchorNote;
    private Future<?> waveRead;
    private Future<?> voiceRender;
    private AudioSource requestedOriginal;
    private AudioSource requestedChip;
    private StudioPlayback.Samples originalSamples;
    private StudioPlayback.Samples chipSamples;
    private volatile StudioPlayback.Samples monitorSamples;
    private volatile double devicePosition = Double.NaN;
    private double projectPosition;
    private volatile long epoch;
    private boolean playing;
    private boolean playWhenLoaded;
    private boolean closed;

    ReconstructionTransport(Stage stage, Path work, ReconstructionCanvas view,
                            ReconstructionInspection model, Label status, Runnable stopAudition) {
        this.stage = stage;
        this.work = work;
        this.view = view;
        this.model = model;
        this.status = status;
        this.stopAudition = stopAudition;
        view.onLane = lane -> {
            selectedLane = lane;
            if (controlsReady && lane != null && lanes.getItems().contains(lane)) lanes.setValue(lane);
        };
        view.onSeek = seconds -> {
            updatePosition(this.model.toProject(seconds), false);
            if (playing) startPlayback();
        };
        view.onSection = band -> {
            section = band;
            updatePosition(this.model.toProject(band.sourceStart()), false);
            if (scopes.getValue() == Scope.Section) transportChanged();
            else scopes.setValue(Scope.Section);
        };
        view.onSectionsChanged = bands -> {
            var previous = section;
            reconcileSection(bands);
            if (!Objects.equals(previous, section) && scopes.getValue() == Scope.Section) transportChanged();
        };
        view.onRange = selected -> {
            range = selected;
            transportChanged();
        };
        var scene = stage.getScene();
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == KeyCode.SPACE && spaceAvailable()) {
                if (view.releaseSpace()) togglePlayback();
                event.consume();
            }
        });
        scene.focusOwnerProperty().addListener((ignored, old, next) -> view.releaseSpace());
        stage.focusedProperty().addListener((ignored, old, focused) -> {
            if (!focused) view.cancelNavigation();
        });
    }

    void initialize() {
        controlsReady = true;
        refreshSources();
        refreshTargets();
        updatePosition(0, false);
        prepareAudio();
    }

    void refresh(ReconstructionInspection next) {
        double sourcePosition = model.toSource(projectPosition);
        stopPlayback();
        model = next;
        if (waveRead != null) waveRead.cancel(true);
        requestedOriginal = null;
        requestedChip = null;
        originalSamples = null;
        chipSamples = null;
        monitorSamples = null;
        loader.execute(waves::clear);
        view.refresh(next);
        reconcileSection(next.sections().sections());
        refreshSources();
        refreshTargets();
        prepareAudio();
        updatePosition(model.toProject(sourcePosition), false);
    }

    private void reconcileSection(List<Section> sections) {
        if (section == null) return;
        String id = section.id();
        section = sections.stream().filter(band -> band.id().equals(id)).findFirst().orElse(null);
    }

    void cuePreview(double position) {
        if (playing || playWhenLoaded) stopPlayback();
        updatePosition(position, true);
    }

    void projectTime(boolean selected) {
        projectTime = selected;
        clockHint.setText(selected ? "Project time" : "Source time");
        view.projectRuler(selected);
        updateClock();
    }

    ComboBox<AudioSource> spectrumSource() {
        sources.setId("reconstruction-source");
        sources.setPrefWidth(190);
        sources.setTooltip(new Tooltip("Show a mix, stem or chip spectrum. Choosing a mix or stem also sets the Original reference."));
        sources.valueProperty().addListener((ignored, previous, next) -> {
            if (updatingTargets) return;
            view.source(next);
            if (next != null && next.kind() != AudioKind.CHIP) {
                referenceSource = next;
                transportChanged();
            }
        });
        return sources;
    }

    VBox bar() {
        play.setId("reconstruction-play");
        play.setTooltip(new Tooltip("Play or stop · Space"));
        play.setOnAction(event -> togglePlayback());
        play.getStyleClass().add("primary-action");
        play.setMinWidth(70);
        scopes.setId("reconstruction-play-scope");
        scopes.getItems().setAll(Scope.values());
        scopes.setValue(Scope.Song);
        scopes.setTooltip(new Tooltip("Choose which part of the reconstruction to play"));
        hearing.setId("reconstruction-hearing");
        hearing.getItems().setAll(Hearing.values());
        hearing.setValue(Hearing.ORIGINAL);
        hearing.setTooltip(new Tooltip("Choose original audio, the chip result, or switch between A and B"));
        abSide.setId("reconstruction-ab-side");
        abSide.setTooltip(new Tooltip("Switch between Original and Chip at the current playhead · R"));
        abSide.setOnAction(event -> updateMonitor());
        abSide.managedProperty().bind(abSide.visibleProperty());
        abSide.setVisible(false);
        voices.setId("reconstruction-play-voice");
        voices.setPrefWidth(150);
        voices.setPromptText("Choose voice");
        voices.setConverter(new StringConverter<>() {
            @Override public String toString(String id) {
                return view.voices().entries().stream().filter(entry -> entry.id().equals(id))
                        .map(ReconstructionVoices.Entry::shortName).findFirst().orElse("");
            }
            @Override public String fromString(String text) { return null; }
        });
        lanes.setId("reconstruction-play-lane");
        lanes.setPromptText("Choose lane");
        for (var picker : List.of(voices, lanes)) {
            picker.managedProperty().bind(picker.visibleProperty());
            picker.setVisible(false);
            picker.valueProperty().addListener((ignored, old, next) -> {
                if (!updatingTargets && picker.isVisible()) transportChanged();
            });
        }
        scopes.valueProperty().addListener((ignored, old, next) -> {
            voices.setVisible(next == Scope.Voice);
            lanes.setVisible(next == Scope.Lane);
            var span = span();
            if (span != null && next != Scope.Song) updatePosition(model.toProject(span.start()), false);
            transportChanged();
        });
        hearing.valueProperty().addListener((ignored, old, next) -> {
            abSide.setVisible(next == Hearing.AB);
            if (next == Hearing.AB) abSide.setSelected(false);
            transportChanged();
        });
        setLoop.setId("reconstruction-set-loop");
        setLoop.setOnAction(event -> editLoop());
        clearRange.setId("reconstruction-clear-loop");
        clearRange.setOnAction(event -> clearLoop());
        clearRange.managedProperty().bind(clearRange.visibleProperty());
        loopLabel.setId("reconstruction-loop");
        scopeHint.setId("reconstruction-scope-hint");
        scopeHint.getStyleClass().add("muted");
        clock.setId("reconstruction-clock");
        clock.setTooltip(clockHint);
        clock.setMinWidth(78);
        clock.getStyleClass().add("reconstruction-clock");
        var controls = new FlowPane(8, 6, play, new Label("Play range"), scopes, voices, lanes,
                new Label("Hear"), hearing, abSide, setLoop, loopLabel, clearRange);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(controls, Priority.ALWAYS);
        var transportRow = new HBox(12, controls, clock);
        transportRow.setAlignment(Pos.CENTER_LEFT);
        var hintRow = new HBox(10, scopeHint, spacer(), status);
        status.setMinWidth(0);
        scopeHint.setMinWidth(0);
        var footer = new VBox(5, transportRow, hintRow);
        footer.getStyleClass().add("reconstruction-transport");
        return footer;
    }

    private void refreshSources() {
        updatingTargets = true;
        var previous = sources.getValue();
        sources.getItems().setAll(model.sources());
        if (!sources.getItems().contains(referenceSource)) referenceSource = model.sources().stream()
                .filter(source -> source.kind() != AudioKind.CHIP).findFirst().orElse(null);
        if (sources.getItems().contains(previous)) sources.setValue(previous);
        else sources.setValue(referenceSource == null ? chip() : referenceSource);
        view.source(sources.getValue());
        sources.setDisable(sources.getItems().isEmpty());
        if (referenceSource == null && hearing.getValue() == Hearing.ORIGINAL) hearing.setValue(Hearing.CHIP);
        updatingTargets = false;
    }

    void refreshTargets() {
        if (!controlsReady) return;
        updatingTargets = true;
        String previousVoice = voices.getValue();
        var audibleIds = model.overlays().stream().filter(Overlay::sounds).map(Overlay::id).collect(Collectors.toSet());
        var voiceIds = view.voices().entries().stream().filter(entry -> !entry.id().isEmpty()
                && entry.notes().stream().anyMatch(note -> audibleIds.contains(note.id()))).map(ReconstructionVoices.Entry::id).toList();
        voices.getItems().setAll(voiceIds);
        var owner = voiceAnchorNote == null ? null : view.voices().byNote().get(voiceAnchorNote);
        if (voiceIds.contains(previousVoice)) voices.setValue(previousVoice);
        else if (owner != null && voiceIds.contains(owner.id())) voices.setValue(owner.id());
        else voices.getSelectionModel().clearSelection();
        String previousLane = lanes.getValue();
        lanes.getItems().setAll(view.lanes());
        if (lanes.getItems().contains(selectedLane)) lanes.setValue(selectedLane);
        else if (lanes.getItems().contains(previousLane)) lanes.setValue(previousLane);
        else lanes.getSelectionModel().clearSelection();
        updatingTargets = false;
        if (scopes.getValue() == Scope.Voice || scopes.getValue() == Scope.Lane) transportChanged();
        else updateTransport();
    }

    void selectionChanged() {
        if (!controlsReady) return;
        var selection = view.selectedNotes();
        voiceAnchorNote = selection.size() == 1 ? selection.getFirst().id() : null;
        var owner = voiceAnchorNote == null ? null : view.voices().byNote().get(voiceAnchorNote);
        if (owner != null && voices.getItems().contains(owner.id())) voices.setValue(owner.id());
        if (scopes.getValue() == Scope.Selection) transportChanged();
        else updateTransport();
    }

    private boolean spaceAvailable() {
        var focus = stage.getScene().getFocusOwner();
        return !(focus instanceof TextInputControl)
                && !(focus instanceof ComboBox<?> combo && combo.isShowing());
    }

    boolean key(KeyEvent event) {
        if (!controlsReady || event.isShortcutDown()) return false;
        if (event.getCode() == KeyCode.SPACE && spaceAvailable()) view.holdSpace();
        else if (event.getCode() == KeyCode.R) {
            if (hearing.getValue() == Hearing.AB) abSide.fire();
            else hearing.setValue(hearing.getValue() == Hearing.CHIP ? Hearing.ORIGINAL : Hearing.CHIP);
        } else if (event.getCode() == KeyCode.ESCAPE && range != null) clearLoop();
        else return false;
        return true;
    }

    private AudioSource chip() {
        return model.sources().stream().filter(source -> source.kind() == AudioKind.CHIP).findFirst().orElse(null);
    }

    private AudioSource original() {
        var choice = referenceSource;
        if (scopes.getValue() != Scope.Voice) return choice;
        var parts = voiceNotes().stream().map(Overlay::part).distinct().toList();
        if (parts.size() != 1) return choice;
        String stem = "Stem " + parts.getFirst();
        return sources.getItems().stream().filter(source -> source.kind() == AudioKind.STEM
                && (source.label().equals(stem) || source.label().startsWith(stem + " · "))).findFirst().orElse(choice);
    }

    private List<Overlay> voiceNotes() {
        String id = voices.getValue();
        if (id == null) return List.of();
        var membership = view.voices().byNote();
        return model.overlays().stream().filter(Overlay::sounds).filter(note -> {
            var voice = membership.get(note.id());
            return voice != null && id.equals(voice.id());
        }).toList();
    }

    private ReviewTransport.Range span() {
        return switch (scopes.getValue()) {
            case Song -> sourceRange(0, model.duration());
            case Lane -> lanes.getValue() == null ? null : sourceRange(0, model.duration());
            case Section -> section == null ? null : sourceRange(section.sourceStart(), section.sourceEnd());
            case Voice -> span(voiceNotes());
            case Selection -> span(view.selectedNotes());
        };
    }

    private ReviewTransport.Range span(List<Overlay> notes) {
        double start = notes.stream().mapToDouble(note -> note.interval().sourceStart()).min().orElse(0);
        double end = notes.stream().mapToDouble(note -> note.interval().sourceEnd()).max().orElse(0);
        return sourceRange(start, end);
    }

    private ReviewTransport.Range sourceRange(double start, double end) {
        start = Math.clamp(start, 0, model.duration());
        end = Math.clamp(end, 0, model.duration());
        return end > start ? new ReviewTransport.Range(start, end) : null;
    }

    private ReviewTransport.Range projectRange(ReviewTransport.Range span) {
        if (span == null) return null;
        double start = model.toProject(span.start()), end = model.toProject(span.end());
        return end > start ? new ReviewTransport.Range(start, end) : null;
    }

    private ReviewTransport.Range effectiveLoop(ReviewTransport.Range span) {
        if (span == null || range == null) return null;
        return sourceRange(Math.max(span.start(), range.start()), Math.min(span.end(), range.end()));
    }

    private static String bounds(ReviewTransport.Range span) {
        return String.format(Locale.ROOT, "%.2f–%.2f s", span.start(), span.end());
    }

    private void editLoop() {
        var span = span();
        if (span == null) return;
        var active = effectiveLoop(span);
        var initial = active == null ? span : active;
        var start = new TextField(String.format(Locale.ROOT, "%.3f", initial.start()));
        var end = new TextField(String.format(Locale.ROOT, "%.3f", initial.end()));
        var error = new Label();
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(stage);
        dialog.setTitle("Set loop range");
        dialog.setHeaderText("Source seconds within " + bounds(span));
        dialog.getDialogPane().getStylesheets().addAll(stage.getScene().getStylesheets());
        dialog.getDialogPane().setContent(new VBox(6, new Label("Start"), start, new Label("End"), end, error));
        var confirm = new ButtonType("Set loop", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(confirm, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(confirm).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                double first = Double.parseDouble(start.getText().strip());
                double last = Double.parseDouble(end.getText().strip());
                if (first < span.start() - .0005 || last > span.end() + .0005
                        || !Double.isFinite(first) || !Double.isFinite(last) || first >= last)
                    throw new NumberFormatException();
                range = sourceRange(Math.max(first, span.start()), Math.min(last, span.end()));
                if (range == null) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                error.setText("Enter a start and end within the play range.");
                event.consume();
            }
        });
        if (dialog.showAndWait().filter(confirm::equals).isPresent()) transportChanged();
    }

    private void clearLoop() {
        range = null;
        transportChanged();
    }

    private boolean liveChip() { return scopes.getValue() == Scope.Lane || scopes.getValue() == Scope.Voice; }

    private void updateTransport() {
        if (!controlsReady || updatingTargets) return;
        var span = span();
        var activeLoop = effectiveLoop(span);
        view.listeningSection(scopes.getValue() == Scope.Section ? section : null);
        view.loopRange(activeLoop);
        transport.loop = projectRange(activeLoop);
        String description = switch (scopes.getValue()) {
            case Song -> "Click a section, period or phrase band to play it.";
            case Section -> span == null ? "Click a section, period or phrase band." :
                    section.level().name().substring(0, 1).toUpperCase(Locale.ROOT)
                            + section.level().name().substring(1) + " " + section.label() + " · " + bounds(span);
            case Selection -> span == null ? "Select notes in Roles or Notes." : "Selected time · " + bounds(span);
            case Voice -> span == null ? "Choose a voice to hear its built notes."
                    : "Built voice · " + bounds(span);
            case Lane -> span == null ? "Choose a chip lane." : "Chip lane " + lanes.getValue() + " · " + bounds(span);
        };
        var original = original();
        if (span != null && hearing.getValue() != Hearing.CHIP && original != null && liveChip()) {
            description += original.kind() == AudioKind.STEM ? " · Original uses " + original.label()
                    : " · Original uses the mix";
        }
        boolean hasOriginal = original != null;
        boolean hasChip = liveChip() ? model.project() != null : chip() != null;
        String unavailable = switch (hearing.getValue()) {
            case ORIGINAL -> hasOriginal ? "" : "Original audio unavailable";
            case CHIP -> hasChip ? "" : "Chip audio unavailable";
            case AB -> hasOriginal && hasChip ? "" : "A/B needs Original and Chip audio";
        };
        scopeHint.setText(unavailable.isEmpty() ? description : description + "  " + unavailable);
        play.setDisable(span == null || !unavailable.isEmpty());
        setLoop.setDisable(span == null);
        clearRange.setVisible(range != null);
        loopLabel.setText(activeLoop != null ? "Loop " + bounds(activeLoop) : range == null ? "" : "Loop outside play range");
        loopLabel.setVisible(range != null);
        loopLabel.setManaged(range != null);
    }

    private void transportChanged() {
        if (!controlsReady || updatingTargets) return;
        boolean wasPlaying = playing || playWhenLoaded;
        if (wasPlaying) stopPlayback();
        updateTransport();
        prepareAudio();
        if (wasPlaying && !play.isDisabled()) startPlayback();
    }

    private void prepareAudio() {
        var original = hearing.getValue() == Hearing.CHIP ? null : original();
        var chip = hearing.getValue() == Hearing.ORIGINAL || liveChip() ? null : chip();
        if (Objects.equals(original, requestedOriginal) && Objects.equals(chip, requestedChip)
                && (waveRead != null || original == null && chip == null)) return;
        requestedOriginal = original;
        requestedChip = chip;
        long request = ++sourceEpoch;
        if (waveRead != null) waveRead.cancel(true);
        originalSamples = null;
        chipSamples = null;
        if (original == null && chip == null) {
            waveRead = null;
            status.setText(play.isDisabled() ? "Audio unavailable" : "Chip ready");
            updateMonitor();
            return;
        }
        status.setText("Loading audio…");
        var inspection = model;
        waveRead = loader.submit(() -> {
            try {
                var reference = samples(original, inspection);
                var rendered = samples(chip, inspection);
                Platform.runLater(() -> {
                    if (closed || request != sourceEpoch) return;
                    originalSamples = reference;
                    chipSamples = rendered;
                    updateMonitor();
                    status.setText((original == null ? chip.label() : original.label()) + " ready");
                    if (playWhenLoaded) startPlayback();
                });
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted() || failure instanceof java.io.InterruptedIOException) return;
                Platform.runLater(() -> {
                    if (closed || request != sourceEpoch) return;
                    waveRead = null;
                    stopPlayback();
                    status.setText("Playback unavailable · " + failure.getMessage());
                });
            }
        });
    }

    private StudioPlayback.Samples samples(AudioSource source, ReconstructionInspection inspection) throws java.io.IOException {
        if (source == null) return null;
        var wave = waves.get(source);
        if (wave == null) {
            wave = ReferenceWave.read(source.path());
            waves.put(source, wave);
            if (waves.size() > 3) waves.pollFirstEntry();
        }
        var ready = wave;
        return (seconds, channel) -> ready.sample(source.projectClock() ? seconds : inspection.toSource(seconds), channel);
    }

    private void updateMonitor() {
        boolean reference = hearing.getValue() == Hearing.ORIGINAL
                || hearing.getValue() == Hearing.AB && !abSide.isSelected();
        monitorSamples = reference ? originalSamples : chipSamples;
        abSide.setText(reference ? "A · Original" : "B · Chip");
    }

    private void updatePosition(double seconds, boolean follow) {
        projectPosition = Math.clamp(seconds, 0, model.projectDuration());
        view.movePlayhead(model.toSource(projectPosition), follow);
        updateClock();
    }

    private void updateClock() {
        double seconds = projectTime ? projectPosition : model.toSource(projectPosition);
        long hundredths = Math.round(seconds * 100);
        clock.setText(String.format(Locale.ROOT, "%d:%02d.%02d", hundredths / 6000, hundredths / 100 % 60, hundredths % 100));
    }

    private void togglePlayback() {
        if (play.isDisabled()) return;
        if (playing || playWhenLoaded) stopPlayback();
        else {
            prepareAudio();
            startPlayback();
        }
    }

    private void startPlayback() {
        stopAudition.run();
        var sourceSpan = span();
        var span = projectRange(sourceSpan);
        if (span == null || play.isDisabled()) return;
        if (requestedOriginal != null && originalSamples == null || requestedChip != null && chipSamples == null) {
            playWhenLoaded = true;
            play.setText("Cancel");
            return;
        }
        if (voiceRender != null) voiceRender.cancel(true);
        playWhenLoaded = false;
        long request = ++epoch;
        playing = true;
        play.setText("Stop");
        devicePosition = Double.NaN;
        double start = projectPosition;
        if (start < span.start() || start >= span.end()) start = span.start();
        var active = effectiveLoop(sourceSpan);
        if (active != null) {
            var loop = projectRange(active);
            if (loop != null && (start < loop.start() || start >= loop.end())) start = loop.start();
        }
        boolean chipAudible = hearing.getValue() != Hearing.ORIGINAL;
        var project = chipAudible && liveChip() ? model.project() : null;
        var mask = ReconstructionView.laneMask(project == null || scopes.getValue() != Scope.Lane
                ? List.of() : List.of(lanes.getValue()), List.of());
        if (chipAudible && scopes.getValue() == Scope.Voice) {
            var ids = voiceNotes().stream().map(Overlay::id).collect(Collectors.toSet());
            double from = start;
            status.setText("Preparing voice playback…");
            voiceRender = loader.submit(() -> {
                try {
                    var voice = ReconstructionEditing.audition(work, ids);
                    Platform.runLater(() -> {
                        if (!closed && request == epoch) playAudio(request, voice.project(), mask, span, from);
                    });
                } catch (Exception failure) {
                    Platform.runLater(() -> {
                        if (closed || request != epoch) return;
                        playbackStopped();
                        status.setText("Voice playback unavailable · " + failure.getMessage());
                    });
                }
            });
        } else playAudio(request, project, mask, span, start);
    }

    private void playAudio(long request, StudioProject project,
                           S3kRenderer.ChannelMask mask,
                           ReviewTransport.Range span, double start) {
        audioControl.execute(() -> {
            if (request != epoch) return;
            transport.seconds = start;
            player.playMonitor(project, transport, mask, span.end(), () -> monitorSamples,
                    message -> Platform.runLater(() -> {
                        if (closed || request != epoch) return;
                        status.setText(message);
                        if (!message.startsWith("Playing")) {
                            if (Double.isFinite(devicePosition)) updatePosition(devicePosition, false);
                            playbackStopped();
                        }
                    }), position -> {
                        if (request == epoch) devicePosition = position;
                    }, span);
        });
        playheadTimer.start();
    }

    private void stopPlayback() {
        epoch++;
        if (voiceRender != null) voiceRender.cancel(true);
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

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(runnable -> Thread.ofPlatform().daemon().name(name).unstarted(runnable));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        epoch++;
        playheadTimer.stop();
        loader.shutdownNow();
        audioControl.execute(player::close);
        audioControl.shutdown();
    }
}
