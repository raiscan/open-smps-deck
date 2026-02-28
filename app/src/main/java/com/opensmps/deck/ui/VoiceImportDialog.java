package com.opensmps.deck.ui;

import com.opensmps.deck.io.ImportableVoice;
import com.opensmps.deck.io.RomVoiceImporter;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for browsing and importing FM voices from SMPS .bin files.
 * Scans a user-selected directory, displays voices in a filterable table,
 * and allows multi-select import into the active song's voice bank.
 */
public class VoiceImportDialog extends Dialog<List<ImportableVoice>> {

    private static File lastDirectory;
    private final RomVoiceImporter importer = new RomVoiceImporter();
    private final TableView<ImportableVoice> table;
    private final ObservableList<ImportableVoice> allVoices;
    private final FilteredList<ImportableVoice> filteredList;

    public VoiceImportDialog() {
        this(null);
    }

    /**
     * Creates a voice import dialog, optionally pre-populated with the given voices.
     * When {@code preloadedVoices} is non-null the directory picker is hidden and the
     * table is populated immediately with the supplied voices.
     *
     * @param preloadedVoices voices to pre-populate, or {@code null} for directory-scan mode
     */
    public VoiceImportDialog(List<ImportableVoice> preloadedVoices) {
        boolean preloaded = preloadedVoices != null;
        setTitle(preloaded ? "Select Voices to Import" : "Import Voices from SMPS Files");
        setHeaderText(preloaded
                ? "Select voices to add to the song's voice bank:"
                : "Select a directory containing SMPS files (.bin, .s3k, .sm2, .smp):");

        DialogPane pane = getDialogPane();
        pane.setPrefWidth(600);
        pane.setPrefHeight(500);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Directory picker (only shown in directory-scan mode)
        Label dirLabel = new Label("No directory selected");
        Button browseButton = new Button("Browse...");
        HBox dirRow = new HBox(8, dirLabel, browseButton);
        HBox.setHgrow(dirLabel, Priority.ALWAYS);

        // Filter
        TextField filterField = new TextField();
        filterField.setPromptText("Filter by song name or algorithm...");

        // Table
        allVoices = FXCollections.observableArrayList();
        filteredList = new FilteredList<>(allVoices, p -> true);
        table = new TableView<>(filteredList);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<ImportableVoice, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().sourceSong() + " #" + c.getValue().originalIndex()));
        nameCol.setPrefWidth(200);

        TableColumn<ImportableVoice, String> songCol = new TableColumn<>("Source Song");
        songCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sourceSong()));
        songCol.setPrefWidth(140);

        TableColumn<ImportableVoice, Number> idxCol = new TableColumn<>("Index");
        idxCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().originalIndex()));
        idxCol.setPrefWidth(60);

        TableColumn<ImportableVoice, Number> algoCol = new TableColumn<>("Algo");
        algoCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().algorithm()));
        algoCol.setPrefWidth(60);

        table.getColumns().addAll(List.of(nameCol, songCol, idxCol, algoCol));

        // Filter logic
        filterField.textProperty().addListener((obs, oldVal, newVal) -> {
            String filter = newVal.toLowerCase().trim();
            filteredList.setPredicate(v -> {
                if (filter.isEmpty()) return true;
                return v.sourceSong().toLowerCase().contains(filter)
                        || String.valueOf(v.algorithm()).contains(filter);
            });
        });

        // Browse action (only wired in directory-scan mode)
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select SMPS Directory");
            if (lastDirectory != null && lastDirectory.isDirectory()) {
                chooser.setInitialDirectory(lastDirectory);
            }
            File dir = chooser.showDialog(pane.getScene().getWindow());
            if (dir != null) {
                lastDirectory = dir;
                dirLabel.setText(dir.getAbsolutePath());
                List<ImportableVoice> voices = importer.scanDirectory(dir);
                allVoices.setAll(voices);
            }
        });

        if (preloaded) {
            allVoices.setAll(preloadedVoices);
            content.getChildren().addAll(filterField, table);
        } else {
            content.getChildren().addAll(dirRow, filterField, table);
        }
        pane.setContent(content);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return new ArrayList<>(table.getSelectionModel().getSelectedItems());
            }
            return null;
        });
    }
}
