package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("reconstruct edit expects WORK_DIR add JSON | list | remove ID");
        JsonObject edit = null;
        String id = null;
        if (args.length == 3) {
            if (args[1].equals("remove")) id = args[2];
            else if (args[1].equals("add")) {
                try {
                    var value = JsonParser.parseString(args[2]);
                    require(value.isJsonObject(), "edit", "expected an intent object");
                    edit = value.getAsJsonObject();
                } catch (JsonParseException error) {
                    throw new IllegalArgumentException("edit: invalid JSON", error);
                }
            } else throw new IllegalArgumentException("Only add or remove takes an argument");
        }
        out.println(JSON.toJson(run(Path.of(args[0]), args[1], edit, id, false, (a, b, c) -> {})));
    }

    public static synchronized JsonObject run(Path work, String action, JsonObject edit, String id,
                                              boolean rerun, ReconstructionStages.Progress progress) throws IOException {
        require(Files.isDirectory(work), "workDir", "expected an existing directory");
        if (action.equals("list")) {
            require(edit == null && id == null && !rerun, "list", "does not take edit, id or rerun");
            return result(work, ReconstructionEdits.read(work).intents());
        }
        require(action.equals("add") || action.equals("remove"), "action", "expected add, list or remove");
        require(action.equals("add") ? edit != null && id == null : edit == null && id != null,
                action, action.equals("add") ? "requires edit" : "requires id");
        try (var channel = FileChannel.open(work.resolve(LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            return change(work, action, edit, id, rerun, progress);
        }
    }

    private static JsonObject change(Path work, String action, JsonObject edit, String id, boolean rerun,
                                     ReconstructionStages.Progress progress) throws IOException {
        var intents = new ArrayList<>(ReconstructionEdits.read(work).intents());
        ReconstructionEdits.Intent changed;
        if (action.equals("add")) {
            changed = ReconstructionEdits.parse(edit, "edit");
            require(intents.stream().noneMatch(intent -> intent.id().equals(changed.id())),
                    "edit.id", "duplicate edit ID " + changed.id());
            intents.add(changed);
        } else {
            changed = intents.stream().filter(intent -> intent.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("edit.id: no edit named " + id));
            intents.remove(changed);
        }
        var before = rerun ? ReconstructionTrace.read(work) : Map.<String, ReconstructionTrace.Row>of();
        var updated = new ReconstructionEdits(intents);
        var prior = ReconstructionEdits.Journal.read(work);
        var from = Objects.equals(prior.scoreRecipe(), updated.scoreRecipe())
                ? changed.firstStage() : ReconstructionStages.Stage.TRANSCRIBE;
        String stage = from.name().toLowerCase(Locale.ROOT);
        var args = new ArrayList<>(List.of("--from", stage, work.toString()));
        Path rolesFile = work.resolve("roles.json");
        if (rerun && Files.isRegularFile(rolesFile)) {
            double rate = ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class).harmonyMaxHz();
            args.add("--harmony-max-hz");
            args.add(Double.toString(rate));
        }
        updated.write(work);
        if (rerun) ReconstructionStages.runForReview("run", args.toArray(String[]::new), progress);
        var result = result(work, intents);
        result.addProperty("from", stage);
        result.addProperty("id", changed.id());
        if (rerun) {
            var after = ReconstructionTrace.read(work);
            var span = span(changed, before, after);
            var diff = ReconstructionTrace.diff(before, after, span == null ? null
                    : new ReconstructionTrace.Span(span.start(), span.end()));
            var changes = diff.getAsJsonArray("changes");
            long outside = changes.asList().stream().filter(row -> row.getAsJsonObject().has("outsideSpan")
                    && row.getAsJsonObject().get("outsideSpan").getAsBoolean()).count();
            diff.addProperty("changedNotes", changes.size());
            diff.addProperty("outsideSpanChanges", outside);
            if (span != null) diff.add("span", JSON.toJsonTree(List.of(span.start(), span.end())));
            result.add("traceDiff", diff);
        }
        return result;
    }

    private static JsonObject result(Path work, List<ReconstructionEdits.Intent> intents) throws IOException {
        return JSON.toJsonTree(Map.of("version", ReconstructionStages.VERSION,
                "edits", intents.stream().map(ReconstructionEdits.Intent::json).toList(),
                "lastRun", ReconstructionEdits.Journal.read(work).all())).getAsJsonObject();
    }

    private static ReconstructionEdits.Span span(ReconstructionEdits.Intent edit,
                                                  Map<String, ReconstructionTrace.Row> before,
                                                  Map<String, ReconstructionTrace.Row> after) {
        if (edit.span() != null) return edit.span();
        var range = edit.explicitSpan();
        var ids = edit.kind() == ReconstructionEdits.Kind.insertNote ? List.of(edit.insertedId()) : edit.noteIds();
        for (var notes : List.of(before, after)) for (String id : ids) {
            var row = notes.get(id);
            if (row == null) continue;
            var time = row.score() == null ? row.timing() : row.score().timing();
            var gate = new ReconstructionEdits.Span(time.sourceStart(), Math.max(time.sourceEnd(), time.sourceStart() + 1e-6));
            range = gate.union(range);
        }
        return range;
    }
}
