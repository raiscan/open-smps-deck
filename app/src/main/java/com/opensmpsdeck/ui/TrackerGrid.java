package com.opensmpsdeck.ui;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.codec.EffectMnemonics;
import com.opensmpsdeck.codec.PasteResolver;
import com.opensmpsdeck.codec.SmpsDecoder;
import com.opensmpsdeck.codec.SmpsEncoder;
import com.opensmpsdeck.codec.UnrolledTimeline;
import com.opensmpsdeck.model.ClipboardData;
import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.Pattern;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
import com.opensmpsdeck.model.UndoManager;
import com.opensmps.smps.SmpsCoordFlags;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Canvas-based tracker grid displaying decoded SMPS track data.
 *
 * <p>Renders 10 channels side-by-side with row numbers, note names,
 * durations, instruments, and effects. Handles keyboard input for
 * note entry, navigation, selection, copy/paste, transpose, and undo.
 */
public class TrackerGrid extends ScrollPane {

    /** Whether the grid shows a single phrase or the full unrolled timeline. */
    public enum ViewMode { PHRASE, UNROLLED }

    private static final int ROW_HEIGHT = 20;
    private static final int ROW_NUM_WIDTH = 40;
    private static final int CHANNEL_WIDTH = 170;
    private static final int HEADER_HEIGHT = 24;

    private static final String[] CHANNEL_NAMES = {
        "FM1", "FM2", "FM3", "FM4", "FM5", "DAC",
        "PSG1", "PSG2", "PSG3", "Noise"
    };

    private static final Font MONO_FONT = Font.font("Monospaced", 13);
    private static final Font HEADER_FONT = Font.font("Monospaced", 12);

    /** Sub-column positions within a channel cell. */
    static final int COL_NOTE = 0;
    static final int COL_DURATION = 1;
    static final int COL_INSTRUMENT = 2;
    static final int COL_EFFECT = 3;
    private static final int COL_COUNT = 4;

    /** Channel index for the DAC channel (FM6 used as DAC on Mega Drive). */
    private static final int DAC_CHANNEL = 5;

    /**
     * Maps keyboard characters to sequential DAC sample indices.
     * Lower row: Z=0, S=1, X=2, D=3, C=4, V=5, G=6, B=7, H=8, N=9, J=10, M=11.
     * Upper row: Q=12, W=13, E=14, R=15, T=16, Y=17, U=18.
     */
    private static final int[] DAC_KEY_MAP = new int[128];
    static {
        Arrays.fill(DAC_KEY_MAP, -1);
        DAC_KEY_MAP['Z'] = 0;
        DAC_KEY_MAP['S'] = 1;
        DAC_KEY_MAP['X'] = 2;
        DAC_KEY_MAP['D'] = 3;
        DAC_KEY_MAP['C'] = 4;
        DAC_KEY_MAP['V'] = 5;
        DAC_KEY_MAP['G'] = 6;
        DAC_KEY_MAP['B'] = 7;
        DAC_KEY_MAP['H'] = 8;
        DAC_KEY_MAP['N'] = 9;
        DAC_KEY_MAP['J'] = 10;
        DAC_KEY_MAP['M'] = 11;
        DAC_KEY_MAP['Q'] = 12;
        DAC_KEY_MAP['W'] = 13;
        DAC_KEY_MAP['E'] = 14;
        DAC_KEY_MAP['R'] = 15;
        DAC_KEY_MAP['T'] = 16;
        DAC_KEY_MAP['Y'] = 17;
        DAC_KEY_MAP['U'] = 18;
    }

    private final Canvas canvas;
    private InstrumentPanel instrumentPanel;
    private Song song;
    private int currentPatternIndex = 0;
    private int cursorRow = 0;
    private int cursorChannel = 0;
    private int cursorColumn = COL_NOTE;
    private int playbackRow = -1;
    private int playbackOrderRow = -1;
    private final int[] playbackRowsByChannel = new int[Pattern.CHANNEL_COUNT];
    private boolean autoScrollActive = true;
    private int currentOctave = 4;
    private int currentDuration = SmpsEncoder.DEFAULT_DURATION;

    /** First hex digit waiting for second, or -1 if not in hex entry mode. */
    private int pendingHexDigit = -1;

    // Selection state
    private int selStartRow = -1, selStartChannel = -1;
    private int selEndRow = -1, selEndChannel = -1;
    private int dragAnchorRow = -1, dragAnchorChannel = -1;
    private ClipboardData clipboard;
    private final UndoManager undoManager = new UndoManager();
    private Runnable onTogglePlayback;
    private Runnable onStopPlayback;
    private Runnable onPlayFromCursor;
    private Runnable onDirty;

    // Mute/solo state
    private final boolean[] channelMuted = new boolean[Pattern.CHANNEL_COUNT];
    private int soloChannel = -1;
    private PlaybackEngine playbackEngine;

    // Phrase editing mode (hierarchical arrangement)
    private Phrase activePhrase;
    private int phraseChannelIndex = -1;

    // Unrolled timeline mode
    private ViewMode viewMode = ViewMode.PHRASE;
    private UnrolledTimeline unrolledTimeline;
    private int zoomLevel = 1;

    // Cached decoded rows per channel
    private final List<List<SmpsDecoder.TrackerRow>> decodedChannels = new ArrayList<>();
    private boolean decodedCacheDirty = true;
    private Song decodedCacheSong;
    private int decodedCachePatternIndex = -1;
    private int decodedCacheRowCount = 1;

    public TrackerGrid() {
        this.canvas = new Canvas();
        setContent(canvas);
        setFitToWidth(true);
        setPannable(false);
        setStyle("-fx-background: #1a1a2e;");
        Arrays.fill(playbackRowsByChannel, -1);
        setupKeyboardHandling();
    }

    public void setInstrumentPanel(InstrumentPanel panel) {
        this.instrumentPanel = panel;
    }

    public void setOnTogglePlayback(Runnable callback) { this.onTogglePlayback = callback; }
    public void setOnStopPlayback(Runnable callback) { this.onStopPlayback = callback; }
    public void setOnPlayFromCursor(Runnable callback) { this.onPlayFromCursor = callback; }
    public void setOnDirty(Runnable callback) { this.onDirty = callback; }
    public void setPlaybackEngine(PlaybackEngine engine) { this.playbackEngine = engine; }
    private void markDirty() { if (onDirty != null) onDirty.run(); }

    public void setSong(Song song) {
        this.song = song;
        this.currentPatternIndex = 0;
        Arrays.fill(channelMuted, false);
        soloChannel = -1;
        applyMuteState();
        invalidateDecodedCache();
        refreshDisplay();
    }

    /**
     * Switch to phrase editing mode: display a single phrase's data.
     * In this mode, the grid shows one channel and edits write to the phrase.
     */
    public void setPhrase(Phrase phrase, int channelIndex) {
        this.activePhrase = phrase;
        this.phraseChannelIndex = channelIndex;
        this.cursorRow = 0;
        this.cursorChannel = 0;
        this.cursorColumn = COL_NOTE;
        clearSelection();
        invalidateDecodedCache();
        refreshDisplay();
    }

    /** Return to normal multi-channel pattern mode. */
    public void clearPhrase() {
        this.activePhrase = null;
        this.phraseChannelIndex = -1;
        invalidateDecodedCache();
        refreshDisplay();
    }

    public boolean isInPhraseMode() {
        return activePhrase != null;
    }

    public ViewMode getViewMode() { return viewMode; }
    public boolean isUnrolledMode() { return viewMode == ViewMode.UNROLLED; }

    public void setUnrolledTimeline(UnrolledTimeline timeline) {
        this.unrolledTimeline = timeline;
        this.viewMode = ViewMode.UNROLLED;
        this.zoomLevel = 1;
        refreshDisplay();
    }

    public void exitUnrolledMode() {
        this.viewMode = ViewMode.PHRASE;
        this.unrolledTimeline = null;
        refreshDisplay();
    }

    public int getZoomLevel() { return zoomLevel; }

    public void setZoomLevel(int zoom) {
        this.zoomLevel = zoom;
        refreshDisplay();
    }

    public void setCurrentPatternIndex(int index) {
        this.currentPatternIndex = index;
        invalidateDecodedCache();
        refreshDisplay();
    }

    public int getCurrentPatternIndex() {
        return currentPatternIndex;
    }

    public void refreshDisplay() {
        if (activePhrase != null) {
            int maxRows = Math.max(1, ensureDecodedCache());
            double totalWidth = ROW_NUM_WIDTH + CHANNEL_WIDTH;
            double totalHeight = HEADER_HEIGHT + ROW_HEIGHT * maxRows;
            canvas.setWidth(totalWidth);
            canvas.setHeight(totalHeight);
            render(maxRows);
            return;
        }

        if (song == null || song.getPatterns().isEmpty()) return;
        if (currentPatternIndex < 0 || currentPatternIndex >= song.getPatterns().size()) return;

        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        int maxRows = Math.max(pattern.getRows(), ensureDecodedCache());

        // Size the canvas
        double totalWidth = ROW_NUM_WIDTH + CHANNEL_WIDTH * Pattern.CHANNEL_COUNT;
        double totalHeight = HEADER_HEIGHT + ROW_HEIGHT * maxRows;
        canvas.setWidth(totalWidth);
        canvas.setHeight(totalHeight);

        render(maxRows);
    }

    private void invalidateDecodedCache() {
        decodedCacheDirty = true;
    }

    private int ensureDecodedCache() {
        // Phrase mode: decode single channel from phrase data
        if (activePhrase != null) {
            if (!decodedCacheDirty && decodedChannels.size() == 1) {
                return decodedCacheRowCount;
            }
            decodedChannels.clear();
            List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(activePhrase.getDataDirect());
            decodedChannels.add(rows);
            decodedCacheRowCount = Math.max(rows.size(), 1);
            decodedCacheDirty = false;
            return decodedCacheRowCount;
        }

        if (song == null || song.getPatterns().isEmpty()) {
            decodedChannels.clear();
            decodedCacheRowCount = 1;
            decodedCacheSong = song;
            decodedCachePatternIndex = currentPatternIndex;
            decodedCacheDirty = false;
            return decodedCacheRowCount;
        }
        if (currentPatternIndex < 0 || currentPatternIndex >= song.getPatterns().size()) {
            decodedChannels.clear();
            decodedCacheRowCount = 1;
            decodedCacheSong = song;
            decodedCachePatternIndex = currentPatternIndex;
            decodedCacheDirty = false;
            return decodedCacheRowCount;
        }

        if (!decodedCacheDirty
                && decodedCacheSong == song
                && decodedCachePatternIndex == currentPatternIndex
                && decodedChannels.size() == Pattern.CHANNEL_COUNT) {
            return decodedCacheRowCount;
        }

        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        decodedChannels.clear();
        int maxRows = 0;
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(pattern.getTrackDataDirect(ch));
            decodedChannels.add(rows);
            maxRows = Math.max(maxRows, rows.size());
        }

        decodedCacheRowCount = Math.max(maxRows, pattern.getRows());
        decodedCacheSong = song;
        decodedCachePatternIndex = currentPatternIndex;
        decodedCacheDirty = false;
        return decodedCacheRowCount;
    }

    private void render(int rowCount) {
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Draw header
        int channelCount = getVisibleChannelCount();
        gc.setFill(Color.web("#2a2a2a"));
        gc.fillRect(0, 0, canvas.getWidth(), HEADER_HEIGHT);
        gc.setFont(HEADER_FONT);
        for (int ch = 0; ch < channelCount; ch++) {
            double x = ROW_NUM_WIDTH + ch * CHANNEL_WIDTH + 4;
            String headerName;
            if (activePhrase != null) {
                headerName = activePhrase.getName();
            } else {
                headerName = CHANNEL_NAMES[ch];
            }
            boolean effectivelyMuted = soloChannel >= 0 ? (ch != soloChannel) : channelMuted[ch];
            if (soloChannel == ch) {
                gc.setFill(Color.web("#ffcc00")); // gold for solo
            } else if (effectivelyMuted) {
                gc.setFill(Color.web("#555555")); // grey for muted
            } else {
                gc.setFill(Color.web("#88aacc")); // normal
            }
            gc.fillText(headerName, x, HEADER_HEIGHT - 6);
            if (effectivelyMuted) {
                gc.setStroke(Color.web("#555555"));
                gc.setLineWidth(1);
                double textY = HEADER_HEIGHT - 6;
                gc.strokeLine(x, textY - 4, x + CHANNEL_WIDTH - 8, textY - 4);
            }
        }

        // Draw rows
        gc.setFont(MONO_FONT);
        for (int row = 0; row < rowCount; row++) {
            double y = HEADER_HEIGHT + row * ROW_HEIGHT;

            // Playback cursor highlight (teal bar, semi-transparent)
            if (row == playbackRow && playbackRow >= 0) {
                gc.setFill(Color.rgb(0, 180, 180, 0.25));
                gc.fillRect(0, y, canvas.getWidth(), ROW_HEIGHT);
            }

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
            for (int ch = 0; ch < channelCount; ch++) {
                List<SmpsDecoder.TrackerRow> channelRows = decodedChannels.get(ch);
                double x = ROW_NUM_WIDTH + ch * CHANNEL_WIDTH + 4;

                // In phrase mode, use the phrase's channel index for rendering context
                int renderCh = activePhrase != null ? phraseChannelIndex : ch;

                if (row < channelRows.size()) {
                    SmpsDecoder.TrackerRow tr = channelRows.get(row);
                    renderCell(gc, tr, x, y, renderCh, ch == cursorChannel && row == cursorRow);
                } else {
                    // Empty cell
                    gc.setFill(Color.web("#333333"));
                    gc.fillText("... .. .. ....", x, y + ROW_HEIGHT - 5);
                }

                if (!isInPhraseMode() && row == playbackRowsByChannel[ch] && playbackRowsByChannel[ch] >= 0) {
                    gc.setStroke(Color.rgb(0, 220, 220, 0.85));
                    gc.setLineWidth(1.5);
                    gc.strokeRect(ROW_NUM_WIDTH + ch * CHANNEL_WIDTH + 1, y + 1, CHANNEL_WIDTH - 2, ROW_HEIGHT - 2);
                }
            }

            // Draw channel separator lines
            gc.setStroke(Color.web("#333344"));
            gc.setLineWidth(1);
            for (int ch = 0; ch <= channelCount; ch++) {
                double x = ROW_NUM_WIDTH + ch * CHANNEL_WIDTH;
                gc.strokeLine(x, HEADER_HEIGHT, x, HEADER_HEIGHT + rowCount * ROW_HEIGHT);
            }
        }
    }

    private void renderCell(GraphicsContext gc, SmpsDecoder.TrackerRow row, double x, double y, int channel, boolean isCursor) {
        double textY = y + ROW_HEIGHT - 5;

        // Draw cursor column underline indicator when this cell is focused
        if (isCursor) {
            gc.setStroke(Color.web("#88ccff"));
            gc.setLineWidth(2);
            double underlineY = y + ROW_HEIGHT - 1;
            switch (cursorColumn) {
                case COL_NOTE -> gc.strokeLine(x, underlineY, x + 36, underlineY);
                case COL_DURATION -> gc.strokeLine(x + 38, underlineY, x + 52, underlineY);
                case COL_INSTRUMENT -> gc.strokeLine(x + 55, underlineY, x + 72, underlineY);
                case COL_EFFECT -> gc.strokeLine(x + 75, underlineY, x + CHANNEL_WIDTH - 8, underlineY);
            }
            // Show pending hex digit indicator
            if (pendingHexDigit >= 0 && cursorColumn == COL_INSTRUMENT) {
                gc.setFill(Color.web("#ffcc44"));
                gc.fillText(String.format("%X_", pendingHexDigit), x + 55, textY);
            }
        }

        // Note — DAC channel shows sample name abbreviations instead of musical notes
        if (row.note() != null && !row.note().isEmpty()) {
            String displayNote = row.note();
            boolean isDacNote = false;

            if (channel == DAC_CHANNEL && song != null && !song.getDacSamples().isEmpty()) {
                // Try to format as DAC sample name
                if (!displayNote.equals("---") && !displayNote.equals("===") && !displayNote.equals("???")) {
                    // Reverse-lookup: the decoded note string came from a 0x81+ byte.
                    // Re-derive the note byte from the display string to get the DAC index.
                    String dacDisplay = formatDacNoteFromDisplay(displayNote);
                    if (dacDisplay != null) {
                        displayNote = dacDisplay;
                        isDacNote = true;
                    }
                }
            }

            if (displayNote.equals("---")) {
                gc.setFill(Color.web("#666688"));
            } else if (displayNote.equals("===")) {
                gc.setFill(Color.web("#88aa88"));
            } else if (isDacNote) {
                gc.setFill(Color.web("#dd99ff")); // purple tint for DAC samples
            } else {
                gc.setFill(Color.web("#ccddee"));
            }
            gc.fillText(displayNote, x, textY);
        }

        // Duration
        if (row.duration() > 0) {
            gc.setFill(Color.web("#8899bb"));
            gc.fillText(String.format("%02X", row.duration()), x + 38, textY);
        }

        // Instrument (skip if pending hex digit is being shown at this cursor position)
        if (!(isCursor && pendingHexDigit >= 0 && cursorColumn == COL_INSTRUMENT)) {
            if (row.instrument() != null && !row.instrument().isEmpty()) {
                gc.setFill(Color.web("#aacc88"));
                gc.fillText(row.instrument(), x + 55, textY);
            }
        }

        // Effect
        if (row.effect() != null && !row.effect().isEmpty()) {
            gc.setFill(Color.web("#cc8866"));
            double effectX = x + 75;
            double effectWidth = CHANNEL_WIDTH - 83; // 75px offset + 8px right margin
            String summary = summarizeEffectText(row.effect());
            gc.save();
            gc.beginPath();
            gc.rect(effectX, y, effectWidth, ROW_HEIGHT);
            gc.closePath();
            gc.clip();
            gc.fillText(summary, effectX, textY);
            gc.restore();
        }
    }

    public int getCursorRow() { return cursorRow; }
    public int getCursorChannel() { return cursorChannel; }
    public int getCursorColumn() { return cursorColumn; }

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

    /** Set the playback highlight row within the current pattern. */
    public void setPlaybackRow(int row) {
        this.playbackRow = row;
        if (autoScrollActive && row >= 0) {
            scrollToRow(row);
        }
        refreshDisplay();
    }

    /** Set per-channel playback row markers (length 10, -1 for inactive channels). */
    public void setPlaybackRowsByChannel(int[] rows) {
        if (rows == null) {
            Arrays.fill(playbackRowsByChannel, -1);
            refreshDisplay();
            return;
        }
        int len = Math.min(playbackRowsByChannel.length, rows.length);
        for (int i = 0; i < len; i++) {
            playbackRowsByChannel[i] = rows[i];
        }
        for (int i = len; i < playbackRowsByChannel.length; i++) {
            playbackRowsByChannel[i] = -1;
        }
        refreshDisplay();
    }

    /**
     * Scrolls the viewport so the given row is visible, centering it
     * if it has moved outside the visible area.
     */
    private void scrollToRow(int row) {
        double totalHeight = canvas.getHeight();
        double viewportHeight = getViewportBounds().getHeight();
        if (totalHeight <= viewportHeight || viewportHeight <= 0) return;

        double rowY = HEADER_HEIGHT + row * ROW_HEIGHT;
        double currentVvalue = getVvalue();
        double scrollableHeight = totalHeight - viewportHeight;
        double viewTop = currentVvalue * scrollableHeight;
        double viewBottom = viewTop + viewportHeight;

        // Only scroll if the row is outside the visible area (with some margin)
        if (rowY < viewTop + HEADER_HEIGHT || rowY + ROW_HEIGHT > viewBottom) {
            // Center the playback row in the viewport
            double targetTop = rowY - viewportHeight / 2;
            targetTop = Math.max(0, Math.min(targetTop, scrollableHeight));
            setVvalue(targetTop / scrollableHeight);
        }
    }

    /** Set the playback order row for order list highlighting. */
    public void setPlaybackOrderRow(int orderRow) {
        if (this.playbackOrderRow != orderRow) {
            autoScrollActive = true;
        }
        this.playbackOrderRow = orderRow;
    }

    /** Get the current playback order row, or -1 if not playing. */
    public int getPlaybackOrderRow() {
        return playbackOrderRow;
    }

    /** Clear the playback cursor (called on stop). */
    public void clearPlaybackCursor() {
        boolean anyChannelMarker = false;
        for (int value : playbackRowsByChannel) {
            if (value >= 0) {
                anyChannelMarker = true;
                break;
            }
        }
        if (playbackRow < 0 && playbackOrderRow < 0 && !anyChannelMarker) return;
        this.playbackRow = -1;
        this.playbackOrderRow = -1;
        Arrays.fill(playbackRowsByChannel, -1);
        this.autoScrollActive = true;
        refreshDisplay();
    }

    private void setupKeyboardHandling() {
        canvas.setFocusTraversable(true);

        // Request focus and place cursor on press; drag extends selection.
        canvas.setOnMousePressed(e -> {
            canvas.requestFocus();
            handleMousePressed(e);
        });
        canvas.setOnMouseDragged(this::handleMouseDragged);

        // Header click toggles mute/solo.
        canvas.setOnMouseClicked(e -> {
            handleMouseClicked(e);
        });

        canvas.setOnKeyPressed(this::handleKeyPressed);
    }

    private int clampVisualRow(double y) {
        int row = (int) ((y - HEADER_HEIGHT) / ROW_HEIGHT);
        int maxRow = getMaxRowCount() - 1;
        if (maxRow < 0) return 0;
        return Math.max(0, Math.min(row, maxRow));
    }

    private int clampChannel(double x, int fallbackChannel) {
        int maxCh = getVisibleChannelCount() - 1;
        if (x < ROW_NUM_WIDTH) {
            return Math.max(0, Math.min(fallbackChannel, maxCh));
        }
        int ch = (int) ((x - ROW_NUM_WIDTH) / CHANNEL_WIDTH);
        return Math.max(0, Math.min(ch, maxCh));
    }

    private int clickedColumn(double x, int channel) {
        double xInChannel = (x - ROW_NUM_WIDTH) - channel * CHANNEL_WIDTH;
        if (xInChannel < 55) return COL_NOTE; // Covers note + duration area (duration is display-only)
        if (xInChannel < 75) return COL_INSTRUMENT;
        return COL_EFFECT;
    }

    private void handleMousePressed(MouseEvent e) {
        if (e.getY() < HEADER_HEIGHT) {
            dragAnchorRow = -1;
            dragAnchorChannel = -1;
            return;
        }

        cancelPendingHex();
        clearSelection();
        cursorRow = clampVisualRow(e.getY());
        cursorChannel = clampChannel(e.getX(), cursorChannel);
        if (e.getX() >= ROW_NUM_WIDTH) {
            cursorColumn = clickedColumn(e.getX(), cursorChannel);
        }
        dragAnchorRow = cursorRow;
        dragAnchorChannel = cursorChannel;
        autoScrollActive = false;
        refreshDisplay();
    }

    private void handleMouseDragged(MouseEvent e) {
        if (!e.isPrimaryButtonDown()) return;
        if (e.getY() < HEADER_HEIGHT) return;
        if (dragAnchorRow < 0 || dragAnchorChannel < 0) return;

        cancelPendingHex();
        selStartRow = dragAnchorRow;
        selStartChannel = dragAnchorChannel;
        selEndRow = clampVisualRow(e.getY());
        selEndChannel = clampChannel(e.getX(), cursorChannel);
        cursorRow = selEndRow;
        cursorChannel = selEndChannel;
        autoScrollActive = false;
        refreshDisplay();
    }

    private void handleMouseClicked(MouseEvent e) {
        if (e.getY() < HEADER_HEIGHT) {
            int ch = (int) ((e.getX() - ROW_NUM_WIDTH) / CHANNEL_WIDTH);
            if (ch >= 0 && ch < getVisibleChannelCount()) {
                if (e.isControlDown()) {
                    toggleSolo(ch);
                } else {
                    toggleMute(ch);
                }
            }
            return;
        }

        if (e.getClickCount() >= 2 && cursorColumn == COL_EFFECT) {
            openEffectStackEditor();
        }
    }

    private void handleKeyPressed(KeyEvent e) {
        // Hex digit entry for instrument edits should work regardless of Shift state.
        if (cursorColumn == COL_INSTRUMENT || cursorColumn == COL_EFFECT) {
            int hexDigit = hexDigitFromKeyEvent(e);
            if (hexDigit >= 0) {
                // If the user is on Effect, treat hex as an instrument edit on this row.
                if (cursorColumn == COL_EFFECT) {
                    cursorColumn = COL_INSTRUMENT;
                }
                handleHexDigit(hexDigit);
                e.consume();
                return;
            }
        }

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
                    cancelPendingHex();
                    int chCount = getVisibleChannelCount();
                    cursorColumn = COL_NOTE;
                    cursorChannel = (cursorChannel - 1 + chCount) % chCount;
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
                case Z -> {
                    if (undoManager.undo()) {
                        invalidateDecodedCache();
                        refreshDisplay();
                    }
                }
                case Y -> {
                    if (undoManager.redo()) {
                        invalidateDecodedCache();
                        refreshDisplay();
                    }
                }
                case UP -> { currentOctave = Math.min(currentOctave + 1, 8); }
                case DOWN -> { currentOctave = Math.max(currentOctave - 1, 0); }
                default -> {}
            }
            e.consume();
            return;
        }

        switch (e.getCode()) {
            case UP -> { cancelPendingHex(); clearSelection(); moveCursor(-1, 0); }
            case DOWN -> { cancelPendingHex(); clearSelection(); moveCursor(1, 0); }
            case LEFT -> { cancelPendingHex(); clearSelection(); moveCursorColumn(-1); }
            case RIGHT -> { cancelPendingHex(); clearSelection(); moveCursorColumn(1); }
            case DELETE -> { cancelPendingHex(); deleteAtCursor(); }
            case INSERT -> { cancelPendingHex(); insertEmptyRow(); }
            case BACK_SPACE -> { cancelPendingHex(); deleteAndPullUp(); }
            case PAGE_UP -> { cancelPendingHex(); currentOctave = Math.min(currentOctave + 1, 7); }
            case PAGE_DOWN -> { cancelPendingHex(); currentOctave = Math.max(currentOctave - 1, 0); }
            case PERIOD -> { cancelPendingHex(); insertRest(); }
            case EQUALS, ADD -> { cancelPendingHex(); transposeSelection(1); }
            case MINUS, SUBTRACT -> { cancelPendingHex(); transposeSelection(-1); }
            case TAB -> {
                cancelPendingHex();
                int chCount = getVisibleChannelCount();
                cursorColumn = COL_NOTE;
                cursorChannel = (cursorChannel + 1) % chCount;
                refreshDisplay();
            }
            case F1 -> currentOctave = 1;
            case F2 -> currentOctave = 2;
            case F3 -> currentOctave = 3;
            case F4 -> currentOctave = 4;
            case F5 -> currentOctave = 5;
            case F6 -> currentOctave = 6;
            case F7 -> currentOctave = 7;
            case F8 -> currentOctave = 8;
            case ENTER -> {
                cancelPendingHex();
                if (cursorColumn == COL_EFFECT) {
                    openEffectStackEditor();
                } else if (onPlayFromCursor != null) {
                    onPlayFromCursor.run();
                }
                e.consume();
            }
            case SPACE -> { cancelPendingHex(); if (onTogglePlayback != null) onTogglePlayback.run(); }
            case ESCAPE -> {
                if (pendingHexDigit >= 0) {
                    cancelPendingHex();
                    refreshDisplay();
                } else {
                    if (onStopPlayback != null) onStopPlayback.run();
                    clearSelection();
                }
            }
            default -> {
                if (cursorColumn == COL_NOTE) {
                    String text = e.getText();
                    if (text != null && !text.isEmpty()) {
                        char key = text.charAt(0);
                        if (isDacChannelActive()) {
                            int dacIndex = dacIndexFromKey(key);
                            if (dacIndex >= 0 && dacIndex < song.getDacSamples().size()) {
                                insertNote(0x81 + dacIndex);
                            }
                        } else {
                            int noteValue = SmpsEncoder.encodeNoteFromKey(key, currentOctave);
                            if (noteValue > 0) {
                                insertNote(noteValue);
                            }
                        }
                    }
                }
            }
        }
    }

    private void moveCursor(int rowDelta, int channelDelta) {
        int maxRow = getMaxRowCount() - 1;
        int maxChannel = getVisibleChannelCount() - 1;
        cursorRow = Math.max(0, Math.min(cursorRow + rowDelta, maxRow));
        cursorChannel = Math.max(0, Math.min(cursorChannel + channelDelta, maxChannel));
        autoScrollActive = false;
        refreshDisplay();
    }

    /**
     * Move the cursor left/right through sub-columns (Note -> Duration -> Instrument -> Effect),
     * wrapping to the previous/next channel at the boundaries.
     */
    private void moveCursorColumn(int delta) {
        int maxChannel = getVisibleChannelCount() - 1;
        int newCol = cursorColumn + delta;
        // Skip COL_DURATION (display-only, not editable)
        if (newCol == COL_DURATION) newCol += delta;
        if (newCol < 0) {
            // Wrap to previous channel's last column
            if (cursorChannel > 0) {
                cursorChannel--;
                cursorColumn = COL_EFFECT;
            }
            // else stay at first column of first channel
        } else if (newCol >= COL_COUNT) {
            // Wrap to next channel's first column
            if (cursorChannel < maxChannel) {
                cursorChannel++;
                cursorColumn = COL_NOTE;
            }
            // else stay at last column of last channel
        } else {
            cursorColumn = newCol;
        }
        refreshDisplay();
    }

    /**
     * Extract a hex digit (0-15) from a key event, or -1 if the key is not a hex digit.
     * Handles both main keyboard digits (0-9) and letters A-F.
     */
    private static int hexDigitFromKeyEvent(KeyEvent e) {
        return switch (e.getCode()) {
            case DIGIT0, NUMPAD0 -> 0;
            case DIGIT1, NUMPAD1 -> 1;
            case DIGIT2, NUMPAD2 -> 2;
            case DIGIT3, NUMPAD3 -> 3;
            case DIGIT4, NUMPAD4 -> 4;
            case DIGIT5, NUMPAD5 -> 5;
            case DIGIT6, NUMPAD6 -> 6;
            case DIGIT7, NUMPAD7 -> 7;
            case DIGIT8, NUMPAD8 -> 8;
            case DIGIT9, NUMPAD9 -> 9;
            case A -> 0xA;
            case B -> 0xB;
            case C -> 0xC;
            case D -> 0xD;
            case E -> 0xE;
            case F -> 0xF;
            default -> -1;
        };
    }

    /**
     * Handle a hex digit entry for the instrument column.
     * Two consecutive digits form a complete byte value (high nibble first).
     * On the first digit, the value is stored as pending. On the second digit,
     * the instrument change is applied to the current row.
     */
    private void handleHexDigit(int digit) {
        if (pendingHexDigit < 0) {
            // First digit: store as pending high nibble
            pendingHexDigit = digit;
            refreshDisplay();
        } else {
            // Second digit: combine and apply
            int value = (pendingHexDigit << 4) | digit;
            pendingHexDigit = -1;
            applyInstrumentValue(value);
        }
    }

    /** Cancel any pending hex digit entry without applying. */
    private void cancelPendingHex() {
        pendingHexDigit = -1;
    }

    /**
     * Apply an instrument change (SET_VOICE for FM, PSG_INSTRUMENT for PSG)
     * to the row at the cursor position.
     */
    private void applyInstrumentValue(int value) {
        if (!canEdit()) return;
        int targetRow = mapVisualRowToTrackRowIndex(cursorChannel, cursorRow);
        byte[] trackData = ensureTrackHasRow(getActiveTrackData(), targetRow);

        int effectiveChannel = activePhrase != null ? phraseChannelIndex : cursorChannel;
        int instrFlag = (effectiveChannel <= 5)
                ? SmpsCoordFlags.SET_VOICE
                : SmpsCoordFlags.PSG_INSTRUMENT;

        recordUndoIfPattern();
        byte[] newData = SmpsEncoder.setRowInstrument(trackData, targetRow, instrFlag, value);
        setActiveTrackData(newData);
        invalidateDecodedCache();
        refreshDisplay();
        markDirty();
    }

    private int getMaxRowCount() {
        if (activePhrase != null) return Math.max(ensureDecodedCache(), 1);
        if (song == null || song.getPatterns().isEmpty()) return 1;
        if (currentPatternIndex < 0 || currentPatternIndex >= song.getPatterns().size()) return 1;
        return Math.max(ensureDecodedCache(), 1);
    }

    /** Returns true if an edit target is available (phrase or pattern). */
    private boolean canEdit() {
        if (activePhrase != null) return true;
        return song != null && currentPatternIndex < song.getPatterns().size();
    }

    /** Get the track data for the current cursor channel (phrase or pattern). */
    private byte[] getActiveTrackData() {
        if (activePhrase != null) return activePhrase.getDataDirect();
        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        return pattern.getTrackDataDirect(cursorChannel);
    }

    /** Set the track data for the current cursor channel (phrase or pattern). */
    private void setActiveTrackData(byte[] data) {
        if (activePhrase != null) {
            activePhrase.setData(data);
        } else {
            Pattern pattern = song.getPatterns().get(currentPatternIndex);
            pattern.setTrackData(cursorChannel, data);
        }
    }

    /** Record undo snapshot before an edit. No-op in phrase mode. */
    private void recordUndoIfPattern() {
        if (activePhrase != null) return;
        Pattern pattern = song.getPatterns().get(currentPatternIndex);
        undoManager.recordEdit(pattern, cursorChannel);
    }

    /** The number of channels visible in the grid. */
    private int getVisibleChannelCount() {
        return activePhrase != null ? 1 : Pattern.CHANNEL_COUNT;
    }

    private byte[] prependInstrumentIfSelected(byte[] noteBytes) {
        if (instrumentPanel == null) return noteBytes;

        byte[] instrBytes = null;
        int effectiveChannel = activePhrase != null ? phraseChannelIndex : cursorChannel;
        if (effectiveChannel <= 5) {
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
        if (!canEdit()) return;
        int targetRow = mapVisualRowToTrackRowIndex(cursorChannel, cursorRow);
        byte[] trackData = ensureTrackHasRow(getActiveTrackData(), targetRow);
        byte[] noteBytes = SmpsEncoder.encodeNote(noteValue, currentDuration);
        byte[] insertBytes = prependInstrumentIfSelected(noteBytes);
        byte[] newData = SmpsEncoder.insertAtRow(trackData, targetRow, insertBytes);
        recordUndoIfPattern();
        setActiveTrackData(newData);
        cursorRow++;
        invalidateDecodedCache();
        refreshDisplay();
        markDirty();
    }

    private void insertRest() {
        if (!canEdit()) return;
        int targetRow = mapVisualRowToTrackRowIndex(cursorChannel, cursorRow);
        byte[] trackData = ensureTrackHasRow(getActiveTrackData(), targetRow);
        byte[] restBytes = SmpsEncoder.encodeRest(currentDuration);
        byte[] newData = SmpsEncoder.insertAtRow(trackData, targetRow, restBytes);
        recordUndoIfPattern();
        setActiveTrackData(newData);
        cursorRow++;
        invalidateDecodedCache();
        refreshDisplay();
        markDirty();
    }

    private void deleteAtCursor() {
        if (!canEdit()) return;
        byte[] trackData = getActiveTrackData();
        int targetRow = mapVisualRowToTrackRowIndex(cursorChannel, cursorRow);
        byte[] newData = SmpsEncoder.deleteRow(trackData, targetRow);
        recordUndoIfPattern();
        setActiveTrackData(newData);
        invalidateDecodedCache();
        refreshDisplay();
        markDirty();
    }

    private void openEffectStackEditor() {
        if (!canEdit()) return;

        String context = String.format("%s row %02X", CHANNEL_NAMES[cursorChannel], cursorRow);
        Optional<List<SmpsEncoder.EffectCommand>> updated = EffectStackEditor.show(getEffectsAtCursor(), context);
        if (updated.isEmpty()) return;
        setEffectsAtCursor(updated.get());
    }

    private List<SmpsEncoder.EffectCommand> getEffectsAtCursor() {
        if (!canEdit()) return List.of();
        int targetRow = mapVisualRowToTrackRowIndex(cursorChannel, cursorRow);
        byte[] trackData = ensureTrackHasRow(getActiveTrackData(), targetRow);
        return SmpsEncoder.getRowEffects(trackData, targetRow);
    }

    private void setEffectsAtCursor(List<SmpsEncoder.EffectCommand> effects) {
        if (!canEdit()) return;
        int targetRow = mapVisualRowToTrackRowIndex(cursorChannel, cursorRow);
        byte[] trackData = ensureTrackHasRow(getActiveTrackData(), targetRow);
        byte[] newData = SmpsEncoder.setRowEffects(trackData, targetRow, effects);
        recordUndoIfPattern();
        setActiveTrackData(newData);
        invalidateDecodedCache();
        refreshDisplay();
        markDirty();
    }

    private static boolean isEditableTrackRow(SmpsDecoder.TrackerRow row) {
        return row.note() != null && !row.note().isEmpty();
    }

    private static int parseHexByteToken(String token) {
        if (token == null || token.isBlank()) return -1;
        try {
            int value = Integer.parseInt(token.trim(), 16);
            return value & 0xFF;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String summarizeEffectText(String effectText) {
        if (effectText == null || effectText.isBlank()) return "";
        String[] commands = effectText.split(";");
        List<String> labels = new ArrayList<>();
        for (String cmd : commands) {
            String trimmed = cmd.trim();
            if (trimmed.isEmpty()) continue;
            String[] tokens = trimmed.split("\\s+");
            int flag = parseHexByteToken(tokens[0]);
            if (flag >= 0) {
                int paramCount = SmpsCoordFlags.getParamCount(flag);
                int[] params = new int[Math.min(paramCount, tokens.length - 1)];
                for (int i = 0; i < params.length; i++) {
                    params[i] = parseHexByteToken(tokens[i + 1]);
                }
                labels.add(EffectMnemonics.format(flag, params));
            } else {
                labels.add(tokens[0]);
            }
        }
        if (labels.isEmpty()) return "";
        if (labels.size() == 1) return labels.get(0);
        return labels.get(0) + " +" + (labels.size() - 1);
    }

    private List<SmpsDecoder.TrackerRow> getDecodedRowsForChannel(int channel) {
        ensureDecodedCache();
        if (channel >= 0 && channel < decodedChannels.size()) {
            return decodedChannels.get(channel);
        }
        return List.of();
    }

    /**
     * Map a visual grid row index to a concrete track row index (note/rest/tie row).
     * Visual rows past current data map to insert positions by padding with rests.
     */
    private int mapVisualRowToTrackRowIndex(int channel, int visualRow) {
        List<SmpsDecoder.TrackerRow> rows = getDecodedRowsForChannel(channel);
        if (rows.isEmpty()) {
            return Math.max(visualRow, 0);
        }

        if (visualRow >= rows.size()) {
            int editableCount = 0;
            for (SmpsDecoder.TrackerRow row : rows) {
                if (isEditableTrackRow(row)) editableCount++;
            }
            return editableCount + (visualRow - rows.size());
        }

        int editableBefore = 0;
        for (int i = 0; i < visualRow; i++) {
            if (isEditableTrackRow(rows.get(i))) editableBefore++;
        }
        if (isEditableTrackRow(rows.get(visualRow))) {
            return editableBefore;
        }
        return editableBefore;
    }

    private byte[] ensureTrackHasRow(byte[] trackData, int rowIndex) {
        if (rowIndex < 0) return trackData;
        int editableRows = 0;
        List<SmpsDecoder.TrackerRow> decoded = SmpsDecoder.decode(trackData);
        for (SmpsDecoder.TrackerRow row : decoded) {
            if (isEditableTrackRow(row)) editableRows++;
        }
        if (rowIndex < editableRows) return trackData;

        ByteArrayOutputStream out = new ByteArrayOutputStream(trackData.length + (rowIndex - editableRows + 1) * 2);
        out.write(trackData, 0, trackData.length);
        for (int i = editableRows; i <= rowIndex; i++) {
            byte[] rest = SmpsEncoder.encodeRest(currentDuration);
            out.write(rest, 0, rest.length);
        }
        return out.toByteArray();
    }

    private void insertEmptyRow() {
        // In SMPS, a rest is the equivalent of a blank row (every tick must have data)
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
        selEndChannel = Math.max(0, Math.min(selEndChannel + channelDelta, getVisibleChannelCount() - 1));
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
        selEndChannel = getVisibleChannelCount() - 1;
        refreshDisplay();
    }

    private void copySelection() {
        if (!hasSelection() || !canEdit()) return;

        int minCh = getSelMinChannel();
        int maxCh = getSelMaxChannel();
        int minRow = getSelMinRow();
        int maxRow = getSelMaxRow();
        int rowCount = maxRow - minRow + 1;
        int chCount = maxCh - minCh + 1;

        byte[][] channelData = new byte[chCount][];
        if (activePhrase != null) {
            channelData[0] = SmpsEncoder.extractRowRange(activePhrase.getDataDirect(), minRow, rowCount);
        } else {
            Pattern pattern = song.getPatterns().get(currentPatternIndex);
            for (int ch = 0; ch < chCount; ch++) {
                byte[] trackData = pattern.getTrackDataDirect(minCh + ch);
                channelData[ch] = SmpsEncoder.extractRowRange(trackData, minRow, rowCount);
            }
        }

        clipboard = new ClipboardData(channelData, rowCount, song);
    }

    private void deleteSelection() {
        if (!hasSelection() || !canEdit()) return;

        int minCh = getSelMinChannel();
        int maxCh = getSelMaxChannel();
        int minRow = getSelMinRow();
        int maxRow = getSelMaxRow();

        if (activePhrase != null) {
            byte[] trackData = activePhrase.getDataDirect();
            for (int row = maxRow; row >= minRow; row--) {
                trackData = SmpsEncoder.deleteRow(trackData, row);
            }
            activePhrase.setData(trackData);
        } else {
            Pattern pattern = song.getPatterns().get(currentPatternIndex);
            int chCount = maxCh - minCh + 1;
            int[] channels = new int[chCount];
            for (int i = 0; i < chCount; i++) {
                channels[i] = minCh + i;
            }
            undoManager.recordMultiEdit(pattern, channels);

            for (int ch = minCh; ch <= maxCh; ch++) {
                byte[] trackData = pattern.getTrackDataDirect(ch);
                for (int row = maxRow; row >= minRow; row--) {
                    trackData = SmpsEncoder.deleteRow(trackData, row);
                }
                pattern.setTrackData(ch, trackData);
            }
        }

        clearSelection();
        invalidateDecodedCache();
        refreshDisplay();
        markDirty();
    }

    private void pasteAtCursor() {
        if (clipboard == null || !canEdit()) return;

        // Build paste data from clipboard
        int chCount = clipboard.getChannelCount();
        byte[][] pasteChannelData = new byte[chCount][];
        for (int ch = 0; ch < chCount; ch++) {
            pasteChannelData[ch] = clipboard.getChannelData(ch);
        }

        // Cross-song paste: resolve instrument references
        if (song != null && clipboard.isCrossSong()) {
            pasteChannelData = resolveCrossPaste(pasteChannelData,
                    clipboard.getSourceVoices(), clipboard.getSourcePsgEnvelopes(), song);
            if (pasteChannelData == null) return; // user cancelled
        }

        if (activePhrase != null) {
            // In phrase mode, paste only the first channel
            byte[] trackData = activePhrase.getDataDirect();
            byte[] newData = SmpsEncoder.insertAtRow(trackData, cursorRow, pasteChannelData[0]);
            activePhrase.setData(newData);
        } else {
            Pattern pattern = song.getPatterns().get(currentPatternIndex);
            int count = 0;
            for (int ch = 0; ch < chCount; ch++) {
                if (cursorChannel + ch < Pattern.CHANNEL_COUNT) count++;
            }
            int[] affectedChannels = new int[count];
            for (int i = 0; i < count; i++) {
                affectedChannels[i] = cursorChannel + i;
            }
            undoManager.recordMultiEdit(pattern, affectedChannels);

            for (int ch = 0; ch < chCount; ch++) {
                int targetChannel = cursorChannel + ch;
                if (targetChannel >= Pattern.CHANNEL_COUNT) break;

                byte[] trackData = pattern.getTrackDataDirect(targetChannel);
                byte[] pasteData = pasteChannelData[ch];
                byte[] newData = SmpsEncoder.insertAtRow(trackData, cursorRow, pasteData);
                pattern.setTrackData(targetChannel, newData);
            }
        }
        invalidateDecodedCache();
        refreshDisplay();
        markDirty();
    }

    private byte[][] resolveCrossPaste(byte[][] channelData, List<FmVoice> srcVoices,
                                        List<PsgEnvelope> srcPsgEnvelopes, Song dstSong) {
        PasteResolver.ScanResult scan = PasteResolver.scanAndAutoRemap(
                channelData, srcVoices, srcPsgEnvelopes, dstSong);

        if (PasteResolver.hasNoInstruments(scan)) return channelData;

        if (PasteResolver.isFullyResolved(scan)) {
            return PasteResolver.rewriteAll(channelData, scan.voiceMap(), scan.psgMap());
        }

        // Unresolved instruments require user interaction via dialog
        Song srcProxy = new Song();
        srcProxy.getVoiceBank().addAll(srcVoices);
        srcProxy.getPsgEnvelopes().addAll(srcPsgEnvelopes);
        InstrumentResolveDialog dialog = new InstrumentResolveDialog(
                srcProxy, dstSong, scan.unresolvedVoices(), scan.unresolvedPsg());
        Optional<InstrumentResolveDialog.Resolution> result = dialog.showAndWait();
        if (result.isEmpty()) return null;

        InstrumentResolveDialog.Resolution res = result.get();
        // Note: voice bank additions from cross-paste are not undoable.
        // A full undo system for voice bank changes is planned for a future phase.
        dstSong.getVoiceBank().addAll(res.voicesToCopy());
        dstSong.getPsgEnvelopes().addAll(res.envelopesToCopy());
        Map<Integer, Integer> voiceMap = scan.voiceMap();
        Map<Integer, Integer> psgMap = scan.psgMap();
        voiceMap.putAll(res.voiceMap());
        psgMap.putAll(res.psgMap());

        return PasteResolver.rewriteAll(channelData, voiceMap, psgMap);
    }

    private void transposeSelection(int semitones) {
        if (!canEdit()) return;

        if (activePhrase != null) {
            // Phrase mode: transpose active phrase data
            byte[] trackData = activePhrase.getDataDirect();
            int minRow = hasSelection() ? getSelMinRow() : cursorRow;
            int rowCount = hasSelection() ? getSelMaxRow() - minRow + 1 : 1;
            byte[] transposed = SmpsEncoder.transposeTrackRange(trackData, minRow, rowCount, semitones);
            activePhrase.setData(transposed);
        } else {
            Pattern pattern = song.getPatterns().get(currentPatternIndex);
            if (hasSelection()) {
                int minCh = getSelMinChannel();
                int maxCh = getSelMaxChannel();
                int minRow = getSelMinRow();
                int rowCount = getSelMaxRow() - minRow + 1;

                int chCount = maxCh - minCh + 1;
                int[] channels = new int[chCount];
                for (int i = 0; i < chCount; i++) {
                    channels[i] = minCh + i;
                }
                undoManager.recordMultiEdit(pattern, channels);
                for (int ch = minCh; ch <= maxCh; ch++) {
                    byte[] trackData = pattern.getTrackDataDirect(ch);
                    byte[] transposed = SmpsEncoder.transposeTrackRange(trackData, minRow, rowCount, semitones);
                    pattern.setTrackData(ch, transposed);
                }
            } else {
                undoManager.recordEdit(pattern, cursorChannel);
                byte[] trackData = pattern.getTrackDataDirect(cursorChannel);
                byte[] transposed = SmpsEncoder.transposeTrackRange(trackData, cursorRow, 1, semitones);
                pattern.setTrackData(cursorChannel, transposed);
            }
        }
        invalidateDecodedCache();
        refreshDisplay();
        markDirty();
    }

    // --- DAC helpers ---

    /**
     * Returns true when the cursor is on the DAC channel and the song
     * has at least one DAC sample defined, enabling DAC-specific behavior.
     */
    private boolean isDacChannelActive() {
        int effectiveChannel = activePhrase != null ? phraseChannelIndex : cursorChannel;
        return effectiveChannel == DAC_CHANNEL
                && song != null
                && !song.getDacSamples().isEmpty();
    }

    /**
     * Maps a keyboard character to a sequential DAC sample index (0-11),
     * or -1 if the key is not mapped.
     */
    private static int dacIndexFromKey(char key) {
        char upper = Character.toUpperCase(key);
        if (upper < 128) {
            return DAC_KEY_MAP[upper];
        }
        return -1;
    }

    /**
     * Formats a DAC note byte for display. If the note byte corresponds to a
     * valid DAC sample, returns the first 3 characters of the sample name
     * (uppercased). Otherwise returns "D" + 2-digit hex index (e.g. "D00").
     */
    private String formatDacNote(int noteByte) {
        int dacIdx = (noteByte & 0xFF) - 0x81;
        if (dacIdx < 0) return null;
        if (song != null) {
            List<DacSample> samples = song.getDacSamples();
            if (dacIdx < samples.size()) {
                String name = samples.get(dacIdx).getName();
                if (name != null && !name.isEmpty()) {
                    String abbrev = name.length() >= 3
                            ? name.substring(0, 3).toUpperCase()
                            : name.toUpperCase();
                    return abbrev;
                }
            }
        }
        return String.format("D%02X", dacIdx);
    }

    /** Note names matching SmpsDecoder's format for reverse lookup. */
    private static final String[] REVERSE_NOTE_NAMES = {
        "C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-"
    };

    /**
     * Reverse-maps a decoded note display string (e.g. "C-0") back to a note byte,
     * then formats it as a DAC sample name. Returns null if the string is not a valid note.
     */
    private String formatDacNoteFromDisplay(String noteDisplay) {
        if (noteDisplay == null || noteDisplay.length() < 3) return null;
        String notePart = noteDisplay.substring(0, 2);
        char octaveChar = noteDisplay.charAt(2);
        if (octaveChar < '0' || octaveChar > '9') return null;
        int octave = octaveChar - '0';

        int semitone = -1;
        for (int i = 0; i < REVERSE_NOTE_NAMES.length; i++) {
            if (REVERSE_NOTE_NAMES[i].equals(notePart)) {
                semitone = i;
                break;
            }
        }
        if (semitone < 0) return null;

        int noteByte = 0x81 + octave * 12 + semitone;
        return formatDacNote(noteByte);
    }

    // --- Mute / Solo ---

    private void applyMuteState() {
        if (playbackEngine == null) return;
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            boolean muted = soloChannel >= 0 ? (ch != soloChannel) : channelMuted[ch];
            if (ch < 6) {
                playbackEngine.setFmMute(ch, muted);
            } else {
                playbackEngine.setPsgMute(ch - 6, muted);
            }
        }
    }

    private void toggleMute(int channel) {
        if (channel < 0 || channel >= Pattern.CHANNEL_COUNT) return;
        if (soloChannel >= 0) {
            soloChannel = -1;
        }
        channelMuted[channel] = !channelMuted[channel];
        applyMuteState();
        refreshDisplay();
    }

    private void toggleSolo(int channel) {
        if (channel < 0 || channel >= Pattern.CHANNEL_COUNT) return;
        if (soloChannel == channel) {
            soloChannel = -1;
        } else {
            soloChannel = channel;
        }
        applyMuteState();
        refreshDisplay();
    }

    /**
     * Returns whether a channel is effectively muted (by explicit mute or solo exclusion).
     * Useful for WAV export to respect the current mute/solo state.
     */
    public boolean isChannelMuted(int channel) {
        if (soloChannel >= 0) return channel != soloChannel;
        return channel >= 0 && channel < channelMuted.length && channelMuted[channel];
    }

    public UndoManager getUndoManager() { return undoManager; }
}
