package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchCartridge.Game;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.S3kDacFamily;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class BenchDrums {
    enum Drum {
        KICK(36), SNARE(38), HAT(42), OTHER(45);
        final int gm;
        Drum(int gm) { this.gm = gm; }
    }

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
        return switch (S3kDacFamily.slot(slot)) {
            case KICK -> Drum.KICK;
            case SNARE -> Drum.SNARE;
            case OTHER -> Drum.OTHER;
        };
    }

    private static Drum named(String description) {
        String name = description.toLowerCase(Locale.ROOT);
        if (name.contains("hi-hat") || name.contains("hihat") || name.contains("hi hat")) return Drum.HAT;
        if (name.contains("kick")) return Drum.KICK;
        if (name.contains("snare") || name.contains("clap")) return Drum.SNARE;
        return Drum.OTHER;
    }
}
