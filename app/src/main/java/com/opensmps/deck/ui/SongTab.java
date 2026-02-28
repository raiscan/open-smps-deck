package com.opensmps.deck.ui;

import com.opensmps.deck.model.Song;
import java.io.File;

/**
 * Represents a single song editor tab. Holds the Song model, file reference,
 * dirty flag, and the UI components for this editor context.
 *
 * <p>UI components are lazily created when {@link #buildContent()} is called,
 * allowing the model to be tested without JavaFX.
 */
public class SongTab {

    private Song song;
    private File file;
    private boolean dirty;

    private TrackerGrid trackerGrid;
    private OrderListPanel orderListPanel;
    private InstrumentPanel instrumentPanel;

    public SongTab() {
        this(new Song());
    }

    public SongTab(Song song) {
        this.song = song;
    }

    public Song getSong() { return song; }
    public void setSong(Song song) { this.song = song; }

    public File getFile() { return file; }
    public void setFile(File file) { this.file = file; }

    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }

    public TrackerGrid getTrackerGrid() { return trackerGrid; }
    public OrderListPanel getOrderListPanel() { return orderListPanel; }
    public InstrumentPanel getInstrumentPanel() { return instrumentPanel; }

    /**
     * Returns the display title for this tab.
     * Shows the file name if saved, otherwise the song name. Appends " *" if dirty.
     */
    public String getTitle() {
        String name = file != null ? file.getName() : song.getName();
        return dirty ? name + " *" : name;
    }

    /**
     * Build the JavaFX UI components for this tab.
     * Must be called on the JavaFX application thread.
     */
    public void buildContent() {
        trackerGrid = new TrackerGrid();
        trackerGrid.setSong(song);
        orderListPanel = new OrderListPanel(song);
        instrumentPanel = new InstrumentPanel(song);
        trackerGrid.setInstrumentPanel(instrumentPanel);
    }

    /**
     * Refresh all UI panels from the current Song model.
     */
    public void refreshAllPanels() {
        if (trackerGrid != null) {
            trackerGrid.setSong(song);
            orderListPanel.setSong(song);
            instrumentPanel.setSong(song);
        }
    }
}
