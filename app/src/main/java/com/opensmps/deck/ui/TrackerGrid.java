package com.opensmps.deck.ui;

import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.Song;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.List;

public class TrackerGrid extends ScrollPane {

    private static final int ROW_HEIGHT = 20;
    private static final int ROW_NUM_WIDTH = 40;
    private static final int CHANNEL_WIDTH = 140;
    private static final int HEADER_HEIGHT = 24;

    private static final String[] CHANNEL_NAMES = {
        "FM1", "FM2", "FM3", "FM4", "FM5", "DAC",
        "PSG1", "PSG2", "PSG3", "Noise"
    };

    private static final Font MONO_FONT = Font.font("Monospaced", 13);
    private static final Font HEADER_FONT = Font.font("Monospaced", 12);

    private final Canvas canvas;
    private Song song;
    private int currentPatternIndex = 0;
    private int cursorRow = 0;
    private int cursorChannel = 0;

    // Cached decoded rows per channel
    private final List<List<SmpsDecoder.TrackerRow>> decodedChannels = new ArrayList<>();

    public TrackerGrid() {
        this.canvas = new Canvas();
        setContent(canvas);
        setFitToWidth(true);
        setPannable(true);
        setStyle("-fx-background: #1a1a2e;");
    }

    public void setSong(Song song) {
        this.song = song;
        this.currentPatternIndex = 0;
        refreshDisplay();
    }

    public void setCurrentPatternIndex(int index) {
        this.currentPatternIndex = index;
        refreshDisplay();
    }

    public int getCurrentPatternIndex() {
        return currentPatternIndex;
    }

    public void refreshDisplay() {
        if (song == null || song.getPatterns().isEmpty()) return;

        Pattern pattern = song.getPatterns().get(currentPatternIndex);

        // Decode all channels
        decodedChannels.clear();
        int maxRows = 0;
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(pattern.getTrackData(ch));
            decodedChannels.add(rows);
            maxRows = Math.max(maxRows, rows.size());
        }

        // Use pattern's row count as minimum
        maxRows = Math.max(maxRows, pattern.getRows());

        // Size the canvas
        double totalWidth = ROW_NUM_WIDTH + CHANNEL_WIDTH * Pattern.CHANNEL_COUNT;
        double totalHeight = HEADER_HEIGHT + ROW_HEIGHT * maxRows;
        canvas.setWidth(totalWidth);
        canvas.setHeight(totalHeight);

        render(maxRows);
    }

    private void render(int rowCount) {
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Draw header
        gc.setFill(Color.web("#2a2a2a"));
        gc.fillRect(0, 0, canvas.getWidth(), HEADER_HEIGHT);
        gc.setFont(HEADER_FONT);
        gc.setFill(Color.web("#88aacc"));
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            double x = ROW_NUM_WIDTH + ch * CHANNEL_WIDTH + 4;
            gc.fillText(CHANNEL_NAMES[ch], x, HEADER_HEIGHT - 6);
        }

        // Draw rows
        gc.setFont(MONO_FONT);
        for (int row = 0; row < rowCount; row++) {
            double y = HEADER_HEIGHT + row * ROW_HEIGHT;

            // Row number
            boolean isCurrentRow = (row == cursorRow);
            if (isCurrentRow) {
                gc.setFill(Color.web("#2a2a4e"));
                gc.fillRect(0, y, canvas.getWidth(), ROW_HEIGHT);
            }

            // Alternating row background for readability
            if (!isCurrentRow && row % 4 == 0) {
                gc.setFill(Color.web("#1e1e34"));
                gc.fillRect(0, y, canvas.getWidth(), ROW_HEIGHT);
            }

            // Row number text
            gc.setFill(Color.web("#666666"));
            gc.fillText(String.format("%02X", row), 4, y + ROW_HEIGHT - 5);

            // Channel data
            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                List<SmpsDecoder.TrackerRow> channelRows = decodedChannels.get(ch);
                double x = ROW_NUM_WIDTH + ch * CHANNEL_WIDTH + 4;

                if (row < channelRows.size()) {
                    SmpsDecoder.TrackerRow tr = channelRows.get(row);
                    renderCell(gc, tr, x, y, ch == cursorChannel && row == cursorRow);
                } else {
                    // Empty cell
                    gc.setFill(Color.web("#333333"));
                    gc.fillText("... .. ....", x, y + ROW_HEIGHT - 5);
                }
            }

            // Draw channel separator lines
            gc.setStroke(Color.web("#333344"));
            gc.setLineWidth(1);
            for (int ch = 0; ch <= Pattern.CHANNEL_COUNT; ch++) {
                double x = ROW_NUM_WIDTH + ch * CHANNEL_WIDTH;
                gc.strokeLine(x, HEADER_HEIGHT, x, HEADER_HEIGHT + rowCount * ROW_HEIGHT);
            }
        }
    }

    private void renderCell(GraphicsContext gc, SmpsDecoder.TrackerRow row, double x, double y, boolean isCursor) {
        double textY = y + ROW_HEIGHT - 5;

        // Note
        if (row.note() != null && !row.note().isEmpty()) {
            if (row.note().equals("---")) {
                gc.setFill(Color.web("#666688"));
            } else if (row.note().equals("===")) {
                gc.setFill(Color.web("#88aa88"));
            } else {
                gc.setFill(Color.web("#ccddee"));
            }
            gc.fillText(row.note(), x, textY);
        }

        // Instrument
        if (row.instrument() != null && !row.instrument().isEmpty()) {
            gc.setFill(Color.web("#aacc88"));
            gc.fillText(row.instrument(), x + 40, textY);
        }

        // Effect
        if (row.effect() != null && !row.effect().isEmpty()) {
            gc.setFill(Color.web("#cc8866"));
            // Truncate if too long
            String eff = row.effect().length() > 14 ? row.effect().substring(0, 14) : row.effect();
            gc.fillText(eff, x + 65, textY);
        }
    }

    public int getCursorRow() { return cursorRow; }
    public int getCursorChannel() { return cursorChannel; }

    public void setCursorRow(int row) {
        this.cursorRow = row;
        refreshDisplay();
    }

    public void setCursorChannel(int channel) {
        this.cursorChannel = channel;
        refreshDisplay();
    }
}
