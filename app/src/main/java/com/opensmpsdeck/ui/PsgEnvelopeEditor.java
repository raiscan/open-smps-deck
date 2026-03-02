package com.opensmpsdeck.ui;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.model.PsgEnvelope;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Dialog for editing a PSG volume envelope.
 *
 * <p>Displays the envelope as a clickable bar graph where each step is a vertical
 * bar. Volume 0 is the loudest (tallest bar), volume 7 is the quietest (shortest).
 * Click or drag on the canvas to set volume values. Works on a copy of the source
 * envelope; OK returns the edited copy, Cancel returns null.
 */
public class PsgEnvelopeEditor extends Dialog<PsgEnvelope> {

    private static final String BG_COLOR = "#1e1e1e";
    private static final String TEXT_COLOR = "#cccccc";
    private static final String BAR_FILL = "#55cc55";
    private static final String BAR_OUTLINE = "#338833";
    private static final String GRID_COLOR = "#333333";

    private static final double BAR_WIDTH = 20;
    private static final double BAR_MAX_HEIGHT = 160;
    private static final double CANVAS_PADDING = 12;
    private static final double LABEL_AREA_HEIGHT = 18;

    private static final Font INDEX_FONT = Font.font("Monospaced", 11);
    private static final Font GRID_FONT = Font.font("Monospaced", 10);

    private final PsgEnvelope envelope;
    private final Canvas canvas;
    private final Label stepCountLabel;
    private PlaybackEngine previewEngine;

    /**
     * Creates a new PSG envelope editor dialog.
     *
     * @param source the envelope to edit (a working copy is made internally)
     */
    public PsgEnvelopeEditor(PsgEnvelope source) {
        this.envelope = new PsgEnvelope(source.getName(), source.getData());

        setTitle("PSG Envelope Editor");
        setResizable(true);

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
        TextField nameField = new TextField(envelope.getName());
        nameField.setPrefWidth(250);
        nameField.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        nameField.textProperty().addListener((obs, oldVal, newVal) -> envelope.setName(newVal));
        nameRow.getChildren().addAll(nameLabel, nameField);

        // Canvas
        canvas = new Canvas(computeCanvasWidth(), BAR_MAX_HEIGHT + CANVAS_PADDING * 2 + LABEL_AREA_HEIGHT);
        setupCanvasInteraction();

        // Controls row
        HBox controlsRow = new HBox(8);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        Button addButton = new Button("+Step");
        addButton.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        addButton.setOnAction(e -> {
            envelope.addStep(0);
            refreshCanvas();
        });

        Button removeButton = new Button("-Step");
        removeButton.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        removeButton.setOnAction(e -> {
            if (envelope.getStepCount() > 0) {
                envelope.removeStep(envelope.getStepCount() - 1);
                refreshCanvas();
            }
        });

        stepCountLabel = new Label();
        stepCountLabel.setStyle("-fx-text-fill: " + TEXT_COLOR + ";");
        updateStepCountLabel();

        Button previewButton = new Button("\u266B Preview");
        previewButton.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        previewButton.setOnAction(e -> previewEnvelope());

        controlsRow.getChildren().addAll(addButton, removeButton, stepCountLabel, previewButton);

        mainLayout.getChildren().addAll(nameRow, canvas, controlsRow);
        dialogPane.setContent(mainLayout);

        // Result converter
        setResultConverter(button -> button == ButtonType.OK ? envelope : null);

        // Initial draw
        drawBarGraph();
    }

    /**
     * Sets the playback engine used for envelope preview.
     *
     * @param engine the playback engine (may be null to disable preview)
     */
    public void setPreviewEngine(PlaybackEngine engine) {
        this.previewEngine = engine;
    }

    /**
     * Plays a short PSG tone on channel 0 to preview the sound.
     * Stops any active playback first to ensure the PSG channel is available.
     */
    private void previewEnvelope() {
        if (previewEngine == null) return;

        // Stop any active playback so PSG channel 0 lock is released
        previewEngine.stop();

        var driver = previewEngine.getDriver();

        // PSG channel 0: set frequency for middle C (~262Hz)
        // SN76489 freq value for ~262Hz at 3.58MHz clock: 3579545/(32*262) = 427 = 0x1AB
        int freq = 0x1AB;
        driver.writePsg(this, 0x80 | (freq & 0x0F));        // Latch tone 0 + low 4 bits
        driver.writePsg(this, (freq >> 4) & 0x3F);           // High 6 bits
        driver.writePsg(this, 0x90 | 0x00);                  // Volume 0 (max) on channel 0

        // Ensure audio output is running so the preview is audible
        previewEngine.play();

        // Schedule silence after 500ms
        Thread silenceThread = new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            driver.writePsg(this, 0x9F); // Volume 0xF (silent) on channel 0
        });
        silenceThread.setDaemon(true);
        silenceThread.start();
    }

    private void setupCanvasInteraction() {
        canvas.setOnMousePressed(e -> handleCanvasClick(e.getX(), e.getY()));
        canvas.setOnMouseDragged(e -> handleCanvasClick(e.getX(), e.getY()));
    }

    private void handleCanvasClick(double x, double y) {
        int stepCount = envelope.getStepCount();
        if (stepCount == 0) {
            return;
        }

        // Determine which step was clicked
        double barsStartX = CANVAS_PADDING;
        int stepIndex = (int) ((x - barsStartX) / BAR_WIDTH);
        if (stepIndex < 0 || stepIndex >= stepCount) {
            return;
        }

        // Convert y position to volume (0=loudest at top, 7=quietest at bottom)
        double graphTop = CANVAS_PADDING;
        double relativeY = y - graphTop;
        int volume = (int) (relativeY / (BAR_MAX_HEIGHT / 8.0));
        volume = Math.max(0, Math.min(7, volume));

        envelope.setStep(stepIndex, volume);
        drawBarGraph();
    }

    private void refreshCanvas() {
        canvas.setWidth(computeCanvasWidth());
        updateStepCountLabel();
        drawBarGraph();
    }

    private double computeCanvasWidth() {
        int stepCount = envelope.getStepCount();
        return CANVAS_PADDING * 2 + Math.max(stepCount, 1) * BAR_WIDTH + 20;
    }

    private void updateStepCountLabel() {
        stepCountLabel.setText("Steps: " + envelope.getStepCount());
    }

    private void drawBarGraph() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        // Clear background
        gc.setFill(Color.web(BG_COLOR));
        gc.fillRect(0, 0, w, h);

        double graphTop = CANVAS_PADDING;
        double graphBottom = graphTop + BAR_MAX_HEIGHT;

        // Draw horizontal grid lines for each volume level (0-7)
        gc.setStroke(Color.web(GRID_COLOR));
        gc.setLineWidth(1);
        gc.setFont(GRID_FONT);
        for (int v = 0; v <= 8; v++) {
            double lineY = graphTop + (v * BAR_MAX_HEIGHT / 8.0);
            gc.strokeLine(CANVAS_PADDING, lineY, w - CANVAS_PADDING, lineY);

            // Volume labels on the right edge
            if (v < 8) {
                gc.setFill(Color.web(GRID_COLOR));
                gc.fillText(String.valueOf(v), w - CANVAS_PADDING + 2, lineY + BAR_MAX_HEIGHT / 16.0 + 4);
            }
        }

        // Draw bars
        int stepCount = envelope.getStepCount();
        gc.setFont(INDEX_FONT);
        for (int i = 0; i < stepCount; i++) {
            int volume = envelope.getStep(i);
            double barHeight = BAR_MAX_HEIGHT * (8 - volume) / 8.0;
            double barX = CANVAS_PADDING + i * BAR_WIDTH;
            double barY = graphBottom - barHeight;

            // Bar fill
            gc.setFill(Color.web(BAR_FILL));
            gc.fillRect(barX + 1, barY, BAR_WIDTH - 2, barHeight);

            // Bar outline
            gc.setStroke(Color.web(BAR_OUTLINE));
            gc.setLineWidth(1);
            gc.strokeRect(barX + 1, barY, BAR_WIDTH - 2, barHeight);

            // Step index label below the bar (hex)
            gc.setFill(Color.web(TEXT_COLOR));
            gc.fillText(String.format("%02X", i), barX + 2, graphBottom + LABEL_AREA_HEIGHT - 4);
        }
    }
}
