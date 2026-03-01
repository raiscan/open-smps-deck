package com.opensmps.deck.ui;

import com.opensmps.deck.codec.SmpsDecoder;
import com.opensmps.deck.model.*;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * TableView-based dialog for detailed chain editing.
 * Columns: #, Phrase (ComboBox), Transpose (Spinner), Repeat (Spinner), Length.
 * Supports Add, Remove, Move Up/Down, and Set Loop operations.
 */
public class ChainEditor extends Dialog<ButtonType> {

    private final Chain chain;
    private final PhraseLibrary phraseLibrary;
    private final TableView<ChainEntryRow> table;
    private final ObservableList<ChainEntryRow> rows = FXCollections.observableArrayList();

    /** Wrapper for table display. */
    static class ChainEntryRow {
        final int index;
        final ChainEntry entry;
        final Phrase phrase;
        final int rowCount;
        final boolean isLoopPoint;

        ChainEntryRow(int index, ChainEntry entry, Phrase phrase, int rowCount, boolean isLoopPoint) {
            this.index = index;
            this.entry = entry;
            this.phrase = phrase;
            this.rowCount = rowCount;
            this.isLoopPoint = isLoopPoint;
        }
    }

    public ChainEditor(Chain chain, PhraseLibrary phraseLibrary, String channelName) {
        this.chain = chain;
        this.phraseLibrary = phraseLibrary;

        setTitle("Chain Editor - " + channelName);
        setHeaderText("Edit chain entries for " + channelName);

        table = new TableView<>();
        table.setPrefWidth(560);
        table.setPrefHeight(300);
        table.setStyle("-fx-font-family: 'Monospaced';");

        setupColumns();
        refreshRows();

        // Toolbar
        HBox toolbar = createToolbar();

        VBox content = new VBox(8, toolbar, table);
        content.setPadding(new Insets(8));
        VBox.setVgrow(table, Priority.ALWAYS);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    }

    @SuppressWarnings("unchecked")
    private void setupColumns() {
        TableColumn<ChainEntryRow, Number> indexCol = new TableColumn<>("#");
        indexCol.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().index));
        indexCol.setPrefWidth(35);

        TableColumn<ChainEntryRow, String> loopCol = new TableColumn<>("");
        loopCol.setCellValueFactory(cd ->
            new SimpleStringProperty(cd.getValue().isLoopPoint ? "\u21BA" : ""));
        loopCol.setPrefWidth(30);

        TableColumn<ChainEntryRow, String> phraseCol = new TableColumn<>("Phrase");
        phraseCol.setCellValueFactory(cd -> {
            Phrase p = cd.getValue().phrase;
            return new SimpleStringProperty(p != null ?
                String.format("[%d] %s", p.getId(), p.getName()) : "?");
        });
        phraseCol.setPrefWidth(180);

        TableColumn<ChainEntryRow, Number> transposeCol = new TableColumn<>("Transpose");
        transposeCol.setCellValueFactory(cd ->
            new SimpleIntegerProperty(cd.getValue().entry.getTransposeSemitones()));
        transposeCol.setPrefWidth(80);

        TableColumn<ChainEntryRow, Number> repeatCol = new TableColumn<>("Repeat");
        repeatCol.setCellValueFactory(cd ->
            new SimpleIntegerProperty(cd.getValue().entry.getRepeatCount()));
        repeatCol.setPrefWidth(65);

        TableColumn<ChainEntryRow, Number> lengthCol = new TableColumn<>("Rows");
        lengthCol.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().rowCount));
        lengthCol.setPrefWidth(55);

        table.getColumns().addAll(indexCol, loopCol, phraseCol, transposeCol, repeatCol, lengthCol);
    }

    private HBox createToolbar() {
        HBox toolbar = new HBox(6);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button addBtn = new Button("Add");
        addBtn.setTooltip(new Tooltip("Add new phrase entry"));
        addBtn.setOnAction(e -> addEntry());

        Button removeBtn = new Button("Remove");
        removeBtn.setTooltip(new Tooltip("Remove selected entry"));
        removeBtn.setOnAction(e -> removeEntry());

        Button upBtn = new Button("\u25B2");
        upBtn.setTooltip(new Tooltip("Move up"));
        upBtn.setOnAction(e -> moveEntry(-1));

        Button downBtn = new Button("\u25BC");
        downBtn.setTooltip(new Tooltip("Move down"));
        downBtn.setOnAction(e -> moveEntry(1));

        Button loopBtn = new Button("Set Loop");
        loopBtn.setTooltip(new Tooltip("Set loop point at selected entry"));
        loopBtn.setOnAction(e -> setLoopPoint());

        Spinner<Integer> transposeSpinner = new Spinner<>(-48, 48, 0);
        transposeSpinner.setPrefWidth(70);
        transposeSpinner.setEditable(true);

        Button applyTranspose = new Button("Set Trn");
        applyTranspose.setTooltip(new Tooltip("Set transpose for selected entry"));
        applyTranspose.setOnAction(e -> {
            int sel = table.getSelectionModel().getSelectedIndex();
            if (sel >= 0 && sel < chain.getEntries().size()) {
                chain.getEntries().get(sel).setTransposeSemitones(transposeSpinner.getValue());
                refreshRows();
            }
        });

        Spinner<Integer> repeatSpinner = new Spinner<>(1, 255, 1);
        repeatSpinner.setPrefWidth(65);
        repeatSpinner.setEditable(true);

        Button applyRepeat = new Button("Set Rep");
        applyRepeat.setTooltip(new Tooltip("Set repeat count for selected entry"));
        applyRepeat.setOnAction(e -> {
            int sel = table.getSelectionModel().getSelectedIndex();
            if (sel >= 0 && sel < chain.getEntries().size()) {
                chain.getEntries().get(sel).setRepeatCount(repeatSpinner.getValue());
                refreshRows();
            }
        });

        toolbar.getChildren().addAll(
            addBtn, removeBtn, upBtn, downBtn,
            new Separator(), loopBtn,
            new Separator(), transposeSpinner, applyTranspose,
            new Separator(), repeatSpinner, applyRepeat
        );
        return toolbar;
    }

    private void refreshRows() {
        int sel = table.getSelectionModel().getSelectedIndex();
        rows.clear();
        List<ChainEntry> entries = chain.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            ChainEntry entry = entries.get(i);
            Phrase phrase = phraseLibrary.getPhrase(entry.getPhraseId());
            int rowCount = phrase != null ?
                Math.max(1, SmpsDecoder.decode(phrase.getDataDirect()).size()) : 0;
            boolean isLoop = chain.hasLoop() && i == chain.getLoopEntryIndex();
            rows.add(new ChainEntryRow(i, entry, phrase, rowCount, isLoop));
        }
        table.setItems(rows);
        if (sel >= 0 && sel < rows.size()) {
            table.getSelectionModel().select(sel);
        }
    }

    private void addEntry() {
        // Create a new empty phrase and add an entry
        ChannelType type = ChannelType.fromChannelIndex(chain.getChannelIndex());
        Phrase newPhrase = phraseLibrary.createPhrase("New", type);
        chain.getEntries().add(new ChainEntry(newPhrase.getId()));
        refreshRows();
    }

    private void removeEntry() {
        int sel = table.getSelectionModel().getSelectedIndex();
        if (sel >= 0 && sel < chain.getEntries().size()) {
            chain.getEntries().remove(sel);
            if (chain.getLoopEntryIndex() >= chain.getEntries().size()) {
                chain.setLoopEntryIndex(-1);
            }
            refreshRows();
        }
    }

    private void moveEntry(int direction) {
        int sel = table.getSelectionModel().getSelectedIndex();
        int target = sel + direction;
        if (sel < 0 || target < 0 || target >= chain.getEntries().size()) return;

        List<ChainEntry> entries = chain.getEntries();
        ChainEntry entry = entries.remove(sel);
        entries.add(target, entry);

        // Adjust loop point
        if (chain.hasLoop()) {
            int loop = chain.getLoopEntryIndex();
            if (loop == sel) {
                chain.setLoopEntryIndex(target);
            } else if (loop == target) {
                chain.setLoopEntryIndex(sel);
            }
        }

        refreshRows();
        table.getSelectionModel().select(target);
    }

    private void setLoopPoint() {
        int sel = table.getSelectionModel().getSelectedIndex();
        if (sel >= 0) {
            chain.setLoopEntryIndex(sel);
            refreshRows();
        }
    }
}
