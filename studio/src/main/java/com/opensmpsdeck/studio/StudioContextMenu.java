package com.opensmpsdeck.studio;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.List;

final class StudioContextMenu {
    private final ContextMenu menu = new ContextMenu();

    StudioContextMenu add(String label, Runnable action) {
        return add(label, null, null, action);
    }

    StudioContextMenu add(String label, String shortcut, String unavailable, Runnable action) {
        menu.getItems().add(item(label, shortcut, unavailable, null, action));
        return this;
    }

    StudioContextMenu submenu(String label, List<MenuItem> choices) {
        if (!choices.isEmpty()) {
            var group = new Menu(label);
            group.getItems().setAll(choices);
            menu.getItems().add(group);
        }
        return this;
    }

    static MenuItem item(String label, String shortcut, String unavailable, Color color, Runnable action) {
        // These keys are dispatched by the focused editor. Display them without
        // registering scene-wide accelerators that could outlive a popup target.
        var item = new MenuItem(label);
        if (shortcut != null || unavailable != null || color != null) {
            var hint = new Label(shortcut == null ? "" : shortcut);
            if (color != null) hint.setGraphic(new Rectangle(10, 10, color));
            if (unavailable != null) hint.setTooltip(new Tooltip(unavailable));
            item.setGraphic(hint);
        }
        item.setDisable(unavailable != null);
        item.setOnAction(event -> action.run());
        return item;
    }

    static String shortcut(String key) {
        return (System.getProperty("os.name", "").startsWith("Mac") ? "⌘" : "Ctrl+") + key;
    }

    void show(Node owner, ContextMenuEvent event) {
        if (menu.getItems().isEmpty()) return;
        menu.show(owner, event.getScreenX(), event.getScreenY());
        // Disabled rows still need to explain their state on hover.
        for (var item : menu.getItems()) installRowTooltip(item);
        event.consume();
    }

    private static void installRowTooltip(MenuItem item) {
        if (item.getGraphic() instanceof Label hint && hint.getTooltip() != null && item.getStyleableNode() != null)
            Tooltip.install(item.getStyleableNode(), hint.getTooltip());
    }

    static boolean within(Node node, Node container) {
        for (; node != null; node = node.getParent()) if (node == container) return true;
        return false;
    }

    static boolean editingText(Object target) {
        if (!(target instanceof Node node)) return false;
        for (; node != null; node = node.getParent())
            if (node instanceof TextInputControl || node instanceof Spinner<?>) return true;
        return false;
    }
}
