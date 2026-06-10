package com.opensmpsdeck.audio;

import com.opensmpsdeck.model.ArrangementMode;
import com.opensmpsdeck.model.Chain;
import com.opensmpsdeck.model.ChainEntry;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.SmpsMode;
import com.opensmpsdeck.model.Song;
import com.opensmps.smps.SmpsCoordFlags;

/**
 * Plays single-note instrument previews through the real SMPS pipeline.
 *
 * <p>Each preview builds a minimal one-note Song that uses the instrument,
 * compiles it through {@link PlaybackEngine#loadSong}, and plays it. What you
 * hear is exactly what the instrument sounds like in a song — including PSG
 * envelope stepping, FM operator behavior, and DAC sample rate handling.
 *
 * <p>The transport reloads the current song on every play, so borrowing the
 * engine for a preview does not disturb normal playback.
 */
public final class InstrumentPreviewPlayer {

    /** Default preview note: C4 (0x81 = C0, +12 per octave). */
    public static final int DEFAULT_NOTE = 0xB1;

    /** Default preview note length in frames (~0.75s at 60Hz). */
    public static final int DEFAULT_DURATION = 0x2D;

    private static final int PSG_PREVIEW_CHANNEL = 6; // PSG1
    private static final int FM_PREVIEW_CHANNEL = 0;  // FM1
    private static final int DAC_CHANNEL = 5;

    private InstrumentPreviewPlayer() {
    }

    /** Plays a single note with the given FM voice. */
    public static void previewFmVoice(PlaybackEngine engine, FmVoice voice, int noteByte) {
        if (engine == null) return;
        engine.stop();
        engine.loadPreview(buildFmPreviewSong(voice, noteByte, DEFAULT_DURATION));
        engine.play();
    }

    /** Plays a single note with the given PSG volume envelope. */
    public static void previewPsgEnvelope(PlaybackEngine engine, PsgEnvelope envelope, int noteByte) {
        if (engine == null) return;
        engine.stop();
        engine.loadPreview(buildPsgPreviewSong(envelope, noteByte, DEFAULT_DURATION));
        engine.play();
    }

    /** Plays the given DAC sample once at its configured rate. */
    public static void previewDacSample(PlaybackEngine engine, DacSample sample) {
        if (engine == null) return;
        engine.stop();
        engine.loadPreview(buildDacPreviewSong(sample));
        engine.play();
    }

    static Song buildFmPreviewSong(FmVoice voice, int noteByte, int durationFrames) {
        Song song = newPreviewSong();
        song.getVoiceBank().add(voice);
        byte[] data = {
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) clampNote(noteByte), (byte) clampDuration(durationFrames),
            (byte) SmpsCoordFlags.STOP
        };
        addPhrase(song, FM_PREVIEW_CHANNEL, data);
        return song;
    }

    static Song buildPsgPreviewSong(PsgEnvelope envelope, int noteByte, int durationFrames) {
        Song song = newPreviewSong();
        song.getPsgEnvelopes().add(envelope);
        byte[] data = {
            (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x00,
            (byte) clampNote(noteByte), (byte) clampDuration(durationFrames),
            (byte) SmpsCoordFlags.STOP
        };
        addPhrase(song, PSG_PREVIEW_CHANNEL, data);
        return song;
    }

    static Song buildDacPreviewSong(DacSample sample) {
        Song song = newPreviewSong();
        song.getDacSamples().add(sample);
        byte[] data = {
            (byte) 0x81, 0x7F, // first DAC sample, max duration so it plays out
            (byte) SmpsCoordFlags.STOP
        };
        addPhrase(song, DAC_CHANNEL, data);
        return song;
    }

    private static Song newPreviewSong() {
        Song song = new Song();
        song.setName("Preview");
        song.setSmpsMode(SmpsMode.S2);
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);
        song.setTempo(0xFF); // tick nearly every frame so durations are predictable
        song.setDividingTiming(1);
        return song;
    }

    private static void addPhrase(Song song, int channel, byte[] data) {
        var hier = song.getHierarchicalArrangement();
        Phrase phrase = hier.getPhraseLibrary()
                .createPhrase("Preview", ChannelType.fromChannelIndex(channel));
        phrase.setData(data);
        Chain chain = hier.getChain(channel);
        chain.getEntries().add(new ChainEntry(phrase.getId()));
    }

    private static int clampNote(int noteByte) {
        return Math.max(0x81, Math.min(0xDF, noteByte));
    }

    private static int clampDuration(int frames) {
        return Math.max(0x01, Math.min(0x7F, frames));
    }
}
