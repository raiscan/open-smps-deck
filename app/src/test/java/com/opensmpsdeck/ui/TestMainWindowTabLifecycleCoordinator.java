package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestMainWindowTabLifecycleCoordinator {

    @Test
    void selectingPlusTabRequestsNewTab() {
        MainWindowTabLifecycleCoordinator coordinator = new MainWindowTabLifecycleCoordinator();
        RecordingCallbacks callbacks = new RecordingCallbacks();

        coordinator.onTabSelectionChanged("+", null, null, callbacks);

        assertEquals(List.of("createNewTab"), callbacks.events);
    }

    @Test
    void selectingSongTabActivatesTransportAndTitle() {
        MainWindowTabLifecycleCoordinator coordinator = new MainWindowTabLifecycleCoordinator();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        SongTab active = new SongTab();

        coordinator.onTabSelectionChanged("Song 1", active, active, callbacks);

        assertEquals(List.of("activateSong", "updateTitle"), callbacks.events);
        assertSame(active.getSong(), callbacks.lastActivatedSong);
    }

    @Test
    void selectingSongTabWithNoActiveTabDoesNothing() {
        MainWindowTabLifecycleCoordinator coordinator = new MainWindowTabLifecycleCoordinator();
        RecordingCallbacks callbacks = new RecordingCallbacks();

        coordinator.onTabSelectionChanged("Song 1", new Object(), null, callbacks);

        assertEquals(List.of(), callbacks.events);
    }

    private static final class RecordingCallbacks implements MainWindowTabLifecycleCoordinator.Callbacks {
        private final List<String> events = new ArrayList<>();
        private Song lastActivatedSong;

        @Override
        public void createNewTab() {
            events.add("createNewTab");
        }

        @Override
        public void activateSong(Song song) {
            events.add("activateSong");
            lastActivatedSong = song;
        }

        @Override
        public void updateTitle() {
            events.add("updateTitle");
        }
    }
}
