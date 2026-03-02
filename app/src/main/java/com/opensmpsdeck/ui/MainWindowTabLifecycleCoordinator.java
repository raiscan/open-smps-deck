package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.Song;

/**
 * Coordinates tab-selection lifecycle behavior for {@link MainWindow}.
 */
final class MainWindowTabLifecycleCoordinator {

    interface Callbacks {
        void createNewTab();
        void activateSong(Song song);
        void updateTitle();
    }

    /**
     * Handles selection changes in the tab strip.
     *
     * <p>If the "+" tab is selected, requests a new tab. Otherwise, for a valid
     * active song tab, updates transport binding and title.
     */
    void onTabSelectionChanged(String selectedTabText,
                               Object selectedTabUserData,
                               SongTab activeSongTab,
                               Callbacks callbacks) {
        if ("+".equals(selectedTabText) && selectedTabUserData == null) {
            callbacks.createNewTab();
            return;
        }
        if (activeSongTab != null) {
            callbacks.activateSong(activeSongTab.getSong());
            callbacks.updateTitle();
        }
    }
}
