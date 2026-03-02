package com.opensmpsdeck.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered set of block references for a single tracker channel.
 */
public class ChannelArrangement {
    private final List<BlockRef> blockRefs = new ArrayList<>();

    public List<BlockRef> getBlockRefs() {
        return blockRefs;
    }
}

