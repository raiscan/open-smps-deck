package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** CLI and MCP entry points for the shared portable reader and writer. */
public final class PortablePackages {
    private static final com.google.gson.Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private PortablePackages() {}

    public static StudioProject openProject(Path path, List<Path> roms) throws IOException {
        return openProject(path, roms, warning -> {});
    }

    public static StudioProject openProject(Path path, List<Path> roms, java.util.function.Consumer<String> warnings)
            throws IOException {
        if (roms.isEmpty()) return ProjectFiles.load(path).project();
        var project = ProjectFiles.loadSource(path);
        var sources = new ArrayList<StockAssets>();
        boolean unknown = false;
        for (Path rom : roms) {
            long size = java.nio.file.Files.size(rom);
            PackageDriver driver = size == 0x80000 ? ClassicPackageDriver.S1
                    : size == 0x100000 ? ClassicPackageDriver.S2 : S3kPackageDriver.INSTANCE;
            var image = driver.openRom(rom);
            unknown |= !image.known();
            sources.add(image.assets());
        }
        if (unknown) warnings.accept("UNKNOWN_ROM_IMAGE");
        return StockAssets.resolveProject(project, sources);
    }

    static StudioProject openProject(Path path, List<Path> roms, PrintStream err) throws IOException {
        return openProject(path, roms, warning -> ProjectProvenance.printWarnings(List.of(warning), err));
    }

    public static JsonObject inspect(Path input) throws IOException {
        var archive = PortablePackageReader.read(input);
        var result = archive.manifest().json();
        result.getAsJsonObject("provenance").add("warnings", JSON.toJsonTree(PortablePackageReader.warnings(archive)));
        return result;
    }

    public static JsonObject validate(Path input, List<Path> roms, Path output, Integer frames) {
        var result = new JsonObject();
        var warnings = new TreeSet<String>();
        boolean structure = false;
        boolean playback = false;
        try {
            if (frames != null && output == null) throw new IllegalArgumentException("--frames requires --render");
            if (frames != null) S3kRenderer.validatePresentations(frames);
            var archive = PortablePackageReader.read(input);
            structure = true;
            var manifest = archive.manifest();
            warnings.addAll(PortablePackageReader.warnings(archive));
            result.addProperty("driver", manifest.driver());
            result.addProperty("schemaVersion", manifest.schemaVersion());
            result.addProperty("stockAssetsRequired", manifest.requires().stockAssets());
            boolean patch = manifest.bank().form().equals("patch");
            if (patch && roms.isEmpty()) {
                String requirement = RetailBankPatch.romNeeded(manifest.provenance().derivedFromRetailSong());
                if (output != null) throw new IllegalArgumentException(requirement);
                result.addProperty("playbackPending", requirement);
            }
            if (!manifest.requires().stockAssets() || !roms.isEmpty() || output != null) {
                var driver = PackageDriver.supported(manifest.driver());
                var sources = openRoms(roms, driver, warnings);
                if (manifest.requires().stockAssets() && sources.isEmpty())
                    throw new IllegalArgumentException("Package needs ROM to resolve stock assets: "
                            + archive.manifest().assets().stream().filter(asset -> asset.ref() != null)
                            .map(PackageManifest.Asset::key).toList());
                var assembled = PortablePackageReader.assemble(archive, sources);
                driver.preflight(assembled);
                playback = true;
                if (output != null) {
                    Main.requireDifferentFiles(input, output);
                    driver.render(assembled, output, frames == null ? manifest.bank().previewFrames() : frames);
                    result.addProperty("render", output.toString());
                }
            }
            result.addProperty("valid", true);
        } catch (IOException | IllegalArgumentException error) {
            result.addProperty("valid", false);
            result.addProperty("error", error.getMessage());
        }
        result.addProperty("structureValidated", structure);
        result.addProperty("playbackValidated", playback);
        result.add("warnings", JSON.toJsonTree(warnings));
        return result;
    }

    static List<StockAssets> openRoms(List<Path> paths, PackageDriver driver, TreeSet<String> warnings) throws IOException {
        var sources = new ArrayList<StockAssets>();
        for (Path path : paths) {
            var image = driver.openRom(path);
            if (!image.known()) warnings.add("UNKNOWN_ROM_IMAGE");
            sources.add(image.assets());
        }
        return sources;
    }

    static int cli(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException(args[0] + " expects PKG");
        Path input = Path.of(args[1]);
        if (args[0].equals("inspect-package")) {
            Main.requireArguments(args, 2);
            var inspected = inspect(input);
            var warnings = inspected.getAsJsonObject("provenance").getAsJsonArray("warnings");
            ProjectProvenance.printWarnings(warnings.asList().stream().map(com.google.gson.JsonElement::getAsString).toList(), err);
            out.println(JSON.toJson(inspected));
            return 0;
        }
        var roms = new ArrayList<Path>();
        boolean render = args[0].equals("render-package");
        if (render && args.length < 3) throw new IllegalArgumentException("render-package expects PKG OUT.wav");
        Path output = render ? Path.of(args[2]) : null;
        Integer frames = null;
        for (int i = render ? 3 : 2; i < args.length;) {
            String option = args[i++];
            if (i == args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[i++];
            switch (option) {
                case "--rom" -> roms.add(Path.of(value));
                case "--render" -> {
                    if (output != null) throw new IllegalArgumentException("Duplicate --render");
                    output = Path.of(value);
                }
                case "--frames" -> {
                    if (frames != null) throw new IllegalArgumentException("Duplicate --frames");
                    frames = Integer.valueOf(value);
                }
                default -> throw new IllegalArgumentException("Unknown option " + option);
            }
        }
        var report = validate(input, roms, output, frames);
        out.println(JSON.toJson(report));
        ProjectProvenance.printWarnings(report.getAsJsonArray("warnings").asList().stream()
                .map(com.google.gson.JsonElement::getAsString).toList(), err);
        return report.get("valid").getAsBoolean() ? 0 : 2;
    }

    static void printWriteWarnings(PortablePackageWriter.Payload payload, PrintStream err) {
        ProjectProvenance.printWarnings(payload.warnings().stream()
                .filter(code -> !Set.of("UNDECLARED_STOCK_CONVERTED", "TEMPO_APPROXIMATED").contains(code)).toList(), err);
        payload.tempoWarnings().forEach(warning -> err.println("Warning " + warning));
        for (String asset : payload.converted())
            err.println("Warning [UNDECLARED_STOCK_CONVERTED]: Converted exact stock match for asset " + asset);
    }
}
