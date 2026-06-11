package com.opensmpsdeck.ui;

import com.opensmpsdeck.audio.InstrumentPreviewPlayer;
import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.audio.match.FmPatchSearch;
import com.opensmpsdeck.audio.match.VoiceMatchService;
import com.opensmpsdeck.audio.match.WavStemReader;
import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;
import com.opensmpsdeck.model.FmVoice;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.List;

/** Runs WAV voice matching and offers the top candidates with audition. */
public class VoiceMatchDialog extends Dialog<FmVoice> {

    private final ListView<FmPatchSearch.ScoredVoice> list = new ListView<>();
    private final ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Label status = new Label("Analyzing…");
    private final VoiceMatchService service = new VoiceMatchService();
    private final String resultBaseName;
    private PlaybackEngine previewEngine;

    /**
     * @param wavFile        stem WAV to analyse
     * @param notes          MIDI notes guiding isolation (single synthetic note for the
     *                       no-MIDI-guidance path)
     * @param map            tick-to-time mapper for {@code notes}
     * @param lowConfidence  when true, marks results as "low confidence" (no MIDI guidance)
     */
    public VoiceMatchDialog(File wavFile, List<NoteEvent> notes, TickTimeMapper map,
                            boolean lowConfidence) {
        setTitle("Match Voice from WAV");
        this.resultBaseName = baseName(wavFile);
        list.setPrefHeight(180);
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(FmPatchSearch.ScoredVoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : String.format("score %.4f — %s", item.score(), item.voice().getName()));
            }
        });
        Button audition = new Button("Audition");
        audition.setOnAction(e -> {
            var sel = list.getSelectionModel().getSelectedItem();
            if (sel != null && previewEngine != null) {
                InstrumentPreviewPlayer.previewFmVoice(previewEngine, sel.voice(),
                        InstrumentPreviewPlayer.DEFAULT_NOTE);
            }
        });

        VBox box = new VBox(10, status, progress, list, new HBox(10, audition));
        box.setPadding(new Insets(10));
        getDialogPane().setContent(box);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
        setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            var sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return null;
            // Name the accepted voice after the stem so it is identifiable in the bank.
            FmVoice accepted = new FmVoice(resultBaseName + " match", sel.voice().getData());
            return accepted;
        });
        setOnHidden(e -> service.shutdown());

        startMatch(wavFile, notes, map, lowConfidence);
    }

    public void setPreviewEngine(PlaybackEngine engine) { this.previewEngine = engine; }

    private void startMatch(File wavFile, List<NoteEvent> notes, TickTimeMapper map,
                            boolean lowConfidence) {
        new Thread(() -> {
            try {
                float[] audio = WavStemReader.readMono44k(wavFile);
                service.match(audio, notes, map, FmPatchSearch.Config.defaults(),
                                gen -> Platform.runLater(() ->
                                        status.setText("Searching… generation " + gen)))
                        .thenAccept(result -> Platform.runLater(() -> {
                            progress.setVisible(false);
                            if (result.candidates().isEmpty()) {
                                status.setText(result.failureReason());
                            } else {
                                status.setText(lowConfidence
                                        ? "Top candidates — low confidence (no MIDI guidance):"
                                        : "Top candidates — audition and accept:");
                                list.setItems(FXCollections.observableArrayList(
                                        result.candidates()));
                                list.getSelectionModel().selectFirst();
                                getDialogPane().lookupButton(ButtonType.OK).setDisable(false);
                            }
                        }));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    status.setText("Failed: " + ex.getMessage());
                });
            }
        }, "voice-match-load").start();
    }

    private static String baseName(File wavFile) {
        String n = wavFile.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
