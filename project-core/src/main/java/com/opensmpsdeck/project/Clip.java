package com.opensmpsdeck.project;

/** A linked sequence instance, repeating or trimming to exactly lengthTicks. */
public record Clip(String id, String sequenceId, String lane, int startTick, int lengthTicks) {}
