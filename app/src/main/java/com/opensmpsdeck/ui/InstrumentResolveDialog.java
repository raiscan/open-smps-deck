package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.*;

/**
 * Modal dialog for resolving instrument references when pasting
 * between different songs. Shows unresolved voice/PSG references
 * and lets the user choose: copy, remap, or skip for each.
 */
public class InstrumentResolveDialog extends Dialog<InstrumentResolveDialog.Resolution> {

    public record Resolution(Map<Integer, Integer> voiceMap, Map<Integer, Integer> psgMap,
                             List<FmVoice> voicesToCopy, List<PsgEnvelope> envelopesToCopy) {}

    private final List<ResolveEntry> voiceEntries = new ArrayList<>();
    private final List<ResolveEntry> psgEntries = new ArrayList<>();

    private static class ResolveEntry {
        final int sourceIndex;
        String action = "Copy";
        int remapTarget = -1;

        ResolveEntry(int sourceIndex) { this.sourceIndex = sourceIndex; }
    }

    public InstrumentResolveDialog(Song srcSong, Song dstSong,
                                    Set<Integer> unresolvedVoices, Set<Integer> unresolvedPsg) {
        setTitle("Resolve Instruments");
        setHeaderText("Some instruments differ between songs. Choose how to handle each:");

        DialogPane pane = getDialogPane();
        pane.setPrefWidth(550);
        pane.setPrefHeight(450);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        if (!unresolvedVoices.isEmpty()) {
            Label voiceLabel = new Label("FM Voices:");
            voiceLabel.setStyle("-fx-font-weight: bold;");
            for (int idx : unresolvedVoices) voiceEntries.add(new ResolveEntry(idx));
            VBox voiceRows = buildActionRows(voiceEntries, srcSong.getVoiceBank(),
                    dstSong.getVoiceBank(), "Voice");
            content.getChildren().addAll(voiceLabel, voiceRows);
        }

        if (!unresolvedPsg.isEmpty()) {
            Label psgLabel = new Label("PSG Envelopes:");
            psgLabel.setStyle("-fx-font-weight: bold;");
            for (int idx : unresolvedPsg) psgEntries.add(new ResolveEntry(idx));
            VBox psgRows = buildPsgActionRows(psgEntries, srcSong.getPsgEnvelopes(),
                    dstSong.getPsgEnvelopes());
            content.getChildren().addAll(psgLabel, psgRows);
        }

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        pane.setContent(scrollPane);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return buildResolution(srcSong, dstSong);
            }
            return null;
        });
    }

    private <T> VBox buildActionRows(List<ResolveEntry> entries, List<FmVoice> srcList,
                                      List<FmVoice> dstList, String prefix) {
        VBox rows = new VBox(4);
        for (ResolveEntry entry : entries) {
            String srcName = entry.sourceIndex < srcList.size()
                    ? srcList.get(entry.sourceIndex).getName() : prefix + " " + entry.sourceIndex;
            Label nameLabel = new Label(String.format("%02X: %s", entry.sourceIndex, srcName));
            nameLabel.setMinWidth(180);

            List<String> options = new ArrayList<>();
            options.add("Copy into song");
            for (int i = 0; i < dstList.size(); i++) {
                options.add(String.format("Remap \u2192 %02X: %s", i, dstList.get(i).getName()));
            }
            options.add("Skip");

            ComboBox<String> actionCombo = new ComboBox<>(FXCollections.observableArrayList(options));
            actionCombo.setValue("Copy into song");
            actionCombo.setPrefWidth(280);
            actionCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal == null) return;
                if (newVal.equals("Copy into song")) {
                    entry.action = "Copy";
                    entry.remapTarget = -1;
                } else if (newVal.equals("Skip")) {
                    entry.action = "Skip";
                    entry.remapTarget = -1;
                } else if (newVal.startsWith("Remap")) {
                    entry.action = "Remap";
                    // Parse index from "Remap -> XX: name"
                    String hex = newVal.substring(newVal.indexOf("\u2192") + 2, newVal.indexOf(":")).trim();
                    entry.remapTarget = Integer.parseInt(hex, 16);
                }
            });

            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8, nameLabel, actionCombo);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        return rows;
    }

    private VBox buildPsgActionRows(List<ResolveEntry> entries, List<PsgEnvelope> srcList,
                                     List<PsgEnvelope> dstList) {
        VBox rows = new VBox(4);
        for (ResolveEntry entry : entries) {
            String srcName = entry.sourceIndex < srcList.size()
                    ? srcList.get(entry.sourceIndex).getName() : "Env " + entry.sourceIndex;
            Label nameLabel = new Label(String.format("%02X: %s", entry.sourceIndex, srcName));
            nameLabel.setMinWidth(180);

            List<String> options = new ArrayList<>();
            options.add("Copy into song");
            for (int i = 0; i < dstList.size(); i++) {
                options.add(String.format("Remap \u2192 %02X: %s", i, dstList.get(i).getName()));
            }
            options.add("Skip");

            ComboBox<String> actionCombo = new ComboBox<>(FXCollections.observableArrayList(options));
            actionCombo.setValue("Copy into song");
            actionCombo.setPrefWidth(280);
            actionCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal == null) return;
                if (newVal.equals("Copy into song")) {
                    entry.action = "Copy";
                    entry.remapTarget = -1;
                } else if (newVal.equals("Skip")) {
                    entry.action = "Skip";
                    entry.remapTarget = -1;
                } else if (newVal.startsWith("Remap")) {
                    entry.action = "Remap";
                    String hex = newVal.substring(newVal.indexOf("\u2192") + 2, newVal.indexOf(":")).trim();
                    entry.remapTarget = Integer.parseInt(hex, 16);
                }
            });

            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8, nameLabel, actionCombo);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        return rows;
    }

    private Resolution buildResolution(Song srcSong, Song dstSong) {
        Map<Integer, Integer> voiceMap = new HashMap<>();
        Map<Integer, Integer> psgMap = new HashMap<>();
        List<FmVoice> voicesToCopy = new ArrayList<>();
        List<PsgEnvelope> envelopesToCopy = new ArrayList<>();

        int nextVoiceIdx = dstSong.getVoiceBank().size();
        for (ResolveEntry entry : voiceEntries) {
            switch (entry.action) {
                case "Copy" -> {
                    if (entry.sourceIndex >= 0 && entry.sourceIndex < srcSong.getVoiceBank().size()) {
                        FmVoice voice = srcSong.getVoiceBank().get(entry.sourceIndex);
                        voicesToCopy.add(new FmVoice(voice.getName(), voice.getData()));
                        voiceMap.put(entry.sourceIndex, nextVoiceIdx++);
                    }
                }
                case "Remap" -> voiceMap.put(entry.sourceIndex, entry.remapTarget);
                case "Skip" -> { /* no mapping */ }
            }
        }

        int nextPsgIdx = dstSong.getPsgEnvelopes().size();
        for (ResolveEntry entry : psgEntries) {
            switch (entry.action) {
                case "Copy" -> {
                    if (entry.sourceIndex >= 0 && entry.sourceIndex < srcSong.getPsgEnvelopes().size()) {
                        PsgEnvelope env = srcSong.getPsgEnvelopes().get(entry.sourceIndex);
                        envelopesToCopy.add(new PsgEnvelope(env.getName(), env.getData()));
                        psgMap.put(entry.sourceIndex, nextPsgIdx++);
                    }
                }
                case "Remap" -> psgMap.put(entry.sourceIndex, entry.remapTarget);
                case "Skip" -> { /* no mapping */ }
            }
        }

        return new Resolution(voiceMap, psgMap, voicesToCopy, envelopesToCopy);
    }
}
