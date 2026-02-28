package com.opensmps.deck.model;

/**
 * A pattern containing raw SMPS track data for each of the 10 channels.
 *
 * <p>Channel layout: FM1-FM5 (0-4), DAC/FM6 (5), PSG1-PSG3 (6-8), PSG Noise (9).
 * Each channel's data is a raw SMPS bytecode byte array interpreted by
 * {@link com.opensmps.deck.codec.SmpsDecoder} for display and
 * {@link com.opensmps.deck.codec.SmpsEncoder} for editing.
 */
public class Pattern {

    public static final int CHANNEL_COUNT = 10;

    private final int id;
    private int rows;
    private final byte[][] tracks; // one byte[] per channel

    public Pattern(int id, int rows) {
        this.id = id;
        this.rows = rows;
        this.tracks = new byte[CHANNEL_COUNT][];
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            tracks[i] = new byte[0];
        }
    }

    public int getId() {
        return id;
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    public int getTrackCount() {
        return CHANNEL_COUNT;
    }

    public byte[] getTrackData(int channel) {
        return tracks[channel];
    }

    public void setTrackData(int channel, byte[] data) {
        tracks[channel] = data;
    }
}
