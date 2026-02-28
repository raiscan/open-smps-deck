package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.io.ProjectFile;
import com.opensmps.deck.io.SmpsExporter;
import com.opensmps.deck.model.Song;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

/**
 * Main application window with BorderPane layout.
 *
 * <p>Layout: MenuBar + TransportBar (top), TrackerGrid (center),
 * OrderListPanel (bottom), InstrumentPanel (right).
 */
public class MainWindow {

    private final Stage stage;
    private final BorderPane root;
    private Song currentSong;
    private final PlaybackEngine playbackEngine;
    private File currentFile;
    private TrackerGrid trackerGrid;
    private OrderListPanel orderListPanel;
    private InstrumentPanel instrumentPanel;

    public MainWindow(Stage stage) {
        this.stage = stage;
        this.currentSong = new Song();
        this.playbackEngine = new PlaybackEngine();
        this.root = new BorderPane();

        setupLayout();
        setupStage();
    }

    private void setupLayout() {
        // Top: MenuBar + Transport bar in a VBox
        MenuBar menuBar = createMenuBar();
        TransportBar transportBar = new TransportBar(playbackEngine, currentSong);
        VBox topContainer = new VBox(menuBar, transportBar);
        root.setTop(topContainer);

        // Center: Tracker grid
        trackerGrid = new TrackerGrid();
        trackerGrid.setSong(currentSong);
        root.setCenter(trackerGrid);

        // Bottom: Order list
        orderListPanel = new OrderListPanel(currentSong);
        orderListPanel.setOnOrderRowSelected(rowIndex -> {
            if (!currentSong.getOrderList().isEmpty()) {
                int[] orderRow = currentSong.getOrderList().get(rowIndex);
                // Use the first channel's pattern index to display
                trackerGrid.setCurrentPatternIndex(orderRow[0]);
            }
        });
        root.setBottom(orderListPanel);

        // Right: Instrument panel
        instrumentPanel = new InstrumentPanel(currentSong);
        root.setRight(instrumentPanel);

        // Wire instrument selection to tracker grid note entry
        trackerGrid.setInstrumentPanel(instrumentPanel);
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
        currentSong = new Song();
        currentFile = null;
        refreshAllPanels();
        updateTitle();
    }

    private void onOpen() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Project");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("OpenSMPS Deck Project", "*.osmpsd"));
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try {
                currentSong = ProjectFile.load(file);
                currentFile = file;
                refreshAllPanels();
                updateTitle();
            } catch (IOException ex) {
                showError("Failed to open project", ex.getMessage());
            }
        }
    }

    private void onSave() {
        if (currentFile != null) {
            saveToFile(currentFile);
        } else {
            onSaveAs();
        }
    }

    private void onSaveAs() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Project As");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("OpenSMPS Deck Project", "*.osmpsd"));
        if (currentFile != null) {
            fileChooser.setInitialDirectory(currentFile.getParentFile());
            fileChooser.setInitialFileName(currentFile.getName());
        }
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            saveToFile(file);
        }
    }

    private void saveToFile(File file) {
        try {
            ProjectFile.save(currentSong, file);
            currentFile = file;
            updateTitle();
        } catch (IOException ex) {
            showError("Failed to save project", ex.getMessage());
        }
    }

    private void onExportSmps() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export SMPS Binary");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("SMPS Binary", "*.bin"));
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                SmpsExporter exporter = new SmpsExporter();
                exporter.export(currentSong, file);
            } catch (IOException ex) {
                showError("Failed to export SMPS", ex.getMessage());
            }
        }
    }

    private void refreshAllPanels() {
        trackerGrid.setSong(currentSong);
        orderListPanel.setSong(currentSong);
        orderListPanel.setOnOrderRowSelected(rowIndex -> {
            if (!currentSong.getOrderList().isEmpty()) {
                int[] orderRow = currentSong.getOrderList().get(rowIndex);
                trackerGrid.setCurrentPatternIndex(orderRow[0]);
            }
        });
        instrumentPanel.setSong(currentSong);
    }

    private void updateTitle() {
        String filename = currentFile != null ? currentFile.getName() : "Untitled";
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
        stage.setTitle("OpenSMPS Deck - " + currentSong.getName());
        stage.setMinWidth(800);
        stage.setMinHeight(600);
    }

    public void show() {
        stage.show();
    }

    public Song getCurrentSong() {
        return currentSong;
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
        return trackerGrid;
    }

    public OrderListPanel getOrderListPanel() {
        return orderListPanel;
    }

    public InstrumentPanel getInstrumentPanel() {
        return instrumentPanel;
    }
}
