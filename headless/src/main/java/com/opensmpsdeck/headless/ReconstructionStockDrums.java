package com.opensmpsdeck.headless;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rom.VerifiedRom;
import com.opensmpsdeck.headless.rompatch.S2RomPatch;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed-rate DAC notes from a local, verified classic cartridge. */
final class ReconstructionStockDrums {
    private ReconstructionStockDrums() {}

    static Map<String, DacSample> load(TargetGame target) throws IOException {
        return load(RomLibrary.userLibrary().resolve(RomGame.valueOf(target.name()), null));
    }

    static Map<String, DacSample> load(VerifiedRom verified) throws IOException {
        var game = verified.revision().game();
        if (game != RomGame.S1 && game != RomGame.S2)
            throw new IllegalArgumentException("Stock reconstruction kits require a Sonic 1 or Sonic 2 ROM");
        var target = TargetGame.parse(game.id());
        byte[] bytes = verified.bytes();
        boolean sonic1 = game == RomGame.S1;
        // The pinned S2 loader decodes the PCM correctly, but its compressed-table
        // scan misreads the last rate. Use the same decompressed driver playlist
        // as Test in game.
        var rates = sonic1 ? Map.<Integer, Integer>of() : S2RomPatch.stockDacRates(verified);
        var kit = new LinkedHashMap<String, DacSample>();
        try (var rom = Rom.fromReader(RomByteReader.fromBytes(bytes), verified.path().getFileName().toString())) {
            var assets = new ClassicStockAssets(sonic1, rom, bytes);
            for (int note = 0x81; note <= (sonic1 ? 0x8b : 0x91); note++) {
                // The raw S1 timpani note inherits the previous alias's rate.
                if (sonic1 && note >= 0x83 && note < 0x88) continue;
                int rate = sonic1 ? assets.dacRate(note) : rates.get(note);
                String id = "stock-" + target.id() + "-%02x".formatted(note);
                var locator = StockTable.ref(target.id(), "dac", null, note, "0".repeat(64));
                byte[] pcm = assets.payload(locator);
                if (pcm == null) throw new IOException("Missing stock DAC note 0x%02X for %s".formatted(note, target.id()));
                var ref = StockTable.ref(target.id(), "dac", null, note, StockTable.hash(pcm));
                String name = target.name() + " " + DacFamily.stockName(target, note);
                kit.put(id, DacSample.of(id, name, rate, pcm).withOrigin(AssetOrigin.stock(ref)));
            }
        }
        DacSample.validateAll(kit.values().stream().toList());
        return java.util.Collections.unmodifiableMap(kit);
    }
}
