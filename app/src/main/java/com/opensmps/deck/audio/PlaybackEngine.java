package com.opensmps.deck.audio;

import com.opensmps.deck.codec.PatternCompiler;
import com.opensmps.deck.model.DacSample;
import com.opensmps.deck.model.Song;
import com.opensmps.deck.model.SmpsMode;
import com.opensmps.driver.AudioOutput;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.DacData;
import com.opensmps.smps.SmpsSequencer;
import com.opensmps.smps.SmpsSequencerConfig;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Coordinates song compilation and playback.
 * Compiles Song -> SMPS binary -> SmpsSequencer -> SmpsDriver -> AudioOutput.
 */
public class PlaybackEngine {

    /** Resolved playback position in order list / pattern row coordinates. */
    public record PlaybackPosition(int orderIndex, int rowIndex) {}
    private record ResolvedTrackCursor(int channel, PatternCompiler.CursorPosition cursor) {}

    private final SmpsDriver driver;
    private final PatternCompiler compiler;
    private final PlaybackSliceBuilder playbackSliceBuilder;
    private AudioOutput audioOutput;
    private SmpsSequencer currentSequencer;
    private volatile PatternCompiler.CompilationResult compilationResult;
    private int baseOrderIndex; // only accessed from UI thread
    private PatternCompiler.CursorPosition lastResolvedCursor;

    /**
     * Create a playback engine with a 44.1 kHz driver and default compiler.
     */
    public PlaybackEngine() {
        this.driver = new SmpsDriver(44100.0);
        this.compiler = new PatternCompiler();
        this.playbackSliceBuilder = new PlaybackSliceBuilder();
    }

    /** Compile song and load into sequencer. Does NOT start playback. */
    public void loadSong(Song song) {
        baseOrderIndex = 0;
        loadSongImpl(song);
    }

    /**
     * Internal: compile and load a song (or playback slice) into the sequencer.
     * Stores the {@link PatternCompiler.CompilationResult} for position tracking.
     */
    private void loadSongImpl(Song song) {
        driver.stopAll();
        lastResolvedCursor = null;

        PatternCompiler.CompilationResult result = compiler.compileDetailed(song);
        this.compilationResult = result;
        byte[] smps = result.getSmpsDataUnsafe();

        int baseNoteOffset = switch (song.getSmpsMode()) {
            case S1 -> 0;
            case S2 -> 1;
            case S3K -> 0;
        };
        SimpleSmpsData data = new SimpleSmpsData(smps, baseNoteOffset);

        // Convert PSG envelopes from Song model to byte[][] for SmpsData
        if (!song.getPsgEnvelopes().isEmpty()) {
            byte[][] envs = new byte[song.getPsgEnvelopes().size()][];
            for (int i = 0; i < envs.length; i++) {
                envs[i] = song.getPsgEnvelopes().get(i).getData();
            }
            data.setPsgEnvelopes(envs);
        }

        DacData dacData = null;
        // Wire DAC samples from Song model into the driver's synthesizer
        if (!song.getDacSamples().isEmpty()) {
            Map<Integer, byte[]> sampleBank = new HashMap<>();
            Map<Integer, DacData.DacEntry> mapping = new HashMap<>();
            for (int i = 0; i < song.getDacSamples().size(); i++) {
                DacSample dac = song.getDacSamples().get(i);
                int sampleId = i;
                sampleBank.put(sampleId, dac.getDataDirect());
                mapping.put(0x81 + i, new DacData.DacEntry(sampleId, dac.getRate()));
            }
            int baseCycles = switch (song.getSmpsMode()) {
                case S1 -> 301;
                case S2 -> 288;
                case S3K -> 297;
            };
            dacData = new DacData(sampleBank, mapping, baseCycles);
        }

        SmpsSequencerConfig config = buildConfig(song.getSmpsMode());
        currentSequencer = new SmpsSequencer(data, dacData, driver, config);
        driver.addSequencer(currentSequencer, false);
    }

    /** Render samples to buffer without audio device (headless). */
    public int renderBuffer(short[] buffer) {
        return driver.read(buffer);
    }

    /** Start audio output (creates AudioOutput if needed). */
    public void play() {
        if (audioOutput == null) {
            audioOutput = new AudioOutput(driver);
        }
        audioOutput.start();
    }

    /** Stop playback and silence all channels. */
    public void stop() {
        if (audioOutput != null) audioOutput.stop();
        driver.stopAll();
        driver.silenceAll();
        lastResolvedCursor = null;
    }

    /** Pause playback (audio thread sleeps). */
    public void pause() {
        if (audioOutput != null) audioOutput.pause();
    }

    /** Resume from pause. */
    public void resume() {
        if (audioOutput != null) audioOutput.resume();
    }

    /**
     * Compile and start playback from a specific position in the order list.
     * This recompiles the song and starts from the selected order row.
     *
     * @param song the song to play
     * @param orderIndex the order list row to start from (0-based)
     */
    public void playFromOrder(Song song, int orderIndex) {
        playFromPosition(song, orderIndex, 0);
    }

    /**
     * Compile and start playback from a specific order row and pattern row.
     *
     * @param song the song to play
     * @param orderIndex the order list row to start from (0-based)
     * @param rowIndex the row inside the selected order row to start from (0-based)
     */
    public void playFromPosition(Song song, int orderIndex, int rowIndex) {
        baseOrderIndex = orderIndex;
        Song slice = createPlaybackSlice(song, orderIndex, rowIndex);
        loadSongImpl(slice);
        if (audioOutput == null) {
            audioOutput = new AudioOutput(driver);
        }
        audioOutput.start();
    }

    /** Reload song: recompile and seek to current order position. Resumes playback if active. */
    public void reload(Song song) {
        PlaybackPosition pos = getPlaybackPosition();
        boolean wasPlaying = isPlaying();

        if (wasPlaying && audioOutput != null) {
            audioOutput.stop();
        }

        if (pos != null) {
            baseOrderIndex = pos.orderIndex();
            loadSongImpl(createPlaybackSlice(song, pos.orderIndex(), pos.rowIndex()));
        } else {
            loadSong(song);
        }

        if (wasPlaying) {
            play();
        }
    }

    /** Set FM channel mute. */
    public void setFmMute(int channel, boolean muted) {
        driver.setFmMute(channel, muted);
    }

    /** Set PSG channel mute. */
    public void setPsgMute(int channel, boolean muted) {
        driver.setPsgMute(channel, muted);
    }

    /**
     * Mute or unmute a channel by unified index (0-5 = FM, 6-9 = PSG).
     */
    public void setChannelMute(int channel, boolean muted) {
        if (channel < 6) {
            setFmMute(channel, muted);
        } else {
            setPsgMute(channel - 6, muted);
        }
    }

    /**
     * Returns the underlying synth driver.
     */
    public SmpsDriver getDriver() { return driver; }

    /**
     * Returns true when the real-time audio output thread is running.
     */
    public boolean isPlaying() { return audioOutput != null && audioOutput.isRunning(); }

    /**
     * Returns the current playback position in terms of the original song's
     * order list and pattern row, or {@code null} if no song is loaded.
     *
     * <p>Returns {@code null} when no song is loaded, or when the active
     * track position cannot be resolved (e.g. the sequencer has not yet
     * started ticking).
     *
     * <p>The returned {@code orderIndex} is adjusted by {@code baseOrderIndex}
     * so that play-from-cursor slices report positions relative to the
     * original song order list.
     *
     * <p>Must be called from the JavaFX application thread (or the same
     * thread that calls {@link #reload} and {@link #loadSong}).  The read
     * of {@code compilationResult} and the subsequent track-position query
     * are not atomic, so calling from a different thread could pair a stale
     * compilation result with the current sequencer state.
     */
    public PlaybackPosition getPlaybackPosition() {
        if (compilationResult == null) return null;

        // Resolve all active tracks, then pick the nearest forward cursor.
        // This keeps motion monotonic without letting the fastest channel
        // drag the global cursor too far ahead of the rest.
        List<ResolvedTrackCursor> candidates = collectResolvedTrackCursors();
        if (candidates.isEmpty()) return null;

        PatternCompiler.CursorPosition cursor = chooseGlobalCursor(candidates);
        if (cursor == null) return null;
        lastResolvedCursor = cursor;
        return new PlaybackPosition(
                cursor.orderIndex() + baseOrderIndex,
                cursor.rowIndex());
    }

    /**
     * Returns per-channel pattern row indices for currently active tracks.
     * Inactive or unresolved channels return {@code -1}.
     */
    public int[] getChannelPlaybackRows() {
        int[] rows = new int[10];
        Arrays.fill(rows, -1);
        if (compilationResult == null) {
            return rows;
        }
        for (ResolvedTrackCursor resolved : collectResolvedTrackCursors()) {
            rows[resolved.channel()] = resolved.cursor().rowIndex();
        }
        return rows;
    }

    private List<ResolvedTrackCursor> collectResolvedTrackCursors() {
        List<ResolvedTrackCursor> out = new ArrayList<>(10);

        addResolvedCursor(out, driver.getTrackRuntimeState(SmpsSequencer.TrackType.DAC, 5));
        for (int fm = 0; fm < 6; fm++) {
            addResolvedCursor(out, driver.getTrackRuntimeState(SmpsSequencer.TrackType.FM, fm));
        }
        for (int psg = 0; psg < 4; psg++) {
            addResolvedCursor(out, driver.getTrackRuntimeState(SmpsSequencer.TrackType.PSG, psg));
        }
        return out;
    }

    private void addResolvedCursor(List<ResolvedTrackCursor> out,
                                   SmpsSequencer.TrackRuntimeState state) {
        ResolvedTrackCursor resolved = resolveFromMatchingTimeline(state);
        if (resolved != null) {
            out.add(resolved);
        }
    }

    private ResolvedTrackCursor resolveFromMatchingTimeline(SmpsSequencer.TrackRuntimeState state) {
        if (state == null) return null;

        // While a row is still counting down, the sequencer position points at the
        // next unread byte, not the current row start. Look back one byte so we
        // resolve to the row that is still sounding (including rests).
        int effectivePos = (state.remainingDuration() > 0)
                ? Math.max(0, state.position() - 1)
                : state.position();

        PatternCompiler.ChannelTimeline best = null;
        for (int ch = 0; ch < 10; ch++) {
            PatternCompiler.ChannelTimeline timeline = compilationResult.getChannelTimeline(ch);
            if (timeline == null || timeline.getRowCount() == 0) continue;
            if (effectivePos >= timeline.getTrackOffset()) {
                if (best == null || timeline.getTrackOffset() > best.getTrackOffset()) {
                    best = timeline;
                }
            }
        }
        if (best == null) return null;
        PatternCompiler.CursorPosition cursor = best.resolvePosition(effectivePos);
        if (cursor == null) return null;
        return new ResolvedTrackCursor(best.getChannel(), cursor);
    }

    private PatternCompiler.CursorPosition chooseGlobalCursor(List<ResolvedTrackCursor> candidates) {
        if (candidates.isEmpty()) return null;
        if (lastResolvedCursor == null) {
            PatternCompiler.CursorPosition earliest = candidates.get(0).cursor();
            for (int i = 1; i < candidates.size(); i++) {
                PatternCompiler.CursorPosition candidate = candidates.get(i).cursor();
                if (compareCursor(candidate, earliest) < 0) {
                    earliest = candidate;
                }
            }
            return earliest;
        }

        PatternCompiler.CursorPosition nearestForward = null;
        boolean hasEqual = false;
        for (ResolvedTrackCursor resolved : candidates) {
            PatternCompiler.CursorPosition candidate = resolved.cursor();
            int cmp = compareCursor(candidate, lastResolvedCursor);
            if (cmp > 0) {
                if (nearestForward == null || compareCursor(candidate, nearestForward) < 0) {
                    nearestForward = candidate;
                }
            } else if (cmp == 0) {
                hasEqual = true;
            }
        }
        if (nearestForward != null) return nearestForward;
        if (hasEqual) return lastResolvedCursor;
        return lastResolvedCursor;
    }

    private int compareCursor(PatternCompiler.CursorPosition a, PatternCompiler.CursorPosition b) {
        if (a.orderIndex() != b.orderIndex()) {
            return Integer.compare(a.orderIndex(), b.orderIndex());
        }
        return Integer.compare(a.rowIndex(), b.rowIndex());
    }

    /**
     * Creates a playback-ready slice that starts at the requested order row.
     *
     * <p>For order index 0 (or out-of-range values), returns the original song.
     * For positive in-range indices, returns a deep copy with the order list
     * trimmed from {@code orderIndex} to end and a loop point adjusted to the new
     * order space.
     *
     * <p>If {@code rowIndex > 0}, the first order row is rewritten to a synthesized
     * entry pattern where each channel is trimmed to start at that row. Later order
     * rows still reference their original patterns.
     *
     * <p>Package-private for unit tests.
     */
    Song createPlaybackSlice(Song song, int orderIndex, int rowIndex) {
        return playbackSliceBuilder.createPlaybackSlice(song, orderIndex, rowIndex);
    }

    private SmpsSequencerConfig buildConfig(SmpsMode mode) {
        return switch (mode) {
            case S2 -> new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();
            case S1 -> new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .relativePointers(true)
                .tempoOnFirstTick(true)
                .build();
            case S3K -> new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .tempoOnFirstTick(true)
                .build();
        };
    }
}
