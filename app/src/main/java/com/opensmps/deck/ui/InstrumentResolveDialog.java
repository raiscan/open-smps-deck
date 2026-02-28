package com.opensmps.deck.ui;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.PsgEnvelope;
import com.opensmps.deck.model.Song;
import javafx.beans.property.SimpleStringProperty;
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

    public InstrumentResolveDialog(Song srcSong, Song dstSong,
                                    Set<Integer> unresolvedVoices, Set<Integer> unresolvedPsg) {
        setTitle("Resolve Instruments");
        setHeaderText("Some instruments differ between songs. Choose how to handle each:");

        DialogPane pane = getDialogPane();
        pane.setPrefWidth(500);
        pane.setPrefHeight(400);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        if (!unresolvedVoices.isEmpty()) {
            Label voiceLabel = new Label("FM Voices:");
            voiceLabel.setStyle("-fx-font-weight: bold;");
            TableView<int[]> voiceTable = buildTable(srcSong.getVoiceBank(), unresolvedVoices, "Voice");
            content.getChildren().addAll(voiceLabel, voiceTable);
        }

        if (!unresolvedPsg.isEmpty()) {
            Label psgLabel = new Label("PSG Envelopes:");
            psgLabel.setStyle("-fx-font-weight: bold;");
            // For PSG, just show indices
            TableView<int[]> psgTable = buildPsgTable(srcSong.getPsgEnvelopes(), unresolvedPsg);
            content.getChildren().addAll(psgLabel, psgTable);
        }

        pane.setContent(content);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return buildResolution(srcSong, dstSong, unresolvedVoices, unresolvedPsg);
            }
            return null;
        });
    }

    private TableView<int[]> buildTable(List<FmVoice> srcVoices, Set<Integer> indices, String prefix) {
        List<int[]> entries = new ArrayList<>();
        for (int idx : indices) entries.add(new int[]{idx});

        TableView<int[]> table = new TableView<>(FXCollections.observableArrayList(entries));
        table.setPrefHeight(150);

        TableColumn<int[], String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> {
            int idx = c.getValue()[0];
            String name = idx < srcVoices.size() ? srcVoices.get(idx).getName() : prefix + " " + idx;
            return new SimpleStringProperty(String.format("%02X: %s", idx, name));
        });
        nameCol.setPrefWidth(200);

        TableColumn<int[], String> actionCol = new TableColumn<>("Action");
        actionCol.setCellValueFactory(c -> new SimpleStringProperty("Copy into song"));
        actionCol.setPrefWidth(250);

        table.getColumns().addAll(List.of(nameCol, actionCol));
        return table;
    }

    private TableView<int[]> buildPsgTable(List<PsgEnvelope> srcEnvelopes, Set<Integer> indices) {
        List<int[]> entries = new ArrayList<>();
        for (int idx : indices) entries.add(new int[]{idx});

        TableView<int[]> table = new TableView<>(FXCollections.observableArrayList(entries));
        table.setPrefHeight(150);

        TableColumn<int[], String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> {
            int idx = c.getValue()[0];
            String name = idx < srcEnvelopes.size() ? srcEnvelopes.get(idx).getName() : "Env " + idx;
            return new SimpleStringProperty(String.format("%02X: %s", idx, name));
        });
        nameCol.setPrefWidth(200);

        TableColumn<int[], String> actionCol = new TableColumn<>("Action");
        actionCol.setCellValueFactory(c -> new SimpleStringProperty("Copy into song"));
        actionCol.setPrefWidth(250);

        table.getColumns().addAll(List.of(nameCol, actionCol));
        return table;
    }

    private Resolution buildResolution(Song srcSong, Song dstSong,
                                        Set<Integer> unresolvedVoices, Set<Integer> unresolvedPsg) {
        Map<Integer, Integer> voiceMap = new HashMap<>();
        Map<Integer, Integer> psgMap = new HashMap<>();
        List<FmVoice> voicesToCopy = new ArrayList<>();
        List<PsgEnvelope> envelopesToCopy = new ArrayList<>();

        int nextVoiceIdx = dstSong.getVoiceBank().size();
        for (int srcIdx : unresolvedVoices) {
            if (srcIdx >= 0 && srcIdx < srcSong.getVoiceBank().size()) {
                FmVoice voice = srcSong.getVoiceBank().get(srcIdx);
                voicesToCopy.add(new FmVoice(voice.getName(), voice.getData()));
                voiceMap.put(srcIdx, nextVoiceIdx++);
            }
        }

        int nextPsgIdx = dstSong.getPsgEnvelopes().size();
        for (int srcIdx : unresolvedPsg) {
            if (srcIdx >= 0 && srcIdx < srcSong.getPsgEnvelopes().size()) {
                PsgEnvelope env = srcSong.getPsgEnvelopes().get(srcIdx);
                envelopesToCopy.add(new PsgEnvelope(env.getName(), env.getData()));
                psgMap.put(srcIdx, nextPsgIdx++);
            }
        }

        return new Resolution(voiceMap, psgMap, voicesToCopy, envelopesToCopy);
    }
}
