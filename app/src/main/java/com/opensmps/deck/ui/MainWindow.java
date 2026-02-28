package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.io.ProjectFile;
import com.opensmps.deck.io.SmpsExporter;
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

        fileMenu.getItems().addAll(newItem, openItem, new SeparatorMenuItem(),
                saveItem, saveAsItem, separator, exportItem);

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

    private void onExportSmps() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export SMPS Binary");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("SMPS Binary", "*.bin"));
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                SmpsExporter exporter = new SmpsExporter();
                exporter.export(tab.getSong(), file);
            } catch (IOException ex) {
                showError("Failed to export SMPS", ex.getMessage());
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
