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

import java.util.HashMap;
import java.util.Map;

/**
 * Coordinates song compilation and playback.
 * Compiles Song -> SMPS binary -> SmpsSequencer -> SmpsDriver -> AudioOutput.
 */
public class PlaybackEngine {

    /** Resolved playback position in order list / pattern row coordinates. */
    public record PlaybackPosition(int orderIndex, int rowIndex) {}

    private final SmpsDriver driver;
    private final PatternCompiler compiler;
    private final PlaybackSliceBuilder playbackSliceBuilder;
    private AudioOutput audioOutput;
    private SmpsSequencer currentSequencer;
    private PatternCompiler.CompilationResult compilationResult;
    private int baseOrderIndex;

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

    /** Reload song (recompile + restart from beginning). */
    public void reload(Song song) {
        loadSong(song);
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
     * Returns the underlying synth driver.
     */
    public SmpsDriver getDriver() { return driver; }

    /**
     * Returns true when the real-time audio output thread is running.
     */
    public boolean isPlaying() { return audioOutput != null && audioOutput.isRunning(); }

    /**
     * Maps the sequencer's current byte position to order/row coordinates.
     *
     * <p>Returns {@code null} when no song is loaded, or when the active
     * track position cannot be resolved (e.g. the sequencer has not yet
     * started ticking).
     *
     * <p>The returned {@code orderIndex} is adjusted by {@code baseOrderIndex}
     * so that play-from-cursor slices report positions relative to the
     * original song order list.
     */
    public PlaybackPosition getPlaybackPosition() {
        if (compilationResult == null) return null;

        // Collect all active sequencer byte positions.  The sequencer assigns
        // hardware channel IDs using fmChannelOrder which may re-map the
        // compiler's channel ordering (e.g. the first FM slot becomes DAC in
        // S2 mode).  We query every track type and resolve each position
        // against the timeline whose trackOffset matches.
        int pos = findFirstActivePosition();
        if (pos < 0) return null;

        PatternCompiler.CursorPosition cursor = resolveFromMatchingTimeline(pos);
        if (cursor == null) return null;
        return new PlaybackPosition(
                cursor.orderIndex() + baseOrderIndex,
                cursor.rowIndex());
    }

    /**
     * Returns the byte position of the first active sequencer track,
     * trying DAC first, then FM channels 0-5, then PSG channels 0-3.
     * Returns {@code -1} if no active track is found.
     */
    private int findFirstActivePosition() {
        int pos = driver.getTrackPosition(SmpsSequencer.TrackType.DAC, 5);
        if (pos >= 0) return pos;
        for (int ch = 0; ch < 6; ch++) {
            pos = driver.getTrackPosition(SmpsSequencer.TrackType.FM, ch);
            if (pos >= 0) return pos;
        }
        for (int ch = 0; ch < 4; ch++) {
            pos = driver.getTrackPosition(SmpsSequencer.TrackType.PSG, ch);
            if (pos >= 0) return pos;
        }
        return -1;
    }

    /**
     * Resolves a sequencer byte position against the timeline whose
     * track offset range contains it.  Each channel timeline knows
     * its trackOffset; the position must fall at or after it to be
     * a valid match.
     */
    private PatternCompiler.CursorPosition resolveFromMatchingTimeline(int absolutePos) {
        for (int ch = 0; ch < 10; ch++) {
            PatternCompiler.ChannelTimeline timeline =
                    compilationResult.getChannelTimeline(ch);
            if (timeline == null || timeline.getRowCount() == 0) continue;
            // Only resolve against a timeline whose track contains this position
            if (absolutePos >= timeline.getTrackOffset()) {
                PatternCompiler.CursorPosition cursor = timeline.resolvePosition(absolutePos);
                if (cursor != null) return cursor;
            }
        }
        return null;
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
