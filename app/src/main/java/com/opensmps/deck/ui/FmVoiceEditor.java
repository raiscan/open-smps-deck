package com.opensmps.deck.ui;

import com.opensmps.deck.model.FmVoice;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Dialog for editing an FM voice patch.
 *
 * <p>Displays the YM2612 algorithm topology as a canvas diagram and provides
 * slider controls for all operator parameters. Works on a copy of the source
 * voice; OK returns the edited copy, Cancel returns null.
 */
public class FmVoiceEditor extends Dialog<FmVoice> {

    private static final String BG_COLOR = "#1e1e1e";
    private static final String PANEL_COLOR = "#252525";
    private static final String TEXT_COLOR = "#cccccc";
    private static final String HEADER_COLOR = "#88aacc";
    private static final String CARRIER_COLOR = "#55ccff";
    private static final String MODULATOR_COLOR = "#cccccc";
    private static final String CARRIER_BORDER = "#55ccff";
    private static final String MODULATOR_BORDER = "#666666";

    private static final Font DIAGRAM_FONT = Font.font("Monospaced", FontWeight.BOLD, 14);
    private static final Font LABEL_FONT = Font.font("System", 12);

    private static final double CANVAS_WIDTH = 200;
    private static final double CANVAS_HEIGHT = 120;

    private final FmVoice voice;
    private final Canvas algorithmCanvas;
    private final VBox[] operatorColumns = new VBox[4];

    /**
     * Creates a new FM voice editor dialog.
     *
     * @param source the voice to edit (a working copy is made internally)
     */
    public FmVoiceEditor(FmVoice source) {
        this.voice = new FmVoice(source.getName(), source.getData());

        setTitle("FM Voice Editor");
        setResizable(true);

        // Dialog pane setup
        DialogPane dialogPane = getDialogPane();
        dialogPane.setStyle("-fx-background-color: " + BG_COLOR + ";");
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Main layout
        VBox mainLayout = new VBox(12);
        mainLayout.setPadding(new Insets(12));
        mainLayout.setStyle("-fx-background-color: " + BG_COLOR + ";");

        // Top row: Name, Algorithm, Feedback
        HBox topRow = buildTopRow();

        // Algorithm diagram
        algorithmCanvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        algorithmCanvas.setStyle("-fx-background-color: " + PANEL_COLOR + ";");
        StackPane canvasWrapper = new StackPane(algorithmCanvas);
        canvasWrapper.setStyle("-fx-background-color: " + PANEL_COLOR + "; "
                + "-fx-border-color: #333333; -fx-border-width: 1;");
        canvasWrapper.setPadding(new Insets(4));
        canvasWrapper.setMaxWidth(CANVAS_WIDTH + 12);
        canvasWrapper.setAlignment(Pos.CENTER_LEFT);

        // Operator columns
        HBox operatorRow = new HBox(8);
        operatorRow.setAlignment(Pos.TOP_LEFT);
        for (int display = 0; display < 4; display++) {
            operatorColumns[display] = buildOperatorColumn(display);
            operatorRow.getChildren().add(operatorColumns[display]);
        }

        ScrollPane scrollPane = new ScrollPane(operatorRow);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background: " + BG_COLOR + "; -fx-background-color: " + BG_COLOR + ";");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        mainLayout.getChildren().addAll(topRow, canvasWrapper, scrollPane);
        dialogPane.setContent(mainLayout);
        dialogPane.setPrefSize(720, 620);

        // Result converter
        setResultConverter(button -> button == ButtonType.OK ? voice : null);

        // Initial draw
        drawAlgorithmDiagram();
        updateOperatorBorders();
    }

    private HBox buildTopRow() {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);

        // Name field
        Label nameLabel = createHeaderLabel("Name:");
        TextField nameField = new TextField(voice.getName());
        nameField.setPrefWidth(200);
        nameField.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        nameField.textProperty().addListener((obs, oldVal, newVal) -> voice.setName(newVal));

        // Algorithm combo
        Label algoLabel = createHeaderLabel("Algorithm:");
        ComboBox<Integer> algoCombo = new ComboBox<>();
        for (int i = 0; i <= 7; i++) algoCombo.getItems().add(i);
        algoCombo.setValue(voice.getAlgorithm());
        algoCombo.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        algoCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                voice.setAlgorithm(newVal);
                drawAlgorithmDiagram();
                updateOperatorBorders();
            }
        });

        // Feedback combo
        Label fbLabel = createHeaderLabel("Feedback:");
        ComboBox<Integer> fbCombo = new ComboBox<>();
        for (int i = 0; i <= 7; i++) fbCombo.getItems().add(i);
        fbCombo.setValue(voice.getFeedback());
        fbCombo.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: " + TEXT_COLOR + ";");
        fbCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) voice.setFeedback(newVal);
        });

        row.getChildren().addAll(nameLabel, nameField, algoLabel, algoCombo, fbLabel, fbCombo);
        return row;
    }

    private VBox buildOperatorColumn(int displayIndex) {
        int smpsOp = FmVoice.displayToSmps(displayIndex);
        boolean carrier = voice.isCarrier(smpsOp);

        VBox column = new VBox(4);
        column.setPadding(new Insets(8));
        column.setMinWidth(150);
        column.setPrefWidth(155);
        column.setStyle(columnStyle(carrier));

        // Header
        Label header = new Label("Op " + (displayIndex + 1));
        header.setFont(Font.font("System", FontWeight.BOLD, 14));
        header.setStyle("-fx-text-fill: " + (carrier ? CARRIER_COLOR : HEADER_COLOR) + ";");
        column.getChildren().add(header);

        // Parameter sliders
        column.getChildren().add(buildSliderRow("MUL", 0, 15, voice.getMul(smpsOp),
                v -> voice.setMul(smpsOp, v)));
        column.getChildren().add(buildSliderRow("DT", 0, 7, voice.getDt(smpsOp),
                v -> voice.setDt(smpsOp, v)));
        column.getChildren().add(buildSliderRow("TL", 0, 127, voice.getTl(smpsOp),
                v -> voice.setTl(smpsOp, v)));
        column.getChildren().add(buildSliderRow("AR", 0, 31, voice.getAr(smpsOp),
                v -> voice.setAr(smpsOp, v)));
        column.getChildren().add(buildSliderRow("D1R", 0, 31, voice.getD1r(smpsOp),
                v -> voice.setD1r(smpsOp, v)));
        column.getChildren().add(buildSliderRow("D2R", 0, 31, voice.getD2r(smpsOp),
                v -> voice.setD2r(smpsOp, v)));
        column.getChildren().add(buildSliderRow("D1L", 0, 15, voice.getD1l(smpsOp),
                v -> voice.setD1l(smpsOp, v)));
        column.getChildren().add(buildSliderRow("RR", 0, 15, voice.getRr(smpsOp),
                v -> voice.setRr(smpsOp, v)));
        column.getChildren().add(buildSliderRow("RS", 0, 3, voice.getRs(smpsOp),
                v -> voice.setRs(smpsOp, v)));

        // AM checkbox
        CheckBox amCheck = new CheckBox("AM");
        amCheck.setSelected(voice.getAm(smpsOp));
        amCheck.setStyle("-fx-text-fill: " + TEXT_COLOR + ";");
        amCheck.selectedProperty().addListener((obs, oldVal, newVal) -> voice.setAm(smpsOp, newVal));
        column.getChildren().add(amCheck);

        return column;
    }

    private HBox buildSliderRow(String label, int min, int max, int initial,
                                java.util.function.IntConsumer onChange) {
        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(String.format("%-3s", label));
        nameLabel.setFont(LABEL_FONT);
        nameLabel.setStyle("-fx-text-fill: " + TEXT_COLOR + ";");
        nameLabel.setMinWidth(30);

        Slider slider = new Slider(min, max, initial);
        slider.setMajorTickUnit(max > 15 ? 16 : (max > 3 ? 4 : 1));
        slider.setBlockIncrement(1);
        slider.setSnapToTicks(true);
        slider.setPrefWidth(80);

        Label valueLabel = new Label(String.valueOf(initial));
        valueLabel.setFont(LABEL_FONT);
        valueLabel.setStyle("-fx-text-fill: " + TEXT_COLOR + ";");
        valueLabel.setMinWidth(28);
        valueLabel.setAlignment(Pos.CENTER_RIGHT);

        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int intVal = newVal.intValue();
            valueLabel.setText(String.valueOf(intVal));
            onChange.accept(intVal);
        });

        row.getChildren().addAll(nameLabel, slider, valueLabel);
        return row;
    }

    private void updateOperatorBorders() {
        for (int display = 0; display < 4; display++) {
            int smpsOp = FmVoice.displayToSmps(display);
            boolean carrier = voice.isCarrier(smpsOp);
            operatorColumns[display].setStyle(columnStyle(carrier));

            // Update the header label color
            if (!operatorColumns[display].getChildren().isEmpty()
                    && operatorColumns[display].getChildren().get(0) instanceof Label headerLabel) {
                headerLabel.setStyle("-fx-text-fill: " + (carrier ? CARRIER_COLOR : HEADER_COLOR) + ";");
            }
        }
    }

    private String columnStyle(boolean carrier) {
        String borderColor = carrier ? CARRIER_BORDER : MODULATOR_BORDER;
        return "-fx-background-color: " + PANEL_COLOR + "; "
                + "-fx-border-color: " + borderColor + "; "
                + "-fx-border-width: 2; "
                + "-fx-border-radius: 4; "
                + "-fx-background-radius: 4;";
    }

    // --- Algorithm diagram drawing ---

    /**
     * Draws the current algorithm topology on the canvas.
     * Each operator is a rounded rectangle; carriers are cyan, modulators gray.
     * Connections are drawn as lines; output arrows on carriers.
     */
    private void drawAlgorithmDiagram() {
        GraphicsContext gc = algorithmCanvas.getGraphicsContext2D();
        double w = algorithmCanvas.getWidth();
        double h = algorithmCanvas.getHeight();

        // Clear background
        gc.setFill(Color.web(PANEL_COLOR));
        gc.fillRect(0, 0, w, h);

        int algo = voice.getAlgorithm();

        // Operator box positions are defined per-algorithm as (col, row) in a logical grid.
        // Grid: cols 0-4, rows 0-2. Each cell is ~40x36.
        double cellW = 38;
        double cellH = 32;
        double boxW = 30;
        double boxH = 24;
        double marginX = 10;
        double marginY = 10;

        // Define topologies: for each algo, (displayOp, col, row) tuples and connections.
        // Connections: (fromDisplay, toDisplay) or (fromDisplay, -1) for output.
        int[][] positions;       // [displayOp][col, row]
        int[][] connections;     // [fromDisplay, toDisplay] (-1 = output)

        switch (algo) {
            case 0 -> {
                // 1->2->3->4->OUT
                positions = new int[][]{{0, 1}, {1, 1}, {2, 1}, {3, 1}};
                connections = new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, -1}};
            }
            case 1 -> {
                // 1+2->3->4->OUT (1 and 2 both feed 3)
                positions = new int[][]{{0, 0}, {0, 2}, {1, 1}, {2, 1}};
                connections = new int[][]{{0, 2}, {1, 2}, {2, 3}, {3, -1}};
            }
            case 2 -> {
                // 1 feeds 2, 3 feeds 4, 2 and 3 both feed 4
                // Actually: 1->2+3->4->OUT (2 and 3 both feed 4, 1 feeds only 2)
                positions = new int[][]{{0, 0}, {1, 0}, {1, 2}, {2, 1}};
                connections = new int[][]{{0, 1}, {1, 3}, {2, 3}, {3, -1}};
            }
            case 3 -> {
                // 1->2->4, 3->4->OUT
                positions = new int[][]{{0, 0}, {1, 0}, {1, 2}, {2, 1}};
                connections = new int[][]{{0, 1}, {1, 3}, {2, 3}, {3, -1}};
            }
            case 4 -> {
                // 1->2->OUT, 3->4->OUT (two serial pairs)
                positions = new int[][]{{0, 0}, {1, 0}, {0, 2}, {1, 2}};
                connections = new int[][]{{0, 1}, {1, -1}, {2, 3}, {3, -1}};
            }
            case 5 -> {
                // 1 feeds 2, 3, and 4; all outputs
                positions = new int[][]{{0, 1}, {1, 0}, {1, 1}, {1, 2}};
                connections = new int[][]{{0, 1}, {0, 2}, {0, 3}, {1, -1}, {2, -1}, {3, -1}};
            }
            case 6 -> {
                // 1->2->OUT, 3->OUT, 4->OUT
                positions = new int[][]{{0, 0}, {1, 0}, {1, 1}, {1, 2}};
                connections = new int[][]{{0, 1}, {1, -1}, {2, -1}, {3, -1}};
            }
            case 7 -> {
                // All independent -> OUT
                positions = new int[][]{{0, 0}, {0, 1}, {0, 2}, {0, 3}};
                connections = new int[][]{{0, -1}, {1, -1}, {2, -1}, {3, -1}};
            }
            default -> {
                positions = new int[][]{{0, 1}, {1, 1}, {2, 1}, {3, 1}};
                connections = new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, -1}};
            }
        }

        // Calculate max columns/rows for centering
        int maxCol = 0, maxRow = 0;
        for (int[] pos : positions) {
            maxCol = Math.max(maxCol, pos[0]);
            maxRow = Math.max(maxRow, pos[1]);
        }

        // Center the diagram
        double totalW = (maxCol + 1) * cellW + boxW;
        double totalH = (maxRow + 1) * cellH;
        double offsetX = marginX + (w - 2 * marginX - totalW) / 2.0;
        double offsetY = marginY + (h - 2 * marginY - totalH) / 2.0;

        // Compute pixel centers for each operator
        double[] centerX = new double[4];
        double[] centerY = new double[4];
        for (int i = 0; i < 4; i++) {
            centerX[i] = offsetX + positions[i][0] * cellW + boxW / 2.0;
            centerY[i] = offsetY + positions[i][1] * cellH + boxH / 2.0;
        }

        // Draw connections
        gc.setLineWidth(2);
        for (int[] conn : connections) {
            int from = conn[0];
            int to = conn[1];

            double fx = centerX[from] + boxW / 2.0;
            double fy = centerY[from];

            if (to >= 0) {
                // Line to another operator
                double tx = centerX[to] - boxW / 2.0;
                double ty = centerY[to];
                gc.setStroke(Color.web("#888888"));
                gc.strokeLine(fx, fy, tx, ty);
            } else {
                // Output arrow
                int smpsOp = FmVoice.displayToSmps(from);
                if (voice.isCarrier(smpsOp)) {
                    double arrowX = Math.min(fx + cellW * 0.6, w - marginX);
                    gc.setStroke(Color.web(CARRIER_COLOR));
                    gc.strokeLine(fx, fy, arrowX, fy);
                    // Arrowhead
                    gc.strokeLine(arrowX - 5, fy - 4, arrowX, fy);
                    gc.strokeLine(arrowX - 5, fy + 4, arrowX, fy);
                }
            }
        }

        // Draw operator boxes
        gc.setFont(DIAGRAM_FONT);
        for (int display = 0; display < 4; display++) {
            int smpsOp = FmVoice.displayToSmps(display);
            boolean carrier = voice.isCarrier(smpsOp);
            double bx = centerX[display] - boxW / 2.0;
            double by = centerY[display] - boxH / 2.0;

            // Fill
            gc.setFill(carrier ? Color.web(CARRIER_COLOR, 0.2) : Color.web("#444444", 0.3));
            gc.fillRoundRect(bx, by, boxW, boxH, 6, 6);

            // Border
            gc.setStroke(carrier ? Color.web(CARRIER_COLOR) : Color.web(MODULATOR_COLOR));
            gc.setLineWidth(1.5);
            gc.strokeRoundRect(bx, by, boxW, boxH, 6, 6);

            // Label
            gc.setFill(carrier ? Color.web(CARRIER_COLOR) : Color.web(MODULATOR_COLOR));
            String label = String.valueOf(display + 1);
            gc.fillText(label, centerX[display] - 4, centerY[display] + 5);
        }
    }

    private Label createHeaderLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + HEADER_COLOR + "; -fx-font-weight: bold;");
        return label;
    }
}
