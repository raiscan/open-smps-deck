package com.opensmpsdeck.project;

import java.util.List;

/** The 25 native S3K voice bytes, unchanged and in native parameter-group order. */
public record Voice(String id, String name, List<Integer> bytes, AssetOrigin origin) {
    public Voice(String id, String name, List<Integer> bytes) {
        this(id, name, bytes, null);
    }

    public Voice withOrigin(AssetOrigin origin) {
        return new Voice(id, name, bytes, origin);
    }

    public Voice { if (bytes != null) bytes = List.copyOf(bytes); }
}
