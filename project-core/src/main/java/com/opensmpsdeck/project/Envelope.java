package com.opensmpsdeck.project;

import java.util.List;

/** A stable-ID native envelope program; use ProjectFormat at document boundaries. */
public record Envelope(String id,String name,String type,List<Integer> bytes) {
    public static final String PSG_VOLUME="PSG_VOLUME", MODULATION="MODULATION";
    public Envelope { bytes=List.copyOf(bytes); }
}
