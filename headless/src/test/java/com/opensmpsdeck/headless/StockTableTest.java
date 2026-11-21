package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class StockTableTest {
    @Test void publicTableContainsOnlyCanonicalHashesAndLocators() throws Exception {
        var text = resource();
        var entries = StockTable.entries().stream().filter(entry -> entry.game().equals("s3k")).toList();
        assertEquals(StockTable.encode(entries), text);
        var fields = Map.of("BANK", Set.of("songId"), "global-fm", Set.of("slot"),
                "local-fm", Set.of("songId", "slot"), "psg", Set.of("index"),
                "mod", Set.of("index"), "dac", Set.of("note"));
        var coordinates = new HashSet<>();
        for (var value : JsonParser.parseString(text).getAsJsonArray()) {
            var entry = value.getAsJsonObject();
            var expected = new HashSet<>(Set.of("game", "kind", "length", "sha256"));
            String type = entry.has("table") ? entry.get("table").getAsString() : "BANK";
            if (entry.has("table")) expected.add("table");
            expected.addAll(fields.get(type));
            assertEquals(expected, entry.keySet());
            assertEquals("s3k", entry.get("game").getAsString());
            assertTrue(entry.get("sha256").getAsString().matches("[0-9a-f]{64}"));
            assertTrue(entry.get("length").getAsInt() > 0);
            assertTrue(coordinates.add(type + fields.get(type).stream().sorted().map(entry::get).toList()));
        }
        assertEquals(fields.keySet(), entries.stream().map(e -> e.table() == null ? "BANK" : e.table())
                .collect(Collectors.toSet()));
        assertEquals(S3kRomImporter.songs().stream().map(S3kRomImporter.Song::id).toList(),
                entries.stream().filter(e -> e.kind().equals("BANK")).map(StockTable.Entry::songId).toList());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S3K_ROM", matches = ".+")
    void lockedOnCartridgeRegeneratesPublicTable() throws Exception {
        assertEquals(resource(), StockTable.encode(StockTable.build(Path.of(System.getenv("OPENSMPSDECK_S3K_ROM")))));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S1_ROM", matches = ".+")
    void sonic1Rev01ResolvesTable() throws Exception { classic(ClassicPackageDriver.S1, "OPENSMPSDECK_S1_ROM"); }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S1_REV00_ROM", matches = ".+")
    void sonic1Rev00ResolvesTable() throws Exception { classic(ClassicPackageDriver.S1, "OPENSMPSDECK_S1_REV00_ROM"); }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S2_ROM", matches = ".+")
    void sonic2Rev01ResolvesTable() throws Exception { classic(ClassicPackageDriver.S2, "OPENSMPSDECK_S2_ROM"); }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S2_REV00_ROM", matches = ".+")
    void sonic2Rev00ResolvesTable() throws Exception { classic(ClassicPackageDriver.S2, "OPENSMPSDECK_S2_REV00_ROM"); }

    private static void classic(ClassicPackageDriver driver, String env) throws Exception {
        var rom = Path.of(System.getenv(env));
        var image = driver.openRom(rom);
        assertTrue(image.known());
        var entries = StockTable.entries().stream().filter(e -> e.game().equals(driver.id())).toList();
        // Both verified revisions currently expose the same audio payloads.
        assertEquals(entries, StockTable.build(driver, rom));
        for (var entry : entries) {
            if (entry.kind().equals("BANK")) continue;
            byte[] payload = StockAssets.resolve(entry.kind(), entry.ref(), null, null, java.util.List.of(image.assets()));
            assertEquals(entry.length(), payload.length);
        }
    }

    private static String resource() throws Exception {
        try (var input = StockTable.class.getResourceAsStream(StockTable.RESOURCE)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
