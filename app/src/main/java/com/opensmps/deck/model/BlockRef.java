package com.opensmps.deck.model;

/**
 * Reference to a reusable block on a specific channel timeline.
 *
 * <p>Timing is absolute in song ticks. The same block definition can be
 * instantiated multiple times by adding multiple references.
 */
public class BlockRef {
    private int blockId;
    private int startTick;
    private int repeatCount = 1;
    private int transposeSemitones = 0;

    public BlockRef() {}

    public BlockRef(int blockId, int startTick) {
        this.blockId = blockId;
        this.startTick = startTick;
    }

    public int getBlockId() {
        return blockId;
    }

    public void setBlockId(int blockId) {
        this.blockId = blockId;
    }

    public int getStartTick() {
        return startTick;
    }

    public void setStartTick(int startTick) {
        this.startTick = Math.max(0, startTick);
    }

    public int getRepeatCount() {
        return repeatCount;
    }

    public void setRepeatCount(int repeatCount) {
        this.repeatCount = Math.max(1, repeatCount);
    }

    public int getTransposeSemitones() {
        return transposeSemitones;
    }

    public void setTransposeSemitones(int transposeSemitones) {
        this.transposeSemitones = transposeSemitones;
    }
}

