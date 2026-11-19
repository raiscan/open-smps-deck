package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.InstrumentCatalogue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.List;

final class BenchHoldout {
    record Index(int formatVersion, int indexedSongs, List<InstrumentCatalogue.Asset> assets,
                 List<InstrumentCatalogue.Variant> variants, List<InstrumentCatalogue.Issue> issues) {}

    private BenchHoldout() {}

    /** Assets used by at least this many other songs are shared library sounds and stay available. */
    static final int SHARED_SONG_THRESHOLD = 2;

    /**
     * Remove a content asset that the held-out song uses (including declaration-only occurrences)
     * unless at least {@link #SHARED_SONG_THRESHOLD} other songs also use it. Shared assets, such
     * as the common DAC drum kit, are part of any realistic library. Drop recipes that reference
     * excluded assets.
     */
    static Index filter(InstrumentCatalogue source, String revision, int song, String name) {
        var heldOut = new HashSet<String>();
        var otherSongs = new HashMap<String, Set<String>>();
        for (var variant : source.variants()) {
            for (var occurrence : variant.occurrences()) {
                if (belongs(occurrence, revision, song, name)) {
                    heldOut.add(variant.assetId());
                } else {
                    otherSongs.computeIfAbsent(variant.assetId(), id -> new HashSet<>())
                            .add(occurrence.revision() + "#" + occurrence.songId() + "#" + occurrence.song());
                }
            }
        }
        var excluded = new HashSet<String>();
        for (var id : heldOut) {
            if (otherSongs.getOrDefault(id, Set.of()).size() < SHARED_SONG_THRESHOLD) excluded.add(id);
        }
        var remaining = source.variants().stream()
                .filter(variant -> !excluded.contains(variant.assetId()))
                .filter(variant -> !excluded.contains(Objects.toString(variant.recipe().volumeEnvelope(), "")))
                .filter(variant -> !excluded.contains(Objects.toString(variant.recipe().modulationEnvelope(), "")))
                .filter(variant -> variant.recipe().commands().stream().noneMatch(
                        command -> excluded.contains(Objects.toString(command.assetId(), ""))))
                .toList();
        // Preserve serialized roles, metadata and ordering from the source catalogue.
        var assets = source.assets().stream().filter(asset -> !excluded.contains(asset.id())).toList();
        return new Index(InstrumentCatalogue.FORMAT_VERSION, source.indexedSongs(), assets, remaining, source.issues());
    }

    private static boolean belongs(InstrumentCatalogue.Occurrence occurrence, String revision, int song, String name) {
        return revision.equals(occurrence.revision()) && Integer.valueOf(song).equals(occurrence.songId())
                || name.equalsIgnoreCase(occurrence.song());
    }

}
