package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
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
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

/**
 * Main application window with tab-based multi-document layout.
 *
 * <p>Layout: MenuBar + TransportBar (top), TabPane (center).
 * Each tab wraps a {@link SongTab} with its own TrackerGrid, OrderListPanel,
 * and InstrumentPanel.
 */
public class MainWindow {

    private final Stage stage;
    private final BorderPane root;
    private final PlaybackEngine playbackEngine;
    private final TabPane tabPane = new TabPane();
    private TransportBar transportBar;

    public MainWindow(Stage stage) {
        this.stage = stage;
        this.playbackEngine = new PlaybackEngine();
        this.root = new BorderPane();

        setupLayout();
        setupStage();
    }

    private SongTab getActiveSongTab() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getUserData() instanceof SongTab st) {
            return st;
        }
        return null;
    }

    private Tab createSongTabUI(SongTab songTab) {
        songTab.buildContent();
        songTab.getTrackerGrid().setPlaybackEngine(playbackEngine);
        songTab.getInstrumentPanel().setPlaybackEngine(playbackEngine);
        songTab.getInstrumentPanel().setOnImportBank(this::onImportVoiceBank);

        // Wire transport callbacks for keyboard shortcuts in TrackerGrid
        songTab.getTrackerGrid().setOnTogglePlayback(() -> {
            if (playbackEngine.isPlaying()) {
                playbackEngine.stop();
            } else {
                playbackEngine.loadSong(songTab.getSong());
                playbackEngine.play();
            }
        });
        songTab.getTrackerGrid().setOnStopPlayback(() -> playbackEngine.stop());
        songTab.getTrackerGrid().setOnPlayFromCursor(() -> {
            playbackEngine.loadSong(songTab.getSong());
            playbackEngine.play();
        });

        BorderPane content = new BorderPane();
        content.setCenter(songTab.getTrackerGrid());
        content.setBottom(songTab.getOrderListPanel());
        content.setRight(songTab.getInstrumentPanel());

        songTab.getOrderListPanel().setOnOrderRowSelected(rowIndex -> {
            Song song = songTab.getSong();
            if (!song.getOrderList().isEmpty()) {
                int[] orderRow = song.getOrderList().get(rowIndex);
                songTab.getTrackerGrid().setCurrentPatternIndex(orderRow[0]);
            }
        });

        Tab tab = new Tab(songTab.getTitle(), content);
        tab.setUserData(songTab);
        tab.setClosable(true);
        songTab.setOnDirtyChanged(() -> {
            tab.setText(songTab.getTitle());
            if (tab == tabPane.getSelectionModel().getSelectedItem()) {
                updateTitle();
            }
        });
        return tab;
    }

    private void addNewTab(SongTab songTab) {
        Tab tab = createSongTabUI(songTab);
        // Insert before the [+] button tab (last tab)
        int insertIndex = Math.max(0, tabPane.getTabs().size() - 1);
        tabPane.getTabs().add(insertIndex, tab);
        tabPane.getSelectionModel().select(tab);
    }

    private void setupLayout() {
        MenuBar menuBar = createMenuBar();
        // Placeholder song; replaced by first tab's song immediately below
        transportBar = new TransportBar(playbackEngine, new Song());
        transportBar.setOnSongChanged(this::markActiveSongDirty);
        VBox topContainer = new VBox(menuBar, transportBar);
        root.setTop(topContainer);

        // TabPane with closable tabs
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        // Tab selection listener: update transport and title
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null && "+".equals(newTab.getText()) && newTab.getUserData() == null) {
                // [+] tab selected -- create new tab
                Platform.runLater(() -> addNewTab(new SongTab()));
            } else {
                SongTab st = getActiveSongTab();
                if (st != null) {
                    transportBar.setSong(st.getSong());
                    updateTitle();
                }
            }
        });

        // Initial song tab
        addNewTab(new SongTab());

        // Wire transport to the first tab's song (instead of relying on listener)
        SongTab firstTab = getActiveSongTab();
        if (firstTab != null) {
            transportBar.setSong(firstTab.getSong());
        }

        // [+] button tab (not closable)
        Tab plusTab = new Tab("+");
        plusTab.setClosable(false);
        tabPane.getTabs().add(plusTab);

        root.setCenter(tabPane);
    }

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");

        MenuItem newItem = new MenuItem("New");
        newItem.setAccelerator(KeyCombination.keyCombination("Ctrl+N"));
        newItem.setOnAction(e -> onNew());

        MenuItem openItem = new MenuItem("Open...");
        openItem.setAccelerator(KeyCombination.keyCombination("Ctrl+O"));
        openItem.setOnAction(e -> onOpen());

        MenuItem saveItem = new MenuItem("Save");
        saveItem.setAccelerator(KeyCombination.keyCombination("Ctrl+S"));
        saveItem.setOnAction(e -> onSave());

        MenuItem saveAsItem = new MenuItem("Save As...");
        saveAsItem.setAccelerator(KeyCombination.keyCombination("Ctrl+Shift+S"));
        saveAsItem.setOnAction(e -> onSaveAs());

        SeparatorMenuItem separator = new SeparatorMenuItem();

        MenuItem exportItem = new MenuItem("Export SMPS...");
        exportItem.setOnAction(e -> onExportSmps());

        MenuItem exportWavItem = new MenuItem("Export WAV...");
        exportWavItem.setOnAction(e -> onExportWav());

        MenuItem importVoicesItem = new MenuItem("Import Voices...");
        importVoicesItem.setOnAction(e -> onImportVoices());

        MenuItem importSmpsItem = new MenuItem("Import SMPS...");
        importSmpsItem.setOnAction(e -> onImportSmps());

        MenuItem importVoiceBankItem = new MenuItem("Import Voice Bank...");
        importVoiceBankItem.setOnAction(e -> onImportVoiceBank());

        MenuItem exportVoiceBankItem = new MenuItem("Export Voice Bank...");
        exportVoiceBankItem.setOnAction(e -> onExportVoiceBank());

        fileMenu.getItems().addAll(newItem, openItem, new SeparatorMenuItem(),
                saveItem, saveAsItem, separator, exportItem, exportWavItem,
                new SeparatorMenuItem(), importVoicesItem, importSmpsItem,
                new SeparatorMenuItem(), importVoiceBankItem, exportVoiceBankItem);

        menuBar.getMenus().add(fileMenu);
        return menuBar;
    }

    private void onNew() {
        addNewTab(new SongTab());
    }

    private void onOpen() {
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
                addNewTab(tab);
            } catch (IOException ex) {
                showError("Failed to open project", ex.getMessage());
            }
        }
    }

    private void onSave() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        if (tab.getFile() != null) {
            saveToFile(tab, tab.getFile());
        } else {
            onSaveAs();
        }
    }

    private void onSaveAs() {
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

    private void saveToFile(SongTab tab, File file) {
        try {
            ProjectFile.save(tab.getSong(), file);
            tab.setFile(file);
            tab.setDirty(false);
            // Update the tab text to reflect the new file name
            Tab uiTab = tabPane.getSelectionModel().getSelectedItem();
            if (uiTab != null) {
                uiTab.setText(tab.getTitle());
            }
            updateTitle();
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

    private void onExportSmps() {
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

    private void onExportWav() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        if (tab.getSong().getPatterns().isEmpty()) {
            showError("Cannot export", "Song has no patterns to export.");
            return;
        }
        File file = showFileDialog("Export WAV Audio", "WAV Audio", "*.wav", true);
        if (file != null) {
            // Capture mute state from tracker grid
            boolean[] mutedChannels = new boolean[10];
            TrackerGrid grid = tab.getTrackerGrid();
            for (int ch = 0; ch < 10; ch++) {
                mutedChannels[ch] = grid.isChannelMuted(ch);
            }
            Song song = tab.getSong();

            // Show progress dialog
            Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
            progressAlert.setTitle("Exporting WAV");
            progressAlert.setHeaderText("Rendering audio...");
            progressAlert.setContentText("Please wait while the song is exported.");
            progressAlert.getButtonTypes().clear();
            progressAlert.show();

            Thread exportThread = new Thread(() -> {
                try {
                    WavExporter exporter = new WavExporter();
                    exporter.setMutedChannels(mutedChannels);
                    exporter.export(song, file);
                    Platform.runLater(() -> {
                        progressAlert.close();
                    });
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
    }

    private void onImportVoices() {
        SongTab songTab = getActiveSongTab();
        if (songTab == null) return;

        VoiceImportDialog dialog = new VoiceImportDialog();
        java.util.Optional<java.util.List<ImportableVoice>> result = dialog.showAndWait();
        if (result.isPresent() && !result.get().isEmpty()) {
            for (ImportableVoice iv : result.get()) {
                songTab.getSong().getVoiceBank().add(
                        new FmVoice(iv.sourceSong() + " #" + iv.originalIndex(), iv.voiceData()));
            }
            songTab.getInstrumentPanel().refresh();
        }
    }

    private void onImportSmps() {
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
                addNewTab(songTab);
            } catch (Exception ex) {
                showError("Failed to import SMPS", ex.getMessage());
            }
        }
    }

    private void onImportVoiceBank() {
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
            if (file.getName().toLowerCase().endsWith(".rym2612")) {
                // Single voice -- add directly without selection dialog
                FmVoice voice = Rym2612Importer.importFile(file);
                song.getVoiceBank().add(voice);
            } else {
                VoiceBankFile.LoadResult result = VoiceBankFile.load(file);
                if (result.voices().size() <= 1) {
                    // Single or empty bank -- add directly
                    song.getVoiceBank().addAll(result.voices());
                    song.getPsgEnvelopes().addAll(result.psgEnvelopes());
                } else {
                    // Multiple voices -- show selection dialog
                    String bankName = result.name() != null ? result.name() : file.getName();
                    java.util.List<ImportableVoice> importable = new java.util.ArrayList<>();
                    for (int i = 0; i < result.voices().size(); i++) {
                        FmVoice v = result.voices().get(i);
                        importable.add(new ImportableVoice(
                                bankName, i, v.getData(), v.getAlgorithm()));
                    }
                    VoiceImportDialog dialog = new VoiceImportDialog(importable);
                    java.util.Optional<java.util.List<ImportableVoice>> selected = dialog.showAndWait();
                    if (selected.isPresent() && !selected.get().isEmpty()) {
                        for (ImportableVoice iv : selected.get()) {
                            song.getVoiceBank().add(
                                    new FmVoice(iv.sourceSong() + " #" + iv.originalIndex(), iv.voiceData()));
                        }
                    }
                    // PSG envelopes from the bank are still added (they aren't voice-selectable)
                    song.getPsgEnvelopes().addAll(result.psgEnvelopes());
                }
            }
            songTab.getInstrumentPanel().refresh();
        } catch (Exception ex) {
            showError("Failed to import voice bank", ex.getMessage());
        }
    }

    private void onExportVoiceBank() {
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

    private void updateTitle() {
        SongTab tab = getActiveSongTab();
        String filename = "Untitled";
        if (tab != null) {
            filename = tab.getTitle();
        }
        stage.setTitle("OpenSMPS Deck - " + filename);
    }

    private void markActiveSongDirty() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        tab.setDirty(true);
    }

    private void showError(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void setupStage() {
        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/style.css") != null
                ? getClass().getResource("/style.css").toExternalForm()
                : "");
        stage.setScene(scene);
        updateTitle();
        stage.setMinWidth(800);
        stage.setMinHeight(600);
    }

    public void show() {
        stage.show();
    }

    public Song getCurrentSong() {
        SongTab tab = getActiveSongTab();
        return tab != null ? tab.getSong() : null;
    }

    public PlaybackEngine getPlaybackEngine() {
        return playbackEngine;
    }

    public Stage getStage() {
        return stage;
    }

    public BorderPane getRoot() {
        return root;
    }

    public TrackerGrid getTrackerGrid() {
        SongTab tab = getActiveSongTab();
        return tab != null ? tab.getTrackerGrid() : null;
    }

    public OrderListPanel getOrderListPanel() {
        SongTab tab = getActiveSongTab();
        return tab != null ? tab.getOrderListPanel() : null;
    }

    public InstrumentPanel getInstrumentPanel() {
        SongTab tab = getActiveSongTab();
        return tab != null ? tab.getInstrumentPanel() : null;
    }
}
