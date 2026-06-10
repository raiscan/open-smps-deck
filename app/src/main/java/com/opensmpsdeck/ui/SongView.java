package com.opensmpsdeck.ui;

import com.opensmpsdeck.codec.SmpsDecoder;
import com.opensmpsdeck.model.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
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

    private final Canvas canvas;
    // Spacer carries the full logical width; the canvas stays viewport-sized
    // to respect GPU texture limits (huge canvases fail to render)
    private final Pane scrollSpacer = new Pane();
    private HierarchicalArrangement arrangement;
    private com.opensmps.smps.SmpsCoordFlags.Dialect dialect = com.opensmps.smps.SmpsCoordFlags.Dialect.S2;
    private IntConsumer onPhraseSelected;
    private Runnable onEdited;
    private BiConsumer<Integer, Integer> onPhraseDoubleClicked; // (channelIndex, entryIndex)
    private int selectedChannel = 0;
    private int selectedEntryIndex = -1;
    private double playbackPosition = -1;

    public SongView() {
        canvas = new Canvas(600, Pattern.CHANNEL_COUNT * CHANNEL_HEIGHT);
        scrollSpacer.getChildren().add(canvas);
        setContent(scrollSpacer);
        setFitToWidth(true);
        setPannable(false);
        setStyle("-fx-background: #1a1a2e;");
        setPrefWidth(200);

        canvas.setOnMousePressed(this::handleMousePressed);

        // Re-render the visible window whenever the viewport scrolls or resizes
        hvalueProperty().addListener((obs, oldVal, newVal) -> refreshDisplay());
        viewportBoundsProperty().addListener((obs, oldVal, newVal) -> refreshDisplay());
    }

    public void setDialect(com.opensmps.smps.SmpsCoordFlags.Dialect dialect) {
        this.dialect = dialect;
    }

    public void setArrangement(HierarchicalArrangement arrangement) {
        this.arrangement = arrangement;
        refreshDisplay();
    }

    /** Called after this view mutates the song (delete/rename). */
    public void setOnEdited(Runnable callback) {
        this.onEdited = callback;
    }

    private void notifyEdited() {
        if (onEdited != null) onEdited.run();
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
        double totalWidth = Math.max(computeTotalWidth(), getWidth());
        double totalHeight = Pattern.CHANNEL_COUNT * CHANNEL_HEIGHT;

        scrollSpacer.setMinSize(totalWidth, totalHeight);
        scrollSpacer.setPrefSize(totalWidth, totalHeight);

        double viewportWidth = getViewportBounds() != null ? getViewportBounds().getWidth() : 600;
        if (viewportWidth <= 0) viewportWidth = 600;
        canvas.setWidth(Math.min(viewportWidth, totalWidth));
        canvas.setHeight(totalHeight);

        double scrollX = getScrollX();
        canvas.setLayoutX(scrollX);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        if (arrangement == null) return;

        // Render in content coordinates; the canvas only shows the visible window
        gc.save();
        gc.translate(-scrollX, 0);

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

        gc.restore();
    }

    /**
     * Compute the scroll X offset from the ScrollPane's hvalue:
     * the pixel offset of the viewport left edge within the logical content.
     */
    private double getScrollX() {
        double scrollableWidth = scrollSpacer.getMinWidth() - canvas.getWidth();
        if (scrollableWidth <= 0) return 0;
        return getHvalue() * scrollableWidth;
    }

    private void renderChannel(GraphicsContext gc, int ch) {
        double y = ch * CHANNEL_HEIGHT;
        Chain chain = arrangement.getChain(ch);
        PhraseLibrary library = arrangement.getPhraseLibrary();

        // Channel label
        gc.setFont(LABEL_FONT);
        gc.setFill(Color.web("#88aacc"));
        gc.fillText(CHANNEL_NAMES[ch], 4, y + CHANNEL_HEIGHT - 8);

        // Channel separator line (content coordinates; clipped to the canvas)
        gc.setStroke(Color.web("#333344"));
        gc.setLineWidth(0.5);
        gc.strokeLine(0, y + CHANNEL_HEIGHT, scrollSpacer.getMinWidth(), y + CHANNEL_HEIGHT);

        // Phrase blocks
        double blockX = LABEL_WIDTH;
        gc.setFont(BLOCK_FONT);

        for (int i = 0; i < chain.getEntries().size(); i++) {
            ChainEntry entry = chain.getEntries().get(i);
            Phrase phrase = library.getPhrase(entry.getPhraseId());
            if (phrase == null) continue;

            int rowCount = Math.max(1, SmpsDecoder.decode(phrase.getDataDirect(), dialect).size());
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
                int rowCount = Math.max(1, SmpsDecoder.decode(phrase.getDataDirect(), dialect).size());
                int effectiveRows = rowCount * entry.getRepeatCount();
                channelWidth += Math.max(20, effectiveRows * PIXELS_PER_ROW);
            }
            maxWidth = Math.max(maxWidth, channelWidth);
        }
        return maxWidth + 20;
    }

    private static Color phraseColor(int phraseId) {
        return PhraseColors.forPhraseId(phraseId);
    }

    private void handleMousePressed(MouseEvent e) {
        if (arrangement == null) return;

        int ch = (int) (e.getY() / CHANNEL_HEIGHT);
        if (ch < 0 || ch >= Pattern.CHANNEL_COUNT) return;

        selectedChannel = ch;
        Chain chain = arrangement.getChain(ch);
        PhraseLibrary library = arrangement.getPhraseLibrary();

        // Find which entry was clicked (canvas sits at the scroll offset,
        // so canvas-local x maps to content x by adding the offset)
        double clickX = e.getX() + getScrollX();
        double blockX = LABEL_WIDTH;
        selectedEntryIndex = -1;

        for (int i = 0; i < chain.getEntries().size(); i++) {
            ChainEntry entry = chain.getEntries().get(i);
            Phrase phrase = library.getPhrase(entry.getPhraseId());
            if (phrase == null) continue;
            int rowCount = Math.max(1, SmpsDecoder.decode(phrase.getDataDirect(), dialect).size());
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
            Chain chain = arrangement.getChain(channel);
            if (entryIndex >= chain.getEntries().size()) return;
            ChainEntry entry = chain.getEntries().get(entryIndex);
            Phrase phrase = arrangement.getPhraseLibrary().getPhrase(entry.getPhraseId());
            if (phrase == null) return;
            TextInputDialog dialog = new TextInputDialog(phrase.getName());
            dialog.setTitle("Rename Phrase");
            dialog.setHeaderText("Enter new name for phrase:");
            dialog.showAndWait().ifPresent(newName -> {
                if (!newName.isBlank()) {
                    phrase.setName(newName.trim());
                    refreshDisplay();
                    notifyEdited();
                }
            });
        });

        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(ev -> {
            Chain chain = arrangement.getChain(channel);
            if (entryIndex < chain.getEntries().size()) {
                chain.getEntries().remove(entryIndex);
                int loop = chain.getLoopEntryIndex();
                if (loop > entryIndex) {
                    chain.setLoopEntryIndex(loop - 1);
                } else if (loop >= chain.getEntries().size()) {
                    chain.setLoopEntryIndex(chain.getEntries().isEmpty() ? -1
                            : chain.getEntries().size() - 1);
                }
                selectedEntryIndex = -1;
                refreshDisplay();
                notifyEdited();
            }
        });

        menu.getItems().addAll(renameItem, deleteItem);
        menu.show(canvas, e.getScreenX(), e.getScreenY());
    }
}
