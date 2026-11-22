package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ProjectExport;
import com.opensmpsdeck.headless.ProjectProvenance;
import com.opensmpsdeck.headless.ProjectExport.Format;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class ExportDialog {
    private ExportDialog() {}

    static void show(Window owner, boolean nativeBank, Callable<ProjectExport.Source> source,
                     Executor worker, Consumer<String> status) {
        var dialog = new Dialog<ProjectExport.Options>();
        dialog.initOwner(owner);
        dialog.setTitle("Export");
        dialog.setHeaderText("Export arrangement");
        dialog.getDialogPane().setId("export-dialog");
        dialog.getDialogPane().setPrefWidth(465);
        var export = new ButtonType("Choose output…", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);

        var formats = new ChoiceBox<Format>();
        formats.getItems().setAll(Format.values());
        formats.setValue(Format.WAV);
        formats.setId("export-format");
        formats.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Format format) {
                return switch (format) {
                    case WAV -> "WAV audio";
                    case MP3 -> "MP3 audio";
                    case VGM -> "VGM 1.71";
                    case SMPS -> "Raw S3K SMPS (.bin)";
                    case PACKAGE -> "SMPS package (.smps.zip)";
                };
            }

            @Override public Format fromString(String value) { throw new UnsupportedOperationException(); }
        });
        var span = new CheckBox("Limit to a span");
        var start = field("0", "export-start");
        var end = field("Song end", "export-end");
        var fade = field("None", "export-fade");
        var loops = field("One pass", "export-loops");
        var stems = new CheckBox("Separate channel stems");
        var zip = new CheckBox("Bundle stems in a ZIP");
        var note = new Label();
        note.setWrapText(true);
        note.setMaxWidth(435);
        var error = new Label();
        error.setStyle("-fx-text-fill: #c13434;");
        error.setWrapText(true);
        error.setMaxWidth(435);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        var form = new GridPane();
        form.setHgap(14);
        form.setVgap(10);
        form.addRow(0, new Label("Format"), formats);
        form.add(span, 1, 1);
        form.addRow(2, new Label("Start (seconds)"), start);
        form.addRow(3, new Label("End (seconds)"), end);
        form.addRow(4, new Label("Fade out (seconds)"), fade);
        if (nativeBank) form.addRow(5, new Label("Native loop count"), loops);
        form.add(stems, 1, 6);
        form.add(zip, 1, 7);
        var body = new VBox(14, form, note, error);
        body.setPadding(new Insets(8, 6, 4, 6));
        dialog.getDialogPane().setContent(body);

        var nonAudio = javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !formats.getValue().audio(), formats.valueProperty());
        span.disableProperty().bind(nonAudio);
        start.disableProperty().bind(nonAudio.or(span.selectedProperty().not()));
        end.disableProperty().bind(start.disableProperty());
        fade.disableProperty().bind(nonAudio);
        loops.disableProperty().bind(nonAudio);
        stems.disableProperty().bind(nonAudio);
        zip.disableProperty().bind(nonAudio.or(stems.selectedProperty().not()));
        formats.valueProperty().addListener((ignored, previous, value) -> note.setText(formatNote(value)));
        note.setText(formatNote(formats.getValue()));

        var result = new ProjectExport.Options[1];
        var confirm = (Region) dialog.getDialogPane().lookupButton(export);
        ButtonBar.setButtonUniformSize(confirm, false);
        confirm.setMinWidth(172);
        confirm.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            Format format = formats.getValue();
            boolean audio = format.audio();
            boolean bounded = audio && span.isSelected();
            boolean split = audio && stems.isSelected();
            try {
                result[0] = new ProjectExport.Options(format,
                        bounded ? seconds(start) : null, bounded ? seconds(end) : null,
                        audio ? seconds(fade) : null, audio && nativeBank && !loops.getText().isBlank()
                                ? Integer.valueOf(loops.getText().strip()) : null,
                        split, split && zip.isSelected());
            } catch (IllegalArgumentException failure) {
                error.setText(failure instanceof NumberFormatException
                        ? "Enter seconds as numbers and loop count as a whole number." : failure.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == export ? result[0] : null);
        dialog.showAndWait().ifPresent(options -> chooseDestination(owner, source, worker, status, options));
    }

    private static TextField field(String prompt, String id) {
        var field = new TextField();
        field.setPromptText(prompt);
        field.setId(id);
        return field;
    }

    private static Double seconds(TextField field) {
        String text = field.getText().strip();
        return text.isEmpty() ? null : Double.valueOf(text);
    }

    private static String formatNote(Format format) {
        return switch (format) {
            case WAV -> "48 kHz stereo PCM via ffmpeg. Span values use the project clock.";
            case MP3 -> "Encoded from the chip render using ffmpeg.";
            case VGM -> "Full song register log for YM2612 and SN76489 VGM players.";
            case SMPS -> "Standalone track for a compatible S3K SMPS driver. Requires an arrangement without external chip assets.";
            case PACKAGE -> "Native S3K bank, bindings, resources and project source for Studio and OpenGGF integration.";
        };
    }

    private static void chooseDestination(Window owner, Callable<ProjectExport.Source> source,
                                          Executor worker, Consumer<String> status, ProjectExport.Options options) {
        var chooser = new FileChooser();
        if (options.stems() && !options.zip()) {
            chooser.setTitle("Name a new directory for channel stems");
            chooser.setInitialFileName("track-stems");
        } else {
            String extension = options.zip() ? "zip" : options.format().extension();
            chooser.setTitle("Choose a new export file");
            chooser.setInitialFileName(options.zip() ? "track-stems.zip" : "track." + extension);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Export (*." + extension + ")", "*." + extension));
        }
        var destination = chooser.showSaveDialog(owner);
        if (destination == null) return;
        Path output = destination.toPath();
        var job = new Task<Boolean>() {
            @Override protected Boolean call() throws Exception {
                var opened = source.call();
                if (options.format() == Format.PACKAGE) {
                    var warnings = ProjectProvenance.detect(opened.project()).warnings();
                    if (!warnings.isEmpty()) {
                        var confirmation = new java.util.concurrent.FutureTask<>(() -> {
                            if (isCancelled()) return false;
                            var text = warnings.stream().map(ProjectProvenance::warningMessage)
                                    .collect(java.util.stream.Collectors.joining("\n\n"));
                            var alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
                            alert.initOwner(owner);
                            alert.setTitle("Package provenance");
                            alert.setHeaderText("Export this package?");
                            return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
                        });
                        javafx.application.Platform.runLater(confirmation);
                        if (!confirmation.get()) return false;
                    }
                }
                ProjectExport.write(opened.project(), opened.work(), output, options, (done, total, message) -> {
                    if (isCancelled()) throw new java.util.concurrent.CancellationException();
                    updateProgress(done, total);
                    updateMessage(message);
                });
                return true;
            }
        };

        var progress = new Dialog<ButtonType>();
        progress.initOwner(owner);
        progress.setTitle("Exporting");
        progress.setHeaderText("Exporting " + output.getFileName());
        var message = new Label("Preparing export…");
        message.textProperty().bind(job.messageProperty());
        var bar = new ProgressBar();
        bar.setPrefWidth(380);
        bar.progressProperty().bind(job.progressProperty());
        progress.getDialogPane().setContent(new VBox(12, message, bar));
        progress.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progress.setOnCloseRequest(event -> { if (!job.isDone()) job.cancel(); });
        job.setOnSucceeded(event -> {
            progress.close();
            status.accept(job.getValue() ? "Exported " + output.getFileName() : "Export cancelled");
        });
        job.setOnFailed(event -> {
            progress.close();
            status.accept("Export failed · " + job.getException().getMessage());
            var alert = new Alert(Alert.AlertType.ERROR, job.getException().getMessage());
            alert.initOwner(owner);
            alert.setHeaderText("Could not export arrangement");
            alert.show();
        });
        job.setOnCancelled(event -> {
            progress.close();
            status.accept("Export cancelled");
        });
        progress.show();
        worker.execute(job);
    }
}
