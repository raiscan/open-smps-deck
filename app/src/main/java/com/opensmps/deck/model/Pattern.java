package com.opensmps.deck.model;

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
