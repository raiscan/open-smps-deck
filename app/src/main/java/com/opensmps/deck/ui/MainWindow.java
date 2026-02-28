package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.model.Song;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.Stage;

public class MainWindow {

    private final Stage stage;
    private final BorderPane root;
    private final Song currentSong;
    private final PlaybackEngine playbackEngine;

    public MainWindow(Stage stage) {
        this.stage = stage;
        this.currentSong = new Song();
        this.playbackEngine = new PlaybackEngine();
        this.root = new BorderPane();

        setupLayout();
        setupStage();
    }

    private void setupLayout() {
        // Top: Transport bar placeholder
        HBox transportPlaceholder = new HBox();
        transportPlaceholder.setPadding(new Insets(8));
        transportPlaceholder.setStyle("-fx-background-color: #2a2a2a;");
        transportPlaceholder.getChildren().add(createLabel("Transport"));
        root.setTop(transportPlaceholder);

        // Center: Tracker grid placeholder
        StackPane gridPlaceholder = new StackPane();
        gridPlaceholder.setStyle("-fx-background-color: #1a1a2e;");
        gridPlaceholder.getChildren().add(createLabel("Tracker Grid"));
        root.setCenter(gridPlaceholder);

        // Bottom: Order list placeholder
        StackPane orderPlaceholder = new StackPane();
        orderPlaceholder.setPrefHeight(150);
        orderPlaceholder.setStyle("-fx-background-color: #1e1e1e;");
        orderPlaceholder.getChildren().add(createLabel("Order List"));
        root.setBottom(orderPlaceholder);

        // Right: Instrument panel placeholder
        StackPane instrumentPlaceholder = new StackPane();
        instrumentPlaceholder.setPrefWidth(250);
        instrumentPlaceholder.setStyle("-fx-background-color: #252525;");
        instrumentPlaceholder.getChildren().add(createLabel("Instruments"));
        root.setRight(instrumentPlaceholder);
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

    private Label createLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #888888; -fx-font-size: 18px;");
        return label;
    }
}
