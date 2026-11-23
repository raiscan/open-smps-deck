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
        boolean dryRun = arguments.remove("--dry-run");
        boolean verbose = arguments.remove("--verbose");
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
        JsonObject response;
        try {
            response = run(Path.of(args[0]), args[1], edit, id, false, dryRun, (a, b, c) -> {});
        } catch (BatchValidation failure) {
            response = failure.response();
        }
        out.println(verbose || !response.has("results") || !dryRun && (edit == null || !edit.isJsonArray()) ? JSON.toJson(response) : new com.google.gson.Gson().toJson(compact(response)));
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
        return proposalBatch(work, action, id == null ? List.of() : List.of(id), group, null, false, false);
    }

    public static synchronized JsonObject proposalBatch(Path work, String action, List<String> ids,
                                                        String group, String op, boolean showDecided,
                                                        boolean verbose) throws IOException {
        require(Files.isDirectory(work), "workDir", "expected an existing directory");
        require(ids.isEmpty() || group == null && op == null, "proposals", "choose IDs or selectors");
        if (action.equals("list"))
            return ReconstructionProposals.listing(work, ReconstructionEdits.read(work), ids, group, op, showDecided);
        require(action.equals("accept") || action.equals("reject"), "action", "expected list, accept or reject");
        require(!showDecided, "showDecided", "only applies to list");
        require(!ids.isEmpty() || group != null || op != null, "proposals", "select IDs, a group or an operation");
        return reviewProposals(work, action.equals("accept"), ids, group, op, verbose, null);
    }

    public static synchronized void rejectProposals(Path work, List<String> ids, ReconstructionReview.Revision expected)
            throws IOException {
        require(!ids.isEmpty(), "proposals", "select proposals to reject");
        reviewProposals(work, false, ids, null, null, false, expected);
    }

    private static JsonObject reviewProposals(Path work, boolean accept, List<String> ids, String group, String op,
                                              boolean verbose, ReconstructionReview.Revision expected) throws IOException {
        try (var channel = FileChannel.open(work.resolve(LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            if (expected != null && !expected.equals(ReconstructionReview.revision(work))) throw new ReconstructionReview.Changed();
            var journal = ReconstructionEdits.read(work);
            var selected = ReconstructionProposals.selectBatch(journal, ids, group, op);
            require(!selected.isEmpty(), "proposals", "no matching proposals");
            var selectedIds = selected.stream().map(ReconstructionEdits.Intent::id).toList();
            var wanted = Set.copyOf(selectedIds);
            String status = accept ? "accepted" : "rejected";
            var updated = new ReconstructionEdits(journal.intents().stream()
                    .map(intent -> wanted.contains(intent.id()) ? intent.decide(status) : intent).toList());
            var result = new JsonObject();
            result.add("ids", JSON.toJsonTree(selectedIds));
            if (accept) {
                var checks = ReconstructionProposals.resolve(work, journal, selected);
                result.add("results", JSON.toJsonTree(checks.stream().map(check ->
                        new IntentResult(check.id(), summary(check.outcomes()), check.outcomes().stream()
                                .map(ReconstructionReview.EditResult::reason).filter(Objects::nonNull).distinct().toList())).toList()));
                if (verbose) result.add("proposals", JSON.toJsonTree(checks));
                if (checks.stream().anyMatch(check -> summary(check.outcomes()).equals("rejected"))) {
                    result.addProperty("status", "unresolved");
                    return result;
                }
                result.addProperty("from", ReconstructionProposals.from(work, updated.accepted(), selected,
                        ReconstructionEdits.Journal.read(work)).name().toLowerCase(Locale.ROOT));
            }
            updated.write(work);
            result.addProperty("status", status);
            return result;
        }
    }

    public static synchronized JsonObject revise(Path work, String id, JsonObject replacement,
                                                  ReconstructionReview.Revision expected) throws IOException {
        try (var channel = FileChannel.open(work.resolve(LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            if (expected != null && !expected.equals(ReconstructionReview.revision(work))) throw new ReconstructionReview.Changed();
            var journal = ReconstructionEdits.read(work);
            var current = ReconstructionProposals.selectBatch(journal, List.of(id), null, null).getFirst();
            var revised = ReconstructionProposals.proposed(replacement);
            revised.addProperty("proposalRevised", true);
            revised.addProperty("id", id);
            if (!revised.has("group") && current.json().has("group")) revised.add("group", current.json().get("group"));
            var intent = ReconstructionEdits.parse(revised, "revise");
            var updated = new ReconstructionEdits(journal.intents().stream()
                    .map(entry -> entry.id().equals(id) ? intent : entry).toList());
            updated.write(work);
            return JSON.toJsonTree(Map.of("id", id, "status", "proposed", "edit", revised)).getAsJsonObject();
        }
    }

    record VocalProposals(Map<String, JsonObject> entries, List<JsonObject> decided, int saved) {}

    static synchronized VocalProposals vocalProposals(Path work, List<JsonObject> generated, boolean save,
                                                       List<ReconstructionVocalReview.Span> scopes) throws IOException {
        if (!save) return vocalProposals(work, generated, scopes, false);
        try (var channel = FileChannel.open(work.resolve(LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            return vocalProposals(work, generated, scopes, true);
        }
    }

    private static VocalProposals vocalProposals(Path work, List<JsonObject> generated,
                                                  List<ReconstructionVocalReview.Span> scopes, boolean save) throws IOException {
        var journal = ReconstructionEdits.read(work);
        var vocal = journal.intents().stream().filter(intent -> intent.json().has("group")
                && intent.json().get("group").getAsString().equals(ReconstructionVocalReview.GROUP)).toList();
        var byKey = new java.util.HashMap<String, ReconstructionEdits.Intent>();
        for (var intent : vocal) byKey.merge(vocalKey(intent.id()), intent,
                (first, second) -> first.proposed() ? second : first);
        var legacy = vocal.stream().filter(intent -> !intent.proposed() && vocalKey(intent.id()).equals(intent.id()))
                .collect(java.util.stream.Collectors.toMap(intent -> musicalIntent(intent.json()),
                        java.util.function.Function.identity(), (first, second) -> first));
        var matching = new java.util.HashMap<String, JsonObject>();
        var activeIds = new HashSet<String>();
        var fresh = new ArrayList<ReconstructionEdits.Intent>();
        for (var edit : generated) {
            String id = edit.get("id").getAsString();
            var previous = byKey.get(vocalKey(id));
            if (previous == null) previous = legacy.get(musicalIntent(edit));
            if (previous == null) fresh.add(ReconstructionEdits.parse(edit, "vocal-review"));
            else {
                matching.put(id, previous.json());
                activeIds.add(previous.id());
            }
        }
        var decided = vocal.stream().filter(intent -> !intent.proposed())
                .filter(intent -> activeIds.contains(intent.id()) || inVocalScope(intent.id(), scopes))
                .map(ReconstructionEdits.Intent::json).toList();
        if (!save) return new VocalProposals(matching, decided, 0);
        var stale = vocal.stream().filter(intent -> intent.proposed() && !intent.json().has("proposalRevised") && !activeIds.contains(intent.id())
                        && inVocalScope(intent.id(), scopes)).map(ReconstructionEdits.Intent::id).collect(java.util.stream.Collectors.toSet());
        if (!fresh.isEmpty() || !stale.isEmpty()) {
            var updated = new ArrayList<>(journal.intents().stream().filter(intent -> !stale.contains(intent.id())).toList());
            updated.addAll(fresh);
            new ReconstructionEdits(updated).write(work);
        }
        return new VocalProposals(matching, decided, fresh.size());
    }

    private static JsonObject musicalIntent(JsonObject edit) {
        var operation = edit.deepCopy();
        for (String key : List.of("id", "reason", "evidence", "status", "group", "author", "proposalRevised"))
            operation.remove(key);
        return operation;
    }

    private static String vocalKey(String id) {
        String suffix = id.substring(id.lastIndexOf('-') + 1);
        return id.startsWith("vocal-") && suffix.matches("[0-9a-f]{20}") ? suffix : id;
    }

    private static boolean inVocalScope(String id, List<ReconstructionVocalReview.Span> scopes) {
        String[] fields = id.split("-", 5);
        if (fields.length < 4 || !fields[0].equals("vocal") || !fields[2].matches("[0-9]{1,12}")) return false;
        double seconds = Long.parseLong(fields[2]) / 1_000_000.0;
        return scopes.stream().anyMatch(scope -> seconds >= scope.start() - 1e-6 && seconds < scope.end() - 1e-6);
    }

    private record IntentResult(String id, String status, List<String> reasons) {}

    public static final class BatchValidation extends IllegalArgumentException {
        private final JsonObject response;

        BatchValidation(List<IntentResult> results, boolean dryRun) {
            super("Batch contains an invalid intent");
            response = JSON.toJsonTree(Map.of("results", results, "journalSaved", false, "dryRun", dryRun))
                    .getAsJsonObject();
        }

        public JsonObject response() { return response; }
    }

    private static String summary(List<ReconstructionReview.EditResult> outcomes) {
        if (outcomes.isEmpty() || outcomes.stream().anyMatch(row ->
                !Set.of("applied", "satisfied").contains(row.status()))) return "rejected";
        return outcomes.stream().anyMatch(row -> row.status().equals("applied")) ? "applied" : "satisfied";
    }

    private static JsonElement intentResults(List<ReconstructionEdits.Intent> changed,
                                             List<ReconstructionEdits.Outcome> outcomes) {
        var byId = outcomes.stream().collect(java.util.stream.Collectors.groupingBy(ReconstructionEdits.Outcome::id));
        return JSON.toJsonTree(changed.stream().map(intent -> {
            var details = byId.getOrDefault(intent.id(), List.of()).stream().map(row ->
                    new ReconstructionReview.EditResult(row.status().name(), row.detail(), row.noteIds())).toList();
            var reasons = details.stream().map(ReconstructionReview.EditResult::reason)
                    .filter(Objects::nonNull).distinct().toList();
            return new IntentResult(intent.id(), summary(details),
                    details.isEmpty() ? List.of("No matching edit outcome") : reasons);
        }).toList());
    }

    public static JsonObject compact(JsonObject result) {
        var compact = new JsonObject();
        for (String key : List.of("id", "ids", "dryRun", "from", "results", "journalSaved", "warnings", "traceDiff"))
            if (result.has(key)) compact.add(key, result.get(key));
        return compact;
    }

    private static JsonObject change(Path work, String action, JsonElement edit, String id, boolean rerun,
                                     boolean dryRun, ReconstructionStages.Progress progress) throws IOException {
        var intents = new ArrayList<>(ReconstructionEdits.read(work).intents());
        var changed = new ArrayList<ReconstructionEdits.Intent>();
        if (!action.equals("remove")) {
            var seen = new HashSet<>(intents.stream().map(ReconstructionEdits.Intent::id).toList());
            var entries = edit.isJsonArray() ? edit.getAsJsonArray().asList() : List.of(edit);
            require(!entries.isEmpty(), "edits", "expected at least one intent");
            var parsed = new ArrayList<IntentResult>();
            boolean invalid = false;
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                String at = edit.isJsonArray() ? "edits[" + i + "]" : "edit";
                try {
                    require(entry.isJsonObject(), at, "expected an intent object");
                    var entryObject = entry.getAsJsonObject();
                    if (action.equals("propose")) entryObject = ReconstructionProposals.proposed(entryObject);
                    var intent = ReconstructionEdits.parse(entryObject, at);
                    require(!rerun || !intent.inactive(), at, "use a proposal preview to rebuild");
                    require(seen.add(intent.id()), at + ".id", "duplicate edit ID " + intent.id());
                    changed.add(intent);
                    parsed.add(new IntentResult(intent.id(), "rejected", List.of("Batch contains an invalid intent")));
                } catch (IllegalArgumentException failure) {
                    if (!edit.isJsonArray() || !action.equals("add")) throw failure;
                    invalid = true;
                    var name = entry.isJsonObject() ? entry.getAsJsonObject().get("id") : null;
                    parsed.add(new IntentResult(name != null && name.isJsonPrimitive() ? name.getAsString() : at,
                            "rejected", List.of(failure.getMessage())));
                }
            }
            if (invalid) throw new BatchValidation(parsed, dryRun);
            intents.addAll(changed);
        } else {
            var intent = intents.stream().filter(existing -> existing.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("edit.id: no edit named " + id));
            require(!rerun || !intent.inactive(), action, "proposals do not affect the built arrangement");
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
        var from = Objects.equals(prior.scoreRecipe(), updated.accepted().scoreRecipe(work))
                ? changed.stream().filter(intent -> !intent.inactive()).map(ReconstructionEdits.Intent::firstStage)
                    .min(Comparator.naturalOrder()).orElse(ReconstructionStages.Stage.ASSEMBLE)
                : ReconstructionStages.Stage.TRANSCRIBE;
        String stage = from.name().toLowerCase(Locale.ROOT);
        if (dryRun || action.equals("add") && !rerun && proposed.isEmpty()) {
            var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
            var resolutions = new ArrayList<ReconstructionEdits.Resolution>();
            try {
                ReconstructionStages.previewEdits(work, from, updated.accepted(), prior, outcomes, resolutions);
            } catch (IOException | IllegalArgumentException failure) {
                if (dryRun) throw failure;
                for (var intent : changed) outcomes.add(new ReconstructionEdits.Outcome(intent.id(), intent.kind(),
                        ReconstructionEdits.Status.invalid, List.of(), null, failure.getMessage(), null));
            }
            var ids = new HashSet<>(changed.stream().map(ReconstructionEdits.Intent::id).toList());
            var preview = new JsonObject();
            if (dryRun) preview.addProperty("dryRun", true);
            else {
                updated.write(work);
                preview.addProperty("journalSaved", true);
                if (edit.isJsonArray()) preview.add("ids", JSON.toJsonTree(changed.stream().map(ReconstructionEdits.Intent::id).toList()));
                else preview.addProperty("id", changed.getFirst().id());
            }
            preview.add("results", intentResults(changed, outcomes));
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
        updated.write(work);
        if (rerun) ReconstructionStages.runForReview("run", args.toArray(String[]::new), progress);
        var result = result(work, intents);
        if (proposed.size() < changed.size()) result.addProperty("from", stage);
        if (!proposed.isEmpty()) result.add("proposals", JSON.toJsonTree(ReconstructionProposals.resolve(work, updated, proposed)));
        if (edit != null && edit.isJsonArray())
            result.add("ids", JSON.toJsonTree(changed.stream().map(ReconstructionEdits.Intent::id).toList()));
        else result.addProperty("id", changed.getFirst().id());
        if (rerun) {
            if (action.equals("add")) result.add("results", intentResults(changed, ReconstructionEdits.Journal.read(work).all()));
            result.add("warnings", JSON.toJsonTree(ReconstructionStages.packageWarnings(work)));
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
        if (edit.kind() == ReconstructionEdits.Kind.repeatPolicy)
            return new ReconstructionEdits.Span(0, timeline.sourceDuration());
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
