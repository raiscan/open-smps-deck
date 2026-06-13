package com.opensmpsdeck.ui;

import com.opensmpsdeck.library.InstrumentAssetKind;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.SourceReference;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Function;

final class LibraryBrowserDialog extends Dialog<List<InstrumentLibraryEntry>> {

    private final List<TableView<InstrumentLibraryEntry>> tables = new ArrayList<>();

    LibraryBrowserDialog(InstrumentLibrary library) {
        setTitle("Instrument Library");
        setHeaderText("Select library assets to deploy into the active song.");

        DialogPane pane = getDialogPane();
        pane.setPrefWidth(980);
        pane.setPrefHeight(620);

        TabPane tabs = new TabPane();
        tabs.getTabs().add(createTab("FM Voices", library.entries(InstrumentAssetKind.FM_VOICE),
                this::addFmColumns));
        tabs.getTabs().add(createTab("PSG Envelopes", library.entries(InstrumentAssetKind.PSG_ENVELOPE),
                this::addEnvelopeColumns));
        tabs.getTabs().add(createTab("Mod Envelopes", library.entries(InstrumentAssetKind.MOD_ENVELOPE),
                this::addEnvelopeColumns));
        tabs.getTabs().add(createTab("DAC Samples", library.entries(InstrumentAssetKind.DAC_SAMPLE),
                this::addDacColumns));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox content = new VBox(tabs);
        content.setPadding(new Insets(10));
        pane.setContent(content);

        ButtonType deployButtonType = new ButtonType("Deploy", ButtonBar.ButtonData.OK_DONE);
        ButtonType previewButtonType = new ButtonType("Preview", ButtonBar.ButtonData.OTHER);
        ButtonType revealButtonType = new ButtonType("Reveal Sources", ButtonBar.ButtonData.OTHER);
        pane.getButtonTypes().addAll(deployButtonType, previewButtonType, revealButtonType, ButtonType.CANCEL);

        Button previewButton = (Button) pane.lookupButton(previewButtonType);
        previewButton.setDisable(true);

        Button revealButton = (Button) pane.lookupButton(revealButtonType);
        revealButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            revealSources();
        });

        setResultConverter(button -> button == deployButtonType ? selectedEntries() : null);
    }

    private Tab createTab(
            String title,
            List<InstrumentLibraryEntry> entries,
            java.util.function.Consumer<TableView<InstrumentLibraryEntry>> extraColumns) {
        TableView<InstrumentLibraryEntry> table = new TableView<>();
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getItems().setAll(entries);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
        addCommonColumns(table);
        extraColumns.accept(table);
        tables.add(table);
        return new Tab(title + " (" + entries.size() + ")", table);
    }

    private void addCommonColumns(TableView<InstrumentLibraryEntry> table) {
        table.getColumns().add(textColumn("Name", InstrumentLibraryEntry::displayName, 190));
        table.getColumns().add(textColumn("Game", entry -> firstSourceValue(entry, SourceReference::gameName), 120));
        table.getColumns().add(textColumn("Variant", entry -> firstSourceValue(entry, SourceReference::variantPath), 140));
        table.getColumns().add(textColumn("Source", this::sourceFile, 170));
        table.getColumns().add(textColumn("Driver", entry -> firstSourceValue(entry, SourceReference::driverFamily), 100));
        table.getColumns().add(textColumn("Ext", entry -> firstSourceValue(entry, SourceReference::configExtension), 70));

        TableColumn<InstrumentLibraryEntry, Number> sourceCount = new TableColumn<>("Sources");
        sourceCount.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().sourceReferences().size()));
        sourceCount.setPrefWidth(70);
        table.getColumns().add(sourceCount);
    }

    private void addFmColumns(TableView<InstrumentLibraryEntry> table) {
        table.getColumns().add(textColumn(
                "Algorithm / Feedback",
                entry -> entry.algorithm() + " / " + entry.feedback(),
                130));
    }

    private void addEnvelopeColumns(TableView<InstrumentLibraryEntry> table) {
        TableColumn<InstrumentLibraryEntry, Number> steps = new TableColumn<>("Steps");
        steps.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().stepCount()));
        steps.setPrefWidth(70);
        table.getColumns().add(steps);
    }

    private void addDacColumns(TableView<InstrumentLibraryEntry> table) {
        TableColumn<InstrumentLibraryEntry, Number> rate = new TableColumn<>("Rate");
        rate.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().playbackRate()));
        rate.setPrefWidth(70);
        table.getColumns().add(rate);

        TableColumn<InstrumentLibraryEntry, Number> byteLength = new TableColumn<>("Bytes");
        byteLength.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().byteLength()));
        byteLength.setPrefWidth(90);
        table.getColumns().add(byteLength);
    }

    private TableColumn<InstrumentLibraryEntry, String> textColumn(
            String title,
            Function<InstrumentLibraryEntry, String> valueFactory,
            double width) {
        TableColumn<InstrumentLibraryEntry, String> column = new TableColumn<>(title);
        column.setCellValueFactory(c -> new SimpleStringProperty(safe(valueFactory.apply(c.getValue()))));
        column.setPrefWidth(width);
        return column;
    }

    private List<InstrumentLibraryEntry> selectedEntries() {
        List<InstrumentLibraryEntry> selected = new ArrayList<>();
        for (TableView<InstrumentLibraryEntry> table : tables) {
            selected.addAll(table.getSelectionModel().getSelectedItems());
        }
        return selected;
    }

    private void revealSources() {
        List<InstrumentLibraryEntry> selected = selectedEntries();
        if (selected.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Select one or more assets first.", ButtonType.OK);
            alert.setTitle("Instrument Library");
            alert.setHeaderText("No assets selected");
            alert.showAndWait();
            return;
        }

        TextArea textArea = new TextArea(sourceDetails(selected));
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setPrefColumnCount(100);
        textArea.setPrefRowCount(24);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Instrument Library");
        alert.setHeaderText("Source references for selected assets");
        alert.getDialogPane().setContent(textArea);
        alert.showAndWait();
    }

    private String sourceDetails(List<InstrumentLibraryEntry> entries) {
        StringBuilder builder = new StringBuilder();
        for (InstrumentLibraryEntry entry : entries) {
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }
            builder.append(entry.displayName()).append(" [").append(entry.kind()).append("]");
            if (entry.sourceReferences().isEmpty()) {
                builder.append(System.lineSeparator()).append("  No source references");
                continue;
            }
            for (SourceReference source : entry.sourceReferences()) {
                builder.append(System.lineSeparator()).append("  - ");
                StringJoiner joiner = new StringJoiner(", ");
                addPart(joiner, "root", source.scanRoot());
                addPart(joiner, "driver", source.driverFamily());
                addPart(joiner, "game", source.gameName());
                addPart(joiner, "variant", source.variantPath());
                addPart(joiner, "ext", source.configExtension());
                addPart(joiner, "song", source.sourceSongFile());
                addPart(joiner, "companion", source.sourceCompanionFile());
                addPart(joiner, "id", source.originalIndexOrId());
                addPart(joiner, "summary", source.driverSummary());
                builder.append(joiner);
            }
        }
        return builder.toString();
    }

    private String sourceFile(InstrumentLibraryEntry entry) {
        SourceReference source = firstSource(entry);
        if (source == null) {
            return "";
        }
        if (!safe(source.sourceSongFile()).isBlank()) {
            return source.sourceSongFile();
        }
        return source.sourceCompanionFile();
    }

    private static String firstSourceValue(
            InstrumentLibraryEntry entry,
            Function<SourceReference, String> valueFactory) {
        SourceReference source = firstSource(entry);
        return source == null ? "" : valueFactory.apply(source);
    }

    private static SourceReference firstSource(InstrumentLibraryEntry entry) {
        return entry.sourceReferences().isEmpty() ? null : entry.sourceReferences().get(0);
    }

    private static void addPart(StringJoiner joiner, String label, String value) {
        if (!safe(value).isBlank()) {
            joiner.add(label + "=" + value);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
