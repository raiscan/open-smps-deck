package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class ReconstructionEditCommand {
    public static final String FILE = ReconstructionEdits.FILE;
    public static final String RESULTS = ReconstructionEdits.RESULTS;
    public static final String LOCK = ".edits.lock";
    private static final com.google.gson.Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private ReconstructionEditCommand() {}

    static void cli(String[] args, PrintStream out) throws IOException {
        var arguments = new ArrayList<>(List.of(args));
        if (arguments.size() >= 2 && arguments.getFirst().equals("propose"))
            java.util.Collections.swap(arguments, 0, 1);
        boolean dryRun = arguments.size() > 2 && arguments.get(2).equals("--dry-run");
        dryRun |= arguments.size() > 3 && arguments.getLast().equals("--dry-run");
        if (dryRun) arguments.remove("--dry-run");
        args = arguments.toArray(String[]::new);
        if (args.length < 2 || args.length > 4)
            throw new IllegalArgumentException("reconstruct edit expects WORK_DIR add|propose JSON | add|propose --file BATCH | list | remove ID");
        JsonElement edit = null;
        String id = null;
        if (args.length == 4) {
            require((args[1].equals("add") || args[1].equals("propose")) && args[2].equals("--file"), "edit", "expected add --file BATCH");
            edit = ReconstructionArtifacts.readTree(Path.of(args[3]));
            if (!edit.isJsonArray()) edit = ReconstructionEdits.batch(edit, "batch");
        } else if (args.length == 3) {
            if (args[1].equals("remove")) id = args[2];
            else if (args[1].equals("add") || args[1].equals("propose")) {
                try {
                    var value = JsonParser.parseString(args[2]);
                    require(value.isJsonObject(), "edit", "expected an intent object");
                    edit = value.getAsJsonObject();
                } catch (JsonParseException error) {
                    throw new IllegalArgumentException("edit: invalid JSON", error);
                }
            } else throw new IllegalArgumentException("Only add or remove takes an argument");
        }
        out.println(JSON.toJson(run(Path.of(args[0]), args[1], edit, id, false, dryRun, (a, b, c) -> {})));
    }

    public static synchronized JsonObject run(Path work, String action, JsonElement edit, String id,
                                              boolean rerun, ReconstructionStages.Progress progress) throws IOException {
        return run(work, action, edit, id, rerun, false, progress);
    }

    public static synchronized JsonObject run(Path work, String action, JsonElement edit, String id,
                                              boolean rerun, boolean dryRun, ReconstructionStages.Progress progress)
            throws IOException {
        boolean propose = action.equals("propose");
        boolean adding = action.equals("add") || propose;
        require(!dryRun || adding && !rerun, "dryRun", "requires add or propose without rerun");
        require(!propose || !rerun, "propose", "use a proposal preview to rebuild");
        require(Files.isDirectory(work), "workDir", "expected an existing directory");
        if (action.equals("list")) {
            require(edit == null && id == null && !rerun, "list", "does not take edit, id or rerun");
            return result(work, ReconstructionEdits.read(work).intents());
        }
        require(adding || action.equals("remove"), "action", "expected add, propose, list or remove");
        require(adding ? edit != null && id == null : edit == null && id != null,
                action, adding ? "requires edit" : "requires id");
        // Preview publishes no artifacts, including the lock file.
        if (dryRun) return change(work, action, edit, id, false, true, progress);
        try (var channel = FileChannel.open(work.resolve(LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            return change(work, action, edit, id, rerun, false, progress);
        }
    }

    public static synchronized JsonObject proposals(Path work, String action, String id, String group)
            throws IOException {
        require(Files.isDirectory(work), "workDir", "expected an existing directory");
        if (action.equals("list"))
            return ReconstructionProposals.listing(work, ReconstructionEdits.read(work), id, group);
        require(action.equals("accept") || action.equals("reject"), "action", "expected list, accept or reject");
        return reviewProposals(work, action.equals("accept"), null,
                journal -> ReconstructionProposals.select(journal, id, group));
    }

    public static synchronized void rejectProposals(Path work, List<String> ids, ReconstructionReview.Revision expected)
            throws IOException {
        var wanted = Set.copyOf(ids);
        reviewProposals(work, false, expected, journal -> journal.intents().stream()
                .filter(intent -> intent.proposed() && wanted.contains(intent.id())).toList());
    }

    private static JsonObject reviewProposals(Path work, boolean accept, ReconstructionReview.Revision expected,
                                               java.util.function.Function<ReconstructionEdits, List<ReconstructionEdits.Intent>> select)
            throws IOException {
        try (var channel = FileChannel.open(work.resolve(LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            if (expected != null && !expected.equals(ReconstructionReview.revision(work))) throw new ReconstructionReview.Changed();
            var journal = ReconstructionEdits.read(work);
            var selected = select.apply(journal);
            require(!selected.isEmpty(), "proposals", "no matching proposals");
            var ids = selected.stream().map(ReconstructionEdits.Intent::id).collect(java.util.stream.Collectors.toSet());
            var updated = new ReconstructionEdits(journal.intents().stream()
                    .filter(intent -> accept || !ids.contains(intent.id()))
                    .map(intent -> ids.contains(intent.id()) ? intent.accept() : intent).toList());
            ReconstructionProposals.refresh(work, updated);
            updated.write(work);
            var result = ReconstructionProposals.listing(work, updated, null, null);
            result.add("ids", JSON.toJsonTree(selected.stream().map(ReconstructionEdits.Intent::id).toList()));
            if (accept) result.addProperty("from", ReconstructionProposals.from(updated.accepted(), selected,
                    ReconstructionEdits.Journal.read(work)).name().toLowerCase(Locale.ROOT));
            return result;
        }
    }

    private static JsonObject change(Path work, String action, JsonElement edit, String id, boolean rerun,
                                     boolean dryRun, ReconstructionStages.Progress progress) throws IOException {
        var intents = new ArrayList<>(ReconstructionEdits.read(work).intents());
        var changed = new ArrayList<ReconstructionEdits.Intent>();
        if (!action.equals("remove")) {
            var seen = new HashSet<>(intents.stream().map(ReconstructionEdits.Intent::id).toList());
            var entries = edit.isJsonArray() ? edit.getAsJsonArray().asList() : List.of(edit);
            require(!entries.isEmpty(), "edits", "expected at least one intent");
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                String at = edit.isJsonArray() ? "edits[" + i + "]" : "edit";
                require(entry.isJsonObject(), at, "expected an intent object");
                var entryObject = entry.getAsJsonObject();
                if (action.equals("propose")) entryObject = ReconstructionProposals.proposed(entryObject);
                var intent = ReconstructionEdits.parse(entryObject, at);
                require(!rerun || !intent.proposed(), at, "use a proposal preview to rebuild");
                require(seen.add(intent.id()), at + ".id", "duplicate edit ID " + intent.id());
                changed.add(intent);
            }
            intents.addAll(changed);
        } else {
            var intent = intents.stream().filter(existing -> existing.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("edit.id: no edit named " + id));
            require(!rerun || !intent.proposed(), action, "proposals do not affect the built arrangement");
            changed.add(intent);
            intents.remove(intent);
        }
        var before = rerun ? ReconstructionTrace.read(work) : Map.<String, ReconstructionTrace.Row>of();
        var updated = new ReconstructionEdits(intents);
        var proposed = changed.stream().filter(ReconstructionEdits.Intent::proposed).toList();
        if (!proposed.isEmpty() && dryRun) {
            require(proposed.size() == changed.size(), action, "dry-run accepts one status per batch");
            var result = JSON.toJsonTree(Map.of("dryRun", true, "proposals",
                    ReconstructionProposals.resolve(work, updated, proposed))).getAsJsonObject();
            return result;
        }
        var prior = ReconstructionEdits.Journal.read(work);
        var from = Objects.equals(prior.scoreRecipe(), updated.accepted().scoreRecipe())
                ? changed.stream().filter(intent -> !intent.proposed()).map(ReconstructionEdits.Intent::firstStage)
                    .min(Comparator.naturalOrder()).orElse(ReconstructionStages.Stage.ASSEMBLE)
                : ReconstructionStages.Stage.TRANSCRIBE;
        String stage = from.name().toLowerCase(Locale.ROOT);
        if (dryRun) {
            var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
            var resolutions = new ArrayList<ReconstructionEdits.Resolution>();
            ReconstructionStages.previewEdits(work, from, updated.accepted(), prior, outcomes, resolutions);
            var ids = new HashSet<>(changed.stream().map(ReconstructionEdits.Intent::id).toList());
            var preview = new JsonObject();
            preview.addProperty("dryRun", true);
            preview.addProperty("from", stage);
            preview.add("outcomes", JSON.toJsonTree(outcomes.stream().filter(row -> ids.contains(row.id())).toList()));
            preview.add("notes", new GsonBuilder().serializeNulls().create()
                    .toJsonTree(resolutions.stream().filter(row -> ids.contains(row.editId())).toList()));
            return preview;
        }
        var args = new ArrayList<>(List.of("--from", stage, work.toString()));
        if (rerun && from == ReconstructionStages.Stage.ALLOCATE && Files.isRegularFile(work.resolve("voicing.json")))
            args.add("--keep-voicing");
        Path rolesFile = work.resolve("roles.json");
        if (rerun && from.ordinal() <= ReconstructionStages.Stage.ROLES.ordinal() && Files.isRegularFile(rolesFile)) {
            double rate = ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class).harmonyMaxHz();
            args.add("--harmony-max-hz");
            args.add(Double.toString(rate));
        }
        ReconstructionProposals.refresh(work, updated);
        updated.write(work);
        if (rerun) ReconstructionStages.runForReview("run", args.toArray(String[]::new), progress);
        var result = result(work, intents);
        if (proposed.size() < changed.size()) result.addProperty("from", stage);
        if (!proposed.isEmpty()) result.add("proposals", ReconstructionProposals.listing(work, updated, null, null).get("proposals"));
        if (edit != null && edit.isJsonArray())
            result.add("ids", JSON.toJsonTree(changed.stream().map(ReconstructionEdits.Intent::id).toList()));
        else result.addProperty("id", changed.getFirst().id());
        if (rerun) {
            var after = ReconstructionTrace.read(work);
            var journal = action.equals("add") ? ReconstructionEdits.Journal.read(work) : prior;
            var timeline = ReconstructionTimeMap.of(ReconstructionStages.readScore(work.resolve("score.json")));
            ReconstructionEdits.Span span = null;
            for (var intent : changed) {
                var range = span(intent, journal, before, after, timeline);
                if (range != null) span = range.union(span);
            }
            var diff = ReconstructionTrace.diff(before, after, span == null ? null
                    : new ReconstructionTrace.Span(span.start(), span.end()));
            var changes = diff.getAsJsonArray("changes");
            long outside = changes.asList().stream().filter(row -> row.getAsJsonObject().has("outsideSpan")
                    && row.getAsJsonObject().get("outsideSpan").getAsBoolean()).count();
            diff.addProperty("changedNotes", changes.size());
            diff.addProperty("outsideSpanChanges", outside);
            if (span != null) {
                diff.add("span", JSON.toJsonTree(List.of(span.start(), span.end())));
                diff.add("projectSpan", JSON.toJsonTree(List.of(timeline.project(span.start()), timeline.project(span.end()))));
            }
            result.add("traceDiff", diff);
        }
        return result;
    }

    private static JsonObject result(Path work, List<ReconstructionEdits.Intent> intents) throws IOException {
        return JSON.toJsonTree(Map.of("version", ReconstructionStages.VERSION,
                "edits", intents.stream().map(ReconstructionEdits.Intent::json).toList(),
                "lastRun", ReconstructionEdits.Journal.read(work).all())).getAsJsonObject();
    }

    static ReconstructionEdits.Span span(ReconstructionEdits.Intent edit, ReconstructionEdits.Journal journal,
                                                  Map<String, ReconstructionTrace.Row> before,
                                                  Map<String, ReconstructionTrace.Row> after,
                                                  ReconstructionTimeMap timeline) {
        var range = edit.explicitSpan();
        if (range != null) range = new ReconstructionEdits.Span(edit.toSource(range.start(), timeline),
                edit.toSource(range.end(), timeline));
        if (edit.span() != null && range != null && edit.kind() != ReconstructionEdits.Kind.mergeNotes
                && edit.kind() != ReconstructionEdits.Kind.layer) return range;
        var ids = new java.util.LinkedHashSet<>(edit.noteIds());
        if (edit.kind() == ReconstructionEdits.Kind.insertNote) ids.add(edit.insertedId());
        for (var outcome : journal.all())
            if (outcome.id().equals(edit.id()) && (outcome.status() == ReconstructionEdits.Status.applied
                    || outcome.status() == ReconstructionEdits.Status.satisfied || outcome.layer() != null)) {
                ids.addAll(outcome.noteIds());
                if (outcome.removedNotes() != null)
                    for (var removed : outcome.removedNotes()) ids.add(removed.id());
            }
        if (edit.attack() != null && ids.isEmpty()) {
            double at = edit.attack().at(), tolerance = ReconstructionEdits.Attack.TOLERANCE;
            range = new ReconstructionEdits.Span(edit.toSource(Math.max(0, at - tolerance), timeline),
                    edit.toSource(at + tolerance, timeline)).union(range);
        }
        for (var notes : List.of(before, after)) for (String id : ids) {
            var row = notes.get(id);
            if (row == null) continue;
            var time = row.score() == null ? row.timing() : row.score().timing();
            var gate = new ReconstructionEdits.Span(time.sourceStart(), Math.max(time.sourceEnd(), time.sourceStart() + 1e-6));
            range = gate.union(range);
            if (edit.kind() == ReconstructionEdits.Kind.layer && row.layers() != null) {
                for (var layer : row.layers()) {
                    if (layer.status() != ReconstructionLayers.Status.applied) continue;
                    var timing = ReconstructionTrace.ticks(layer.startTick(), layer.endTick(), timeline);
                    range = new ReconstructionEdits.Span(timing.sourceStart(), timing.sourceEnd()).union(range);
                }
            }
        }
        return range;
    }
}
