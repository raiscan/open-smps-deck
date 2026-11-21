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

import static com.opensmpsdeck.headless.ExternalCommands.LABEL;
import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** User-local, explicitly selected transcription commands and their replayable outputs. */
public final class ExternalTranscribers {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(input|output|role|device)}");
    private static final String CONFIG_ENV = "OPENSMPSDECK_TRANSCRIBERS";

    record RawNote(double start, double end, int pitch, int velocity) {}
    record Evidence(String tool, @Nullable String role, @Nullable String device,
                    @Nullable String inputSha256, String outputSha256, @Nullable String configSha256,
                    Map<String, String> metadata, int noteCount, boolean replace, @Nullable Boolean addedStem) {
        Evidence(String tool, String role, String device, String inputSha256, String outputSha256, String configSha256,
                 Map<String, String> metadata, int noteCount, boolean replace) {
            this(tool, role, device, inputSha256, outputSha256, configSha256, metadata, noteCount, replace, null);
        }
    }
    record PartNotes(Evidence provenance, List<RawNote> notes) {}
    record Import(Path file, boolean replace) {}

    private ExternalTranscribers() {}

    private static Path defaultConfig() {
        return ExternalCommands.defaultConfig(CONFIG_ENV, "transcribers.json");
    }

    record Config(Path file, Map<String, List<String>> tonal, Map<String, List<String>> drums,
                  Map<String, String> defaults) {
        String drumName(String override) {
            return override == null ? defaults.getOrDefault("drums", "spectral") : override;
        }

        String fallback(String override, boolean audioOnly) {
            if (override != null) require(tonal.containsKey(override), file.toString(), "unknown transcriber " + override);
            return override != null ? override : audioOnly && tonal.size() == 1 ? tonal.keySet().iterator().next() : null;
        }

        String forRole(String role, String fallback, Map<String, String> overrides, boolean explicitFallback) {
            String key = role.toLowerCase(java.util.Locale.ROOT);
            String name = overrides.get(key);
            if (name == null && !explicitFallback) name = defaults.get(key);
            if (name == null) name = fallback;
            require(!tonal.isEmpty(), file.toString(), "reconstruction needs a configured transcriber");
            require(name != null && names(key, name).stream().allMatch(tonal::containsKey), file.toString(),
                    "select a transcriber for role " + role + " from " + tonal.keySet());
            return name;
        }
    }

    public static List<String> configuredNames() throws IOException {
        return List.copyOf(config(null, true).tonal.keySet());
    }

    public static List<String> configuredDrums() throws IOException {
        var names = new ArrayList<>(config(null, true).drums.keySet());
        names.addFirst("spectral");
        return List.copyOf(names);
    }

    static Config config(Path path, boolean optional) throws IOException {
        Path file = (path == null ? defaultConfig() : path).toAbsolutePath().normalize();
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            String override = System.getenv(CONFIG_ENV);
            if (optional && path == null && (override == null || override.isBlank()))
                return new Config(file, Map.of(), Map.of(), Map.of());
            throw new IllegalArgumentException("External transcription needs a configured transcriber. No configuration at " + file);
        }
        JsonElement tree = ReconstructionArtifacts.readTree(file);
        require(tree != null && tree.isJsonObject(), file.toString(), "expected a transcriber configuration object");
        var entries = tree.getAsJsonObject();
        var roleEntries = object(entries.remove("defaults"), file + ".defaults");
        var drumEntries = object(entries.remove("drumTranscribers"), file + ".drumTranscribers");
        var tonal = ExternalCommands.commands(entries, file.toString(), PLACEHOLDER, "{input}", "{output}");
        var drums = ExternalCommands.commands(drumEntries, file + ".drumTranscribers", PLACEHOLDER, "{input}", "{output}");
        require(!drums.containsKey("spectral"), file.toString(), "spectral is a built-in drum detector");
        var defaults = new TreeMap<String, String>();
        for (var entry : roleEntries.entrySet()) {
            String role = ReconstructionAudioOnly.label(entry.getKey());
            var value = entry.getValue();
            require(value instanceof JsonPrimitive p && p.isString(), file.toString(), "expected a transcriber name");
            String name = value.getAsString();
            require(role.equals("drums") ? name.equals("spectral") || drums.containsKey(name)
                            : names(role, name).stream().allMatch(tonal::containsKey),
                    file.toString(), "unknown transcriber " + name + " for role " + role);
            require(defaults.putIfAbsent(role, name) == null, file.toString(), "duplicate default for " + role);
        }
        return new Config(file, tonal, drums, defaults);
    }

    private static JsonObject object(JsonElement element, String at) {
        if (element == null) return new JsonObject();
        require(element.isJsonObject(), at, "expected an object");
        return element.getAsJsonObject();
    }

    private static List<String> names(String role, String value) {
        var result = List.of(value.split(",", -1));
        require((result.size() == 1 || result.size() <= 3 && role.equals("vocals")
                        && new java.util.HashSet<>(result).size() == result.size())
                        && result.stream().allMatch(name -> LABEL.matcher(name).matches()),
                "Transcribers", "expected one command name or up to three distinct commands for vocals");
        return result;
    }

    static void putRoleOverride(Map<String, String> overrides, String assignment) {
        String[] pair = assignment.split("=", -1);
        require(pair.length == 2, "--transcriber-for", "expected ROLE=NAME");
        String role = ReconstructionAudioOnly.label(pair[0]);
        names(role, pair[1]);
        require(overrides.putIfAbsent(role, pair[1]) == null, "--transcriber-for", "duplicate role " + role);
    }

    static void validatePart(int id, List<MidiScore.Part> parts) {
        require(parts.stream().anyMatch(part -> part.id() == id && !part.drums()),
                "external notes", "expected an existing melodic MIDI part, got " + id);
    }

    static Map<Integer, PartNotes> collect(MidiScore score, Map<Integer, String> stems,
                                           String name, Path config, Map<Integer, String> roles, String device,
                                           Map<Integer, Import> imports) throws IOException {
        return collect(score, stems, name, name == null ? null : config(config, false), Map.of(),
                false, roles, device, imports, null);
    }

    static Map<Integer, PartNotes> collect(MidiScore score, Map<Integer, String> stems,
                                           String name, Config config, Map<String, String> overrides, boolean audioOnly,
                                           Map<Integer, String> roles, String device, Map<Integer, Import> imports,
                                           ReconstructionManifest.Capture capture) throws IOException {
        if (roles.isEmpty() && imports.isEmpty()) return null;
        String context = "External transcription";
        for (int id : imports.keySet()) validatePart(id, score.parts());
        for (var entry : roles.entrySet()) {
            int id = entry.getKey();
            validatePart(id, score.parts());
            require(!imports.containsKey(id), context, "already importing notes for part " + id);
            require(stems.containsKey(id), context, "part " + id + " requires --stem " + id + "=WAV");
            require(LABEL.matcher(entry.getValue()).matches(), context, "invalid role for part " + id);
        }
        var result = new TreeMap<Integer, PartNotes>();
        for (var entry : imports.entrySet()) {
            var source = entry.getValue();
            Path recorded = capture == null ? source.file : capture.external(entry.getKey(), source.file, true);
            var notes = readNotes(recorded);
            result.put(entry.getKey(), new PartNotes(new Evidence("import", null, null, null,
                    ReconstructionCli.audioHash(recorded), null, metadata(source.file), notes.size(), source.replace), notes));
        }
        if (!roles.isEmpty()) {
            require(LABEL.matcher(device).matches(), context, "invalid device");
            String fallback = config.fallback(name, audioOnly);
            for (String role : roles.values()) config.forRole(role, fallback, overrides, name != null);
            String configHash = ReconstructionCli.audioHash(config.file);
            if (capture != null) capture.config(config.file);
            for (var entry : new TreeMap<>(roles).entrySet()) {
                int part = entry.getKey();
                String role = entry.getValue();
                String selected = config.forRole(role, fallback, overrides, name != null);
                try {
                    Path input = Path.of(stems.get(part));
                    var selectedNames = names(role.toLowerCase(java.util.Locale.ROOT), selected);
                    if (selectedNames.size() == 1) {
                        result.put(part, run(config.tonal.get(selected), selected, configHash, input, role, device, part, capture));
                    } else {
                        require(audioOnly, context, "multi-command vocal selection requires audio-only mode");
                        var contenders = new ArrayList<PartNotes>();
                        for (String tool : selectedNames)
                            contenders.add(run(config.tonal.get(tool), tool, configHash, input, role, device, part, null));
                        result.put(part, combine(contenders, selected, configHash, input, device, part, capture));
                    }
                } catch (IllegalArgumentException | IOException error) {
                    throw new IOException("Transcriber " + selected + " for " + role + ": " + error.getMessage(), error);
                }
            }
        }
        return result;
    }

    private static PartNotes combine(List<PartNotes> candidates, String names, String configHash, Path input,
                                     String device, int part, ReconstructionManifest.Capture capture) throws IOException {
        var notes = candidates.stream().flatMap(candidate -> candidate.notes.stream()).toList();
        var metadata = new TreeMap<String, String>();
        var gson = new com.google.gson.Gson();
        metadata.put("vocalFirstNoteCount", Integer.toString(candidates.getFirst().notes.size()));
        metadata.put("vocalCandidates", gson.toJson(candidates.stream().map(PartNotes::provenance).toList()));
        for (var candidate : candidates) {
            var f0 = candidate.provenance.metadata().get("f0Midi10ms");
            if (f0 != null && !f0.isBlank()) metadata.putIfAbsent("f0Midi10ms", f0);
        }
        Path scratch = Files.createTempDirectory("opensmpsdeck-vocals-");
        Path combined = scratch.resolve("vocal-candidates.json");
        try {
            Files.writeString(combined, gson.toJson(notes.stream()
                    .map(note -> List.of(note.start, note.end, note.pitch)).toList()));
            Path recorded = capture == null ? combined : capture.external(part, combined, false);
            return new PartNotes(new Evidence(names, "vocals", device, ReconstructionCli.audioHash(input),
                    ReconstructionCli.audioHash(recorded), configHash, metadata, notes.size(), true), notes);
        } finally {
            Files.deleteIfExists(combined);
            Files.deleteIfExists(scratch);
        }
    }

    private static PartNotes run(List<String> template, String name, String configHash,
                                 Path input, String role, String device, int part,
                                 ReconstructionManifest.Capture capture) throws IOException {
        String inputHash = ReconstructionCli.audioHash(input);
        return invoke(template, name, input, role, device, output -> {
            Path recorded = capture == null ? output : capture.external(part, output, false);
            var notes = readNotes(recorded);
            return new PartNotes(new Evidence(name, role, device, inputHash, ReconstructionCli.audioHash(recorded),
                    configHash, metadata(output), notes.size(), true), notes);
        });
    }

    @FunctionalInterface
    interface OutputReader<T> {
        T read(Path path) throws IOException;
    }

    static <T> T invoke(List<String> template, String name, Path input, String role, String device,
                        OutputReader<T> reader) throws IOException {
        require(LABEL.matcher(device).matches(), name, "invalid device");
        Path scratch = Files.createTempDirectory("opensmpsdeck-transcriber-");
        Path output = scratch.resolve("notes.json");
        Path log = scratch.resolve("stderr");
        try {
            var values = Map.of("input", input.toString(), "output", output.toString(), "role", role, "device", device);
            var command = ExternalCommands.expand(template, PLACEHOLDER, values);
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
            return reader.read(output);
        } finally {
            // The private directory also lets adapters use temporary output files.
            try (var files = Files.walk(scratch)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    static Map<String, String> metadata(Path output) throws IOException {
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

    static double number(JsonElement value, String at) {
        require(value instanceof JsonPrimitive p && p.isNumber(), at, "expected numeric note values");
        return value.getAsDouble();
    }
}
