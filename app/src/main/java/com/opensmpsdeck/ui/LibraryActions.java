package com.opensmpsdeck.ui;

import com.opensmpsdeck.io.InstrumentLibraryFile;
import com.opensmpsdeck.library.DeployResult;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.library.InstrumentLibraryDeployer;
import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.LibraryPaths;
import com.opensmpsdeck.library.scan.InstrumentLibraryScanner;
import com.opensmpsdeck.library.scan.ScanSummary;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

final class LibraryActions {

    private final Stage stage;
    private final Supplier<SongTab> activeSongTab;
    private final Runnable refreshTitles;

    LibraryActions(Stage stage, Supplier<SongTab> activeSongTab, Runnable refreshTitles) {
        this.stage = stage;
        this.activeSongTab = activeSongTab;
        this.refreshTitles = refreshTitles;
    }

    void onOpenLibrary() {
        SongTab songTab = activeSongTab.get();
        if (songTab == null) {
            showWarning("No active song", "Open or create a song before deploying library instruments.");
            return;
        }

        InstrumentLibrary library;
        try {
            library = InstrumentLibraryFile.load(LibraryPaths.getLibraryRoot());
        } catch (Exception ex) {
            showError("Failed to load instrument library", message(ex));
            return;
        }

        LibraryBrowserDialog dialog = new LibraryBrowserDialog(library);
        dialog.initOwner(stage);
        Optional<List<InstrumentLibraryEntry>> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return;
        }
        if (selected.get().isEmpty()) {
            showWarning("No assets selected", "Select one or more library assets to deploy.");
            return;
        }

        DeployResult result;
        try {
            result = InstrumentLibraryDeployer.deploy(songTab.getSong(), selected.get());
        } catch (Exception ex) {
            showError("Failed to deploy library assets", message(ex));
            return;
        }

        if (songTab.getInstrumentPanel() != null) {
            songTab.getInstrumentPanel().refresh();
        }
        if (result.appendedCount() > 0) {
            songTab.setDirty(true);
            refreshTitles.run();
        }
        showInfo(
                "Library assets deployed",
                "Added: " + result.appendedCount() + "\nReused existing: " + result.reusedCount());
    }

    void onScanFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Scan Instrument Library Folder");
        DialogPaths.applyTo(chooser, "instrumentLibraryScan");
        File selectedDir = chooser.showDialog(stage);
        DialogPaths.remember("instrumentLibraryScan", selectedDir);
        if (selectedDir == null) {
            return;
        }

        Path scanRoot = selectedDir.toPath();
        Path libraryRoot = LibraryPaths.getLibraryRoot();
        ProgressDialog progressDialog = createProgressDialog("Scanning instrument library...");
        Task<ScanSummary> scanTask = new Task<>() {
            @Override
            protected ScanSummary call() throws Exception {
                updateMessage("Loading library...");
                InstrumentLibrary library = InstrumentLibraryFile.load(libraryRoot);
                updateMessage("Scanning " + scanRoot + "...");
                ScanSummary summary = new InstrumentLibraryScanner().scan(scanRoot, library);
                if (library.isDirty()) {
                    updateMessage("Saving library...");
                    InstrumentLibraryFile.save(library, libraryRoot);
                }
                updateMessage("Done");
                return summary;
            }
        };

        progressDialog.statusLabel().textProperty().bind(scanTask.messageProperty());

        scanTask.setOnSucceeded(event -> {
            progressDialog.dialog().close();
            ScanSummaryDialog summaryDialog = new ScanSummaryDialog(scanTask.getValue());
            summaryDialog.initOwner(stage);
            summaryDialog.showAndWait();
        });
        scanTask.setOnFailed(event -> {
            progressDialog.dialog().close();
            Throwable ex = scanTask.getException();
            showError("Failed to scan instrument library", ex == null ? "Unknown error" : message(ex));
        });

        Thread thread = new Thread(scanTask, "Instrument-Library-Scan");
        thread.setDaemon(true);
        progressDialog.dialog().show();
        thread.start();
    }

    void onLibraryLocation() {
        LibraryLocationDialog dialog = new LibraryLocationDialog();
        dialog.initOwner(stage);
        dialog.showAndWait().ifPresent(LibraryPaths::setLibraryRoot);
    }

    private ProgressDialog createProgressDialog(String header) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Instrument Library");
        dialog.setHeaderText(header);

        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(32, 32);
        Label status = new Label("Starting...");
        HBox content = new HBox(12, progress, status);
        content.setPadding(new Insets(10));

        DialogPane pane = dialog.getDialogPane();
        pane.setContent(content);
        pane.setPrefWidth(420);
        return new ProgressDialog(dialog, status);
    }

    private void showInfo(String header, String content) {
        showAlert(Alert.AlertType.INFORMATION, "Instrument Library", header, content);
    }

    private void showWarning(String header, String content) {
        showAlert(Alert.AlertType.WARNING, "Instrument Library", header, content);
    }

    private void showError(String header, String content) {
        showAlert(Alert.AlertType.ERROR, "Instrument Library Error", header, content);
    }

    private void showAlert(Alert.AlertType type, String title, String header, String content) {
        Alert alert = new Alert(type);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private static String message(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record ProgressDialog(Dialog<Void> dialog, Label statusLabel) {
    }
}
