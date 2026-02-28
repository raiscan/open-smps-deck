package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.io.DacSampleImporter;
import com.opensmps.deck.io.OsmpsVoiceFile;
import com.opensmps.deck.model.DacSample;
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
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Right-side panel managing the FM voice bank, PSG envelope, and DAC sample lists.
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
    private PlaybackEngine playbackEngine;
    private final ListView<String> voiceListView;
    private final ListView<String> envelopeListView;
    private final ListView<String> dacSampleListView;
    private final ObservableList<String> voiceItems;
    private final ObservableList<String> envelopeItems;
    private final ObservableList<String> dacSampleItems;
    private Runnable onDirty;
    private Runnable onImportBank;

    public void setOnDirty(Runnable callback) { this.onDirty = callback; }
    private void markDirty() { if (onDirty != null) onDirty.run(); }

    /**
     * Sets the callback invoked when the "Import Bank..." button is clicked.
     *
     * @param callback the import bank action, typically delegating to MainWindow
     */
    public void setOnImportBank(Runnable callback) { this.onImportBank = callback; }

    /**
     * Sets the playback engine used for voice/envelope preview in editors.
     *
     * @param engine the playback engine
     */
    public void setPlaybackEngine(PlaybackEngine engine) { this.playbackEngine = engine; }

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
                createButton("Del", e -> deleteSelectedVoice()),
                createButton("Export", e -> exportSelectedVoiceAsPreset()),
                createButton("Import Bank...", e -> {
                    if (onImportBank != null) onImportBank.run();
                })
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

        // --- DAC Samples section ---
        Label dacHeader = createHeaderLabel("DAC Samples");

        dacSampleItems = FXCollections.observableArrayList();
        dacSampleListView = createListView(dacSampleItems);

        dacSampleListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                editSelectedDacSample();
            }
        });

        HBox dacButtons = createButtonBar(
                createButton("+", e -> addDacSample()),
                createButton("Dup", e -> duplicateSelectedDacSample()),
                createButton("Edit", e -> editSelectedDacSample()),
                createButton("Del", e -> deleteSelectedDacSample())
        );

        VBox dacSection = new VBox(4, dacHeader, dacSampleListView, dacButtons);
        VBox.setVgrow(dacSampleListView, Priority.ALWAYS);
        VBox.setVgrow(dacSection, Priority.ALWAYS);

        getChildren().addAll(voiceSection, envelopeSection, dacSection);

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
        refreshDacSampleList();
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
        editor.setPreviewEngine(playbackEngine);
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
        editor.setPreviewEngine(playbackEngine);
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

    private void exportSelectedVoiceAsPreset() {
        int index = getCurrentVoiceIndex();
        if (index < 0) return;

        FmVoice voice = song.getVoiceBank().get(index);
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Voice Preset");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("OpenSMPS Voice Preset", "*.osmpsvoice"));
        chooser.setInitialFileName(voice.getName() + ".osmpsvoice");
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            try {
                OsmpsVoiceFile.save(voice, file);
            } catch (IOException ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR,
                        "Failed to export voice preset: " + ex.getMessage(), ButtonType.OK);
                alert.setTitle("Export Error");
                alert.showAndWait();
            }
        }
    }

    // --- Envelope operations ---

    private void addEnvelope() {
        int index = song.getPsgEnvelopes().size();
        byte[] data = new byte[]{0x00, (byte) 0x80};
        PsgEnvelope newEnvelope = new PsgEnvelope("Env " + index, data);

        PsgEnvelopeEditor editor = new PsgEnvelopeEditor(newEnvelope);
        editor.setPreviewEngine(playbackEngine);
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
        editor.setPreviewEngine(playbackEngine);
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

    // --- DAC Sample operations ---

    private void addDacSample() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import DAC Sample");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio Files", "*.wav", "*.pcm", "*.bin"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            DacSample sample = DacSampleImporter.importFile(file, 0x0C);
            song.getDacSamples().add(sample);
            refreshDacSampleList();
            dacSampleListView.getSelectionModel().selectLast();
            markDirty();
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Failed to import DAC sample: " + ex.getMessage(), ButtonType.OK);
            alert.setTitle("Import Error");
            alert.showAndWait();
        }
    }

    private void duplicateSelectedDacSample() {
        int index = dacSampleListView.getSelectionModel().getSelectedIndex();
        if (index < 0) {
            return;
        }
        DacSample original = song.getDacSamples().get(index);
        DacSample copy = new DacSample(
                original.getName() + " (copy)",
                original.getData(),
                original.getRate()
        );
        song.getDacSamples().add(copy);
        refreshDacSampleList();
        dacSampleListView.getSelectionModel().selectLast();
        markDirty();
    }

    private void editSelectedDacSample() {
        int index = dacSampleListView.getSelectionModel().getSelectedIndex();
        if (index < 0) {
            return;
        }
        DacSample existing = song.getDacSamples().get(index);
        DacSampleEditor editor = new DacSampleEditor(existing);
        Optional<DacSample> result = editor.showAndWait();
        if (result.isPresent()) {
            refreshDacSampleList();
            dacSampleListView.getSelectionModel().select(index);
            markDirty();
        }
    }

    private void deleteSelectedDacSample() {
        int index = dacSampleListView.getSelectionModel().getSelectedIndex();
        if (index < 0) {
            return;
        }
        song.getDacSamples().remove(index);
        refreshDacSampleList();
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

    private void refreshDacSampleList() {
        List<DacSample> samples = song.getDacSamples();
        dacSampleItems.clear();
        for (int i = 0; i < samples.size(); i++) {
            DacSample s = samples.get(i);
            dacSampleItems.add(String.format("%02X: %s (rate=%02X)", i, s.getName(), s.getRate()));
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
