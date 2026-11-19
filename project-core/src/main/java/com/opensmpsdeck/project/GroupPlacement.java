package com.opensmpsdeck.project;

/** A group instance. Its children keep their authored hardware lanes. */
public record GroupPlacement(String id, String groupId, int startTick, int lengthTicks) implements Placement {}
