package com.opensmpsdeck.project;

import java.util.List;

/** A stable-ID native envelope program; use ProjectFormat at document boundaries. */
public record Envelope(String id,String name,String type,List<Integer> bytes, AssetOrigin origin) {
    public Envelope(String id,String name,String type,List<Integer> bytes) {
        this(id, name, type, bytes, null);
    }

    public Envelope withOrigin(AssetOrigin origin) {
        return new Envelope(id, name, type, bytes, origin);
    }

    public static final String PSG_VOLUME="PSG_VOLUME", MODULATION="MODULATION";
    public Envelope { bytes=List.copyOf(bytes); }
}
