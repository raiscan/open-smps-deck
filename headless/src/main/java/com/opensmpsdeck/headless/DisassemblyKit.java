package com.opensmpsdeck.headless;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/** A kit is a view of the portable package. Each driver owns its assembly and integration files. */
final class DisassemblyKit {
    interface Emitter {
        void emit(DisassemblyKit kit) throws IOException;

        static Emitter forDriver(String driver) {
            if (driver.equals("s3k")) return S3kDisasmEmitter::emit;
            throw new IllegalArgumentException("Disassembly kit not yet supported for driver " + driver);
        }
    }

    final PortablePackageReader.Package artifact;
    final PackageManifest manifest;
    final List<? extends StockAssets> roms;
    final boolean closed;
    final List<Integer> replacements;
    final TreeSet<String> warnings;
    final StringBuilder readme = new StringBuilder();
    final Map<String, byte[]> files = new LinkedHashMap<>();
    final Map<String, PackageManifest.Asset> assets = new LinkedHashMap<>();
    final String name;

    private DisassemblyKit(PortablePackageReader.Package artifact, List<? extends StockAssets> roms,
                           boolean closed, List<Integer> replacements) throws IOException {
        this.artifact = artifact;
        manifest = artifact.manifest();
        this.roms = List.copyOf(roms);
        this.closed = closed;
        this.replacements = List.copyOf(replacements);
        warnings = PortablePackageReader.warnings(artifact);
        manifest.assets().forEach(asset -> assets.put(asset.key(), asset));
        name = "OSD_" + manifest.packageInfo().id().replace('-', '_');
        readme.append("# ").append(name).append(" disassembly kit\n\n");
        if (!roms.isEmpty()) {
            warnings.add("LOCAL_ROM_KIT");
            readme.append("**Personal use only. Do not redistribute this ROM-materialised kit.**\n\n");
        } else {
            readme.append("To materialise stock assets, re-run the original export with `--rom PATH` ")
                    .append("using your own S3/S&K ROM. Use the same DAC options.\n\n");
        }
        readme.append("The portable manifest records source metadata, rights, provenance and original bindings. ")
                .append("The kit contains only the build files.\n\n");
    }

    static List<String> write(Path input, Path output, List<Path> romPaths, boolean closed,
                              List<Integer> replacements, ReconstructionStages.Progress progress) throws IOException {
        progress.report(0, 1, "Writing disassembly kit");
        if (Files.isDirectory(input)) input = input.resolve("arrangement.smps.json");
        var roms = new ArrayList<StockAssets>();
        var romWarnings = new TreeSet<String>();
        PortablePackageReader.Package artifact = null;
        if (input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".smps.zip")) {
            artifact = PortablePackageReader.read(input, manifest -> Emitter.forDriver(manifest.driver()));
        }
        for (Path path : romPaths) {
            var image = S3kPackageDriver.INSTANCE.openRom(path);
            roms.add(image.assets());
            if (!image.known()) romWarnings.add("UNKNOWN_ROM_IMAGE");
        }
        if (artifact == null) {
            var project = StockAssets.resolveProject(ProjectFiles.load(input).project(), roms);
            var payload = PortablePackageWriter.build(project,
                    new PortableOptions("s3k", false, null, null, null, List.of(), List.of()));
            artifact = new PortablePackageReader.Package(payload.manifest(), payload.entries());
        }
        var kit = build(artifact, roms, closed, replacements);
        kit.warnings.addAll(romWarnings);
        kit.publish(output.toAbsolutePath().normalize());
        progress.report(1, 1, "Export complete");
        return List.copyOf(kit.warnings);
    }

    static DisassemblyKit build(PortablePackageReader.Package artifact, List<? extends StockAssets> roms,
                                boolean closed, List<Integer> replacements) throws IOException {
        var emitter = Emitter.forDriver(artifact.manifest().driver());
        var kit = new DisassemblyKit(artifact, roms, closed, replacements);
        kit.files.put("manifest.json", artifact.entries().get("manifest.json"));
        emitter.emit(kit);
        return kit;
    }

    void text(String path, CharSequence content) {
        files.put(path, content.toString().getBytes(StandardCharsets.UTF_8));
    }

    boolean stock(PackageManifest.Asset asset, byte[] embedded) {
        return asset.origin().type().equals("stock")
                || embedded != null && StockTable.match(asset.kind().name(), embedded) != null;
    }

    byte[] materialise(PackageManifest.Asset asset, byte[] embedded) {
        if (!stock(asset, embedded)) return embedded;
        if (roms.isEmpty()) return null;
        if (asset.ref() == null) return embedded;
        byte[] bytes = StockAssets.resolve(asset.key(), asset.ref(), asset.transforms(), asset.resultSha256(), roms);
        asset.kind().validateLength(asset.key(), bytes.length);
        return bytes;
    }

    void publish(Path output) throws IOException {
        readme.append("## Warnings\n\n");
        for (String code : warnings) {
            readme.append("- `").append(code).append("` ").append(warningMessage(code)).append('\n');
        }
        if (warnings.isEmpty()) readme.append("None.\n");
        text("README.md", readme);
        if (output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            var entries = new LinkedHashMap<String, byte[]>();
            files.forEach((path, bytes) -> entries.put(name + "-kit/" + path, bytes));
            PortableArchive.write(output, entries, null);
        } else {
            Files.createDirectory(output);
            try {
                for (var file : files.entrySet()) {
                    var path = output.resolve(file.getKey());
                    Files.createDirectories(path.getParent());
                    Files.write(path, file.getValue(), CREATE_NEW, WRITE);
                }
            } catch (IOException | RuntimeException failure) {
                removeOnFailure(output, failure);
                throw failure;
            }
        }
    }

    static String warningMessage(String code) {
        return switch (code) {
            case "LOCAL_ROM_KIT" -> "ROM-materialised kit for personal use only. Do not redistribute.";
            case "DPCM_INEXACT" -> "One or more samples change when encoded to DPCM. See the kit README for individual samples.";
            case "DPCM_CLOSED" -> "DPCM-closed export quantises WAV samples and pads odd lengths.";
            case "INCOMPLETE_RELOCATIONS" -> "Unreached or unclassified bytes are literal. Review hidden pointers and DAC operands before moving the bank.";
            case "DAC_REPLACEMENT" -> "The kit replaces retail DAC note slots. Verify no other music or SFX in your mod uses those slots.";
            case "CHECK_MODULATION_DRIVER" -> "Custom modulation envelopes require operand reads from envelope data in the Z80 driver.";
            case "UNDECLARED_STOCK_CONVERTED" -> "A payload matched the stock catalogue and was converted to a reference.";
            case "UNMATERIALISED_STOCK" -> "The kit needs local stock files. See its README for the ROM export command.";
            default -> ProjectProvenance.warningMessage(code);
        };
    }

    private static void removeOnFailure(Path output, Exception failure) {
        try (var paths = Files.walk(output)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }
}
