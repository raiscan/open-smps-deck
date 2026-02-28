package com.opensmps.deck.ui;

import com.opensmps.deck.io.ImportableVoice;
import com.opensmps.deck.io.ProjectFile;
import com.opensmps.deck.io.Rym2612Importer;
import com.opensmps.deck.io.SmpsExporter;
import com.opensmps.deck.io.SmpsImporter;
import com.opensmps.deck.io.VoiceBankFile;
import com.opensmps.deck.io.WavExporter;
import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Song;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Encapsulates file/import/export workflows for {@link MainWindow}.
 *
 * <p>This keeps layout/tab orchestration in {@link MainWindow}, while project and
 * document operations live in a focused action handler.
 */
final class MainWindowFileActions {

    private final Stage stage;
    private final Supplier<SongTab> activeSongTabSupplier;
    private final Consumer<SongTab> addTabConsumer;
    private final Runnable refreshTitles;
    private final BiConsumer<String, String> errorReporter;

    MainWindowFileActions(Stage stage,
                          Supplier<SongTab> activeSongTabSupplier,
                          Consumer<SongTab> addTabConsumer,
                          Runnable refreshTitles,
                          BiConsumer<String, String> errorReporter) {
        this.stage = stage;
        this.activeSongTabSupplier = activeSongTabSupplier;
        this.addTabConsumer = addTabConsumer;
        this.refreshTitles = refreshTitles;
        this.errorReporter = errorReporter;
    }

    /** Opens an `.osmpsd` project and adds it as a new tab. */
    void onOpen() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Project");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("OpenSMPS Deck Project", "*.osmpsd"));
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try {
                Song song = ProjectFile.load(file);
                SongTab tab = new SongTab(song);
                tab.setFile(file);
                addTabConsumer.accept(tab);
            } catch (IOException ex) {
                showError("Failed to open project", ex.getMessage());
            }
        }
    }

    /** Saves the active tab to its current file, or delegates to Save As if unsaved. */
    void onSave() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        if (tab.getFile() != null) {
            saveToFile(tab, tab.getFile());
        } else {
            onSaveAs();
        }
    }

    /** Prompts for a file path and saves the active tab as an `.osmpsd` project. */
    void onSaveAs() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Project As");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("OpenSMPS Deck Project", "*.osmpsd"));
        if (tab.getFile() != null) {
            fileChooser.setInitialDirectory(tab.getFile().getParentFile());
            fileChooser.setInitialFileName(tab.getFile().getName());
        }
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            saveToFile(tab, file);
        }
    }

    /** Exports the active song as a compiled SMPS binary. */
    void onExportSmps() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        File file = showFileDialog("Export SMPS Binary", "SMPS Binary", "*.bin", true);
        if (file != null) {
            try {
                SmpsExporter exporter = new SmpsExporter();
                exporter.export(tab.getSong(), file);
            } catch (IOException ex) {
                showError("Failed to export SMPS", ex.getMessage());
            }
        }
    }

    /**
     * Exports the active song as WAV on a background thread.
     *
     * <p>Shows a settings dialog allowing the user to configure loop count,
     * fade out toggle, fade duration, and fade mode (Extend/Inset) before
     * rendering. The export respects current tracker mute/solo state.
     */
    void onExportWav() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        if (tab.getSong().getPatterns().isEmpty()) {
            showError("Cannot export", "Song has no patterns to export.");
            return;
        }
        File file = showFileDialog("Export WAV Audio", "WAV Audio", "*.wav", true);
        if (file == null) return;

        // Build settings dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("WAV Export Settings");
        dialog.setHeaderText("Configure export options");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Spinner<Integer> loopSpinner = new Spinner<>(1, 99, 2);
        loopSpinner.setEditable(true);

        CheckBox fadeCheckBox = new CheckBox("Enable fade out");
        fadeCheckBox.setSelected(true);

        Spinner<Double> fadeSpinner = new Spinner<>(0.1, 30.0, 3.0, 0.1);
        fadeSpinner.setEditable(true);

        ComboBox<String> fadeModeCombo = new ComboBox<>();
        fadeModeCombo.getItems().addAll("Extend", "Inset");
        fadeModeCombo.setValue("Extend");

        fadeCheckBox.selectedProperty().addListener((obs, old, checked) -> {
            fadeSpinner.setDisable(!checked);
            fadeModeCombo.setDisable(!checked);
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Loop Count:"), 0, 0);
        grid.add(loopSpinner, 1, 0);
        grid.add(fadeCheckBox, 0, 1, 2, 1);
        grid.add(new Label("Fade Duration (seconds):"), 0, 2);
        grid.add(fadeSpinner, 1, 2);
        grid.add(new Label("Fade Mode:"), 0, 3);
        grid.add(fadeModeCombo, 1, 3);
        dialog.getDialogPane().setContent(grid);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        int loops = loopSpinner.getValue();
        boolean fadeEnabled = fadeCheckBox.isSelected();
        double fadeDuration = fadeSpinner.getValue();
        boolean fadeExtend = "Extend".equals(fadeModeCombo.getValue());

        boolean[] mutedChannels = new boolean[10];
        TrackerGrid trackerGrid = tab.getTrackerGrid();
        for (int ch = 0; ch < 10; ch++) {
            mutedChannels[ch] = trackerGrid.isChannelMuted(ch);
        }
        Song song = tab.getSong();

        Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
        progressAlert.setTitle("Exporting WAV");
        progressAlert.setHeaderText("Rendering audio...");
        progressAlert.setContentText("Please wait while the song is exported.");
        progressAlert.getButtonTypes().clear();
        progressAlert.show();

        Thread exportThread = new Thread(() -> {
            try {
                WavExporter exporter = new WavExporter();
                exporter.setLoopCount(loops);
                exporter.setMutedChannels(mutedChannels);
                exporter.setFadeEnabled(fadeEnabled);
                exporter.setFadeDurationSeconds(fadeDuration);
                exporter.setFadeExtend(fadeExtend);
                exporter.export(song, file);
                Platform.runLater(progressAlert::close);
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    progressAlert.close();
                    showError("Failed to export WAV", ex.getMessage());
                });
            }
        }, "WAV-Export");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    /** Imports selected FM voices into the active song's voice bank. */
    void onImportVoices() {
        SongTab songTab = getActiveSongTab();
        if (songTab == null) return;

        VoiceImportDialog dialog = new VoiceImportDialog();
        Optional<java.util.List<ImportableVoice>> result = dialog.showAndWait();
        if (result.isPresent() && !result.get().isEmpty()) {
            for (ImportableVoice iv : result.get()) {
                songTab.getSong().getVoiceBank().add(
                        new FmVoice(iv.sourceSong() + " #" + iv.originalIndex(), iv.voiceData()));
            }
            songTab.getInstrumentPanel().refresh();
            songTab.setDirty(true);
            refreshTitles.run();
        }
    }

    /** Imports an SMPS binary as a new song tab. */
    void onImportSmps() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import SMPS Binary");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All SMPS Files", "*.bin", "*.s3k", "*.sm2", "*.smp"),
                new FileChooser.ExtensionFilter("SMPS Binary", "*.bin"),
                new FileChooser.ExtensionFilter("SMPSPlay S3K", "*.s3k"),
                new FileChooser.ExtensionFilter("SMPSPlay S2", "*.sm2"),
                new FileChooser.ExtensionFilter("SMPSPlay S1", "*.smp")
        );
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try {
                SmpsImporter smpsImporter = new SmpsImporter();
                Song song = smpsImporter.importFile(file);
                SongTab songTab = new SongTab(song);
                addTabConsumer.accept(songTab);
            } catch (Exception ex) {
                showError("Failed to import SMPS", ex.getMessage());
            }
        }
    }

    /** Imports voices/envelopes from `.ovm` or `.rym2612` into the active song. */
    void onImportVoiceBank() {
        SongTab songTab = getActiveSongTab();
        if (songTab == null) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Voice Bank");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Voice Files", "*.ovm", "*.rym2612"),
                new FileChooser.ExtensionFilter("OpenSMPS Voice Bank", "*.ovm"),
                new FileChooser.ExtensionFilter("RYM2612 Patch", "*.rym2612")
        );
        File file = fileChooser.showOpenDialog(stage);
        if (file == null) return;

        try {
            Song song = songTab.getSong();
            boolean changed = false;
            if (file.getName().toLowerCase().endsWith(".rym2612")) {
                FmVoice voice = Rym2612Importer.importFile(file);
                song.getVoiceBank().add(voice);
                changed = true;
            } else {
                VoiceBankFile.LoadResult result = VoiceBankFile.load(file);
                if (result.voices().size() <= 1) {
                    song.getVoiceBank().addAll(result.voices());
                    song.getPsgEnvelopes().addAll(result.psgEnvelopes());
                    changed = !result.voices().isEmpty() || !result.psgEnvelopes().isEmpty();
                } else {
                    String bankName = result.name() != null ? result.name() : file.getName();
                    java.util.List<ImportableVoice> importable = new java.util.ArrayList<>();
                    for (int i = 0; i < result.voices().size(); i++) {
                        FmVoice v = result.voices().get(i);
                        importable.add(new ImportableVoice(
                                bankName, i, v.getData(), v.getAlgorithm()));
                    }
                    VoiceImportDialog dialog = new VoiceImportDialog(importable);
                    Optional<java.util.List<ImportableVoice>> selected = dialog.showAndWait();
                    if (selected.isPresent() && !selected.get().isEmpty()) {
                        for (ImportableVoice iv : selected.get()) {
                            song.getVoiceBank().add(
                                    new FmVoice(iv.sourceSong() + " #" + iv.originalIndex(), iv.voiceData()));
                        }
                        changed = true;
                    }
                    song.getPsgEnvelopes().addAll(result.psgEnvelopes());
                    changed = changed || !result.psgEnvelopes().isEmpty();
                }
            }
            songTab.getInstrumentPanel().refresh();
            if (changed) {
                songTab.setDirty(true);
                refreshTitles.run();
            }
        } catch (Exception ex) {
            showError("Failed to import voice bank", ex.getMessage());
        }
    }

    /** Exports the active song's FM voices and PSG envelopes to an `.ovm` file. */
    void onExportVoiceBank() {
        SongTab songTab = getActiveSongTab();
        if (songTab == null) return;

        Song song = songTab.getSong();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Voice Bank");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("OpenSMPS Voice Bank", "*.ovm"));
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                VoiceBankFile.save(song.getName(), song.getVoiceBank(),
                        song.getPsgEnvelopes(), file);
            } catch (IOException ex) {
                showError("Failed to export voice bank", ex.getMessage());
            }
        }
    }

    private SongTab getActiveSongTab() {
        return activeSongTabSupplier.get();
    }

    private void saveToFile(SongTab tab, File file) {
        try {
            ProjectFile.save(tab.getSong(), file);
            tab.setFile(file);
            tab.setDirty(false);
            refreshTitles.run();
        } catch (IOException ex) {
            showError("Failed to save project", ex.getMessage());
        }
    }

    private File showFileDialog(String title, String desc, String ext, boolean save) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(desc, ext));
        return save ? fileChooser.showSaveDialog(stage) : fileChooser.showOpenDialog(stage);
    }

    private void showError(String header, String content) {
        errorReporter.accept(header, content);
    }
}
