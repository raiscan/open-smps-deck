package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import javafx.geometry.Insets;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** The DAC source editor uses point events on sample rows. */
final class DacTriggerPane extends BorderPane {
    static final int GUTTER = 134;
    static final int HEADER = 27;
    private static final int ROW = 30;
    private static final int SCALE = 9;
    private static final Color BACKGROUND = Color.web("#101722");
    private static final Color GUTTER_COLOR = Color.web("#172130");
    private static final Color GRID_LINE = Color.web("#29384a");
    private static final Color ACCENT = Color.web("#e7ab69");
    private static final Color SELECTED = Color.web("#fff1d5");
    private static final Color LABEL = Color.web("#c5d2e5");

    final TriggerGrid grid = new TriggerGrid();
    private final TextField tick = new TextField("0");
    private final ComboBox<String> sample = new ComboBox<>();
    private final Label hint = new Label();
    private final Button add, apply, delete, editSample;
    private final Consumer<UnaryOperator<StudioProject>> edit;
    private final Consumer<String> error;
    private final Consumer<String> openSample;
    private StudioProject project;
    private Sequence source, baseline;
    private SampleTrigger selected;
    private String initialSample;
    private String initialTick = "0";
    private boolean updating;

    DacTriggerPane(Consumer<UnaryOperator<StudioProject>> edit, Consumer<String> error, Consumer<String> openSample) {
        this.edit = edit;
        this.error = error;
        this.openSample = openSample;
        setMinSize(0, 0);

        tick.setId("trigger-tick");
        tick.setPrefColumnCount(6);
        sample.setId("trigger-sample");
        sample.setPrefWidth(170);
        sample.setCellFactory(ignored -> sampleCell());
        sample.setButtonCell(sampleCell());
        sample.valueProperty().addListener((obs, old, value) -> {
            if (!updating) refreshActions();
        });
        tick.textProperty().addListener((obs, old, value) -> {
            if (!updating) refreshActions();
        });
        tick.setOnAction(event -> {
            if (selected == null) addFromForm();
            else apply();
        });
        add = button("Add hit", "add-trigger", this::addFromForm);
        apply = button("Apply hit", "apply-trigger", this::apply);
        delete = button("Delete hit", "delete-trigger", this::deleteSelected);
        editSample = button("Edit sample…", "edit-trigger-sample", () -> openSample.accept(sample.getValue()));
        var reload = button("Reset form", "reload-trigger", this::reloadForm);
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        var actions = new FlowPane(7, 6, new Label("Tick"), tick, new Label("Sample"),
                sample, add, apply, delete, reload, editSample);
        var tools = new VBox(6, actions, hint);
        tools.setPadding(new Insets(9));
        setTop(tools);
        grid.canvas.setId("dac-trigger-grid");
        setCenter(grid.scroll);
        refreshActions();
    }

    void setSource(StudioProject nextProject, Sequence nextSource) {
        boolean same = nextSource != null && source != null && source.id().equals(nextSource.id());
        boolean retain = same && draftChanged();
        String sampleId = sample.getValue();
        String selectedId = selected == null ? null : selected.id();
        updating = true;
        try {
            project = nextProject;
            source = nextSource;
            if (!same) {
                selected = null;
                grid.scroll.setHvalue(0);
                grid.scroll.setVvalue(0);
            }
            SampleTrigger latest = findTrigger(selectedId);
            sample.getItems().setAll(samples().stream().map(DacSample::id).toList());
            if (retain) sample.setValue(sampleId);
            else if (same && latest == null && selectedId != null) fill(null, "0", firstSample());
            else if (same && latest == null) fill(null, tick.getText(), exists(sampleId) ? sampleId : firstSample());
            else if (same) fill(latest, Integer.toString(latest.tick()), latest.sampleId());
            else fill(null, "0", firstSample());
        } finally {
            updating = false;
        }
        grid.refreshLayout();
        refreshActions();
    }

    void deleteSelected() {
        if (delete.isDisabled()) return;
        Sequence expected = baseline;
        String id = selected.id();
        edit.accept(p -> ProjectEdits.deleteTrigger(requireSource(p, expected), expected.id(), id));
        if (findTrigger(id) == null) load(null, tick.getText(), sample.getValue());
    }

    private void addFromForm() {
        try { addAt(parseTick(), sample.getValue(), baseline); }
        catch (IllegalArgumentException failure) { error.accept(failure.getMessage()); }
    }

    private void addAt(int at, String sampleId, Sequence expected) {
        if (expected == null || sampleId == null) {
            error.accept("Choose a DAC source and a sample first.");
            return;
        }
        SampleTrigger event = new SampleTrigger("hit-" + UUID.randomUUID(), at, sampleId);
        edit.accept(p -> ProjectEdits.addTrigger(requireSource(p, expected), expected.id(), event));
        SampleTrigger result = findTrigger(event.id());
        if (result != null) load(result, Integer.toString(at), sampleId);
    }

    private void apply() {
        if (selected == null || source == null || sample.getValue() == null) return;
        try {
            Sequence expected = baseline;
            SampleTrigger event = new SampleTrigger(selected.id(), parseTick(), sample.getValue());
            edit.accept(p -> ProjectEdits.updateTrigger(requireSource(p, expected), expected.id(), event));
            if (event.equals(findTrigger(event.id()))) load(event, Integer.toString(event.tick()), event.sampleId());
        } catch (IllegalArgumentException failure) {
            error.accept(failure.getMessage());
        }
    }

    private void reloadForm() {
        SampleTrigger latest = selected == null ? null : findTrigger(selected.id());
        if (latest == null) load(null, "0", firstSample());
        else load(latest, Integer.toString(latest.tick()), latest.sampleId());
    }

    private void load(SampleTrigger event, String tickValue, String sampleId) {
        updating = true;
        try { fill(event, tickValue, sampleId); }
        finally { updating = false; }
        grid.draw();
        refreshActions();
    }

    private void fill(SampleTrigger event, String tickValue, String sampleId) {
        baseline = source;
        selected = event;
        initialTick = tickValue;
        initialSample = sampleId;
        tick.setText(tickValue);
        sample.setValue(sampleId);
    }

    private boolean draftChanged() {
        return baseline != null
                && (!initialTick.equals(tick.getText()) || !Objects.equals(initialSample, sample.getValue()));
    }

    private void refreshActions() {
        boolean stale = source != null && baseline != null && !source.equals(baseline);
        boolean hasSample = exists(sample.getValue());
        add.setDisable(source == null || !hasSample || stale);
        apply.setDisable(source == null || selected == null || !hasSample || stale);
        delete.setDisable(source == null || selected == null || stale);
        editSample.setDisable(!hasSample);
        if (stale) hint.setText("The source changed on disk. Reset form to edit the current hits.");
        else if (samples().isEmpty()) hint.setText("Import a sample in Assets → DAC samples to add hits.");
        else hint.setText("Double-click a sample row to add a hit. A new hit cuts the previous sample.");
    }

    private int parseTick() {
        try { return Integer.parseInt(tick.getText().trim()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Enter a whole number for the hit tick."); }
    }

    private List<DacSample> samples() { return project == null ? List.of() : project.samples(); }
    private String firstSample() { return samples().isEmpty() ? null : samples().getFirst().id(); }
    private boolean exists(String id) { return id != null && samples().stream().anyMatch(s -> s.id().equals(id)); }
    private String name(String id) {
        return samples().stream().filter(s -> s.id().equals(id)).map(DacSample::name).findFirst().orElse(id);
    }
    private SampleTrigger findTrigger(String id) {
        if (source == null || id == null) return null;
        return source.triggers().stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }

    private static StudioProject requireSource(StudioProject project, Sequence expected) {
        if (expected == null || !project.sequences().contains(expected))
            throw new IllegalArgumentException("The DAC source changed. Reset the trigger form first.");
        return project;
    }

    private ListCell<String> sampleCell() {
        return new ListCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : name(value));
            }
        };
    }

    private static Button button(String label, String id, Runnable action) {
        Button button = new Button(label);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }

    final class TriggerGrid extends ViewportCanvas {
        TriggerGrid() {
            canvas.setOnMousePressed(event -> {
                if (event.getButton() != MouseButton.PRIMARY || source == null
                        || event.getX() < GUTTER || event.getY() < HEADER) return;
                canvas.requestFocus();
                int row = (int)((event.getY() - HEADER) / ROW);
                if (row >= samples().size()) return;
                double position = (event.getX() - GUTTER + offset) / SCALE;
                if (position >= source.lengthTicks()) return;
                String rowSample = samples().get(row).id();
                SampleTrigger hit = source.triggers().stream()
                        .filter(t -> t.sampleId().equals(rowSample) && Math.abs(t.tick() - position) <= 0.75)
                        .findFirst().orElse(null);
                int quantum = Math.max(1, project.ticksPerBeat() / 4);
                long at = Math.round(position / quantum) * (long)quantum;
                if (hit != null) load(hit, Integer.toString(hit.tick()), rowSample);
                else if (at < source.lengthTicks()) {
                    if (event.getClickCount() == 2) addAt((int)at, rowSample, source);
                    else load(null, Long.toString(at), rowSample);
                }
            });
        }

        void refreshLayout() {
            double anchor = offset;
            dimensions(GUTTER + 20 + (source == null ? 24 : source.lengthTicks()) * (double)SCALE,
                    HEADER + Math.max(1, samples().size()) * ROW);
            double scrollable = Math.max(0, contentWidth - scroll.getViewportBounds().getWidth());
            scroll.setHvalue(scrollable == 0 ? 0 : Math.clamp(anchor / scrollable, 0, 1));
            updateViewport();
        }

        @Override void draw() {
            GraphicsContext g = canvas.getGraphicsContext2D();
            double width = canvas.getWidth();
            double height = canvas.getHeight();
            g.setFill(BACKGROUND);
            g.fillRect(0, 0, width, height);
            if (source == null) return;
            g.setFont(Font.font("System", 13));
            int beat = project.ticksPerBeat();
            long first = (long)Math.ceil(offset / (SCALE * (double)beat));
            long last = Math.min(source.lengthTicks() / (long)beat,
                    (long)Math.ceil((offset + width) / (SCALE * (double)beat)));
            g.setStroke(GRID_LINE);
            for (long b = first; b <= last; b++) {
                double x = GUTTER + b * beat * (double)SCALE - offset;
                g.strokeLine(x, HEADER, x, height);
                g.setFill(LABEL);
                g.fillText(Long.toString(b + 1), x + 5, 18);
            }
            List<DacSample> rows = samples();
            var rowById = new java.util.HashMap<String, Integer>();
            for (int row = 0; row < rows.size(); row++) {
                rowById.put(rows.get(row).id(), row);
                g.strokeLine(GUTTER, HEADER + (row + 1) * ROW, width, HEADER + (row + 1) * ROW);
            }
            for (SampleTrigger trigger : source.triggers()) {
                double x = GUTTER + trigger.tick() * (double)SCALE - offset;
                Integer row = rowById.get(trigger.sampleId());
                if (row == null || x < GUTTER || x > width) continue;
                double y = HEADER + row * ROW + ROW / 2.0;
                g.setFill(selected != null && selected.id().equals(trigger.id()) ? SELECTED : ACCENT);
                g.fillPolygon(new double[]{x, x + 6, x, x - 6}, new double[]{y - 7, y, y + 7, y}, 4);
            }
            g.setFill(GUTTER_COLOR);
            g.fillRect(0, 0, GUTTER, height);
            g.setFill(LABEL);
            g.fillText("SAMPLE", 10, 18);
            for (int row = 0; row < rows.size(); row++)
                g.fillText(rows.get(row).name(), 10, HEADER + row * ROW + 20, GUTTER - 18);
            if (rows.isEmpty()) g.fillText("Import a sample to add hits", GUTTER + 12, HEADER + 20);
        }
    }
}
