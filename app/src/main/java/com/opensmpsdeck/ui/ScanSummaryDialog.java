package com.opensmpsdeck.ui;

import com.opensmpsdeck.library.InstrumentAssetKind;
import com.opensmpsdeck.library.scan.ScanFailure;
import com.opensmpsdeck.library.scan.ScanSummary;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class ScanSummaryDialog extends Dialog<ButtonType> {

    ScanSummaryDialog(ScanSummary summary) {
        setTitle("Instrument Library Scan");
        setHeaderText("Scan complete");

        GridPane counters = new GridPane();
        counters.setHgap(16);
        counters.setVgap(6);
        counters.setPadding(new Insets(0, 0, 8, 0));

        int row = 0;
        row = addCounter(counters, row, "Files visited", summary.filesVisited());
        row = addCounter(counters, row, "Config directories", summary.configDirectoriesFound());
        row = addCounter(counters, row, "Song imports attempted", summary.fullSongImportsAttempted());
        row = addCounter(counters, row, "Song imports succeeded", summary.fullSongImportsSucceeded());
        row = addCounter(counters, row, "Asset-only folders", summary.assetOnlyFoldersHarvested());
        row = addCounter(counters, row, "Unsupported song dialects", summary.unsupportedSongDialects());
        row = addCounter(counters, row, "New assets", summary.newAssets());
        row = addCounter(counters, row, "Duplicate assets", summary.duplicateAssets());

        row = addSection(counters, row, "New assets by kind");
        for (InstrumentAssetKind kind : InstrumentAssetKind.values()) {
            row = addCounter(counters, row, label(kind), summary.newAssetsByKind().getOrDefault(kind, 0));
        }

        row = addSection(counters, row, "Duplicates by kind");
        for (InstrumentAssetKind kind : InstrumentAssetKind.values()) {
            row = addCounter(counters, row, label(kind), summary.duplicateAssetsByKind().getOrDefault(kind, 0));
        }

        row = addSection(counters, row, "Library totals");
        for (InstrumentAssetKind kind : InstrumentAssetKind.values()) {
            row = addCounter(counters, row, label(kind), summary.totalLibraryCountsByKind().getOrDefault(kind, 0));
        }

        TextArea failures = new TextArea(failuresText(summary));
        failures.setEditable(false);
        failures.setWrapText(false);
        failures.setPrefRowCount(8);
        failures.setPrefColumnCount(90);

        VBox content = new VBox(8, counters, new Label("Failures:"), failures);
        content.setPadding(new Insets(10));
        VBox.setVgrow(failures, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportWidth(680);
        scrollPane.setPrefViewportHeight(540);

        getDialogPane().setContent(scrollPane);
        getDialogPane().getButtonTypes().add(ButtonType.OK);
    }

    private int addCounter(GridPane grid, int row, String label, int value) {
        grid.add(new Label(label + ":"), 0, row);
        grid.add(new Label(Integer.toString(value)), 1, row);
        return row + 1;
    }

    private int addSection(GridPane grid, int row, String label) {
        Label section = new Label(label);
        section.setStyle("-fx-font-weight: bold;");
        grid.add(section, 0, row, 2, 1);
        return row + 1;
    }

    private String failuresText(ScanSummary summary) {
        if (summary.failures().isEmpty()) {
            return "No failures.";
        }
        StringBuilder builder = new StringBuilder();
        for (ScanFailure failure : summary.failures()) {
            builder.append(failure.path()).append(": ").append(failure.reason()).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private static String label(InstrumentAssetKind kind) {
        return switch (kind) {
            case FM_VOICE -> "FM voices";
            case PSG_ENVELOPE -> "PSG envelopes";
            case MOD_ENVELOPE -> "Mod envelopes";
            case DAC_SAMPLE -> "DAC samples";
        };
    }
}
