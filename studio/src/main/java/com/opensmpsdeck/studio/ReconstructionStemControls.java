package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionStems;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;

/** Source monitor controls. Their state belongs to this local Analysis view. */
final class ReconstructionStemControls {
    final ToggleButton disclosure = new ToggleButton("Stems ▾");
    final FlowPane channels = new FlowPane(6, 5);
    private final Preferences preferences;
    private final Runnable changed;
    private final Map<String, ToggleButton> buttons = new LinkedHashMap<>();

    ReconstructionStemControls(Path work, Runnable changed) {
        var key = UUID.nameUUIDFromBytes(work.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
        preferences = Preferences.userNodeForPackage(ReconstructionStemControls.class)
                .node("reconstruction-stems").node(key.toString());
        this.changed = changed;
        disclosure.setId("reconstruction-stems");
        disclosure.setTooltip(new Tooltip("Choose which source stems to hear and highlight"));
        disclosure.managedProperty().bind(disclosure.visibleProperty());
        channels.setAlignment(Pos.CENTER_LEFT);
        channels.setId("reconstruction-stem-channels");
        channels.getStyleClass().add("reconstruction-stem-channels");
        channels.visibleProperty().bind(disclosure.visibleProperty().and(disclosure.selectedProperty()));
        channels.managedProperty().bind(channels.visibleProperty());
    }

    void load(ReconstructionStems stems) {
        buttons.clear();
        channels.getChildren().clear();
        disclosure.setVisible(stems != null);
        if (stems == null) return;
        var disabled = Arrays.asList(preferences.get("muted", "").split(","));
        var all = new Button("All");
        all.setId("reconstruction-stem-all");
        all.setAccessibleText("All stems, original mix");
        all.setTooltip(new Tooltip("Restore the original full mix"));
        all.setOnAction(event -> selectAll());
        channels.getChildren().addAll(new Label("Source stems"), all);
        int index = 0;
        for (var name : stems.names()) {
            var label = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1).replace('_', ' ');
            var swatch = new Circle(4, color(name, index++));
            var toggle = new ToggleButton(label, swatch);
            swatch.opacityProperty().bind(javafx.beans.binding.Bindings.when(toggle.selectedProperty()).then(1.0).otherwise(.45));
            toggle.setId("reconstruction-stem-" + name);
            toggle.setSelected(!disabled.contains(name));
            toggle.setTooltip(new Tooltip("Toggle " + label + " · Alt or Ctrl-click to solo"));
            toggle.setOnAction(event -> update());
            toggle.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() != MouseButton.PRIMARY
                        || !(event.isAltDown() || event.isControlDown() || event.isMetaDown())) return;
                event.consume();
                toggle.requestFocus();
                solo(name);
            });
            toggle.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.SPACE && (event.isAltDown() || event.isShortcutDown())) {
                    event.consume();
                    solo(name);
                }
            });
            buttons.put(name, toggle);
            channels.getChildren().add(toggle);
        }
        updateLabel();
    }

    boolean all() { return buttons.values().stream().allMatch(ToggleButton::isSelected); }

    List<String> selected() {
        return buttons.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList();
    }

    Color color(String name) { return (Color) ((Circle) buttons.get(name).getGraphic()).getFill(); }

    void selectAll() {
        buttons.values().forEach(button -> button.setSelected(true));
        update();
    }

    private void solo(String name) {
        if (selected().equals(List.of(name))) selectAll();
        else {
            buttons.forEach((id, toggle) -> toggle.setSelected(id.equals(name)));
            update();
        }
    }

    private void update() {
        var muted = buttons.entrySet().stream().filter(entry -> !entry.getValue().isSelected()).map(Map.Entry::getKey).toList();
        if (muted.isEmpty()) preferences.remove("muted");
        else preferences.put("muted", String.join(",", muted));
        updateLabel();
        changed.run();
    }

    private void updateLabel() {
        var summary = all() ? "Original mix" : selected().size() + " of " + buttons.size() + " stems";
        disclosure.setText(all() ? "Stems ▾" : "Stems " + selected().size() + "/" + buttons.size() + " ▾");
        disclosure.setAccessibleText("Stems, " + summary);
    }

    private static Color color(String name, int index) {
        return switch (name) {
            case "vocals" -> Color.web("#52dec4");
            case "bass" -> Color.web("#f6be68");
            case "drums" -> Color.web("#ff897c");
            case "guitar" -> Color.web("#83abff");
            case "piano", "keys" -> Color.web("#c8a2ff");
            case "other" -> Color.web("#b5bfce");
            default -> Color.hsb((323 + index * 137.508) % 360, .46, .95);
        };
    }
}
