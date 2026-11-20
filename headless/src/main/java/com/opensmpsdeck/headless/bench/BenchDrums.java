package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchCartridge.Game;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BenchDrums {
    enum Drum {
        KICK(36), SNARE(38), HAT(42), OTHER(45);
        final int gm;
        Drum(int gm) { this.gm = gm; }
    }

    // S3K named DAC presets. Where a sample has several aliases, prefer a
    // specific class to "other"; conflicting specific classes remain ambiguous.
    private static final Set<Integer> KICKS = Set.of(0x86, 0x9d, 0xa1, 0xa4, 0xa7, 0xa8,
            0xb4, 0xb5, 0xb7, 0xb8, 0xb9, 0xbd, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4);
    private static final Set<Integer> SNARES = Set.of(0x81, 0x87, 0x8f, 0x94, 0x95, 0x96, 0x97,
            0x9b, 0x9f, 0xa6, 0xab, 0xb2, 0xb3, 0xbc);

    private final Map<String, Drum> byHash = new HashMap<>();

    private final Game game;

    BenchDrums(InstrumentCatalogue catalogue, String revision) {
        this(catalogue, revision, Game.s3k);
    }

    BenchDrums(InstrumentCatalogue catalogue, String revision, Game game) {
        this.game = game;
        var tagged = new HashMap<String, Drum>();
        var conflict = new java.util.HashSet<String>();
        for (var variant : catalogue.variants()) {
            var asset = catalogue.asset(variant.assetId());
            if (asset.kind() != Kind.DAC_SAMPLE) continue;
            String hash = asset.id().substring("dac-sample-".length());
            Drum named = named(asset.name() + " " + variant.name() + " " + String.join(" ", variant.roles()));
            if (named != Drum.OTHER) tagged.putIfAbsent(hash, named);
            for (var usage : variant.occurrences()) {
                if (!revision.equals(usage.revision()) || usage.slot() == null) continue;
                Drum known = slot(usage.slot());
                if (known == Drum.OTHER) continue;
                Drum previous = byHash.putIfAbsent(hash, known);
                if (previous != null && previous != known) conflict.add(hash);
            }
        }
        for (String hash : conflict) byHash.remove(hash);
        byHash.putAll(tagged);
    }

    Drum classify(String hash, int slot) {
        return byHash.getOrDefault(hash, slot(slot));
    }

    private Drum slot(int slot) {
        if (game != Game.s3k)
            return slot == 0x81 ? Drum.KICK : slot == 0x82 ? Drum.SNARE : Drum.OTHER;
        if (KICKS.contains(slot)) return Drum.KICK;
        if (SNARES.contains(slot)) return Drum.SNARE;
        return Drum.OTHER;
    }

    private static Drum named(String description) {
        String name = description.toLowerCase(Locale.ROOT);
        if (name.contains("hi-hat") || name.contains("hihat") || name.contains("hi hat")) return Drum.HAT;
        if (name.contains("kick")) return Drum.KICK;
        if (name.contains("snare") || name.contains("clap")) return Drum.SNARE;
        return Drum.OTHER;
    }
}
