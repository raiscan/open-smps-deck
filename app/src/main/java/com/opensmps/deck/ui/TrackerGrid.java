package com.opensmps.deck.ui;

import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.Song;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
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
    private int currentOctave = 4;
    private int currentDuration = SmpsEncoder.DEFAULT_DURATION;

    // Cached decoded rows per channel
    private final List<List<SmpsDecoder.TrackerRow>> decodedChannels = new ArrayList<>();

    public TrackerGrid() {
        this.canvas = new Canvas();
        setContent(canvas);
        setFitToWidth(true);
        setPannable(true);
        setStyle("-fx-background: #1a1a2e;");
        setupKeyboardHandling();
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

    public int getCurrentOctave() { return currentOctave; }
    public void setCurrentOctave(int octave) { this.currentOctave = octave; }
    public int getCurrentDuration() { return currentDuration; }
    public void setCurrentDuration(int duration) { this.currentDuration = duration; }

    private void setupKeyboardHandling() {
        canvas.setFocusTraversable(true);

        // Request focus when clicked
        canvas.setOnMouseClicked(e -> canvas.requestFocus());

        canvas.setOnKeyPressed(this::handleKeyPressed);
    }

    private void handleKeyPressed(KeyEvent e) {
        switch (e.getCode()) {
            case UP -> moveCursor(-1, 0);
            case DOWN -> moveCursor(1, 0);
            case LEFT -> moveCursor(0, -1);
            case RIGHT -> moveCursor(0, 1);
            case DELETE -> deleteAtCursor();
            case INSERT -> insertEmptyRow();
            case BACK_SPACE -> deleteAndPullUp();
            case PAGE_UP -> { currentOctave = Math.min(currentOctave + 1, 7); }
            case PAGE_DOWN -> { currentOctave = Math.max(currentOctave - 1, 0); }
            case PERIOD -> insertRest();
            default -> {
                // Check for note keys
                String text = e.getText();
                if (text != null && !text.isEmpty()) {
                    char key = text.charAt(0);
                    int noteValue = SmpsEncoder.encodeNoteFromKey(key, currentOctave);
                    if (noteValue > 0) {
                        insertNote(noteValue);
                    }
                }
            }
        }
    }

    private void moveCursor(int rowDelta, int channelDelta) {
        int maxRow = getMaxRowCount() - 1;
        cursorRow = Math.max(0, Math.min(cursorRow + rowDelta, maxRow));
        cursorChannel = Math.max(0, Math.min(cursorChannel + channelDelta, Pattern.CHANNEL_COUNT - 1));
        refreshDisplay();
    }

    private int getMaxRowCount() {
        if (song == null || song.getPatterns().isEmpty()) return 1;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        int max = pattern.getRows();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(pattern.getTrackData(ch));
            max = Math.max(max, rows.size());
        }
        return Math.max(max, 1);
    }

    private void insertNote(int noteValue) {
        if (song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        byte[] trackData = pattern.getTrackData(cursorChannel);
        byte[] noteBytes = SmpsEncoder.encodeNote(noteValue, currentDuration);
        byte[] newData = SmpsEncoder.insertAtRow(trackData, cursorRow, noteBytes);
        pattern.setTrackData(cursorChannel, newData);
        cursorRow++;
        refreshDisplay();
    }

    private void insertRest() {
        if (song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        byte[] trackData = pattern.getTrackData(cursorChannel);
        byte[] restBytes = SmpsEncoder.encodeRest(currentDuration);
        byte[] newData = SmpsEncoder.insertAtRow(trackData, cursorRow, restBytes);
        pattern.setTrackData(cursorChannel, newData);
        cursorRow++;
        refreshDisplay();
    }

    private void deleteAtCursor() {
        if (song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        byte[] trackData = pattern.getTrackData(cursorChannel);
        byte[] newData = SmpsEncoder.deleteRow(trackData, cursorRow);
        pattern.setTrackData(cursorChannel, newData);
        refreshDisplay();
    }

    private void insertEmptyRow() {
        // Insert a rest at the cursor position (pushing down)
        insertRest();
    }

    private void deleteAndPullUp() {
        // Same as delete but cursor doesn't move
        deleteAtCursor();
    }
}
