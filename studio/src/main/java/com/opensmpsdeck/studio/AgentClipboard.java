package com.opensmpsdeck.studio;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class AgentClipboard {
    private AgentClipboard() {}

    static boolean shortcut(KeyEvent event) {
        return event.isShortcutDown() && !event.isAltDown() && !event.isShiftDown() && event.getCode() == KeyCode.C;
    }

    static boolean copy(String text, Consumer<String> status) {
        var content = new ClipboardContent();
        content.putString(text);
        status.accept(Clipboard.getSystemClipboard().setContent(content)
                ? "Copied notes for agent" : "Could not write to the clipboard");
        return true;
    }

    static void menu(Node anchor, ContextMenuEvent event, BooleanSupplier action) {
        var copy = new MenuItem("Copy for agent");
        copy.setOnAction(ignored -> action.getAsBoolean());
        new ContextMenu(copy).show(anchor, event.getScreenX(), event.getScreenY());
    }
}
