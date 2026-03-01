package com.opensmps.deck.ui;

import com.opensmps.deck.codec.SmpsDecoder;
import com.opensmps.deck.model.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * Canvas-based overview panel showing all 10 channels' phrase blocks
 * in the hierarchical arrangement. Each channel is a horizontal row
 * with blocks sized proportionally to phrase data length.
 */
public class SongView extends ScrollPane {

    private static final int CHANNEL_HEIGHT = 28;
    private static final int LABEL_WIDTH = 40;
    private static final int PIXELS_PER_ROW = 6;
    private static final int HEADER_HEIGHT = 0;
    private static final Font BLOCK_FONT = Font.font("Monospaced", 10);
    private static final Font LABEL_FONT = Font.font("Monospaced", 11);

    private static final String[] CHANNEL_NAMES = {
        "FM1", "FM2", "FM3", "FM4", "FM5", "DAC",
        "PSG1", "PSG2", "PSG3", "Nse"
    };

    /** Color palette for phrase blocks. Shared phrases get consistent colors. */
    private static final Color[] PHRASE_COLORS = {
        Color.web("#3a6b8a"), Color.web("#6b3a8a"), Color.web("#3a8a6b"),
        Color.web("#8a6b3a"), Color.web("#8a3a5a"), Color.web("#5a8a3a"),
        Color.web("#3a5a8a"), Color.web("#8a5a3a"), Color.web("#5a3a8a"),
        Color.web("#3a8a5a"), Color.web("#8a3a3a"), Color.web("#3a8a8a")
    };

    private final Canvas canvas;
    private HierarchicalArrangement arrangement;
    private IntConsumer onPhraseSelected;
    private BiConsumer<Integer, Integer> onPhraseDoubleClicked; // (channelIndex, entryIndex)
    private int selectedChannel = 0;
    private int selectedEntryIndex = -1;
    private double playbackPosition = -1;

    public SongView() {
        canvas = new Canvas(600, Pattern.CHANNEL_COUNT * CHANNEL_HEIGHT);
        setContent(canvas);
        setFitToWidth(true);
        setPannable(false);
        setStyle("-fx-background: #1a1a2e;");
        setPrefWidth(200);

        canvas.setOnMousePressed(this::handleMousePressed);
    }

    public void setArrangement(HierarchicalArrangement arrangement) {
        this.arrangement = arrangement;
        refreshDisplay();
    }

    public void setOnPhraseSelected(IntConsumer callback) {
        this.onPhraseSelected = callback;
    }

    public void setOnPhraseDoubleClicked(BiConsumer<Integer, Integer> callback) {
        this.onPhraseDoubleClicked = callback;
    }

    public int getSelectedChannel() { return selectedChannel; }
    public int getSelectedEntryIndex() { return selectedEntryIndex; }

    public void setPlaybackPosition(double position) {
        this.playbackPosition = position;
        refreshDisplay();
    }

    public void refreshDisplay() {
        double totalWidth = computeTotalWidth();
        canvas.setWidth(Math.max(totalWidth, getWidth()));
        canvas.setHeight(Pattern.CHANNEL_COUNT * CHANNEL_HEIGHT);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        if (arrangement == null) return;

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            renderChannel(gc, ch);
        }

        // Playback cursor
        if (playbackPosition >= 0) {
            double cursorX = LABEL_WIDTH + playbackPosition * PIXELS_PER_ROW;
            gc.setStroke(Color.rgb(255, 255, 255, 0.7));
            gc.setLineWidth(1);
            gc.strokeLine(cursorX, 0, cursorX, canvas.getHeight());
        }
    }

    private void renderChannel(GraphicsContext gc, int ch) {
        double y = ch * CHANNEL_HEIGHT;
        Chain chain = arrangement.getChain(ch);
        PhraseLibrary library = arrangement.getPhraseLibrary();

        // Channel label
        gc.setFont(LABEL_FONT);
        gc.setFill(Color.web("#88aacc"));
        gc.fillText(CHANNEL_NAMES[ch], 4, y + CHANNEL_HEIGHT - 8);

        // Channel separator line
        gc.setStroke(Color.web("#333344"));
        gc.setLineWidth(0.5);
        gc.strokeLine(0, y + CHANNEL_HEIGHT, canvas.getWidth(), y + CHANNEL_HEIGHT);

        // Phrase blocks
        double blockX = LABEL_WIDTH;
        gc.setFont(BLOCK_FONT);

        for (int i = 0; i < chain.getEntries().size(); i++) {
            ChainEntry entry = chain.getEntries().get(i);
            Phrase phrase = library.getPhrase(entry.getPhraseId());
            if (phrase == null) continue;

            int rowCount = Math.max(1, SmpsDecoder.decode(phrase.getDataDirect()).size());
            int effectiveRows = rowCount * entry.getRepeatCount();
            double blockWidth = Math.max(20, effectiveRows * PIXELS_PER_ROW);

            // Block fill
            Color blockColor = phraseColor(entry.getPhraseId());
            boolean isSelected = ch == selectedChannel && i == selectedEntryIndex;
            if (isSelected) {
                blockColor = blockColor.brighter().brighter();
            }
            gc.setFill(blockColor);
            gc.fillRect(blockX + 1, y + 2, blockWidth - 2, CHANNEL_HEIGHT - 4);

            // Block border
            gc.setStroke(isSelected ? Color.web("#88ccff") : Color.web("#556677"));
            gc.setLineWidth(isSelected ? 1.5 : 0.5);
            gc.strokeRect(blockX + 1, y + 2, blockWidth - 2, CHANNEL_HEIGHT - 4);

            // Loop marker
            if (chain.hasLoop() && i == chain.getLoopEntryIndex()) {
                gc.setFill(Color.web("#ffcc44"));
                gc.fillText("\u21BA", blockX + 3, y + CHANNEL_HEIGHT - 8);
            }

            // Label: phrase name + decorations
            gc.setFill(Color.web("#dddddd"));
            String label = phrase.getName();
            if (entry.getTransposeSemitones() != 0) {
                label += String.format(" %+d", entry.getTransposeSemitones());
            }
            if (entry.getRepeatCount() > 1) {
                label += " \u00D7" + entry.getRepeatCount();
            }
            // Clip label to block width
            double labelX = blockX + 4;
            if (chain.hasLoop() && i == chain.getLoopEntryIndex()) {
                labelX += 12; // offset past loop marker
            }
            gc.save();
            gc.beginPath();
            gc.rect(blockX + 1, y, blockWidth - 2, CHANNEL_HEIGHT);
            gc.closePath();
            gc.clip();
            gc.fillText(label, labelX, y + CHANNEL_HEIGHT - 8);
            gc.restore();

            blockX += blockWidth;
        }
    }

    private double computeTotalWidth() {
        if (arrangement == null) return LABEL_WIDTH + 200;
        double maxWidth = 0;
        PhraseLibrary library = arrangement.getPhraseLibrary();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            double channelWidth = LABEL_WIDTH;
            for (ChainEntry entry : arrangement.getChain(ch).getEntries()) {
                Phrase phrase = library.getPhrase(entry.getPhraseId());
                if (phrase == null) continue;
                int rowCount = Math.max(1, SmpsDecoder.decode(phrase.getDataDirect()).size());
                int effectiveRows = rowCount * entry.getRepeatCount();
                channelWidth += Math.max(20, effectiveRows * PIXELS_PER_ROW);
            }
            maxWidth = Math.max(maxWidth, channelWidth);
        }
        return maxWidth + 20;
    }

    private static Color phraseColor(int phraseId) {
        return PHRASE_COLORS[Math.abs(phraseId) % PHRASE_COLORS.length];
    }

    private void handleMousePressed(MouseEvent e) {
        if (arrangement == null) return;

        int ch = (int) (e.getY() / CHANNEL_HEIGHT);
        if (ch < 0 || ch >= Pattern.CHANNEL_COUNT) return;

        selectedChannel = ch;
        Chain chain = arrangement.getChain(ch);
        PhraseLibrary library = arrangement.getPhraseLibrary();

        // Find which entry was clicked
        double clickX = e.getX();
        double blockX = LABEL_WIDTH;
        selectedEntryIndex = -1;

        for (int i = 0; i < chain.getEntries().size(); i++) {
            ChainEntry entry = chain.getEntries().get(i);
            Phrase phrase = library.getPhrase(entry.getPhraseId());
            if (phrase == null) continue;
            int rowCount = Math.max(1, SmpsDecoder.decode(phrase.getDataDirect()).size());
            int effectiveRows = rowCount * entry.getRepeatCount();
            double blockWidth = Math.max(20, effectiveRows * PIXELS_PER_ROW);

            if (clickX >= blockX && clickX < blockX + blockWidth) {
                selectedEntryIndex = i;
                break;
            }
            blockX += blockWidth;
        }

        if (selectedEntryIndex >= 0) {
            int phraseId = chain.getEntries().get(selectedEntryIndex).getPhraseId();
            if (onPhraseSelected != null) {
                onPhraseSelected.accept(phraseId);
            }
            if (e.getClickCount() >= 2 && onPhraseDoubleClicked != null) {
                onPhraseDoubleClicked.accept(ch, selectedEntryIndex);
            }
        }

        // Context menu
        if (e.getButton() == MouseButton.SECONDARY && selectedEntryIndex >= 0) {
            showContextMenu(e, ch, selectedEntryIndex);
        }

        refreshDisplay();
    }

    private void showContextMenu(MouseEvent e, int channel, int entryIndex) {
        ContextMenu menu = new ContextMenu();

        MenuItem renameItem = new MenuItem("Rename");
        renameItem.setOnAction(ev -> {
            // Placeholder — will be wired in MainWindow integration
        });

        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(ev -> {
            Chain chain = arrangement.getChain(channel);
            if (entryIndex < chain.getEntries().size()) {
                chain.getEntries().remove(entryIndex);
                selectedEntryIndex = -1;
                refreshDisplay();
            }
        });

        menu.getItems().addAll(renameItem, deleteItem);
        menu.show(canvas, e.getScreenX(), e.getScreenY());
    }
}
