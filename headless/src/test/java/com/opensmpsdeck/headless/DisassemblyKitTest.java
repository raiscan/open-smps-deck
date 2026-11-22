package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.RetailSongOrigin;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class DisassemblyKitTest {
    @TempDir Path directory;

    @Test void reassemblesEveryExampleWithOnlyDacRemapping() throws Exception {
        try (var examples = Files.list(Path.of("../examples"))) {
            for (var path : examples.filter(file -> file.toString().endsWith(".smps.json")).toList()) {
                var artifact = artifact(ProjectFiles.load(path).project());
                var kit = DisassemblyKit.build(artifact, List.of(), false, List.of());
                byte[] expected = PortablePackageReader.assemble(artifact, List.of()).song();
                var analysis = NativeBankAnalysis.inspectForEditing(PortablePackageReader.assemble(artifact, List.of()));
                for (var event : analysis.events()) {
                    if (!event.meaning().equals("DAC sample")) continue;
                    int note = event.bytes().getFirst();
                    int ordinal = artifact.manifest().dacBindings().stream().map(PackageManifest.DacBinding::note)
                            .toList().indexOf(note);
                    assertTrue(ordinal >= 0);
                    expected[event.address() - artifact.manifest().bank().base()] = (byte) (0xC5 + ordinal);
                }
                int header = artifact.manifest().bank().headerOffset();
                if (artifact.manifest().globalVoices() != null && expected[header] == 0 && expected[header + 1] == 0
                        && expected[header + 2] > 1) {
                    // This abstract pointer selects the package's global voice table.
                    expected[header] = (byte) 0xD8;
                    expected[header + 1] = 0x17;
                }
                assertArrayEquals(expected, assemble(kit), path.getFileName().toString());
                for (String z80 : List.of("envelope-setup.asm", "global-voices.asm")) {
                    if (kit.files.containsKey(z80)) assertFalse(string(kit, z80).contains("$"), z80);
                }
            }
        }
    }

    @Test void preservesSkippedBytesAndLabelsCallLoopAndJump() throws Exception {
        byte[] bytes = {(byte) 0xA7, (byte) 0xC5, 0, 0, 1, 0, 1, 2,
                12, (byte) 0x80, 0, 0,
                (byte) 0xf8, 24, (byte) 0x80,
                (byte) 0xf7, 0, 2, 12, (byte) 0x80,
                (byte) 0xf6, 12, (byte) 0x80,
                (byte) 0xA1, (byte) 0x80, 6, (byte) 0xf9};
        var kit = DisassemblyKit.build(artifact(nativeProject(bytes, List.of(), List.of())), List.of(), false, List.of());
        assertArrayEquals(bytes, assemble(kit));
        String source = string(kit, music(kit));
        for (String macro : List.of("smpsCall", "smpsLoop", "smpsJump", "smpsReturn")) assertTrue(source.contains(macro));
        assertTrue(kit.warnings.contains("INCOMPLETE_RELOCATIONS"));
        assertTrue(source.contains("dc.b\t$A1"));
    }

    @Test void sharesWavAcrossRatesAndRemapsDirectDacAndReplacement() throws Exception {
        byte[] bytes = {0, 0, 1, 0, 1, 2, 10, 0, 0, 0, (byte) 0xEA, 1, (byte) 0x81, 4, (byte) 0x82, 5,
                (byte) 0xEA, (byte) 0x81, (byte) 0xF2};
        var sample = DacSample.of("pulse", "Pulse", 6, new byte[]{0, 77, (byte) 129});
        var bindings = List.of(new NativeProgram.DacSlot(0x81, sample.id(), 6),
                new NativeProgram.DacSlot(0x82, sample.id(), 255));
        var kit = DisassemblyKit.build(artifact(nativeProject(bytes, List.of(sample), bindings)), List.of(), true, List.of(0xC4));
        byte[] expected = bytes.clone();
        expected[11] = 0x44;
        expected[12] = (byte) 0xC4;
        expected[14] = (byte) 0xC5;
        assertArrayEquals(expected, assemble(kit));
        var setup = string(kit, "dac-setup.asm");
        assertTrue(setup.contains("DAC_Setup " + kit.name + "_DacC4,"));
        assertEquals(1, kit.files.keySet().stream().filter(path -> path.endsWith(".wav")).count());
        assertTrue(kit.warnings.containsAll(List.of("DPCM_CLOSED", "DPCM_INEXACT", "DAC_REPLACEMENT")));
        var wav = kit.files.get("Sound/DAC/C4.wav");
        assertEquals(S3kDisasmDac.sampleRate(6), ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24));
        assertArrayEquals(S3kDisasmDac.quantize(sample.pcm()), Arrays.copyOfRange(wav, 44, wav.length));
    }

    @Test void stockReferencesKeepDefaultKitPayloadFreeAndResolveFromSyntheticSource() throws Exception {
        var project = DemoProject.create();
        var voice = project.voices().getFirst();
        byte[] voiceBytes = StockTable.bytes(voice.bytes());
        var fm = StockTable.ref("global-fm", null, 2, StockTable.hash(voiceBytes));
        var stockVoice = new Voice(voice.id(), voice.name(), voice.bytes(), AssetOrigin.stock(fm));
        byte[] pcm = {(byte) 128, (byte) 129, (byte) 130, (byte) 131};
        var reference = StockTable.ref("dac", null, 129, StockTable.hash(pcm));
        var origin = new AssetOrigin("stock", reference, List.of(
                new AssetOrigin.Transform("trim", null, 1, 4, null)), null, null);
        var sample = DacSample.of("sample", "Stock DAC", 6, Arrays.copyOfRange(pcm, 1, 4)).withOrigin(origin);
        var retail = new RetailSongOrigin("retail-song", "s3k", 1, "a".repeat(64));
        var fixture = new StudioProject(4, project.name(), project.mode(), project.ticksPerBeat(), project.tempo(),
                List.of(stockVoice, project.voices().get(1)), project.sequences(), project.clips(), List.of(), List.of(sample))
                .withDerivedFrom(retail);

        var artifact = artifact(fixture);
        var kit = DisassemblyKit.build(artifact, List.of(), false, List.of());
        String include = "Sound/Music/" + kit.name + "_StockVoices.asm";
        assertTrue(string(kit, music(kit)).contains(include));
        assertFalse(kit.files.containsKey(include));
        assertFalse(kit.files.containsKey("Sound/DAC/C5.wav"));
        assertTrue(kit.warnings.containsAll(List.of("UNMATERIALISED_STOCK", "RETAIL_SONG_DATA")));

        StockAssets image = ref -> ref.equals(fm) ? voiceBytes : ref.equals(reference) ? pcm : null;
        Path archive = directory.resolve("retail.smps.zip");
        Path output = directory.resolve("retail-kit");
        PortableArchive.write(archive, artifact.entries(), null);
        var stderr = new java.io.ByteArrayOutputStream();
        int status = Main.run(new String[] {"export", archive.toString(), output.toString(), "--format", "disasm"},
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()), new java.io.PrintStream(stderr));
        assertEquals(0, status, stderr.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Warning [RETAIL_SONG_DATA]"));
        assertTrue(Files.readString(output.resolve("README.md")).contains("RETAIL_SONG_DATA"));

        var local = DisassemblyKit.build(artifact, List.of(image), false, List.of());
        assertTrue(local.files.containsKey(include));
        assertTrue(local.warnings.contains("LOCAL_ROM_KIT"));
        assertArrayEquals(new byte[]{(byte) 129, (byte) 130, (byte) 131, (byte) 131},
                Arrays.copyOfRange(local.files.get("Sound/DAC/C5.wav"), 44, 48));
        assertArrayEquals(PortablePackageReader.assemble(artifact, List.of(image)).song(), assemble(local));
    }

    @Test void onlyAnExactStockBindingReusesTheNativeNote() throws Exception {
        byte[] bank = {0, 0, 1, 0, 1, 2, 10, 0, 0, 0, (byte) 0x81, 4, (byte) 0xF2};
        var sample = DacSample.of("sample", "Placeholder", 4, new byte[] {0, 1});
        var ref = StockTable.entries().stream()
                .filter(entry -> entry.kind().equals("DAC_SAMPLE") && entry.note() == 0x81).findFirst().orElseThrow().ref();
        // Construct only the stock reference, without importing a retail payload.
        var artifact = artifact(nativeProject(bank, List.of(sample), List.of(new NativeProgram.DacSlot(0x81, sample.id(), 4))));
        var manifest = artifact.manifest().json();
        var asset = manifest.getAsJsonArray("assets").get(0).getAsJsonObject();
        asset.remove("path");
        asset.remove("sha256");
        asset.getAsJsonObject("origin").addProperty("type", "stock");
        asset.add("ref", new com.google.gson.Gson().toJsonTree(ref));
        asset.add("transforms", new com.google.gson.JsonArray());
        manifest.getAsJsonObject("requires").addProperty("stockAssets", true);
        manifest.getAsJsonObject("requires").getAsJsonArray("games").add("s3k");
        var entries = new java.util.LinkedHashMap<>(artifact.entries());
        entries.remove("dac/asset-000.pcm");
        for (int rate : List.of(4, 5)) {
            manifest.getAsJsonArray("dacBindings").get(0).getAsJsonObject().addProperty("rate", rate);
            var bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            entries.put("manifest.json", bytes);
            var model = PackageManifest.decode(bytes);
            PortablePackageReader.validateStructure(model, entries);
            var kit = DisassemblyKit.build(new PortablePackageReader.Package(model, entries), List.of(), false, List.of());
            assertFalse(kit.files.keySet().stream().anyMatch(path -> path.endsWith(".wav")));
            if (rate == 4) {
                assertTrue(string(kit, music(kit)).contains("dSnareS3"));
                assertFalse(kit.files.containsKey("dac-setup.asm"));
            } else {
                assertTrue(kit.files.containsKey("dac-setup.asm"));
                assertTrue(kit.warnings.contains("UNMATERIALISED_STOCK"));
            }
        }
    }

    @Test void limitsNewNotesAndCountsPointerAndSetupBytesAgainstBankSize() throws Exception {
        byte[] bank = {0, 0, 1, 0, 1, 2, 10, 0, 0, 0, (byte) 0x81, 4, (byte) 0xF2};
        var sample = DacSample.of("sample", "Pulse", 0, new byte[] {(byte) 128, (byte) 128});
        var slots = java.util.stream.IntStream.range(0, 28)
                .mapToObj(i -> new NativeProgram.DacSlot(0x81 + i, sample.id(), i)).toList();
        var artifact = artifact(nativeProject(bank, List.of(sample), slots));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DisassemblyKit.build(artifact, List.of(), false, List.of())).getMessage().contains("27 new"));
        var kit = DisassemblyKit.build(artifact, List.of(), false, List.of(0xC4));
        assertTrue(string(kit, music(kit)).contains(" equ $DF"));
        var length = 2 * (0x8000 - 2 * 69 - 5);
        for (int count : new int[] {length, length + 1}) {
            var longSample = DacSample.of("sample", "Long", 0, new byte[count]);
            var large = artifact(nativeProject(bank, List.of(longSample), slots.subList(0, 1)));
            if (count == length) {
                assertTrue(DisassemblyKit.build(large, List.of(), false, List.of()).readme.toString().contains("32768 of 32768 bytes"));
            } else {
                assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> DisassemblyKit.build(large, List.of(), false, List.of())).getMessage().contains("32769"));
            }
        }
    }

    @Test void portableCliKeepsManifestBytesAndDoesNotOverwriteOutput() throws Exception {
        Path packageFile = directory.resolve("song.smps.zip");
        var payload = PortablePackageWriter.write(DemoProject.create(), packageFile, PortableOptions.DEFAULT);
        Path output = directory.resolve("kit.zip");
        ProjectExport.exportDisasm(packageFile, output, List.of(), false, List.of(), (done, total, message) -> {});
        try (var zip = new ZipFile(output.toFile())) {
            var manifest = zip.stream().filter(entry -> entry.getName().endsWith("/manifest.json")).findFirst().orElseThrow();
            try (var stream = zip.getInputStream(manifest)) {
                assertArrayEquals(payload.entries().get("manifest.json"), stream.readAllBytes());
            }
        }
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> ProjectExport.exportDisasm(packageFile, output, List.of(), false, List.of(), (done, total, message) -> {}));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DisassemblyKit.Emitter.forDriver("s2"))
                .getMessage().contains("not yet supported for driver s2"));
    }

    private static PortablePackageReader.Package artifact(StudioProject project) throws Exception {
        var payload = PortablePackageWriter.build(project, new PortableOptions("s3k", false, null, null, null, List.of(), List.of()));
        return new PortablePackageReader.Package(payload.manifest(), payload.entries());
    }

    private static StudioProject nativeProject(byte[] bytes, List<DacSample> samples, List<NativeProgram.DacSlot> dac) {
        var bank = NativeProgram.of(bytes[0] == (byte) 0xA7 ? 0x8000 : 0, bytes[0] == (byte) 0xA7 ? 2 : 0,
                0, 60, bytes, List.of(), dac);
        return new StudioProject(5, "Test bank", "S3K", 24, bank.tempo(), List.of(), List.of(), List.of(), List.of(), samples, bank);
    }

    private static byte[] assemble(DisassemblyKit kit) {
        var includes = new HashMap<String, String>();
        for (String path : kit.files.keySet()) if (path.endsWith("_StockVoices.asm")) includes.put(path, string(kit, path));
        return new DisasmTestAssembler(kit.manifest.bank().base(), includes).assemble(string(kit, music(kit)));
    }

    private static String music(DisassemblyKit kit) { return "Sound/Music/" + kit.name + ".asm"; }

    private static String string(DisassemblyKit kit, String name) {
        return new String(kit.files.get(name), StandardCharsets.UTF_8);
    }
}
