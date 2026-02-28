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
        this.channelData = channelData;
        this.channelCount = channelData.length;
        this.rowCount = rowCount;
    }

    public byte[][] getChannelData() { return channelData; }
    public int getChannelCount() { return channelCount; }
    public int getRowCount() { return rowCount; }
}
