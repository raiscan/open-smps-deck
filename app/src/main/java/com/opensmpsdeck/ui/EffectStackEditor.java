package com.opensmpsdeck.ui;

import com.opensmpsdeck.codec.SmpsEncoder;
import com.opensmps.smps.SmpsCoordFlags;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dialog for editing stacked non-instrument coordination flags on one tracker row.
 */
public class EffectStackEditor extends Dialog<List<SmpsEncoder.EffectCommand>> {

    private record FlagChoice(int flag, String label) {
        @Override
        public String toString() {
            return String.format("%02X %s", flag, label);
        }
    }

    private final ObservableList<SmpsEncoder.EffectCommand> commands = FXCollections.observableArrayList();
    private final ListView<SmpsEncoder.EffectCommand> commandList = new ListView<>(commands);
    private final ComboBox<FlagChoice> flagBox = new ComboBox<>();
    private final TextField paramsField = new TextField();
    private final Label hintLabel = new Label();
    private final Label bytesPreview = new Label();

    public EffectStackEditor(List<SmpsEncoder.EffectCommand> initial, String contextLabel) {
        setTitle("Effect Stack");
        setHeaderText(contextLabel);

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setPrefSize(540, 440);

        commands.addAll(initial != null ? initial : List.of());

        commandList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SmpsEncoder.EffectCommand item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%02X", item.flag() & 0xFF))
                  .append(" ")
                  .append(SmpsCoordFlags.getLabel(item.flag()));
                int[] params = item.params();
                if (params.length > 0) {
                    sb.append("  ");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(" ");
                        sb.append(String.format("%02X", params[i] & 0xFF));
                    }
                }
                setText(sb.toString());
            }
        });

        setupFlagChoices();
        flagBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> updateHintLabel());

        commandList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, cmd) -> {
            if (cmd == null) return;
            selectFlag(cmd.flag());
            paramsField.setText(formatParams(cmd.params()));
            updateHintLabel();
        });

        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> onAdd());
        Button updateBtn = new Button("Update");
        updateBtn.setOnAction(e -> onUpdate());
        Button delBtn = new Button("Remove");
        delBtn.setOnAction(e -> onRemove());
        Button upBtn = new Button("Up");
        upBtn.setOnAction(e -> onMove(-1));
        Button downBtn = new Button("Down");
        downBtn.setOnAction(e -> onMove(1));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        form.add(new Label("Command"), 0, 0);
        form.add(flagBox, 1, 0);
        form.add(new Label("Params"), 0, 1);
        form.add(paramsField, 1, 1);
        form.add(hintLabel, 1, 2);
        GridPane.setHgrow(flagBox, Priority.ALWAYS);
        GridPane.setHgrow(paramsField, Priority.ALWAYS);

        HBox editButtons = new HBox(6, addBtn, updateBtn, delBtn, upBtn, downBtn);
        editButtons.setAlignment(Pos.CENTER_LEFT);

        bytesPreview.setStyle("-fx-text-fill: #cc8866;");
        refreshPreview();

        VBox root = new VBox(8,
                commandList,
                form,
                editButtons,
                new Label("Row bytes preview"),
                bytesPreview
        );
        root.setPadding(new Insets(8));
        VBox.setVgrow(commandList, Priority.ALWAYS);
        pane.setContent(root);

        setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                return new ArrayList<>(commands);
            }
            return null;
        });

        if (!commands.isEmpty()) {
            commandList.getSelectionModel().select(0);
        } else if (!flagBox.getItems().isEmpty()) {
            flagBox.getSelectionModel().select(0);
            updateHintLabel();
        }
    }

    public static Optional<List<SmpsEncoder.EffectCommand>> show(List<SmpsEncoder.EffectCommand> initial, String contextLabel) {
        return new EffectStackEditor(initial, contextLabel).showAndWait();
    }

    private void setupFlagChoices() {
        int[] supported = {
                SmpsCoordFlags.PAN, SmpsCoordFlags.DETUNE, SmpsCoordFlags.SET_COMM,
                SmpsCoordFlags.RETURN, SmpsCoordFlags.FADE_IN, SmpsCoordFlags.TICK_MULT,
                SmpsCoordFlags.VOLUME, SmpsCoordFlags.NOTE_FILL, SmpsCoordFlags.KEY_DISP,
                SmpsCoordFlags.SET_TEMPO, SmpsCoordFlags.SET_DIV_TIMING, SmpsCoordFlags.PSG_VOLUME,
                SmpsCoordFlags.UNUSED_ED, SmpsCoordFlags.NOOP_EE, SmpsCoordFlags.MODULATION,
                SmpsCoordFlags.MOD_ON, SmpsCoordFlags.PSG_NOISE, SmpsCoordFlags.MOD_OFF,
                SmpsCoordFlags.JUMP, SmpsCoordFlags.LOOP, SmpsCoordFlags.CALL,
                SmpsCoordFlags.SND_OFF, SmpsCoordFlags.FADE_OUT
        };
        for (int flag : supported) {
            flagBox.getItems().add(new FlagChoice(flag, SmpsCoordFlags.getLabel(flag)));
        }
    }

    private void selectFlag(int flag) {
        for (FlagChoice choice : flagBox.getItems()) {
            if ((choice.flag() & 0xFF) == (flag & 0xFF)) {
                flagBox.getSelectionModel().select(choice);
                return;
            }
        }
    }

    private void updateHintLabel() {
        FlagChoice choice = flagBox.getValue();
        if (choice == null) {
            hintLabel.setText("");
            return;
        }
        int params = SmpsCoordFlags.getParamCount(choice.flag());
        hintLabel.setText(params == 0
                ? "No params expected."
                : "Enter " + params + " hex byte(s), e.g. " + sampleParamHint(params));
    }

    private static String sampleParamHint(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(" ");
            sb.append("00");
        }
        return sb.toString();
    }

    private void onAdd() {
        SmpsEncoder.EffectCommand cmd = buildCommandFromInputs();
        if (cmd == null) return;
        commands.add(cmd);
        commandList.getSelectionModel().select(commands.size() - 1);
        refreshPreview();
    }

    private void onUpdate() {
        int idx = commandList.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;
        SmpsEncoder.EffectCommand cmd = buildCommandFromInputs();
        if (cmd == null) return;
        commands.set(idx, cmd);
        commandList.getSelectionModel().select(idx);
        refreshPreview();
    }

    private void onRemove() {
        int idx = commandList.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;
        commands.remove(idx);
        if (!commands.isEmpty()) {
            commandList.getSelectionModel().select(Math.min(idx, commands.size() - 1));
        }
        refreshPreview();
    }

    private void onMove(int delta) {
        int idx = commandList.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;
        int newIdx = idx + delta;
        if (newIdx < 0 || newIdx >= commands.size()) return;
        SmpsEncoder.EffectCommand cmd = commands.remove(idx);
        commands.add(newIdx, cmd);
        commandList.getSelectionModel().select(newIdx);
        refreshPreview();
    }

    private SmpsEncoder.EffectCommand buildCommandFromInputs() {
        FlagChoice choice = flagBox.getValue();
        if (choice == null) return null;
        int expected = SmpsCoordFlags.getParamCount(choice.flag());
        int[] params = parseHexParams(paramsField.getText(), expected);
        if (params == null) return null;
        return new SmpsEncoder.EffectCommand(choice.flag(), params);
    }

    private int[] parseHexParams(String raw, int expectedCount) {
        String text = raw != null ? raw.trim() : "";
        if (expectedCount == 0) return new int[0];
        if (text.isEmpty()) {
            showValidationError("This command needs " + expectedCount + " parameter byte(s).");
            return null;
        }

        String[] parts = text.split("\\s+");
        if (parts.length != expectedCount) {
            showValidationError("Expected " + expectedCount + " parameter byte(s), got " + parts.length + ".");
            return null;
        }

        int[] params = new int[expectedCount];
        for (int i = 0; i < parts.length; i++) {
            try {
                params[i] = Integer.parseInt(parts[i], 16) & 0xFF;
            } catch (NumberFormatException ex) {
                showValidationError("Invalid hex byte: " + parts[i]);
                return null;
            }
        }
        return params;
    }

    private void refreshPreview() {
        StringBuilder sb = new StringBuilder();
        for (SmpsEncoder.EffectCommand cmd : commands) {
            if (sb.length() > 0) sb.append(" ; ");
            sb.append(String.format("%02X", cmd.flag() & 0xFF));
            for (int p : cmd.params()) {
                sb.append(" ").append(String.format("%02X", p & 0xFF));
            }
        }
        bytesPreview.setText(sb.length() > 0 ? sb.toString() : "(none)");
    }

    private static String formatParams(int[] params) {
        if (params == null || params.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(String.format("%02X", params[i] & 0xFF));
        }
        return sb.toString();
    }

    private void showValidationError(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setTitle("Invalid Parameters");
        alert.setHeaderText("Cannot add command");
        alert.showAndWait();
    }
}

