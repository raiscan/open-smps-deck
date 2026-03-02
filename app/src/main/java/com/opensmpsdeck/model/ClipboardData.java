package com.opensmpsdeck.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds copied tracker data for paste operations.
 * Stores raw SMPS byte arrays per channel for the selected range.
 */
public class ClipboardData {

    private final byte[][] channelData;
    private final int channelCount;
    private final int rowCount;
    private final List<FmVoice> sourceVoices;
    private final List<PsgEnvelope> sourcePsgEnvelopes;
    private final boolean crossSong;

    public ClipboardData(byte[][] channelData, int rowCount, Song sourceSong) {
        this.channelData = new byte[channelData.length][];
        for (int i = 0; i < channelData.length; i++) {
            this.channelData[i] = channelData[i] != null ? channelData[i].clone() : new byte[0];
        }
        this.channelCount = channelData.length;
        this.rowCount = rowCount;
        if (sourceSong != null) {
            // Snapshot voice/psg data so the source Song can be GC'd
            this.sourceVoices = new ArrayList<>();
            for (FmVoice v : sourceSong.getVoiceBank()) {
                sourceVoices.add(new FmVoice(v.getName(), v.getData()));
            }
            this.sourcePsgEnvelopes = new ArrayList<>();
            for (PsgEnvelope e : sourceSong.getPsgEnvelopes()) {
                sourcePsgEnvelopes.add(new PsgEnvelope(e.getName(), e.getData()));
            }
            this.crossSong = true;
        } else {
            this.sourceVoices = null;
            this.sourcePsgEnvelopes = null;
            this.crossSong = false;
        }
    }

    public ClipboardData(byte[][] channelData, int rowCount) {
        this(channelData, rowCount, null);
    }

    public byte[] getChannelData(int index) {
        return channelData[index].clone();
    }

    public int getChannelCount() { return channelCount; }
    public int getRowCount() { return rowCount; }

    /** Get the source voice bank snapshot, or null if same-song copy. */
    public List<FmVoice> getSourceVoices() { return sourceVoices; }

    /** Get the source PSG envelope snapshot, or null if same-song copy. */
    public List<PsgEnvelope> getSourcePsgEnvelopes() { return sourcePsgEnvelopes; }

    /** Whether this clipboard data was copied from a different song. */
    public boolean isCrossSong() { return crossSong; }

    /** @deprecated Use isCrossSong() and getSourceVoices()/getSourcePsgEnvelopes() instead. */
    @Deprecated
    public Song getSourceSong() { return null; }
}
