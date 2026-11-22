package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.RetailSongOrigin;
import com.opensmpsdeck.project.StockRef;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PortablePackageTest {
    @TempDir Path directory;

    @Test void originalOnlyArchiveIsDeterministicAndRendersTheProjectPcm() throws Exception {
        var project = ProjectFiles.load(example("dac-percussion")).project();
        Path source = directory.resolve("original.smps.json");
        ProjectFiles.save(source, project, null);
        Path first = directory.resolve("first.smps.zip"), second = directory.resolve("second.smps.zip");
        expectSuccess("compile-package", source.toString(), first.toString(), "--portable");
        expectSuccess("export", source.toString(), second.toString(), "--format", "portable");
        assertEquals(-1, Files.mismatch(first, second));
        var artifact = PortablePackageReader.read(first);
        assertFalse(artifact.manifest().requires().stockAssets());
        assertEquals(List.of(), artifact.manifest().provenance().warnings());
        assertEquals(ProjectCompiler.compileArrangementLayout(project).relocations(), artifact.manifest().bank().relocations());
        assertTrue(artifact.manifest().bank().relocationsComplete());

        var expected = directory.resolve("source.wav");
        var rendered = directory.resolve("package.wav");
        expectSuccess("render-project", source.toString(), expected.toString(), "--frames", "60");
        var report = expectSuccess("validate-package", first.toString(), "--render", rendered.toString(), "--frames", "60");
        assertTrue(parse(report.out()).get("playbackValidated").getAsBoolean());
        assertEquals(-1, Files.mismatch(expected, rendered));
    }

    @Test void portableWarningsReferenceStockAssetsAndReportSourceAudio() throws Exception {
        var original = ProjectFiles.load(Path.of("../examples/dac-percussion.smps.json")).project();
        assertEquals(List.of(), ProjectExport.portableWarnings(original));

        var voice = original.voices().getFirst();
        var ref = StockTable.ref("global-fm", null, 2, StockTable.hash(StockTable.bytes(voice.bytes())));
        var voices = new java.util.ArrayList<>(original.voices());
        voices.set(0, voice.withOrigin(AssetOrigin.stock(ref)));
        var samples = new java.util.ArrayList<>(original.samples());
        samples.set(0, samples.getFirst().withOrigin(AssetOrigin.sourceAudio()));
        var project = original.withAssets(voices, original.envelopes(), samples);
        // A legacy package would embed the stock voice; the portable one references it.
        assertTrue(ProjectProvenance.detect(project).warnings().contains("ROM_DERIVED_DATA"));
        assertEquals(List.of("SOURCE_AUDIO"), ProjectExport.portableWarnings(project));
    }

    @Test void cliReportsTempoApproximationAndStrictModeLeavesNoArchive() throws Exception {
        var original = ProjectFiles.load(example("signal-garden")).project();
        var source = directory.resolve("tempo.smps.json");
        ProjectFiles.save(source, original.withSettings(original.name(), original.ticksPerBeat(), 31), null);
        Path packageFile = directory.resolve("tempo.smps.zip");
        var result = expectSuccess("compile-package", source.toString(), packageFile.toString(),
                "--portable", "--driver", "s1");
        String diagnostic = "TEMPO_APPROXIMATED: Normal tempo S3K 0x1F to S1 0x08 (relative tick-rate error -0.444444%)";
        assertTrue(result.err().contains(diagnostic), result.err());
        assertEquals(List.of("TEMPO_APPROXIMATED"), PortablePackageReader.read(packageFile).manifest().provenance().warnings());

        Path mod = directory.resolve("strict.jar");
        var rejected = run("export", source.toString(), mod.toString(), "--format", "mod",
                "--replace", "129", "--driver", "s1", "--strict-tempo");
        assertNotEquals(0, rejected.exit());
        assertTrue(rejected.err().contains(diagnostic), rejected.err());
        assertFalse(Files.exists(mod));

        Path exact = directory.resolve("exact.smps.zip");
        var accepted = expectSuccess("export", source.toString(), exact.toString(), "--format", "portable",
                "--driver", "s2", "--strict-tempo");
        assertFalse(accepted.err().contains("TEMPO_APPROXIMATED"), accepted.err());
        assertEquals(List.of(), PortablePackageReader.read(exact).manifest().provenance().warnings());
    }

    @Test void modJarHasBothManifestsAndIdenticalExplodedPackageEntries() throws Exception {
        Path source = example("lantern-walk");
        Path mod = directory.resolve("pack.jar"), pkg = directory.resolve("pack.smps.zip");
        var metadata = new String[]{"--package-id", "original-song", "--package-version", "2.1.0",
                "--license", "CC0-1.0 OR MIT", "--author", "First author", "--author", "Second author", "--no-source"};
        var compile = join(new String[]{"compile-package", source.toString(), pkg.toString(), "--portable", "--driver", "s3k"}, metadata);
        var export = join(new String[]{"export", source.toString(), mod.toString(), "--format", "mod", "--replace", "13", "--replace", "2"}, metadata);
        expectSuccess(compile);
        expectSuccess(export);
        var portable = PortableArchive.read(pkg);
        try (var jar = new ZipFile(mod.toFile())) {
            var modManifest = read(jar, "META-INF/openggf-mod.yaml");
            assertTrue(modManifest.contains("type: patch\nbaseGame: s3k\ndependencies: []\n"), modManifest);
            assertTrue(modManifest.contains("  2: \"original-song\"\n  13: \"original-song\"\nartOverrides: {}"), modManifest);
            assertTrue(modManifest.contains("  - \"First author\"\n  - \"Second author\""), modManifest);
            assertEquals("""
                    formatVersion: 2
                    tracks:
                      - id: "original-song"
                        smpsPackage: "audio/smps/original-song/"
                        gain: 1.0
                        tempoEffects: true
                    sfx: []
                    """, read(jar, "audio/audio-manifest.yaml"));
            assertEquals(portable.size() + 2, jar.size());
            for (var entry : portable.entrySet()) {
                var exploded = jar.getEntry("audio/smps/original-song/" + entry.getKey());
                assertNotNull(exploded);
                try (var input = jar.getInputStream(exploded)) {
                    assertArrayEquals(entry.getValue(), input.readAllBytes());
                }
            }
        }
    }

    @Test void syntheticResolverChecksHashesTransformsFillsAndRedactedSource() throws Exception {
        var original = DemoProject.create();
        var voice = original.voices().getFirst();
        byte[] baseVoice = StockTable.bytes(voice.bytes());
        byte[] patch = baseVoice.clone();
        patch[1] ^= 1;
        var fm = StockTable.ref("global-fm", null, 2, StockTable.hash(baseVoice));
        var patched = new Voice(voice.id(), voice.name(), StockAssets.unsigned(patch), AssetOrigin.stock(fm).withPatch(baseVoice, patch));
        byte[] rawDac = {0, 32, (byte) 128, (byte) 192, (byte) 255};
        var dacRef = StockTable.ref("dac", null, 129, StockTable.hash(rawDac));
        var dacOrigin = new AssetOrigin("stock", dacRef, List.of(
                new AssetOrigin.Transform("gain", "0x1.8p0", null, null, null),
                new AssetOrigin.Transform("trim", null, 1, 4, null)), null, null);
        byte[] dac = Arrays.copyOfRange(DacSample.applyGain(rawDac.clone(), 1.5), 1, 4);
        var sample = DacSample.of("dac", "Stock DAC", 6, dac).withOrigin(dacOrigin);
        byte[] envelopeBytes = {0, 2, (byte) 0x83};
        var psg = StockTable.ref("psg", null, 42, StockTable.hash(envelopeBytes));
        var envelope = new Envelope("psg", "Stock PSG", Envelope.PSG_VOLUME,
                StockAssets.unsigned(envelopeBytes), AssetOrigin.stock(psg));
        var project = new StudioProject(4, original.name(), original.mode(), original.ticksPerBeat(), original.tempo(),
                List.of(patched, original.voices().get(1)), original.sequences(), original.clips(), List.of(envelope), List.of(sample));
        // Each image supplies only some assets. A mismatch in the first source
        // must not hide a matching payload in the second.
        StockAssets first = ref -> ref.equals(fm) ? new byte[25] : ref.equals(psg) ? envelopeBytes : null;
        StockAssets second = ref -> ref.equals(fm) ? baseVoice : ref.equals(dacRef) ? rawDac : null;
        Path file = directory.resolve("synthetic.smps.zip");
        PortablePackageWriter.write(project, file, PortableOptions.DEFAULT);
        var artifact = PortablePackageReader.read(file);
        assertTrue(artifact.manifest().requires().stockAssets());
        assertFalse(artifact.manifest().provenance().containsRomDerivedData());
        var skip = PortablePackages.validate(file, List.of(), null, null);
        assertTrue(skip.get("valid").getAsBoolean());
        assertFalse(skip.get("playbackValidated").getAsBoolean());
        assertEquals(1, artifact.manifest().bank().fills().size());
        assertEquals(List.of(), PortablePackageReader.warnings(artifact).stream().toList());
        var assembled = PortablePackageReader.assemble(artifact, List.of(first, second));
        assertArrayEquals(ProjectCompiler.compile(project), assembled.song());
        assertArrayEquals(dac, assembled.samples().getFirst().pcm());
        assertEquals(envelope.bytes(), assembled.envelopes().getFirst().bytes());
        var redacted = ProjectFormat.read(new String(artifact.entries().get("source.smps.json"), StandardCharsets.UTF_8));
        assertNull(redacted.voices().getFirst().bytes());
        assertNull(redacted.samples().getFirst().pcmBase64());
        assertThrows(IllegalArgumentException.class, () -> ProjectCompiler.compile(redacted));
        var redactedPath = Files.write(directory.resolve("source.smps.json"), artifact.entries().get("source.smps.json"));
        var noRom = run("validate-project", redactedPath.toString());
        assertEquals(2, noRom.exit());
        assertTrue(noRom.err().contains("needs ROM") && noRom.err().contains(voice.id()), noRom.err());
        assertArrayEquals(ProjectCompiler.compile(project),
                ProjectCompiler.compile(StockAssets.resolveProject(redacted, List.of(first, second))));

        var bank = NativeProgram.of(0, 0, project.voices().size(), 60, ProjectCompiler.compile(project), List.of(), List.of());
        var nativeProject = new StudioProject(5, project.name(), "S3K", 24, bank.tempo(), List.of(patched),
                List.of(), List.of(), List.of(), List.of(), bank);
        var nativeFile = directory.resolve("native.smps.zip");
        PortablePackageWriter.write(nativeProject, nativeFile, PortableOptions.DEFAULT);
        var nativeArtifact = PortablePackageReader.read(nativeFile);
        assertArrayEquals(new byte[25], nativeArtifact.entries().get("voices/global.bin"));
        var nativeAssembled = PortablePackageReader.assemble(nativeArtifact, List.of(second));
        assertEquals(patched.bytes(), nativeAssembled.globalVoices().getFirst().bytes());
        assertArrayEquals(bank.bank(), nativeAssembled.song());

        assertFailure("actual missing", () -> PortablePackageReader.assemble(artifact, List.of()));
        assertFailure(StockTable.hash(new byte[25]), () -> PortablePackageReader.assemble(artifact, List.of(first)));
        assertFailure("transformed payload", () -> assembleMutated(artifact, json -> asset(json, "fm/0")
                .addProperty("resultSha256", "f".repeat(64)), first, second));
        assertFailure("Assembled bank", () -> assembleMutated(artifact, json -> json.getAsJsonObject("bank")
                .addProperty("assembledSha256", "f".repeat(64)), first, second));
        assertFailure("Trim exceeds", () -> assembleMutated(artifact, json -> asset(json, "dac/0")
                .getAsJsonArray("transforms").get(1).getAsJsonObject().addProperty("end", 100), first, second));
        assertFailure("Patch exceeds", () -> assembleMutated(artifact, json -> asset(json, "fm/0")
                .getAsJsonArray("transforms").get(0).getAsJsonObject().getAsJsonArray("bytes").get(0)
                .getAsJsonObject().addProperty("offset", 25), first, second));
    }

    @Test void strictReaderChecksFieldsPathsHashesHolesEnumsAndLimits() throws Exception {
        var payload = PortablePackageWriter.build(DemoProject.create(), PortableOptions.DEFAULT);
        var artifact = new PortablePackageReader.Package(payload.manifest(), payload.entries());
        assertFailure("unknown fields", () -> openMutated(artifact, json -> json.addProperty("extra", false)));
        assertFailure("is required", () -> openMutated(artifact, json -> json.remove("source")));
        assertFailure("Unsupported bank form", () -> openMutated(artifact, json -> json.getAsJsonObject("bank")
                .addProperty("form", "unknown")));
        assertFailure("actual", () -> openMutated(artifact, json -> json.getAsJsonObject("source")
                .addProperty("sha256", "b".repeat(64))));
        assertFailure("stock references", () -> openMutated(artifact, json -> json.getAsJsonObject("requires")
                .addProperty("stockAssets", true)));
        assertFailure("Asset keys", () -> openMutated(artifact, json -> asset(json, "fm/0").addProperty("key", "fm/5")));
        assertFailure("asset kind", () -> openMutated(artifact, json -> asset(json, "fm/0").addProperty("kind", "OTHER")));
        assertFailure("bank.path", () -> openMutated(artifact, json -> json.getAsJsonObject("bank")
                .addProperty("path", "../track.bin")));
        assertFailure("Relocations", () -> openMutated(artifact, json -> json.getAsJsonObject("bank")
                .getAsJsonArray("relocations").add(0)));
        assertFailure("Unexpected, missing", () -> {
            var entries = new LinkedHashMap<>(artifact.entries());
            entries.put("psg/asset-001.bin", new byte[]{1});
            readPatched(entries);
        });
        assertFailure("size limit", () -> {
            var entries = new LinkedHashMap<>(artifact.entries());
            entries.put("track.bin", new byte[32769]);
            readPatched(entries);
        });
        assertFailure("duplicate field", () -> {
            var entries = new LinkedHashMap<>(artifact.entries());
            String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
            entries.put("manifest.json", manifest.replace("\"schemaVersion\": 4,", "\"schemaVersion\": 4, \"schemaVersion\": 4,")
                    .getBytes(StandardCharsets.UTF_8));
            readPatched(entries);
        });
        assertFailure("Sonic CD FM has no music loader", () ->
                openMutated(artifact, json -> json.addProperty("driver", "scd")));
        assertFailure("SPDX", () -> Metadata.validateLicense("MIT AND ("));
    }

    @Test void legacySchemaGoldenArchivesAreByteIdentical() throws Exception {
        var expected = Map.of(
                "lantern-walk", "9121be2ffba152dbb30b27f5f2ae1f78f758747804d43ecb431c8ae158a9d6c9",
                "dac-percussion", "8ea979880c30d8bb682cf03446bf48cf11d7ac33516a0665a7c8c4117df5e92c",
                "native-evening-clock", "250d6debe4334018c0bfb62a3ddb36f687667524856b1b9b8d6c92590b5fb728");
        for (var entry : expected.entrySet()) {
            Path zip = directory.resolve(entry.getKey() + ".smps.zip");
            expectSuccess("compile-package", example(entry.getKey()).toString(), zip.toString());
            assertEquals(entry.getValue(), StockTable.hash(Files.readAllBytes(zip)));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S3K_ROM", matches = ".+")
    void lockedOnRomResolvesAllAssetLocatorsAndRendersRetailBankWithWarnings() throws Exception {
        Path path = Path.of(System.getenv("OPENSMPSDECK_S3K_ROM"));
        var image = S3kPackageDriver.INSTANCE.openRom(path);
        assertTrue(image.known());
        for (var entry : StockTable.entries()) {
            if (!entry.game().equals("s3k") || entry.kind().equals("BANK")) continue;  // Bank entries are checked by the regeneration test.
            byte[] bytes = image.assets().payload(entry.ref());
            assertNotNull(bytes, entry::toString);
            assertEquals(entry.length(), bytes.length, entry::toString);
            assertEquals(entry.sha256(), StockTable.hash(bytes), entry::toString);
        }
        var project = S3kRomImporter.readSong(path, 13);
        Path file = directory.resolve("retail.smps.zip");
        var payload = PortablePackageWriter.write(project, file, PortableOptions.DEFAULT);
        assertTrue(payload.manifest().provenance().containsRetailSongData());
        assertTrue(payload.manifest().provenance().containsRomDerivedData());
        assertEquals(13, payload.manifest().provenance().derivedFromRetailSong().songId());
        assertFalse(payload.manifest().bank().relocationsComplete());
        assertTrue(payload.manifest().bank().fills().size() >= project.nativeProgram().localVoiceCount());
        Path expected = directory.resolve("import.wav"), rendered = directory.resolve("portable.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(project), expected, 60);
        var report = PortablePackages.validate(file, List.of(path), rendered, 60);
        assertTrue(report.get("valid").getAsBoolean(), report::toString);
        assertTrue(report.getAsJsonArray("warnings").asList().contains(new com.google.gson.JsonPrimitive("RETAIL_SONG_DATA")));
        assertEquals(-1, Files.mismatch(expected, rendered));

        // Per-asset verification admits an otherwise valid unknown ROM image.
        byte[] bytes = Files.readAllBytes(path);
        bytes[0x400000 - 1] ^= 1;
        Path changed = Files.write(directory.resolve("unknown.gen"), bytes);
        var unknown = PortablePackages.validate(file, List.of(changed), null, null);
        assertTrue(unknown.get("valid").getAsBoolean(), unknown::toString);
        assertTrue(unknown.getAsJsonArray("warnings").asList().contains(new com.google.gson.JsonPrimitive("UNKNOWN_ROM_IMAGE")));
        var standalone = Files.write(directory.resolve("standalone-sk.gen"), Arrays.copyOf(bytes, 0x200000));
        var sk = S3kPackageDriver.INSTANCE.openRom(standalone);
        assertTrue(sk.known());
        var global = StockTable.entries().stream().filter(e -> "global-fm".equals(e.table())).findFirst().orElseThrow();
        assertEquals(global.sha256(), StockTable.hash(sk.assets().payload(global.ref())));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S3K_ROM", matches = ".+")
    void catalogueReconstructionRendersIdenticallyAfterPortableExport() throws Exception {
        Path path = Path.of(System.getenv("OPENSMPSDECK_S3K_ROM"));
        var stock = S3kPackageDriver.INSTANCE.openRom(path).assets();
        var ref = StockTable.entries().stream().filter(e -> "global-fm".equals(e.table()))
                .findFirst().orElseThrow().ref();
        var catalogue = new InstrumentCatalogue.Builder();
        String asset = catalogue.asset(InstrumentCatalogue.Kind.FM_VOICE, "Stock FM", stock.payload(ref));
        String variant = catalogue.variant(asset, "Stock FM", InstrumentCatalogue.Recipe.plain(),
                new InstrumentCatalogue.Occurrence("stock", "S3K", "verified", "Song", 1, ref.slot(), "FM1", 0, true, "global-fm"));
        var score = new S3kReconstruction.Score(List.of(new MidiScore.Part(0, "Lead", 0, false)),
                List.of(new MidiScore.Note(0, 60, 100, 0, 300_000), new MidiScore.Note(0, 64, 90, 350_000, 700_000)), List.of());
        var choice = new ReconstructionVoices.Choice(asset, variant, "Stock FM", null, "matched", List.of(), List.of());
        var project = S3kReconstruction.arrange("Reconstructed lead", 30, 60, score, catalogue.build(),
                Map.of(new ReconstructionVoices.Key(0, null), choice), Map.of()).project();
        assertEquals(ref, project.voices().getFirst().origin().ref());
        Path file = directory.resolve("reconstruction.smps.zip");
        var payload = PortablePackageWriter.write(project, file, PortableOptions.DEFAULT);
        assertTrue(payload.manifest().requires().stockAssets());
        assertFalse(payload.manifest().provenance().containsRomDerivedData());
        Path expected = directory.resolve("source.wav"), rendered = directory.resolve("package.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(project), expected, 60);
        var report = PortablePackages.validate(file, List.of(path), rendered, 60);
        assertTrue(report.get("valid").getAsBoolean(), report::toString);
        assertEquals(-1, Files.mismatch(expected, rendered));
        var source = Files.write(directory.resolve("redacted.smps.json"), payload.entries().get("source.smps.json"));
        expectSuccess("validate-project", source.toString(), "--rom", path.toString());
        Path resolved = directory.resolve("resolved.wav");
        expectSuccess("render-project", source.toString(), resolved.toString(), "--rom", path.toString(), "--frames", "60");
        assertEquals(-1, Files.mismatch(expected, resolved));
        expectSuccess("compile-package", source.toString(), directory.resolve("resolved.smps.zip").toString(),
                "--rom", path.toString(), "--portable");
    }

    @Test void retailArrangementKeepsEmbeddedWarningAndRejectsPatchExport() throws Exception {
        var retail = new RetailSongOrigin("retail-song", "s3k", 13, "0".repeat(64));
        var source = directory.resolve("copied.smps.json");
        ProjectFiles.save(source, DemoProject.create().withDerivedFrom(retail), null);
        var output = directory.resolve("copied.smps.zip");
        var patched = run("compile-package", source.toString(), output.toString(), "--portable", "--retail-patch");
        assertEquals(2, patched.exit());
        assertTrue(patched.err().contains("copy-arrangement produces"), patched.err());
        assertFalse(Files.exists(output));
        var embedded = expectSuccess("compile-package", source.toString(), output.toString(), "--portable");
        assertTrue(embedded.err().contains("RETAIL_SONG_DATA"));
    }

    @Test void retailPatchWithoutRomRequiresExactStockBankEntry() throws Exception {
        var project = ProjectFiles.load(example("native-evening-clock")).project();
        var retail = new RetailSongOrigin("retail-song", "s3k", 13, StockTable.hash(project.nativeProgram().bank()));
        var source = directory.resolve("unlisted.smps.json");
        ProjectFiles.save(source, project.withDerivedFrom(retail), null);
        var output = directory.resolve("unlisted.smps.zip");
        var result = run("compile-package", source.toString(), output.toString(), "--portable", "--retail-patch");
        assertEquals(2, result.exit());
        assertTrue(result.err().contains("No stock table BANK entry for s3k song 13"), result.err());
        assertFalse(Files.exists(output));
        result = run("compile-package", source.toString(), output.toString(), "--portable", "--retail-patch", "--driver", "s2");
        assertEquals(2, result.exit());
        assertTrue(result.err().contains("supports only S3K native v5 banks"), result.err());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S3K_ROM", matches = ".+")
    void editedRetailBankPatchRebuildsExactBytesAndPcm() throws Exception {
        Path rom = Path.of(System.getenv("OPENSMPSDECK_S3K_ROM"));
        var original = S3kRomImporter.readSong(rom, 13);
        Path unedited = directory.resolve("unedited.smps.json");
        ProjectFiles.save(unedited, original, null);
        var identity = directory.resolve("identity.smps.zip");
        expectSuccess("compile-package", unedited.toString(), identity.toString(), "--portable", "--retail-patch");
        assertArrayEquals(new byte[]{(byte) 0x80, 0}, PortableArchive.read(identity).get("track.delta"));

        var nativeProgram = original.nativeProgram();
        byte[] edited = nativeProgram.bank();
        edited[nativeProgram.headerOffset() + 5] ^= 1;
        var program = nativeProgram.withBank(edited);
        var project = new StudioProject(5, original.name(), original.mode(), original.ticksPerBeat(), program.tempo(),
                original.voices(), List.of(), List.of(), original.envelopes(), original.samples(), program)
                .withDerivedFrom(original.derivedFrom());
        Path source = directory.resolve("edited.smps.json");
        ProjectFiles.save(source, project, null);

        Path file = directory.resolve("edit.smps.zip");
        var noBase = run("compile-package", source.toString(), file.toString(), "--portable", "--retail-patch");
        assertEquals(2, noBase.exit());
        assertTrue(noBase.err().contains("--rom"), noBase.err());
        var export = expectSuccess("compile-package", source.toString(), file.toString(),
                "--portable", "--retail-patch", "--rom", rom.toString());
        assertTrue(export.err().contains("DERIVED_FROM_RETAIL_SONG"), export.err());
        assertFalse(export.err().contains("[RETAIL_SONG_DATA]"), export.err());
        Path second = directory.resolve("second-patch.smps.zip");
        expectSuccess("export", source.toString(), second.toString(), "--format", "portable",
                "--retail-patch", "--rom", rom.toString());
        assertEquals(-1, Files.mismatch(file, second));
        var artifact = PortablePackageReader.read(file);
        assertEquals("patch", artifact.manifest().bank().form());
        assertEquals(13, artifact.manifest().provenance().derivedFromRetailSong().songId());
        assertFalse(artifact.manifest().provenance().containsRetailSongData());
        assertFalse(artifact.manifest().provenance().containsRomDerivedData());
        assertNull(artifact.manifest().source());
        assertFalse(artifact.entries().containsKey("source.smps.json"));
        assertFalse(artifact.entries().containsKey("track.bin"));
        assertEquals(7, artifact.entries().get("track.delta").length);
        assertTrue(artifact.manifest().bank().fills().isEmpty());
        assertArrayEquals(edited, PortablePackageReader.assemble(artifact,
                List.of(S3kPackageDriver.INSTANCE.openRom(rom).assets())).song());

        var embedded = PortablePackageWriter.build(project, PortableOptions.DEFAULT);
        assertFailure("Patch bank must omit source", () -> openMutated(artifact, json ->
                json.add("source", embedded.manifest().json().get("source"))));
        assertFailure("track.delta: expected SHA-256", () -> openMutated(artifact, json -> json
                .getAsJsonObject("bank").addProperty("sha256", "0".repeat(64))));
        assertFailure("Assembled bank", () -> assembleMutated(artifact, json -> json
                .getAsJsonObject("bank").addProperty("assembledSha256", "0".repeat(64)),
                S3kPackageDriver.INSTANCE.openRom(rom).assets()));

        var inspected = parse(expectSuccess("inspect-package", file.toString()).out());
        assertEquals("patch", inspected.getAsJsonObject("bank").get("form").getAsString());
        assertEquals(13, inspected.getAsJsonObject("provenance").getAsJsonObject("derivedFromRetailSong")
                .get("songId").getAsInt());
        var structural = parse(expectSuccess("validate-package", file.toString()).out());
        assertFalse(structural.get("playbackValidated").getAsBoolean());
        assertTrue(structural.get("playbackPending").getAsString().contains("needs ROM"));
        var verified = parse(expectSuccess("validate-package", file.toString(), "--rom", rom.toString()).out());
        assertTrue(verified.get("playbackValidated").getAsBoolean());
        Path expected = directory.resolve("edited.wav"), rendered = directory.resolve("patch.wav");
        expectSuccess("render-project", source.toString(), expected.toString(), "--frames", "60");
        expectSuccess("render-package", file.toString(), rendered.toString(), "--rom", rom.toString(), "--frames", "60");
        assertEquals(-1, Files.mismatch(expected, rendered));

        Path mod = directory.resolve("patch.jar");
        expectSuccess("export", source.toString(), mod.toString(), "--format", "mod",
                "--rom", rom.toString(), "--retail-patch", "--package-id", "edited", "--replace", "13");
        try (var jar = new ZipFile(mod.toFile())) {
            var delta = jar.getEntry("audio/smps/edited/track.delta");
            assertNotNull(delta);
            try (var bytes = jar.getInputStream(delta)) {
                assertArrayEquals(artifact.entries().get("track.delta"), bytes.readAllBytes());
            }
            assertNull(jar.getEntry("audio/smps/edited/source.smps.json"));
        }
    }

    private static Path example(String name) {
        return Path.of("..", "examples", name + ".smps.json").toAbsolutePath().normalize();
    }

    private PortablePackageReader.Package openMutated(PortablePackageReader.Package artifact, Consumer<JsonObject> edit) throws Exception {
        var entries = new LinkedHashMap<>(artifact.entries());
        var json = artifact.manifest().json();
        edit.accept(json);
        entries.put("manifest.json", new com.google.gson.GsonBuilder().serializeNulls().create()
                .toJson(json).getBytes(StandardCharsets.UTF_8));
        return readPatched(entries);
    }

    private void assembleMutated(PortablePackageReader.Package artifact, Consumer<JsonObject> edit, StockAssets... sources) throws Exception {
        PortablePackageReader.assemble(openMutated(artifact, edit), List.of(sources));
    }

    private PortablePackageReader.Package readPatched(Map<String, byte[]> entries) throws Exception {
        Path file = Files.createTempFile(directory, "modified-", ".smps.zip");
        Files.delete(file);
        PortableArchive.write(file, entries, null);
        return PortablePackageReader.read(file);
    }

    private static JsonObject asset(JsonObject manifest, String key) {
        return manifest.getAsJsonArray("assets").asList().stream().map(v -> v.getAsJsonObject())
                .filter(v -> v.get("key").getAsString().equals(key)).findFirst().orElseThrow();
    }

    private static void assertFailure(String expected, org.junit.jupiter.api.function.Executable command) {
        var error = assertThrows(IllegalArgumentException.class, command);
        assertTrue(error.getMessage().contains(expected), error::getMessage);
    }

    private static String read(ZipFile zip, String name) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String[] join(String[] first, String[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static JsonObject parse(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    private record Result(int exit, String out, String err) {}

    private static Result expectSuccess(String... arguments) {
        var result = run(arguments);
        assertEquals(0, result.exit(), () -> result.out() + result.err());
        return result;
    }

    private static Result run(String... arguments) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = Main.run(arguments, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
