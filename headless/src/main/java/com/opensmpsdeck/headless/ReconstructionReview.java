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
            "allocation.json", "voicing.json", ReconstructionEdits.RESULTS, ReconstructionProposals.RESULTS,
            ReconstructionAllocation.Affinity.FILE, ReconstructionClipLinks.LAYOUT, ReconstructionManifest.FILE,
            "arrangement.smps.json", "arrangement.smps.zip", "render.wav", ReconstructionCli.STEADY_REFERENCE,
            ReconstructionEdits.FILE, ReconstructionVoicePlan.FILE, "report.json");

    public record Revision(String edits, String sections, String voices) {
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
                contents(work.resolve(ReconstructionSections.FILE)), contents(work.resolve(ReconstructionVoicePlan.JOURNAL)));
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

    public static ReconstructionVoicePlan.Result voices(Path work, JsonArray entries) throws IOException {
        if (!Files.isRegularFile(work.resolve("roles.json")))
            return new ReconstructionVoicePlan.Result(1, List.of(), List.of());
        return ReconstructionVoicePlan.review(work, parse(entries).accepted());
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
        if (!Objects.equals(prior.scoreRecipe(), edits.accepted().scoreRecipe())) return "transcribe";
        return changed.stream().map(ReconstructionEdits.Intent::firstStage).min(Comparator.naturalOrder())
                .orElse(ReconstructionStages.Stage.ROLES).name().toLowerCase(Locale.ROOT);
    }

    public record SectionUpdate(ReconstructionSections.Result result, Revision revision) {}

    public static SectionUpdate section(Path work, Revision expected, String action, JsonArray entries)
            throws IOException {
        var revision = new Revision[1];
        var result = ReconstructionSections.run(work, action, entries, () -> requireRevision(work, expected),
                () -> revision[0] = new Revision(expected.edits(), contents(work.resolve(ReconstructionSections.FILE)), expected.voices()));
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
            var inputs = List.of(ReconstructionSections.FILE, "roleplan.json", ReconstructionVoicePlan.JOURNAL);
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
            if (expected.voices() != null && ReconstructionStages.Stage.parse(from).ordinal()
                    > ReconstructionStages.Stage.VOICES.ordinal()) {
                var voices = ReconstructionVoicePlan.saved(before);
                if (voices == null || !voices.equals(ReconstructionVoicePlan.load(before))) from = "voices";
            }
            updated.write(after);
            var args = new ArrayList<>(List.of("--from", from, after.toString()));
            var fromStage = ReconstructionStages.Stage.parse(from);
            if (fromStage.ordinal() <= ReconstructionStages.Stage.VOICE.ordinal()) args.add("--keep-voicing");
            var roles = ReconstructionTrace.optional(before, "roles.json", ReconstructionRoles.Result.class);
            if (roles != null && fromStage.ordinal() <= ReconstructionStages.Stage.ROLES.ordinal()) {
                args.add("--harmony-max-hz");
                args.add(Double.toString(roles.harmonyMaxHz()));
            }
            ReconstructionStages.runForReview("run", args.toArray(String[]::new), (completed, total, message) -> {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                progress.report(completed, total + 1, message);
            });
            checkCancelled();
            if (updated.intents().stream().noneMatch(ReconstructionEdits.Intent::proposed))
                Files.deleteIfExists(after.resolve(ReconstructionProposals.RESULTS));
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

    public record ProposalPreview(Path workDir, List<String> ids, List<RenderComparison.Result> comparisons) {}

    public static ProposalPreview preview(Path work, String id, String group, ReconstructionStages.Progress progress)
            throws IOException {
        return preview(work, id, group, null, null, progress);
    }

    public static ProposalPreview preview(Path work, String id, Revision expected, ReconstructionStages.Progress progress)
            throws IOException {
        return preview(work, id, null, null, expected, progress);
    }

    private static ProposalPreview preview(Path work, String id, String group, ReconstructionStages.Stage start,
                                           Revision expected, ReconstructionStages.Progress progress) throws IOException {
        work = work.toRealPath();
        if (expected == null) expected = revision(work);
        requireRevision(work, expected);
        var journal = parse(expected.entries());
        var selected = ReconstructionProposals.select(journal, id, group);
        ReconstructionArtifacts.require(!selected.isEmpty(), "proposals", "no matching proposals");
        var shadow = ReconstructionProposals.shadow(journal, selected);
        var from = ReconstructionProposals.from(shadow, selected, ReconstructionEdits.Journal.read(work));
        if (start != null && start.ordinal() < from.ordinal()) from = start;
        Path before = work.resolve("render.wav");
        ReconstructionArtifacts.require(Files.isRegularFile(before, NOFOLLOW_LINKS), "proposals",
                "build the accepted arrangement before previewing");
        var oldRows = ReconstructionTrace.read(work);
        Path target = Files.createTempDirectory(work.getParent(), work.getFileName() + "-preview-");
        boolean ready = false;
        try {
            var paths = new ArrayList<>(OUTPUTS);
            paths.removeAll(List.of(ReconstructionEdits.FILE, ReconstructionProposals.RESULTS,
                    "arrangement.smps.zip", ReconstructionCli.STEADY_REFERENCE));
            paths.addAll(List.of(ReconstructionSections.FILE, "roleplan.json", ReconstructionVoicePlan.JOURNAL));
            for (var file : paths) {
                var source = work.resolve(file);
                if (Files.isRegularFile(source, NOFOLLOW_LINKS))
                    Files.copy(source, target.resolve(file.equals("render.wav") ? "built.wav" : file));
                checkCancelled();
            }
            var snapshots = work.resolve("snapshots");
            if (Files.isDirectory(snapshots, NOFOLLOW_LINKS)) {
                try (var files = Files.list(snapshots)) {
                    for (var file : files.toList()) {
                        if (Files.isRegularFile(file, NOFOLLOW_LINKS)) copy(file, target.resolve(work.relativize(file)));
                    }
                }
            }
            requireRevision(work, expected);
            shadow.write(target);
            var args = new ArrayList<>(List.of("--from", from.name().toLowerCase(Locale.ROOT), target.toString()));
            if (from.ordinal() <= ReconstructionStages.Stage.VOICE.ordinal()) args.add("--keep-voicing");
            var roles = ReconstructionTrace.optional(target, "roles.json", ReconstructionRoles.Result.class);
            if (roles != null && from.ordinal() <= ReconstructionStages.Stage.ROLES.ordinal()) {
                args.add("--harmony-max-hz");
                args.add(Double.toString(roles.harmonyMaxHz()));
            }
            ReconstructionStages.runForReview("run", args.toArray(String[]::new), (done, total, message) -> {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                progress.report(done, total + 1, message);
            });
            checkCancelled();
            var after = ReconstructionTrace.read(target);
            var outcomes = ReconstructionEdits.Journal.read(target);
            var clock = ReconstructionTimeMap.of(ReconstructionStages.readScore(target.resolve("score.json")));
            ReconstructionEdits.Span span = null;
            for (var edit : selected) {
                var range = ReconstructionEditCommand.span(edit, outcomes, oldRows, after, clock);
                if (range != null) span = range.union(span);
            }
            var comparisons = RenderComparison.proposals(target, clock, span);
            if (!same(before, target.resolve("built.wav"))) throw new Changed();
            Files.delete(target.resolve("built.wav"));
            requireRevision(work, expected);
            checkCancelled();
            ready = true;
            return new ProposalPreview(target, selected.stream().map(ReconstructionEdits.Intent::id).toList(), comparisons);
        } finally {
            if (!ready) removeTree(target);
        }
    }

    static void previewCli(String[] args, java.io.PrintStream out) throws IOException {
        var values = new ArrayList<>(List.of(args));
        values.remove("--with-proposals");
        ReconstructionStages.Stage from = null;
        int fromAt = values.indexOf("--from");
        if (fromAt >= 0 && fromAt + 1 < values.size()) {
            values.remove(fromAt);
            from = ReconstructionStages.Stage.parse(values.remove(fromAt));
        }
        ReconstructionArtifacts.require(!values.isEmpty() && !values.getFirst().startsWith("--"),
                "proposals", "expected reconstruct run WORK_DIR --with-proposals [--id ID | --group GROUP]");
        var work = Path.of(values.removeFirst());
        var selection = ReconstructionProposals.selection(values);
        var result = preview(work, selection.id(), selection.group(), from, null, (a, b, c) -> {});
        out.println(new com.google.gson.Gson().toJson(java.util.Map.of("workDir", result.workDir().toString(),
                "ids", result.ids(), "comparisons", result.comparisons())));
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
                 var sectionLock = sections.lock();
                 var voices = FileChannel.open(work.resolve(ReconstructionVoicePlan.LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
                 var voiceLock = voices.lock()) {
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
