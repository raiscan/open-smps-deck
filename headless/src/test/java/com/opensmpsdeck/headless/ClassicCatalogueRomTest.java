package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.StockRef;
import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.InstrumentCatalogue.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClassicCatalogueRomTest {
    @TempDir Path directory;

    @Test void scansSonic1StockInventory() throws Exception {
        verify(TargetGame.S1, "OPENSMPSDECK_S1_ROM", 19, 58, 9, 3, 7);
    }

    @Test void scansSonic2StockInventory() throws Exception {
        verify(TargetGame.S2, "OPENSMPSDECK_S2_ROM", 31, 123, 13, 7, 17);
    }

    private void verify(TargetGame target, String variable, int songs, long voices, long psg, long dac, int dacSlots)
            throws Exception {
        String filename = System.getenv(variable);
        assumeTrue(filename != null && !filename.isBlank(), () -> "Set " + variable + " to a verified REV01 ROM");
        var driver = TargetPlayback.classic(target);
        var builder = new InstrumentCatalogue.Builder();
        CatalogueSources.scanRom(Path.of(filename), builder);
        Path output = directory.resolve(target.id() + ".json");
        builder.build().save(output);
        var catalogue = InstrumentCatalogue.open(output);
        assertEquals(songs, catalogue.indexedSongs());
        assertTrue(catalogue.issues().stream().noneMatch(issue -> issue.reason().equals("song-import")),
                () -> catalogue.issues().toString());
        assertEquals(voices, count(catalogue, FM_VOICE));
        assertEquals(psg, count(catalogue, PSG_VOLUME));
        assertEquals(dac, count(catalogue, DAC_SAMPLE));

        Set<StockRef> actual = new HashSet<>();
        Set<Integer> rates = new HashSet<>();
        for (var variant : catalogue.variants()) {
            assertEquals(target, variant.target());
            var asset = catalogue.asset(variant.assetId());
            byte[] nativeBytes = asset.kind() == FM_VOICE ? driver.voiceBytes(asset.bytes()) : asset.bytes();
            String hash = StockTable.hash(nativeBytes);
            var origin = catalogue.origin(asset, target);
            assertNotNull(origin);
            assertEquals(hash, origin.ref().sha256());
            if (asset.kind() == DAC_SAMPLE) {
                rates.add(variant.recipe().rate());
                var probe = CataloguePlayback.audition(catalogue, variant.id());
                assertEquals(variant.recipe().rate(), probe.samples().getFirst().rate());
                assertEquals(target == TargetGame.S1 ? 301 : 295,
                        driver.dac(TargetPlayback.prepare(probe).compiled()).baseCycles());
            }
            for (var usage : variant.occurrences()) {
                assertEquals(target.id(), usage.driver());
                if (!usage.played()) {
                    Integer songId = usage.table().equals("local-fm") ? usage.songId() : null;
                    actual.add(StockTable.ref(target.id(), usage.table(), songId, usage.slot(), hash));
                }
            }
        }
        assertTrue(rates.size() > 1);
        assertEquals(dacSlots, actual.stream().filter(ref -> ref.table().equals("dac")).count());
        var expected = StockTable.entries().stream().filter(entry -> entry.game().equals(target.id()))
                .filter(entry -> !entry.kind().equals("BANK")).map(StockTable.Entry::ref).collect(Collectors.toSet());
        assertEquals(expected, actual);
        for (var kind : Set.of(FM_VOICE, PSG_VOLUME, DAC_SAMPLE))
            assertTrue(catalogue.variants().stream().filter(v -> catalogue.asset(v.assetId()).kind() == kind)
                    .flatMap(v -> v.occurrences().stream()).anyMatch(InstrumentCatalogue.Occurrence::played),
                    () -> "Missing playback observations for " + kind);
        System.out.printf("%s: %d songs, %d FM voices, %d PSG envelopes, %d DAC payloads, %d variants, %d stock refs matched%n",
                target.id(), songs, voices, psg, dac, catalogue.variants().size(), actual.size());
    }

    private static long count(InstrumentCatalogue catalogue, InstrumentCatalogue.Kind kind) {
        return catalogue.assets().stream().filter(asset -> asset.kind() == kind).count();
    }
}
