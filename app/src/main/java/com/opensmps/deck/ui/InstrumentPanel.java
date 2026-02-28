package com.opensmps.deck.ui;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.PsgEnvelope;
import com.opensmps.deck.model.Song;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/**
 * Right-side panel managing the FM voice bank and PSG envelope lists.
 *
 * <p>Each section has a ListView displaying items as hex index + name,
 * and buttons for adding, editing, and deleting entries. Double-clicking
 * or pressing Edit opens the corresponding editor dialog.
 */
public class InstrumentPanel extends VBox {

    private static final String BG_COLOR = "#252525";
    private static final String HEADER_COLOR = "#88aacc";
    private static final String TEXT_COLOR = "#cccccc";
    private static final String LIST_BG_COLOR = "#2a2a2a";
    private static final String BUTTON_STYLE =
            "-fx-background-color: #2a2a2a; -fx-text-fill: #cccccc; -fx-font-size: 12px;";

    private Song song;
    private final ListView<String> voiceListView;
    private final ListView<String> envelopeListView;
    private final ObservableList<String> voiceItems;
    private final ObservableList<String> envelopeItems;
    private Runnable onDirty;

    public void setOnDirty(Runnable callback) { this.onDirty = callback; }
    private void markDirty() { if (onDirty != null) onDirty.run(); }

    /**
     * Creates a new instrument panel bound to the given song model.
     *
     * @param song the song whose voice bank and PSG envelopes to manage
     */
    public InstrumentPanel(Song song) {
        this.song = song;

        setPrefWidth(250);
        setStyle("-fx-background-color: " + BG_COLOR + ";");
        setPadding(new Insets(8));
        setSpacing(8);

        // --- Voice Bank section ---
        Label voiceHeader = createHeaderLabel("Voice Bank");

        voiceItems = FXCollections.observableArrayList();
        voiceListView = createListView(voiceItems);

        voiceListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                editSelectedVoice();
            }
        });

        HBox voiceButtons = createButtonBar(
                createButton("+", e -> addVoice()),
                createButton("Edit", e -> editSelectedVoice()),
                createButton("Del", e -> deleteSelectedVoice())
        );

        VBox voiceSection = new VBox(4, voiceHeader, voiceListView, voiceButtons);
        VBox.setVgrow(voiceListView, Priority.ALWAYS);
        VBox.setVgrow(voiceSection, Priority.ALWAYS);

        // --- PSG Envelopes section ---
        Label envelopeHeader = createHeaderLabel("PSG Envelopes");

        envelopeItems = FXCollections.observableArrayList();
        envelopeListView = createListView(envelopeItems);

        envelopeListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                editSelectedEnvelope();
            }
        });

        HBox envelopeButtons = createButtonBar(
                createButton("+", e -> addEnvelope()),
                createButton("Edit", e -> editSelectedEnvelope()),
                createButton("Del", e -> deleteSelectedEnvelope())
        );

        VBox envelopeSection = new VBox(4, envelopeHeader, envelopeListView, envelopeButtons);
        VBox.setVgrow(envelopeListView, Priority.ALWAYS);
        VBox.setVgrow(envelopeSection, Priority.ALWAYS);

        getChildren().addAll(voiceSection, envelopeSection);

        refresh();
    }

    /**
     * Replace the song model and refresh the display.
     *
     * @param song the new song to display
     */
    public void setSong(Song song) {
        this.song = song;
        refresh();
    }

    /**
     * Syncs the ListViews from the current Song model.
     */
    public void refresh() {
        refreshVoiceList();
        refreshEnvelopeList();
    }

    /**
     * Returns the currently selected voice index, or -1 if none selected.
     */
    public int getCurrentVoiceIndex() {
        return voiceListView.getSelectionModel().getSelectedIndex();
    }

    /**
     * Returns the currently selected envelope index, or -1 if none selected.
     */
    public int getCurrentEnvelopeIndex() {
        return envelopeListView.getSelectionModel().getSelectedIndex();
    }

    // --- Voice operations ---

    private void addVoice() {
        int index = song.getVoiceBank().size();
        byte[] data = new byte[FmVoice.VOICE_SIZE];
        FmVoice newVoice = new FmVoice("Voice " + index, data);

        FmVoiceEditor editor = new FmVoiceEditor(newVoice);
        Optional<FmVoice> result = editor.showAndWait();
        if (result.isPresent()) {
            song.getVoiceBank().add(result.get());
            refreshVoiceList();
            voiceListView.getSelectionModel().selectLast();
            markDirty();
        }
    }

    private void editSelectedVoice() {
        int index = getCurrentVoiceIndex();
        if (index < 0) {
            return;
        }

        FmVoice existing = song.getVoiceBank().get(index);
        FmVoiceEditor editor = new FmVoiceEditor(existing);
        Optional<FmVoice> result = editor.showAndWait();
        if (result.isPresent()) {
            song.getVoiceBank().set(index, result.get());
            refreshVoiceList();
            voiceListView.getSelectionModel().select(index);
            markDirty();
        }
    }

    private void deleteSelectedVoice() {
        int index = getCurrentVoiceIndex();
        if (index < 0) {
            return;
        }

        song.getVoiceBank().remove(index);
        refreshVoiceList();
        markDirty();
    }

    // --- Envelope operations ---

    private void addEnvelope() {
        int index = song.getPsgEnvelopes().size();
        byte[] data = new byte[]{0x00, (byte) 0x80};
        PsgEnvelope newEnvelope = new PsgEnvelope("Env " + index, data);

        PsgEnvelopeEditor editor = new PsgEnvelopeEditor(newEnvelope);
        Optional<PsgEnvelope> result = editor.showAndWait();
        if (result.isPresent()) {
            song.getPsgEnvelopes().add(result.get());
            refreshEnvelopeList();
            envelopeListView.getSelectionModel().selectLast();
            markDirty();
        }
    }

    private void editSelectedEnvelope() {
        int index = getCurrentEnvelopeIndex();
        if (index < 0) {
            return;
        }

        PsgEnvelope existing = song.getPsgEnvelopes().get(index);
        PsgEnvelopeEditor editor = new PsgEnvelopeEditor(existing);
        Optional<PsgEnvelope> result = editor.showAndWait();
        if (result.isPresent()) {
            song.getPsgEnvelopes().set(index, result.get());
            refreshEnvelopeList();
            envelopeListView.getSelectionModel().select(index);
            markDirty();
        }
    }

    private void deleteSelectedEnvelope() {
        int index = getCurrentEnvelopeIndex();
        if (index < 0) {
            return;
        }

        song.getPsgEnvelopes().remove(index);
        refreshEnvelopeList();
        markDirty();
    }

    // --- List refresh helpers ---

    private void refreshVoiceList() {
        List<FmVoice> voices = song.getVoiceBank();
        voiceItems.clear();
        for (int i = 0; i < voices.size(); i++) {
            voiceItems.add(String.format("%02X: %s", i, voices.get(i).getName()));
        }
    }

    private void refreshEnvelopeList() {
        List<PsgEnvelope> envelopes = song.getPsgEnvelopes();
        envelopeItems.clear();
        for (int i = 0; i < envelopes.size(); i++) {
            envelopeItems.add(String.format("%02X: %s", i, envelopes.get(i).getName()));
        }
    }

    // --- UI factory helpers ---

    private Label createHeaderLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + HEADER_COLOR + "; -fx-font-size: 14px; -fx-font-weight: bold;");
        label.setPadding(new Insets(0, 0, 4, 0));
        return label;
    }

    private ListView<String> createListView(ObservableList<String> items) {
        ListView<String> listView = new ListView<>(items);
        listView.setPrefHeight(180);
        listView.setStyle("-fx-background-color: " + LIST_BG_COLOR + "; "
                + "-fx-control-inner-background: " + LIST_BG_COLOR + "; "
                + "-fx-text-fill: " + TEXT_COLOR + ";");
        return listView;
    }

    private Button createButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button button = new Button(text);
        button.setStyle(BUTTON_STYLE);
        button.setOnAction(handler);
        return button;
    }

    private HBox createButtonBar(Button... buttons) {
        HBox bar = new HBox(4, buttons);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 0, 0, 0));
        return bar;
    }
}
