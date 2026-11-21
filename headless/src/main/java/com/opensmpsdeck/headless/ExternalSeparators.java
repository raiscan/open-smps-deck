package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Run an explicitly chosen, user-local source separation command. */
public final class ExternalSeparators {
    static final String PROVENANCE = "separation.json";
    private static final String CONFIG_ENV = "OPENSMPSDECK_SEPARATORS";
    private static final Pattern PLACEHOLDERS = Pattern.compile("\\{(input|outputDir|device)}");
    private static final Pattern STEM_NAME = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private ExternalSeparators() {}

    private static Path defaultConfig() {
        return ExternalCommands.defaultConfig(CONFIG_ENV, "separators.json");
    }

    private static Map<String, List<String>> commands(Path config) throws IOException {
        return ExternalCommands.commands(config, PLACEHOLDERS, "{input}", "{outputDir}", "{device}");
    }

    public static List<String> configuredNames() throws IOException {
        return configuredNames(null);
    }

    static List<String> configuredNames(Path config) throws IOException {
        Path file = config == null ? defaultConfig() : config;
        String override = System.getenv(CONFIG_ENV);
        if (config == null && (override == null || override.isBlank()) && Files.notExists(file, NOFOLLOW_LINKS))
            return List.of();
        return List.copyOf(commands(file).keySet());
    }

    public static Path separate(Path input, Path output, String name, String device, Path config,
                                ReconstructionStages.Progress progress) throws IOException {
        require(name != null && !name.isBlank(), "separator", "--separator NAME is required");
        require(List.of("auto", "cpu", "rocm", "cuda").contains(device), "separator", "invalid device " + device);
        Path source = input.toRealPath();
        String filename = input.getFileName().toString().toLowerCase(Locale.ROOT);
        require(Files.isRegularFile(source) && (filename.endsWith(".wav") || filename.endsWith(".mp3")),
                "separator", "expected a WAV or MP3 input");
        Path requested = output.toAbsolutePath().normalize();
        require(requested.getParent() != null, "separator", "expected a new output directory");
        Path destination = requested.getParent().toRealPath().resolve(requested.getFileName());
        require(Files.notExists(destination, NOFOLLOW_LINKS), "separator", "output already exists: " + destination);
        Path file = config == null ? defaultConfig() : config;
        var template = commands(file).get(name);
        require(template != null, "separator", name + " is not configured in " + file);

        // Stage on the destination filesystem so failed runs never publish an importable folder.
        Path scratch = Files.createTempDirectory(destination.getParent(), ".opensmpsdeck-separate-");
        try {
            Path staged = Files.createDirectory(scratch.resolve("output"));
            progress.report(0, 2, "Running separator " + name);
            var command = ExternalCommands.expand(template, PLACEHOLDERS,
                    Map.of("input", source.toString(), "outputDir", staged.toString(), "device", device));
            invoke(command, name, scratch.resolve("stderr"));
            progress.report(1, 2, "Checking separator output");
            var provenance = readProvenance(staged);
            validateAudio(staged, provenance);
            Files.move(staged, destination);
        } finally {
            try (var files = Files.walk(scratch)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        progress.report(2, 2, "Separation complete");
        return destination;
    }

    private static void invoke(List<String> command, String name, Path log) throws IOException {
        var process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(log.toFile()).start();
        int status;
        try {
            process.getOutputStream().close();
            status = process.waitFor();
        } catch (InterruptedException interrupted) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Separator " + name + " interrupted");
        }
        if (status != 0) {
            try (var errors = Files.newInputStream(log)) {
                // Keep the end, where Python and FFmpeg put the actionable error.
                errors.skipNBytes(Math.max(0, Files.size(log) - 4096));
                String detail = new String(errors.readNBytes(4096), StandardCharsets.UTF_8).strip();
                throw new IOException("Separator " + name + " exited " + status + (detail.isEmpty() ? "" : ": " + detail));
            }
        }
    }

    static JsonObject readProvenance(Path folder) throws IOException {
        Path file = folder.resolve(PROVENANCE);
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 65536,
                PROVENANCE, "expected a regular provenance file of at most 64 KiB");
        var tree = ReconstructionArtifacts.readTree(file);
        require(tree instanceof JsonObject, PROVENANCE, "expected a provenance object");
        var json = tree.getAsJsonObject();
        for (String key : List.of("tool", "model", "version", "checkpointSha256"))
            require(json.get(key) instanceof JsonPrimitive value && value.isString() && !value.getAsString().isBlank(),
                    PROVENANCE, "expected a nonblank " + key);
        require(SHA256.matcher(json.get("checkpointSha256").getAsString()).matches(), PROVENANCE, "invalid checkpointSha256");
        require(json.get("settings") instanceof JsonObject, PROVENANCE, "expected settings object");
        require(json.get("stemOrder") instanceof JsonArray order && !order.isEmpty(),
                PROVENANCE, "expected nonempty stemOrder");
        var names = new HashSet<String>();
        for (var item : json.getAsJsonArray("stemOrder")) {
            require(item instanceof JsonPrimitive value && value.isString() && STEM_NAME.matcher(value.getAsString()).matches(),
                    PROVENANCE, "invalid stem name");
            require(names.add(item.getAsString()), PROVENANCE, "duplicate stem " + item.getAsString());
        }
        return json;
    }

    static void validateAudio(Path folder, JsonObject provenance) throws IOException {
        Path stems = folder.resolve("stems");
        require(Files.isDirectory(stems, NOFOLLOW_LINKS), "separator", "expected a stems directory");
        var layout = ReferenceAudio.layout(folder.resolve("mix.wav"), SunoImportAudio.MAX_SECONDS);
        for (var item : provenance.getAsJsonArray("stemOrder")) {
            String name = item.getAsString();
            var stem = ReferenceAudio.layout(stems.resolve(name + ".wav"), SunoImportAudio.MAX_SECONDS);
            require(layout.equals(stem),
                    "separator", name + ".wav must match the frame count, sample rate and channels of mix.wav");
        }
    }

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("reconstruct separate expects MIX OUT_DIR --separator NAME");
        String name = null;
        String device = "auto";
        Path config = null;
        for (int i = 2; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + flag);
            String value = args[++i];
            switch (flag) {
                case "--separator" -> name = value;
                case "--device" -> device = value;
                case "--separator-config" -> config = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown separate option " + flag);
            }
        }
        Path folder = separate(Path.of(args[0]), Path.of(args[1]), name, device, config,
                (done, total, message) -> out.println(message));
        out.println(folder.resolve(PROVENANCE));
    }
}
