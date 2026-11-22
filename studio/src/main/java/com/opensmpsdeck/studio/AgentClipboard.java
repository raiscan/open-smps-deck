package com.opensmpsdeck.studio;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

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

    static void addTo(StudioContextMenu menu, Runnable action) {
        menu.add("Copy for agent", StudioContextMenu.shortcut("C"), null, action);
    }
}
