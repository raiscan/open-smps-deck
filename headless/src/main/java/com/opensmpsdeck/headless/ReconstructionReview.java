package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.LoadedProject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Revision-aware journal operations for interactive reconstruction review. */
public final class ReconstructionReview {
    private static final List<String> OUTPUTS = List.of("score.json", ReconstructionStemTemplates.FILE, "structure.json", "roles.json",
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

    public record EditResult(String status, String reason, List<String> noteIds, ReconstructionLayers.Result layer) {
        EditResult(String status, String reason, List<String> noteIds) { this(status, reason, noteIds, null); }
    }

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
                    .add(new EditResult(outcome.status().name(), outcome.detail(), outcome.noteIds(), outcome.layer()));
        results.replaceAll((id, values) -> List.copyOf(values));
        return java.util.Map.copyOf(results);
    }

    public static ReconstructionVoicePlan.Result voices(Path work, JsonArray entries) throws IOException {
        if (!Files.isRegularFile(work.resolve("roles.json")))
            return new ReconstructionVoicePlan.Result(1, List.of(), List.of(), List.of());
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

    static ReconstructionEdits parse(JsonArray entries) {
        var ids = new HashSet<String>();
        var intents = new ArrayList<ReconstructionEdits.Intent>();
        for (var entry : entries) {
            var intent = parse(entry);
            if (!ids.add(intent.id())) throw new IllegalArgumentException("Duplicate edit ID " + intent.id());
            intents.add(intent);
        }
        return new ReconstructionEdits(intents);
    }

    /** Preview new orphaned detail targets before accepting a classification edit. */
    public static List<String> newOrphanedDetails(Path work, JsonArray before, JsonArray after) throws IOException {
        var prior = ReconstructionEdits.Journal.read(work);
        var previous = orphans(preview(work, before, prior));
        return orphans(preview(work, after, prior)).entrySet().stream()
                .filter(entry -> !previous.containsKey(entry.getKey())).map(Map.Entry::getValue).toList();
    }

    private static List<ReconstructionEdits.Outcome> preview(Path work, JsonArray entries,
                                                             ReconstructionEdits.Journal prior) throws IOException {
        var changes = changes(revision(work).entries(), entries);
        if (changes.isEmpty()) return prior.all();
        var edits = parse(entries).accepted();
        var from = ReconstructionStages.Stage.parse(stage(work, changes, edits));
        var results = new ArrayList<ReconstructionEdits.Outcome>();
        ReconstructionStages.previewEdits(work, from, edits, prior, results, new ArrayList<>(), new java.util.HashMap<>());
        return results;
    }

    private static Map<List<String>, String> orphans(List<ReconstructionEdits.Outcome> outcomes) {
        var result = new java.util.LinkedHashMap<List<String>, String>();
        for (var outcome : outcomes) {
            if (outcome.status() != ReconstructionEdits.Status.orphaned
                    || !(ReconstructionEdits.shapesSound(outcome.op()) || outcome.op() == ReconstructionEdits.Kind.layer))
                continue;
            var targets = outcome.missingNoteIds();
            if (targets == null || targets.isEmpty()) targets = outcome.noteIds();
            if (targets.isEmpty()) targets = List.of("");
            for (String target : targets) result.put(List.of(outcome.id(), target), outcome.id() + " · " + outcome.detail());
        }
        return result;
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
        if (!Objects.equals(prior.scoreRecipe(), edits.accepted().scoreRecipe(work))) return "transcribe";
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

    static void requireRevision(Path work, Revision expected) throws IOException {
        if (!expected.equals(revision(work))) throw new Changed();
    }

    public static final String ARRANGEMENT = "arrangement.smps.json";

    public static LoadedProject arrangement(Path work) throws IOException {
        var path = work.resolve(ARRANGEMENT);
        return Files.notExists(path, NOFOLLOW_LINKS) ? null : ProjectFiles.load(path);
    }

    static LoadedProject requireAttached(Path work, boolean overwriteDetached) throws IOException {
        var current = arrangement(work);
        if (current != null && current.project().detachedFromReconstruction() && !overwriteDetached)
            throw new IOException("The arrangement has hand edits. Save a copy or use --overwrite-detached to replace it.");
        return current;
    }

    public static JsonObject apply(Path work, Revision expected, JsonArray entries, ReconstructionStages.Progress progress)
            throws IOException {
        return apply(work, expected, entries, null, progress);
    }

    /**
     * Rebuild and compare a private copy. Publish only after the pipeline and comparison finish,
     * and only if the source artifacts and both journals still match the starting revision.
     */
    public static JsonObject apply(Path work, Revision expected, JsonArray entries, String detachedRevision,
                                   ReconstructionStages.Progress progress) throws IOException {
        return apply(work, expected, entries, detachedRevision, null, progress, null, null);
    }

    public static JsonObject applyLanePolicyPreset(Path work, Revision expected, String preset, String detachedRevision,
                                                   ReconstructionStages.Progress progress) throws IOException {
        return apply(work, expected, expected.entries(), detachedRevision, preset, progress, null, null);
    }

    private static final class LiveBuild {
        final boolean save;
        LiveUpdate update;

        LiveBuild(boolean save) { this.save = save; }
    }

    /**
     * Replay against the built allocation in a private directory. The disk revision guards
     * the inputs, while the built journal identifies the stages that need to be replayed.
     */
    public static ReconstructionLivePreview.Prepared liveRebuild(Path work, Revision expected, JsonArray built,
                                                                  JsonArray entries, ReconstructionStages.Progress progress)
            throws IOException {
        return liveRebuild(work, expected, built, entries, false, progress).playback();
    }

    /** Keep source-dependent evidence across journal revisions on a single replay worker. */
    public static final class LiveReplay {
        private final ReconstructionLiveEvidence evidence = new ReconstructionLiveEvidence();

        public void warm(Path work) throws IOException {
            evidence.run(() -> {
                ReconstructionStages.warmLive(work);
                return null;
            });
        }

        public LiveUpdate rebuild(Path work, Revision expected, JsonArray built, JsonArray entries,
                                  boolean save, ReconstructionStages.Progress progress) throws IOException {
            return evidence.run(() -> liveRebuild(work, expected, built, entries, save, progress));
        }
    }

    public record LiveUpdate(ReconstructionLivePreview.Prepared playback, Revision revision) {}

    public static LiveUpdate liveRebuild(Path work, Revision expected, JsonArray built, JsonArray entries,
                                         boolean save, ReconstructionStages.Progress progress) throws IOException {
        var result = new LiveBuild(save);
        apply(work, expected, entries, null, null, progress, built, result);
        return result.update;
    }

    public record StockVoice(String id, String name) {
        @Override public String toString() { return name; }
    }

    public static List<StockVoice> stockVoices(Path work) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var catalogue = stockCatalogue(score);
        return catalogue.variants().stream()
                .filter(variant -> catalogue.asset(variant.assetId()).kind() == InstrumentCatalogue.Kind.FM_VOICE)
                .map(variant -> new StockVoice(variant.id(), variant.name()))
                .sorted(Comparator.comparing(StockVoice::name).thenComparing(StockVoice::id))
                .toList();
    }

    private static InstrumentCatalogue stockCatalogue(ReconstructionStages.Score score) throws IOException {
        return InstrumentCatalogue.openReconstruction(Path.of(score.inputs().catalogue()), score.inputs().targetGame());
    }

    public static String refitStockVoice(Path work, ReconstructionLivePreview.Prepared current, int part)
            throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var voicing = ReconstructionArtifacts.read(work.resolve("voicing.json"), ReconstructionVoicing.Result.class);
        var catalogue = stockCatalogue(score);
        var allocation = ReconstructionAllocation.sourcePlacement(current.allocation(), score.frames(), ReconstructionTimeMap.of(score));
        var previous = voicing.sourceInstruments() == null ? null : voicing;
        var fitted = ReconstructionVoiceStage.fit(score, catalogue, allocation, current.allocation(),
                previous, java.util.Set.<String>of(), null);
        var instruments = fitted.sourceInstruments();
        var sound = instruments == null ? null : instruments.get(ReconstructionVoicePlan.SourceInstrument.id(part));
        if (sound == null || sound.fm().variantId() == null)
            throw new IOException("The stock fit produced no FM voice for this part");
        return sound.fm().variantId();
    }

    /** Reuse the per-note voicing operation. Preview callers keep this intent in memory. */
    public static JsonObject stockVoiceEdit(Path work, ReconstructionLivePreview.Prepared current, int part,
                                            String variant) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var catalogue = stockCatalogue(score);
        var match = catalogue.match(variant);
        if (match.asset().kind() != InstrumentCatalogue.Kind.FM_VOICE)
            throw new IllegalArgumentException("Choose a stock FM voice");
        var notes = new JsonArray();
        current.allocation().notes().stream().filter(row -> row.note().part() == part
                        && row.lane() != null && row.lane().startsWith("FM"))
                .forEach(row -> notes.add(row.note().id()));
        if (notes.isEmpty()) throw new IllegalArgumentException("This part has no allocated FM notes");
        var edit = new JsonObject();
        edit.addProperty("id", "stock-voice-" + java.util.UUID.randomUUID());
        edit.addProperty("op", "noteVoicing");
        edit.addProperty("reason", "Choose stock voice " + match.variant().name());
        edit.addProperty("author", "human");
        edit.addProperty("laneType", "FM");
        edit.addProperty("instrument", match.variant().id());
        edit.add("noteIds", notes);
        return edit;
    }

    public static JsonObject applySaved(Path work, Revision expected, JsonArray built, JsonArray entries,
                                        String detachedRevision, ReconstructionStages.Progress progress) throws IOException {
        return apply(work, expected, entries, detachedRevision, null, progress, built, null);
    }

    private static JsonObject apply(Path work, Revision expected, JsonArray entries, String detachedRevision,
                                    String lanePolicyPreset, ReconstructionStages.Progress progress,
                                    JsonArray built, LiveBuild live) throws IOException {
        requireRevision(work, expected);
        var arrangement = requireAttached(work, detachedRevision != null);
        if (detachedRevision != null && (arrangement == null || !detachedRevision.equals(arrangement.revision())))
            throw new Changed();
        var updated = parse(entries);
        var changed = changes(built == null ? expected.entries() : built, entries);
        Path scratch = Files.createTempDirectory("reconstruction-review-");
        try {
            var before = Files.createDirectory(scratch.resolve("before"));
            var after = Files.createDirectory(scratch.resolve("after"));
            var inputs = List.of(ReconstructionSections.FILE, "roleplan.json", ReconstructionVoicePlan.JOURNAL);
            var paths = new ArrayList<>(OUTPUTS);
            if (live != null) paths.removeAll(List.of("render.wav", "arrangement.smps.zip", ReconstructionCli.STEADY_REFERENCE));
            paths.addAll(inputs);
            for (String file : paths) {
                var source = work.resolve(file);
                if (Files.isRegularFile(source, NOFOLLOW_LINKS)) {
                    ReconstructionLiveEvidence.copy(source, before.resolve(file), after.resolve(file));
                }
                checkCancelled();
            }
            requireRevision(before, expected);
            if (live == null) {
                var copied = arrangement(before);
                if (!Objects.equals(arrangement == null ? null : arrangement.revision(),
                        copied == null ? null : copied.revision())) throw new Changed();
            } else {
                var copied = before.resolve(ARRANGEMENT);
                var revision = Files.exists(copied) ? ProjectFiles.fileRevision(copied) : null;
                if (!Objects.equals(arrangement == null ? null : arrangement.revision(), revision)) throw new Changed();
            }
            String from = lanePolicyPreset == null ? stage(before, changed, updated) : "assemble";
            if (built != null && changed.isEmpty() && from.equals("roles")) from = "assemble";
            if (expected.voices() != null && ReconstructionStages.Stage.parse(from).ordinal()
                    > ReconstructionStages.Stage.VOICES.ordinal()) {
                var voices = ReconstructionVoicePlan.saved(before);
                if (voices == null || !voices.equals(ReconstructionVoicePlan.load(before))) from = "voices";
            }
            if (lanePolicyPreset == null) updated.write(after);
            var args = new ArrayList<>(List.of("--from", from, after.toString(), "--policy-decisions"));
            if (detachedRevision != null) args.add("--overwrite-detached");
            if (lanePolicyPreset != null) args.addAll(List.of("--lane-policy-preset", lanePolicyPreset));
            var fromStage = ReconstructionStages.Stage.parse(from);
            if (fromStage.ordinal() <= ReconstructionStages.Stage.VOICE.ordinal()) args.add("--keep-voicing");
            var roles = ReconstructionTrace.optional(before, "roles.json", ReconstructionRoles.Result.class);
            if (roles != null && fromStage.ordinal() <= ReconstructionStages.Stage.ROLES.ordinal()) {
                args.add("--harmony-max-hz");
                args.add(Double.toString(roles.harmonyMaxHz()));
            }
            ReconstructionStages.Progress checkedProgress = (completed, total, message) -> {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                progress.report(completed, total + 1, message);
            };
            if (live != null) {
                var compiled = ReconstructionStages.runForLive(args.toArray(String[]::new), checkedProgress, arrangement);
                for (String file : paths)
                    if (!same(work.resolve(file), before.resolve(file))) throw new Changed();
                requireRevision(work, expected);
                ReconstructionLiveEvidence.requireCurrent();
                checkCancelled();
                var prepared = ReconstructionLivePreview.rebuilt(compiled.playback(), compiled.allocation());
                var revision = expected;
                if (live.save) {
                    var outcomes = ReconstructionEdits.Journal.read(after).all();
                    var changedIds = changed.stream().map(ReconstructionEdits.Intent::id).collect(java.util.stream.Collectors.toSet());
                    for (var outcome : outcomes)
                        if (changedIds.contains(outcome.id()) && (outcome.status() == ReconstructionEdits.Status.invalid
                                || outcome.status() == ReconstructionEdits.Status.conflict))
                            throw new IOException(outcome.tag() + " · " + outcome.detail());
                    updated.write(after);
                    revision = revision(after);
                    publish(work, before, after, expected, paths, List.of(ReconstructionEdits.FILE),
                            (done, total, message) -> {}, 1);
                }
                live.update = new LiveUpdate(prepared, revision);
                return null;
            }
            ReconstructionStages.runForReview("run", args.toArray(String[]::new), checkedProgress);
            checkCancelled();
            if (ReconstructionEdits.read(after).intents().stream().noneMatch(ReconstructionEdits.Intent::proposed))
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

    public static ProposalPreview previewLayer(Path work, JsonObject layer, Revision expected,
                                               ReconstructionStages.Progress progress) throws IOException {
        work = work.toRealPath();
        requireRevision(work, expected);
        var edit = parse(layer);
        ReconstructionArtifacts.require(edit.kind() == ReconstructionEdits.Kind.layer && !edit.proposed(),
                "layer", "expected a layer edit");
        var entries = expected.entries();
        entries.add(layer);
        var shadow = parse(entries).accepted();
        return preview(work, expected, List.of(edit), shadow, ReconstructionStages.Stage.ASSEMBLE, progress);
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
        var from = ReconstructionProposals.from(work, shadow, selected, ReconstructionEdits.Journal.read(work));
        if (start != null && start.ordinal() < from.ordinal()) from = start;
        return preview(work, expected, selected, shadow, from, progress);
    }

    private static ProposalPreview preview(Path work, Revision expected, List<ReconstructionEdits.Intent> selected,
                                           ReconstructionEdits shadow, ReconstructionStages.Stage from,
                                           ReconstructionStages.Progress progress) throws IOException {
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
            // A preview replaces only the arrangement copied into the private replay directory.
            var args = new ArrayList<>(List.of("--from", from.name().toLowerCase(Locale.ROOT), target.toString(),
                    "--overwrite-detached"));
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
