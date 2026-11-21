package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Revision-aware journal operations for interactive reconstruction review. */
public final class ReconstructionReview {
    private static final List<String> OUTPUTS = List.of("score.json", "structure.json", "roles.json",
            "allocation.json", "voicing.json", ReconstructionEdits.RESULTS,
            ReconstructionAllocation.Affinity.FILE, ReconstructionClipLinks.LAYOUT, ReconstructionManifest.FILE,
            "arrangement.smps.json", "arrangement.smps.zip", "render.wav", ReconstructionCli.STEADY_REFERENCE,
            ReconstructionEdits.FILE, "report.json");

    public record Revision(String edits, String sections) {
        public JsonArray entries() {
            return edits == null ? new JsonArray() : ReconstructionEdits.batch(JsonParser.parseString(edits),
                    ReconstructionEdits.FILE);
        }
    }

    public record EditResult(String status, String reason, List<String> noteIds) {}

    public static final class Changed extends IOException {
        public Changed() { super("The reconstruction changed on disk. Reload before applying your edits."); }
    }

    @FunctionalInterface
    interface Check { void run() throws IOException; }

    private ReconstructionReview() {}

    public static Revision revision(Path work) throws IOException {
        return new Revision(contents(work.resolve(ReconstructionEdits.FILE)),
                contents(work.resolve(ReconstructionSections.FILE)));
    }

    private static String contents(Path file) throws IOException {
        try {
            return Files.readString(file);
        } catch (java.nio.file.NoSuchFileException absent) {
            return null;
        }
    }

    public static java.util.Map<String, List<EditResult>> outcomes(Path work) throws IOException {
        var results = new java.util.LinkedHashMap<String, List<EditResult>>();
        for (var outcome : ReconstructionEdits.Journal.read(work).all())
            results.computeIfAbsent(outcome.id(), id -> new ArrayList<>())
                    .add(new EditResult(outcome.status().name(), outcome.detail(), outcome.noteIds()));
        results.replaceAll((id, values) -> List.copyOf(values));
        return java.util.Map.copyOf(results);
    }

    public static String validationIssue(JsonElement entry) {
        try {
            parse(entry);
            return null;
        } catch (IllegalArgumentException malformed) {
            return malformed.getMessage();
        }
    }

    private static ReconstructionEdits.Intent parse(JsonElement entry) {
        if (!entry.isJsonObject()) throw new IllegalArgumentException("Expected an intent object");
        return ReconstructionEdits.parse(entry.getAsJsonObject(), ReconstructionEdits.FILE);
    }

    private static ReconstructionEdits parse(JsonArray entries) {
        var ids = new HashSet<String>();
        var intents = new ArrayList<ReconstructionEdits.Intent>();
        for (var entry : entries) {
            var intent = parse(entry);
            if (!ids.add(intent.id())) throw new IllegalArgumentException("Duplicate edit ID " + intent.id());
            intents.add(intent);
        }
        return new ReconstructionEdits(intents);
    }

    public static String firstStage(Path work, JsonArray before, JsonArray after) throws IOException {
        return stage(work, changes(before, after), parse(after));
    }

    private static List<ReconstructionEdits.Intent> changes(JsonArray before, JsonArray after) {
        var changed = new ArrayList<ReconstructionEdits.Intent>();
        for (var pair : List.of(List.of(before, after), List.of(after, before))) {
            for (var entry : pair.getFirst()) {
                if (!pair.getLast().contains(entry) && validationIssue(entry) == null) changed.add(parse(entry));
            }
        }
        return changed;
    }

    private static String stage(Path work, List<ReconstructionEdits.Intent> changed, ReconstructionEdits edits)
            throws IOException {
        var prior = ReconstructionEdits.Journal.read(work);
        if (!Objects.equals(prior.scoreRecipe(), edits.scoreRecipe())) return "transcribe";
        return changed.stream().map(ReconstructionEdits.Intent::firstStage).min(Comparator.naturalOrder())
                .orElse(ReconstructionStages.Stage.ROLES).name().toLowerCase(Locale.ROOT);
    }

    public record SectionUpdate(ReconstructionSections.Result result, Revision revision) {}

    public static SectionUpdate section(Path work, Revision expected, String action, JsonArray entries)
            throws IOException {
        var revision = new Revision[1];
        var result = ReconstructionSections.run(work, action, entries, () -> requireRevision(work, expected),
                () -> revision[0] = new Revision(expected.edits(), contents(work.resolve(ReconstructionSections.FILE))));
        return new SectionUpdate(result, revision[0]);
    }

    private static void requireRevision(Path work, Revision expected) throws IOException {
        if (!expected.equals(revision(work))) throw new Changed();
    }

    /**
     * Rebuild and compare a private copy. Publish only after the pipeline and comparison finish,
     * and only if the source artifacts and both journals still match the starting revision.
     */
    public static JsonObject apply(Path work, Revision expected, JsonArray entries, ReconstructionStages.Progress progress)
            throws IOException {
        requireRevision(work, expected);
        var updated = parse(entries);
        var changed = changes(expected.entries(), entries);
        Path scratch = Files.createTempDirectory("reconstruction-review-");
        try {
            var before = Files.createDirectory(scratch.resolve("before"));
            var after = Files.createDirectory(scratch.resolve("after"));
            var inputs = List.of(ReconstructionSections.FILE, "roleplan.json");
            var paths = new ArrayList<>(OUTPUTS);
            paths.addAll(inputs);
            for (String file : paths) {
                var source = work.resolve(file);
                if (Files.isRegularFile(source, NOFOLLOW_LINKS)) {
                    Files.copy(source, before.resolve(file));
                    Files.copy(before.resolve(file), after.resolve(file));
                }
                checkCancelled();
            }
            requireRevision(before, expected);
            String from = stage(before, changed, updated);
            updated.write(after);
            var args = new ArrayList<>(List.of("--from", from, after.toString(), "--keep-voicing"));
            var roles = ReconstructionTrace.optional(before, "roles.json", ReconstructionRoles.Result.class);
            if (roles != null) {
                args.add("--harmony-max-hz");
                args.add(Double.toString(roles.harmonyMaxHz()));
            }
            ReconstructionStages.runForReview("run", args.toArray(String[]::new), (completed, total, message) -> {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                progress.report(completed, total + 1, message);
            });
            checkCancelled();
            int total = ReconstructionStages.Stage.values().length
                    - ReconstructionStages.Stage.parse(from).ordinal() + 1;
            progress.report(total - 1, total, "Comparing trace, compiled timeline and PCM…");
            var diff = compare(before, after, changed);
            diff.addProperty("from", from);
            var generated = new ArrayList<>(OUTPUTS);
            // Older work directories may acquire a manifest and input snapshots on their first resume.
            var snapshots = after.resolve("snapshots");
            if (Files.isDirectory(snapshots)) try (var files = Files.list(snapshots)) {
                for (Path file : files.toList()) {
                    var relative = after.relativize(file).toString();
                    if (Files.exists(work.resolve(relative))) {
                        if (!same(work.resolve(relative), file)) throw new Changed();
                    } else {
                        paths.add(relative);
                        generated.addFirst(relative);
                    }
                }
            }
            publish(work, before, after, expected, paths, generated, progress, total);
            return diff;
        } finally {
            removeTree(scratch);
        }
    }

    private static JsonObject compare(Path before, Path after, List<ReconstructionEdits.Intent> changed) throws IOException {
        var oldRows = ReconstructionTrace.read(before);
        var newRows = ReconstructionTrace.read(after);
        var oldJournal = ReconstructionEdits.Journal.read(before);
        var newJournal = ReconstructionEdits.Journal.read(after);
        var time = ReconstructionTimeMap.of(ReconstructionStages.readScore(after.resolve("score.json")));
        ReconstructionEdits.Span span = null;
        for (var edit : changed) {
            var journal = newJournal.all().stream().anyMatch(outcome -> outcome.id().equals(edit.id()))
                    ? newJournal : oldJournal;
            var range = ReconstructionEditCommand.span(edit, journal, oldRows, newRows, time);
            if (range != null) span = range.union(span);
        }
        var diff = ReconstructionTrace.diff(before, after, span == null ? null : span.start() + "," + span.end());
        var changes = diff.getAsJsonArray("changes");
        diff.addProperty("changedNotes", changes.size());
        diff.addProperty("outsideSpanChanges", changes.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(row -> row.has("outsideSpan") && row.get("outsideSpan").getAsBoolean()).count());
        if (span != null) {
            diff.addProperty("sourceStart", span.start());
            diff.addProperty("sourceEnd", span.end());
        }
        return diff;
    }

    private static void publish(Path work, Path before, Path after, Revision expected,
                                List<String> paths, List<String> generated,
                                ReconstructionStages.Progress progress, int total) throws IOException {
        // Prepare same-filesystem replacements, including when Studio's scratch is on another volume.
        Path stage = Files.createTempDirectory(work, ".review-publish-");
        boolean recovered = true;
        try {
            var replacements = new ArrayList<String>();
            var backups = Files.createDirectory(stage.resolve("previous"));
            for (String file : generated) {
                var output = after.resolve(file);
                if (same(before.resolve(file), output)) continue;
                replacements.add(file);
                if (Files.exists(output)) copy(output, stage.resolve(file));
                if (Files.exists(before.resolve(file))) copy(before.resolve(file), backups.resolve(file));
                checkCancelled();
            }

            try (var edits = FileChannel.open(work.resolve(ReconstructionEditCommand.LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
                 var editLock = edits.lock();
                 var sections = FileChannel.open(work.resolve(ReconstructionSections.LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
                 var sectionLock = sections.lock()) {
                requireRevision(work, expected);
                for (String file : paths) if (!same(work.resolve(file), before.resolve(file))) throw new Changed();
                checkCancelled();
                progress.report(total, total, "Publishing reconstruction…");
                var published = new ArrayList<String>();
                try {
                    for (String file : replacements) {
                        replace(work.resolve(file), stage.resolve(file));
                        published.add(file);
                    }
                } catch (IOException failure) {
                    for (String file : published.reversed()) {
                        try {
                            replace(work.resolve(file), backups.resolve(file));
                        } catch (IOException rollback) {
                            recovered = false;
                            failure.addSuppressed(rollback);
                        }
                    }
                    if (!recovered) throw new IOException("Reconstruction could not be restored. Backups: " + backups, failure);
                    throw failure;
                }
            }
        } finally {
            if (recovered) removeTree(stage);
        }
    }

    private static boolean same(Path a, Path b) throws IOException {
        boolean first = Files.exists(a, NOFOLLOW_LINKS), second = Files.exists(b, NOFOLLOW_LINKS);
        return first == second && (!first || Files.mismatch(a, b) == -1);
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to);
    }

    private static void replace(Path destination, Path source) throws IOException {
        if (Files.exists(source)) {
            Files.createDirectories(destination.getParent());
            Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(destination);
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Reconstruction cancelled");
    }

    private static void removeTree(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) {
            System.getLogger(ReconstructionReview.class.getName())
                    .log(System.Logger.Level.WARNING, "Could not remove reconstruction staging files", failure);
        }
    }
}
