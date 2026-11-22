package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

final class RomSettingsDialog {
    private record Row(Label status, Button clear) {}

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final GridPane grid = new GridPane();
    private final Label message = new Label();
    private final EnumMap<RomGame, Row> rows = new EnumMap<>(RomGame.class);
    private final RomLibrary library;
    private final Executor worker;

    static void show(Window owner, RomLibrary library, Executor worker) {
        new RomSettingsDialog(owner, library, worker).dialog.showAndWait();
    }

    private RomSettingsDialog(Window owner, RomLibrary library, Executor worker) {
        this.library = library;
        this.worker = worker;
        dialog.initOwner(owner);
        dialog.setTitle("ROM settings");
        dialog.setHeaderText("Clean retail ROMs for Test in game");
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
        run(library::list);
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
            if (file != null) run(() -> {
                library.set(game, file.toPath());
                return library.list();
            });
        });
        var clear = new Button("Clear");
        clear.setId("rom-clear-" + game.id());
        clear.setDisable(true);
        clear.setOnAction(event -> run(() -> {
            library.clear(game);
            return library.list();
        }));
        rows.put(game, new Row(status, clear));
        grid.addRow(index, new Label(name), status, browse, clear);
    }

    private void run(Callable<List<RomLibrary.Entry>> action) {
        var job = new Task<List<RomLibrary.Entry>>() {
            @Override protected List<RomLibrary.Entry> call() throws Exception {
                return action.call();
            }
        };
        grid.setDisable(true);
        message.setText("Checking ROMs…");
        job.setOnSucceeded(event -> {
            for (var entry : job.getValue()) {
                var row = rows.get(RomGame.parse(entry.game()));
                row.status().setText(switch (entry.status()) {
                    case "ok" -> "OK · " + entry.revision();
                    case "changed-on-disk" -> "Changed on disk";
                    default -> "Missing";
                });
                row.status().setTooltip(entry.path() == null ? null : new Tooltip(
                        entry.path() + (entry.detail() == null ? "" : "\n" + entry.detail())));
                row.clear().setDisable(entry.path() == null);
            }
            message.setText("");
            grid.setDisable(false);
        });
        job.setOnFailed(event -> {
            message.setText(job.getException().getMessage());
            grid.setDisable(false);
        });
        worker.execute(job);
    }
}
