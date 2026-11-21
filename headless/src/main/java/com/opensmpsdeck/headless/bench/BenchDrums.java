package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchCartridge.Game;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.S3kDacFamily;
import com.opensmpsdeck.project.DacSample;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class BenchDrums {
    enum Drum {
        KICK(36), SNARE(38), HAT(42), OPEN_HAT(46), CRASH(49), RIDE(51), CYMBAL(53), NOISE(42), OTHER(45);
        final int gm;
        Drum(int gm) { this.gm = gm; }
    }

    private static final Pattern GAINED_SAMPLE = Pattern.compile(
            "(dac-sample-[0-9a-f]{64})-([0-9]{1,3})-gain-(0x[01]\\.[0-9a-f]+p-?[0-9]+)");
    private final Map<String, InstrumentCatalogue.Asset> dacAssets = new HashMap<>();
    private final Map<String, Drum> byHash = new HashMap<>();
    private final Map<String, Drum> cymbals = new HashMap<>();

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
            dacAssets.put(asset.id(), asset);
            String hash = asset.id().substring("dac-sample-".length());
            String description = (asset.name() + " " + variant.name() + " " + String.join(" ", variant.roles()))
                    .toLowerCase(Locale.ROOT);
            Drum named = named(description);
            if (named != Drum.OTHER) tagged.putIfAbsent(hash, named);
            if (named == Drum.HAT && description.contains("open")) cymbals.putIfAbsent(hash, Drum.OPEN_HAT);
            if (named == Drum.OTHER) {
                if (description.contains("crash") || description.contains("splash") || description.contains("china"))
                    cymbals.putIfAbsent(hash, Drum.CRASH);
                else if (description.contains("ride")) cymbals.putIfAbsent(hash, Drum.RIDE);
                else if (description.contains("cymbal")) cymbals.putIfAbsent(hash, Drum.CYMBAL);
            }
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

    Map<String, String> gainAliases(List<DacSample> samples) {
        var aliases = new HashMap<String, String>();
        var ambiguous = new HashSet<String>();
        for (var sample : samples) {
            var binding = GAINED_SAMPLE.matcher(sample.id());
            if (!binding.matches() || Integer.parseInt(binding.group(2)) != sample.rate()) continue;
            var asset = dacAssets.get(binding.group(1));
            if (asset == null) continue;
            double gain = Double.parseDouble(binding.group(3));
            if (!(gain > 0 && gain <= 4)) continue;
            byte[] emitted = sample.pcm();
            if (!Arrays.equals(emitted, DacSample.applyGain(asset.bytes(), gain))) continue;
            String hash = BenchFiles.hash(emitted);
            String original = asset.id().substring("dac-sample-".length());
            String previous = aliases.putIfAbsent(hash, original);
            if (previous != null && !previous.equals(original) || byHash.containsKey(hash) && !hash.equals(original))
                ambiguous.add(hash);
        }
        ambiguous.forEach(aliases::remove);
        return aliases;
    }

    Drum classify(String hash, int slot) {
        return byHash.getOrDefault(hash, slot(slot));
    }

    Drum detailed(String hash, Drum legacy) {
        return cymbals.getOrDefault(hash, legacy);
    }

    Drum nativeNoise(Integer envelope) {
        // S3K noise tracks use short ticks and longer accents. Decay gives
        // an articulation, but a long native accent has no GM instrument ID.
        if (game != Game.s3k || envelope == null) return Drum.NOISE;
        return switch (envelope) {
            case 1, 2 -> Drum.HAT;
            case 4, 8 -> Drum.OPEN_HAT;
            default -> Drum.NOISE;
        };
    }

    static Drum fromPitch(int pitch) {
        return switch (pitch) {
            case 35, 36 -> Drum.KICK;
            case 38, 39, 40 -> Drum.SNARE;
            case 42, 44 -> Drum.HAT;
            case 46 -> Drum.OPEN_HAT;
            case 49, 52, 55, 57 -> Drum.CRASH;
            case 51, 59 -> Drum.RIDE;
            case 53 -> Drum.CYMBAL;
            default -> Drum.OTHER;
        };
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

    private static Drum named(String name) {
        if (name.contains("hi-hat") || name.contains("hihat") || name.contains("hi hat")) return Drum.HAT;
        if (name.contains("kick")) return Drum.KICK;
        if (name.contains("snare") || name.contains("clap")) return Drum.SNARE;
        return Drum.OTHER;
    }
}
