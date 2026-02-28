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

    private final SmpsDriver driver;
    private final PatternCompiler compiler;
    private AudioOutput audioOutput;
    private SmpsSequencer currentSequencer;

    public PlaybackEngine() {
        this.driver = new SmpsDriver(44100.0);
        this.compiler = new PatternCompiler();
    }

    /** Compile song and load into sequencer. Does NOT start playback. */
    public void loadSong(Song song) {
        driver.stopAll();

        byte[] smps = compiler.compile(song);
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
            driver.setDacData(new DacData(sampleBank, mapping, baseCycles));
        }

        SmpsSequencerConfig config = buildConfig(song.getSmpsMode());
        currentSequencer = new SmpsSequencer(data, null, driver, config);
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
     * This recompiles the song and seeks to the given order index.
     *
     * @param song the song to play
     * @param orderIndex the order list row to start from (0-based)
     */
    public void playFromOrder(Song song, int orderIndex) {
        loadSong(song);
        // The sequencer starts from the beginning; for now, just load and play.
        // A full seek implementation would need SmpsSequencer support.
        // For MVP, we start from the beginning of the song.
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

    public SmpsDriver getDriver() { return driver; }
    public boolean isPlaying() { return audioOutput != null && audioOutput.isRunning(); }

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
