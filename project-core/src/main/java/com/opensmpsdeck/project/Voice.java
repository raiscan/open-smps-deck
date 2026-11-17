package com.opensmpsdeck.project;

import java.util.List;

/** The 25 native S3K voice bytes, unchanged and in native parameter-group order. */
public record Voice(String id, String name, List<Integer> bytes) {
    public Voice { bytes = List.copyOf(bytes); }
}
