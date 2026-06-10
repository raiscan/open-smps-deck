package com.opensmpsdeck.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;

/**
 * Compact note + octave picker for instrument previews.
 * Produces SMPS note bytes (0x81 = C0, +1 per semitone, +12 per octave).
 */
public class NoteSelector extends HBox {

    private static final String[] NOTE_NAMES = {
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };

    private final ComboBox<String> noteCombo;
    private final Spinner<Integer> octaveSpinner;

    public NoteSelector() {
        super(4);
        setAlignment(Pos.CENTER_LEFT);

        noteCombo = new ComboBox<>(FXCollections.observableArrayList(NOTE_NAMES));
        noteCombo.getSelectionModel().select(0); // C
        noteCombo.setPrefWidth(70);
        noteCombo.setStyle("-fx-background-color: #2a2a2a; -fx-font-size: 11;");

        octaveSpinner = new Spinner<>(0, 7, 4); // C4 default
        octaveSpinner.setPrefWidth(60);
        octaveSpinner.setStyle("-fx-background-color: #2a2a2a;");

        getChildren().addAll(noteCombo, octaveSpinner);
    }

    /** Returns the selected note as an SMPS note byte (0x81-0xDF). */
    public int getNoteByte() {
        int noteIndex = Math.max(0, noteCombo.getSelectionModel().getSelectedIndex());
        int octave = octaveSpinner.getValue();
        int note = 0x81 + octave * 12 + noteIndex;
        return Math.min(0xDF, note);
    }
}
