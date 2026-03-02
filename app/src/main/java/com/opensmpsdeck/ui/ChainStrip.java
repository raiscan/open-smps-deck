package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.IntConsumer;

/**
 * Horizontal strip showing the active channel's chain entries as clickable cells.
 * Each cell displays phrase name, transpose badge, and repeat badge.
 * Click to select and navigate the phrase editor below.
 */
public class ChainStrip extends HBox {

    private Chain chain;
    private PhraseLibrary phraseLibrary;
    private int selectedIndex = -1;
    private IntConsumer onEntrySelected; // callback: phrase ID

    public ChainStrip() {
        setSpacing(2);
        setPadding(new Insets(2, 4, 2, 4));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: #1e1e2e;");
        setPrefHeight(30);
        setMinHeight(30);
    }

    public void setChain(Chain chain, PhraseLibrary phraseLibrary) {
        this.chain = chain;
        this.phraseLibrary = phraseLibrary;
        this.selectedIndex = chain != null && !chain.getEntries().isEmpty() ? 0 : -1;
        rebuild();
    }

    public void setOnEntrySelected(IntConsumer callback) {
        this.onEntrySelected = callback;
    }

    public int getSelectedIndex() { return selectedIndex; }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        updateSelection();
    }

    public void rebuild() {
        getChildren().clear();
        if (chain == null || phraseLibrary == null) return;

        for (int i = 0; i < chain.getEntries().size(); i++) {
            ChainEntry entry = chain.getEntries().get(i);
            Phrase phrase = phraseLibrary.getPhrase(entry.getPhraseId());
            String name = phrase != null ? phrase.getName() : "?";

            StringBuilder labelText = new StringBuilder();
            if (chain.hasLoop() && i == chain.getLoopEntryIndex()) {
                labelText.append("\u21BA ");
            }
            labelText.append(name);
            if (entry.getTransposeSemitones() != 0) {
                labelText.append(String.format(" %+d", entry.getTransposeSemitones()));
            }
            if (entry.getRepeatCount() > 1) {
                labelText.append(" \u00D7").append(entry.getRepeatCount());
            }

            Label cell = new Label(labelText.toString());
            cell.setPadding(new Insets(2, 6, 2, 6));
            cell.setMinWidth(40);

            boolean isSelected = i == selectedIndex;
            applyStyle(cell, isSelected);

            final int idx = i;
            cell.setOnMouseClicked(e -> {
                selectedIndex = idx;
                updateSelection();
                if (onEntrySelected != null) {
                    onEntrySelected.accept(entry.getPhraseId());
                }
            });

            getChildren().add(cell);
        }

        // Spacer to fill remaining width
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);
    }

    private void updateSelection() {
        int childCount = getChildren().size();
        for (int i = 0; i < childCount; i++) {
            if (getChildren().get(i) instanceof Label label) {
                applyStyle(label, i == selectedIndex);
            }
        }
    }

    private void applyStyle(Label cell, boolean selected) {
        if (selected) {
            cell.setStyle(
                "-fx-background-color: #3a5a8a; -fx-text-fill: #ffffff; " +
                "-fx-font-family: 'Monospaced'; -fx-font-size: 11; " +
                "-fx-border-color: #88ccff; -fx-border-width: 1; -fx-border-radius: 2; " +
                "-fx-background-radius: 2;"
            );
        } else {
            cell.setStyle(
                "-fx-background-color: #2a2a3e; -fx-text-fill: #aaaacc; " +
                "-fx-font-family: 'Monospaced'; -fx-font-size: 11; " +
                "-fx-border-color: #444466; -fx-border-width: 1; -fx-border-radius: 2; " +
                "-fx-background-radius: 2;"
            );
        }
    }
}
