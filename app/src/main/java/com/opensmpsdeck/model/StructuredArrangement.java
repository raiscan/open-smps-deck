package com.opensmpsdeck.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Block-reference arrangement for tick-accurate editing.
 *
 * <p>This structure is intentionally orthogonal to legacy pattern/order data so
 * migration can happen incrementally.
 */
public class StructuredArrangement {
    private int ticksPerRow = 6;
    private final List<BlockDefinition> blocks = new ArrayList<>();
    private final List<ChannelArrangement> channels = new ArrayList<>(Pattern.CHANNEL_COUNT);

    public StructuredArrangement() {
        for (int i = 0; i < Pattern.CHANNEL_COUNT; i++) {
            channels.add(new ChannelArrangement());
        }
    }

    public int getTicksPerRow() {
        return ticksPerRow;
    }

    public void setTicksPerRow(int ticksPerRow) {
        this.ticksPerRow = Math.max(1, ticksPerRow);
    }

    public List<BlockDefinition> getBlocks() {
        return blocks;
    }

    public List<ChannelArrangement> getChannels() {
        return channels;
    }
}

