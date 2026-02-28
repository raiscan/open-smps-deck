package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.Song;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSongTabCoordinator {

    @Test
    void playFromCursorUsesExactOrderAndRow() {
        FakePlaybackGateway gateway = new FakePlaybackGateway();
        SongTabCoordinator coordinator = new SongTabCoordinator(gateway);
        Song song = new Song();

        coordinator.onPlayFromCursor(song, 3, 17);

        assertEquals(1, gateway.playFromPositionCalls);
        assertSame(song, gateway.lastPlayFromSong);
        assertEquals(3, gateway.lastPlayFromOrder);
        assertEquals(17, gateway.lastPlayFromRow);
    }

    @Test
    void togglePlaybackStopsWhenAlreadyRunning() {
        FakePlaybackGateway gateway = new FakePlaybackGateway();
        gateway.playing = true;
        SongTabCoordinator coordinator = new SongTabCoordinator(gateway);

        coordinator.onTogglePlayback(new Song());

        assertEquals(List.of("isPlaying", "stop"), gateway.events);
    }

    @Test
    void togglePlaybackLoadsAndStartsWhenStopped() {
        FakePlaybackGateway gateway = new FakePlaybackGateway();
        SongTabCoordinator coordinator = new SongTabCoordinator(gateway);
        Song song = new Song();

        coordinator.onTogglePlayback(song);

        assertEquals(List.of("isPlaying", "loadSong", "play"), gateway.events);
        assertSame(song, gateway.lastLoadedSong);
    }

    @Test
    void resolvePatternForOrderSelectionReturnsFirstChannelPattern() {
        Song song = new Song();
        int[] row = new int[Pattern.CHANNEL_COUNT];
        row[0] = 4;
        song.getOrderList().add(row);

        SongTabCoordinator coordinator = new SongTabCoordinator(new FakePlaybackGateway());
        assertEquals(4, coordinator.resolvePatternForOrderSelection(song, 1));
    }

    @Test
    void resolvePatternForOrderSelectionRejectsInvalidRows() {
        SongTabCoordinator coordinator = new SongTabCoordinator(new FakePlaybackGateway());
        Song song = new Song();

        assertEquals(-1, coordinator.resolvePatternForOrderSelection(song, -1));
        assertEquals(-1, coordinator.resolvePatternForOrderSelection(song, 99));
    }

    @Test
    void onSongEditedReloadsWhenPlaying() {
        FakePlaybackGateway gateway = new FakePlaybackGateway();
        gateway.playing = true;
        SongTabCoordinator coordinator = new SongTabCoordinator(gateway);
        Song song = new Song();

        coordinator.onSongEdited(song);

        assertEquals(List.of("isPlaying", "reload"), gateway.events,
                "Should check isPlaying then trigger reload when playing");
        assertSame(song, gateway.lastReloadedSong,
                "Should pass the edited song to reload");
    }

    @Test
    void onSongEditedDoesNothingWhenStopped() {
        FakePlaybackGateway gateway = new FakePlaybackGateway();
        gateway.playing = false;
        SongTabCoordinator coordinator = new SongTabCoordinator(gateway);

        coordinator.onSongEdited(new Song());

        assertEquals(List.of("isPlaying"), gateway.events,
                "When stopped, should only check isPlaying and do nothing else");
    }

    @Test
    void updatePlaybackCursorNotifiesListenerWhenPlaying() {
        FakePlaybackGateway gateway = new FakePlaybackGateway();
        gateway.playing = true;
        gateway.setFakePosition(new PlaybackEngine.PlaybackPosition(2, 5));
        SongTabCoordinator coordinator = new SongTabCoordinator(gateway);

        int[] notifiedOrder = {-1};
        int[] notifiedRow = {-1};
        coordinator.setPlaybackCursorListener(new SongTabCoordinator.PlaybackCursorListener() {
            @Override
            public void onPlaybackCursorMoved(int orderRow, int patternRow) {
                notifiedOrder[0] = orderRow;
                notifiedRow[0] = patternRow;
            }
            @Override
            public void onPlaybackCursorCleared() {}
        });

        coordinator.updatePlaybackCursor();

        assertEquals(2, notifiedOrder[0]);
        assertEquals(5, notifiedRow[0]);
    }

    @Test
    void updatePlaybackCursorClearsWhenNotPlaying() {
        FakePlaybackGateway gateway = new FakePlaybackGateway();
        gateway.playing = false;
        SongTabCoordinator coordinator = new SongTabCoordinator(gateway);

        boolean[] cleared = {false};
        coordinator.setPlaybackCursorListener(new SongTabCoordinator.PlaybackCursorListener() {
            @Override
            public void onPlaybackCursorMoved(int orderRow, int patternRow) {}
            @Override
            public void onPlaybackCursorCleared() {
                cleared[0] = true;
            }
        });

        coordinator.updatePlaybackCursor();

        assertTrue(cleared[0], "Should clear playback cursor when not playing");
    }

    private static final class FakePlaybackGateway implements SongTabCoordinator.PlaybackGateway {
        private final List<String> events = new ArrayList<>();
        private boolean playing;
        private Song lastLoadedSong;
        private Song lastReloadedSong;
        private Song lastPlayFromSong;
        private int lastPlayFromOrder = -1;
        private int lastPlayFromRow = -1;
        private int playFromPositionCalls;
        private PlaybackEngine.PlaybackPosition fakePosition;

        void setFakePosition(PlaybackEngine.PlaybackPosition pos) {
            this.fakePosition = pos;
        }

        @Override
        public boolean isPlaying() {
            events.add("isPlaying");
            return playing;
        }

        @Override
        public void stop() {
            events.add("stop");
        }

        @Override
        public void loadSong(Song song) {
            events.add("loadSong");
            lastLoadedSong = song;
        }

        @Override
        public void play() {
            events.add("play");
        }

        @Override
        public void reload(Song song) {
            events.add("reload");
            lastReloadedSong = song;
        }

        @Override
        public void playFromPosition(Song song, int orderIndex, int rowIndex) {
            playFromPositionCalls++;
            lastPlayFromSong = song;
            lastPlayFromOrder = orderIndex;
            lastPlayFromRow = rowIndex;
        }

        @Override
        public PlaybackEngine.PlaybackPosition getPlaybackPosition() {
            events.add("getPlaybackPosition");
            return fakePosition;
        }
    }
}
