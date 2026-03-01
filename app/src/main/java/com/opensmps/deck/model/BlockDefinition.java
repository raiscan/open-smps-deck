package com.opensmps.deck.model;

/**
 * Canonical reusable musical block.
 *
 * <p>Phase-1 representation stores raw per-channel SMPS bytes so the existing
 * editor/compiler can be bridged incrementally while block references are
 * introduced.
 */
public class BlockDefinition {
    private final int id;
    private String name;
    private int lengthTicks;
    private final byte[][] tracks = new byte[Pattern.CHANNEL_COUNT][];

    public BlockDefinition(int id, String name, int lengthTicks) {
        this.id = id;
        this.name = name;
        this.lengthTicks = Math.max(0, lengthTicks);
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            tracks[ch] = new byte[0];
        }
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getLengthTicks() {
        return lengthTicks;
    }

    public void setLengthTicks(int lengthTicks) {
        this.lengthTicks = Math.max(0, lengthTicks);
    }

    public byte[] getTrackData(int channel) {
        validateChannel(channel);
        return tracks[channel].clone();
    }

    public byte[] getTrackDataDirect(int channel) {
        validateChannel(channel);
        return tracks[channel];
    }

    public void setTrackData(int channel, byte[] data) {
        validateChannel(channel);
        tracks[channel] = (data != null) ? data.clone() : new byte[0];
    }

    private static void validateChannel(int channel) {
        if (channel < 0 || channel >= Pattern.CHANNEL_COUNT) {
            throw new IndexOutOfBoundsException("Channel " + channel + " out of range 0-" + (Pattern.CHANNEL_COUNT - 1));
        }
    }
}

