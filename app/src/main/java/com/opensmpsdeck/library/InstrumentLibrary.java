package com.opensmpsdeck.library;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InstrumentLibrary {

    private final Map<String, InstrumentLibraryEntry> entriesByDedupeKey = new LinkedHashMap<>();
    private boolean dirty;

    public AddResult addOrMerge(InstrumentLibraryEntry entry, Instant now) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(now, "now");
        InstrumentLibraryEntry existing = entriesByDedupeKey.get(entry.dedupeKey());
        if (existing == null) {
            InstrumentLibraryEntry stored = entry.withTimestamps(now, now);
            entriesByDedupeKey.put(stored.dedupeKey(), stored);
            dirty = true;
            return new AddResult(true, true, stored);
        }

        InstrumentLibraryEntry merged = existing.withMergedSources(entry.sourceReferences(), now);
        if (merged == existing) {
            return new AddResult(false, false, existing);
        }

        entriesByDedupeKey.put(merged.dedupeKey(), merged);
        dirty = true;
        return new AddResult(false, true, merged);
    }

    public List<InstrumentLibraryEntry> entries() {
        return List.copyOf(entriesByDedupeKey.values());
    }

    public List<InstrumentLibraryEntry> entries(InstrumentAssetKind kind) {
        List<InstrumentLibraryEntry> matches = new ArrayList<>();
        for (InstrumentLibraryEntry entry : entriesByDedupeKey.values()) {
            if (entry.kind() == kind) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public static InstrumentLibrary fromEntries(List<InstrumentLibraryEntry> entries) {
        InstrumentLibrary library = new InstrumentLibrary();
        for (InstrumentLibraryEntry entry : entries) {
            library.entriesByDedupeKey.put(entry.dedupeKey(), entry);
        }
        return library;
    }
}
