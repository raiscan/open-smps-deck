package com.opensmpsdeck.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionEditing;
import com.opensmpsdeck.headless.ReconstructionEditing.Instrument;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.headless.ReconstructionLaneType;
import com.opensmpsdeck.headless.ReconstructionLayers;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.headless.ReconstructionVoicePlan;
import com.opensmpsdeck.headless.ReconstructionVoicePlan.Kind;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
    private final Button retrigger = new Button("Queue retrigger");
    private final TextField retriggerRate = new TextField("83");
    private final TextField retriggerCount = new TextField("4");
    private final TextField retriggerGate = new TextField("60");
    private final Button collapse = new Button("Collapse repeats");
    private final Button glide = new Button("Merge with glide");
    private final Button arpeggio = new Button("Arpeggio");
    private final Button chord = new Button("Chord instrument");
    private final TextField arpeggioRate = new TextField("2");
    private final ComboBox<String> arpeggioOrder = combo("Arpeggio order", List.of("up", "down", "up-down"));
    private final VBox actions = new VBox(10);
    private final ComboBox<String> layerMode = combo("Layer mode", List.of("Echo", "Chorus", "Double", "Off"));
    private final ComboBox<ReconstructionLaneType> layerTarget = combo("Target",
            List.of(ReconstructionLaneType.FM, ReconstructionLaneType.PSG));
    private final List<CheckBox> layerMasks = List.of("pads", "leadEcho", "psgDouble", "fmDouble")
            .stream().map(CheckBox::new).toList();
    private final TextField layerDelay = new TextField(Integer.toString(ReconstructionLayers.DEFAULT_ECHO_DELAY_FRAMES));
    private final TextField echoLevel = new TextField(layerDbText(
            ReconstructionLayers.DEFAULT_ECHO_ATTENUATION - ReconstructionLayers.ECHO_REFERENCE_TL));
    private final CheckBox echoTrim = new CheckBox("Trim from " + ReconstructionLayers.ECHO_REFERENCE_TL + " TL echo");
    private final TextField layerLevel = new TextField("-9");
    private final TextField layerDetune = new TextField("-3");
    private final ComboBox<String> layerOctave = combo("Interval", List.of("Unison", "Octave up", "Octave down"));

    private final Consumer<List<JsonObject>> submit;
    private List<Overlay> selected = List.of();
    private java.util.Map<String, Voice> owners = java.util.Map.of();
    private ReconstructionEditing data;
    private java.util.function.Function<Overlay, ReconstructionLaneType> playedType;
    private boolean updating;

    ReconstructionBulk(Consumer<Predicate<Overlay>> filter, Consumer<List<JsonObject>> submit,
                       BiConsumer<List<String>, Instrument> play, Runnable stop, Consumer<JsonObject> previewLayer) {
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
        retrigger.setId("reconstruction-queue-retrigger");
        retrigger.setOnAction(event -> retrigger());
        for (var field : List.of(retriggerRate, retriggerCount, retriggerGate)) field.setPrefColumnCount(4);
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
                layerControls(previewLayer, stop),
                row(role, button("Set role", this::setRole)),
                row(voice, button("Move to voice", this::move)),
                row(voiceName, button("Create voice", this::create)),
                row(type, route), row(lane, pin),
                row(velocity, button("Set", () -> velocity(false)), button("Scale", () -> velocity(true))),
                new SeparatorLabel("Instrument"),
                row(scope, instrumentType), instrumentSearch, instrument,
                row(assign, audition, button("Stop audition", stop)),
                new SeparatorLabel("Retrigger"),
                row(new Label("Interval (ms)"), retriggerRate, new Label("Count"), retriggerCount),
                row(new Label("Gate (ms)"), retriggerGate, retrigger),
                new SeparatorLabel("Combine notes"),
                row(collapse, glide), row(arpeggioOrder, arpeggioRate),
                row(arpeggio, chord));
        getChildren().addAll(count, row(roles, voices, lanes), issue, actions);
    }

    private VBox layerControls(Consumer<JsonObject> preview, Runnable stop) {
        layerMode.setId("reconstruction-layer-mode");
        layerTarget.setId("reconstruction-layer-target");
        layerDelay.setId("reconstruction-layer-delay");
        echoLevel.setId("reconstruction-layer-level");
        echoTrim.setId("reconstruction-layer-echo-trim");
        layerLevel.setId("reconstruction-layer-copy-level");
        echoTrim.setSelected(true);
        layerMode.setValue("Echo");
        layerTarget.setValue(ReconstructionLaneType.FM);
        layerOctave.setValue("Unison");
        for (var field : List.of(layerDelay, echoLevel, layerLevel, layerDetune)) field.setPrefColumnCount(4);
        var delayHint = new Label();
        var echoHint = new Label();
        var levelHint = new Label();
        delayHint.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> delayReadout(layerDelay.getText()), layerDelay.textProperty()));
        echoHint.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> levelReadout(echoLevel.getText(), echoReference()), echoLevel.textProperty(), echoTrim.selectedProperty()));
        levelHint.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> levelReadout(layerLevel.getText(), 0), layerLevel.textProperty()));
        echoTrim.selectedProperty().addListener((ignored, previous, selected) -> {
            try {
                double value = Double.parseDouble(echoLevel.getText().strip());
                double referenceDb = ReconstructionLayers.ECHO_REFERENCE_TL * ReconstructionLayers.DB_PER_TL;
                if (Double.isFinite(value)) echoLevel.setText(java.math.BigDecimal
                        .valueOf(value + (selected ? referenceDb : -referenceDb)).stripTrailingZeros().toPlainString());
            } catch (NumberFormatException ignoredValue) { }
        });
        var echo = new VBox(5, row(new Label("Delay (frames)"), layerDelay, delayHint),
                row(new Label("Level (dB)"), echoLevel, echoHint), echoTrim);
        var level = row(new Label("Level (dB)"), layerLevel, levelHint);
        var detune = row(new Label("Detune (FM units)"), layerDetune);
        var octave = row(new Label("Interval"), layerOctave);
        echo.visibleProperty().bind(layerMode.valueProperty().isEqualTo("Echo"));
        level.visibleProperty().bind(layerMode.valueProperty().isNotEqualTo("Off")
                .and(layerMode.valueProperty().isNotEqualTo("Echo")));
        detune.visibleProperty().bind(layerMode.valueProperty().isEqualTo("Chorus"));
        octave.visibleProperty().bind(layerMode.valueProperty().isEqualTo("Double"));
        layerTarget.disableProperty().bind(layerMode.valueProperty().isEqualTo("Off"));
        var masks = new VBox(4, new Label("Mask policies (clear all to disable all layers)"),
                row(layerMasks.toArray(javafx.scene.Node[]::new)));
        masks.visibleProperty().bind(layerMode.valueProperty().isEqualTo("Off"));
        for (var control : List.of(echo, level, detune, octave, masks))
            control.managedProperty().bind(control.visibleProperty());
        var queue = button("Queue layer", () -> layer(edit -> submit.accept(List.of(edit))));
        queue.setId("reconstruction-queue-layer");
        var audition = button("Preview A/B", () -> layer(preview));
        audition.setId("reconstruction-preview-layer");
        return new VBox(6, new SeparatorLabel("Layer"), row(layerMode, new Label("Target"), layerTarget),
                echo, level, detune, octave, masks, row(queue, audition, button("Stop", stop)));
    }

    private static String layerDbText(int attenuation) {
        return java.math.BigDecimal.valueOf(-attenuation * ReconstructionLayers.DB_PER_TL)
                .stripTrailingZeros().toPlainString();
    }

    private int echoReference() {
        return echoTrim.isSelected() ? ReconstructionLayers.ECHO_REFERENCE_TL : 0;
    }

    private static String delayReadout(String text) {
        try {
            int frames = Integer.parseInt(text.strip());
            if (frames >= 1 && frames <= 600) return String.format(Locale.ROOT, "%.1f ms", frames * 1000.0 / 60);
        } catch (NumberFormatException ignored) { }
        return "";
    }

    private static int attenuation(String text, int reference) {
        double value = Double.parseDouble(text.strip());
        double tl = reference - value / ReconstructionLayers.DB_PER_TL;
        if (!Double.isFinite(tl) || tl < 0 || tl > 127)
            throw new NumberFormatException("Attenuation outside 0–127 TL steps");
        return (int) Math.round(tl);
    }

    private static String levelReadout(String text, int reference) {
        try {
            return attenuation(text, reference) + " TL total";
        } catch (NumberFormatException invalid) {
            return "";
        }
    }

    private void retrigger() {
        if (selected.size() != 1) return;
        var edit = ReconstructionDraft.human("retrigger", "Repeat the selected attack");
        edit.addProperty("noteId", selected.getFirst().id());
        edit.addProperty("timeBase", "project");
        try {
            edit.addProperty("rate", Double.parseDouble(retriggerRate.getText().strip()) / 1000);
            edit.addProperty("count", Integer.parseInt(retriggerCount.getText().strip()));
            edit.addProperty("gate", Double.parseDouble(retriggerGate.getText().strip()) / 1000);
        } catch (NumberFormatException invalid) {
            showIssue("Enter the interval and gate in milliseconds and a whole number of attacks.");
            return;
        }
        String invalid = ReconstructionReview.validationIssue(edit);
        showIssue(invalid);
        if (invalid == null) submit.accept(List.of(edit));
    }

    private void layer(Consumer<JsonObject> action) {
        if (selected.isEmpty()) return;
        String mode = layerMode.getValue();
        var edit = intent("layer", mode.equals("Off") ? "Disable layers on selected notes" : "Add " + mode.toLowerCase(Locale.ROOT));
        edit.addProperty("mode", mode.toLowerCase(Locale.ROOT));
        try {
            if (!mode.equals("Off")) {
                edit.addProperty("laneType", layerTarget.getValue().name());
                if (mode.equals("Echo") && echoTrim.isSelected())
                    edit.addProperty("attenuation", attenuation(echoLevel.getText(), echoReference()));
                else edit.addProperty("levelDb", Double.parseDouble(
                        (mode.equals("Echo") ? echoLevel : layerLevel).getText().strip()));
            }
            switch (mode) {
                case "Echo" -> edit.addProperty("delayFrames", Integer.parseInt(layerDelay.getText().strip()));
                case "Off" -> {
                    var policies = new JsonArray();
                    layerMasks.stream().filter(CheckBox::isSelected).map(CheckBox::getText).forEach(policies::add);
                    if (!policies.isEmpty()) edit.add("policies", policies);
                }
                case "Chorus" -> edit.addProperty("detune", Integer.parseInt(layerDetune.getText().strip()));
                case "Double" -> edit.addProperty("octave", layerOctave.getSelectionModel().getSelectedIndex() == 2 ? -1
                        : layerOctave.getSelectionModel().getSelectedIndex());
                default -> { }
            }
        } catch (NumberFormatException invalid) {
            showIssue("Enter a level within 0–127 total TL steps and whole numbers for delay and detune.");
            return;
        }
        String invalid = ReconstructionReview.validationIssue(edit);
        showIssue(invalid);
        if (invalid == null) action.accept(edit);
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
        retrigger.setDisable(selected.size() != 1 || kind(selected.getFirst()) != Kind.tonal
                || data.attack(selected.getFirst().id()) == null || !data.attack(selected.getFirst().id()).single());
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
        ids().stream().distinct().forEach(ids::add);
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
