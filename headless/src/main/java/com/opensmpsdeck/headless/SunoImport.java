package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Detect and validate Suno downloads and separated stems without modifying the source folder. */
public final class SunoImport {
    public static final String FILE = "import.json";
    private static final int MAX_FILES = 256;
    private static final int MAX_MIDI_PARTS = 128;
    private static final double NAME_BONUS = .05;
    private static final double FAMILY_BONUS = .03;
    private static final double SHARED_PART_AUDIO = .30;
    private static final double SHARED_PART_MARGIN = .08;
    private static final String AUDIO_ONLY = "needs_audio_only_transcription";
    private static final String CATALOGUE = "${CATALOGUE}";

    public enum Status {
        ok("✓"), warning("⚠"), error("✗");

        private final String icon;

        Status(String icon) { this.icon = icon; }
        public String icon() { return icon; }
    }

    public record Issue(Status status, String code, String message) {}
    public record Part(int id, String name, int program, boolean drums, String family) {}
    public record Candidate(int part, double onsetCorrelation, double pitchCorrelation, double audioScore,
                            String labelEvidence) {}
    public record FileResult(String file, String sha256, String role, String family, Status status, String code,
                             String message, Integer part, String decidedBy, Map<String, Object> evidence,
                             List<Issue> issues) {
        public boolean stem() { return role.equals("stem"); }
    }

    public record Report(int version, String folder, String workDir, List<FileResult> files, List<Part> parts,
                         List<PartGap> gaps, List<Issue> issues, Status status, Integer leadPart,
                         String leadEvidence, Alignment alignment, List<String> runArguments,
                         String runCommand, boolean catalogueRequired, RunRequest runRequest) {
        public boolean canRun() { return status != Status.error && !runArguments.isEmpty(); }

        public JsonObject json() {
            return new GsonBuilder().disableHtmlEscaping().create().toJsonTree(this).getAsJsonObject();
        }

        public void print(PrintStream out) {
            for (var file : files) {
                String target = file.part == null ? "" : " → part " + file.part;
                out.printf("%s %s [%s%s] %s (%s)%n", file.status.icon(), printable(file.file), file.role,
                        target, printable(file.message), file.code);
                if (file.sha256 != null) out.println("  SHA-256 " + file.sha256);
                if (file.evidence.containsKey("durationSeconds")) {
                    out.printf(Locale.ROOT, "  %.3f s", file.evidence.get("durationSeconds"));
                    if (file.evidence.containsKey("sampleRate"))
                        out.printf(Locale.ROOT, " · %d Hz · %d channel(s) · RMS %.5f", file.evidence.get("sampleRate"),
                                file.evidence.get("channels"), file.evidence.get("rms"));
                    out.println();
                }
                if (file.evidence.get("assignment") instanceof AssignmentEvidence assignment) {
                    printChoice(out, "Winning score", assignment.winner);
                    if (assignment.runnerUp == null) out.println("  Runner-up stem: none");
                    else printChoice(out, "Runner-up stem " + printable(assignment.runnerUp.file), assignment.runnerUp);
                    if (assignment.alternativePart != null)
                        printChoice(out, "Alternative part " + assignment.alternativePart.part, assignment.alternativePart);
                } else if (file.part != null && file.evidence.get("candidates") instanceof List<?> candidates) {
                    for (var item : candidates) {
                        if (item instanceof Candidate candidate && candidate.part == file.part)
                            out.printf(Locale.ROOT, "  Audio evidence · onset %.3f · pitch %.3f · combined %.3f%n",
                                    candidate.onsetCorrelation, candidate.pitchCorrelation, candidate.audioScore);
                    }
                }
                for (var issue : file.issues) {
                    if (!issue.code.equals(file.code))
                        out.printf("  %s %s (%s)%n", issue.status.icon(), printable(issue.message), issue.code);
                }
            }
            for (var gap : gaps)
                out.printf("⚠ Part %d (%s): %s (%s)%n", gap.part, printable(gap.name), gap.message, gap.code);
            for (var issue : issues)
                out.printf("%s %s (%s)%n", issue.status.icon(), printable(issue.message), issue.code);
            if (alignment != null)
                out.printf(Locale.ROOT, "Mix/stem alignment: offset %+.3f s, correlation %.3f (zero lag %.3f, %d stems)%n",
                        alignment.offsetSeconds(), alignment.correlation(), alignment.zeroLagCorrelation(), alignment.stems());
            if (leadPart != null) out.printf("Lead candidate: part %d (%s)%n", leadPart, leadEvidence);
            if (runCommand != null) out.println("Run: " + runCommand);
        }
    }

    private static void printChoice(PrintStream out, String label, PairEvidence pair) {
        out.printf(Locale.ROOT, "  %s: %.3f (audio %.3f · onset %.3f · pitch %.3f · label %s)%n",
                label, pair.score, pair.audioScore, pair.onsetCorrelation, pair.pitchCorrelation, pair.labelEvidence);
    }

    public record RunRequest(String workDir, RunInputs inputs, Map<String, Object> options) {}
    public record RunInputs(String midi, String wav, String catalogue, Map<?, String> stems, Boolean audioOnly) {
        RunInputs(String midi, String wav, String catalogue, Map<Integer, String> stems) {
            this(midi, wav, catalogue, stems, null);
        }
    }

    public record Alignment(double offsetSeconds, double correlation, double zeroLagCorrelation, int stems) {}

    public record PartGap(int part, String name, Status status, String code, String message) {}

    private static final class Input {
        final Path path;
        final String file;
        final List<Issue> issues = new ArrayList<>();
        final Map<String, Object> evidence = new LinkedHashMap<>();
        String role = "other";
        String family;
        String sha256;
        Integer part;
        String decidedBy;
        double seconds;
        int rate;
        int channels;
        boolean excluded;
        boolean silent;
        List<Candidate> candidates = List.of();

        Input(Path root, Path path) {
            this.path = path;
            file = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        }

        void add(Status status, String code, String message) { issues.add(new Issue(status, code, message)); }

        boolean error() { return issues.stream().anyMatch(issue -> issue.status == Status.error); }

        FileResult result() {
            var top = issues.reversed().stream().max(Comparator.comparing(Issue::status)).orElseThrow();
            return new FileResult(file, sha256, role, family, top.status, top.code, top.message, part, decidedBy,
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(evidence)), List.copyOf(issues));
        }
    }

    private SunoImport() {}

    public static Report inspect(Path folder, Path workDir, Path catalogue, Map<String, Integer> assignments,
                                 Set<String> exclusions, ReconstructionStages.Progress progress) throws IOException {
        return inspect(folder, workDir, catalogue, assignments, exclusions, null, progress);
    }

    public static Report inspect(Path folder, Path workDir, Path catalogue, Map<String, Integer> assignments,
                                 Set<String> exclusions, String transcriber, ReconstructionStages.Progress progress) throws IOException {
        return inspect(folder, workDir, catalogue, assignments, exclusions, transcriber, false, null, Map.of(), progress);
    }

    public static Report inspect(Path folder, Path workDir, Path catalogue, Map<String, Integer> assignments,
                                 Set<String> exclusions, String transcriber, boolean audioOnly,
                                 String audioTranscriber, Map<String, String> stemRoles,
                                 ReconstructionStages.Progress progress) throws IOException {
        return inspect(folder, workDir, catalogue, assignments, exclusions, transcriber, audioOnly, audioTranscriber,
                stemRoles, Map.of(), null, null, null, progress);
    }

    public static Report inspect(Path folder, Path workDir, Path catalogue, Map<String, Integer> assignments,
                                 Set<String> exclusions, String transcriber, boolean audioOnly,
                                 String audioTranscriber, Map<String, String> stemRoles, Map<String, String> transcriberFor,
                                 String drumDetector, String device, Path configFile,
                                 ReconstructionStages.Progress progress) throws IOException {
        Path source = folder.toRealPath();
        if (!Files.isDirectory(source)) throw new IOException("Expected a Suno folder: " + source);
        Path work = prepareWork(source, workDir);
        Path index = catalogue == null ? null : catalogue.toAbsolutePath().normalize();
        var inputs = discover(source);
        boolean separated = inputs.stream().anyMatch(input -> input.file.equals(ExternalSeparators.PROVENANCE)
                && Files.isRegularFile(input.path, NOFOLLOW_LINKS));
        if (audioOnly) inputs.removeIf(input -> extension(input, ".mid") || extension(input, ".midi"));
        int total = inputs.size() * 2 + 2;
        int done = 0;
        progress.report(done, total, separated ? "Finding and hashing separated files" : "Finding and hashing Suno files");
        for (var input : inputs) {
            hash(input);
            progress.report(++done, total, "Scanned " + printable(input.file));
        }
        var issues = new ArrayList<Issue>();
        readMetadata(inputs);
        Input midi = select(inputs, "midi", List.of("suno", "suno-transcription"));
        Input mix = select(inputs, "mix", List.of("mix", "full mix", "full song", "master", "original"));
        var provenance = separated ? readSeparation(source, inputs) : null;
        classify(inputs, midi, mix);
        if (provenance != null) classifySeparated(inputs, provenance);
        applyExclusions(inputs, exclusions, issues);
        boolean separatedAudio = provenance != null && mix != null && midi == null;
        audioOnly |= separatedAudio;
        if (audioOnly && !assignments.isEmpty()) throw new IllegalArgumentException("--assign requires MIDI");
        if (!audioOnly && (audioTranscriber != null || !stemRoles.isEmpty() || !transcriberFor.isEmpty()
                || drumDetector != null || device != null || configFile != null))
            throw new IllegalArgumentException("These transcription and stem options require an audio-only import");
        if (audioOnly && transcriber != null)
            throw new IllegalArgumentException("--transcribe-unmapped requires MIDI. Use --transcriber for audio-only import");
        if (audioOnly && mix == null) issues.add(new Issue(Status.error, "missing_mix", "Add an aligned mix WAV"));
        if (!audioOnly) missingInputs(mix, midi, issues);

        MidiScore score = readMidi(midi);
        progress.report(++done, total, audioOnly ? "Checked stem metadata" : "Checked MIDI and stem metadata");
        if (score == null) score = new MidiScore(List.of(), List.of(), List.of());
        var parts = score.parts().stream().map(SunoImport::part).toList();
        float[] mixAudio = null;
        double[] sum = null;
        int stemCount = 0;
        for (var input : inputs) {
            checkCancelled();
            if (input == mix || input.role.equals("stem") && !input.excluded) {
                float[] audio = readAudio(input);
                if (audio != null) {
                    if (input == mix) mixAudio = audio;
                    else if (!input.silent) {
                        if (sum == null) sum = new double[mixAudio == null ? audio.length : mixAudio.length];
                        for (int i = 0; i < Math.min(audio.length, sum.length); i++) sum[i] += audio[i];
                        stemCount++;
                        if (!audioOnly) {
                            input.candidates = candidates(input, parts, SunoImportAudio.matches(audio, score));
                            input.evidence.put("candidates", input.candidates);
                        }
                    }
                }
            }
            progress.report(++done, total, "Checked " + printable(input.file));
        }

        checkDurations(inputs, mix, midi);
        var alignment = mixAudio == null || sum == null ? null : SunoImportAudio.align(mixAudio, sum, stemCount);
        checkAlignment(alignment, mix, issues);
        Set<Integer> claimed = map(inputs, parts, assignments, issues);
        var gaps = parts.stream().filter(part -> !claimed.contains(part.id))
                .map(part -> new PartGap(part.id, part.name, Status.warning, "part_without_stem",
                        mixPresent(inputs) ? "No matching stem. Reconstruction will use the mix."
                                : "No matching stem. A mix is needed for fallback."))
                .toList();
        var lead = lead(parts, score, claimed);
        RunRequest audioRequest = null;
        if (audioOnly) {
            try {
                audioRequest = audioRequest(work, index, mix, inputs, issues, audioTranscriber, stemRoles,
                        transcriberFor, drumDetector, device, configFile, separatedAudio);
            } catch (IllegalArgumentException | IOException e) {
                issues.add(new Issue(Status.error, "transcriber_unavailable", e.getMessage()));
            }
        }
        for (var input : inputs) finish(input, audioOnly);
        var files = inputs.stream().sorted(Comparator.comparing(input -> input.file)).map(Input::result).toList();
        Status status = java.util.stream.Stream.concat(files.stream().map(FileResult::status),
                        issues.stream().map(Issue::status))
                .max(Comparator.naturalOrder()).orElse(Status.ok);
        if (!gaps.isEmpty() && status == Status.ok) status = Status.warning;
        var request = audioOnly ? (status == Status.error ? null : audioRequest)
                : runRequest(work, index, mix, midi, inputs, lead, status, transcriber);
        var args = request == null ? List.<String>of() : runArguments(request);
        String command = args.isEmpty() ? null : "java -jar \"$DECK_JAR\" " + args.stream()
                .map(arg -> arg.equals(CATALOGUE) ? "\"$CATALOGUE\"" : shellQuote(arg))
                .collect(java.util.stream.Collectors.joining(" "));
        var report = new Report(1, source.toString(), work.toString(), files, parts, gaps, List.copyOf(issues),
                status, lead == null ? null : lead.part, lead == null ? null : lead.reason,
                alignment, args, command, index == null, request);
        checkCancelled();
        ReconstructionArtifacts.write(work.resolve(FILE), report.json());
        progress.report(total, total, "Import checks complete");
        return report;
    }

    public static Path run(Report report, Path catalogue, ReconstructionStages.Progress progress) throws IOException {
        if (!report.canRun()) throw new IllegalArgumentException("Import did not produce a runnable reconstruction request");
        var args = new ArrayList<>(report.runArguments.subList(2, report.runArguments.size()));
        if (report.catalogueRequired && catalogue == null)
            throw new IllegalArgumentException("Choose an instrument catalogue for reconstruction");
        if (catalogue != null) args.set(2, catalogue.toAbsolutePath().normalize().toString());
        Path source = Path.of(report.folder);
        var unmapped = report.runRequest.options.get("transcribeUnmapped");
        var audioStems = Boolean.TRUE.equals(report.runRequest.inputs.audioOnly)
                ? report.runRequest.inputs.stems.values() : List.<String>of();
        for (var file : report.files) {
            if (file.part == null && !List.of("mix", "midi").contains(file.role)
                    && !(unmapped instanceof Map<?, ?> stems && stems.containsKey(source.resolve(file.file).toString()))
                    && !audioStems.contains(source.resolve(file.file).toString())) continue;
            checkCancelled();
            Path path = source.resolve(file.file);
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path)
                    || !ReconstructionCli.audioHash(path, NOFOLLOW_LINKS).equals(file.sha256))
                throw new IOException("Input changed since import: " + printable(file.file) + ". Re-check the folder.");
        }
        ReconstructionStages.runForReview("run", args.toArray(String[]::new), progress);
        return Path.of(args.get(3)).resolve("arrangement.smps.json");
    }

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("reconstruct import expects FOLDER WORK_DIR");
        Path catalogue = null;
        boolean run = false, audioOnly = false;
        String transcriber = null, audioTranscriber = null;
        var stemRoles = new LinkedHashMap<String, String>();
        var transcriberFor = new TreeMap<String, String>();
        String drumDetector = null, device = null;
        Path configFile = null;
        var assignments = new LinkedHashMap<String, Integer>();
        var exclusions = new HashSet<String>();
        for (int i = 2; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--run")) {
                run = true;
                continue;
            }
            if (option.equals("--audio-only")) {
                audioOnly = true;
                continue;
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++i];
            switch (option) {
                case "--catalogue" -> catalogue = Path.of(value);
                case "--exclude" -> exclusions.add(value);
                case "--transcribe-unmapped" -> {
                    if (transcriber != null) throw new IllegalArgumentException("Duplicate transcriber option");
                    transcriber = value;
                }
                case "--transcriber" -> {
                    if (audioTranscriber != null) throw new IllegalArgumentException("Duplicate transcriber option");
                    audioTranscriber = value;
                }
                case "--transcriber-for" -> ExternalTranscribers.putRoleOverride(transcriberFor, value);
                case "--transcriber-config" -> configFile = Path.of(value).toAbsolutePath().normalize();
                case "--transcriber-device" -> device = value;
                case "--drum-detector" -> drumDetector = value;
                case "--stem-role" -> {
                    int split = value.lastIndexOf('=');
                    if (split < 1 || split == value.length() - 1)
                        throw new IllegalArgumentException("--stem-role expects FILE=ROLE");
                    if (stemRoles.putIfAbsent(value.substring(0, split), value.substring(split + 1)) != null)
                        throw new IllegalArgumentException("Duplicate stem role for " + value.substring(0, split));
                }
                case "--assign" -> {
                    int split = value.lastIndexOf('=');
                    if (split < 1 || split == value.length() - 1)
                        throw new IllegalArgumentException("--assign expects FILE=PART");
                    if (assignments.putIfAbsent(value.substring(0, split), Integer.parseInt(value.substring(split + 1))) != null)
                        throw new IllegalArgumentException("Duplicate assignment for " + value.substring(0, split));
                }
                default -> throw new IllegalArgumentException("Unknown import option " + option);
            }
        }
        var report = inspect(Path.of(args[0]), Path.of(args[1]), catalogue, assignments, exclusions, transcriber,
                audioOnly, audioTranscriber, stemRoles, transcriberFor, drumDetector, device, configFile, (a, b, c) -> {});
        report.print(out);
        out.println("Report: " + printable(report.workDir) + "/" + FILE);
        if (report.status == Status.error) throw new IllegalArgumentException("Import checks found errors");
        if (run && report.issues.stream().noneMatch(issue -> issue.code.equals(AUDIO_ONLY)))
            out.println(run(report, catalogue, (a, b, message) -> out.println(message)));
    }

    private static Path prepareWork(Path source, Path requested) throws IOException {
        Path work = requested.toAbsolutePath().normalize();
        // Resolve the closest existing ancestor before creating anything, including through symlinks.
        Path ancestor = work;
        while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        work = ancestor.toRealPath().resolve(ancestor.relativize(work));
        if (work.startsWith(source)) throw new IllegalArgumentException("Work directory must be outside the Suno folder");
        Files.createDirectories(work);
        Path report = work.resolve(FILE);
        if (Files.exists(report, NOFOLLOW_LINKS) && !Files.isRegularFile(report, NOFOLLOW_LINKS))
            throw new IOException("Expected a regular " + FILE + " in the work directory");
        return work;
    }

    private static List<Input> discover(Path root) throws IOException {
        var files = new ArrayList<Input>();
        collect(root, root, files);
        for (var entry : files.toArray(Input[]::new)) {
            if (entry.path.getFileName().toString().equalsIgnoreCase("stems")
                    && Files.isDirectory(entry.path, NOFOLLOW_LINKS)) collect(root, entry.path, files);
        }
        files.removeIf(input -> Files.isDirectory(input.path, NOFOLLOW_LINKS));
        files.sort(Comparator.comparing(input -> input.file));
        return files;
    }

    private static void hash(Input input) throws InterruptedIOException {
        checkCancelled();
        if (!Files.isRegularFile(input.path, NOFOLLOW_LINKS)) {
            input.add(Status.warning, "ignored_non_file", "Ignored a symbolic link or special file");
            return;
        }
        try {
            input.sha256 = ReconstructionCli.audioHash(input.path, NOFOLLOW_LINKS);
            input.evidence.put("bytes", Files.size(input.path));
        } catch (IOException e) {
            input.add(Status.error, "unreadable_file", "Could not hash the file: " + e.getMessage());
        }
    }

    private static void collect(Path root, Path folder, List<Input> files) throws IOException {
        try (var entries = Files.newDirectoryStream(folder)) {
            for (Path path : entries) {
                if (files.size() >= MAX_FILES) throw new IOException("Suno folder contains more than " + MAX_FILES + " entries");
                files.add(new Input(root, path));
            }
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
    }

    private static void readMetadata(List<Input> inputs) {
        for (var metadata : inputs) {
            if (!metadata.path.getFileName().toString().equalsIgnoreCase("stems.json") || metadata.sha256 == null) continue;
            metadata.role = "metadata";
            try {
                if (Files.size(metadata.path) > 1024 * 1024) throw new IOException("stems.json exceeds 1 MiB");
                var tree = ReconstructionArtifacts.readTree(metadata.path);
                if (tree == null || !tree.isJsonObject()) throw new IOException("Expected a manifest object");
                var json = tree.getAsJsonObject();
                JsonElement values = json.has("take1") ? json.get("take1") : json;
                if (!values.isJsonObject()) throw new IOException("Expected a stem mapping object");
                var declared = new TreeMap<String, String>();
                for (var entry : values.getAsJsonObject().entrySet()) {
                    String family = family(entry.getKey());
                    if (family == null || !entry.getValue().isJsonPrimitive()
                            || !entry.getValue().getAsJsonPrimitive().isString()) continue;
                    String reference = entry.getValue().getAsString();
                    declared.put(family, reference);
                    for (var stem : inputs) {
                        if (stem.sha256 == null || !extension(stem, ".wav")) continue;
                        if (bare(stem).equalsIgnoreCase(reference) || stem.file.equalsIgnoreCase(reference)
                                || stem.path.getFileName().toString().equalsIgnoreCase(reference)
                                || normalize(bare(stem)).equals(normalize(entry.getKey()))) {
                            stem.family = family;
                            stem.evidence.put("metadata", metadata.file);
                            stem.evidence.put("metadataReference", reference);
                        }
                    }
                }
                metadata.evidence.put("declaredStems", declared);
                metadata.add(Status.ok, "metadata_read", "Read the stem manifest");
            } catch (IOException | IllegalArgumentException e) {
                metadata.add(Status.warning, "invalid_metadata", "Could not read stems.json. Using file names.");
            }
        }
    }

    private static JsonObject readSeparation(Path source, List<Input> inputs) {
        var metadata = inputs.stream().filter(input -> input.file.equals(ExternalSeparators.PROVENANCE))
                .findFirst().orElseThrow();
        metadata.role = "metadata";
        try {
            var provenance = ExternalSeparators.readProvenance(source);
            ExternalSeparators.validateAudio(source, provenance);
            metadata.evidence.put("separation", provenance);
            metadata.add(Status.ok, "separation_read", "Read separation provenance");
            return provenance;
        } catch (IllegalArgumentException | IOException error) {
            metadata.add(Status.error, "invalid_separation", "Invalid separation output: " + error.getMessage());
            return null;
        }
    }

    private static void classifySeparated(List<Input> inputs, JsonObject provenance) {
        var families = new TreeMap<String, String>();
        for (var item : provenance.getAsJsonArray("stemOrder")) {
            String name = item.getAsString();
            String family = family(name);
            families.put("stems/" + name + ".wav", family == null ? "other" : family);
        }
        for (var input : inputs) {
            String family = families.get(input.file);
            if (family == null || !input.role.equals("stem")) continue;
            input.family = family;
            if (vocals(family)) input.evidence.put("tonalLeadCandidate", true);
        }
    }

    private static Input select(List<Input> inputs, String role, List<String> preferred) {
        boolean midi = role.equals("midi");
        var candidates = inputs.stream().filter(input -> input.sha256 != null && !input.file.contains("/"))
                .filter(input -> midi ? extension(input, ".mid") || extension(input, ".midi")
                        : extension(input, ".wav") && input.family == null && family(bare(input)) == null)
                .toList();
        for (String name : preferred) {
            var matches = candidates.stream().filter(input -> normalize(bare(input)).equals(normalize(name))).toList();
            if (matches.size() == 1) return matches.getFirst();
            if (matches.size() > 1) break;
        }
        if (candidates.size() == 1) return candidates.getFirst();
        for (var input : candidates)
            input.add(Status.warning, "ambiguous_" + role, "Multiple possible " + role + " files. Use a canonical file name.");
        return null;
    }

    private static void classify(List<Input> inputs, Input midi, Input mix) {
        for (var input : inputs) {
            if (input.sha256 == null || input.role.equals("metadata")) continue;
            if (input == mix) input.role = "mix";
            else if (input == midi) input.role = "midi";
            else if (extension(input, ".wav")) {
                if (input.family == null) input.family = family(bare(input));
                if (input.file.contains("/") || input.family != null) input.role = "stem";
                else if (mix != null) input.role = "stem";
            }
            if (input == mix || input == midi)
                input.evidence.put("detection", "Root file name and extension");
        }
        // Process the reference before accumulating stem audio.
        if (mix != null) {
            inputs.remove(mix);
            inputs.addFirst(mix);
        }
    }

    private static boolean extension(Input input, String suffix) {
        return input.file.toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private static String bare(Input input) {
        String name = input.path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String family(String label) {
        int tag = label.lastIndexOf('(');
        String name = normalize(tag >= 0 && label.endsWith(")") ? label.substring(tag + 1, label.length() - 1) : label);
        return switch (name) {
            case "bass", "bass guitar", "electric bass", "synth bass" -> "bass";
            case "drums", "drum", "drum kit" -> "drums";
            case "percussion", "perc" -> "percussion";
            case "keyboard", "keyboards", "keys", "piano", "electric piano", "organ" -> "keyboard";
            case "synth", "synths", "synthesizer", "synth lead", "synth pad" -> "synth";
            case "lead", "melody" -> "lead";
            case "guitar", "guitars", "electric guitar", "acoustic guitar" -> "guitar";
            case "strings", "string", "violin", "cello" -> "strings";
            case "brass", "trumpet", "trombone", "horn" -> "brass";
            case "woodwinds", "woodwind", "wind", "winds", "flute", "sax", "saxophone", "clarinet" -> "woodwinds";
            case "vocals", "vocal", "lead vocals", "lead vocal", "voice" -> "vocals";
            case "backing vocals", "backing vocal", "background vocals", "bgv" -> "backing_vocals";
            case "fx", "effects", "sound effects", "sfx" -> "fx";
            default -> null;
        };
    }

    private static void applyExclusions(List<Input> inputs, Set<String> exclusions, List<Issue> issues) {
        for (String file : new java.util.TreeSet<>(exclusions)) {
            var input = inputs.stream().filter(item -> item.file.equals(file)).findFirst().orElse(null);
            if (input == null || !input.role.equals("stem")) {
                issues.add(new Issue(Status.error, "invalid_exclusion", "Exclusion must name a detected stem: " + file));
                continue;
            }
            input.excluded = true;
            input.add(Status.ok, "excluded", "Excluded by override");
        }
    }

    private static void missingInputs(Input mix, Input midi, List<Issue> issues) {
        if (mix == null && midi == null)
            issues.add(new Issue(Status.error, "missing_mix_and_midi", "Neither the full mix nor the Suno MIDI was found"));
        else if (mix == null) issues.add(new Issue(Status.warning, "missing_mix", "Full mix is missing. Add it to run reconstruction."));
        else if (midi == null) issues.add(new Issue(Status.warning, "missing_midi", "Suno MIDI is missing. Add it to run reconstruction."));
    }

    private static MidiScore readMidi(Input midi) throws InterruptedIOException {
        if (midi == null) return null;
        try {
            validateMidiChunks(midi.path);
            javax.sound.midi.Sequence sequence;
            try (var input = new java.io.BufferedInputStream(Files.newInputStream(midi.path, NOFOLLOW_LINKS))) {
                sequence = javax.sound.midi.MidiSystem.getSequence(input);
            }
            MidiScore score = MidiScore.read(sequence, ": " + midi.file);
            if (score.notes().isEmpty()) throw new IOException("The MIDI contains no complete notes");
            if (score.parts().size() > MAX_MIDI_PARTS) throw new IOException("MIDI exceeds " + MAX_MIDI_PARTS + " parts");
            double lastNote = score.notes().stream().mapToLong(MidiScore.Note::endUs).max().orElse(0) / 1e6;
            midi.seconds = Math.max(lastNote, sequence.getMicrosecondLength() / 1e6);
            midi.evidence.put("durationSeconds", midi.seconds);
            midi.evidence.put("lastNoteSeconds", lastNote);
            midi.evidence.put("partCount", score.parts().size());
            for (var warning : score.warnings()) midi.add(Status.warning, "midi_note_warning", warning);
            return score;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | IllegalArgumentException | javax.sound.midi.InvalidMidiDataException e) {
            midi.add(Status.error, "invalid_midi", "Could not read MIDI: " + e.getMessage());
            return null;
        }
    }

    /** The MIDI provider may accept fewer tracks than the header declares. Validate physical track boundaries first. */
    private static void validateMidiChunks(Path file) throws IOException {
        try (var channel = FileChannel.open(file, NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > 16 * 1024 * 1024) throw new IOException("MIDI exceeds 16 MiB");
            var header = read(channel, 0, 14);
            if (header.getInt() != 0x4d546864) throw new IOException("Missing MIDI header");
            long position = 8 + Integer.toUnsignedLong(header.getInt());
            header.getShort();
            int tracks = Short.toUnsignedInt(header.getShort());
            if (position < 14 || tracks == 0 || tracks > 256) throw new IOException("Invalid MIDI header or track count");
            for (int i = 0; i < tracks; i++) {
                var chunk = read(channel, position, 8);
                if (chunk.getInt() != 0x4d54726b) throw new IOException("Missing MIDI track " + (i + 1));
                long length = Integer.toUnsignedLong(chunk.getInt());
                position += 8 + length;
                if (length < 4 || position > size) throw new IOException("Truncated MIDI track " + (i + 1));
                var end = read(channel, position - 3, 3);
                if (end.get() != (byte) 0xff || end.get() != 0x2f || end.get() != 0)
                    throw new IOException("MIDI track " + (i + 1) + " is missing its end marker");
            }
        }
    }

    private static ByteBuffer read(FileChannel channel, long offset, int size) throws IOException {
        var data = ByteBuffer.allocate(size);
        while (data.hasRemaining()) {
            if (channel.read(data, offset + data.position()) < 0) throw new IOException("Truncated MIDI data");
        }
        return data.flip();
    }

    private static float[] readAudio(Input input) throws InterruptedIOException {
        try {
            var signal = ReferenceAudio.importSignal(input.path, SunoImportAudio.RATE, SunoImportAudio.MAX_SECONDS);
            input.seconds = signal.seconds();
            input.rate = signal.sampleRate();
            input.channels = signal.channels();
            input.silent = signal.rms() < .001;
            input.evidence.put("durationSeconds", input.seconds);
            input.evidence.put("sampleRate", input.rate);
            input.evidence.put("channels", input.channels);
            input.evidence.put("rms", signal.rms());
            if (input.silent) input.add(Status.warning, "nearly_silent", "The audio is nearly silent");
            return signal.mono();
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String code = message.contains("mono or stereo") || message.contains("sample rate")
                    ? "unsupported_audio_format" : "invalid_wav";
            input.add(Status.error, code, "Could not read WAV: " + message);
            return null;
        }
    }

    private static void checkDurations(List<Input> inputs, Input mix, Input midi) {
        var stems = inputs.stream().filter(input -> input.role.equals("stem") && input.seconds > 0).toList();
        var durations = stems.stream().map(input -> input.seconds).sorted().toList();
        double reference = mix != null && mix.seconds > 0 ? mix.seconds
                : durations.isEmpty() ? 0 : durations.get(durations.size() / 2);
        // Identify a mix outlier when the stems agree on a different duration.
        if (mix != null && mix.seconds > 0 && durations.size() >= 2) {
            double median = durations.get(durations.size() / 2);
            boolean midiSupportsStemDuration = midi == null || midi.seconds <= 0
                    || Math.abs(midi.seconds - median) < Math.abs(midi.seconds - mix.seconds);
            if (durations.stream().filter(value -> !differentDuration(value, median)).count() > durations.size() / 2
                    && differentDuration(mix.seconds, median) && midiSupportsStemDuration) {
                durationError(mix, median, "stems");
                reference = median;
            }
        }
        if (reference <= 0) return;
        for (var stem : stems) {
            if (differentDuration(stem.seconds, reference)) durationError(stem, reference, "reference audio");
            if (mix != null && mix.rate != 0 && (stem.rate != mix.rate || stem.channels != mix.channels)) {
                stem.evidence.put("formatReconciliation", "Converted to a common 8000 Hz mono signal for analysis");
                stem.add(Status.ok, "audio_format_reconciled", "Sample rate or channel layout differs and can be reconciled");
            }
        }
        if (midi != null && midi.seconds > reference + Math.max(2, reference * .05))
            durationError(midi, reference, "reference audio");
        else if (midi != null && midi.seconds > 0 && midi.seconds < reference - Math.max(6, reference * .15))
            durationError(midi, reference, "reference audio");
    }

    private static boolean differentDuration(double seconds, double reference) {
        return Math.abs(seconds - reference) > Math.max(.5, reference * .03);
    }

    private static void durationError(Input input, double reference, String peer) {
        input.evidence.put("expectedDurationSeconds", reference);
        input.add(Status.error, "duration_mismatch", String.format(Locale.ROOT,
                "File is %.2f s, compared with %.2f s for the %s", input.seconds, reference, peer));
    }

    private static void checkAlignment(Alignment alignment, Input mix, List<Issue> issues) {
        if (alignment == null) return;
        if (Math.abs(alignment.offsetSeconds()) >= .025 && alignment.correlation() >= .35
                && alignment.correlation() - alignment.zeroLagCorrelation() >= .12) {
            mix.add(Status.error, "start_offset", String.format(Locale.ROOT,
                    "The summed stems start %+.3f s relative to the mix", alignment.offsetSeconds()));
        } else if (alignment.zeroLagCorrelation() < .35) {
            issues.add(new Issue(Status.warning, "alignment_unverified",
                    "The mix and summed stems correlate weakly. Their alignment could not be confirmed."));
        }
    }

    private static Part part(MidiScore.Part part) {
        String nameFamily = partFamily(part.name());
        return new Part(part.id(), part.name(), part.program(), part.drums(),
                part.drums() ? "drums" : nameFamily == null ? programFamily(part.program()) : nameFamily);
    }

    private static String partFamily(String name) {
        // Parentheses in General MIDI names are qualifiers (e.g. Electric Bass (finger)).
        int qualifier = name.indexOf('(');
        String base = normalize(qualifier < 0 ? name : name.substring(0, qualifier));
        if (base.matches("lead [1-8]") || base.equals("synth voice") || base.startsWith("pad ")) return "synth";
        if (base.startsWith("synth strings") || base.endsWith(" harp")) return "strings";
        if (base.endsWith(" guitar")) return "guitar";
        if (base.startsWith("electric bass") || base.startsWith("synth bass")) return "bass";
        if (base.startsWith("brass ")) return "brass";
        return family(base);
    }

    private static String programFamily(int program) {
        if (program < 24) return "keyboard";
        if (program < 32) return "guitar";
        if (program < 40) return "bass";
        if (program == 52 || program == 53) return "vocals";
        if (program < 56) return "strings";
        if (program < 64) return "brass";
        if (program < 80) return "woodwinds";
        if (program < 96) return "synth";
        if (program < 104 || program >= 120) return "fx";
        if (program >= 112) return "percussion";
        return "other";
    }

    private static List<Candidate> candidates(Input input, List<Part> parts, List<SunoImportAudio.Match> matches) {
        var scores = new ArrayList<Candidate>();
        for (var match : matches) {
            Part part = parts.get(match.part());
            String label = "none";
            if (part.drums && "drums".equals(input.family)) label = "drum_channel";
            else if (part.drums && "percussion".equals(input.family)) label = "percussion";
            else if (!part.drums && input.family != null) {
                if (input.family.equals(partFamily(part.name))) label = "name";
                else if (input.family.equals(programFamily(part.program))) label = "program";
            }
            scores.add(new Candidate(part.id, match.onsetCorrelation(), match.pitchCorrelation(), match.score(), label));
        }
        return List.copyOf(scores);
    }

    private record Choice(Input file, Candidate match, double weight, String reason) {
        PairEvidence evidence() {
            return new PairEvidence(file.file, match.part, weight, match.audioScore,
                    match.onsetCorrelation, match.pitchCorrelation, match.labelEvidence);
        }
    }

    private record PairEvidence(String file, int part, double score, double audioScore,
                                double onsetCorrelation, double pitchCorrelation, String labelEvidence) {}

    private record AssignmentEvidence(PairEvidence winner, PairEvidence runnerUp, PairEvidence alternativePart) {}

    private static Set<Integer> map(List<Input> inputs, List<Part> parts, Map<String, Integer> assignments,
                                    List<Issue> issues) {
        var used = new HashSet<Integer>();
        for (var assignment : new TreeMap<>(assignments).entrySet()) {
            String file = assignment.getKey();
            Integer part = assignment.getValue();
            var input = inputs.stream().filter(item -> item.file.equals(file)).findFirst().orElse(null);
            if (input == null || !input.role.equals("stem") || input.excluded || part == null
                    || parts.stream().noneMatch(item -> item.id == part)) {
                issues.add(new Issue(Status.error, "invalid_assignment", "Cannot assign " + file + " to MIDI part " + part));
            } else if (!used.add(part)) {
                issues.add(new Issue(Status.error, "duplicate_assignment", "Multiple overrides claim MIDI part " + part));
            } else {
                input.part = part;
                input.decidedBy = "override";
            }
        }
        var available = parts.stream().filter(part -> !used.contains(part.id))
                .sorted(Comparator.comparingInt(Part::id)).toList();
        var stems = inputs.stream().filter(file -> file.role.equals("stem") && !file.error() && !file.silent
                        && !file.excluded && file.part == null && !defaultUnmapped(file.family))
                .sorted(Comparator.comparing(file -> file.file)).toList();
        var columns = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < available.size(); i++) columns.put(available.get(i).id, i);
        var choices = new Choice[stems.size()][available.size()];
        var weights = new double[stems.size()][available.size()];
        for (int row = 0; row < stems.size(); row++) {
            var file = stems.get(row);
            double bestMelodicAudio = file.candidates.stream().filter(candidate -> !parts.get(candidate.part).drums)
                    .mapToDouble(Candidate::audioScore).max().orElse(0);
            for (var candidate : file.candidates) {
                Integer column = columns.get(candidate.part);
                if (column == null) continue;
                var choice = choice(file, candidate, parts.get(candidate.part), bestMelodicAudio);
                if (choice == null) continue;
                choices[row][column] = choice;
                weights[row][column] = choice.weight;
            }
        }
        int[] assignment = MaximumWeightAssignment.match(weights);
        var winners = new LinkedHashMap<Integer, Choice>();
        for (int row = 0; row < assignment.length; row++) {
            int column = assignment[row];
            if (column < 0) continue;
            var winner = choices[row][column];
            var file = winner.file;
            file.part = winner.match.part;
            file.decidedBy = winner.reason;
            if (!file.decidedBy.equals("audio") && !file.decidedBy.equals("drum_channel") && winner.match.audioScore < .08)
                file.add(Status.warning, "weak_audio_match", "The label matches, with weak audio support");
            used.add(file.part);
            winners.put(file.part, winner);

            Choice runnerUp = null;
            for (var stemChoices : choices) {
                var other = stemChoices[column];
                if (other != winner) runnerUp = stronger(runnerUp, other);
            }
            Choice alternative = null;
            for (var other : choices[row]) {
                if (other != winner) alternative = stronger(alternative, other);
            }
            file.evidence.put("assignment", new AssignmentEvidence(winner.evidence(),
                    runnerUp == null ? null : runnerUp.evidence(), alternative == null ? null : alternative.evidence()));
        }
        for (var file : stems) warnAboutSharedPart(file, parts, winners);
        return used;
    }

    private static Choice choice(Input file, Candidate candidate, Part part, double bestMelodicAudio) {
        boolean drumStem = "drums".equals(file.family);
        if (drumStem != part.drums && (drumStem || !"percussion".equals(file.family))) return null;
        double audio = candidate.audioScore;
        // Dedicated drum stems take precedence over percussion stems on the MIDI drum channel.
        if (drumStem) return new Choice(file, candidate, 2.1 + audio, "drum_channel");
        double bonus = switch (candidate.labelEvidence) {
            case "name" -> NAME_BONUS;
            case "program", "percussion" -> FAMILY_BONUS;
            default -> 0;
        };
        if (bonus > 0 && (part.drums || bestMelodicAudio < .2 || audio >= bestMelodicAudio - .15))
            return new Choice(file, candidate, Math.max(.01, audio + bonus), candidate.labelEvidence);
        if (audio >= .14 && (part.drums || candidate.pitchCorrelation >= .12))
            return new Choice(file, candidate, audio, "audio");
        return null;
    }

    private static Choice stronger(Choice best, Choice candidate) {
        return candidate != null && (best == null || candidate.weight > best.weight) ? candidate : best;
    }

    private static void warnAboutSharedPart(Input file, List<Part> parts, Map<Integer, Choice> winners) {
        if ("drums".equals(file.family)) return;
        var melodic = file.candidates.stream().filter(candidate -> !parts.get(candidate.part).drums)
                .sorted(Comparator.comparingDouble(Candidate::audioScore).reversed().thenComparingInt(Candidate::part))
                .toList();
        if (melodic.isEmpty()) return;
        var best = melodic.getFirst();
        var winner = winners.get(best.part);
        if (winner == null || winner.file == file || best.audioScore < SHARED_PART_AUDIO
                || winner.match.audioScore < SHARED_PART_AUDIO || best.pitchCorrelation < .12
                || winner.match.pitchCorrelation < .12
                || melodic.size() > 1 && best.audioScore - melodic.get(1).audioScore < SHARED_PART_MARGIN) return;
        file.evidence.put("sharedPart", best.part);
        file.add(Status.warning, "also_correlates_with_part", String.format(Locale.ROOT,
                "This stem also correlates with part %d (audio %.3f). %s is the reference for that part. "
                        + "To use this stem instead, re-check with --assign %s",
                best.part, best.audioScore, winner.file.file, shellQuote(file.file + "=" + best.part)));
    }

    private static boolean vocals(String family) {
        return "vocals".equals(family) || "backing_vocals".equals(family);
    }

    private static boolean defaultUnmapped(String family) {
        return vocals(family) || "fx".equals(family);
    }

    private static boolean mixPresent(List<Input> inputs) {
        return inputs.stream().anyMatch(input -> input.role.equals("mix") && input.seconds > 0);
    }

    private static void finish(Input input, boolean audioOnly) {
        if (input.excluded || input.sha256 == null || input.role.equals("metadata")) return;
        if (input.role.equals("stem")) {
            if (input.evidence.get("audioOnlyRole") instanceof String role)
                input.add(Status.ok, "audio_only_stem", "Audio-only role: " + role);
            else if (Boolean.TRUE.equals(input.evidence.get("tonalLeadCandidate")))
                input.add(Status.ok, "tonal_lead_candidate", "Vocals found. Tonal lead candidate.");
            else if (vocals(input.family))
                input.add(Status.warning, "vocals_present", input.part == null
                        ? "Vocals found. Left unmapped by default." : "Vocals found. Mapped by override.");
            else if ("fx".equals(input.family))
                input.add(Status.warning, "fx_present", input.part == null
                        ? "Effects found. Left unmapped by default." : "Effects found. Mapped by override.");
            if (input.part != null) {
                input.add(Status.ok, "mapped", "Mapped to MIDI part " + input.part + " by " + switch (input.decidedBy) {
                    case "audio" -> "onset and pitch-energy correlation";
                    case "name" -> "part name";
                    case "program" -> "MIDI program";
                    case "drum_channel" -> "MIDI drum channel";
                    case "percussion" -> "percussion label and MIDI drum channel";
                    default -> "manual override";
                });
            } else if (!audioOnly && !defaultUnmapped(input.family) && !input.error() && !input.silent) {
                if (!input.evidence.containsKey("sharedPart"))
                    input.add(Status.warning, "stem_without_part", "No matching or available MIDI part");
            }
        }
        if (input.issues.isEmpty())
            input.add(Status.ok, input.role.equals("other") ? "ignored_file" : "validated",
                    input.role.equals("other") ? "Ignored an unrelated file" : "File validated");
    }

    private record Lead(int part, String reason, double priority) {}

    private static Lead lead(List<Part> parts, MidiScore score, Set<Integer> mapped) {
        Lead best = null;
        for (var part : parts) {
            if (part.drums || defaultUnmapped(part.family) || List.of("bass", "percussion").contains(part.family)) continue;
            String name = normalize(part.name);
            boolean named = List.of(name.split(" ")).stream().anyMatch(word -> word.equals("lead") || word.equals("melody"));
            boolean gmLead = part.program >= 80 && part.program <= 87;
            var notes = score.notes().stream().filter(note -> note.partId() == part.id).toList();
            if (notes.isEmpty()) continue;
            long end = 0;
            int overlaps = 0;
            double pitch = 0;
            for (var note : notes) {
                if (note.startUs() + 25_000 < end) overlaps++;
                end = Math.max(end, note.endUs());
                pitch += note.pitch();
            }
            double mono = 1 - overlaps / (double) notes.size();
            double meanPitch = pitch / notes.size();
            boolean melodic = mono >= .7 && meanPitch >= 59 && notes.size() >= 4;
            double priority = (named ? 10 : gmLead ? 5 : melodic ? 2 : 0) + mono + meanPitch / 128
                    + (mapped.contains(part.id) ? .15 : 0);
            if (best == null || priority > best.priority) best = new Lead(part.id, named ? "part name"
                    : gmLead ? "General MIDI lead program" : melodic ? "mostly monophonic high-register notes"
                    : "available melodic part, low confidence", priority);
        }
        return best;
    }

    private static RunRequest audioRequest(Path work, Path catalogue, Input mix, List<Input> files,
                                           List<Issue> issues, String transcriber, Map<String, String> overrides,
                                           Map<String, String> transcriberFor, String drumDetector, String device,
                                           Path configFile, boolean separatedAudio) throws IOException {
        var stems = new TreeMap<String, String>();
        var roles = new TreeMap<String, String>();
        var seen = new HashSet<String>();
        var activeRoles = new java.util.TreeSet<String>();
        var transcribersByRole = new TreeMap<String, String>();
        transcriberFor.forEach((role, name) -> ExternalTranscribers.putRoleOverride(transcribersByRole, role + "=" + name));
        for (var file : files) {
            if (!file.role.equals("stem") || file.excluded || file.error()) continue;
            String role = overrides.get(file.file);
            if (role != null) {
                seen.add(file.file);
                role = ReconstructionAudioOnly.label(role);
            } else {
                if ("fx".equals(file.family) || "percussion".equals(file.family)) {
                    file.add(Status.ok, "ignored_non_tonal", "Use --stem-role to include this stem");
                    continue;
                }
                role = "keyboard".equals(file.family) && normalize(bare(file)).equals("piano")
                        ? "piano" : ReconstructionAudioOnly.defaultRole(file.family == null ? "other" : file.family);
            }
            String base = file.family == null ? "other" : file.family;
            String label = base;
            for (int i = 2; stems.containsKey(label); i++) label = base + "-" + i;
            stems.put(label, file.path.toString());
            if (!role.equals(ReconstructionAudioOnly.defaultRole(label))) roles.put(label, role);
            file.evidence.put("audioOnlyRole", role);
            // Metadata reports whole-file RMS. The run keeps stems with an audible local window.
            if (!file.silent || mix != null && !ReconstructionAudioOnly.silent(file.path, Math.min(mix.seconds, file.seconds)))
                activeRoles.add(ReconstructionAudioOnly.transcriberRole(label, role));
        }
        for (String key : new java.util.TreeSet<>(overrides.keySet())) {
            if (!seen.contains(key)) issues.add(new Issue(Status.error, "unknown_stem_role",
                    "Role override must name an included stem: " + key));
        }
        if (stems.isEmpty()) issues.add(new Issue(Status.error, "missing_stems", "No labelled stems found"));
        if (mix == null || stems.isEmpty()) return null;
        var config = ExternalTranscribers.config(configFile, true);
        if (config.tonal().isEmpty() && separatedAudio && transcriber == null && transcribersByRole.isEmpty()) {
            issues.add(new Issue(Status.warning, AUDIO_ONLY, "no MIDI: needs audio-only transcription. Configure a transcriber to run."));
            return null;
        }
        var fallback = config.fallback(transcriber, true);
        var selected = new TreeMap<String, String>();
        if (transcribersByRole.containsKey("drums") && drumDetector != null)
            throw new IllegalArgumentException("Choose either --transcriber-for drums=NAME or --drum-detector");
        String drum = config.drumName(transcribersByRole.getOrDefault("drums", drumDetector));
        AudioDrumDetector.select(config, drum, device == null ? "auto" : device);
        for (String role : activeRoles) {
            if (!role.equals("drums")) selected.put(role, config.forRole(role, fallback, transcribersByRole, transcriber != null));
        }
        if (activeRoles.contains("drums")) selected.put("drums", drum);
        issues.add(new Issue(Status.ok, "audio_only_transcription", "Audio-only transcribers by role " + selected));
        var options = new LinkedHashMap<String, Object>();
        if (!roles.isEmpty()) options.put("stemRoles", roles);
        if (fallback != null) options.put("transcriber", fallback);
        if (!transcribersByRole.isEmpty()) options.put("transcriberFor", transcribersByRole);
        if (drumDetector != null) options.put("drumDetector", drumDetector);
        if (device != null) options.put("transcriberDevice", device);
        if (configFile != null) options.put("transcriberConfig", configFile.toString());
        return new RunRequest(work.resolve("reconstruction").toString(), new RunInputs(null, mix.path.toString(),
                catalogue == null ? null : catalogue.toString(), stems, true), options);
    }

    private static RunRequest runRequest(Path work, Path catalogue, Input mix, Input midi, List<Input> files,
                                         Lead lead, Status status, String transcriber) {
        if (mix == null || midi == null || status == Status.error) return null;
        var stems = new TreeMap<Integer, String>();
        for (var file : files) if (file.part != null) stems.put(file.part, file.path.toString());
        var inputs = new RunInputs(midi.path.toString(), mix.path.toString(),
                catalogue == null ? null : catalogue.toString(), java.util.Collections.unmodifiableSortedMap(stems));
        var options = new LinkedHashMap<String, Object>();
        if (lead != null && lead.priority >= 2) options.put("leadPart", lead.part);
        if (transcriber != null) {
            var unmapped = new TreeMap<String, String>();
            for (var file : files) {
                if (ReconstructionStemParts.tonalFamily(file.family)
                        && file.part == null && file.issues.stream().anyMatch(issue -> issue.code.equals("stem_without_part")
                                || issue.code.equals("also_correlates_with_part")))
                    unmapped.put(file.path.toString(), file.family);
            }
            if (!unmapped.isEmpty()) {
                options.put("transcriber", transcriber);
                options.put("transcribeUnmapped", unmapped);
            }
        }
        return new RunRequest(work.resolve("reconstruction").toString(), inputs, options);
    }

    private static List<String> runArguments(RunRequest request) {
        var inputs = request.inputs;
        var args = new ArrayList<>(List.of("reconstruct", "run", Boolean.TRUE.equals(inputs.audioOnly) ? "--audio-only" : inputs.midi, inputs.wav,
                inputs.catalogue == null ? CATALOGUE : inputs.catalogue, request.workDir));
        inputs.stems.forEach((part, file) -> {
            args.add("--stem");
            args.add(part + "=" + file);
        });
        if (Boolean.TRUE.equals(inputs.audioOnly)) {
            for (String[] option : List.of(new String[]{"transcriber", "--transcriber"},
                    new String[]{"transcriberConfig", "--transcriber-config"},
                    new String[]{"transcriberDevice", "--transcriber-device"},
                    new String[]{"drumDetector", "--drum-detector"})) {
                if (!request.options.containsKey(option[0])) continue;
                args.add(option[1]);
                args.add(request.options.get(option[0]).toString());
            }
            if (request.options.get("transcriberFor") instanceof Map<?, ?> roles) roles.forEach((role, name) -> {
                args.add("--transcriber-for");
                args.add(role + "=" + name);
            });
            if (request.options.get("stemRoles") instanceof Map<?, ?> roles) roles.forEach((label, role) -> {
                args.add("--stem-role");
                args.add(label + "=" + role);
            });
        }
        if (request.options.containsKey("leadPart")) {
            args.add("--lead-part");
            args.add(request.options.get("leadPart").toString());
        }
        if (request.options.get("transcribeUnmapped") instanceof Map<?, ?> unmapped) {
            args.add("--transcriber");
            args.add(request.options.get("transcriber").toString());
            unmapped.forEach((path, role) -> {
                args.add("--transcribe-unmapped");
                args.add(role + "=" + path);
            });
        }
        return List.copyOf(args);
    }

    private static String shellQuote(String arg) {
        if (arg.matches("[a-zA-Z0-9_./=-]+")) return arg;
        if (arg.chars().anyMatch(Character::isISOControl)) {
            var quoted = new StringBuilder("$'");
            for (char c : arg.toCharArray()) {
                if (Character.isISOControl(c)) quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                else {
                    if (c == '\\' || c == '\'') quoted.append('\\');
                    quoted.append(c);
                }
            }
            return quoted.append('\'').toString();
        }
        return "'" + arg.replace("'", "'\"'\"'") + "'";
    }

    private static String printable(String value) {
        return value.replaceAll("\\p{Cntrl}", "?");
    }
}
