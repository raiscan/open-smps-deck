package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * Dialog that previews the hierarchical decompilation of an imported SMPS song.
 * Shows the per-channel structure (chain entries, phrases, shared phrase counts,
 * loop points) of the arrangement built by the importer, and provides
 * Import/Cancel buttons.
 *
 * <p>The displayed arrangement is the one {@code SmpsImporter} attached to the
 * song. It is shown as-is — re-decompiling here would lose loop points and
 * mode-specific note compensation.
 */
public class ImportPreviewDialog extends Dialog<ButtonType> {

    private static final String[] CHANNEL_NAMES = {
        "FM1", "FM2", "FM3", "FM4", "FM5", "DAC",
        "PSG1", "PSG2", "PSG3", "Noise"
    };

    private final Song song;

    public ImportPreviewDialog(Song song) {
        this.song = song;
        setTitle("Import Structure Preview");
        setHeaderText("Decompiled structure from imported SMPS data");

        HierarchicalArrangement arrangement = song.getHierarchicalArrangement();

        VBox content = new VBox(8);
        content.setPadding(new Insets(12));
        content.setPrefWidth(550);
        content.setPrefHeight(400);

        // Phrase reference counts across all chains (shared = referenced 2+ times)
        Map<Integer, Integer> refCounts = new HashMap<>();
        int totalLoops = 0;
        if (arrangement != null) {
            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                Chain chain = arrangement.getChain(ch);
                for (ChainEntry entry : chain.getEntries()) {
                    refCounts.merge(entry.getPhraseId(), 1, Integer::sum);
                }
                if (chain.hasLoop()) totalLoops++;
            }
        }
        int totalPhrases = arrangement != null
                ? arrangement.getPhraseLibrary().getAllPhrases().size() : 0;
        long totalShared = refCounts.values().stream().filter(c -> c > 1).count();

        Label summaryLabel = new Label(String.format(
            "Phrases: %d  |  Shared: %d  |  Channels with loop: %d",
            totalPhrases, totalShared, totalLoops));
        summaryLabel.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 12;");

        // Per-channel details
        ListView<String> channelListView = new ListView<>();
        channelListView.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 11;");
        VBox.setVgrow(channelListView, Priority.ALWAYS);

        if (arrangement != null) {
            PhraseLibrary library = arrangement.getPhraseLibrary();
            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                Chain chain = arrangement.getChain(ch);
                if (chain.getEntries().isEmpty()) continue;

                long distinctPhrases = chain.getEntries().stream()
                        .map(ChainEntry::getPhraseId).distinct().count();
                long sharedInChain = chain.getEntries().stream()
                        .map(ChainEntry::getPhraseId).distinct()
                        .filter(id -> refCounts.getOrDefault(id, 0) > 1).count();

                StringBuilder sb = new StringBuilder();
                sb.append(CHANNEL_NAMES[ch]).append(": ");
                sb.append(chain.getEntries().size()).append(" entries, ");
                sb.append(distinctPhrases).append(" phrases");
                if (sharedInChain > 0) {
                    sb.append(" (").append(sharedInChain).append(" shared)");
                }
                if (chain.hasLoop()) {
                    sb.append(" [loop at ").append(chain.getLoopEntryIndex()).append("]");
                }
                channelListView.getItems().add(sb.toString());

                for (int i = 0; i < chain.getEntries().size(); i++) {
                    ChainEntry entry = chain.getEntries().get(i);
                    Phrase phrase = library.getPhrase(entry.getPhraseId());
                    String phraseName = phrase != null ? phrase.getName() : "?";
                    int dataLen = phrase != null ? phrase.getDataDirect().length : 0;

                    StringBuilder entryStr = new StringBuilder("  ");
                    if (chain.hasLoop() && i == chain.getLoopEntryIndex()) {
                        entryStr.append("↺ ");
                    } else {
                        entryStr.append("  ");
                    }
                    entryStr.append(String.format("[%d] %s (%d bytes)",
                            entry.getPhraseId(), phraseName, dataLen));
                    if (entry.getRepeatCount() > 1) {
                        entryStr.append(" ×").append(entry.getRepeatCount());
                    }
                    if (entry.getTransposeSemitones() != 0) {
                        entryStr.append(String.format(" %+d", entry.getTransposeSemitones()));
                    }
                    channelListView.getItems().add(entryStr.toString());
                }
            }
        }

        content.getChildren().addAll(summaryLabel, channelListView);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Import");
    }

    /**
     * Returns the arrangement the importer attached to the song.
     * Kept for call-site compatibility; this dialog no longer rebuilds it.
     */
    public HierarchicalArrangement buildHierarchicalArrangement() {
        return song.getHierarchicalArrangement();
    }
}
