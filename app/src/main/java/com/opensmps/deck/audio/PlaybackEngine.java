package com.opensmps.deck.audio;

import com.opensmps.deck.model.Song;
import com.opensmps.deck.model.SmpsMode;
import com.opensmps.driver.AudioOutput;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.SmpsSequencer;
import com.opensmps.smps.SmpsSequencerConfig;

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
        driver.silenceAll();

        byte[] smps = compiler.compile(song);
        SimpleSmpsData data = new SimpleSmpsData(smps);

        // Convert PSG envelopes from Song model to byte[][] for SmpsData
        if (!song.getPsgEnvelopes().isEmpty()) {
            byte[][] envs = new byte[song.getPsgEnvelopes().size()][];
            for (int i = 0; i < envs.length; i++) {
                envs[i] = song.getPsgEnvelopes().get(i).getData();
            }
            data.setPsgEnvelopes(envs);
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
