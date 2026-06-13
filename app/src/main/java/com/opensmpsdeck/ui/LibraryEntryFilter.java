package com.opensmpsdeck.ui;

import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.SourceReference;

import java.util.Locale;
import java.util.StringJoiner;

final class LibraryEntryFilter {

    private LibraryEntryFilter() {
    }

    static boolean matches(InstrumentLibraryEntry entry, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String haystack = searchableText(entry);
        for (String token : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static String searchableText(InstrumentLibraryEntry entry) {
        StringJoiner joiner = new StringJoiner(" ");
        add(joiner, entry.kind().name());
        add(joiner, entry.displayName());
        add(joiner, entry.compressionLabel());
        add(joiner, entry.dacId());
        add(joiner, Integer.toString(entry.algorithm()));
        add(joiner, Integer.toString(entry.feedback()));
        add(joiner, Integer.toString(entry.stepCount()));
        add(joiner, Integer.toString(entry.playbackRate()));
        add(joiner, Integer.toString(entry.byteLength()));
        for (SourceReference source : entry.sourceReferences()) {
            add(joiner, source.scanRoot());
            add(joiner, source.driverFamily());
            add(joiner, source.gameName());
            add(joiner, source.variantPath());
            add(joiner, source.configExtension());
            add(joiner, source.sourceSongFile());
            add(joiner, source.sourceCompanionFile());
            add(joiner, source.originalIndexOrId());
            add(joiner, source.driverSummary());
        }
        return joiner.toString().toLowerCase(Locale.ROOT);
    }

    private static void add(StringJoiner joiner, String value) {
        if (value != null && !value.isBlank()) {
            joiner.add(value);
        }
    }
}
