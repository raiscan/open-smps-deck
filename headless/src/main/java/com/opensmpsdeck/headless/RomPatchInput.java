package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomTarget;
import com.opensmpsdeck.headless.rom.VerifiedRom;
import com.opensmpsdeck.headless.rompatch.S1RomTarget;
import com.opensmpsdeck.headless.rompatch.S1Song;
import com.opensmpsdeck.headless.rompatch.S2RomTarget;
import com.opensmpsdeck.headless.rompatch.S2Song;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.opensmpsdeck.headless.rompatch.RomPatch;
import com.opensmpsdeck.headless.rompatch.S3kRomTarget;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipFile;

record RomPatchInput(RomGame game, Song song) {
    @FunctionalInterface
    interface Song {
        RomTarget.PatchedRom<JsonObject> patch(VerifiedRom clean, TestInGame.Options options) throws IOException;
    }

    static RomPatchInput compile(StudioProject project, RomGame driver) {
        var game = driver == null ? TestInGame.game(project) : driver;
        if (game != RomGame.S3K) {
            if (project.nativeProgram() != null)
                throw new IllegalArgumentException(game.id() + ": native v5 banks are S3K-only. Export an editable arrangement.");
            return new RomPatchInput(game, (clean, options) -> compileClassic(game, project, clean, options));
        }
        try {
            return s3k(new S3kRomTarget.Song(ProjectCompiler.compileWithAssets(project), List.of()));
        } catch (IllegalArgumentException failure) {
            var limit = ProjectCompiler.BankLimitException.from(failure);
            if (limit == null) throw failure;
            throw new IllegalArgumentException(("Compiled song is %,d bytes, over the 32,768-byte (32 KB) per-song limit, "
                    + "after loops and calls. The largest S3K bank has %,d free bytes before assets. "
                    + "Shorten the song or reduce layering.")
                    .formatted(limit.compiledBytes(), RomPatch.largestBankCapacity()), failure);
        }
    }

    private static RomTarget.PatchedRom<JsonObject> compileClassic(RomGame game, StudioProject project,
                                                                  VerifiedRom clean, TestInGame.Options options) throws IOException {
        if (project.voices().stream().anyMatch(voice -> voice.bytes() == null)
                || project.envelopes().stream().anyMatch(envelope -> envelope.bytes() == null)
                || project.samples().stream().anyMatch(sample -> sample.pcmBase64() == null))
            project = StockAssets.resolveProject(project, stock(game, clean));
        var driver = game == RomGame.S1 ? ClassicPackageDriver.S1 : ClassicPackageDriver.S2;
        var tempo = driver.convertTempo(project.tempo());
        ProjectCompiler.BankLayout layout;
        try {
            layout = driver.compile(project, tempo.targetByte());
        } catch (IllegalArgumentException failure) {
            if (!failure.getMessage().contains("32 KiB")) throw failure;
            throw new IllegalArgumentException("Compiled song exceeds the 32,768-byte (32 KB) per-song limit. "
                    + "Shorten the song or reduce layering.", failure);
        }
        var compiled = new CompiledProject(layout.bytes(), project.envelopes(), project.samples());
        var result = patchClassic(game, clean, compiled, project.voices().size(), layout.relocations(), options);
        if (!tempo.exact())
            result.report().getAsJsonArray("warnings").add(tempo.warning("Normal tempo", project.tempo(), driver));
        return result;
    }

    private static List<StockAssets> stock(RomGame game, VerifiedRom clean) {
        byte[] bytes = clean.bytes();
        var rom = Rom.fromReader(RomByteReader.fromBytes(bytes), clean.revision().displayName());
        return List.of(new ClassicStockAssets(game == RomGame.S1, rom, bytes));
    }

    private static RomPatchInput s3k(S3kRomTarget.Song song) {
        return new RomPatchInput(RomGame.S3K, (clean, options) -> S3kRomTarget.TARGET.patch(clean, song, options.s3k()));
    }

    static RomPatchInput load(Path path, RomGame driver) throws IOException {
        if (!path.getFileName().toString().endsWith(".smps.zip")) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Expected a regular project file: " + path);
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                var source = StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(ProjectFiles.readSource(input))).toString();
                return compile(readProject(source), driver);
            }
        }
        try (var zip = new ZipFile(path.toFile())) {
            var manifest = JsonParser.parseString(new String(entry(zip, "manifest.json", 1024 * 1024),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            var game = checkDriver(manifest);
            if (driver != null && game != driver)
                throw new IllegalArgumentException("Package driver " + game.id() + " does not match requested " + driver.id());
            if (game != RomGame.S3K) return loadClassic(game, PortablePackageReader.read(path));
            if (!manifest.has("track") || !manifest.has("source")) {
                throw new IllegalArgumentException("Test in game requires a legacy SMPS package with track and source entries. "
                        + "Export from the original project instead.");
            }
            // The legacy archive's native bytecode is authoritative across compiler versions.
            // Its source project supplies the asset bindings.
            var trackRef = manifest.getAsJsonObject("track");
            byte[] track = checked(zip, trackRef, ProjectCompiler.MAX_BANK_BYTES);
            byte[] source = checked(zip, manifest.getAsJsonObject("source"), 16 * 1024 * 1024);
            var project = readProject(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString());
            var nativeProgram = project.nativeProgram();
            if (manifest.get("bankBase").getAsInt() != (nativeProgram == null ? 0 : nativeProgram.bankBase()))
                throw new IllegalArgumentException("Package track and source have different bank bases");
            var offsets = manifest.has("relocations") ? manifest.getAsJsonArray("relocations")
                    : trackRef.getAsJsonArray("relocations");
            var relocations = offsets == null ? List.<Integer>of()
                    : offsets.asList().stream().map(value -> value.getAsBigDecimal().intValueExact()).toList();
            var compiled = new CompiledProject(track, project.envelopes(), project.samples(),
                    nativeProgram == null ? null : nativeProgram.withBank(track),
                    nativeProgram == null ? List.of() : project.voices());
            return s3k(new S3kRomTarget.Song(compiled, relocations));
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException
                 | ArithmeticException malformed) {
            throw new IOException("Invalid SMPS package manifest or source", malformed);
        }
    }

    private static RomPatchInput loadClassic(RomGame game, PortablePackageReader.Package pkg) {
        var manifest = pkg.manifest();
        if (!game.id().equals(manifest.driver()))
            throw new IllegalArgumentException("Expected a " + game.id() + " portable package");
        return new RomPatchInput(game, (clean, options) -> {
            var compiled = PortablePackageReader.assemble(pkg,
                    manifest.requires().stockAssets() ? stock(game, clean) : List.of());
            var result = patchClassic(game, clean, compiled, manifest.bank().localVoiceCount(),
                    manifest.bank().relocations(), options);
            var warnings = result.report().getAsJsonArray("warnings");
            manifest.provenance().warnings().forEach(warnings::add);
            if (manifest.driverOptions() != null && manifest.driverOptions().has("speedUpTempo")
                    && !manifest.driverOptions().get("speedUpTempo").isJsonNull())
                warnings.add(game == RomGame.S1 ? "Sonic 1 retains retail speed-shoes tempo behavior."
                        : "Sonic 2 uses the retail speed-shoes tempo for the selected slot.");
            return result;
        });
    }

    private static RomTarget.PatchedRom<JsonObject> patchClassic(RomGame game, VerifiedRom clean,
            CompiledProject compiled, int voiceCount, List<Integer> relocations, TestInGame.Options options) throws IOException {
        if (game == RomGame.S2)
            return S2RomTarget.TARGET.patch(clean, S2Song.from(compiled, voiceCount, relocations), options.s2());

        var envelopes = new TreeMap<Integer, byte[]>();
        compiled.psgEnvelopes().forEach((slot, values) -> envelopes.put(slot, StockTable.bytes(values)));
        var dac = new TreeMap<Integer, S1Song.Dac>();
        if (compiled.nativeProgram() == null) {
            for (var sample : compiled.samples())
                dac.put(DacSample.FIRST_NOTE + dac.size(), new S1Song.Dac(sample.pcm(), sample.rate()));
        } else {
            var samples = new TreeMap<String, DacSample>();
            compiled.samples().forEach(sample -> samples.put(sample.id(), sample));
            compiled.nativeProgram().dacSlots().forEach(slot ->
                    dac.put(slot.note(), new S1Song.Dac(samples.get(slot.sampleId()).pcm(), slot.rate())));
        }
        return S1RomTarget.TARGET.patch(clean, new S1Song(compiled.song(), voiceCount, envelopes, dac), options.s1());
    }

    private static StudioProject readProject(String source) throws IOException {
        try {
            checkDriver(JsonParser.parseString(source).getAsJsonObject());
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IOException("Invalid project JSON: " + malformed.getMessage(), malformed);
        }
        return ProjectFormat.read(source);
    }

    private static RomGame checkDriver(JsonObject source) {
        // Prefer a portable package's driver over the editable project's mode.
        for (String field : List.of("driver", "mode")) {
            if (source.has(field)) {
                var value = source.get(field);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Expected a string " + field + " for Test in game.");
                return TestInGame.game(value.getAsString());
            }
        }
        return RomGame.S3K;
    }

    private static byte[] checked(ZipFile zip, JsonObject ref, int limit) throws IOException {
        if (ref == null) throw new IllegalArgumentException("ROM patch requires a legacy package with track and source entries");
        String path = ref.get("path").getAsString();
        byte[] data = entry(zip, path, limit);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if (!digest.equals(ref.get("sha256").getAsString()))
                throw new IllegalArgumentException("Package hash does not match " + path);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        return data;
    }

    private static byte[] entry(ZipFile zip, String name, int limit) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing package entry " + name);
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Package entry exceeds size limit: " + name);
            return bytes;
        }
    }
}
