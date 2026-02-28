package com.opensmps.deck.model;

/**
 * Holds copied tracker data for paste operations.
 * Stores raw SMPS byte arrays per channel for the selected range.
 */
public class ClipboardData {

    private final byte[][] channelData; // one byte[] per channel in the selection
    private final int channelCount;
    private final int rowCount;
    private final Song sourceSong;

    public ClipboardData(byte[][] channelData, int rowCount, Song sourceSong) {
        // Defensive copy
        this.channelData = new byte[channelData.length][];
        for (int i = 0; i < channelData.length; i++) {
            this.channelData[i] = channelData[i] != null ? channelData[i].clone() : new byte[0];
        }
        this.channelCount = channelData.length;
        this.rowCount = rowCount;
        this.sourceSong = sourceSong;
    }

    public ClipboardData(byte[][] channelData, int rowCount) {
        this(channelData, rowCount, null);
    }

    /**
     * Get a defensive copy of the channel data for a specific channel index.
     *
     * @param index channel index within the clipboard selection
     * @return cloned byte array for the channel
     */
    public byte[] getChannelData(int index) {
        return channelData[index].clone();
    }

    /** Get the number of channels in this clipboard selection. */
    public int getChannelCount() { return channelCount; }

    /** Get the number of rows in this clipboard selection. */
    public int getRowCount() { return rowCount; }

    /** Get the source song this data was copied from, or null if unknown. */
    public Song getSourceSong() { return sourceSong; }
}
