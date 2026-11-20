package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/** Accepts only rip layouts whose bytes and complete dependencies can be checked. */
final class CatalogueRips {
    private static final int PREVIEW_FRAMES = 3600;
    private static final Set<String> OTHER_RIP_EXTENSIONS = Set.of("sm1", "sm2", "sm3", "smz", "smy",
            "smp", "s2", "smps", "trs");

    private CatalogueRips() {}

    static void scan(Path directory, InstrumentCatalogue.Builder builder) throws IOException, InterruptedException {
        Path root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Choose a rip directory");
        try (var files = Files.find(root, Integer.MAX_VALUE, (path, attributes) -> attributes.isRegularFile())) {
            for (Path path : files.sorted().toList()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String source = root.relativize(path).toString().replace('\\', '/');
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".smps.json")) {
                    readSource(path, source, builder, CatalogueRips::readProject);
                } else if (name.endsWith(".smps.zip")) {
                    readSource(path, source, builder, CatalogueRips::readPackageProject);
                } else if (name.endsWith(".s3k")) {
                    readSource(path, source, builder, CatalogueRips::readBank);
                } else if (isOtherRip(name)) {
                    builder.issue(source, "unsupported-input",
                            "Use a self-contained S3K rip or a Studio project with its companion bindings");
                }
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static boolean isOtherRip(String name) {
        return name.equals("config.ini") || OTHER_RIP_EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1));
    }

    private record Source(StudioProject project, String revision) {}

    @FunctionalInterface
    private interface Reader { Source read(Path path) throws IOException; }

    private static void readSource(Path path, String name, InstrumentCatalogue.Builder builder, Reader reader)
            throws InterruptedException {
        try {
            var source = reader.read(path);
            CatalogueSources.scanProject(source.project(), name, source.revision(), builder);
        } catch (MissingCompanion failure) {
            builder.issue(name, "missing-companion", failure.getMessage());
        } catch (IllegalArgumentException failure) {
            builder.issue(name, "invalid-or-unsupported-bank", failure.getMessage());
        } catch (IOException failure) {
            String detail = failure.getMessage();
            builder.issue(name, "unreadable-data", detail == null ? "Cannot read rip" : detail.replace(path.toString(), name));
        }
    }

    private static Source readProject(Path path) throws IOException {
        var loaded = ProjectFiles.load(path);
        return new Source(loaded.project(), loaded.revision());
    }

    private static Source readPackageProject(Path path) throws IOException {
        // The package source is self-contained. No archive entry is extracted.
        try (var zip = new ZipFile(path.toFile())) {
            var source = zip.getEntry("source.smps.json");
            if (source == null) throw new MissingCompanion("Package has no source.smps.json");
            try (var in = zip.getInputStream(source)) {
                byte[] data = ProjectFiles.readSource(in);
                String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
                return new Source(ProjectFormat.read(json), InstrumentCatalogue.digest(data));
            }
        }
    }

    private static Source readBank(Path path) throws IOException {
        byte[] bank;
        try (var in = Files.newInputStream(path)) {
            bank = in.readNBytes(S3kBinary.MAX_BYTES + 1);
        }
        if (bank.length < 10 || bank.length > S3kBinary.MAX_BYTES)
            throw new IllegalArgumentException("A standalone S3K bank must contain 10..32768 bytes");
        // S3K uses absolute 16-bit track addresses. The mandatory DAC pointer
        // determines which of the supported bank windows contains this rip.
        int base = (bank[7] & 0x80) << 8;
        var initial = NativeProgram.of(base, 0, 0, PREVIEW_FRAMES, bank, List.of(), List.of());
        int voices;
        try {
            voices = NativeBankAnalysis.discoverLocalVoiceCount(
                    new CompiledProject(bank, List.of(), List.of(), initial, List.of()));
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage();
            if (message.contains("undefined DAC sample") || message.contains("undefined FM voice")
                    || (message.contains("undefined") && message.contains("envelope")))
                throw new MissingCompanion(message + ". Supply the rip as a Studio project with its bindings.");
            throw failure;
        }
        var program = NativeProgram.of(base, 0, voices, PREVIEW_FRAMES, bank, List.of(), List.of());
        var project = new StudioProject(5, path.getFileName().toString(), "S3K", 24, program.tempo(),
                List.of(), List.of(), List.of(), List.of(), List.of(), program);
        return new Source(project, InstrumentCatalogue.digest(bank));
    }

    private static final class MissingCompanion extends IOException {
        MissingCompanion(String message) { super(message); }
    }
}
