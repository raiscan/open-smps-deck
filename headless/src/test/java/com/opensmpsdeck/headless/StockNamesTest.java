package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Label;
import com.opensmpsdeck.headless.InstrumentCatalogue.Occurrence;
import com.opensmpsdeck.headless.InstrumentCatalogue.Query;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockNamesTest {
    /** Voice 0Bh from skdisasm's z80_UniVoiceBank comment bytes. */
    private static final byte[] WOOD_BLOCK = HexFormat.of().parseHex(
            "3c3631767194" + "9f969f12" + "00140f04" + "0a040d2f" + "0f4f2f33" + "801a80");

    @Test
    void namesEveryUniversalVoiceAndRetailDacNote() {
        assertEquals(0x23, StockNames.UNIVERSAL_VOICES.size());
        assertEquals(0xC4 - 0x80, S3kDisasmSamples.retailNoteNames().size());
        long globals = StockTable.entries().stream()
                .filter(entry -> entry.game().equals("s3k") && "global-fm".equals(entry.table())).count();
        assertEquals(StockNames.UNIVERSAL_VOICES.size(), globals);
    }

    @Test
    void labelsAnExactUniversalVoiceAndFindsItBySearch() {
        assertEquals(List.of(new Label("Wood Block", "skdisasm", "UVB 0Bh")),
                StockNames.labels(Kind.FM_VOICE, WOOD_BLOCK, null));
        byte[] edited = WOOD_BLOCK.clone();
        edited[24] ^= 1;
        assertEquals(List.of(), StockNames.labels(Kind.FM_VOICE, edited, null));

        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(Kind.FM_VOICE, "Global FM 0B", WOOD_BLOCK);
        builder.variant(asset, "Global FM 0B", Recipe.plain(),
                new Occurrence("rom", "S3K", "verified", "Song", 1, 0x0B, null, null, false, "global-fm"));
        var catalogue = builder.build();
        var page = catalogue.search(new Query("wood block", null, null, null, null, null, 0, 10));
        assertEquals(1, page.total());
        assertEquals("Wood Block", InstrumentCatalogue.labels(page.items().getFirst()).getFirst().text());
        assertEquals(1, catalogue.search(new Query("UVB 0B", null, null, null, null, null, 0, 10)).total());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S3K_ROM", matches = ".+")
    void retailDacPayloadsCarryTheirNoteNameAtTheirRate() throws Exception {
        var stock = S3kPackageDriver.INSTANCE.openRom(Path.of(System.getenv("OPENSMPSDECK_S3K_ROM"))).assets();
        var rates = DisasmDacRates.read("s3k");
        var names = S3kDisasmSamples.retailNoteNames();
        for (var entry : StockTable.entries()) {
            if (!entry.game().equals("s3k") || !"dac".equals(entry.table())) continue;
            byte[] pcm = stock.payload(entry.ref());
            int note = entry.note();
            var labels = StockNames.labels(Kind.DAC_SAMPLE, pcm, rates.get(note));
            assertTrue(labels.contains(new Label(names.get(note - 0x81), "skdisasm", "DAC %02Xh".formatted(note))),
                    () -> "%02X %s".formatted(note, labels));
            assertTrue(labels.stream().allMatch(label -> rates.get(Integer.parseInt(label.ref().substring(4, 6), 16))
                    .equals(rates.get(note))), labels::toString);
        }
    }
}
