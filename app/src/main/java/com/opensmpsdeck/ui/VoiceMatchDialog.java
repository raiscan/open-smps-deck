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
    private final com.opensmpsdeck.audio.PcmClipPlayer clipPlayer =
            new com.opensmpsdeck.audio.PcmClipPlayer();
    private final String resultBaseName;
    private PlaybackEngine previewEngine;
    /** The original WAV slice the candidates were matched against (A/B reference). */
    private float[] referenceSlice;
    /** Note byte for candidate audition — the matched window's pitch, not a fixed C4. */
    private int auditionNoteByte = InstrumentPreviewPlayer.DEFAULT_NOTE;

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
        Button audition = new Button("Audition Match");
        audition.setOnAction(e -> {
            clipPlayer.stop();
            var sel = list.getSelectionModel().getSelectedItem();
            if (sel != null && previewEngine != null) {
                InstrumentPreviewPlayer.previewFmVoice(previewEngine, sel.voice(),
                        auditionNoteByte);
            }
        });
        Button playOriginal = new Button("Play Original");
        playOriginal.setDisable(true);
        playOriginal.setOnAction(e -> {
            if (previewEngine != null) previewEngine.stop();
            if (referenceSlice != null) clipPlayer.play(referenceSlice, 44100);
        });
        this.playOriginalButton = playOriginal;

        VBox box = new VBox(10, status, progress, list,
                new HBox(10, audition, playOriginal));
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
        setOnHidden(e -> {
            clipPlayer.stop();
            service.shutdown();
        });

        startMatch(wavFile, notes, map, lowConfidence);
    }

    private final Button playOriginalButton;

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
                                String base = lowConfidence
                                        ? "Top candidates — low confidence (no MIDI guidance):"
                                        : "Top candidates — compare against the original and accept:";
                                var mod = result.referenceModulation();
                                if (mod != null && mod.significant()) {
                                    base += String.format(
                                            "  (vibrato detected: %.0f cents @ %.1f Hz — "
                                            + "consider SMPS modulation on this channel)",
                                            mod.depthCents(), mod.rateHz());
                                }
                                status.setText(base);
                                list.setItems(FXCollections.observableArrayList(
                                        result.candidates()));
                                list.getSelectionModel().selectFirst();
                                getDialogPane().lookupButton(ButtonType.OK).setDisable(false);
                                referenceSlice = result.referenceSlice();
                                if (result.referencePitch() >= 0) {
                                    // audition at the matched pitch for a fair A/B
                                    auditionNoteByte = Math.max(0x81, Math.min(0xDF,
                                            0x81 + result.referencePitch() - 12));
                                }
                                playOriginalButton.setDisable(referenceSlice == null);
                            }
                        }))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> {
                                progress.setVisible(false);
                                status.setText("Match failed: " + ex.getMessage());
                            });
                            return null;
                        });
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
