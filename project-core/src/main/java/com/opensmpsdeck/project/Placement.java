package com.opensmpsdeck.project;

/** A channel clip or linked group instance within one arrangement. */
public sealed interface Placement permits Clip, GroupPlacement {
    String id();
    int startTick();
    int lengthTicks();

    default int endTick() {
        return Math.addExact(startTick(), lengthTicks());
    }
}
