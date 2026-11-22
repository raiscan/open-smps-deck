package com.opensmpsdeck.headless;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Stage;

/** Dry-run results live beside the journal and never enter the built edit results. */
public final class ReconstructionProposals {
    public static final String RESULTS = "proposal-results.json";
    private static final com.google.gson.Gson JSON =
            new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    public record Marker(String noteId, int part, int pitch, double start, double end,
                         List<ReconstructionInspection.BendPoint> bend) {}
    public record RoleChange(String noteId, String from, String to, boolean satisfied) {}
    public record Result(JsonObject edit, List<ReconstructionReview.EditResult> outcomes,
                         List<Marker> markers, List<RoleChange> roles) {
        public String id() { return edit.get("id").getAsString(); }
    }
    private record Report(int version, String journal, List<Result> proposals) {}

    private ReconstructionProposals() {}

    public static boolean isProposed(JsonElement entry) {
        return hasStatus(entry, "proposed");
    }

    public static boolean isInactive(JsonElement entry) {
        return isProposed(entry) || hasStatus(entry, "rejected");
    }

    private static boolean hasStatus(JsonElement entry, String status) {
        return entry.isJsonObject() && new com.google.gson.JsonPrimitive(status)
                .equals(entry.getAsJsonObject().get("status"));
    }

    static JsonObject proposed(JsonObject entry) {
        var proposal = entry.deepCopy();
        proposal.addProperty("status", "proposed");
        if (!proposal.has("author")) proposal.addProperty("author", "agent");
        return proposal;
    }

    static List<ReconstructionEdits.Intent> select(ReconstructionEdits journal, String id, String group) {
        return selectBatch(journal, id == null ? List.of() : List.of(id), group, null);
    }

    static List<ReconstructionEdits.Intent> selectBatch(ReconstructionEdits journal, List<String> ids,
                                                         String group, String op) {
        require(ids.isEmpty() || group == null && op == null, "proposals", "choose IDs or selectors");
        var wanted = new java.util.LinkedHashSet<>(ids);
        var result = journal.intents().stream().filter(ReconstructionEdits.Intent::proposed)
                .filter(edit -> wanted.isEmpty() || wanted.contains(edit.id()))
                .filter(edit -> group == null || edit.json().has("group")
                        && edit.json().get("group").getAsString().equals(group))
                .filter(edit -> op == null || edit.kind().name().equals(op)).toList();
        result.forEach(edit -> wanted.remove(edit.id()));
        require(wanted.isEmpty(), "proposals", "no pending proposal for " + wanted);
        return result;
    }

    static Stage from(ReconstructionEdits shadow, List<ReconstructionEdits.Intent> selected,
                      ReconstructionEdits.Journal prior) {
        if (!java.util.Objects.equals(prior.scoreRecipe(), shadow.scoreRecipe())) return Stage.TRANSCRIBE;
        var built = prior.all().stream().map(ReconstructionEdits.Outcome::id)
                .collect(java.util.stream.Collectors.toSet());
        return java.util.stream.Stream.concat(selected.stream(),
                        shadow.intents().stream().filter(edit -> !built.contains(edit.id())))
                .map(ReconstructionEdits.Intent::firstStage).min(java.util.Comparator.naturalOrder()).orElseThrow();
    }

    static ReconstructionEdits shadow(ReconstructionEdits journal, List<ReconstructionEdits.Intent> selected) {
        var ids = selected.stream().map(ReconstructionEdits.Intent::id).collect(java.util.stream.Collectors.toSet());
        return new ReconstructionEdits(journal.intents().stream()
                .filter(edit -> !edit.inactive() || ids.contains(edit.id()))
                .map(edit -> edit.proposed() ? edit.accept() : edit).toList());
    }

    public static List<Result> results(Path work) throws IOException {
        return results(work, ReconstructionEdits.read(work));
    }

    static List<Result> results(Path work, ReconstructionEdits journal) throws IOException {
        var selected = select(journal, null, null);
        if (selected.isEmpty()) return List.of();
        var file = work.resolve(RESULTS);
        if (Files.isRegularFile(file)) {
            var saved = JSON.fromJson(ReconstructionArtifacts.readTree(file), Report.class);
            if (saved != null && saved.version() == ReconstructionStages.VERSION && saved.proposals() != null
                    && java.util.Objects.equals(saved.journal(), fingerprint(work, journal))) return saved.proposals();
        }
        return resolve(work, journal, selected);
    }

    static void refresh(Path work, ReconstructionEdits journal) throws IOException {
        var selected = select(journal, null, null);
        var file = work.resolve(RESULTS);
        if (selected.isEmpty()) {
            Files.deleteIfExists(file);
        } else {
            ReconstructionArtifacts.write(file, new Report(ReconstructionStages.VERSION, fingerprint(work, journal),
                    resolve(work, journal, selected)));
        }
    }

    private static String fingerprint(Path work, ReconstructionEdits journal) throws IOException {
        var revision = ReconstructionReview.revision(work);
        return ReconstructionArtifacts.fingerprint(java.util.Arrays.asList(
                journal.intents().stream().map(ReconstructionEdits.Intent::json).toList(), revision.sections(), revision.voices()));
    }

    static List<Result> resolve(Path work, ReconstructionEdits journal, List<ReconstructionEdits.Intent> selected)
            throws IOException {
        var shadow = shadow(journal, selected);
        var prior = ReconstructionEdits.Journal.read(work);
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var resolutions = new ArrayList<ReconstructionEdits.Resolution>();
        var notes = new HashMap<String, ReconstructionStages.Note>();
        try {
            ReconstructionStages.previewEdits(work, from(shadow, selected, prior), shadow, prior, outcomes, resolutions, notes);
        } catch (IOException | IllegalArgumentException failure) {
            // A partial run may not have the artifacts that the proposal needs yet.
            return selected.stream().map(edit -> new Result(edit.json(), List.of(
                    new ReconstructionReview.EditResult("unresolved", failure.getMessage(), List.of())), List.of(), List.of())).toList();
        }
        var byId = new HashMap<String, List<ReconstructionEdits.Outcome>>();
        for (var outcome : outcomes) byId.computeIfAbsent(outcome.id(), ignored -> new ArrayList<>()).add(outcome);
        var rolesById = new HashMap<String, List<RoleChange>>();
        for (var resolution : resolutions) {
            if (resolution.incomingRole() == resolution.resultingRole() && !resolution.satisfied()) continue;
            rolesById.computeIfAbsent(resolution.editId(), ignored -> new ArrayList<>()).add(new RoleChange(
                    resolution.noteId(), role(resolution.incomingRole()), role(resolution.resultingRole()), resolution.satisfied()));
        }
        return selected.stream().map(edit -> {
            var matching = byId.getOrDefault(edit.id(), List.of());
            var details = matching.stream().map(outcome -> new ReconstructionReview.EditResult(
                    outcome.status().name(), outcome.detail(), outcome.noteIds(), outcome.layer())).toList();
            var markers = matching.stream().filter(outcome -> outcome.status() == ReconstructionEdits.Status.applied
                            || outcome.status() == ReconstructionEdits.Status.satisfied)
                    .flatMap(outcome -> outcome.noteIds().stream().map(id ->
                            notes.getOrDefault(id, outcome.original() != null && outcome.original().id().equals(id)
                                    ? outcome.original() : null)))
                    .filter(java.util.Objects::nonNull).map(ReconstructionProposals::marker).distinct().toList();
            return new Result(edit.json(), details.isEmpty()
                    ? List.of(new ReconstructionReview.EditResult("unresolved", "No matching edit outcome", List.of()))
                    : details, markers, rolesById.getOrDefault(edit.id(), List.of()));
        }).toList();
    }

    private static String role(ReconstructionRoles.Role role) {
        return role == null ? null : role.name();
    }

    private static Marker marker(ReconstructionStages.Note note) {
        var bend = note.bend() == null ? List.<ReconstructionInspection.BendPoint>of() : note.bend().points().stream()
                .map(point -> new ReconstructionInspection.BendPoint(point.time(), note.pitch() + point.cents() / 100)).toList();
        return new Marker(note.id(), note.part(), note.pitch(), note.start(), note.end(), bend);
    }

    static JsonObject listing(Path work, ReconstructionEdits journal, List<String> ids, String group,
                              String op, boolean showDecided) throws IOException {
        var selected = selectBatch(journal, showDecided ? List.of() : ids, group, op).stream()
                .map(ReconstructionEdits.Intent::id).collect(java.util.stream.Collectors.toSet());
        var object = new JsonObject();
        object.add("proposals", JSON.toJsonTree(results(work, journal).stream()
                .filter(row -> selected.contains(row.id()) && (ids.isEmpty() || ids.contains(row.id()))).toList()));
        if (showDecided) object.add("decided", JSON.toJsonTree(journal.intents().stream()
                .filter(edit -> !edit.proposed() && edit.json().has("status"))
                .filter(edit -> ids.isEmpty() || ids.contains(edit.id()))
                .filter(edit -> group == null || edit.json().has("group")
                        && edit.json().get("group").getAsString().equals(group))
                .filter(edit -> op == null || edit.kind().name().equals(op))
                .map(ReconstructionEdits.Intent::json).toList()));
        return object;
    }

    public static List<String> idsFile(Path file) throws IOException {
        String text = Files.readString(file).strip();
        require(!text.isEmpty(), "file", "expected at least one proposal ID");
        if (text.startsWith("[")) return com.google.gson.JsonParser.parseString(text)
                .getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList();
        return List.of(text.split("\\s+"));
    }

    static void cli(String[] args, java.io.PrintStream out) throws IOException {
        require(args.length >= 2, "proposals", "expected list|accept|reject|revise WORK_DIR [IDs | --file FILE | --group GROUP | --op OP]");
        var work = Path.of(args[1]);
        if (args[0].equals("revise")) {
            require(args.length == 5 && args[3].equals("--file"), "revise", "expected ID --file INTENT.json");
            out.println(JSON.toJson(ReconstructionEditCommand.revise(work, args[2],
                    ReconstructionArtifacts.readTree(Path.of(args[4])).getAsJsonObject(), null)));
            return;
        }
        var ids = new ArrayList<String>();
        String group = null, op = null;
        boolean showDecided = false, verbose = false;
        for (int i = 2; i < args.length; i++) {
            var option = args[i];
            switch (option) {
                case "--show-decided" -> showDecided = true;
                case "--verbose" -> verbose = true;
                case "--id", "--file", "--group", "--op" -> {
                    require(i + 1 < args.length, option, "expected a value");
                    String value = args[++i];
                    switch (option) {
                        case "--id" -> ids.add(value);
                        case "--file" -> ids.addAll(idsFile(Path.of(value)));
                        case "--group" -> group = value;
                        case "--op" -> op = value;
                    }
                }
                default -> {
                    require(!option.startsWith("--"), "proposals", "unknown option " + option);
                    ids.add(option);
                }
            }
        }
        out.println(JSON.toJson(ReconstructionEditCommand.proposalBatch(work, args[0], ids, group, op,
                showDecided, verbose)));
    }

    record Selection(String id, String group) {}

    static Selection selection(List<String> args) {
        if (args.isEmpty()) return new Selection(null, null);
        if (args.size() == 1 && !args.getFirst().startsWith("--")) return new Selection(args.getFirst(), null);
        if (args.size() == 2) {
            if (args.getFirst().equals("--id")) return new Selection(args.getLast(), null);
            if (args.getFirst().equals("--group")) return new Selection(null, args.getLast());
        }
        throw new IllegalArgumentException("Expected an ID or --group GROUP");
    }
}
