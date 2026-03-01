package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.List;

public class Chain {

    private final int channelIndex;
    private final List<ChainEntry> entries = new ArrayList<>();
    private int loopEntryIndex = -1;

    public Chain(int channelIndex) {
        this.channelIndex = channelIndex;
    }

    public int getChannelIndex() { return channelIndex; }
    public List<ChainEntry> getEntries() { return entries; }

    public int getLoopEntryIndex() { return loopEntryIndex; }
    public void setLoopEntryIndex(int index) { this.loopEntryIndex = index; }

    public boolean hasLoop() { return loopEntryIndex >= 0; }
}
