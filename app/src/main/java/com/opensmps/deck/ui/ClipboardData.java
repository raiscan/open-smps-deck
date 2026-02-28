package com.opensmps.deck.ui;

/**
 * Holds copied tracker data for paste operations.
 * Stores raw SMPS byte arrays per channel for the selected range.
 */
public class ClipboardData {

    private final byte[][] channelData; // one byte[] per channel in the selection
    private final int channelCount;
    private final int rowCount;

    public ClipboardData(byte[][] channelData, int rowCount) {
        // Defensive copy
        this.channelData = new byte[channelData.length][];
        for (int i = 0; i < channelData.length; i++) {
            this.channelData[i] = channelData[i] != null ? channelData[i].clone() : new byte[0];
        }
        this.channelCount = channelData.length;
        this.rowCount = rowCount;
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
}
