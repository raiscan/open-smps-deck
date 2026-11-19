package com.opensmpsdeck.project;

/** A one-shot sample attack at an integer tick in a DAC source. */
public record SampleTrigger(String id, int tick, String sampleId) {}
