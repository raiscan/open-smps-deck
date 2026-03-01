package com.opensmps.deck.ui;

import com.opensmps.deck.codec.HierarchyDecompiler;
import com.opensmps.deck.model.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog that previews the hierarchical decompilation of an imported SMPS song.
 * Shows per-channel structure with phrase blocks, CALL/LOOP/JUMP markers,
 * shared phrase reference counts, and provides Import/Cancel buttons.
 */
public class ImportPreviewDialog extends Dialog<ButtonType> {

    private static final String[] CHANNEL_NAMES = {
        "FM1", "FM2", "FM3", "FM4", "FM5", "DAC",
        "PSG1", "PSG2", "PSG3", "Noise"
    };

    private final Song song;
    private final List<HierarchyDecompiler.DecompileResult> channelResults = new ArrayList<>();
    private boolean importAsHierarchical = false;

    public ImportPreviewDialog(Song song) {
        this.song = song;
        setTitle("Import Structure Preview");
        setHeaderText("Decompiled structure from imported SMPS data");

        decompileAllChannels();

        VBox content = new VBox(8);
        content.setPadding(new Insets(12));
        content.setPrefWidth(550);
        content.setPrefHeight(400);

        // Summary stats
        int totalPhrases = 0;
        int totalShared = 0;
        int totalLoops = 0;
        for (var result : channelResults) {
            totalPhrases += result.phrases().size();
            totalShared += result.sharedPhraseCount();
            if (result.hasLoopPoint()) totalLoops++;
        }
        Label summaryLabel = new Label(String.format(
            "Phrases: %d  |  Shared (CALL): %d  |  Channels with loop: %d",
            totalPhrases, totalShared, totalLoops));
        summaryLabel.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 12;");

        // Per-channel details
        ListView<String> channelListView = new ListView<>();
        channelListView.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 11;");
        VBox.setVgrow(channelListView, Priority.ALWAYS);

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (ch >= channelResults.size()) break;
            var result = channelResults.get(ch);
            if (result.phrases().isEmpty() && result.chainEntries().isEmpty()) continue;

            StringBuilder sb = new StringBuilder();
            sb.append(CHANNEL_NAMES[ch]).append(": ");
            sb.append(result.chainEntries().size()).append(" entries, ");
            sb.append(result.phrases().size()).append(" phrases");
            if (result.sharedPhraseCount() > 0) {
                sb.append(" (").append(result.sharedPhraseCount()).append(" shared)");
            }
            if (result.hasLoopPoint()) {
                sb.append(" [loop at ").append(result.loopEntryIndex()).append("]");
            }

            channelListView.getItems().add(sb.toString());

            // Show chain entries
            for (int i = 0; i < result.chainEntries().size(); i++) {
                var entry = result.chainEntries().get(i);
                Phrase phrase = findPhrase(result.phrases(), entry.getPhraseId());
                String phraseName = phrase != null ? phrase.getName() : "?";
                int dataLen = phrase != null ? phrase.getDataDirect().length : 0;

                StringBuilder entryStr = new StringBuilder("  ");
                if (result.hasLoopPoint() && i == result.loopEntryIndex()) {
                    entryStr.append("\u21BA ");
                } else {
                    entryStr.append("  ");
                }
                entryStr.append(String.format("[%d] %s (%d bytes)", entry.getPhraseId(), phraseName, dataLen));
                if (entry.getRepeatCount() > 1) {
                    entryStr.append(" \u00D7").append(entry.getRepeatCount());
                }
                channelListView.getItems().add(entryStr.toString());
            }
        }

        // Import mode selector
        CheckBox hierarchicalCheck = new CheckBox("Import as Hierarchical arrangement");
        hierarchicalCheck.setSelected(false);
        hierarchicalCheck.setOnAction(e -> importAsHierarchical = hierarchicalCheck.isSelected());

        HBox optionsBox = new HBox(12, hierarchicalCheck);
        optionsBox.setPadding(new Insets(4, 0, 4, 0));

        content.getChildren().addAll(summaryLabel, channelListView, optionsBox);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Import");
    }

    private void decompileAllChannels() {
        if (song.getPatterns().isEmpty()) return;
        Pattern pattern = song.getPatterns().getFirst();

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            byte[] trackData = pattern.getTrackData(ch);
            if (trackData == null || trackData.length == 0) {
                channelResults.add(new HierarchyDecompiler.DecompileResult(
                    List.of(), List.of(), false, -1, 0));
                continue;
            }
            ChannelType type = ChannelType.fromChannelIndex(ch);
            channelResults.add(HierarchyDecompiler.decompileTrack(trackData, type));
        }
    }

    public boolean isImportAsHierarchical() {
        return importAsHierarchical;
    }

    /**
     * Build a HierarchicalArrangement from the decompiled results.
     * Should only be called when {@link #isImportAsHierarchical()} is true.
     */
    public HierarchicalArrangement buildHierarchicalArrangement() {
        HierarchicalArrangement arrangement = new HierarchicalArrangement();
        PhraseLibrary library = arrangement.getPhraseLibrary();

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (ch >= channelResults.size()) break;
            var result = channelResults.get(ch);
            Chain chain = arrangement.getChain(ch);

            // Re-create phrases in the arrangement's library
            java.util.Map<Integer, Integer> oldToNew = new java.util.HashMap<>();
            for (Phrase oldPhrase : result.phrases()) {
                ChannelType type = ChannelType.fromChannelIndex(ch);
                Phrase newPhrase = library.createPhrase(oldPhrase.getName(), type);
                newPhrase.setData(oldPhrase.getData());
                oldToNew.put(oldPhrase.getId(), newPhrase.getId());
            }

            // Build chain entries with remapped phrase IDs
            for (var entry : result.chainEntries()) {
                Integer newId = oldToNew.get(entry.getPhraseId());
                if (newId != null) {
                    ChainEntry newEntry = new ChainEntry(newId);
                    newEntry.setRepeatCount(entry.getRepeatCount());
                    newEntry.setTransposeSemitones(entry.getTransposeSemitones());
                    chain.getEntries().add(newEntry);
                }
            }

            if (result.hasLoopPoint()) {
                chain.setLoopEntryIndex(result.loopEntryIndex());
            }
        }

        return arrangement;
    }

    private static Phrase findPhrase(List<Phrase> phrases, int id) {
        for (Phrase p : phrases) {
            if (p.getId() == id) return p;
        }
        return null;
    }
}
