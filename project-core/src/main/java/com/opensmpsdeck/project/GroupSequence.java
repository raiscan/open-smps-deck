package com.opensmpsdeck.project;

import java.util.List;

/** A linked arrangement with positions relative to its own start. */
public record GroupSequence(String id, String name, int lengthTicks, List<Clip> clips,
                            List<GroupPlacement> groupPlacements) {
    public GroupSequence {
        clips = List.copyOf(clips);
        groupPlacements = List.copyOf(groupPlacements);
    }
}
