package com.opensmpsdeck.studio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.headless.ReconstructionReview.EditResult;
import com.opensmpsdeck.headless.ReconstructionReview.Revision;
import com.opensmpsdeck.headless.ReconstructionSections;
import com.opensmpsdeck.project.ProjectWatcher;
import com.opensmpsdeck.studio.ReconstructionTools.Mode;
import com.opensmpsdeck.studio.ReconstructionTools.Placement;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.opensmpsdeck.studio.ReconstructionDraft.text;

/** Owns draft history, note controls and the two watched reconstruction journals. */
final class ReconstructionEditor implements AutoCloseable {
    record Snapshot(ReconstructionInspection inspection, Revision revision,
                    Map<String, List<EditResult>> outcomes, BasicFileAttributes report) {
        static Snapshot read(Path work) throws IOException {
            var revision = ReconstructionReview.revision(work);
            revision.entries();
            var report = report(work);
            var inspection = ReconstructionInspection.open(work);
            var outcomes = ReconstructionReview.outcomes(work);
            if (!revision.equals(ReconstructionReview.revision(work))) throw new ReconstructionReview.Changed();
            return new Snapshot(inspection, revision, outcomes, report);
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
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().daemon().name("reconstruction-editor").unstarted(task));
    private final List<ProjectWatcher> watchers = new ArrayList<>();
    private final ReconstructionDraft draft;

    private final Button apply = new Button("Apply");
    private final Button cancel = new Button("Cancel");
    private final Button undo = new Button("Undo");
    private final Button redo = new Button("Redo");
    private final Label pendingLabel = new Label();
    private final Button conflict = new Button("Resolve conflict…");
    private final ProgressBar progress = new ProgressBar(0);
    private final CheckBox outline = new CheckBox("Outlines (O)");
    private final ComboBox<ReconstructionInspection.Part> parts = new ComboBox<>();
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
    private final Button remove = new Button("Remove selected edit");
    private final Label comparison = new Label("Apply a change to compare reconstruction output.");
    private final Tab noteTab = new Tab("Note");
    private final Tab editTab = new Tab("Edits");
    private final Tab diffTab = new Tab("Trace diff");
    private final TabPane tabs = new TabPane(noteTab, editTab, diffTab);

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

    ReconstructionEditor(Path work, Window owner, ReconstructionCanvas view, Snapshot initial,
                         Consumer<String> status, Consumer<ReconstructionInspection> refresh) {
        this.work = work;
        this.owner = owner;
        this.view = view;
        this.snapshot = initial;
        this.status = status;
        this.refresh = refresh;
        sections = initial.inspection().sections().sections();
        draft = new ReconstructionDraft(initial.revision().entries());
        view.onInsert = this::insert;
        view.onPlacement = this::move;
        view.onSectionEdit = this::section;
        view.onSectionMenu = this::sectionMenu;
        for (String filename : List.of("edits.json", "sections.json", "report.json")) {
            try {
                watchers.add(new ProjectWatcher(work.resolve(filename), () -> Platform.runLater(this::readExternal)));
            } catch (IOException failure) {
                status.accept("Could not watch " + filename + " · " + failure.getMessage());
            }
        }
    }

    FlowPane toolbar() {
        var buttons = new ToggleGroup();
        var bar = new FlowPane(8, 6);
        bar.getStyleClass().addAll("panelbar", "reconstruction-controls");
        bar.setAlignment(Pos.CENTER_LEFT);
        for (var mode : Mode.values()) {
            var button = new ToggleButton(mode.label);
            button.setId("reconstruction-tool-" + mode.name().toLowerCase(Locale.ROOT));
            button.setToggleGroup(buttons);
            button.setDisable(!mode.available);
            button.setTooltip(new Tooltip(switch (mode) {
                case SELECT -> "Select or drag a note";
                case INSERT -> "Click an empty pitch row to insert";
                default -> "Planned for a later release";
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
        parts.setPrefWidth(162);
        parts.getItems().setAll(snapshot.inspection().parts());
        parts.getItems().stream().filter(part -> !part.drums()).findFirst()
                .or(() -> parts.getItems().stream().findFirst()).ifPresent(parts::setValue);
        parts.valueProperty().addListener((ignored, old, next) -> view.insertAvailable(next != null));
        view.insertAvailable(parts.getValue() != null);
        var grid = new ComboBox<>(FXCollections.observableArrayList(ReconstructionInspection.Grid.values()));
        grid.setValue(ReconstructionInspection.Grid.BEAT);
        grid.setTooltip(new Tooltip("Hold Alt during placement to bypass snapping"));
        grid.valueProperty().addListener((ignored, old, next) -> view.grid(next));
        apply.setId("reconstruction-apply");
        apply.setOnAction(event -> apply());
        cancel.setOnAction(event -> cancel());
        undo.setOnAction(event -> history(false));
        redo.setOnAction(event -> history(true));
        conflict.setId("reconstruction-conflict");
        conflict.setOnAction(event -> resolve());
        progress.setPrefWidth(108);
        for (Control control : List.of(progress, cancel, conflict)) control.managedProperty().bind(control.visibleProperty());
        bar.getChildren().addAll(parts, grid, apply, cancel, progress, undo, redo, pendingLabel, conflict);
        return bar;
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

    TabPane inspector() {
        noteTitle.getStyleClass().add("inspector-title");
        noteTitle.setWrapText(true);
        noteSummary.setWrapText(true);
        noteSummary.setId("reconstruction-note-summary");
        rawTrace.setId("reconstruction-trace");
        rawTrace.setEditable(false);
        rawTrace.setWrapText(true);
        rawTrace.setPrefRowCount(12);
        raw.setId("reconstruction-raw-trace");
        raw.setExpanded(false);
        role.setPromptText("Role");
        lane.setPromptText("Lane");
        velocity.setEditable(true);
        velocity.setPrefWidth(95);
        var setRole = new Button("Set role");
        setRole.setOnAction(event -> {
            if (selected != null && role.getValue() != null)
                target("setRole", "role", role.getValue(), "Set role in Studio");
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
        var placement = new VBox(8,
                new FlowPane(6, 5, role, setRole), new FlowPane(6, 5, lane, pin),
                new FlowPane(6, 5, velocity, setVelocity), noteDelete);
        setRole.disableProperty().bind(role.disabledProperty());
        pin.disableProperty().bind(lane.disabledProperty());
        setVelocity.disableProperty().bind(velocity.disabledProperty());
        noteDelete.setOnAction(event -> delete());

        var details = new VBox(12, noteTitle, noteSummary, placement, raw);
        details.getStyleClass().add("inspector");
        noteTab.setContent(scroll(details));
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
            updateDraft();
        });
        var editList = new VBox(8, journal, remove);
        editList.getStyleClass().add("inspector");
        VBox.setVgrow(journal, Priority.ALWAYS);
        editTab.setContent(editList);
        comparison.setWrapText(true);
        comparison.setId("reconstruction-diff-summary");
        var summary = new VBox(comparison);
        summary.getStyleClass().add("inspector");
        diffTab.setContent(scroll(summary));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefWidth(324);
        tabs.setMinWidth(255);
        updateDraft();
        readExternal();
        return tabs;
    }

    private static ScrollPane scroll(VBox content) {
        var pane = new ScrollPane(content);
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
            noteTitle.setText("Note trace");
            noteSummary.setText(EMPTY_TRACE);
            rawTrace.clear();
            updateControls();
            return;
        }
        noteTitle.setText(ReconstructionCanvas.pitchName(note.pitch()) + " · " + note.partName());
        var detail = inspection.noteDetails(note.id());
        var time = note.interval();
        String body = String.format(Locale.ROOT, "%s%s%n%nRole  %s%nLane  %s%nInstrument  %s"
                        + "%n%nSource  %.3f–%.3f s%nProject  %.3f–%.3f s%n%nCompeting notes  %s%nEdits applied  %s",
                note.fate(), note.reason() == null ? "" : "\n" + note.reason(), ReconstructionCanvas.roleName(note.role()),
                orDash(note.lane()), orDash(note.patch()), time.sourceStart(), time.sourceEnd(),
                time.projectStart(), time.projectEnd(), names(note.competitors()), names(detail.edits()));
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

    private int pendingVelocity(Overlay note, int fallback) {
        int value = fallback;
        for (var entry : draft.entries()) {
            if (draft.changed(entry) && note.id().equals(targetId(entry)) && ReconstructionReview.validationIssue(entry) == null
                    && entry.isJsonObject()
                    && entry.getAsJsonObject().has("velocity")) value = entry.getAsJsonObject().get("velocity").getAsInt();
        }
        return value;
    }

    private static String orDash(String value) { return value == null ? "—" : value; }
    private static String names(List<String> values) { return values == null || values.isEmpty() ? "—" : String.join(", ", values); }

    private boolean editableScore(Overlay note) {
        return snapshot.inspection().noteDetails(note.id()).scored() || localInsert(note.id());
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

    private void insert(Placement location) {
        if (busy || external != null || parts.getValue() == null) return;
        if (!parts.getValue().drums() && (location.pitch() < 12 || location.pitch() > 106)) {
            status.accept("Choose a tonal pitch between MIDI 12 and 106.");
            return;
        }
        var intent = ReconstructionDraft.insert(parts.getValue().id(), location.start(), location.end(), location.pitch());
        add(intent);
        var inserted = overlays().stream().filter(note -> note.id().equals(targetId(intent))).findFirst().orElseThrow();
        view.select(inserted, null);
        tabs.getSelectionModel().select(noteTab);
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

    private void delete() {
        if (!selectableNote() || busy || external != null) return;
        String id = selected.id();
        if (localInsert(id)) {
            draft.change(entries -> {
                for (int i = entries.size() - 1; i >= 0; i--)
                    if (id.equals(targetId(entries.get(i)))) entries.remove(i);
            });
            updateDraft();
        } else if (snapshot.inspection().noteDetails(id).generated()) {
            target("setRole", "role", "drop", "Drop generated note in Studio");
        } else {
            add(single("deleteNote", selected, "Delete note in Studio"));
        }
    }

    private static JsonObject single(String op, Overlay note, String reason) {
        var intent = ReconstructionDraft.human(op, reason);
        intent.addProperty("noteId", note.id());
        return intent;
    }

    private void add(JsonObject intent) {
        if (busy || external != null) return;
        var error = ReconstructionReview.validationIssue(intent);
        if (error != null) {
            status.accept(error);
            return;
        }
        draft.add(intent);
        updateDraft();
    }

    private static String targetId(JsonElement entry) {
        if (text(entry, "op", "").equals("insertNote")) return "edit-" + text(entry, "id", "");
        if (!entry.isJsonObject()) return null;
        var ids = entry.getAsJsonObject().get("noteIds");
        if (ids != null && ids.isJsonArray() && ids.getAsJsonArray().size() == 1
                && ids.getAsJsonArray().get(0).isJsonPrimitive()) return ids.getAsJsonArray().get(0).getAsString();
        return text(entry, "noteId", null);
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
            String id = targetId(entry);
            if (id == null) continue;
            var json = entry.getAsJsonObject();
            var note = pending.getOrDefault(id, built.get(id));
            var op = text(entry, "op", "");
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
            if (note == null) continue;
            var place = Placement.of(note);
            var next = new Placement(number(json, "start", place.start()), number(json, "end", place.end()),
                    (int) number(json, "pitch", place.pitch()));
            String nextRole = text(entry, "role", null);
            boolean drop = op.equals("deleteNote") || "drop".equals(nextRole)
                    || nextRole == null && note.fate().equals("Pending deletion");
            var color = nextRole == null || drop ? note.role() : Role.valueOf(nextRole.toUpperCase(Locale.ROOT));
            String fate = drop ? "Pending deletion" : note.fate().equals("Pending insertion")
                    ? note.fate() : "Pending change";
            String nextLane = text(entry, "lane", note.lane());
            pending.put(id, preview(note, next, color, nextLane, fate, text(entry, "reason", ""),
                    !drop && nextLane != null));
        }
        return List.copyOf(pending.values());
    }

    private static double number(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }

    private Overlay preview(Overlay source, Placement place, Role role, String lane, String fate, String reason,
                            boolean sounds) {
        return new Overlay(source.id(), source.eventId(), source.part(), source.partName(), role, lane, place.pitch(),
                source.patch(), place.interval(snapshot.inspection()), List.of(), sounds,
                ReconstructionInspection.Style.SOLID, fate, reason, source.competitors());
    }

    private void updateDraft() {
        view.pending(overlays());
        var rows = new ArrayList<EditRow>();
        var entries = draft.entries();
        var idCounts = entries.asList().stream().collect(Collectors.groupingBy(e -> text(e, "id", ""), Collectors.counting()));
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String id = text(entry, "id", "");
            String issue = ReconstructionReview.validationIssue(entry);
            if (issue == null && idCounts.get(id) > 1) issue = "Duplicate edit ID";
            var results = snapshot.outcomes().getOrDefault(id, List.of());
            String state = issue != null ? "invalid" : draft.changed(entry) || results.isEmpty() ? "pending"
                    : results.stream().map(result -> result.status().equals("satisfied") ? "applied" : result.status())
                        .distinct().collect(Collectors.joining(", "));
            String detail = issue != null ? issue : results.stream().map(EditResult::reason).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" · "));
            rows.add(row(i, entry, state, detail));
        }
        for (var removed : draft.removed()) rows.add(row(-1, removed, "pending removal", ""));
        journal.getItems().setAll(rows);
        updateControls();
    }

    private static EditRow row(int index, JsonElement entry, String state, String detail) {
        return new EditRow(index, text(entry, "op", "Unreadable edit") + " · " + text(entry, "author", "agent")
                + " · " + state, text(entry, "id", "—") + "\n" + text(entry, "reason", "No reason supplied")
                + (detail.isBlank() ? "" : "\n" + detail));
    }

    private void updateControls() {
        boolean editing = !busy && external == null && !externalError;
        view.editing(editing);
        var entries = draft.entries();
        boolean unbuilt = entries.asList().stream().anyMatch(e -> !snapshot.outcomes().containsKey(text(e, "id", "")));
        apply.setDisable(!editing || !draft.pending() && !unbuilt && !externalUnbuilt);
        undo.setDisable(!editing || !draft.canUndo(false));
        redo.setDisable(!editing || !draft.canUndo(true));
        cancel.setVisible(busy);
        progress.setVisible(busy);
        conflict.setVisible(external != null);
        pendingLabel.setText(external != null ? "Conflict" : draft.pending() ? "Pending edits" : "");
        noteDelete.setDisable(!editing || !selectableNote() || selected.fate().equals("Pending deletion"));
        role.setDisable(!editing || !selectableNote());
        lane.setDisable(!editing || !selectableNote());
        velocity.setDisable(!editing || selected == null || !editableScore(selected));
        var edit = journal.getSelectionModel().getSelectedItem();
        remove.setDisable(!editing || edit == null || edit.index() < 0);
    }

    private void history(boolean forward) {
        if (busy || external != null || externalError) return;
        draft.undo(forward);
        updateDraft();
    }

    boolean key(KeyEvent event) {
        if (event.isShortcutDown() && (event.getCode() == KeyCode.Z || event.getCode() == KeyCode.Y)) {
            history(event.getCode() == KeyCode.Y || event.isShiftDown());
        } else if (!event.isShortcutDown() && event.getCode() == KeyCode.DELETE && view.canvas.isFocused()) {
            delete();
        } else if (!event.isShortcutDown() && event.getCode() == KeyCode.O) {
            outline.setSelected(!outline.isSelected());
            view.outline(outline.isSelected());
        } else return false;
        return true;
    }

    private void apply() {
        setBusy();
        var expected = snapshot.revision();
        var intents = draft.entries();
        worker.execute(() -> {
            JsonObject diff = null;
            Snapshot next = null;
            Exception failure = null;
            try {
                synchronized (jobLock) {
                    running = Thread.currentThread();
                    if (cancelling) throw new java.util.concurrent.CancellationException();
                }
                diff = ReconstructionReview.apply(work, expected, intents, this::reportProgress);
                next = Snapshot.read(work);
            } catch (Exception error) {
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

    private void finish(JsonObject diff, Snapshot next, Exception error) {
        if (closed) return;
        busy = false;
        if (diff != null) {
            draft.applied();
            externalUnbuilt = false;
            comparison.setText(summary(diff));
            tabs.getSelectionModel().select(diffTab);
            if (next != null) adopt(next);
            status.accept(error == null ? "Reconstruction applied from " + diff.get("from").getAsString()
                    : "Applied, but refresh failed · " + error.getMessage());
        } else if (error != null) {
            status.accept(cancelling ? "Apply cancelled · edits are still pending" : "Apply failed · " + error.getMessage());
        }
        updateDraft();
        readExternal();
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
        busy = true;
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
        if (closed || busy) return;
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
                var next = !rebuilt && revision.equals(current.revision()) ? current : Snapshot.read(work);
                Platform.runLater(() -> {
                    refreshQueued = false;
                    if (closed || busy || snapshot != current) return;
                    externalError = false;
                    if (next == current) {
                        external = null;
                        updateControls();
                        if (checkAgain) readExternal();
                        return;
                    }
                    boolean sectionsChanged = !Objects.equals(current.revision().sections(), next.revision().sections());
                    boolean journalsChanged = !current.revision().equals(next.revision());
                    if (draft.adopt(next.revision().entries(), sectionsChanged || journalsChanged && draft.pending())) {
                        external = null;
                        if (rebuilt) externalUnbuilt = false;
                        else if (!Objects.equals(current.revision().edits(), next.revision().edits())) externalUnbuilt = true;
                        adopt(next);
                        status.accept("Adopted reconstruction changes from disk");
                    } else {
                        external = next;
                        view.editing(false);
                        status.accept("Edits or sections changed on disk · resolve the conflict to continue");
                        updateControls();
                    }
                    if (checkAgain) readExternal();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> {
                    refreshQueued = false;
                    if (closed || busy || snapshot != current) return;
                    externalError = true;
                    status.accept("External reconstruction update unavailable · " + failure.getMessage());
                    updateControls();
                    if (checkAgain) readExternal();
                });
            }
        });
    }

    private void adopt(Snapshot next) {
        snapshot = next;
        sections = next.inspection().sections().sections();
        external = null;
        refresh.accept(next.inspection());
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
            externalUnbuilt = !Objects.equals(snapshot.revision().edits(), external.revision().edits());
            draft.discard(external.revision().entries());
            adopt(external);
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
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    snapshot = new Snapshot(snapshot.inspection(), result.revision(), snapshot.outcomes(), snapshot.report());
                    sections = result.result().sections();
                    view.sections(sections);
                    status.accept("Musical sections saved");
                    updateControls();
                    readExternal();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    status.accept("Section change failed · " + failure.getMessage());
                    updateControls();
                    readExternal();
                });
            }
        });
    }

    boolean allowClose() {
        if (busy) {
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
        closed = true;
        watchers.forEach(ProjectWatcher::close);
        synchronized (jobLock) {
            if (running != null && !publishing) running.interrupt();
        }
        worker.shutdown();
    }
}
