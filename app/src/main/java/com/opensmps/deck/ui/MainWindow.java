package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.io.ImportableVoice;
import com.opensmps.deck.io.ProjectFile;
import com.opensmps.deck.io.SmpsExporter;
import com.opensmps.deck.io.SmpsImporter;
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

        fileMenu.getItems().addAll(newItem, openItem, new SeparatorMenuItem(),
                saveItem, saveAsItem, separator, exportItem, exportWavItem,
                new SeparatorMenuItem(), importVoicesItem, importSmpsItem);

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

    private void updateTitle() {
        SongTab tab = getActiveSongTab();
        String filename = "Untitled";
        if (tab != null) {
            filename = tab.getFile() != null ? tab.getFile().getName() : tab.getSong().getName();
        }
        stage.setTitle("OpenSMPS Deck - " + filename);
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
