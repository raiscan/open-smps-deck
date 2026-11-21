package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.RetailSongOrigin;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassicDisassemblyKitTest {
    @TempDir Path directory;

    @Test void everyRateInvertsTheTwoWavMacros() {
        for (int rate = 0; rate < 256; rate++) {
            int s1 = ClassicDisasmDac.sampleRate(true, rate);
            int s2 = ClassicDisasmDac.sampleRate(false, rate);
            assertEquals(rate, 1 + (53693175 / 15 / s1 - 301 / 2 + 13 / 2) / 13, "S1 rate " + rate);
            assertEquals(rate, 1 + (53693175 / 15 / s2 - 295 / 2 + 13 / 2) / 13, "S2 rate " + rate);
        }
    }

    @Test void assemblesEveryClassicExample() throws Exception {
        var unsupported = Map.of("dac-percussion", "envelope 'env-fireflies'",
                "native-evening-clock", "native v5 banks are S3K-only",
                "voices-and-envelopes", "envelope 'env-fireflies'");
        try (var files = Files.list(Path.of("..", "examples"))) {
            var examples = files.filter(p -> p.toString().endsWith(".smps.json")).sorted().toList();
            for (Path example : examples) {
                String name = example.getFileName().toString().replace(".smps.json", "");
                var project = ProjectFiles.load(example).project();
                for (var driver : ClassicPackageDriver.values()) {
                    if (!unsupported.containsKey(name)) {
                        var artifact = artifact(project, driver);
                        var kit = DisassemblyKit.build(artifact, List.of(), false, List.of());
                        assertRoundTrip(artifact, kit, List.of(), -1);
                    } else {
                        var error = assertThrows(IllegalArgumentException.class, () -> artifact(project, driver));
                        assertTrue(error.getMessage().contains(unsupported.get(name)), error::getMessage);
                    }
                }
            }
        }
    }

    @Test void syntheticDacNotesRelocateAndSlotsCanBeReplaced() throws Exception {
        var project = ClassicPortablePackageTest.project(NativeCommand.DETUNE, 60);
        var s1 = artifact(project, ClassicPackageDriver.S1);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DisassemblyKit.build(s1, List.of(), false, List.of())).getMessage().contains("--replace-dac"));
        var s1Kit = DisassemblyKit.build(s1, List.of(), true, List.of(0x82));
        assertRoundTrip(s1, s1Kit, List.of(), 0x82);
        assertTrue(file(s1Kit, "dac-setup.asm").contains("zDAC_Snare:"));
        assertTrue(s1Kit.readme.toString().contains("SpeedUpIndex` entry to $02"));

        var s2 = artifact(project, ClassicPackageDriver.S2);
        var s2Kit = DisassemblyKit.build(s2, List.of(), false, List.of());
        assertRoundTrip(s2, s2Kit, List.of(), 0x92);
        assertTrue(file(s2Kit, "dac-setup.asm").contains(",006h\t; $92 append"));
        assertTrue(s2Kit.readme.toString().contains("zSpedUpTempoTable` entry to $30"));
        var replaced = DisassemblyKit.build(s2, List.of(), true, List.of(0x91));
        assertRoundTrip(s2, replaced, List.of(), 0x91);
        assertTrue(file(replaced, "dac-setup.asm").contains(",006h\t; $91 replace"));
        assertTrue(replaced.warnings.contains("DAC_REPLACEMENT"));
        assertTrue(replaced.warnings.contains("DPCM_INEXACT"));
        assertTrue(replaced.warnings.contains("DPCM_CLOSED"));
        assertThrows(IllegalArgumentException.class, () -> DisassemblyKit.build(s2, List.of(), false, List.of(0x92)));
        assertThrows(IllegalArgumentException.class, () -> DisassemblyKit.build(s2, List.of(), false, List.of(0x81, 0x81)));

        Path archive = directory.resolve("s2.smps.zip");
        PortableArchive.write(archive, s2.entries(), null);
        Path output = directory.resolve("kit");
        ProjectExport.exportDisasm(archive, output, List.of(), false, List.of(), (done, total, message) -> {});
        assertTrue(Files.isRegularFile(output.resolve("sound/music/" + s2Kit.name + ".asm")));
    }

    @Test void stockAssetsAreRedactedThenMaterialisedInDriverOrder() throws Exception {
        var source = ClassicPortablePackageTest.project(NativeCommand.DETUNE, 60);
        for (var driver : ClassicPackageDriver.values()) {
            boolean s1 = driver == ClassicPackageDriver.S1;
            byte[] voice = driver.voiceBytes(StockTable.bytes(source.voices().getFirst().bytes()));
            byte[] envelope = StockTable.bytes(source.envelopes().getFirst().bytes());
            byte[] pcm = source.samples().getFirst().pcm();
            var fm = StockTable.ref(driver.id(), "local-fm", 129, 0, StockTable.hash(voice));
            var psg = StockTable.ref(driver.id(), "psg", null, 1, StockTable.hash(envelope));
            var dac = StockTable.ref(driver.id(), "dac", null, 129, StockTable.hash(pcm));
            var assets = Map.of(fm, voice, psg, envelope, dac, pcm);
            var project = source.withAssets(List.of(source.voices().getFirst().withOrigin(AssetOrigin.stock(fm))),
                    List.of(source.envelopes().getFirst().withOrigin(AssetOrigin.stock(psg))),
                    List.of(source.samples().getFirst().withOrigin(AssetOrigin.stock(dac))));
            var artifact = artifact(project, driver);
            var replacements = s1 ? List.of(0x81) : List.<Integer>of();
            var redacted = DisassemblyKit.build(artifact, List.of(), false, replacements);
            assertTrue(redacted.warnings.contains("UNMATERIALISED_STOCK"));
            assertFalse(redacted.files.keySet().stream().anyMatch(path -> path.endsWith(".wav")));
            assertTrue(file(redacted, "sound/music/" + redacted.name + "_stock.asm").contains("fatal"));
            assertTrue(file(redacted, "envelope-setup.asm").contains("fatal"));
            assertFalse(redacted.files.containsKey("track.bin"));

            List<StockAssets> roms = List.of(assets::get);
            var materialised = DisassemblyKit.build(artifact, roms, true, replacements);
            assertRoundTrip(artifact, materialised, roms, s1 ? 0x81 : 0x92);
            assertTrue(materialised.warnings.contains("LOCAL_ROM_KIT"));
            assertTrue(materialised.readme.toString().contains("Do not redistribute"));
            assertTrue(file(materialised, "envelope-setup.asm").contains(s1 ? "$01,$02,$80" : "001h,002h,080h"));
            byte[] wav = materialised.files.entrySet().stream().filter(entry -> entry.getKey().endsWith(".wav"))
                    .findFirst().orElseThrow().getValue();
            assertEquals(ClassicDisasmDac.sampleRate(s1, 6), ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24));
            assertArrayEquals(S3kDisasmDac.quantize(pcm), Arrays.copyOfRange(wav, 44, wav.length));
        }
    }

    @Test void untransformedStockDacUsesNativeSymbolsAndChangedS2RateKeepsTheSampleReference() throws Exception {
        var source = ClassicPortablePackageTest.project(NativeCommand.DETUNE, 60);
        for (var driver : ClassicPackageDriver.values()) {
            var artifact = artifact(source, driver);
            var kit = DisassemblyKit.build(withStockKick(artifact, 23), List.of(), false, List.of());
            assertTrue(file(kit, "sound/music/" + kit.name + ".asm").contains("dc.b dKick,"));
            assertFalse(kit.files.containsKey("dac-setup.asm"));
            assertFalse(kit.files.keySet().stream().anyMatch(path -> path.endsWith(".wav")));
            assertThrows(IllegalArgumentException.class,
                    () -> DisassemblyKit.build(withStockKick(artifact, 23), List.of(), false, List.of(0x81)));

            if (driver == ClassicPackageDriver.S2) {
                var changed = DisassemblyKit.build(withStockKick(artifact, 255), List.of(), false, List.of());
                assertTrue(file(changed, "dac-setup.asm").contains("db id(SndDAC_Kick.pointer),0FFh"));
                assertFalse(changed.files.keySet().stream().anyMatch(path -> path.endsWith(".wav")));
            }
        }
    }

    @Test void retainsRetailSongWarningForClassicPackages() throws Exception {
        var project = ClassicPortablePackageTest.project(NativeCommand.DETUNE, 60).withDerivedFrom(
                new RetailSongOrigin("retail-song", "s2", 0x81, StockTable.hash(new byte[] {1, 2, 3})));
        var kit = DisassemblyKit.build(artifact(project, ClassicPackageDriver.S2), List.of(), false, List.of());
        assertTrue(kit.warnings.contains("RETAIL_SONG_DATA"));
    }

    // Populate a manifest with a public stock locator and no retail payload.
    private static PortablePackageReader.Package withStockKick(PortablePackageReader.Package original, int rate)
            throws Exception {
        var manifest = original.manifest();
        var kick = StockTable.entries().stream().filter(entry -> entry.game().equals(manifest.driver())
                && "dac".equals(entry.table()) && entry.note() == 0x81).findFirst().orElseThrow();
        var assets = manifest.assets().stream().map(asset -> asset.kind() != PackageManifest.Kind.DAC_SAMPLE ? asset
                : new PackageManifest.Asset(asset.key(), asset.kind(), asset.id(), asset.name(),
                new PackageManifest.Origin("stock", null, null), kick.ref(), List.of(), null, null, null, null)).toList();
        var binding = manifest.dacBindings().getFirst();
        var updated = new PackageManifest(manifest.schemaVersion(), manifest.profile(), manifest.driver(),
                manifest.packageInfo(), manifest.generator(), manifest.provenance(),
                new PackageManifest.Requirements(true, List.of(manifest.driver())), manifest.driverOptions(),
                manifest.bank(), assets, manifest.envelopeBindings(),
                List.of(new PackageManifest.DacBinding(binding.note(), binding.asset(), rate)), null, null);
        var files = new LinkedHashMap<>(original.entries());
        var sample = manifest.assets().stream().filter(asset -> asset.key().equals(binding.asset())).findFirst().orElseThrow();
        files.remove(sample.path());
        files.remove(manifest.source().path());
        files.put("manifest.json", updated.encode());
        PortablePackageReader.validateStructure(updated, files);
        return new PortablePackageReader.Package(updated, files);
    }

    private static PortablePackageReader.Package artifact(StudioProject project, ClassicPackageDriver driver) throws Exception {
        var payload = PortablePackageWriter.build(project,
                new PortableOptions(driver.id(), true, null, null, null, List.of(), List.of(), 208));
        return new PortablePackageReader.Package(payload.manifest(), payload.entries());
    }

    private static void assertRoundTrip(PortablePackageReader.Package artifact, DisassemblyKit kit,
                                        List<StockAssets> roms, int newNote) {
        boolean s1 = artifact.manifest().driver().equals("s1");
        byte[] source = PortablePackageReader.assemble(artifact, roms).song();
        int dac = s1 ? (source[6] & 255) * 256 + (source[7] & 255) : (source[7] & 255) * 256 + (source[6] & 255);
        if (newNote >= 0) {
            assertEquals(0x81, source[dac] & 255);
            source[dac] = (byte) newNote;
        }
        for (int placement : s1 ? List.of(0) : List.of(0x1380, 0x84BA)) {
            byte[] expected = source.clone();
            if (!s1) {
                var bank = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
                for (int offset : artifact.manifest().bank().relocations())
                    bank.putShort(offset, (short) (bank.getShort(offset) + placement));
            }
            assertArrayEquals(expected, ClassicDisasmTestAssembler.assemble(kit, s1, placement));
        }
    }

    private static String file(DisassemblyKit kit, String path) {
        return new String(kit.files.get(path), StandardCharsets.UTF_8);
    }
}
