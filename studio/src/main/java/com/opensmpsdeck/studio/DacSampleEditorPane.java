package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.StudioProject;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** DAC audio assets with an explicit draft for name, rate and replacement audio. */
final class DacSampleEditorPane extends BorderPane {
    private static final int WAVE_WIDTH = 240;
    private static final int WAVE_HEIGHT = 76;
    private static final Color WAVE_BACKGROUND = Color.web("#101722");
    private static final Color WAVE_AXIS = Color.web("#29384a");
    private static final Color WAVE_LINE = Color.web("#e7ab69");

    private final Consumer<UnaryOperator<StudioProject>> edit;
    private final Consumer<String> error;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "studio-dac-import");
        thread.setDaemon(true);
        return thread;
    });
    private final ListView<DacSample> list = new ListView<>();
    private final TextField name = new TextField();
    private final TextField rate = new TextField();
    private final Canvas waveform = new Canvas(WAVE_WIDTH, WAVE_HEIGHT);
    private final Label usage = label("", "muted");
    private final Label rateHz = label("", "muted");
    private final Label audioSize = label("", "muted");
    private final Label notice = label("", "muted");
    private final VBox form = new VBox(8);
    private final Button importButton = button("Import WAV / PCM…", "import-sample", () -> chooseAudio(false));
    private final Button replace = button("Replace audio…", "replace-sample-audio", () -> chooseAudio(true));
    private final Button duplicate = button("Duplicate", "duplicate-sample", this::duplicate);
    private final Button delete = button("Delete unused", "delete-sample", this::deleteSelected);
    private final Button apply = button("Apply sample", "apply-sample", this::apply);
    private final Button reload = button("Reload sample", "reload-sample", this::reload);
    private StudioProject project;
    private DacSample baseline;
    private DacSample replacementAudio;
    private long documentGeneration;
    private boolean updating;
    private boolean reading;
    private boolean closed;

    DacSampleEditorPane(Consumer<UnaryOperator<StudioProject>> edit, Consumer<String> error) {
        this.edit = Objects.requireNonNull(edit);
        this.error = Objects.requireNonNull(error);
        list.setId("sample-list");
        list.setFixedCellSize(31);
        list.prefHeightProperty().bind(Bindings.size(list.getItems()).map(count ->
                Math.clamp(count.intValue(), 1, 4) * list.getFixedCellSize() + 4));
        list.setMinHeight(Region.USE_PREF_SIZE);
        list.setPlaceholder(label("No samples", "muted"));
        list.setCellFactory(view -> new ListCell<>() {
            {
                // Allow long names to ellipsize inside the inventory column.
                setPrefWidth(0);
            }
            @Override protected void updateItem(DacSample sample, boolean empty) {
                super.updateItem(sample, empty);
                boolean filled = !empty && sample != null;
                setText(filled ? sample.name() : null);
                setTooltip(filled ? new Tooltip(sample.name() + "\nID " + sample.id()) : null);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (!updating && value != null && !Objects.equals(selectedSampleId(), value.id())) load(value);
        });
        name.setId("sample-name");
        rate.setId("sample-rate");
        rate.setPrefColumnCount(4);
        rate.setTooltip(new Tooltip("Native DAC rate in hex. 00 is slowest."));
        waveform.setId("sample-waveform");
        name.textProperty().addListener((obs, old, text) -> { if (!updating) refresh(); });
        rate.textProperty().addListener((obs, old, text) -> { if (!updating) refresh(); });
        name.setOnAction(event -> apply());
        rate.setOnAction(event -> apply());

        var nameField = new VBox(4, label("Sample name", "muted"), name);
        nameField.setMinWidth(0);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        var rateField = new VBox(4, label("Rate (hex)", "muted"), rate);
        rateField.setPrefWidth(84);
        var fields = new HBox(8, nameField, rateField);
        form.getChildren().addAll(fields, new FlowPane(8, 6, apply, reload), notice,
                waveform, new FlowPane(8, 4, rateHz, audioSize, usage), replace);
        form.setMinWidth(WAVE_WIDTH);
        HBox.setHgrow(form, Priority.ALWAYS);
        VBox inventory = new VBox(8, label("DAC SAMPLES", "section"), list,
                new FlowPane(8, 8, importButton, duplicate, delete));
        inventory.setMinWidth(145);
        inventory.setPrefWidth(172);
        inventory.setMaxWidth(172);
        notice.visibleProperty().bind(notice.textProperty().isNotEmpty());
        notice.managedProperty().bind(notice.visibleProperty());
        var contents = new HBox(12, inventory, form);
        contents.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(contents);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("editor-scroll");
        setCenter(scroll);
        setMinSize(0, 0);
        setProject(null);
    }

    void setProject(StudioProject next) {
        boolean initial = project == null;
        boolean draft = draftChanged();
        project = next;
        updating = true;
        try {
            List<DacSample> samples = next == null ? List.of() : next.samples();
            if (next == null) {
                documentGeneration++;
                fill(null);
            } else if (initial && !samples.isEmpty()) {
                fill(samples.getFirst());
            } else {
                DacSample current = findSample(next, selectedSampleId());
                if (current != null && !draft && !current.equals(baseline)) fill(current);
            }
            if (!list.getItems().equals(samples)) list.getItems().setAll(samples);
            selectListItem();
        } finally {
            updating = false;
        }
        refresh();
    }

    String selectedSampleId() {
        return baseline == null ? null : baseline.id();
    }

    void selectSample(String id) {
        DacSample current = findSample(project, id);
        if (current != null && !Objects.equals(id, selectedSampleId())) load(current);
    }

    boolean ownsFocus(Node node) {
        for (; node != null; node = node.getParent()) {
            if (node == this) return true;
        }
        return false;
    }

    void deleteSelected() {
        if (baseline == null) return;
        DacSample expected = baseline;
        submit(p -> ProjectEdits.deleteSample(guardSample(p, expected), expected.id()));
        if (findSample(project, expected.id()) == null && !draftChanged() && Objects.equals(expected, baseline)) {
            load(project == null || project.samples().isEmpty() ? null : project.samples().getFirst());
        }
    }

    void importFile(Path path) {
        readAudio(path, null);
    }

    void close() {
        closed = true;
        reader.shutdownNow();
    }

    private void chooseAudio(boolean replaceAudio) {
        Window owner = getScene() == null ? null : getScene().getWindow();
        if (owner == null || project == null || reading) return;
        DacSample target = replaceAudio ? baseline : null;
        long generation = documentGeneration;
        var chooser = new FileChooser();
        chooser.setTitle(replaceAudio ? "Replace DAC sample audio" : "Import DAC sample");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV or unsigned PCM", "*.wav", "*.WAV", "*.pcm", "*.PCM", "*.bin", "*.BIN"));
        var chosen = chooser.showOpenDialog(owner);
        if (chosen != null && generation == documentGeneration) readAudio(chosen.toPath(), target);
    }

    private void readAudio(Path path, DacSample target) {
        if (project == null || reading || closed) return;
        reading = true;
        refresh();
        long generation = documentGeneration;
        reader.execute(() -> {
            DacSample sample;
            try {
                sample = DacSampleReader.read(path, target == null ? newId() : target.id());
            } catch (IOException | IllegalArgumentException failure) {
                Platform.runLater(() -> finishRead(generation, target, null, "Cannot import " + path.getFileName() + ". " + failure.getMessage()));
                return;
            }
            Platform.runLater(() -> finishRead(generation, target, sample, null));
        });
    }

    private void finishRead(long generation, DacSample target, DacSample sample, String failure) {
        reading = false;
        if (closed) return;
        refresh();
        if (generation != documentGeneration) return;
        if (failure != null) {
            error.accept(failure);
        } else if (target == null) {
            submit(p -> ProjectEdits.addSample(p, sample));
            selectSample(sample.id());
        } else if (target.equals(baseline)) {
            replacementAudio = sample;
            rate.setText(hex(sample.rate()));
            drawWaveform();
            refresh();
        } else {
            error.accept("The selected sample changed while audio was loading. Choose Replace audio again.");
        }
    }

    private void duplicate() {
        DacSample source = findSample(project, selectedSampleId());
        if (source == null) return;
        var copy = new DacSample(newId(), source.name() + " copy", source.rate(), source.pcmBase64(), source.origin());
        submit(p -> ProjectEdits.addSample(p, copy));
        selectSample(copy.id());
    }

    private void apply() {
        if (baseline == null || reading) return;
        DacSample expected = baseline;
        int parsed = parseRate(rate.getText());
        if (parsed < 0) {
            error.accept("Enter a native rate from 00 to FF in hexadecimal.");
            return;
        }
        if (name.getText().isBlank()) {
            error.accept("Enter a sample name.");
            return;
        }
        DacSample audio = replacementAudio == null ? expected : replacementAudio;
        var sample = new DacSample(expected.id(), name.getText(), parsed, audio.pcmBase64(), audio.origin());
        submit(p -> ProjectEdits.updateSample(guardSample(p, expected), sample));
        if (sample.equals(findSample(project, sample.id()))) load(sample);
    }

    private void reload() {
        DacSample current = findSample(project, selectedSampleId());
        if (current == null) error.accept("The sample was removed. Your draft is retained.");
        else load(current);
    }

    private void load(DacSample sample) {
        updating = true;
        try {
            fill(sample);
            selectListItem();
        } finally {
            updating = false;
        }
        refresh();
    }

    private void fill(DacSample sample) {
        baseline = sample;
        replacementAudio = null;
        name.setText(sample == null ? "" : sample.name());
        rate.setText(sample == null ? "" : hex(sample.rate()));
        drawWaveform();
    }

    private void selectListItem() {
        DacSample current = findSample(project, selectedSampleId());
        if (current == null) list.getSelectionModel().clearSelection();
        else list.getSelectionModel().select(current);
    }

    private void refresh() {
        String id = selectedSampleId();
        DacSample current = findSample(project, id);
        boolean changed = draftChanged();
        boolean stale = baseline != null && !baseline.equals(current);
        long hits = project == null || id == null ? 0 : project.sequences().stream()
                .flatMap(source -> source.triggers().stream()).filter(hit -> id.equals(hit.sampleId())).count();
        usage.setText(id == null ? "" : hits + (hits == 1 ? " linked hit" : " linked hits"));
        int parsed = parseRate(rate.getText());
        rateHz.setText(parsed < 0 ? "" : String.format(Locale.ROOT, "%.1f Hz", DacSample.sampleRateHz(parsed)));
        usage.setTooltip(id == null ? null : new Tooltip("ID " + id));
        form.setDisable(baseline == null);
        rate.setDisable(reading);
        importButton.setDisable(project == null || reading);
        replace.setDisable(current == null || stale || reading);
        duplicate.setDisable(current == null);
        delete.setDisable(current == null || stale || hits != 0);
        apply.setDisable(!changed || stale || reading);
        reload.setDisable(current == null || !(changed || stale));
        notice.setText(reading ? "Reading audio…"
                : baseline == null ? "Import or choose a sample."
                : current == null ? "Sample removed. Your draft is retained until you choose another sample."
                : stale ? "Sample changed externally. Reload it before applying your edits."
                : changed ? "Unapplied sample edits."
                : "");
    }

    private void drawWaveform() {
        var graphics = waveform.getGraphicsContext2D();
        graphics.setFill(WAVE_BACKGROUND);
        graphics.fillRect(0, 0, WAVE_WIDTH, WAVE_HEIGHT);
        graphics.setStroke(WAVE_AXIS);
        graphics.strokeLine(0, WAVE_HEIGHT / 2.0, WAVE_WIDTH, WAVE_HEIGHT / 2.0);
        DacSample audio = replacementAudio == null ? baseline : replacementAudio;
        if (audio == null) {
            audioSize.setText("");
            return;
        }

        byte[] pcm = audio.pcm();
        audioSize.setText(pcm.length + (pcm.length == 1 ? " PCM byte" : " PCM bytes"));
        graphics.setStroke(WAVE_LINE);
        int columns = Math.min(pcm.length, WAVE_WIDTH);
        double previousX = 0;
        double previousY = waveformY(Byte.toUnsignedInt(pcm[0]));
        for (int column = 0; column < columns; column++) {
            int start = column * pcm.length / columns;
            int end = (column + 1) * pcm.length / columns;
            int low = 255;
            int high = 0;
            for (int frame = start; frame < end; frame++) {
                int value = Byte.toUnsignedInt(pcm[frame]);
                low = Math.min(low, value);
                high = Math.max(high, value);
            }
            double x = (column + 0.5) * WAVE_WIDTH / columns;
            graphics.strokeLine(x, waveformY(high), x, waveformY(low) + 1);
            double center = (waveformY(low) + waveformY(high)) / 2;
            if (column > 0) graphics.strokeLine(previousX, previousY, x, center);
            previousX = x;
            previousY = center;
        }
    }

    private boolean draftChanged() {
        return baseline != null && (replacementAudio != null || !name.getText().equals(baseline.name())
                || parseRate(rate.getText()) != baseline.rate());
    }

    private void submit(UnaryOperator<StudioProject> operation) {
        try {
            edit.accept(operation);
        } catch (IllegalArgumentException failure) {
            error.accept(failure.getMessage());
        }
    }

    static StudioProject guardSample(StudioProject project, DacSample expected) {
        if (!expected.equals(findSample(project, expected.id())))
            throw new IllegalArgumentException("Sample changed or was removed. Your draft is retained.");
        return project;
    }

    private static DacSample findSample(StudioProject project, String id) {
        return project == null || id == null ? null
                : project.samples().stream().filter(sample -> sample.id().equals(id)).findFirst().orElse(null);
    }

    private static int parseRate(String text) {
        String trimmed = text.strip();
        return trimmed.matches("[0-9a-fA-F]{1,2}") ? Integer.parseInt(trimmed, 16) : -1;
    }

    private static String newId() {
        return "sample-" + UUID.randomUUID();
    }

    private static String hex(int value) {
        return String.format(Locale.ROOT, "%02X", value);
    }

    private static double waveformY(int value) {
        return 2 + (255 - value) * (WAVE_HEIGHT - 4) / 255.0;
    }

    private static Label label(String text, String style) {
        var label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }

    private static Button button(String text, String id, Runnable action) {
        var button = new Button(text);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }
}
