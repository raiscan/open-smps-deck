package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.NativeFmVoice;
import com.opensmpsdeck.project.NativeFmVoice.Parameter;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Native FM assets with independent parameter and raw-byte drafts. */
final class VoiceEditorPane extends BorderPane {
    private static final int[] REGISTER_OFFSETS = {0, 8, 4, 12};
    private final Consumer<UnaryOperator<StudioProject>> edit;
    private final Consumer<String> error;
    private final ListView<Voice> voices = new ListView<>();
    private final TextField name = new TextField();
    private final TextArea raw = new TextArea();
    private final Map<Parameter, Control> globals = new EnumMap<>(Parameter.class);
    private final List<Map<Parameter, Control>> operators = new ArrayList<>();
    private final Label selected = label("Choose an FM voice", "section");
    private final Label linked = label("", "muted");
    private final Label parameterNotice = label("", "muted"), rawNotice = label("", "muted");
    private final Button add, duplicate, delete, reload, apply, applyRaw;
    private final VBox parameterForm;
    private StudioProject project;
    private Voice parameterBaseline, rawBaseline;
    private String selectedId;
    private boolean updating, initializedSelection;

    VoiceEditorPane(Consumer<UnaryOperator<StudioProject>> edit, Consumer<String> error) {
        this.edit = Objects.requireNonNull(edit);
        this.error = Objects.requireNonNull(error);
        setMinSize(0, 0);
        voices.setId("voice-list"); voices.setPrefHeight(135); voices.setMinHeight(95);
        voices.setPlaceholder(label("No FM voices. Choose New to create a local native patch.", "muted"));
        voices.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(Voice voice, boolean empty) {
                super.updateItem(voice, empty);
                setText(null); setGraphic(null);
                if (!empty && voice != null) {
                    long uses = sourceUses(voice.id());
                    setGraphic(new VBox(3, label(voice.name(), "sequence-name"),
                            label(voice.id() + " · " + uses + " source" + (uses == 1 ? "" : "s"), "muted")));
                }
            }
        });
        voices.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!updating && b != null) selectVoice(b.id());
        });
        add = button("New", "add-voice", this::addVoice);
        duplicate = button("Duplicate", "duplicate-voice", this::duplicateVoice);
        duplicate.setTooltip(new Tooltip("Duplicate the applied voice. Unapplied form values are not part of the copy."));
        delete = button("Delete unused voice", "delete-voice", this::deleteSelected);
        reload = button("Reload voice", "reload-voice", this::reloadVoice);
        FlowPane actions = new FlowPane(8, 8, add, duplicate, delete, reload);
        name.setId("voice-name"); name.setPrefColumnCount(24);
        name.textProperty().addListener((o, a, b) -> changed());
        name.setOnAction(e -> applyParameters());
        FlowPane header = new FlowPane(12, 8, field("Voice name", name));
        for (Parameter parameter : Parameter.values()) if (!parameter.operator()) {
            ComboBox<Integer> control = new ComboBox<>();
            control.getItems().setAll(IntStream.rangeClosed(0, parameter.max()).boxed().toList());
            control.setId("voice-" + key(parameter)); control.setPrefWidth(105);
            control.valueProperty().addListener((o, a, b) -> changed());
            globals.put(parameter, control); header.getChildren().add(field(parameter.label(), control));
        }
        FlowPane operatorCards = new FlowPane(10, 10);
        for (int slot = 0; slot < 4; slot++) {
            Map<Parameter, Control> fields = new EnumMap<>(Parameter.class); operators.add(fields);
            VBox card = new VBox(7, label(String.format(Locale.ROOT, "Native slot %d · YM +%02X", slot, REGISTER_OFFSETS[slot]), "section"));
            card.setPrefWidth(210); card.setMinWidth(190); card.getStyleClass().add("inspector");
            for (Parameter parameter : Parameter.values()) if (parameter.operator()) {
                Control control;
                if (parameter == Parameter.AMPLITUDE_MODULATION || parameter == Parameter.TRACK_VOLUME) {
                    CheckBox check = new CheckBox(parameter == Parameter.TRACK_VOLUME ? "Apply track volume" : parameter.label()); check.setWrapText(true);
                    check.selectedProperty().addListener((o, a, b) -> changed());
                    if (parameter == Parameter.TRACK_VOLUME) check.setTooltip(new Tooltip("Native TL bit 7: add the source track attenuation to this stored operator. Independent of the algorithm; preserved unless you change this flag."));
                    control = check; card.getChildren().add(check);
                } else {
                    TextField text = new TextField(); text.setPrefColumnCount(3); text.setMaxWidth(66);
                    text.setTooltip(new Tooltip(parameter.label() + ": decimal 0–" + parameter.max()));
                    text.textProperty().addListener((o, a, b) -> changed());
                    text.setOnAction(e -> applyParameters()); control = text;
                    Label caption = label(parameter.label(), "muted");
                    HBox row = new HBox(8, caption, text); HBox.setHgrow(caption, Priority.ALWAYS);
                    caption.setMaxWidth(Double.MAX_VALUE); card.getChildren().add(row);
                }
                control.setId("voice-" + slot + "-" + key(parameter)); fields.put(parameter, control);
            }
            operatorCards.getChildren().add(card);
        }
        apply = button("Apply parameters + name", "apply-voice", this::applyParameters);
        Label parameterHelp = label("Parameters edit the native stored slots without reordering. Values are decimal; TL 0 is loudest and 127 is quietest. Apply track volume is an independent TL high-bit flag. Algorithm changes retain those flags and all other native bits.", "help");
        parameterForm = new VBox(10, header, operatorCards, apply, parameterHelp, parameterNotice);
        raw.setId("voice-bytes"); raw.setPrefRowCount(3); raw.setWrapText(true);
        raw.setPromptText("Exactly 25 raw hex bytes in native S3K order");
        raw.textProperty().addListener((o, a, b) -> changed());
        applyRaw = button("Apply raw bytes", "apply-voice-bytes", this::applyRaw);
        Label rawHelp = label("Exactly 25 native bytes, 00–FF, separated by spaces; 0x prefixes are accepted. Order: algorithm/feedback, DT/MUL, RS/AR, AM/D1R, D2R, D1L/RR, TL. Apply raw bytes changes bytes only; the name belongs to the parameter form. These two drafts are independent. Reload voice replaces both drafts with the current asset.", "help");
        VBox inventory=new VBox(10,label("LOCAL FM VOICES","section"),voices,actions);inventory.setMinWidth(150);inventory.setPrefWidth(200);inventory.setMaxWidth(200);
        VBox editor=new VBox(10,selected,linked,parameterForm,new Separator(),label("EXACT NATIVE BYTES","section"),raw,applyRaw,rawHelp,rawNotice);editor.setMinWidth(210);HBox.setHgrow(editor,Priority.ALWAYS);
        HBox body=new HBox(12,inventory,editor);
        VBox content = new VBox(10,body);
        content.setPadding(new Insets(12)); content.setMinWidth(0);
        ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true); scroll.setMinSize(0, 0);
        scroll.getStyleClass().add("editor-scroll"); setCenter(scroll);
        updateState();
    }

    void setProject(StudioProject latest) {
        if (latest == null) {
            boolean previous = updating; updating = true;
            try {
                project = null; initializedSelection = false;
                voices.getItems().clear(); clearEditor();
            } finally { updating = previous; }
            return;
        }
        boolean parameterDraft = parametersChanged(), rawDraft = rawChanged();
        boolean previous = updating; updating = true;
        try {
            project = latest;
            voices.getSelectionModel().clearSelection();
            voices.getItems().setAll(latest == null ? List.of() : latest.voices());
            if (!initializedSelection && latest != null) {
                initializedSelection = true;
                if (!latest.voices().isEmpty()) selectedId = latest.voices().getFirst().id();
            }
            Voice current = findVoice(project, selectedId);
            if (current != null) {
                voices.getSelectionModel().select(current);
                if (parameterBaseline == null || !parameterDraft) fillParameters(current);
                if (rawBaseline == null || !rawDraft) fillRaw(current);
            }
            // A removed selected ID remains a tombstone. Its draft is never
            // transferred to a different list row by an external refresh.
        } finally { updating = previous; }
        updateState();
    }

    void selectVoice(String id) {
        Voice current = findVoice(project, id);
        if (current == null) return;
        boolean previous = updating; updating = true;
        try {
            selectedId = id; initializedSelection = true;
            voices.getSelectionModel().select(current); voices.scrollTo(current);
            fillParameters(current); fillRaw(current);
        } finally { updating = previous; }
        updateState();
    }

    String selectedVoiceId() { return selectedId; }

    boolean ownsFocus(Node focus) {
        for (Node node = focus; node != null; node = node.getParent()) if (node == this) return true;
        return false;
    }

    private void fillParameters(Voice current) {
        boolean previous = updating; updating = true;
        try {
            parameterBaseline = current; name.setText(current.name());
            globals.forEach((parameter, control) -> writeControl(control, NativeFmVoice.read(current, 0, parameter)));
            for (int slot = 0; slot < operators.size(); slot++) {
                int index = slot;
                operators.get(slot).forEach((parameter, control) -> writeControl(control, NativeFmVoice.read(current, index, parameter)));
            }
        } finally { updating = previous; }
    }

    private void fillRaw(Voice current) {
        boolean previous = updating; updating = true;
        try { rawBaseline = current; raw.setText(formatVoiceBytes(current.bytes())); }
        finally { updating = previous; }
    }

    private void reloadVoice() {
        if (findVoice(project, selectedId) == null) {
            report("This voice was removed. Your drafts are retained; select another voice or create a new one."); return;
        }
        selectVoice(selectedId);
    }

    private void addVoice() {
        if (project == null) return;
        Voice created = NativeFmVoice.initial("voice-" + UUID.randomUUID(), "New FM voice");
        submit(p -> ProjectEdits.addVoice(p, created));
        if (findVoice(project, created.id()) != null) selectVoice(created.id());
    }

    private void duplicateVoice() {
        Voice current = findVoice(project, selectedId);
        if (current == null) return;
        Voice duplicate = new Voice("voice-" + UUID.randomUUID(), current.name() + " copy", current.bytes());
        submit(p -> ProjectEdits.addVoice(guardVoice(p, current), duplicate));
        if (findVoice(project, duplicate.id()) != null) selectVoice(duplicate.id());
    }

    void deleteSelected() {
        Voice expected = parameterBaseline;
        if (expected == null || selectedId == null) return;
        try {
            guardVoice(project, expected);
            submit(p -> ProjectEdits.deleteVoice(guardVoice(p, expected), expected.id()));
            if (findVoice(project, expected.id()) == null) clearEditor();
        } catch (IllegalArgumentException e) { report(e.getMessage()); }
    }

    private void clearEditor() {
        boolean previous = updating; updating = true;
        try {
            selectedId = null; parameterBaseline = null; rawBaseline = null;
            voices.getSelectionModel().clearSelection(); name.clear(); raw.clear();
            globals.values().forEach(VoiceEditorPane::clearControl);
            operators.forEach(fields -> fields.values().forEach(VoiceEditorPane::clearControl));
        } finally { updating = previous; }
        updateState();
    }

    private void applyParameters() {
        if (parameterBaseline == null) return;
        try {
            Voice expected = parameterBaseline;
            guardVoice(project, expected);
            Voice updated = new Voice(expected.id(), name.getText(), expected.bytes());
            for (var field : globals.entrySet()) updated = NativeFmVoice.with(updated, 0, field.getKey(), readControl(field.getValue(), field.getKey()));
            for (int slot = 0; slot < operators.size(); slot++)
                for (var field : operators.get(slot).entrySet()) updated = NativeFmVoice.with(updated, slot, field.getKey(), readControl(field.getValue(), field.getKey()));
            Voice replacement = updated;
            submit(p -> ProjectEdits.updateVoice(guardVoice(p, expected), replacement));
            if (replacement.equals(findVoice(project, selectedId))) fillParameters(replacement);
            updateState();
        } catch (IllegalArgumentException e) { report(e.getMessage()); }
    }

    private void applyRaw() {
        if (rawBaseline == null) return;
        try {
            Voice expected = rawBaseline;
            guardVoice(project, expected);
            Voice replacement = new Voice(expected.id(), expected.name(), parseVoiceBytes(raw.getText()));
            submit(p -> ProjectEdits.updateVoice(guardVoice(p, expected), replacement));
            if (replacement.equals(findVoice(project, selectedId))) fillRaw(replacement);
            updateState();
        } catch (IllegalArgumentException e) { report(e.getMessage()); }
    }

    private void submit(UnaryOperator<StudioProject> transform) {
        try { edit.accept(transform); } catch (IllegalArgumentException e) { report(e.getMessage()); }
    }

    private void report(String message) { error.accept(message); }

    private void changed() { if (!updating) updateState(); }

    private void updateState() {
        if (apply == null) return;
        Voice current = findVoice(project, selectedId);
        long uses = sourceUses(selectedId);
        selected.setText(selectedId == null ? "Choose an FM voice" : selectedId + (current == null ? " · removed" : ""));
        Set<String> sources = project == null || selectedId == null ? Set.of() : project.sequences().stream()
                .filter(s -> Objects.equals(s.voiceId(), selectedId)).map(s -> s.id()).collect(Collectors.toSet());
        long clips = project == null ? 0 : project.clips().stream().filter(c -> sources.contains(c.sequenceId())).count();
        linked.setText(selectedId == null ? "Voices are linked assets. Assign them through a source's voice selector."
                : "Linked FM voice · " + uses + " source" + (uses == 1 ? "" : "s") + " · " + clips + " clip instance" + (clips == 1 ? "" : "s") + ". Editing changes every reference; deletion requires no remaining references.");
        parameterNotice.setText(draftNotice(current, parameterBaseline, parametersChanged(), "Parameter / name"));
        rawNotice.setText(draftNotice(current, rawBaseline, rawChanged(), "Raw bytes"));
        add.setDisable(project == null); duplicate.setDisable(current == null);
        delete.setDisable(current == null || uses > 0); reload.setDisable(selectedId == null);
        parameterForm.setDisable(parameterBaseline == null); raw.setDisable(rawBaseline == null); applyRaw.setDisable(rawBaseline == null);
    }

    private static String draftNotice(Voice current, Voice baseline, boolean changed, String kind) {
        if (baseline == null) return "";
        if (current == null) return kind + " draft retained: the selected voice was removed. Select a voice or create a new one.";
        if (!current.equals(baseline)) return kind + " draft retained: the voice changed. Reload voice / reselect it before applying. Reload replaces both drafts.";
        return changed ? kind + " draft is unapplied. Apply commits it; typing alone is not saved." : kind + " are current. Apply preserves all untouched native bits.";
    }

    private boolean parametersChanged() {
        if (parameterBaseline == null) return false;
        if (!Objects.equals(name.getText(), parameterBaseline.name())) return true;
        for (var field : globals.entrySet()) if (!controlMatches(field.getValue(), NativeFmVoice.read(parameterBaseline, 0, field.getKey()))) return true;
        for (int slot = 0; slot < operators.size(); slot++)
            for (var field : operators.get(slot).entrySet()) if (!controlMatches(field.getValue(), NativeFmVoice.read(parameterBaseline, slot, field.getKey()))) return true;
        return false;
    }

    private boolean rawChanged() {
        return rawBaseline != null && !Objects.equals(raw.getText(), formatVoiceBytes(rawBaseline.bytes()));
    }

    private long sourceUses(String id) {
        return project == null || id == null ? 0 : project.sequences().stream().filter(s -> Objects.equals(s.voiceId(), id)).count();
    }

    static StudioProject guardVoice(StudioProject project, Voice expected) {
        Voice current = expected == null ? null : findVoice(project, expected.id());
        if (current == null || !current.equals(expected)) throw new IllegalArgumentException("Voice changed or was removed while this form was open. Your draft is retained; Reload voice or reselect it before applying.");
        return project;
    }

    static List<Integer> parseVoiceBytes(String text) {
        List<Integer> bytes = new ArrayList<>();
        if (text != null && !text.isBlank()) for (String token : text.trim().split("\\s+")) {
            String digits = token.startsWith("0x") || token.startsWith("0X") ? token.substring(2) : token;
            if (!digits.matches("[0-9a-fA-F]{1,2}")) throw new IllegalArgumentException("Invalid voice hex byte '" + token + "'. Use bytes 00–FF separated by spaces.");
            bytes.add(Integer.parseInt(digits, 16));
        }
        if (bytes.size() != 25) throw new IllegalArgumentException("A native FM voice requires exactly 25 bytes; this draft has " + bytes.size() + ".");
        return List.copyOf(bytes);
    }

    static String formatVoiceBytes(List<Integer> bytes) {
        return String.join(" ", bytes.stream().map(b -> String.format(Locale.ROOT, "%02X", b)).toList());
    }

    private static Voice findVoice(StudioProject project, String id) {
        return project == null || id == null ? null : project.voices().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
    }

    private static boolean controlMatches(Control control, int value) {
        if (control instanceof TextField text) return text.getText().equals(Integer.toString(value));
        if (control instanceof CheckBox check) return check.isSelected() == (value != 0);
        return Objects.equals(((ComboBox<?>) control).getValue(), value);
    }

    private static int readControl(Control control, Parameter parameter) {
        int value;
        if (control instanceof CheckBox check) value = check.isSelected() ? 1 : 0;
        else if (control instanceof ComboBox<?> combo && combo.getValue() instanceof Integer integer) value = integer;
        else if (control instanceof TextField text) {
            try { value = Integer.parseInt(text.getText().trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(parameter.label() + " must be a decimal integer from 0 to " + parameter.max() + "."); }
        } else throw new IllegalArgumentException("Choose " + parameter.label() + ".");
        if (value < 0 || value > parameter.max()) throw new IllegalArgumentException(parameter.label() + " must be from 0 to " + parameter.max() + ".");
        return value;
    }

    private static void clearControl(Control control) {
        if (control instanceof TextField text) text.clear();
        else if (control instanceof CheckBox check) check.setSelected(false);
        else ((ComboBox<?>) control).setValue(null);
    }

    @SuppressWarnings("unchecked")
    private static void writeControl(Control control, int value) {
        if (control instanceof TextField text) text.setText(Integer.toString(value));
        else if (control instanceof CheckBox check) check.setSelected(value != 0);
        else ((ComboBox<Integer>) control).setValue(value);
    }

    private static String key(Parameter parameter) { return parameter.name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    private static Button button(String text, String id, Runnable action) {
        Button button = new Button(text); button.setId(id); button.setOnAction(e -> action.run()); return button;
    }

    private static VBox field(String text, Control control) {
        VBox box = new VBox(4, label(text, "muted"), control); box.setMinWidth(0); return box;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text); label.getStyleClass().add(style); label.setWrapText(true); label.setMinWidth(0); return label;
    }
}
