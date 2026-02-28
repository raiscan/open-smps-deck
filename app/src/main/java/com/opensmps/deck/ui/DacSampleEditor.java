package com.opensmps.deck.ui;

import com.opensmps.deck.model.DacSample;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Dialog for editing a DAC sample's name and playback rate.
 *
 * <p>The dialog displays the sample name (editable), playback rate spinner
 * (0-255), and the sample data size as a read-only label. On OK, the sample's
 * name and rate are updated in place and the sample is returned. On Cancel,
 * null is returned.
 */
public class DacSampleEditor extends Dialog<DacSample> {

    private static final String BG_COLOR = "#1e1e1e";
    private static final String TEXT_COLOR = "#cccccc";

    /**
     * Creates a new DAC sample editor dialog.
     *
     * @param sample the sample to edit (modified in place on OK)
     */
    public DacSampleEditor(DacSample sample) {
        boolean isNew = sample.getName() == null || sample.getName().isEmpty();

        setTitle("Edit DAC Sample");
        setHeaderText(isNew ? "New DAC Sample" : "Edit: " + sample.getName());
        setResizable(false);

        // Dialog pane setup
        DialogPane dialogPane = getDialogPane();
        dialogPane.setStyle("-fx-background-color: " + BG_COLOR + ";");
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Main layout
        VBox mainLayout = new VBox(10);
        mainLayout.setPadding(new Insets(12));
        mainLayout.setStyle("-fx-background-color: " + BG_COLOR + ";");

        // Name field row
        HBox nameRow = new HBox(8);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label("Name:");
        nameLabel.setStyle("-fx-text-fill: " + TEXT_COLOR + "; -fx-font-weight: bold;");
        TextField nameField = new TextField(sample.getName());
        nameField.setPrefWidth(250);
        nameField.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        nameRow.getChildren().addAll(nameLabel, nameField);

        // Rate spinner row
        HBox rateRow = new HBox(8);
        rateRow.setAlignment(Pos.CENTER_LEFT);
        Label rateLabel = new Label("Rate:");
        rateLabel.setStyle("-fx-text-fill: " + TEXT_COLOR + "; -fx-font-weight: bold;");
        Spinner<Integer> rateSpinner = new Spinner<>(0, 255, sample.getRate());
        rateSpinner.setEditable(true);
        rateSpinner.setPrefWidth(100);
        rateSpinner.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        rateRow.getChildren().addAll(rateLabel, rateSpinner);

        // Size label row
        HBox sizeRow = new HBox(8);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        Label sizeLabel = new Label("Size:");
        sizeLabel.setStyle("-fx-text-fill: " + TEXT_COLOR + "; -fx-font-weight: bold;");
        int dataLength = sample.getDataDirect().length;
        Label sizeValue = new Label(String.format("%d bytes", dataLength));
        sizeValue.setStyle("-fx-text-fill: " + TEXT_COLOR + ";");
        sizeRow.getChildren().addAll(sizeLabel, sizeValue);

        mainLayout.getChildren().addAll(nameRow, rateRow, sizeRow);
        dialogPane.setContent(mainLayout);

        // Result converter: on OK update sample in place and return it
        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                sample.setName(nameField.getText());
                sample.setRate(rateSpinner.getValue());
                return sample;
            }
            return null;
        });
    }
}
