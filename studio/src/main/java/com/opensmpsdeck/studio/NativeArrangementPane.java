package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.NativeArrangement;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Read-only view of the native player's presentation-time trace. */
final class NativeArrangementPane extends VBox implements AutoCloseable {
    final TimelineView timeline = new TimelineView(true);
    final PianoRollView piano = new PianoRollView(true);

    private final Button editableCopy = new Button("Save editable copy…");
    private final Label message = new Label();
    private final Label detail = new Label();
    private final ListView<SampleTrigger> hits = new ListView<>();
    private final VBox events = new VBox(detail);
    private final ResponsiveEditors editors;
    private final ThreadPoolExecutor projectionWorker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), work -> {
                var thread = new Thread(work, "studio-native-arrangement");
                thread.setDaemon(true);
                return thread;
            });
    private Map<String, String> sampleNames = Map.of();
    private StudioProject requested;
    private Future<?> pending;
    private long generation;

    NativeArrangementPane(Runnable openBank, Runnable saveEditableCopy) {
        setId("native-arrangement");
        message.getStyleClass().add("muted");
        message.setWrapText(true);
        var bank = new Button("Edit in Native bank");
        bank.setOnAction(e -> openBank.run());
        bank.setMinWidth(Region.USE_PREF_SIZE);
        editableCopy.setId("save-editable-copy");
        editableCopy.setOnAction(e -> saveEditableCopy.run());
        editableCopy.setMinWidth(Region.USE_PREF_SIZE);
        String copyHelp = "Rebuilds the finite preview as editable notes, DAC hits and selected instruments. "
                + "The original keeps its native effects. Playback in the copy may differ.";
        var help = new Tooltip(copyHelp);
        help.setWrapText(true);
        help.setMaxWidth(340);
        editableCopy.setTooltip(help);
        editableCopy.setAccessibleHelp(copyHelp);
        var actions = new FlowPane(10, 6, bank, editableCopy, message);
        actions.getStyleClass().add("panelbar");
        message.maxWidthProperty().bind(Bindings.createDoubleBinding(() -> Math.max(0,
                actions.getWidth() - actions.getInsets().getLeft() - actions.getInsets().getRight()),
                actions.widthProperty(), actions.insetsProperty()));
        var zoom = new Slider(0.5, 8, 2);
        timeline.scale = zoom.getValue();
        piano.scale = 4;
        zoom.valueProperty().addListener((o, a, b) -> {
            timeline.scale = b.doubleValue();
            timeline.setProject(timeline.project, timeline.selected);
        });
        var ruler = new HBox(10, new Label("Zoom"), zoom);
        ruler.getStyleClass().add("panelbar");
        var arrangement = new VBox(ruler, timeline.scroll);
        VBox.setVgrow(timeline.scroll, Priority.ALWAYS);
        detail.getStyleClass().add("panelbar");
        hits.setId("native-dac-hits");
        hits.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(SampleTrigger hit, boolean empty) {
                super.updateItem(hit, empty);
                setText(empty || hit == null ? null : time(hit.tick()) + "   " +
                        sampleNames.getOrDefault(hit.sampleId(), hit.sampleId()));
            }
        });
        VBox.setVgrow(piano.scroll, Priority.ALWAYS);
        VBox.setVgrow(hits, Priority.ALWAYS);
        editors = new ResponsiveEditors(arrangement, events);
        timeline.selection = this::select;
        timeline.openNotes = editors::showNotes;
        piano.selection = noteId -> updateDetail(sequence());
        getChildren().setAll(actions, editors);
        VBox.setVgrow(editors, Priority.ALWAYS);
        select(null);
    }

    void setSavingEditableCopy(boolean saving) {
        editableCopy.setDisable(saving);
    }

    void setCompact(boolean compact) {
        editors.setCompact(compact);
    }

    void setProject(StudioProject source) {
        if (Objects.equals(requested, source)) return;
        requested = source;
        long request = ++generation;
        cancelPending();

        String previousSelection = timeline.selected;
        timeline.setProject(null, previousSelection);
        select(previousSelection);
        if (source == null) {
            timeline.selected = null;
            return;
        }

        message.setText("Reading native preview…");
        pending = projectionWorker.submit(() -> {
            try {
                var projection = NativeArrangement.project(source);
                Platform.runLater(() -> {
                    if (request != generation) return;
                    showProjection(projection);
                });
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> {
                    if (request == generation)
                        message.setText("Arrangement unavailable. " + failure.getMessage());
                });
            }
        });
    }

    private void showProjection(StudioProject projection) {
        String clipId = timeline.selected;
        if (projection.clips().stream().noneMatch(c -> c.id().equals(timeline.selected)))
            clipId = projection.clips().isEmpty() ? null : projection.clips().getFirst().id();
        sampleNames = projection.samples().stream().collect(Collectors.toMap(DacSample::id, DacSample::name));
        timeline.setProject(projection, clipId);
        select(clipId);
        message.setText(projection.clips().isEmpty() ? "No notes or DAC hits in the preview." :
                "Read-only playback preview · select a clip to inspect its events");
    }

    void setPlayhead(double tick) {
        timeline.playhead = tick;
        timeline.draw();
    }

    private void select(String id) {
        timeline.selected = id;
        Sequence source = sequence();
        boolean dac = source != null && source.type().equals("DAC");
        piano.setSequence(dac ? null : source, null, 60);
        hits.getItems().setAll(dac ? source.triggers() : java.util.List.of());
        hits.refresh();
        events.getChildren().setAll(detail, dac ? hits : piano.scroll);
        updateDetail(source);
    }

    private void updateDetail(Sequence source) {
        if (source == null) {
            detail.setText("Select a clip to inspect its events");
            return;
        }
        String summary = source.name() + " · " + time(source.lengthTicks());
        if (source.type().equals("DAC")) {
            detail.setText(summary + " · DAC hits");
        } else {
            var note = source.notes().stream().filter(n -> n.id().equals(piano.selected)).findFirst();
            detail.setText(note.map(n -> summary + " · " + PianoRollView.noteName(n.pitch())
                    + " at " + time(n.tick()) + " · " + time(n.duration()) + " long").orElse(summary));
        }
    }

    private Sequence sequence() {
        var project = timeline.project;
        if (project == null || timeline.selected == null) return null;
        return project.clips().stream()
                .filter(c -> c.id().equals(timeline.selected))
                .map(Clip::sequenceId)
                .findFirst()
                .flatMap(id -> project.sequences().stream().filter(s -> s.id().equals(id)).findFirst())
                .orElse(null);
    }

    private static String time(int ticks) {
        return String.format(Locale.ROOT, "%.2f s", ticks / 60.0);
    }

    private void cancelPending() {
        if (pending != null) pending.cancel(true);
        projectionWorker.purge();
    }

    @Override public void close() {
        generation++;
        cancelPending();
        projectionWorker.shutdownNow();
    }
}
