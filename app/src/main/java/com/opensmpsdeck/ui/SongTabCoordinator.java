package com.opensmpsdeck.ui;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.model.Song;

/**
 * Coordinates non-file song-tab interactions used by {@link MainWindow}.
 *
 * <p>This class keeps playback shortcut behavior and order-row routing out of
 * the window shell so those rules are unit-testable without JavaFX setup.
 */
final class SongTabCoordinator {

    /**
     * Playback abstraction used for unit-testable tab interaction logic.
     */
    interface PlaybackGateway {
        boolean isPlaying();
        void stop();
        void loadSong(Song song);
        void play();
        void reload(Song song);
        void playFromPosition(Song song, int orderIndex, int rowIndex);
        PlaybackEngine.PlaybackPosition getPlaybackPosition();
        void setChannelMute(int channel, boolean muted);
    }

    /**
     * Abstraction for querying channel mute state, allowing unit tests to
     * supply a fake without requiring JavaFX or a real TrackerGrid.
     */
    interface MuteStateProvider {
        boolean isChannelMuted(int channel);
    }

    /**
     * Callback for playback cursor position changes, used by the UI to
     * highlight the currently playing row.
     */
    interface PlaybackCursorListener {
        void onPlaybackCursorMoved(int orderRow, int patternRow);
        void onPlaybackCursorCleared();
    }

    private final PlaybackGateway playback;
    private PlaybackCursorListener cursorListener;
    private MuteStateProvider muteStateProvider;

    SongTabCoordinator(PlaybackGateway playback) {
        this.playback = playback;
    }

    void setPlaybackCursorListener(PlaybackCursorListener listener) {
        this.cursorListener = listener;
    }

    void setMuteStateProvider(MuteStateProvider provider) {
        this.muteStateProvider = provider;
    }

    /**
     * Called periodically (~15 Hz) during playback to update cursor position.
     * Should be called from the JavaFX application thread.
     */
    void updatePlaybackCursor() {
        if (!playback.isPlaying()) {
            if (cursorListener != null) {
                cursorListener.onPlaybackCursorCleared();
            }
            return;
        }
        PlaybackEngine.PlaybackPosition pos = playback.getPlaybackPosition();
        if (pos != null && cursorListener != null) {
            cursorListener.onPlaybackCursorMoved(pos.orderIndex(), pos.rowIndex());
        }
    }

    /**
     * Handles tracker play/pause toggle semantics.
     */
    void onTogglePlayback(Song song) {
        if (playback.isPlaying()) {
            playback.stop();
            return;
        }
        playback.loadSong(song);
        playback.play();
    }

    /**
     * Handles tracker stop shortcut.
     */
    void onStopPlayback() {
        playback.stop();
    }

    /**
     * Handles tracker "play from cursor" shortcut.
     */
    void onPlayFromCursor(Song song, int orderIndex, int rowIndex) {
        playback.playFromPosition(song, orderIndex, rowIndex);
    }

    /**
     * Called when the song model is edited. If currently playing, reloads
     * the song to reflect changes while preserving the playback position.
     * Any active mute/solo state is snapshotted before reload and restored
     * afterwards, since reload creates a fresh sequencer with all channels
     * unmuted.
     */
    void onSongEdited(Song song) {
        if (playback.isPlaying()) {
            // Snapshot mute state before reload
            boolean[] muteSnapshot = null;
            if (muteStateProvider != null) {
                muteSnapshot = new boolean[10];
                for (int ch = 0; ch < 10; ch++) {
                    muteSnapshot[ch] = muteStateProvider.isChannelMuted(ch);
                }
            }

            playback.reload(song);

            // Restore mute state after reload
            if (muteSnapshot != null) {
                for (int ch = 0; ch < 10; ch++) {
                    playback.setChannelMute(ch, muteSnapshot[ch]);
                }
            }
        }
    }

    /**
     * Resolves the first-channel pattern index for a selected order row.
     *
     * @return pattern index to display, or {@code -1} when selection is invalid
     */
    int resolvePatternForOrderSelection(Song song, int rowIndex) {
        if (song == null || song.getOrderList().isEmpty()) {
            return -1;
        }
        if (rowIndex < 0 || rowIndex >= song.getOrderList().size()) {
            return -1;
        }
        return song.getOrderList().get(rowIndex)[0];
    }
}
