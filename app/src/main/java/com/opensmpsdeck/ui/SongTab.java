package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.ArrangementMode;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.Song;
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

    // Hierarchical mode components
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
            // Refresh SongView and ChainStrip on every edit so phrase blocks update
            if (songView != null) songView.refreshDisplay();
            if (chainStrip != null) chainStrip.rebuild();
        };
        trackerGrid.setOnDirty(dirtyAndEdited);
        instrumentPanel.setOnDirty(dirtyAndEdited);
        orderListPanel.setOnDirty(dirtyAndEdited);

        buildHierarchicalComponents();
    }

    private void buildHierarchicalComponents() {
        songView = new SongView();
        chainStrip = new ChainStrip();
        breadcrumbBar = new BreadcrumbBar();

        if (song.getHierarchicalArrangement() != null) {
            var arr = song.getHierarchicalArrangement();
            songView.setArrangement(arr);

            // Default: show first channel's chain
            chainStrip.setChain(arr.getChain(0), arr.getPhraseLibrary());
            breadcrumbBar.push("FM1 Chain", 0);

            // SongView phrase selection → update ChainStrip and return to chain view
            songView.setOnPhraseSelected(phraseId -> {
                int ch = songView.getSelectedChannel();
                chainStrip.setChain(arr.getChain(ch), arr.getPhraseLibrary());
                String chName = channelDisplayName(ch);
                breadcrumbBar.setCrumbs(java.util.List.of(
                    new BreadcrumbBar.Crumb(chName + " Chain", 0)
                ));
                // Return to pattern view when switching channels
                trackerGrid.clearPhrase();
            });

            // SongView double-click → navigate into phrase
            songView.setOnPhraseDoubleClicked((ch, entryIndex) -> {
                navigateToPhrase(arr, ch, entryIndex);
            });

            // ChainStrip entry selection → navigate into phrase
            chainStrip.setOnEntrySelected(phraseId -> {
                int ch = songView.getSelectedChannel();
                Phrase phrase = arr.getPhraseLibrary().getPhrase(phraseId);
                if (phrase != null) {
                    trackerGrid.setPhrase(phrase, ch);
                    breadcrumbBar.push(phrase.getName(), 1);
                }
            });

            // ChainStrip right-click → open ChainEditor
            chainStrip.setOnContextMenuRequested(event -> {
                int ch = songView.getSelectedChannel();
                String chName = channelDisplayName(ch);
                ChainEditor editor = new ChainEditor(arr.getChain(ch), arr.getPhraseLibrary(), chName);
                editor.showAndWait().ifPresent(result -> {
                    if (result == javafx.scene.control.ButtonType.OK) {
                        chainStrip.setChain(arr.getChain(ch), arr.getPhraseLibrary());
                        songView.refreshDisplay();
                        setDirty(true);
                        if (onEdited != null) onEdited.run();
                    }
                });
            });

            // BreadcrumbBar navigation
            breadcrumbBar.setOnNavigate(depth -> {
                if (depth == 0) {
                    // Back to chain level: clear phrase from grid
                    trackerGrid.clearPhrase();
                }
            });
        }
    }

    private void navigateToPhrase(com.opensmpsdeck.model.HierarchicalArrangement arr, int ch, int entryIndex) {
        var chain = arr.getChain(ch);
        if (entryIndex < 0 || entryIndex >= chain.getEntries().size()) return;
        var entry = chain.getEntries().get(entryIndex);
        Phrase phrase = arr.getPhraseLibrary().getPhrase(entry.getPhraseId());
        if (phrase != null) {
            trackerGrid.setPhrase(phrase, ch);
            String chName = channelDisplayName(ch);
            breadcrumbBar.setCrumbs(java.util.List.of(
                new BreadcrumbBar.Crumb(chName + " Chain", 0),
                new BreadcrumbBar.Crumb(phrase.getName(), 1)
            ));
        }
    }

    private static String channelDisplayName(int ch) {
        if (ch == 5) return "DAC";
        if (ch < 5) return "FM" + (ch + 1);
        if (ch < 9) return "PSG" + (ch - 5);
        return "Noise";
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
