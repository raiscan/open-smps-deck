package com.opensmpsdeck.headless;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** User-local, explicitly selected transcription commands and their replayable outputs. */
public final class ExternalTranscribers {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(input|output|role|device)}");
    private static final Pattern LABEL = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.:-]*");
    private static final String CONFIG_ENV = "OPENSMPSDECK_TRANSCRIBERS";

    record RawNote(double start, double end, int pitch, int velocity) {}
    record Evidence(String tool, @Nullable String role, @Nullable String device,
                    @Nullable String inputSha256, String outputSha256, @Nullable String configSha256,
                    Map<String, String> metadata, int noteCount, boolean replace) {}
    record PartNotes(Evidence provenance, List<RawNote> notes) {}
    record Import(Path file, boolean replace) {}

    private ExternalTranscribers() {}

    private static Path defaultConfig() {
        String override = System.getenv(CONFIG_ENV);
        if (override != null && !override.isBlank()) return Path.of(override);
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path root = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
        return root.resolve("opensmpsdeck/transcribers.json");
    }

    public static List<String> configuredNames() throws IOException {
        Path config = defaultConfig();
        String override = System.getenv(CONFIG_ENV);
        if ((override == null || override.isBlank()) && Files.notExists(config, LinkOption.NOFOLLOW_LINKS))
            return List.of();
        return List.copyOf(commands(config).keySet());
    }

    private static Map<String, List<String>> commands(Path file) throws IOException {
        JsonElement tree = ReconstructionArtifacts.readTree(file);
        require(tree != null && tree.isJsonObject(), file.toString(), "expected an object mapping tool names to commands");
        var commands = new TreeMap<String, List<String>>();
        for (var entry : tree.getAsJsonObject().entrySet()) {
            String at = file + "." + entry.getKey();
            var value = entry.getValue();
            require(LABEL.matcher(entry.getKey()).matches(), at, "invalid tool name");
            require(value.isJsonArray() && !value.getAsJsonArray().isEmpty(), at, "expected a nonempty argument array");
            var command = new ArrayList<String>();
            for (var arg : value.getAsJsonArray()) {
                require(arg instanceof JsonPrimitive scalar && scalar.isString() && !scalar.getAsString().contains("\0"),
                        at, "expected string arguments");
                command.add(arg.getAsString());
            }
            require(!command.getFirst().isBlank() && !PLACEHOLDER.matcher(command.getFirst()).find(),
                    at, "expected a fixed executable");
            for (String required : List.of("{input}", "{output}"))
                require(command.stream().skip(1).anyMatch(arg -> arg.contains(required)), at, "missing " + required);
            commands.put(entry.getKey(), List.copyOf(command));
        }
        return commands;
    }

    static void validatePart(int id, List<MidiScore.Part> parts) {
        require(parts.stream().anyMatch(part -> part.id() == id && !part.drums()),
                "external notes", "expected an existing melodic MIDI part, got " + id);
    }

    static Map<Integer, PartNotes> collect(MidiScore score, Map<Integer, String> stems,
                                           String name, Path config, Map<Integer, String> roles, String device,
                                           Map<Integer, Import> imports) throws IOException {
        return collect(score, stems, name, config, roles, device, imports, null);
    }

    static Map<Integer, PartNotes> collect(MidiScore score, Map<Integer, String> stems,
                                           String name, Path config, Map<Integer, String> roles, String device,
                                           Map<Integer, Import> imports, ReconstructionManifest.Capture capture) throws IOException {
        if (name == null && imports.isEmpty()) return null;
        for (int id : imports.keySet()) validatePart(id, score.parts());
        for (var entry : roles.entrySet()) {
            int id = entry.getKey();
            validatePart(id, score.parts());
            require(!imports.containsKey(id), name, "already importing notes for part " + id);
            require(stems.containsKey(id), name, "part " + id + " requires --stem " + id + "=WAV");
            require(LABEL.matcher(entry.getValue()).matches(), name, "invalid role for part " + id);
        }
        var result = new TreeMap<Integer, PartNotes>();
        for (var entry : imports.entrySet()) {
            var source = entry.getValue();
            Path recorded = capture == null ? source.file : capture.external(entry.getKey(), source.file, true);
            var notes = readNotes(recorded);
            result.put(entry.getKey(), new PartNotes(new Evidence("import", null, null, null,
                    ReconstructionCli.audioHash(recorded), null, metadata(source.file), notes.size(), source.replace), notes));
        }
        if (name != null) {
            Path file = config == null ? defaultConfig() : config;
            try {
                require(LABEL.matcher(device).matches(), name, "invalid device");
                var template = commands(file).get(name);
                require(template != null, name, "transcriber is not configured in " + file);
                String configHash = ReconstructionCli.audioHash(file);
                if (capture != null) capture.config(file);
                for (var entry : roles.entrySet()) {
                    int part = entry.getKey();
                    result.put(part, run(template, name, configHash, Path.of(stems.get(part)), entry.getValue(), device, part, capture));
                }
            } catch (IllegalArgumentException | IOException error) {
                throw new IOException("Transcriber " + name + ": " + error.getMessage(), error);
            }
        }
        return result;
    }

    private static PartNotes run(List<String> template, String name, String configHash,
                                 Path input, String role, String device, int part,
                                 ReconstructionManifest.Capture capture) throws IOException {
        String inputHash = ReconstructionCli.audioHash(input);
        Path scratch = Files.createTempDirectory("opensmpsdeck-transcriber-");
        Path output = scratch.resolve("notes.json");
        Path log = scratch.resolve("stderr");
        try {
            var values = Map.of("input", input.toString(), "output", output.toString(), "role", role, "device", device);
            var command = template.stream().map(arg -> PLACEHOLDER.matcher(arg).replaceAll(match ->
                    java.util.regex.Matcher.quoteReplacement(values.get(match.group(1))))).toList();
            var process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(log.toFile()).start();
            int status;
            try {
                process.getOutputStream().close();
                status = process.waitFor();
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("command interrupted", interrupted);
            }
            if (status != 0) {
                try (var errors = Files.newInputStream(log)) {
                    String detail = new String(errors.readNBytes(4096), StandardCharsets.UTF_8).strip();
                    throw new IOException("command exited " + status + (detail.isEmpty() ? "" : ": " + detail));
                }
            }
            require(Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS), name, "command did not produce notes.json");
            Path recorded = capture == null ? output : capture.external(part, output, false);
            var notes = readNotes(recorded);
            return new PartNotes(new Evidence(name, role, device, inputHash, ReconstructionCli.audioHash(recorded),
                    configHash, metadata(output), notes.size(), true), notes);
        } finally {
            // The private directory also lets adapters use temporary output files.
            try (var files = Files.walk(scratch)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Map<String, String> metadata(Path output) throws IOException {
        Path sidecar = output.resolveSibling(output.getFileName() + ".meta.json");
        if (Files.notExists(sidecar, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        JsonElement tree = ReconstructionArtifacts.readTree(sidecar);
        require(tree instanceof JsonObject, sidecar.toString(), "expected a metadata object");
        var metadata = new TreeMap<String, String>();
        for (var entry : tree.getAsJsonObject().entrySet()) {
            var value = entry.getValue();
            metadata.put(entry.getKey(), value instanceof JsonPrimitive p && p.isString() ? p.getAsString() : value.toString());
        }
        return metadata;
    }

    static List<RawNote> readNotes(Path file) throws IOException {
        String filename = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return readNotes(file, filename.endsWith(".mid") || filename.endsWith(".midi"));
    }

    static List<RawNote> readNotes(Path file, boolean midiFile) throws IOException {
        var notes = new ArrayList<RawNote>();
        if (midiFile) {
            var midi = MidiScore.read(file);
            var melodic = midi.parts().stream().filter(part -> !part.drums()).map(MidiScore.Part::id).toList();
            require(melodic.size() <= 1, file.toString(), "expected a MIDI file with at most one melodic part");
            for (var note : midi.notes()) {
                if (melodic.contains(note.partId()) && note.endUs() > note.startUs())
                    notes.add(new RawNote(note.startUs() / 1e6, note.endUs() / 1e6, note.pitch(), note.velocity()));
            }
        } else {
            var tree = ReconstructionArtifacts.readTree(file);
            require(tree != null && tree.isJsonArray(), file.toString(), "expected [[start, end, pitch], ...]");
            for (var element : tree.getAsJsonArray()) {
                String at = file + "[" + notes.size() + "]";
                require(element.isJsonArray() && element.getAsJsonArray().size() == 3, at, "expected [start, end, pitch]");
                var row = element.getAsJsonArray();
                double start = number(row.get(0), at);
                double end = number(row.get(1), at);
                double pitch = number(row.get(2), at);
                require(pitch == Math.rint(pitch) && pitch >= 0 && pitch <= 127, at, "expected a MIDI pitch in 0..127");
                notes.add(new RawNote(start, end, (int) pitch, 96));
            }
        }
        for (var note : notes) validateNote(note, file.toString());
        return List.copyOf(notes);
    }

    static void validateNote(RawNote note, String at) {
        require(Double.isFinite(note.start) && Double.isFinite(note.end) && note.start >= 0 && note.end > note.start,
                at, "expected finite, nonnegative start and strictly later end");
        require(note.pitch >= 0 && note.pitch <= 127 && note.velocity >= 1 && note.velocity <= 127,
                at, "invalid pitch or velocity");
    }

    private static double number(JsonElement value, String at) {
        require(value instanceof JsonPrimitive p && p.isNumber(), at, "expected numeric note values");
        return value.getAsDouble();
    }
}
