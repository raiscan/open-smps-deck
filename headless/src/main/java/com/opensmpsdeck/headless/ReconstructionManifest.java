package com.opensmpsdeck.headless;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;

final class ReconstructionManifest {
    static final String FILE = "manifest.json";
    private static final String SNAPSHOTS = "snapshots";

    record Tool(String version, String commit) {}
    record Input(String basename, @Nullable String path, @Nullable String sha256, @Nullable Long bytes,
                 @Nullable String snapshot) {}
    enum Encoding { json, midi, parsedLegacy }
    record External(Input file, Encoding encoding, ExternalTranscribers.Evidence provenance) {}
    record LegacyNotes(int version, List<ExternalTranscribers.RawNote> notes) {}
    record Run(String from, @Nullable Double harmonyMaxHz) {}
    record Manifest(int version, Tool tool, long durationUs, ReconstructionStages.Inputs options,
                    Map<String, Input> inputs, Map<Integer, String> partToStem,
                    Map<Integer, External> externalNotes, List<String> originalArguments,
                    @Nullable Input transcriberConfig, Run lastRun) {
        Manifest withLastRun(Run run) {
            return new Manifest(version, tool, durationUs, options, inputs, partToStem, externalNotes,
                    originalArguments, transcriberConfig, run);
        }
    }

    record Replay(Manifest manifest, ReconstructionStages.Inputs inputs, Map<String, Path> snapshots) {}

    private ReconstructionManifest() {}

    static final class Capture implements AutoCloseable {
        private Path temporary;
        private final Map<String, Path> copies = new LinkedHashMap<>();
        private final Map<Integer, Input> external = new TreeMap<>();
        private final Map<Integer, Encoding> encodings = new TreeMap<>();
        private Input config;

        Path external(int part, Path source, boolean imported) throws IOException {
            Encoding encoding = encoding(source.getFileName().toString());
            Input input = snapshot(source, imported, encoding == Encoding.midi ? ".mid" : ".json");
            external.put(part, input);
            encodings.put(part, encoding);
            return copies.get(input.snapshot);
        }

        void config(Path source) throws IOException {
            config = evidence(source);
        }

        private Path temporary() throws IOException {
            if (temporary == null) temporary = Files.createTempDirectory("opensmpsdeck-manifest-");
            return temporary;
        }

        private Input snapshot(Path source, boolean storePath, String extension) throws IOException {
            // Hash the copied bytes, which are also the bytes the transcriber importer reads.
            Path copy = Files.createTempFile(temporary(), "input-", extension);
            Files.copy(source, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String hash = ReconstructionCli.audioHash(copy);
            String snapshot = SNAPSHOTS + "/" + hash + extension;
            copies.putIfAbsent(snapshot, copy);
            return new Input(source.getFileName().toString(), storePath ? source.toAbsolutePath().normalize().toString() : null,
                    hash, Files.size(copy), snapshot);
        }

        void write(Path work, ReconstructionStages.Score score, List<String> argv, Run run) throws IOException {
            var inputs = score.inputs();
            var source = new LinkedHashMap<String, Input>();
            source.put("midi", evidence(Path.of(inputs.midi())));
            source.put("wav", evidence(Path.of(inputs.wav())));
            source.put("catalogue", evidence(Path.of(inputs.catalogue())));
            var stems = new TreeMap<Integer, String>();
            for (var entry : inputs.stems().entrySet()) {
                String key = "stem-" + entry.getKey();
                source.put(key, evidence(Path.of(entry.getValue())));
                stems.put(entry.getKey(), key);
            }
            if (inputs.candidates() != null) {
                Path candidates = Path.of(inputs.candidates());
                source.put("candidates", Files.isRegularFile(candidates) ? snapshot(candidates, true, ".json") : evidence(candidates));
            }
            var notes = new TreeMap<Integer, External>();
            if (inputs.externalNotes() != null) for (var entry : inputs.externalNotes().entrySet()) {
                int part = entry.getKey();
                var input = external.get(part);
                var encoding = encodings.get(part);
                if (input == null) {
                    // Old work dirs saved parsed notes but did not retain the original file.
                    Path parsed = Files.createTempFile(temporary(), "legacy-notes-", ".json");
                    ReconstructionArtifacts.write(parsed, new LegacyNotes(VERSION, entry.getValue().notes()));
                    input = snapshot(parsed, false, ".json");
                    input = new Input("legacy-part-" + part + ".json", null, input.sha256, input.bytes, input.snapshot);
                    encoding = Encoding.parsedLegacy;
                }
                notes.put(part, new External(input, encoding, entry.getValue().provenance()));
            }
            persistCopies(work);
            var manifest = new Manifest(VERSION, tool(), score.durationUs(), withPaths(inputs, inputs.midi(), inputs.wav(),
                    inputs.catalogue(), inputs.stems(), inputs.candidates(), null), source, stems, notes, argv, config, run);
            ReconstructionArtifacts.write(work.resolve(FILE), manifest);
        }

        private void persistCopies(Path work) throws IOException {
            if (copies.isEmpty()) return;
            Path directory = work.resolve(SNAPSHOTS);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Expected a private snapshot directory: " + directory);
            for (var copy : copies.entrySet()) {
                Path target = work.resolve(copy.getKey());
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(copy.getValue(), target);
                } else if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.mismatch(copy.getValue(), target) != -1) {
                    throw new IOException("Snapshot conflicts with existing file: " + target);
                }
            }
        }

        @Override public void close() throws IOException {
            if (temporary == null) return;
            try (var files = Files.list(temporary)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.delete(temporary);
        }
    }

    static void resumed(Path work, ReconstructionStages.Score score, Run run, Capture capture) throws IOException {
        Path file = work.resolve(FILE);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            var manifest = ReconstructionArtifacts.read(file, Manifest.class);
            ReconstructionArtifacts.write(file, manifest.withLastRun(run));
        } else {
            capture.write(work, score, List.of(), run);
        }
    }

    static Replay resolve(Path work, Path newWork, List<Path> search) throws IOException {
        var manifest = ReconstructionArtifacts.read(work.resolve(FILE), Manifest.class);
        var required = new LinkedHashMap<>(manifest.inputs);
        for (String key : List.of("midi", "wav", "catalogue"))
            ReconstructionArtifacts.require(required.containsKey(key), FILE, "missing input entry " + key);
        for (String key : manifest.partToStem.values())
            ReconstructionArtifacts.require(required.containsKey(key), FILE, "missing stem entry " + key);
        manifest.externalNotes.forEach((part, external) -> required.put("externalNotes[" + part + "]", external.file));
        var paths = new LinkedHashMap<String, Path>();
        for (var entry : required.entrySet()) {
            var input = entry.getValue();
            Path original = input.path == null ? null : Path.of(input.path);
            Path copy = snapshotPath(work, input);
            if (matches(original, input)) paths.put(entry.getKey(), original);
            else if (matches(copy, input)) paths.put(entry.getKey(), copy);
        }
        if (paths.size() != required.size()) search(search, required, paths);
        if (paths.size() != required.size()) {
            var missing = new ArrayList<String>();
            for (var entry : required.entrySet()) {
                if (paths.containsKey(entry.getKey())) continue;
                var input = entry.getValue();
                missing.add(entry.getKey() + ": " + input.basename + " ("
                        + (input.sha256 == null ? "no recorded SHA-256" : "SHA-256 " + input.sha256)
                        + (input.path == null ? "" : ", original " + input.path)
                        + (input.snapshot == null ? "" : ", snapshot " + input.snapshot) + ")");
            }
            throw new IOException("Missing or changed reconstruction inputs:\n  " + String.join("\n  ", missing));
        }

        var stems = new TreeMap<Integer, String>();
        manifest.partToStem.forEach((part, key) -> stems.put(part, paths.get(key).toString()));
        var notes = new TreeMap<Integer, ExternalTranscribers.PartNotes>();
        var external = new TreeMap<Integer, External>();
        for (var entry : manifest.externalNotes.entrySet()) {
            var saved = entry.getValue();
            var path = paths.get("externalNotes[" + entry.getKey() + "]");
            var raw = saved.encoding == Encoding.parsedLegacy
                    ? ReconstructionArtifacts.read(path, LegacyNotes.class).notes()
                    : ExternalTranscribers.readNotes(path, saved.encoding == Encoding.midi);
            notes.put(entry.getKey(), new ExternalTranscribers.PartNotes(saved.provenance, raw));
            external.put(entry.getKey(), saved);
        }
        var copies = new LinkedHashMap<String, Path>();
        for (var entry : required.entrySet()) {
            String snapshot = entry.getValue().snapshot;
            if (snapshot != null) copies.put(snapshot, paths.get(entry.getKey()));
        }
        Input candidateInput = manifest.inputs.get("candidates");
        String candidates = candidateInput == null ? null : candidateInput.snapshot == null
                ? paths.get("candidates").toString() : newWork.resolve(candidateInput.snapshot).toString();
        var inputs = withPaths(manifest.options, paths.get("midi").toString(), paths.get("wav").toString(),
                paths.get("catalogue").toString(), stems, candidates, notes.isEmpty() ? null : notes);
        var relocated = new LinkedHashMap<String, Input>();
        for (var entry : manifest.inputs.entrySet()) {
            var original = entry.getValue();
            relocated.put(entry.getKey(), relocated(work, original, paths.get(entry.getKey())));
        }
        external.replaceAll((part, saved) -> new External(
                relocated(work, saved.file, paths.get("externalNotes[" + part + "]")), saved.encoding, saved.provenance));
        var replayed = new Manifest(VERSION, tool(), manifest.durationUs,
                withPaths(inputs, inputs.midi(), inputs.wav(), inputs.catalogue(), stems, candidates, null),
                relocated, manifest.partToStem, external, manifest.originalArguments, manifest.transcriberConfig,
                new Run("transcribe", null));
        return new Replay(replayed, inputs, copies);
    }

    private static Input relocated(Path work, Input original, Path resolved) {
        // Leave the historical original pathname when the resolved file is a snapshot in the old work directory.
        String path = resolved.startsWith(work.resolve(SNAPSHOTS)) ? original.path : resolved.toString();
        return new Input(original.basename, path, original.sha256, original.bytes, original.snapshot);
    }

    static void writeReplay(Path newWork, Replay replay) throws IOException {
        try (var capture = new Capture()) {
            capture.copies.putAll(replay.snapshots);
            capture.persistCopies(newWork);
        }
        ReconstructionArtifacts.write(newWork.resolve(FILE), replay.manifest);
    }

    private static Path snapshotPath(Path work, Input input) {
        if (input.snapshot == null) return null;
        if (input.sha256 == null || !input.sha256.matches("[0-9a-f]{64}")
                || !(input.snapshot.equals(SNAPSHOTS + "/" + input.sha256 + ".json")
                || input.snapshot.equals(SNAPSHOTS + "/" + input.sha256 + ".mid")))
            throw new IllegalArgumentException("Invalid snapshot reference for " + input.basename);
        Path root = work.resolve(SNAPSHOTS);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null;
        Path path = work.resolve(input.snapshot);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? path : null;
    }

    private static void search(List<Path> roots, Map<String, Input> required, Map<String, Path> resolved) throws IOException {
        var bySize = new HashMap<Long, List<Map.Entry<String, Input>>>();
        for (var entry : required.entrySet()) {
            if (!resolved.containsKey(entry.getKey()) && entry.getValue().bytes != null)
                bySize.computeIfAbsent(entry.getValue().bytes, ignored -> new ArrayList<>()).add(entry);
        }
        for (Path root : roots) {
            if (resolved.size() == required.size()) break;
            if (!Files.isDirectory(root)) throw new IOException("Search directory does not exist: " + root);
            try (var walk = Files.walk(root.toRealPath())) {
                var it = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).iterator();
                while (it.hasNext() && resolved.size() < required.size()) {
                    Path path = it.next();
                    var candidates = bySize.get(Files.size(path));
                    if (candidates == null || candidates.stream().allMatch(entry -> resolved.containsKey(entry.getKey()))) continue;
                    String hash = ReconstructionCli.audioHash(path);
                    for (var entry : candidates) if (hash.equals(entry.getValue().sha256))
                        resolved.putIfAbsent(entry.getKey(), path);
                }
            }
        }
    }

    private static boolean matches(Path path, Input input) throws IOException {
        return path != null && input.sha256 != null && input.bytes != null && Files.isRegularFile(path)
                && Files.size(path) == input.bytes && ReconstructionCli.audioHash(path).equals(input.sha256);
    }

    private static Input evidence(Path path) throws IOException {
        boolean present = Files.isRegularFile(path);
        return new Input(path.getFileName().toString(), path.toAbsolutePath().normalize().toString(),
                present ? ReconstructionCli.audioHash(path) : null, present ? Files.size(path) : null, null);
    }

    private static Encoding encoding(String basename) {
        String lower = basename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mid") || lower.endsWith(".midi") ? Encoding.midi : Encoding.json;
    }

    private static Tool tool() throws IOException {
        var build = new Properties();
        try (var resource = ReconstructionManifest.class.getResourceAsStream("/opensmpsdeck-build.properties")) {
            if (resource != null) build.load(resource);
        }
        return new Tool(build.getProperty("git.build.version", "unknown"), build.getProperty("git.commit.id.full", "unknown"));
    }

    static ReconstructionStages.Inputs withPaths(ReconstructionStages.Inputs options, String midi, String wav,
                                                String catalogue, Map<Integer, String> stems, String candidates,
                                                Map<Integer, ExternalTranscribers.PartNotes> external) {
        return new ReconstructionStages.Inputs(midi, wav, catalogue, options.name(), options.ticksPerBeat(),
                options.keepOctaves(), options.fmOnly(), options.wholePartVoices(), options.audioRefine(), stems,
                candidates, options.harmonyMaxHz(), options.grid(), options.audioRepeats(), external,
                options.leadPart(), options.fastRunsOff(), options.drumSource());
    }

    static void run(String[] args, PrintStream out) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("rerun expects WORKDIR NEWWORKDIR [--search DIR ...]");
        var search = new ArrayList<Path>();
        boolean keepVoicing = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--keep-voicing")) {
                keepVoicing = true;
                continue;
            }
            if (!args[i].equals("--search") || ++i == args.length)
                throw new IllegalArgumentException("rerun expects --search DIR or --keep-voicing");
            search.add(Path.of(args[i]).toAbsolutePath().normalize());
        }
        ReconstructionStages.rerun(Path.of(args[0]).toAbsolutePath().normalize(),
                Path.of(args[1]).toAbsolutePath().normalize(), search, keepVoicing, out);
    }
}
