package com.opensmpsdeck.headless.rom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RomFoundationTest {
    @TempDir Path scratch;

    @Test void reportsHeaderAndFullFileDigestForUnsupportedDump() {
        byte[] header = new byte[512];
        put(header, 0x100, "SEGA MEGA DRIVE");
        put(header, 0x150, "Synthetic game");
        var error = assertThrows(IllegalArgumentException.class,
                () -> RomRevision.identify(header)).getMessage();
        assertTrue(error.contains("512 bytes"), error);
        assertTrue(error.contains("SHA-1"), error);
        assertTrue(error.contains("Synthetic game"), error);
        assertTrue(error.contains("Sonic 1 World REV01"), error);
        assertTrue(error.contains("Sonic 2 World REV01"), error);
        assertTrue(error.contains("Sonic 3 & Knuckles"), error);
    }

    @Test void detectsHeaderedInterleavedSmd() {
        byte[] smd = new byte[512 + 16384];
        smd[8] = (byte) 0xaa;
        smd[9] = (byte) 0xbb;
        smd[10] = 6;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> RomRevision.identify(smd)).getMessage().contains("Convert"));
    }

    @Test void rejectedSetDoesNotWriteConfigurationOrOverrideAnExplicitPath() throws Exception {
        var library = new RomLibrary(scratch.resolve("config/roms.json"));
        assertTrue(library.list().stream().allMatch(entry -> entry.status().equals("missing")));
        Path invalid = Files.writeString(scratch.resolve("invalid.gen"), "invalid");
        assertThrows(IllegalArgumentException.class, () -> library.set(RomGame.S1, invalid));
        assertThrows(IllegalArgumentException.class, () -> library.resolve(RomGame.S1, invalid));
        assertFalse(Files.exists(scratch.resolve("config")));
    }

    @Test void atomicNoOverwritePreservesAFilePublishedByAnotherWriter() throws Exception {
        Path destination = scratch.resolve("song.gen");
        try (var output = new AtomicRomOutput(destination, false)) {
            Files.writeString(output.path(), "complete staging");
            assertFalse(Files.exists(destination));
            Files.writeString(destination, "other writer");
            assertThrows(FileAlreadyExistsException.class, output::publish);
            assertEquals("other writer", Files.readString(destination));
        }
        try (var files = Files.list(scratch)) {
            assertEquals(1, files.count());
        }
    }

    @ParameterizedTest
    @EnumSource(RomGame.class)
    void roundTripsRetailImageAndExercisesLibraryAndWriter(RomGame game) throws Exception {
        String variable = "OPENSMPSDECK_" + game.name() + "_ROM";
        String location = System.getenv(variable);
        assumeTrue(location != null && !location.isBlank(), variable + " is unset");
        Path retail = Path.of(location);
        Path config = scratch.resolve("config/roms.json");
        var library = new RomLibrary(config);
        assertEquals("ok", library.set(game, retail).status());
        var otherGame = game == RomGame.S1 ? RomGame.S2 : RomGame.S1;
        assertThrows(IllegalArgumentException.class, () -> library.set(otherGame, retail));
        var clean = library.resolve(game, null);
        assertEquals(game, clean.revision().game());
        assertEquals(clean.revision(), library.resolve(game, retail).revision());
        byte[] cleanBytes = clean.bytes();
        assertEquals(clean.revision().size(), cleanBytes.length);
        var crc = new CRC32();
        crc.update(cleanBytes);
        assertEquals(clean.revision().crc32(), HexFormat.of().withUpperCase().toHexDigits((int) crc.getValue()));

        // Synthetic edit, arbitrary stock-range relocation, ROM expansion, and new padding.
        var target = new RomTarget<byte[], Boolean, String>(game, (source, song, expand) -> {
            byte[] edited = Arrays.copyOf(source.bytes(), source.revision().size() + (expand ? 8192 : 0));
            edited[0] ^= 1;
            edited[source.revision().size() - 1] ^= 1;
            if (expand) {
                System.arraycopy(edited, 12345, edited, source.revision().size(), 4096);
                System.arraycopy(song, 0, edited, edited.length - song.length, song.length);
            }
            return new RomTarget.PatchedRom<>(edited, "synthetic edit");
        });
        var patched = target.patch(clean, new byte[]{12, 34, 56, 78}, true);
        var encoded = BpsPatch.encode(clean.bytes(), patched.bytes());
        assertArrayEquals(patched.bytes(), BpsPatch.apply(encoded.bytes(), clean.bytes()));
        assertTrue(encoded.stats().sourceCopyBytes() >= 4096);
        assertTrue(encoded.stats().targetReadBytes() <= 12);
        System.out.printf("%s retail round-trip TargetRead=%d bytes%n", game.id(), encoded.stats().targetReadBytes());

        // Only the BPS is written to disposable storage. Keep retail ROM bytes in memory.
        Path patch = scratch.resolve("song." + game.id() + ".bps");
        var export = RomExport.write(clean, patched, null, patch, false);
        assertEquals("synthetic edit", export.report());
        assertEquals(encoded.stats(), export.bpsStats());
        assertEquals(2, export.warnings().size());
        assertArrayEquals(encoded.bytes(), Files.readAllBytes(patch));
        assertThrows(FileAlreadyExistsException.class, () -> RomExport.write(clean, patched, null, patch, false));
        assertThrows(IllegalArgumentException.class, () -> RomExport.write(clean, patched, retail, null, true));
        Files.writeString(patch, "obsolete output");
        RomExport.write(clean, patched, null, patch, true);
        assertArrayEquals(encoded.bytes(), Files.readAllBytes(patch));

        // Exercise publication of a cartridge image with wholly synthetic engine output.
        byte[] synthetic = {1, 9, 9, 4};
        var syntheticOutput = new RomTarget.PatchedRom<>(synthetic, "publish only");
        Path image = scratch.resolve("synthetic.gen");
        assertThrows(FileAlreadyExistsException.class, () -> RomExport.write(clean, syntheticOutput, image, patch, false));
        assertFalse(Files.exists(image));
        RomExport.write(clean, syntheticOutput, image, patch, true);
        assertArrayEquals(synthetic, Files.readAllBytes(image));
        assertArrayEquals(synthetic, BpsPatch.apply(Files.readAllBytes(patch), clean.bytes()));

        try (var files = Files.list(scratch)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(".rom-export-")));
        }
        assertEquals(clean.revision(), library.resolve(game, null).revision());
        assertTrue(Files.readString(config).contains(game.id()));
        Path invalid = Files.writeString(scratch.resolve("different.gen"), "unsupported");
        var settings = new com.google.gson.JsonObject();
        settings.addProperty(game.id(), invalid.toString());
        Files.writeString(config, settings.toString());
        assertEquals("changed-on-disk", library.list().stream().filter(entry -> entry.game().equals(game.id()))
                .findFirst().orElseThrow().status());
        assertThrows(IllegalArgumentException.class, () -> library.resolve(game, null));
        assertEquals(clean.revision(), library.resolve(game, retail).revision());
        library.clear(game);
        assertTrue(library.list().stream().allMatch(entry -> entry.status().equals("missing")));
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }
}
