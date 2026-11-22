package com.opensmpsdeck.studio;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ProjectExport;
import com.opensmpsdeck.headless.TestInGame;
import com.opensmpsdeck.headless.rom.RomExport;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rompatch.RomPatch;
import com.opensmpsdeck.project.StudioProject;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class TestInGameDialog {
    private TestInGameDialog() {}

    static void show(Window owner, String songName, Callable<ProjectExport.Source> source, Path sourcePath,
                     RomLibrary library, Executor worker, Consumer<String> status) {
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setTitle("Test in game");
        dialog.setHeaderText("Export to Sonic 3 & Knuckles");
        var pane = dialog.getDialogPane();
        pane.setId("test-in-game-dialog");
        pane.setPrefWidth(535);
        var export = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(export, ButtonType.CANCEL);

        String name = songName.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "-").strip();
        if (name.isEmpty()) name = "song";
        var rom = new Destination("Patched ROM", name + ".s3k.gen", "*.gen", pane);
        var bps = new Destination("BPS patch", name + ".s3k.bps", "*.bps", pane);
        var noDemo = new CheckBox("Skip title demo");
        noDemo.setId("rom-skip-demo");
        noDemo.setSelected(true);
        var loop = new CheckBox("Loop song");
        loop.setId("rom-loop");
        var note = new Label("Choose one or both outputs. Your song plays on the title screen.");
        note.setWrapText(true);
        var body = new VBox(12, note, rom.row, bps.row, noDemo, loop);
        body.setPadding(new Insets(8, 6, 4, 6));
        pane.setContent(body);

        var submit = pane.lookupButton(export);
        submit.disableProperty().bind(rom.path.isNull().and(bps.path.isNull()));
        dialog.showAndWait().filter(export::equals).ifPresent(button -> {
            var outputs = new TestInGame.Outputs(rom.path.get(), bps.path.get(), false);
            String warnings = RomExport.warnings(outputs.out() != null, outputs.bps() != null).stream()
                    .map(RomExport.Warning::message).collect(Collectors.joining("\n\n"));
            var confirm = new Alert(Alert.AlertType.CONFIRMATION, "", export, ButtonType.CANCEL);
            confirm.getDialogPane().setContent(wrappedMessage(warnings));
            confirm.initOwner(owner);
            confirm.setTitle("Confirm Test in game export");
            confirm.setHeaderText("Write the selected files?");
            confirm.showAndWait().filter(export::equals).ifPresent(confirmed ->
                    run(owner, source, sourcePath, library, outputs, new RomPatch.Options(
                            RomPatch.DEFAULT_SLOT, true, noDemo.isSelected(), loop.isSelected()), worker, status));
        });
    }

    private static final class Destination {
        final javafx.beans.property.ObjectProperty<Path> path = new javafx.beans.property.SimpleObjectProperty<>();
        final HBox row;

        Destination(String type, String suggested, String pattern, DialogPane pane) {
            var chooser = new FileChooser();
            chooser.setTitle("Choose a new " + type + " file");
            chooser.setInitialFileName(suggested);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(type, pattern));
            var choose = new Button(type + "…");
            choose.setOnAction(event -> {
                var file = chooser.showSaveDialog(pane.getScene().getWindow());
                if (file != null) path.set(file.toPath());
            });
            var selected = new Label("No output selected");
            selected.setMaxWidth(280);
            selected.setWrapText(true);
            path.addListener((ignored, previous, value) -> {
                selected.setText(value == null ? "No output selected" : value.getFileName().toString());
                selected.setTooltip(value == null ? null : new Tooltip(value.toString()));
            });
            var remove = new Button("Remove");
            remove.disableProperty().bind(path.isNull());
            remove.setOnAction(event -> path.set(null));
            row = new HBox(8, choose, selected, remove);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        }
    }

    private static void run(Window owner, Callable<ProjectExport.Source> source, Path sourcePath, RomLibrary library,
                            TestInGame.Outputs outputs, RomPatch.Options options, Executor worker, Consumer<String> status) {
        var progress = new Dialog<ButtonType>();
        progress.initOwner(owner);
        progress.setTitle("Test in game");
        progress.setHeaderText("Exporting for Sonic 3 & Knuckles");
        var spinner = new ProgressIndicator();
        spinner.setPrefSize(24, 24);
        var message = new HBox(12, spinner, new Label("Verifying ROM and preparing export…"));
        message.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var pane = progress.getDialogPane();
        pane.setContent(message);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        var close = pane.lookupButton(ButtonType.CLOSE);
        close.setVisible(false);
        close.setManaged(false);
        // Publishing can finish after an interruption, so keep the progress open until the job reports its outcome.
        progress.setOnCloseRequest(event -> event.consume());
        var job = new Task<String>() {
            @Override protected String call() throws Exception {
                var project = source.call().project();
                return summary(project, TestInGame.export(library, project, sourcePath, outputs, options));
            }
        };
        job.setOnSucceeded(event -> {
            finish(progress);
            status.accept("Test in game export complete");
            var summary = new TextArea(job.getValue());
            summary.setEditable(false);
            summary.setWrapText(true);
            summary.setPrefRowCount(14);
            var alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(owner);
            alert.setTitle("Test in game");
            alert.setHeaderText("Export complete");
            alert.getDialogPane().setPrefWidth(620);
            alert.getDialogPane().setContent(summary);
            alert.show();
        });
        job.setOnFailed(event -> {
            finish(progress);
            String reason = job.getException().getMessage();
            status.accept("Test in game export failed · " + reason);
            var alert = new Alert(Alert.AlertType.ERROR);
            alert.getDialogPane().setContent(wrappedMessage(reason));
            alert.initOwner(owner);
            alert.setTitle("Test in game");
            alert.setHeaderText("Could not export for Sonic 3 & Knuckles");
            alert.getDialogPane().setPrefWidth(535);
            alert.show();
        });
        progress.show();
        worker.execute(job);
    }

    private static Label wrappedMessage(String text) {
        var message = new Label(text);
        message.setWrapText(true);
        message.setPrefWidth(470);
        message.setMinHeight(Region.USE_PREF_SIZE);
        return message;
    }

    private static void finish(Dialog<ButtonType> progress) {
        progress.setOnCloseRequest(null);
        progress.close();
    }

    private static String summary(StudioProject project, JsonObject result) {
        var report = result.getAsJsonObject("report");
        int bank = report.get("bank").getAsInt();
        int free = report.getAsJsonObject("freeBytesByBank").get(Integer.toString(bank)).getAsInt();
        var text = new StringBuilder(("Bank 0x%02X · Music slot 0x%02X%n"
                + "Compiled song: %,d bytes · 32,768-byte limit%n"
                + "Bank free space before assets: %,d bytes%n")
                .formatted(bank, report.get("slot").getAsInt(), report.get("compiledSize").getAsInt(), free));
        if (result.has("out")) text.append("\nPatched ROM\n").append(result.get("out").getAsString()).append('\n');
        if (result.has("bps")) text.append("\nBPS patch\n").append(result.get("bps").getAsString()).append('\n');

        var drums = report.getAsJsonArray("dacNoteMap");
        if (!drums.isEmpty()) text.append("\nDrums (4-bit DPCM)\n");
        drums.forEach(value -> {
            var drum = value.getAsJsonObject();
            String id = drum.get("sample").getAsString();
            String name = project.samples().stream().filter(sample -> sample.id().equals(id))
                    .map(sample -> sample.name()).findFirst().orElse(id);
            var metrics = drum.getAsJsonObject("metrics");
            text.append(name).append(" · ");
            if (metrics.has("snrDb")) {
                text.append(String.format(Locale.ROOT, "%.1f dB SNR", metrics.get("snrDb").getAsDouble()));
            } else {
                text.append(drum.get("exact").getAsBoolean() ? "Exact" : "SNR unavailable");
            }
            text.append('\n');
        });
        var approximations = report.getAsJsonArray("envelopeMap").asList().stream()
                .map(value -> value.getAsJsonObject()).filter(entry -> !entry.get("exact").getAsBoolean()).toList();
        if (!approximations.isEmpty()) text.append("\nEnvelope approximations\n");
        approximations.forEach(entry -> text.append("Envelope ").append(entry.get("index").getAsInt())
                .append(" → stock envelope ").append(entry.get("mappedIndex").getAsInt()).append('\n'));
        return text.toString();
    }
}
