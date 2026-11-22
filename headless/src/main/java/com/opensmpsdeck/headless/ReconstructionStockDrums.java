package com.opensmpsdeck.headless;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
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
        var game = RomGame.valueOf(target.name());
        var verified = RomLibrary.userLibrary().resolve(game, null);
        byte[] bytes = verified.bytes();
        boolean sonic1 = target == TargetGame.S1;
        var kit = new LinkedHashMap<String, DacSample>();
        try (var rom = Rom.fromReader(RomByteReader.fromBytes(bytes), verified.path().getFileName().toString())) {
            var assets = new ClassicStockAssets(sonic1, rom, bytes);
            for (int note = 0x81; note <= (sonic1 ? 0x8b : 0x91); note++) {
                // The raw S1 timpani note inherits the previous alias's rate.
                if (sonic1 && note >= 0x83 && note < 0x88) continue;
                int rate = assets.dacRate(note);
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
