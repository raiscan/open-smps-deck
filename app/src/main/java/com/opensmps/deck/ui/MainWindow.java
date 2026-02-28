package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.model.Song;
import javafx.scene.Scene;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * Main application window with BorderPane layout.
 *
 * <p>Layout: TransportBar (top), TrackerGrid (center),
 * OrderListPanel (bottom), InstrumentPanel (right).
 */
public class MainWindow {

    private final Stage stage;
    private final BorderPane root;
    private final Song currentSong;
    private final PlaybackEngine playbackEngine;
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
        // Top: Transport bar
        TransportBar transportBar = new TransportBar(playbackEngine, currentSong);
        root.setTop(transportBar);

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
