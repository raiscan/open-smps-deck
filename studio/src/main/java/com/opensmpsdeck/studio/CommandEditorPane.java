package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Native events and source settings use the document's common edit/undo path. */
final class CommandEditorPane extends BorderPane {
    Consumer<String> voiceEditor=ignored->{},envelopeEditor=ignored->{};
    private final Consumer<UnaryOperator<StudioProject>> edit;
    private final Consumer<String> error;
    private final TableView<CommandEvent> table = new TableView<>();
    private final ComboBox<NativeCommand> command = new ComboBox<>();
    private final ComboBox<String> eventAsset = new ComboBox<>();
    private final VBox eventAssetField = field("Command asset", eventAsset);
    private final TextField tick = new TextField("0"), bytes = new TextField();
    private final ComboBox<String> voice = new ComboBox<>(), repeat = new ComboBox<>();
    private final ComboBox<String> psgEnvelope = new ComboBox<>(), modEnvelope = new ComboBox<>();
    private final TextField volume = new TextField();
    private final Label heading = label("SOURCE COMMANDS", "section");
    private final Label linked = label("Choose a source", "muted");
    private final Label help = label("", "help"), draftNotice = label("", "muted");
    private final Label settingsNotice = label("", "muted");
    private final Button apply, delete, up, down;
    private StudioProject project;
    private Sequence source, commandBaseline, settingsBaseline;
    private CommandEvent originalCommand;
    private NativeCommand emptyFormType;
    private String sequenceId, selectedId;
    private boolean updating;

    CommandEditorPane(Consumer<UnaryOperator<StudioProject>> edit, Consumer<String> error) {
        this.edit = Objects.requireNonNull(edit);
        this.error = Objects.requireNonNull(error);
        setMinSize(0, 0);
        table.setId("command-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<CommandEvent, String> tickColumn = column("Tick", e -> Integer.toString(e.tick()));
        TableColumn<CommandEvent, String> commandColumn = column("Command", e -> e.command().label());
        TableColumn<CommandEvent, String> bytesColumn = column("Bytes / asset", this::commandBytes);
        tickColumn.setPrefWidth(65); commandColumn.setPrefWidth(180); bytesColumn.setPrefWidth(200);
        table.getColumns().addAll(List.of(tickColumn, commandColumn, bytesColumn));
        table.setPlaceholder(label("No native events. Fill the form and choose Add.", "muted"));
        table.setPrefHeight(220); table.setMinHeight(120);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!updating && b != null) loadCommand(b);
        });
        command.setId("command-type"); command.setPrefWidth(240); command.setMaxWidth(Double.MAX_VALUE);
        command.setConverter(new StringConverter<>() {
            @Override public String toString(NativeCommand value) {
                return value == null ? "" : value.label() + " · " + commandOpcode(value);
            }
            @Override public NativeCommand fromString(String value) { throw new UnsupportedOperationException(); }
        });
        tick.setId("command-tick"); tick.setPrefColumnCount(6);
        bytes.setId("command-bytes"); bytes.setPrefColumnCount(18); bytes.setPromptText("Operands only: C0 or 00 01 01 04");
        configureAssetPicker(eventAsset, "command-asset", () -> assetKind(command.getValue()) == NativeCommand.Asset.VOICE
                ? "Choose an FM voice" : "Off",
                id -> assetName(assetKind(command.getValue()), id));
        eventAssetField.managedProperty().bind(eventAssetField.visibleProperty());
        command.valueProperty().addListener((o, a, b) -> {
            if (!updating) {
                bytes.setText(b == null ? "" : hexArguments(b.defaultArguments()));
                var retainedAsset = assetKind(a) == assetKind(b) ? eventAsset.getValue() : null;
                updateEventAssetChoices(retainedAsset);
                updateHelp(); updateNotices();
            }
        });
        tick.textProperty().addListener((o, a, b) -> { if (!updating) updateNotices(); });
        bytes.textProperty().addListener((o, a, b) -> { if (!updating) updateNotices(); });
        Button add = button("Add", "add-command", this::addCommand);
        apply = button("Apply", "apply-command", this::applyCommand);
        delete = button("Delete", "delete-command", this::deleteSelected);
        up = button("Up", "command-up", () -> moveSelected(-1));
        down = button("Down", "command-down", () -> moveSelected(1));
        Button fresh = button("New form", "new-command", this::newForm);
        Button reloadEvent = button("Reload event", "reload-command", () -> {
            CommandEvent current = findCommand(selectedId);
            if (current == null) report("That event is no longer present. Choose New form or select an event.");
            else loadCommand(current);
        });
        FlowPane eventFields = new FlowPane(10, 8, field("Native command", command), field("Tick (decimal)", tick), field("Raw operand bytes (hex)", bytes), eventAssetField);
        FlowPane eventButtons = new FlowPane(8, 8, add, apply, delete, up, down, fresh, reloadEvent);
        Label ordering = label("At the same tick, authored row order is execution order. Up / Down changes that order.", "muted");
        Label inputGuide = label("Operands are raw hex bytes 00–FF, separated by spaces (0x prefixes accepted). The command and asset selectors supply their bytes. Press Enter or Apply to commit a draft; typing alone is not saved.", "muted");
        tick.setOnAction(e -> commitForm()); bytes.setOnAction(e -> commitForm());

        voice.setId("source-voice"); voice.setPrefWidth(240); voice.setMaxWidth(Double.MAX_VALUE);
        voice.setConverter(new StringConverter<>() {
            @Override public String toString(String id) {
                if (id == null) return source != null && source.type().equals("PSG") ? "PSG · no FM voice" : "";
                return project == null ? id : project.voices().stream().filter(v -> v.id().equals(id))
                        .findFirst().map(v -> v.name() + " · " + v.id()).orElse(id + " (unavailable)");
            }
            @Override public String fromString(String value) { return value; }
        });
        volume.setId("source-volume"); volume.setPrefColumnCount(6);
        repeat.setId("repeat-state"); repeat.getItems().setAll("RESET", "CARRY"); repeat.setPrefWidth(145);
        repeat.setTooltip(new Tooltip("RESET restores native state at each repetition. CARRY retains it within a clip; independent clips always reset."));
        voice.valueProperty().addListener((o, a, b) -> { if (!updating) updateNotices(); });
        volume.textProperty().addListener((o, a, b) -> { if (!updating) updateNotices(); });
        repeat.valueProperty().addListener((o, a, b) -> { if (!updating) updateNotices(); });
        configureEnvelope(psgEnvelope,"source-psg-envelope");configureEnvelope(modEnvelope,"source-mod-envelope");
        volume.setOnAction(e -> applySource());
        FlowPane settings = new FlowPane(10, 8, field("Source voice", voice), field("Volume (0–127)", volume), field("Repeat state", repeat));
        FlowPane settingButtons = new FlowPane(8, 8, button("Apply source", "apply-source", this::applySource),
                button("Reload source settings", "reload-source", () -> { if (source != null) loadSettings(source); }));
        Button editVoice=button("Edit selected voice","edit-source-voice",()->voiceEditor.accept(voice.getValue()));editVoice.disableProperty().bind(voice.disableProperty());
        Button editPsg=button("Edit PSG envelope","edit-source-psg-envelope",()->envelopeEditor.accept(psgEnvelope.getValue()));editPsg.disableProperty().bind(psgEnvelope.disableProperty());
        Button editMod=button("Edit modulation envelope","edit-source-mod-envelope",()->envelopeEditor.accept(modEnvelope.getValue()));editMod.disableProperty().bind(modEnvelope.disableProperty());
        FlowPane assetLinks=new FlowPane(8,8,editVoice,editPsg,editMod);
        Label repeatHelp = label("RESET restores supported native state on every repetition. CARRY retains it between repetitions of one clip. Each independent clip always starts reset.", "muted");
        FlowPane envelopeSettings=new FlowPane(10,8,field("PSG volume envelope",psgEnvelope),field("Modulation envelope",modEnvelope));
        VBox contents = new VBox(10, heading, linked, table, ordering, eventFields, eventButtons, help, inputGuide,
                draftNotice, new Separator(), label("LINKED SOURCE SETTINGS", "section"), settings, envelopeSettings, settingButtons, assetLinks, repeatHelp, settingsNotice);
        contents.setPadding(new Insets(12)); contents.setMinWidth(0);
        ScrollPane scroll = new ScrollPane(contents); scroll.setFitToWidth(true); scroll.setMinSize(0, 0);
        scroll.getStyleClass().add("editor-scroll"); setCenter(scroll);
        setSource(null, null);
    }

    void setSource(StudioProject project, String sequenceId) {
        Sequence next = findSource(project, sequenceId);
        boolean same = source != null && next != null && Objects.equals(this.sequenceId, sequenceId);
        boolean commandDraft = same && commandDraftChanged();
        boolean settingsDraft = same && settingsDraftChanged();
        updating = true;
        try {
            String previousEventAsset = eventAsset.getValue();
            this.project = project; this.sequenceId = sequenceId; source = next;
            table.getSelectionModel().clearSelection();
            table.getItems().setAll(next == null ? List.of() : next.commands());
            NativeCommand previousType = command.getValue();
            command.getItems().setAll(Arrays.stream(NativeCommand.values()).filter(c -> next == null || c.supports(next.type())).toList());
            command.setValue((same && (commandDraft || selectedId != null)) || command.getItems().contains(previousType) ? previousType : command.getItems().getFirst());
            updateEventAssetChoices(same ? previousEventAsset : null);
            String previousVoice = voice.getValue();
            voice.getItems().setAll(project == null ? List.of() : project.voices().stream().map(Voice::id).toList());
            voice.setValue(previousVoice); voice.setDisable(next == null || !next.type().equals("FM"));
            updateEnvelopeChoices(psgEnvelope,"PSG_VOLUME");updateEnvelopeChoices(modEnvelope,"MODULATION");
            psgEnvelope.setDisable(next==null||!next.type().equals("PSG"));modEnvelope.setDisable(next==null);
            heading.setText(next == null ? "SOURCE COMMANDS" : next.name().toUpperCase(Locale.ROOT) + " · COMMANDS");
            long count = next == null ? 0 : project.clips().stream().filter(c -> c.sequenceId().equals(next.id())).count();
            linked.setText(next == null ? "Choose a source in the sequence browser" : "Linked source · " + count + " clip instance" + (count == 1 ? "" : "s") + " · event, voice, volume and repeat edits affect every linked clip");
            if (!same) {
                selectedId = null; originalCommand = null; commandBaseline = next; emptyFormType = command.getValue();
                tick.setText("0"); bytes.setText(hexArguments(command.getValue().defaultArguments()));
                loadSettings(next);
            } else {
                CommandEvent current = findCommand(selectedId);
                if (current != null) {
                    table.getSelectionModel().select(current);
                    if (!commandDraft) fillCommand(current);
                } else if (selectedId == null && !commandDraft) commandBaseline = next;
                if (!settingsDraft) loadSettings(next);
            }
            setDisable(next == null); updateHelp(); updateNotices();
        } finally { updating = false; }
    }

    boolean ownsFocus(Node focus) {
        for (Node node = focus; node != null; node = node.getParent()) if (node == this) return true;
        return false;
    }

    String selectedCommandId() { return selectedId; }

    void selectCommand(String id) {
        CommandEvent event = findCommand(id);
        if (event == null) return;
        updating = true;
        try { table.getSelectionModel().select(event); table.scrollTo(event); fillCommand(event); }
        finally { updating = false; }
        updateHelp(); updateNotices();
    }

    private void loadCommand(CommandEvent event) {
        boolean previous = updating; updating = true;
        try { fillCommand(event); } finally { updating = previous; }
        updateHelp(); updateNotices();
    }

    private void fillCommand(CommandEvent event) {
        selectedId = event.id(); originalCommand = event; commandBaseline = source;
        command.setValue(event.command()); tick.setText(Integer.toString(event.tick())); bytes.setText(hexArguments(event.arguments()));
        updateEventAssetChoices(event.assetId());
    }

    private void newForm() {
        if (source == null) return;
        updating = true;
        try {
            table.getSelectionModel().clearSelection(); selectedId = null; originalCommand = null; commandBaseline = source;
            if (!command.getItems().contains(command.getValue())) command.setValue(command.getItems().getFirst());
            emptyFormType = command.getValue();
            tick.setText("0"); bytes.setText(hexArguments(command.getValue().defaultArguments()));
            updateEventAssetChoices(null);
        } finally { updating = false; }
        updateHelp(); updateNotices();
    }

    private void loadSettings(Sequence latest) {
        boolean previous = updating; updating = true;
        try {
            settingsBaseline = latest;
            voice.setValue(latest == null ? null : latest.voiceId());
            volume.setText(latest == null ? "" : Integer.toString(latest.volume()));
            repeat.setValue(latest == null ? "RESET" : latest.repeatState());
            psgEnvelope.setValue(latest==null?null:latest.psgEnvelopeId());modEnvelope.setValue(latest==null?null:latest.modulationEnvelopeId());
        } finally { updating = previous; }
        updateNotices();
    }

    private void commitForm() { if (selectedId == null) addCommand(); else applyCommand(); }

    private void addCommand() {
        if (source == null) return;
        try {
            Sequence expected = commandBaseline;
            CommandEvent event = formEvent("command-" + UUID.randomUUID());
            guardSource(project, expected);
            submit(p -> ProjectEdits.addCommand(guardSource(p, expected), expected.id(), event));
            if (findCommand(event.id()) != null) selectCommand(event.id());
        } catch (IllegalArgumentException e) { report(e.getMessage()); }
    }

    private void applyCommand() {
        if (source == null || selectedId == null) { report("Select an event to apply changes, or choose Add."); return; }
        try {
            Sequence expected = commandBaseline;
            CommandEvent event = formEvent(selectedId);
            guardSource(project, expected);
            submit(p -> ProjectEdits.updateCommand(guardSource(p, expected), expected.id(), event));
            if (event.equals(findCommand(event.id()))) selectCommand(event.id());
        } catch (IllegalArgumentException e) { report(e.getMessage()); }
    }

    void deleteSelected() {
        if (source == null || selectedId == null) return;
        if (findCommand(selectedId) == null) { report("That event was removed. Your draft is retained; select an event or choose New form."); return; }
        Sequence expected = commandBaseline; String id = selectedId;
        submit(p -> ProjectEdits.deleteCommand(guardSource(p, expected), expected.id(), id));
        if (source != null && findCommand(id) == null) newForm();
    }

    private void moveSelected(int direction) {
        if (source == null || selectedId == null) return;
        Sequence expected = commandBaseline; String id = selectedId;
        int before = commandIndex(source, id);
        submit(p -> ProjectEdits.moveCommand(guardSource(p, expected), expected.id(), id, direction));
        if (source != null && commandIndex(source, id) != before) { commandBaseline = source; updateNotices(); }
    }

    private void applySource() {
        if (source == null || settingsBaseline == null) return;
        try {
            int newVolume = Integer.parseInt(volume.getText().trim());
            if (newVolume < 0 || newVolume > 127) throw new IllegalArgumentException("Source volume must be a decimal integer from 0 to 127.");
            Sequence expected = settingsBaseline;
            String newVoice = source.type().equals("FM") ? voice.getValue() : null;
            String newRepeat = repeat.getValue();
            String newPsg=source.type().equals("PSG")?psgEnvelope.getValue():null,newMod=modEnvelope.getValue();
            Sequence intended = findSource(sourceSettingsFromDraft(project, expected, newVoice, newVolume, newRepeat,newPsg,newMod), expected.id());
            submit(p -> sourceSettingsFromDraft(p, expected, newVoice, newVolume, newRepeat,newPsg,newMod));
            if (source != null && source.equals(intended)) loadSettings(source);
        } catch (NumberFormatException e) { report("Source volume must be a decimal integer from 0 to 127."); }
        catch (IllegalArgumentException e) { report(e.getMessage()); }
    }

    private CommandEvent formEvent(String id) {
        int at;
        try { at = Integer.parseInt(tick.getText().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Command tick must be a decimal integer within the source."); }
        NativeCommand type = command.getValue();
        if (type == null) throw new IllegalArgumentException("Choose a native command.");
        String assetId = type.asset() == NativeCommand.Asset.NONE ? null : eventAsset.getValue();
        if (type.asset() == NativeCommand.Asset.VOICE && assetId == null)
            throw new IllegalArgumentException("Choose an FM voice for this command.");
        return new CommandEvent(id, at, type, parseHexArguments(bytes.getText()), assetId);
    }

    private void submit(UnaryOperator<StudioProject> transform) {
        try { edit.accept(transform); } catch (IllegalArgumentException e) { report(e.getMessage()); }
    }

    private void report(String message) { error.accept(message); }

    private void updateHelp() {
        NativeCommand type = command.getValue();
        help.setText(type == null ? "" : "Raw operand bytes: " + type.argumentCount() + ". " + type.help() + (type == NativeCommand.MODULATION && project != null
                && project.formatVersion() < 4 ? " This project version requires an explicit note attack tick." : ""));
    }

    private void updateNotices() {
        if (apply == null) return;
        boolean removed = selectedId != null && findCommand(selectedId) == null;
        boolean stale = commandBaseline != null && source != null && !commandBaseline.equals(source);
        draftNotice.setText(removed ? "Selected event was removed. Your draft is retained; select an event or choose New form."
                : stale ? "Source changed while this form was open. Your draft is retained; reselect the event / Reload event, or choose New form before applying."
                : commandDraftChanged() ? "Unapplied event draft · Enter / Apply commits changes; Add inserts a new event."
                : "Choose an event to edit it, or fill the form and Add.");
        settingsNotice.setText(settingsDraftChanged() ? "Unapplied source settings · Apply source commits these values. Changed fields are checked against the current source; untouched fields keep their latest values." : "");
        apply.setDisable(selectedId == null); delete.setDisable(selectedId == null);
        int index = source == null ? -1 : commandIndex(source, selectedId);
        up.setDisable(index <= 0); down.setDisable(index < 0 || index >= table.getItems().size() - 1);
    }

    private boolean commandDraftChanged() {
        NativeCommand initialType = originalCommand == null ? emptyFormType : originalCommand.command();
        int initialTick = originalCommand == null ? 0 : originalCommand.tick();
        List<Integer> initialBytes = originalCommand == null && initialType != null ? initialType.defaultArguments()
                : originalCommand == null ? List.of() : originalCommand.arguments();
        return command.getValue() != initialType || !tick.getText().trim().equals(Integer.toString(initialTick))
                || !bytes.getText().trim().equalsIgnoreCase(hexArguments(initialBytes))
                || !Objects.equals(eventAsset.getValue(), originalCommand == null ? null : originalCommand.assetId());
    }

    private boolean settingsDraftChanged() {
        return settingsBaseline != null && (!Objects.equals(voice.getValue(), settingsBaseline.voiceId())
                || !volume.getText().trim().equals(Integer.toString(settingsBaseline.volume()))
                || !Objects.equals(repeat.getValue(), settingsBaseline.repeatState())
                || !Objects.equals(psgEnvelope.getValue(),settingsBaseline.psgEnvelopeId())
                || !Objects.equals(modEnvelope.getValue(),settingsBaseline.modulationEnvelopeId()));
    }

    private CommandEvent findCommand(String id) {
        return source == null || id == null ? null : source.commands().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    static StudioProject guardSource(StudioProject project, Sequence expected) {
        Sequence latest = expected == null ? null : findSource(project, expected.id());
        if (latest == null || !latest.equals(expected)) throw new IllegalArgumentException("Source changed while this form was open. Your draft is retained; reselect the event or choose New form before applying.");
        return project;
    }

    static StudioProject sourceSettingsFromDraft(StudioProject project, Sequence expected, String voice, int volume, String repeat) {
        return sourceSettingsFromDraft(project,expected,voice,volume,repeat,expected.psgEnvelopeId(),expected.modulationEnvelopeId());
    }

    static StudioProject sourceSettingsFromDraft(StudioProject project, Sequence expected, String voice, int volume, String repeat,String psg,String mod) {
        Sequence latest = findSource(project, expected.id());
        if (latest == null || !latest.type().equals(expected.type())) throw new IllegalArgumentException("Source changed; reload source settings before applying.");
        boolean voiceChanged = !Objects.equals(voice, expected.voiceId());
        boolean volumeChanged = volume != expected.volume();
        boolean repeatChanged = !Objects.equals(repeat, expected.repeatState());
        boolean psgChanged=!Objects.equals(psg,expected.psgEnvelopeId()),modChanged=!Objects.equals(mod,expected.modulationEnvelopeId());
        if ((voiceChanged && !Objects.equals(latest.voiceId(), expected.voiceId()))
                || (volumeChanged && latest.volume() != expected.volume())
                || (repeatChanged && !Objects.equals(latest.repeatState(), expected.repeatState()))
                || (psgChanged&&!Objects.equals(latest.psgEnvelopeId(),expected.psgEnvelopeId()))
                || (modChanged&&!Objects.equals(latest.modulationEnvelopeId(),expected.modulationEnvelopeId())))
            throw new IllegalArgumentException("Source settings changed while you were editing. Your values are retained; reload source settings before applying.");
        StudioProject updated = ProjectEdits.setSourceSettings(project, expected.id(), voiceChanged ? voice : latest.voiceId(), volumeChanged ? volume : latest.volume());
        updated=ProjectEdits.setRepeatState(updated, expected.id(), repeatChanged ? repeat : latest.repeatState());
        return ProjectEdits.setSourceEnvelopes(updated,expected.id(),psgChanged?psg:latest.psgEnvelopeId(),modChanged?mod:latest.modulationEnvelopeId());
    }

    private void configureEnvelope(ComboBox<String> box, String id) {
        configureAssetPicker(box, id, () -> "None", assetId -> assetName(NativeCommand.Asset.MODULATION, assetId));
    }

    private void configureAssetPicker(ComboBox<String> box, String id,
                                      java.util.function.Supplier<String> emptyText,
                                      java.util.function.Function<String, String> name) {
        box.setId(id);
        box.setPrefWidth(240);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPromptText(emptyText.get());
        box.setConverter(new StringConverter<>() {
            @Override public String toString(String value) {
                return value == null ? emptyText.get() : name.apply(value);
            }
            @Override public String fromString(String value) { return value; }
        });
        box.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : box.getConverter().toString(value));
            }
        });
        box.valueProperty().addListener((o, a, b) -> { if (!updating) updateNotices(); });
    }

    private static NativeCommand.Asset assetKind(NativeCommand type) {
        return type == null ? NativeCommand.Asset.NONE : type.asset();
    }

    private void updateEventAssetChoices(String selected) {
        var kind = assetKind(command.getValue());
        eventAssetField.setVisible(kind != NativeCommand.Asset.NONE);
        if (kind == NativeCommand.Asset.NONE) {
            eventAsset.getItems().clear();
            eventAsset.setValue(null);
            return;
        }
        eventAsset.setPromptText(eventAsset.getConverter().toString(null));
        if (kind == NativeCommand.Asset.VOICE) {
            eventAsset.getItems().setAll(project == null ? List.of() : project.voices().stream().map(Voice::id).toList());
        } else {
            updateEnvelopeChoices(eventAsset, kind == NativeCommand.Asset.PSG_VOLUME ? Envelope.PSG_VOLUME : Envelope.MODULATION);
        }
        eventAsset.setValue(selected);
    }

    private String assetName(NativeCommand.Asset kind, String id) {
        if (project == null) return id;
        var name = kind == NativeCommand.Asset.VOICE
                ? project.voices().stream().filter(v -> v.id().equals(id)).findFirst().map(Voice::name)
                : project.envelopes().stream().filter(e -> e.id().equals(id)).findFirst().map(Envelope::name);
        return name.map(value -> value + " · " + id).orElse(id + " (unavailable)");
    }

    private void updateEnvelopeChoices(ComboBox<String> box,String type){
        String previous=box.getValue();List<String> ids=new ArrayList<>();ids.add(null);
        if(project!=null)project.envelopes().stream().filter(e->e.type().equals(type)).map(Envelope::id).forEach(ids::add);
        box.getItems().setAll(ids);box.setValue(previous);
    }

    static List<Integer> parseHexArguments(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Integer> values = new ArrayList<>();
        for (String token : text.trim().split("\\s+")) {
            String digits = token.startsWith("0x") || token.startsWith("0X") ? token.substring(2) : token;
            if (!digits.matches("[0-9A-Fa-f]{1,2}")) throw new IllegalArgumentException("Invalid hex byte '" + token + "'. Use bytes 00–FF separated by spaces.");
            values.add(Integer.parseInt(digits, 16));
        }
        return List.copyOf(values);
    }

    static String hexArguments(List<Integer> values) {
        return String.join(" ", values.stream().map(v -> String.format(Locale.ROOT, "%02X", v)).toList());
    }

    private String commandBytes(CommandEvent event) {
        String encoded = commandOpcode(event.command());
        if (!event.arguments().isEmpty()) encoded += " " + hexArguments(event.arguments());
        var kind = event.command().asset();
        if (kind == NativeCommand.Asset.NONE) return encoded;
        return encoded + " · " + (event.assetId() == null ? "Off" : assetName(kind, event.assetId()));
    }

    private static String commandOpcode(NativeCommand type) {
        String opcode = String.format(Locale.ROOT, "%02X", type.opcode());
        return type.subOpcode() < 0 ? opcode : opcode + String.format(Locale.ROOT, " %02X", type.subOpcode());
    }

    private static Sequence findSource(StudioProject project, String id) {
        return project == null || id == null ? null : project.sequences().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    private static int commandIndex(Sequence source, String id) {
        for (int i = 0; i < source.commands().size(); i++) if (source.commands().get(i).id().equals(id)) return i;
        return -1;
    }

    private static TableColumn<CommandEvent, String> column(String text, java.util.function.Function<CommandEvent, String> value) {
        TableColumn<CommandEvent, String> column = new TableColumn<>(text);
        column.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue()))); column.setSortable(false);
        return column;
    }

    private static Button button(String text, String id, Runnable action) {
        Button button = new Button(text); button.setId(id); button.setOnAction(e -> action.run()); return button;
    }

    private static VBox field(String text, Control control) {
        VBox field = new VBox(4, label(text, "muted"), control); field.setMinWidth(0); return field;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text); label.getStyleClass().add(style); label.setWrapText(true); label.setMinWidth(0); return label;
    }
}
