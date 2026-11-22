package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rom.VerifiedRom;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.prefs.Preferences;

final class RomSettingsDialog {
    private record Row(Label status, Hyperlink importPrevious, Button clear) {}
    private record Settings(List<RomLibrary.Entry> entries, Map<RomGame, Path> previous) {}

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final GridPane grid = new GridPane();
    private final Label message = new Label();
    private final EnumMap<RomGame, Row> rows = new EnumMap<>(RomGame.class);
    private final RomLibrary library;
    private final Executor worker;
    private final Preferences previousRoms = Preferences.userNodeForPackage(ExportDialog.class).node("export-roms");

    static void show(Window owner, RomLibrary library, Executor worker) {
        new RomSettingsDialog(owner, library, worker).dialog.showAndWait();
    }

    private RomSettingsDialog(Window owner, RomLibrary library, Executor worker) {
        this.library = library;
        this.worker = worker;
        dialog.initOwner(owner);
        dialog.setTitle("ROM settings");
        dialog.setHeaderText("Clean retail ROMs for export and Test in game");
        var pane = dialog.getDialogPane();
        pane.setId("rom-settings-dialog");
        pane.setPrefWidth(585);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        grid.setHgap(12);
        grid.setVgap(12);

        addRow(RomGame.S1, "Sonic 1", 0);
        addRow(RomGame.S2, "Sonic 2", 1);
        addRow(RomGame.S3K, "Sonic 3 & Knuckles", 2);
        message.setWrapText(true);
        message.setMaxWidth(535);
        message.setId("rom-settings-message");
        message.managedProperty().bind(message.textProperty().isNotEmpty());
        var body = new VBox(12, grid, message);
        body.setPadding(new Insets(8, 6, 4, 6));
        pane.setContent(body);
        dialog.setOnCloseRequest(event -> {
            if (pane.lookupButton(ButtonType.CLOSE).isDisabled()) event.consume();
        });
        run(null);
    }

    private void addRow(RomGame game, String name, int index) {
        var status = new Label("Checking…");
        status.setId("rom-status-" + game.id());
        status.setPrefWidth(245);
        status.setMaxWidth(245);
        var browse = new Button("Browse…");
        browse.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("Choose a clean " + name + " ROM");
            var file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file != null) run(() -> library.set(game, file.toPath()));
        });
        var clear = new Button("Clear");
        clear.setId("rom-clear-" + game.id());
        clear.setDisable(true);
        clear.setOnAction(event -> run(() -> library.clear(game)));
        var importPrevious = new Hyperlink("Register previous export ROM");
        importPrevious.setId("rom-import-previous-" + game.id());
        importPrevious.managedProperty().bind(importPrevious.visibleProperty());
        importPrevious.setVisible(false);
        rows.put(game, new Row(status, importPrevious, clear));
        grid.addRow(index, new Label(name), new VBox(status, importPrevious), browse, clear);
    }

    @FunctionalInterface
    private interface Update {
        void run() throws IOException;
    }

    static Path verifiedPrevious(RomLibrary.Entry entry, Preferences preferences) {
        if (!entry.status().equals("missing")) return null;
        String saved = preferences.get(entry.game(), null);
        if (saved == null) return null;
        try {
            Path path = Path.of(saved);
            if (path.isAbsolute()) {
                VerifiedRom.read(RomGame.parse(entry.game()), path);
                return path;
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Standalone, changed and unverified images cannot be registered.
        }
        return null;
    }

    private void run(Update action) {
        var job = new Task<Settings>() {
            @Override protected Settings call() throws IOException {
                if (action != null) action.run();
                var entries = library.list();
                var previous = new EnumMap<RomGame, Path>(RomGame.class);
                for (var entry : entries) {
                    Path path = verifiedPrevious(entry, previousRoms);
                    if (path != null) previous.put(RomGame.parse(entry.game()), path);
                }
                return new Settings(entries, previous);
            }
        };
        grid.setDisable(true);
        var close = dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        close.setDisable(action != null);
        message.setText("Checking ROMs…");
        job.setOnSucceeded(event -> {
            for (var entry : job.getValue().entries()) {
                var game = RomGame.parse(entry.game());
                var row = rows.get(game);
                row.status().setText(switch (entry.status()) {
                    case "ok" -> "OK · " + entry.revision();
                    case "changed-on-disk" -> "Changed on disk";
                    default -> "Missing";
                });
                row.status().setTooltip(entry.path() == null ? null : new Tooltip(
                        entry.path() + (entry.detail() == null ? "" : "\n" + entry.detail())));
                row.clear().setDisable(entry.path() == null);
                Path previous = job.getValue().previous().get(game);
                row.importPrevious().setVisible(previous != null);
                row.importPrevious().setTooltip(previous == null ? null : new Tooltip(previous.toString()));
                row.importPrevious().setOnAction(previous == null ? null : click -> run(() -> {
                    var current = library.list().stream().filter(item -> item.game().equals(game.id()))
                            .findFirst().orElseThrow();
                    if (current.status().equals("missing")) library.set(game, previous);
                    previousRoms.remove(game.id());
                }));
            }
            message.setText("");
            grid.setDisable(false);
            close.setDisable(false);
            resize();
        });
        job.setOnFailed(event -> {
            message.setText(job.getException().getMessage());
            grid.setDisable(false);
            close.setDisable(false);
            resize();
        });
        worker.execute(job);
    }

    private void resize() {
        if (dialog.isShowing()) dialog.getDialogPane().getScene().getWindow().sizeToScene();
    }
}
