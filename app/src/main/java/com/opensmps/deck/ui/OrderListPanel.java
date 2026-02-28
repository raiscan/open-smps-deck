package com.opensmps.deck.ui;

import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.Song;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.IntConsumer;

/**
 * Order list panel showing the sequence of patterns for playback.
 * Supports add, remove, duplicate, and loop-point operations.
 */
public class OrderListPanel extends VBox {

    private static final String[] CHANNEL_NAMES = {
        "FM1", "FM2", "FM3", "FM4", "FM5", "DAC",
        "PSG1", "PSG2", "PSG3", "Noise"
    };

    private final Song song;
    private final ListView<Integer> orderListView;
    private IntConsumer onOrderRowSelected;
    private int selectedRow = 0;

    public OrderListPanel(Song song) {
        this.song = song;
        this.orderListView = new ListView<>();

        setPrefHeight(160);
        setStyle("-fx-background-color: #1e1e1e;");
        setPadding(new Insets(4));
        setSpacing(4);

        // Toolbar
        HBox toolbar = createToolbar();

        // Order list view
        orderListView.setCellFactory(lv -> new OrderRowCell());
        orderListView.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.intValue() >= 0) {
                selectedRow = newVal.intValue();
                if (onOrderRowSelected != null) {
                    onOrderRowSelected.accept(selectedRow);
                }
            }
        });
        VBox.setVgrow(orderListView, Priority.ALWAYS);

        getChildren().addAll(toolbar, orderListView);
        refresh();
    }

    private HBox createToolbar() {
        HBox toolbar = new HBox(6);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(2, 4, 2, 4));

        Label titleLabel = new Label("Order List");
        titleLabel.setStyle("-fx-text-fill: #88aacc; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addButton = new Button("+");
        addButton.setTooltip(new Tooltip("Add order row"));
        addButton.setOnAction(e -> addRow());

        Button removeButton = new Button("-");
        removeButton.setTooltip(new Tooltip("Remove order row"));
        removeButton.setOnAction(e -> removeRow());

        Button duplicateButton = new Button("Dup");
        duplicateButton.setTooltip(new Tooltip("Duplicate selected row"));
        duplicateButton.setOnAction(e -> duplicateRow());

        Button loopButton = new Button("Loop");
        loopButton.setTooltip(new Tooltip("Set loop point to selected row"));
        loopButton.setOnAction(e -> setLoopPoint());

        toolbar.getChildren().addAll(titleLabel, spacer, addButton, removeButton, duplicateButton, loopButton);
        return toolbar;
    }

    /** Set callback when user selects an order row. */
    public void setOnOrderRowSelected(IntConsumer callback) {
        this.onOrderRowSelected = callback;
    }

    /** Refresh the list from the song model. */
    public void refresh() {
        orderListView.getItems().clear();
        for (int i = 0; i < song.getOrderList().size(); i++) {
            orderListView.getItems().add(i);
        }
        if (selectedRow < song.getOrderList().size()) {
            orderListView.getSelectionModel().select(selectedRow);
        }
    }

    private void addRow() {
        // Add a new order row pointing to pattern 0 for all channels
        int[] newRow = new int[Pattern.CHANNEL_COUNT];
        song.getOrderList().add(newRow);

        // Ensure pattern 0 exists
        if (song.getPatterns().isEmpty()) {
            song.getPatterns().add(new Pattern(0, 64));
        }

        refresh();
    }

    private void removeRow() {
        if (song.getOrderList().size() <= 1) return; // keep at least one row
        int idx = orderListView.getSelectionModel().getSelectedIndex();
        if (idx >= 0) {
            song.getOrderList().remove(idx);
            selectedRow = Math.min(selectedRow, song.getOrderList().size() - 1);
            // Adjust loop point if needed
            if (song.getLoopPoint() >= song.getOrderList().size()) {
                song.setLoopPoint(song.getOrderList().size() - 1);
            }
            refresh();
        }
    }

    private void duplicateRow() {
        int idx = orderListView.getSelectionModel().getSelectedIndex();
        if (idx >= 0) {
            int[] source = song.getOrderList().get(idx);
            int[] copy = source.clone();
            song.getOrderList().add(idx + 1, copy);
            selectedRow = idx + 1;
            refresh();
        }
    }

    private void setLoopPoint() {
        int idx = orderListView.getSelectionModel().getSelectedIndex();
        if (idx >= 0) {
            song.setLoopPoint(idx);
            refresh(); // Re-render to show loop marker
        }
    }

    public int getSelectedRow() {
        return selectedRow;
    }

    /** Custom cell renderer for order rows. */
    private class OrderRowCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer rowIndex, boolean empty) {
            super.updateItem(rowIndex, empty);
            if (empty || rowIndex == null) {
                setText(null);
                setGraphic(null);
                setStyle("");
            } else {
                int[] orderRow = song.getOrderList().get(rowIndex);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%02X", rowIndex));
                if (rowIndex == song.getLoopPoint()) {
                    sb.append(" \u21BA ");
                } else {
                    sb.append("    ");
                }
                for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                    sb.append(String.format("%02X ", orderRow[ch]));
                }
                setText(sb.toString());
                setStyle("-fx-font-family: 'Monospaced'; -fx-text-fill: #cccccc; -fx-background-color: transparent;");
            }
        }
    }
}
