package com.opensmpsdeck.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionEditing;
import com.opensmpsdeck.headless.ReconstructionEditing.Instrument;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.headless.ReconstructionLaneType;
import com.opensmpsdeck.headless.ReconstructionVoicePlan;
import com.opensmpsdeck.headless.ReconstructionVoicePlan.Kind;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Selection filters and actions. Each action submits one draft history change. */
final class ReconstructionBulk extends VBox {
    record Voice(String id, String name, Kind kind, ReconstructionVoicePlan.Assignment assignment) {
        @Override public String toString() { return name; }
    }
    private enum Scope {
        VOICE("This voice"), NOTES("These notes only");
        private final String label;
        Scope(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final Label count = new Label("Select notes to edit them together.");
    private final Label issue = new Label();
    private final ComboBox<Role> roles = combo("Filter by role", List.of(Role.values()));
    private final ComboBox<Voice> voices = combo("Filter by voice", List.of());
    private final ComboBox<String> lanes = combo("Filter by lane", List.of());
    private final ComboBox<String> role = combo("Role", List.of("melody", "counter", "harmony", "bass", "drums", "drop"));
    private final ComboBox<Voice> voice = combo("Destination voice", List.of());
    private final TextField voiceName = new TextField();
    private final ComboBox<ReconstructionLaneType> type = combo("Chip lane type", List.of(ReconstructionLaneType.values()));
    private final ComboBox<String> lane = combo("Exact lane", TimelineView.LANES);
    private final TextField velocity = new TextField();
    private final ComboBox<Scope> scope = combo("Assignment scope", List.of(Scope.values()));
    private final ComboBox<ReconstructionLaneType> instrumentType = combo("Instrument chip", List.of(ReconstructionLaneType.values()));
    private final TextField instrumentSearch = new TextField();
    private final ComboBox<Instrument> instrument = combo("Catalogue instrument", List.of());
    private final Button route = new Button("Set lane type");
    private final Button pin = new Button("Pin lane");
    private final Button assign = new Button("Assign instrument");
    private final Button audition = new Button("Audition selection");
    private final Button collapse = new Button("Collapse repeats");
    private final Button glide = new Button("Merge with glide");
    private final Button arpeggio = new Button("Arpeggio");
    private final Button chord = new Button("Chord instrument");
    private final TextField arpeggioRate = new TextField("2");
    private final ComboBox<String> arpeggioOrder = combo("Arpeggio order", List.of("up", "down", "up-down"));
    private final VBox actions = new VBox(10);

    private final Consumer<List<JsonObject>> submit;
    private List<Overlay> selected = List.of();
    private java.util.Map<String, Voice> owners = java.util.Map.of();
    private ReconstructionEditing data;
    private java.util.function.Function<Overlay, ReconstructionLaneType> playedType;
    private boolean updating;

    ReconstructionBulk(Consumer<Predicate<Overlay>> filter, Consumer<List<JsonObject>> submit,
                       BiConsumer<List<String>, Instrument> play, Runnable stop) {
        super(10);
        this.submit = submit;
        getStyleClass().add("inspector");
        count.getStyleClass().add("inspector-title");
        issue.setWrapText(true);
        issue.setId("reconstruction-bulk-issue");
        issue.managedProperty().bind(issue.visibleProperty());
        count.setId("reconstruction-selection-count");
        route.setId("reconstruction-set-lane-type");
        type.setId("reconstruction-lane-type");
        scope.setValue(Scope.VOICE);
        arpeggioOrder.setValue("up");
        arpeggioRate.setPromptText("Driver ticks");
        arpeggioRate.setPrefColumnCount(4);
        instrument.setPrefWidth(260);
        instrument.setMaxWidth(260);
        voice.setPrefWidth(156);
        voices.setPrefWidth(138);
        instrumentSearch.setPromptText("Search catalogue");
        instrumentSearch.textProperty().addListener(ignored -> refreshInstruments());
        instrumentType.valueProperty().addListener(ignored -> refreshInstruments());
        voiceName.setPromptText("New voice name");
        velocity.setPromptText("Velocity 1–127 or scale");
        velocity.setPrefColumnCount(16);
        route.setOnAction(event -> notes("pinLane", "laneType", type.getValue().name(), "Set chip lane type"));
        pin.setOnAction(event -> notes("pinLane", "lane", lane.getValue(), "Pin lane"));
        assign.setOnAction(event -> assign());
        audition.setOnAction(event -> play.accept(ids(), instrument.getValue()));
        collapse.setOnAction(event -> submit.accept(List.of(intent("mergeNotes", "Collapse repeated notes"))));
        glide.setOnAction(event -> {
            var edit = intent("mergeNotes", "Merge notes with a pitch glide");
            edit.addProperty("glide", true);
            submit.accept(List.of(edit));
        });
        arpeggio.setOnAction(event -> {
            try {
                int rate = Integer.parseInt(arpeggioRate.getText().strip());
                if (rate < 1 || rate > 127) throw new NumberFormatException();
                var edit = intent("arpeggio", "Fit selected chord as an arpeggio");
                edit.addProperty("rate", rate);
                edit.addProperty("order", arpeggioOrder.getValue());
                submit.accept(List.of(edit));
            } catch (NumberFormatException invalid) {
                showIssue("Arpeggio rate must be 1–127 driver ticks.");
            }
        });
        chord.setOnAction(event -> submit.accept(List.of(intent("chordInstrument", "Fit selected chord as an FM instrument"))));
        for (var control : List.of(type, lane, scope, instrument, role)) control.valueProperty().addListener(ignored -> validate());
        roles.setOnAction(event -> {
            var value = roles.getValue();
            if (!updating && value != null) filter.accept(note -> note.role() == value);
        });
        voices.setOnAction(event -> {
            var value = voices.getValue();
            if (!updating && value != null) filter.accept(note -> value.equals(owners.get(note.id())));
        });
        lanes.setOnAction(event -> {
            var value = lanes.getValue();
            if (!updating && value != null) filter.accept(note -> value.equals(laneName(note)));
        });
        actions.getChildren().addAll(
                row(role, button("Set role", this::setRole)),
                row(voice, button("Move to voice", this::move)),
                row(voiceName, button("Create voice", this::create)),
                row(type, route), row(lane, pin),
                row(velocity, button("Set", () -> velocity(false)), button("Scale", () -> velocity(true))),
                new SeparatorLabel("Instrument"),
                row(scope, instrumentType), instrumentSearch, instrument,
                row(assign, audition, button("Stop audition", stop)),
                new SeparatorLabel("Combine notes"),
                row(collapse, glide), row(arpeggioOrder, arpeggioRate),
                row(arpeggio, chord));
        getChildren().addAll(count, row(roles, voices, lanes), issue, actions);
    }

    private static final class SeparatorLabel extends Label {
        SeparatorLabel(String text) { super(text); getStyleClass().add("inspector-title"); }
    }

    void show(List<Overlay> selection, List<Voice> targets, java.util.Map<String, Voice> owners,
              ReconstructionEditing data, java.util.function.Function<Overlay, ReconstructionLaneType> playedType) {
        updating = true;
        this.selected = selection;
        this.owners = owners;
        this.data = data;
        this.playedType = playedType;
        count.setText(selection.isEmpty() ? "Select notes to edit them together."
                : selection.size() + (selection.size() == 1 ? " note selected" : " notes selected"));
        actions.setDisable(selection.isEmpty());
        roles.getItems().setAll(selection.stream().map(Overlay::role).distinct().toList());
        voices.getItems().setAll(selection.stream().map(note -> owners.get(note.id())).filter(Objects::nonNull).distinct().toList());
        lanes.getItems().setAll(selection.stream().map(ReconstructionBulk::laneName).distinct().toList());
        for (var field : List.of(roles, voices, lanes)) field.getSelectionModel().clearSelection();
        var existing = voice.getValue();
        voice.getItems().setAll(targets.stream().filter(candidate -> selection.stream()
                .allMatch(note -> kind(note) == candidate.kind())).toList());
        if (voice.getItems().contains(existing)) voice.setValue(existing);
        if (instrumentType.getValue() == null && !selection.isEmpty()) {
            var first = selection.getFirst();
            var owner = owners.get(first.id());
            instrumentType.setValue(owner != null && owner.assignment() != null ? owner.assignment().laneType()
                    : first.lane() == null ? kind(first) == Kind.drum ? ReconstructionLaneType.DAC : ReconstructionLaneType.FM
                    : playedType.apply(first));
        }
        refreshInstruments();
        updating = false;
        validate();
    }

    private void refreshInstruments() {
        if (data == null) return;
        var chip = instrumentType.getValue();
        String query = instrumentSearch.getText().strip().toLowerCase(Locale.ROOT);
        var current = instrument.getValue();
        var items = data.instruments().stream().filter(item -> item.type() == chip
                && item.toString().toLowerCase(Locale.ROOT).contains(query)).toList();
        if (!items.equals(instrument.getItems())) {
            instrument.getItems().setAll(items);
            if (items.contains(current)) instrument.setValue(current);
        }
        validate();
    }

    private void validate() {
        if (updating || data == null) return;
        String routing = laneIssue(type.getValue(), null, selected);
        String pinning = laneIssue(null, lane.getValue(), selected);
        route.setDisable(selected.isEmpty() || type.getValue() == null || routing != null);
        pin.setDisable(selected.isEmpty() || lane.getValue() == null || pinning != null);
        var candidate = instrument.getValue();
        String auditionIssue = null, assignmentIssue = null;
        if (candidate != null) {
            boolean drumInstrument = candidate.type() == ReconstructionLaneType.NOISE || candidate.type() == ReconstructionLaneType.DAC;
            if (selected.stream().anyMatch(note -> (kind(note) == Kind.drum) != drumInstrument))
                auditionIssue = "This instrument requires " + (drumInstrument ? "MIDI drum notes." : "tonal notes.");
            else if (selected.stream().anyMatch(note -> !note.sounds() || playedType.apply(note) != candidate.type()))
                auditionIssue = "Audition uses allocated notes on the instrument's chip. Apply a compatible lane type first.";
            if (scope.getValue() == Scope.VOICE) {
                var owner = sharedVoice();
                if (owner == null) assignmentIssue = "Select notes from one voice, or create a voice from this selection.";
                else if ((owner.kind() == Kind.drum) != drumInstrument)
                    assignmentIssue = "This instrument's chip must match the voice's tonal or drum kind.";
                else assignmentIssue = laneIssue(candidate.type(), null, selected);
            } else assignmentIssue = auditionIssue;
        }
        assign.setDisable(selected.isEmpty() || candidate == null || assignmentIssue != null);
        audition.setDisable(selected.isEmpty() || candidate == null || auditionIssue != null);
        boolean singlePart = selected.size() > 1 && selected.stream().allMatch(note -> kind(note) == Kind.tonal
                && note.part() == selected.getFirst().part() && data.attack(note.id()) != null && data.attack(note.id()).single());
        collapse.setDisable(!singlePart || selected.stream().map(Overlay::pitch).distinct().count() != 1);
        glide.setDisable(!singlePart || selected.size() != 2 || selected.getFirst().pitch() == selected.getLast().pitch()
                || onsetTick(selected.getFirst()) == onsetTick(selected.getLast()));
        boolean coAttack = singlePart && selected.stream().mapToLong(this::onsetTick).distinct().count() == 1
                && selected.stream().map(Overlay::pitch).distinct().count() == selected.size();
        arpeggio.setDisable(!coAttack);
        chord.setDisable(!coAttack);
        showIssue(routing != null ? routing : pinning != null ? pinning
                : assignmentIssue != null ? assignmentIssue : auditionIssue);
    }

    private long onsetTick(Overlay note) { return Math.round(note.interval().projectStart() * 60); }

    private String laneIssue(ReconstructionLaneType type, String lane, List<Overlay> notes) {
        if (type == null && lane == null) return null;
        for (var note : notes) {
            String error = data.laneIssue(note, type, lane);
            if (error != null) return note.id() + " · " + error;
        }
        return null;
    }

    private void setRole() {
        var value = role.getValue();
        if (value == null || selected.isEmpty()) return;
        if (!value.equals("drop") && selected.stream().anyMatch(note -> (kind(note) == Kind.drum) != value.equals("drums"))) {
            showIssue("The drums role requires a MIDI percussion part. Tonal roles require a tonal part.");
            return;
        }
        notes("setRole", "role", value, "Set role");
    }

    private void move() {
        var target = voice.getValue();
        if (selected.isEmpty() || target == null) return;
        var assignment = target.assignment();
        String invalid = assignment == null ? null : laneIssue(assignment.laneType(), null, selected);
        if (invalid != null) {
            showIssue(invalid);
            return;
        }
        submit.accept(List.of(voiceEdit("move", target.id(), "Move selection to " + target.name(), body -> body.add("noteIds", noteIds()))));
    }

    private void create() {
        String name = voiceName.getText().strip();
        if (name.isEmpty() || selected.isEmpty()) return;
        var kind = kind(selected.getFirst());
        if (selected.stream().anyMatch(note -> kind(note) != kind)) {
            showIssue("Create separate voices for tonal notes and drums.");
            return;
        }
        String id = "studio-voice-" + UUID.randomUUID();
        submit.accept(List.of(voiceEdit("create", id, "Create voice " + name, body -> {
            body.addProperty("name", name);
            body.addProperty("kind", kind.name());
        }), voiceEdit("move", id, "Move selection to " + name, body -> body.add("noteIds", noteIds()))));
        voiceName.clear();
    }

    private void velocity(boolean scale) {
        if (selected.isEmpty()) return;
        try {
            double value = Double.parseDouble(velocity.getText().strip());
            if (!Double.isFinite(value) || (scale ? value < 0 : value != Math.rint(value) || value < 1 || value > 127))
                throw new NumberFormatException();
            var edit = intent("setVelocity", scale ? "Scale selection velocity" : "Set selection velocity");
            edit.addProperty(scale ? "scale" : "velocity", value);
            submit.accept(List.of(edit));
        } catch (NumberFormatException invalid) {
            showIssue(scale ? "Enter a nonnegative scale factor, such as 0.8." : "Enter a whole velocity from 1 to 127.");
        }
    }

    private void assign() {
        var choice = instrument.getValue();
        if (scope.getValue() == Scope.VOICE) {
            var owner = sharedVoice();
            submit.accept(List.of(voiceEdit("assign", owner.id(), "Assign instrument to " + owner.name(), body -> {
                var assignment = new JsonObject();
                assignment.addProperty("laneType", choice.type().name());
                assignment.addProperty("instrument", choice.id());
                body.add("assignment", assignment);
            })));
        } else {
            var edit = intent("noteVoicing", "Assign instrument to selected notes");
            edit.addProperty("laneType", choice.type().name());
            edit.addProperty("instrument", choice.id());
            submit.accept(List.of(edit));
        }
    }

    private Voice sharedVoice() {
        if (selected.isEmpty()) return null;
        var owner = owners.get(selected.getFirst().id());
        return owner != null && selected.stream().allMatch(note -> owner.equals(owners.get(note.id()))) ? owner : null;
    }

    private void notes(String op, String field, String value, String reason) {
        var edit = intent(op, reason + " in Studio");
        edit.addProperty(field, value);
        submit.accept(List.of(edit));
    }

    private JsonObject intent(String op, String reason) {
        var edit = ReconstructionDraft.human(op, reason);
        edit.add("noteIds", noteIds());
        return edit;
    }

    private JsonArray noteIds() {
        var ids = new JsonArray();
        ids().forEach(ids::add);
        return ids;
    }

    private List<String> ids() { return selected.stream().map(Overlay::id).toList(); }

    private static JsonObject voiceEdit(String op, String voice, String reason, Consumer<JsonObject> properties) {
        var edit = ReconstructionDraft.human("voiceEdit", reason);
        var body = new JsonObject();
        body.addProperty("op", op);
        body.addProperty("voiceId", voice);
        properties.accept(body);
        edit.add("voiceEdit", body);
        return edit;
    }

    private Kind kind(Overlay note) {
        var data = this.data.attack(note.id());
        return (data == null ? note.role() == Role.DRUMS : data.drums()) ? Kind.drum : Kind.tonal;
    }

    private void showIssue(String text) {
        issue.setText(text);
        issue.setVisible(text != null);
    }

    private static String laneName(Overlay note) { return note.lane() == null ? ReconstructionCanvas.UNASSIGNED : note.lane(); }
    private static FlowPane row(javafx.scene.Node... nodes) { return new FlowPane(6, 5, nodes); }
    private static Button button(String name, Runnable action) {
        var button = new Button(name);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static <T> ComboBox<T> combo(String prompt, List<T> items) {
        var box = new ComboBox<T>(javafx.collections.FXCollections.observableArrayList(items));
        box.setPromptText(prompt);
        return box;
    }
}
