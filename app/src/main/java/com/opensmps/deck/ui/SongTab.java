package com.opensmps.deck.ui;

import com.opensmps.deck.model.ArrangementMode;
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
    private Runnable onDirtyChanged;
    private Runnable onEdited;

    private TrackerGrid trackerGrid;
    private OrderListPanel orderListPanel;
    private InstrumentPanel instrumentPanel;

    // Hierarchical mode components (null when in LEGACY_PATTERNS mode)
    private SongView songView;
    private ChainStrip chainStrip;
    private BreadcrumbBar breadcrumbBar;

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

    /**
     * Sets the dirty flag for this tab and notifies listeners on change.
     */
    public void setDirty(boolean dirty) {
        if (this.dirty == dirty) return;
        this.dirty = dirty;
        if (onDirtyChanged != null) {
            onDirtyChanged.run();
        }
    }

    /**
     * Sets a callback invoked whenever the dirty state changes.
     */
    public void setOnDirtyChanged(Runnable callback) {
        this.onDirtyChanged = callback;
    }

    /**
     * Sets a callback invoked on every song edit. Unlike {@link #setOnDirtyChanged},
     * this fires on every edit even when the tab is already dirty, so that
     * playback can recompile the song in real-time.
     */
    public void setOnEdited(Runnable callback) {
        this.onEdited = callback;
    }

    public TrackerGrid getTrackerGrid() { return trackerGrid; }
    public OrderListPanel getOrderListPanel() { return orderListPanel; }
    public InstrumentPanel getInstrumentPanel() { return instrumentPanel; }
    public SongView getSongView() { return songView; }
    public ChainStrip getChainStrip() { return chainStrip; }
    public BreadcrumbBar getBreadcrumbBar() { return breadcrumbBar; }

    public boolean isHierarchical() {
        return song.getArrangementMode() == ArrangementMode.HIERARCHICAL;
    }

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

        Runnable dirtyAndEdited = () -> {
            setDirty(true);
            if (onEdited != null) onEdited.run();
        };
        trackerGrid.setOnDirty(dirtyAndEdited);
        instrumentPanel.setOnDirty(dirtyAndEdited);
        orderListPanel.setOnDirty(dirtyAndEdited);

        if (isHierarchical()) {
            buildHierarchicalComponents();
        }
    }

    private void buildHierarchicalComponents() {
        songView = new SongView();
        chainStrip = new ChainStrip();
        breadcrumbBar = new BreadcrumbBar();

        if (song.getHierarchicalArrangement() != null) {
            songView.setArrangement(song.getHierarchicalArrangement());

            // Default: show first channel's chain
            var arr = song.getHierarchicalArrangement();
            var chain = arr.getChain(0);
            chainStrip.setChain(chain, arr.getPhraseLibrary());
            breadcrumbBar.push("FM1 Chain", 0);

            // SongView phrase selection → update ChainStrip
            songView.setOnPhraseSelected(phraseId -> {
                int ch = songView.getSelectedChannel();
                chainStrip.setChain(arr.getChain(ch), arr.getPhraseLibrary());
                String chName = ch < 6 ? "FM" + (ch + 1) : ch < 9 ? "PSG" + (ch - 5) : "Noise";
                if (ch == 5) chName = "DAC";
                breadcrumbBar.setCrumbs(java.util.List.of(
                    new BreadcrumbBar.Crumb(chName + " Chain", 0)
                ));
            });

            // SongView double-click → navigate to phrase
            songView.setOnPhraseDoubleClicked((ch, entryIndex) -> {
                var entry = arr.getChain(ch).getEntries().get(entryIndex);
                var phrase = arr.getPhraseLibrary().getPhrase(entry.getPhraseId());
                if (phrase != null) {
                    breadcrumbBar.push(phrase.getName(), 1);
                }
            });

            // ChainStrip entry selection
            chainStrip.setOnEntrySelected(phraseId -> {
                var phrase = arr.getPhraseLibrary().getPhrase(phraseId);
                if (phrase != null) {
                    breadcrumbBar.push(phrase.getName(), 1);
                }
            });

            // BreadcrumbBar navigation
            breadcrumbBar.setOnNavigate(depth -> {
                // depth 0 = chain level, depth 1 = phrase level
            });
        }
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
        if (songView != null && song.getHierarchicalArrangement() != null) {
            songView.setArrangement(song.getHierarchicalArrangement());
        }
    }
}
