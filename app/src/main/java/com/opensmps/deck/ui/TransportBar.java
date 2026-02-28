package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.model.SmpsMode;
import com.opensmps.deck.model.Song;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Transport controls bar with Play/Stop/Pause, tempo/timing spinners,
 * and SMPS mode selector.
 */
public class TransportBar extends HBox {

    private final PlaybackEngine playbackEngine;
    private Song song;
    private final Button playButton;
    private final Button stopButton;
    private final Button pauseButton;
    private final Spinner<Integer> tempoSpinner;
    private final Spinner<Integer> dtSpinner;
    private final ComboBox<SmpsMode> modeSelector;
    private boolean paused = false;

    public TransportBar(PlaybackEngine playbackEngine, Song song) {
        this.playbackEngine = playbackEngine;
        this.song = song;

        setPadding(new Insets(6, 10, 6, 10));
        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: #2a2a2a;");

        // Play button
        playButton = new Button("\u25B6 Play");
        playButton.setOnAction(e -> onPlay());

        // Stop button
        stopButton = new Button("\u25A0 Stop");
        stopButton.setOnAction(e -> onStop());

        // Pause button
        pauseButton = new Button("\u23F8 Pause");
        pauseButton.setOnAction(e -> onPause());

        // Spacer
        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);

        // Tempo
        Label tempoLabel = new Label("Tempo:");
        tempoLabel.setStyle("-fx-text-fill: #cccccc;");
        tempoSpinner = new Spinner<>(1, 255, song.getTempo());
        tempoSpinner.setPrefWidth(80);
        tempoSpinner.setEditable(true);
        tempoSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            song.setTempo(newVal);
        });

        // Dividing timing
        Label dtLabel = new Label("Div:");
        dtLabel.setStyle("-fx-text-fill: #cccccc;");
        dtSpinner = new Spinner<>(1, 8, song.getDividingTiming());
        dtSpinner.setPrefWidth(60);
        dtSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            song.setDividingTiming(newVal);
        });

        // Spacer
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        // SMPS mode selector
        Label modeLabel = new Label("Mode:");
        modeLabel.setStyle("-fx-text-fill: #cccccc;");
        modeSelector = new ComboBox<>();
        modeSelector.getItems().addAll(SmpsMode.values());
        modeSelector.setValue(song.getSmpsMode());
        modeSelector.setOnAction(e -> {
            song.setSmpsMode(modeSelector.getValue());
        });

        getChildren().addAll(
            playButton, stopButton, pauseButton,
            spacer1,
            tempoLabel, tempoSpinner,
            dtLabel, dtSpinner,
            spacer2,
            modeLabel, modeSelector
        );
    }

    public void setSong(Song song) {
        this.song = song;
        tempoSpinner.getValueFactory().setValue(song.getTempo());
        dtSpinner.getValueFactory().setValue(song.getDividingTiming());
        modeSelector.setValue(song.getSmpsMode());
    }

    private void onPlay() {
        if (paused) {
            playbackEngine.resume();
            paused = false;
        } else {
            playbackEngine.loadSong(song);
            playbackEngine.play();
        }
    }

    private void onStop() {
        playbackEngine.stop();
        paused = false;
    }

    private void onPause() {
        if (playbackEngine.isPlaying()) {
            playbackEngine.pause();
            paused = true;
        }
    }
}
