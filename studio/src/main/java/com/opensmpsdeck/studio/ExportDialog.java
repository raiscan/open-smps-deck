package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ProjectExport;
import com.opensmpsdeck.headless.ProjectExport.Format;
import com.opensmpsdeck.project.ProjectCompiler.BankLimitException;
import com.opensmpsdeck.headless.TestInGame;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rom.VerifiedRom;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.TargetGame;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class ExportDialog {
    private ExportDialog() {}

    static void show(Window owner, StudioProject project, Path projectPath, Path work,
                     RomLibrary library, Executor worker, Consumer<String> status) {
        show(owner, project, projectPath, work, TargetGame.S3K, library, worker, status);
    }

    static void show(Window owner, StudioProject project, Path projectPath, Path work, TargetGame fallbackTarget,
                     RomLibrary library, Executor worker, Consumer<String> status) {
        show(owner, project, projectPath, work, fallbackTarget, library, worker, status, false, null);
    }

    static void showRedacted(Window owner, StudioProject project, Path path, RomLibrary library,
                             Executor worker, Consumer<String> status) {
        show(owner, project, path, null, project.target(), library, worker, status, true, null);
    }

    static void showReconstruction(Window owner, StudioProject project, Path projectPath, Path work,
                                   TargetGame fallbackTarget, RomLibrary library, Executor worker,
                                   Consumer<String> status, Callable<ProjectExport.Source> journal) {
        show(owner, project, projectPath, work, fallbackTarget, library, worker, status, false, journal);
    }

    private static void show(Window owner, StudioProject project, Path projectPath, Path work, TargetGame fallbackTarget,
                             RomLibrary library, Executor worker, Consumer<String> status, boolean redacted,
                             Callable<ProjectExport.Source> journal) {
        boolean nativeBank = project != null && project.nativeProgram() != null;
        Callable<ProjectExport.Source> source = journal != null ? journal
                : () -> project == null ? ProjectExport.open(work) : new ProjectExport.Source(project, work);
        RomGame game = project == null ? RomGame.parse(fallbackTarget.rulebook().id()) : TestInGame.game(project);
        String name = project == null ? work.getFileName().toString() : project.name();
        var target = project == null ? fallbackTarget : project.target();
        var packageOptions = new ExportPackageOptions(target,
                nativeBank && ProjectExport.canExportRetailPatch(project));
        var dialog = new Dialog<ProjectExport.Options>();
        dialog.initOwner(owner);
        dialog.setTitle("Export");
        dialog.setHeaderText(redacted ? "Export redacted source" : "Export arrangement");
        dialog.getDialogPane().setId("export-dialog");
        dialog.getDialogPane().setPrefWidth(465);
        var export = new ButtonType("Choose output…", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);

        var formats = new ChoiceBox<Format>();
        if (redacted) formats.getItems().setAll(Format.PORTABLE, Format.MOD, Format.DISASM);
        else formats.getItems().setAll(Format.WAV, Format.MP3, Format.VGM, Format.SMPS, Format.PACKAGE,
                Format.PORTABLE, Format.MOD, Format.DISASM);
        // Mods and disassembly kits patch a retail game. Other targets export portable packages.
        if (!target.retail()) formats.getItems().removeAll(Format.MOD, Format.DISASM);
        formats.getSelectionModel().selectFirst();
        formats.setId("export-format");
        formats.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Format format) {
                return switch (format) {
                    case WAV -> "WAV audio";
                    case MP3 -> "MP3 audio";
                    case VGM -> "VGM 1.71";
                    case SMPS -> "Raw S3K SMPS (.bin)";
                    case PACKAGE -> "SMPS package (.smps.zip)";
                    case PORTABLE -> "Portable SMPS package (.smps.zip)";
                    case MOD -> "OpenGGF mod (.jar)";
                    case DISASM -> "Disassembly kit (.zip)";
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
        form.add(span, 1, 0);
        form.addRow(1, new Label("Start (seconds)"), start);
        form.addRow(2, new Label("End (seconds)"), end);
        form.addRow(3, new Label("Fade out (seconds)"), fade);
        if (nativeBank) form.addRow(4, new Label("Native loop count"), loops);
        form.add(stems, 1, 5);
        form.add(zip, 1, 6);

        var audioSelected = Bindings.createBooleanBinding(() -> formats.getValue().audio(), formats.valueProperty());
        showWhen(form, audioSelected);
        start.disableProperty().bind(span.selectedProperty().not());
        end.disableProperty().bind(start.disableProperty());
        zip.disableProperty().bind(stems.selectedProperty().not());

        var packageSelected = Bindings.createBooleanBinding(
                () -> ExportPackageOptions.supports(formats.getValue()), formats.valueProperty());
        var portableSelected = Bindings.createBooleanBinding(
                () -> ExportPackageOptions.portable(formats.getValue()), formats.valueProperty());
        var kitSelected = formats.valueProperty().isEqualTo(Format.DISASM);
        var modSelected = formats.valueProperty().isEqualTo(Format.MOD);
        var driver = new Label(target.id().toUpperCase(java.util.Locale.ROOT));
        driver.setId("export-driver");
        var includeSource = new CheckBox("Include redacted project source");
        includeSource.setId("export-include-source");
        includeSource.setSelected(true);
        showWhen(includeSource, portableSelected);
        var retailPatch = new CheckBox("Store changes as a retail song patch");
        retailPatch.setId("export-retail-patch");
        if (packageOptions.retailPatchAvailable()) showWhen(retailPatch, portableSelected);
        else {
            retailPatch.setVisible(false);
            retailPatch.setManaged(false);
        }
        includeSource.disableProperty().bind(retailPatch.selectedProperty());
        retailPatch.selectedProperty().addListener((ignored, previous, selected) -> {
            if (selected) {
                retailPatch.setUserData(includeSource.isSelected());
                includeSource.setSelected(false);
            } else {
                includeSource.setSelected(Boolean.TRUE.equals(retailPatch.getUserData()));
            }
        });
        var license = field("Optional SPDX expression", "export-license");
        var modIds = field("Decimal IDs separated by commas", "export-replace");
        var replaceDac = field("Optional hex notes separated by commas", "export-replace-dac");
        var dpcmClosed = new CheckBox("DPCM closed (quantise WAV samples)");
        dpcmClosed.setId("export-dpcm-closed");
        showWhen(dpcmClosed, kitSelected);
        var licenseRow = fieldRow("License", license);
        showWhen(licenseRow, portableSelected);
        var modRow = fieldRow("Replace music IDs", modIds);
        showWhen(modRow, modSelected);
        var dacRow = fieldRow("Replace DAC notes", replaceDac);
        showWhen(dacRow, kitSelected);

        var packageGame = RomGame.parse(target.rulebook().id());
        var roms = new SimpleObjectProperty<List<RomLibrary.Entry>>();
        var libraryError = new SimpleStringProperty("");
        var registered = Bindings.createObjectBinding(() -> entry(roms.get(), packageGame), roms);

        var useRom = new CheckBox("Use ROM for stock assets");
        useRom.setId("export-use-rom");
        useRom.setSelected(true);
        var override = new SimpleObjectProperty<Path>();
        var rom = Bindings.createObjectBinding(() -> {
            if (!useRom.isSelected()) return null;
            if (override.get() != null) return override.get();
            var entry = registered.get();
            return verified(entry) ? Path.of(entry.path()) : null;
        }, useRom.selectedProperty(), override, registered);
        var romName = new Label();
        romName.setId("export-package-rom");
        romName.setMaxWidth(435);
        romName.textProperty().bind(Bindings.createStringBinding(() -> {
            if (!useRom.isSelected()) return "Exporting without a ROM";
            if (rom.get() != null) {
                return (override.get() == null ? "Registered ROM · " : "This export only · ") + rom.get().getFileName();
            }
            if (!libraryError.get().isEmpty()) return "Could not read ROM settings";
            var entry = registered.get();
            if (entry == null) return "Checking the ROM library…";
            if (entry.status().equals("changed-on-disk")) return "Saved " + driver.getText() + " ROM changed on disk";
            return "No " + driver.getText() + " ROM registered";
        }, rom, registered, override, useRom.selectedProperty(), libraryError));
        var romDetails = new Tooltip();
        romDetails.textProperty().bind(Bindings.createStringBinding(() -> {
            if (!useRom.isSelected()) return "";
            if (rom.get() != null) return rom.get().toString();
            if (!libraryError.get().isEmpty()) return libraryError.get();
            var entry = registered.get();
            return entry != null && entry.detail() != null ? entry.detail() : "";
        }, rom, registered, useRom.selectedProperty(), libraryError));
        romName.tooltipProperty().bind(Bindings.when(romDetails.textProperty().isEmpty())
                .then((Tooltip) null).otherwise(romDetails));
        showWhen(romName, packageSelected);
        var alternateRom = new Hyperlink("Use another ROM for this export…");
        alternateRom.setId("export-rom-override");
        alternateRom.setTooltip(new Tooltip("Use a matching stock-asset source, such as standalone Sonic 3 or Sonic & Knuckles."));
        alternateRom.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("Choose a ROM for this export only");
            var file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file != null) override.set(file.toPath());
        });
        var resetRom = new Hyperlink("Use registered ROM");
        resetRom.setOnAction(event -> override.set(null));
        var choosingRom = packageSelected.and(useRom.selectedProperty());
        showWhen(alternateRom, choosingRom.and(override.isNull()));
        showWhen(resetRom, choosingRom.and(override.isNotNull()));

        var settings = new Button("ROM settings…");
        settings.setId("export-rom-settings");
        settings.disableProperty().bind(roms.isNull());
        var romActions = new HBox(8, settings, alternateRom, resetRom);
        romActions.setAlignment(Pos.CENTER_LEFT);
        Runnable refreshRoms = () -> {
            libraryError.set("");
            roms.set(null);
            var job = new Task<List<RomLibrary.Entry>>() {
                @Override protected List<RomLibrary.Entry> call() throws Exception {
                    return library.list();
                }
            };
            job.setOnSucceeded(event -> roms.set(job.getValue()));
            job.setOnFailed(event -> {
                libraryError.set(job.getException().getMessage());
                roms.set(List.of());
            });
            worker.execute(job);
        };
        settings.setOnAction(event -> {
            RomSettingsDialog.show(dialog.getDialogPane().getScene().getWindow(), library, worker);
            refreshRoms.run();
        });

        var driverRow = new HBox(8, new Label("Target driver"), driver);
        showWhen(driverRow, portableSelected.or(kitSelected));
        var packageForm = new VBox(10, driverRow, includeSource, retailPatch, licenseRow, modRow, dpcmClosed, dacRow);
        if (!redacted) packageForm.getChildren().add(useRom);
        packageForm.getChildren().add(romName);
        showWhen(packageForm, packageSelected);

        var body = new VBox(14, new HBox(14, new Label("Format"), formats), form, note, packageForm, romActions, error);
        if (redacted) body.getChildren().addFirst(new Label("Choose a ROM to resolve this project's stock references."));
        else body.getChildren().add(new Separator());
        body.setPadding(new Insets(8, 6, 4, 6));
        dialog.getDialogPane().setContent(body);
        formats.valueProperty().addListener((ignored, previous, value) -> {
            note.setText(formatNote(value));
            error.setText("");
            var window = dialog.getDialogPane().getScene().getWindow();
            // Apply managed/visible changes before asking the window for its new preferred size.
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            window.sizeToScene();
        });
        note.setText(formatNote(formats.getValue()));

        var result = new ProjectExport.Options[1];
        var confirm = (Region) dialog.getDialogPane().lookupButton(export);
        ButtonBar.setButtonUniformSize(confirm, false);
        confirm.setMinWidth(172);
        confirm.disableProperty().bind(choosingRom.and(override.isNull()).and(roms.isNull())
                .or(Bindings.createBooleanBinding(() -> redacted && rom.get() == null, rom)));
        confirm.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            Format format = formats.getValue();
            boolean audio = format.audio();
            boolean bounded = audio && span.isSelected();
            boolean split = audio && stems.isSelected();
            try {
                result[0] = packageSelected.get()
                        ? packageOptions.options(format, rom.get(), includeSource.isSelected(), retailPatch.isSelected(),
                                license.getText(), modIds.getText(), dpcmClosed.isSelected(), replaceDac.getText())
                        : new ProjectExport.Options(format,
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
        var testRequested = new javafx.beans.property.SimpleBooleanProperty();
        var testDriver = new javafx.beans.property.SimpleObjectProperty<>(game);
        if (!redacted) addTestInGame(body, testDriver, !nativeBank, roms, libraryError, () -> {
            testRequested.set(true);
            dialog.close();
        });
        refreshRoms.run();
        var options = dialog.showAndWait();
        if (testRequested.get()) {
            TestInGameDialog.show(owner, name, source, projectPath, testDriver.get(), library, worker, status);
        } else {
            options.ifPresent(value -> {
                Callable<ProjectExport.Source> exportSource = journal == null && project == null && ExportPackageOptions.supports(value.format())
                        ? () -> new ProjectExport.Source(ProjectFiles.loadSource(work.resolve("arrangement.smps.json")), work)
                        : source;
                Path verifyRom = packageSelected.get() && override.get() == null ? rom.get() : null;
                chooseDestination(owner, exportSource, worker, status, value, packageGame, verifyRom);
            });
        }
    }

    private static void addTestInGame(VBox body, ObjectProperty<RomGame> gameSelection, boolean editable,
                                      ObjectProperty<List<RomLibrary.Entry>> roms, SimpleStringProperty libraryError,
                                      Runnable openTestInGame) {
        var test = new Button("Test in game…");
        test.setId("export-test-in-game");
        var hint = new Label();
        hint.setId("export-rom-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(435);
        var actions = new HBox(8, test);
        if (editable) {
            var target = new ChoiceBox<RomGame>();
            target.setId("test-in-game-driver");
            target.setAccessibleText("Test in game target");
            target.getItems().setAll(RomGame.S3K, RomGame.S1, RomGame.S2);
            target.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(RomGame value) { return TestInGameDialog.gameName(value); }
                @Override public RomGame fromString(String value) { throw new UnsupportedOperationException(); }
            });
            target.valueProperty().bindBidirectional(gameSelection);
            actions.getChildren().addFirst(target);
        }
        body.getChildren().addAll(actions, hint);

        var selected = Bindings.createObjectBinding(() -> entry(roms.get(), gameSelection.get()), roms, gameSelection);
        test.disableProperty().bind(Bindings.createBooleanBinding(() -> !verified(selected.get()), selected));
        hint.textProperty().bind(Bindings.createStringBinding(() -> {
            if (!libraryError.get().isEmpty()) return "Could not read ROM settings. " + libraryError.get();
            var entry = selected.get();
            if (entry == null) return "Checking the ROM library…";
            return switch (entry.status()) {
                case "ok" -> entry.revision() + " ROM verified.";
                case "changed-on-disk" -> "The saved ROM changed on disk. Select a clean ROM in ROM settings.";
                default -> "Select a clean " + gameSelection.get().id().toUpperCase(java.util.Locale.ROOT)
                        + " ROM in ROM settings.";
            };
        }, selected, libraryError));
        test.setOnAction(event -> openTestInGame.run());
    }

    private static RomLibrary.Entry entry(List<RomLibrary.Entry> entries, RomGame game) {
        return entries == null ? null : entries.stream().filter(entry -> entry.game().equals(game.id()))
                .findFirst().orElse(null);
    }

    private static boolean verified(RomLibrary.Entry entry) {
        return entry != null && entry.status().equals("ok");
    }

    private static void showWhen(javafx.scene.Node node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(condition);
    }

    private static HBox fieldRow(String label, TextField field) {
        var row = new HBox(8, new Label(label), field);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
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
            case PORTABLE -> "Eligible stock assets become references resolved from the player's ROM.";
            case MOD -> "OpenGGF music replacement mod.";
            case DISASM -> "SMPS2ASM files for the project driver. A ROM materialises stock assets for personal use.";
        };
    }

    private static void chooseDestination(Window owner, Callable<ProjectExport.Source> source,
                                          Executor worker, Consumer<String> status, ProjectExport.Options options,
                                          RomGame game, Path registeredRom) {
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
        var progress = new Dialog<ButtonType>();
        progress.initOwner(owner);
        var job = new Task<Boolean>() {
            @Override protected Boolean call() throws Exception {
                updateMessage("Preparing canonical export source…");
                var opened = source.call();
                if (ExportPackageOptions.supports(options.format())) {
                    updateMessage("Checking package and ROM references…");
                    if (registeredRom != null) VerifiedRom.read(game, registeredRom);
                    var prepared = ProjectExport.preparePackage(opened.project(), options);
                    var messages = new ArrayList<String>();
                    for (var warning : prepared.warnings()) {
                        String detail = ProjectExport.warningMessage(options.format(), warning);
                        messages.add(warning.equals(detail) ? detail : warning + "\n" + detail);
                    }
                    var roms = options.format() == Format.DISASM ? options.disasm().roms() : options.portable().roms();
                    if (!roms.isEmpty()) {
                        if (!prepared.warnings().contains("UNKNOWN_ROM_IMAGE")) messages.add("Known ROM image fingerprint verified.");
                        var matches = prepared.matchedStockAssets();
                        messages.add(matches.isEmpty() ? "There are no stock asset references to check."
                                : "Matched stock assets\n" + String.join("\n", matches));
                    }
                    if (!messages.isEmpty()) {
                        var confirmation = new java.util.concurrent.FutureTask<>(() -> {
                            if (isCancelled()) return false;
                            var alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL);
                            alert.initOwner(progress.getDialogPane().getScene().getWindow());
                            alert.setTitle("Package review");
                            alert.setHeaderText("Export this package?");
                            setDetails(alert, String.join("\n\n", messages));
                            return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
                        });
                        javafx.application.Platform.runLater(confirmation);
                        if (!confirmation.get()) return false;
                    }
                    prepared.write(output, this::report);
                } else {
                    ProjectExport.write(opened, output, options, this::report);
                }
                return true;
            }

            private void report(int done, int total, String message) {
                if (isCancelled()) throw new java.util.concurrent.CancellationException();
                updateProgress(done, total);
                updateMessage(message);
            }
        };

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
            var failure = job.getException();
            var bankLimit = BankLimitException.from(failure);
            String detail = bankLimit == null ? failure.getMessage() : bankLimit.getMessage();
            status.accept("Export failed · " + detail);
            var alert = new Alert(Alert.AlertType.ERROR);
            setDetails(alert, detail);
            alert.initOwner(owner);
            alert.setHeaderText("Could not export arrangement");
            alert.getDialogPane().setPrefWidth(500);
            alert.setResizable(true);
            alert.show();
        });
        job.setOnCancelled(event -> {
            progress.close();
            status.accept("Export cancelled");
        });
        progress.show();
        worker.execute(job);
    }
    private static void setDetails(Alert alert, String detail) {
        var text = new TextArea(detail);
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefColumnCount(52);
        text.setPrefRowCount(Math.clamp(detail.lines().count(), 3, 13));
        alert.getDialogPane().setContent(text);
        alert.setResizable(true);
    }

}
