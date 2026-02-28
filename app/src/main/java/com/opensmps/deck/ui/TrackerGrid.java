package com.opensmps.deck.ui;

import com.opensmps.deck.codec.SmpsDecoder;
import com.opensmps.deck.codec.SmpsEncoder;
import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.Song;
import com.opensmps.deck.model.UndoManager;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas-based tracker grid displaying decoded SMPS track data.
 *
 * <p>Renders 10 channels side-by-side with row numbers, note names,
 * durations, instruments, and effects. Handles keyboard input for
 * note entry, navigation, selection, copy/paste, transpose, and undo.
 */
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
    private InstrumentPanel instrumentPanel;
    private Song song;
    private int currentPatternIndex = 0;
    private int cursorRow = 0;
    private int cursorChannel = 0;
    private int currentOctave = 4;
    private int currentDuration = SmpsEncoder.DEFAULT_DURATION;

    // Selection state
    private int selStartRow = -1, selStartChannel = -1;
    private int selEndRow = -1, selEndChannel = -1;
    private ClipboardData clipboard;
    private final UndoManager undoManager = new UndoManager();

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

    public void setInstrumentPanel(InstrumentPanel panel) {
        this.instrumentPanel = panel;
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

            // Selection highlight
            if (hasSelection()) {
                int minRow = getSelMinRow();
                int maxRow = getSelMaxRow();
                int minCh = getSelMinChannel();
                int maxCh = getSelMaxChannel();
                if (row >= minRow && row <= maxRow) {
                    for (int ch = minCh; ch <= maxCh; ch++) {
                        double selX = ROW_NUM_WIDTH + ch * CHANNEL_WIDTH;
                        gc.setFill(Color.web("#3344667F")); // semi-transparent blue
                        gc.fillRect(selX, y, CHANNEL_WIDTH, ROW_HEIGHT);
                    }
                }
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
        // Selection: Shift+Arrow
        if (e.isShiftDown()) {
            switch (e.getCode()) {
                case UP -> extendSelection(-1, 0);
                case DOWN -> extendSelection(1, 0);
                case LEFT -> extendSelection(0, -1);
                case RIGHT -> extendSelection(0, 1);
                case EQUALS, ADD -> transposeSelection(12); // Shift+= (Shift++) = octave up
                case MINUS, SUBTRACT -> transposeSelection(-12); // Shift+- = octave down
                case TAB -> {
                    cursorChannel = (cursorChannel - 1 + Pattern.CHANNEL_COUNT) % Pattern.CHANNEL_COUNT;
                    refreshDisplay();
                }
                default -> {}
            }
            e.consume();
            return;
        }

        // Ctrl shortcuts
        if (e.isControlDown()) {
            switch (e.getCode()) {
                case C -> copySelection();
                case V -> pasteAtCursor();
                case X -> {
                    if (hasSelection()) {
                        copySelection();
                        deleteSelection();
                    }
                }
                case A -> selectAll();
                case Z -> { if (undoManager.undo()) refreshDisplay(); }
                case Y -> { if (undoManager.redo()) refreshDisplay(); }
                default -> {}
            }
            e.consume();
            return;
        }

        switch (e.getCode()) {
            case UP -> { clearSelection(); moveCursor(-1, 0); }
            case DOWN -> { clearSelection(); moveCursor(1, 0); }
            case LEFT -> { clearSelection(); moveCursor(0, -1); }
            case RIGHT -> { clearSelection(); moveCursor(0, 1); }
            case DELETE -> deleteAtCursor();
            case INSERT -> insertEmptyRow();
            case BACK_SPACE -> deleteAndPullUp();
            case PAGE_UP -> { currentOctave = Math.min(currentOctave + 1, 7); }
            case PAGE_DOWN -> { currentOctave = Math.max(currentOctave - 1, 0); }
            case PERIOD -> insertRest();
            case EQUALS, ADD -> transposeSelection(1); // + = semitone up
            case MINUS, SUBTRACT -> transposeSelection(-1); // - = semitone down
            case TAB -> {
                cursorChannel = (cursorChannel + 1) % Pattern.CHANNEL_COUNT;
                refreshDisplay();
            }
            case F1 -> currentOctave = 1;
            case F2 -> currentOctave = 2;
            case F3 -> currentOctave = 3;
            case F4 -> currentOctave = 4;
            case F5 -> currentOctave = 5;
            case F6 -> currentOctave = 6;
            case F7 -> currentOctave = 7;
            case ESCAPE -> clearSelection();
            default -> {
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
        // Use cached decoded channels if available, avoiding re-decode on every call
        if (!decodedChannels.isEmpty()) {
            for (List<SmpsDecoder.TrackerRow> rows : decodedChannels) {
                max = Math.max(max, rows.size());
            }
        } else {
            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(pattern.getTrackData(ch));
                max = Math.max(max, rows.size());
            }
        }
        return Math.max(max, 1);
    }

    private byte[] prependInstrumentIfSelected(byte[] noteBytes) {
        if (instrumentPanel == null) return noteBytes;

        byte[] instrBytes = null;
        if (cursorChannel <= 5) {
            // FM channel: use voice index
            int voiceIdx = instrumentPanel.getCurrentVoiceIndex();
            if (voiceIdx >= 0) {
                instrBytes = SmpsEncoder.encodeVoiceChange(voiceIdx);
            }
        } else {
            // PSG channel: use envelope index
            int envIdx = instrumentPanel.getCurrentEnvelopeIndex();
            if (envIdx >= 0) {
                instrBytes = SmpsEncoder.encodePsgEnvelope(envIdx);
            }
        }

        if (instrBytes == null) return noteBytes;

        byte[] combined = new byte[instrBytes.length + noteBytes.length];
        System.arraycopy(instrBytes, 0, combined, 0, instrBytes.length);
        System.arraycopy(noteBytes, 0, combined, instrBytes.length, noteBytes.length);
        return combined;
    }

    private void insertNote(int noteValue) {
        if (song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        byte[] trackData = pattern.getTrackData(cursorChannel);
        byte[] noteBytes = SmpsEncoder.encodeNote(noteValue, currentDuration);
        byte[] insertBytes = prependInstrumentIfSelected(noteBytes);
        byte[] newData = SmpsEncoder.insertAtRow(trackData, cursorRow, insertBytes);
        undoManager.recordEdit(pattern, cursorChannel);
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
        undoManager.recordEdit(pattern, cursorChannel);
        pattern.setTrackData(cursorChannel, newData);
        cursorRow++;
        refreshDisplay();
    }

    private void deleteAtCursor() {
        if (song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        byte[] trackData = pattern.getTrackData(cursorChannel);
        byte[] newData = SmpsEncoder.deleteRow(trackData, cursorRow);
        undoManager.recordEdit(pattern, cursorChannel);
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

    private void extendSelection(int rowDelta, int channelDelta) {
        if (selStartRow < 0) {
            // Start new selection from cursor
            selStartRow = cursorRow;
            selStartChannel = cursorChannel;
            selEndRow = cursorRow;
            selEndChannel = cursorChannel;
        }

        selEndRow = Math.max(0, Math.min(selEndRow + rowDelta, getMaxRowCount() - 1));
        selEndChannel = Math.max(0, Math.min(selEndChannel + channelDelta, Pattern.CHANNEL_COUNT - 1));
        cursorRow = selEndRow;
        cursorChannel = selEndChannel;
        refreshDisplay();
    }

    private void clearSelection() {
        selStartRow = -1;
        selStartChannel = -1;
        selEndRow = -1;
        selEndChannel = -1;
    }

    private boolean hasSelection() {
        return selStartRow >= 0;
    }

    private int getSelMinRow() { return Math.min(selStartRow, selEndRow); }
    private int getSelMaxRow() { return Math.max(selStartRow, selEndRow); }
    private int getSelMinChannel() { return Math.min(selStartChannel, selEndChannel); }
    private int getSelMaxChannel() { return Math.max(selStartChannel, selEndChannel); }

    private void selectAll() {
        selStartRow = 0;
        selStartChannel = 0;
        selEndRow = getMaxRowCount() - 1;
        selEndChannel = Pattern.CHANNEL_COUNT - 1;
        refreshDisplay();
    }

    private void copySelection() {
        if (!hasSelection() || song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);

        int minCh = getSelMinChannel();
        int maxCh = getSelMaxChannel();
        int minRow = getSelMinRow();
        int maxRow = getSelMaxRow();
        int rowCount = maxRow - minRow + 1;
        int chCount = maxCh - minCh + 1;

        byte[][] channelData = new byte[chCount][];
        for (int ch = 0; ch < chCount; ch++) {
            byte[] trackData = pattern.getTrackData(minCh + ch);
            channelData[ch] = SmpsEncoder.extractRowRange(trackData, minRow, rowCount);
        }

        clipboard = new ClipboardData(channelData, rowCount);
    }

    private void deleteSelection() {
        if (!hasSelection() || song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);

        int minCh = getSelMinChannel();
        int maxCh = getSelMaxChannel();
        int minRow = getSelMinRow();
        int maxRow = getSelMaxRow();

        // Record undo for all affected channels
        int chCount = maxCh - minCh + 1;
        int[] channels = new int[chCount];
        for (int i = 0; i < chCount; i++) {
            channels[i] = minCh + i;
        }
        undoManager.recordMultiEdit(pattern, channels);

        // Delete rows from bottom to top (since rows shift after each delete)
        for (int ch = minCh; ch <= maxCh; ch++) {
            byte[] trackData = pattern.getTrackData(ch);
            for (int row = maxRow; row >= minRow; row--) {
                trackData = SmpsEncoder.deleteRow(trackData, row);
            }
            pattern.setTrackData(ch, trackData);
        }

        clearSelection();
        refreshDisplay();
    }

    private void pasteAtCursor() {
        if (clipboard == null || song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);

        // Collect affected channel indices for atomic undo
        int count = 0;
        for (int ch = 0; ch < clipboard.getChannelCount(); ch++) {
            if (cursorChannel + ch < Pattern.CHANNEL_COUNT) count++;
        }
        int[] affectedChannels = new int[count];
        for (int i = 0; i < count; i++) {
            affectedChannels[i] = cursorChannel + i;
        }

        // Record undo atomically for all affected channels BEFORE any mutations
        undoManager.recordMultiEdit(pattern, affectedChannels);

        for (int ch = 0; ch < clipboard.getChannelCount(); ch++) {
            int targetChannel = cursorChannel + ch;
            if (targetChannel >= Pattern.CHANNEL_COUNT) break;

            byte[] trackData = pattern.getTrackData(targetChannel);
            byte[] pasteData = clipboard.getChannelData(ch);

            // Insert pasted bytes at cursor row
            byte[] newData = SmpsEncoder.insertAtRow(trackData, cursorRow, pasteData);
            pattern.setTrackData(targetChannel, newData);
        }
        refreshDisplay();
    }

    private void transposeSelection(int semitones) {
        if (song == null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);

        if (hasSelection()) {
            int minCh = getSelMinChannel();
            int maxCh = getSelMaxChannel();
            int minRow = getSelMinRow();
            int rowCount = getSelMaxRow() - minRow + 1;

            for (int ch = minCh; ch <= maxCh; ch++) {
                undoManager.recordEdit(pattern, ch);
            }
            for (int ch = minCh; ch <= maxCh; ch++) {
                byte[] trackData = pattern.getTrackData(ch);
                byte[] transposed = SmpsEncoder.transposeTrackRange(trackData, minRow, rowCount, semitones);
                pattern.setTrackData(ch, transposed);
            }
        } else {
            // Transpose single cell at cursor
            undoManager.recordEdit(pattern, cursorChannel);
            byte[] trackData = pattern.getTrackData(cursorChannel);
            byte[] transposed = SmpsEncoder.transposeTrackRange(trackData, cursorRow, 1, semitones);
            pattern.setTrackData(cursorChannel, transposed);
        }
        refreshDisplay();
    }

    public UndoManager getUndoManager() { return undoManager; }
}
