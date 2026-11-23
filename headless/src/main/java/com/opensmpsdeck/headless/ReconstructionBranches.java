package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Journal-backed branches and their isolated canonical builds. */
public final class ReconstructionBranches {
    private static final Gson JSON = new Gson();
    private static final ReconstructionStages.Progress QUIET = (a, b, c) -> {};
    private static final Set<String> BRANCH_FIELDS =
            Set.of("version", "name", "author", "createdAt", "parent", "groups", "edits", "removedEditIds", "order");

    public record Parent(String revision, int editCount) {}
    public record Group(String id, String label, String rationale) {}
    public record Branch(int version, String name, String author, String createdAt, Parent parent, List<Group> groups,
                         List<JsonObject> edits, @Nullable List<String> removedEditIds, @Nullable List<String> order) {}
    public record Selection(Set<Integer> parts, Double start, Double end, Set<String> groups, Set<String> edits) {
        public Selection {
            parts = Set.copyOf(parts);
            groups = Set.copyOf(groups);
            edits = Set.copyOf(edits);
            new ReconstructionTrace.Span(start, end);
            require(parts.stream().allMatch(part -> part >= 0), "parts", "expected nonnegative part IDs");
        }
        public static Selection all() { return new Selection(Set.of(), null, null, Set.of(), Set.of()); }
    }

    public record State(Branch branch, Parent main, boolean stale) {}
    public record Range(double start, double end) {
        boolean overlaps(Range other) { return start < other.end && other.start < end; }
    }
    public record EditRef(String branch, String editId) {}
    public record DirectConflict(EditRef left, EditRef right, String reason, List<String> noteIds,
                                 Range leftSpan, Range rightSpan) {}
    public record IndirectConflict(String noteId, Range span, boolean allocation, boolean pcm, List<EditRef> edits) {}
    public record SourceSelection(String branch, Selection selection, Set<String> excludedEditIds) {
        public SourceSelection {
            name(branch);
            java.util.Objects.requireNonNull(selection);
            excludedEditIds = Set.copyOf(excludedEditIds);
        }
    }
    public record StitchPlan(Parent parent, ReconstructionReview.Revision mainRevision, JsonArray journal,
                             Path build, Path mainBuild, List<Group> groups, int selectedEdits,
                             List<DirectConflict> directConflicts, List<IndirectConflict> indirectConflicts,
                             Map<String, List<ReconstructionReview.EditResult>> outcomes) {
        public StitchPlan { journal = journal.deepCopy(); }
        @Override public JsonArray journal() { return journal.deepCopy(); }
    }

    private record MainState(Path work, ReconstructionReview.Revision journal, Parent parent) {
        JsonArray entries() { return journal.entries(); }
        void check() throws IOException {
            if (!parent.equals(main(work).parent)) throw new ReconstructionReview.Changed();
        }
    }

    @FunctionalInterface
    private interface Mutation<T> { T run() throws IOException; }

    private ReconstructionBranches() {}

    private static MainState main(Path work) throws IOException {
        var journal = ReconstructionReview.revision(work);
        var fingerprint = ReconstructionNoteContext.revision(ReconstructionNoteContext.builtRevision(work), journal);
        var parent = new Parent(fingerprint, journal.entries().size());
        ReconstructionReview.requireRevision(work, journal);
        return new MainState(work, journal, parent);
    }

    static String name(String name) {
        require(name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                && !name.equalsIgnoreCase("main"), "branch", "expected a branch name of 1–64 letters, digits, dots, underscores or hyphens");
        return name;
    }

    static Path directory(Path work, String child) throws IOException {
        var edits = work.resolve("edits");
        var directory = edits.resolve(child);
        for (Path path : List.of(edits, directory))
            require(!Files.exists(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS),
                    path.toString(), "expected a directory without symlinks");
        return directory;
    }

    private static Path branchFile(Path work, String name) throws IOException {
        return directory(work, "branches").resolve(name(name) + ".json");
    }

    public static List<Branch> list(Path work) throws IOException {
        var directory = directory(work, "branches");
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return List.of();
        try (var files = Files.list(directory)) {
            var result = new ArrayList<Branch>();
            for (Path file : files.sorted().toList()) {
                String filename = file.getFileName().toString();
                if (filename.endsWith(".json") && !filename.startsWith("."))
                    result.add(show(work, filename.substring(0, filename.length() - 5)));
            }
            return List.copyOf(result);
        }
    }

    public static Branch show(Path work, String name) throws IOException {
        var file = branchFile(work, name);
        require(Files.isRegularFile(file, NOFOLLOW_LINKS), "branch", "unknown branch " + name);
        var tree = ReconstructionArtifacts.readTree(file);
        ReconstructionArtifacts.shape(file.toString(), Branch.class, tree);
        var branch = JSON.fromJson(tree, Branch.class);
        require(BRANCH_FIELDS.containsAll(tree.getAsJsonObject().keySet()) && branch.version == 1
                && name.equals(branch.name), "branch", "unsupported version or mismatched branch name");
        require(branch.parent.revision.matches("[0-9a-f]{64}") && branch.parent.editCount >= 0,
                "parent", "invalid revision or edit count");
        validate(branch);
        return branch;
    }

    private static void validate(Branch branch) {
        require(branch.author != null && !branch.author.isBlank(), "author", "expected a human or agent name");
        ReconstructionReview.parse(array(branch.edits));
        var ids = new LinkedHashSet<String>();
        for (var group : branch.groups)
            require(group.id != null && !group.id.isBlank() && ids.add(group.id)
                    && group.label != null && !group.label.isBlank() && group.rationale != null,
                    "groups", "expected unique group IDs, labels and rationale");
        if (branch.order != null)
            require(Set.copyOf(branch.order).size() == branch.order.size(), "order", "duplicate order ID");
        if (branch.removedEditIds != null)
            require(Set.copyOf(branch.removedEditIds).size() == branch.removedEditIds.size(),
                    "removedEditIds", "duplicate removal");
    }

    public static Branch create(Path work, String name, String author) throws IOException {
        return importEntries(work, name, author, null);
    }

    public static Branch importJournal(Path work, String name, String author, Path copy) throws IOException {
        return importEntries(work, name, author, ReconstructionReview.revision(copy.toRealPath()).entries());
    }

    private static Branch importEntries(Path work, String name, String author, JsonArray imported) throws IOException {
        var main = main(work.toRealPath());
        var branch = delta(name(name), author, Instant.now().toString(), main, List.of(),
                imported == null ? main.entries() : imported);
        return mutate(main.work, () -> {
            require(!Files.exists(branchFile(main.work, name), NOFOLLOW_LINKS), "branch", "branch already exists: " + name);
            main.check();
            saveParent(main);
            write(main.work, branch);
            return branch;
        });
    }

    public static void delete(Path work, String name) throws IOException {
        mutate(work.toRealPath(), () -> {
            show(work, name);
            Files.delete(branchFile(work, name));
            return null;
        });
    }

    /** Append or revise branch intents and replace any supplied group metadata. */
    public static Branch edit(Path work, String name, JsonArray edits, List<Group> groups, Set<String> remove)
            throws IOException {
        ReconstructionReview.parse(edits);
        var root = work.toRealPath();
        return mutate(root, () -> {
            var branch = show(root, name);
            var entries = indexed(array(branch.edits));
            remove.forEach(entries::remove);
            indexed(edits).forEach(entries::put);
            var metadata = new LinkedHashMap<String, Group>();
            branch.groups.forEach(group -> metadata.put(group.id, group));
            groups.forEach(group -> metadata.put(group.id, group));
            var updated = new Branch(1, name, branch.author, branch.createdAt, branch.parent,
                    List.copyOf(metadata.values()), List.copyOf(entries.values()), branch.removedEditIds, branch.order);
            write(root, updated);
            return updated;
        });
    }

    private static synchronized <T> T mutate(Path work, Mutation<T> change) throws IOException {
        var folder = directory(work, "branches");
        Files.createDirectories(folder);
        try (var channel = FileChannel.open(folder.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            return change.run();
        }
    }

    private static void write(Path work, Branch branch) throws IOException {
        validate(branch);
        ReconstructionArtifacts.write(branchFile(work, branch.name), branch);
    }

    private static void saveParent(MainState main) throws IOException {
        var folder = directory(main.work, "parents");
        Files.createDirectories(folder);
        var file = folder.resolve(main.parent.revision + ".json");
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
            ReconstructionArtifacts.write(file, Map.of("version", 1, "edits", main.entries()));
    }

    private static JsonArray parentEntries(Path work, Parent parent) throws IOException {
        var file = directory(work, "parents").resolve(parent.revision + ".json");
        require(Files.isRegularFile(file, NOFOLLOW_LINKS), "parent", "missing parent snapshot " + parent.revision);
        var entries = ReconstructionEdits.batch(ReconstructionArtifacts.readTree(file), "parent");
        require(entries.size() == parent.editCount, "parent", "parent snapshot edit count differs");
        return entries;
    }

    private static Branch delta(String name, String author, String createdAt, MainState main, List<Group> groups,
                                JsonArray result) {
        var old = indexed(main.entries());
        var updated = indexed(result);
        var edits = updated.entrySet().stream().filter(entry -> !entry.getValue().equals(old.get(entry.getKey())))
                .map(Map.Entry::getValue).toList();
        var removed = old.keySet().stream().filter(id -> !updated.containsKey(id)).toList();
        var order = new ArrayList<>(old.keySet());
        order.removeAll(removed);
        updated.keySet().stream().filter(id -> !old.containsKey(id)).forEach(order::add);
        return new Branch(1, name, author, createdAt, main.parent, groups, edits,
                removed.isEmpty() ? null : removed, order.equals(new ArrayList<>(updated.keySet())) ? null
                        : List.copyOf(updated.keySet()));
    }

    private static JsonArray apply(JsonArray base, Branch branch) {
        var entries = indexed(base);
        if (branch.removedEditIds != null) branch.removedEditIds.forEach(entries::remove);
        indexed(array(branch.edits)).forEach(entries::put);
        var result = new JsonArray();
        if (branch.order != null) for (var id : branch.order) {
            var edit = entries.remove(id);
            if (edit != null) result.add(edit);
        }
        entries.values().forEach(result::add);
        return result;
    }

    static JsonArray array(Iterable<JsonObject> entries) {
        var array = new JsonArray();
        entries.forEach(array::add);
        return array;
    }

    static Map<String, JsonObject> indexed(JsonArray entries) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (var edit : ReconstructionReview.parse(entries).intents()) result.put(edit.id(), edit.json());
        return result;
    }

    private static boolean stale(MainState main, Branch branch) throws IOException {
        return !branch.parent.equals(main.parent) && !parentEntries(main.work, branch.parent).equals(main.entries());
    }

    private static void current(MainState main, Branch branch) throws IOException {
        require(!stale(main, branch), "branch", branch.name + " is based on an older journal. Rebase it first.");
    }

    public static State state(Path work, String name) throws IOException {
        var main = main(work.toRealPath());
        var branch = show(main.work, name);
        return new State(branch, main.parent, stale(main, branch));
    }

    public static Parent mainParent(Path work) throws IOException {
        return main(work.toRealPath()).parent;
    }

    public static Path buildMain(Path work, ReconstructionStages.Progress progress) throws IOException {
        var main = main(work.toRealPath());
        return ReconstructionBranchBuild.build(main.work, main.journal, main.entries(), progress);
    }

    public static Path build(Path work, String name, ReconstructionStages.Progress progress) throws IOException {
        var main = main(work.toRealPath());
        var branch = show(main.work, name);
        current(main, branch);
        return ReconstructionBranchBuild.build(main.work, main.journal, apply(main.entries(), branch), progress);
    }

    public static JsonObject diff(Path work, String name, ReconstructionStages.Progress progress) throws IOException {
        var main = main(work.toRealPath());
        var branch = show(main.work, name);
        current(main, branch);
        var before = ReconstructionBranchBuild.build(main.work, main.journal, main.entries(), progress);
        var after = ReconstructionBranchBuild.build(main.work, main.journal, apply(main.entries(), branch), progress);
        main.check();
        return ReconstructionBranchDiff.diff(before, after, branch);
    }

    public static JsonObject rebase(Path work, String name, ReconstructionStages.Progress progress) throws IOException {
        var main = main(work.toRealPath());
        var branch = show(main.work, name);
        var old = parentEntries(main.work, branch.parent);
        var entries = apply(main.entries(), branch);
        var before = ReconstructionBranchBuild.build(main.work, main.journal, main.entries(), progress);
        var after = ReconstructionBranchBuild.build(main.work, main.journal, entries, progress);
        var incoming = ReconstructionBranchDiff.effects(branch.name, old, apply(old, branch), before, after);
        var upstream = ReconstructionBranchDiff.effects("main", old, main.entries(), before, before);
        var conflicts = ReconstructionBranchDiff.direct(upstream, incoming);
        var updated = delta(branch.name, branch.author, branch.createdAt, main, branch.groups, entries);
        if (conflicts.isEmpty()) mutate(main.work, () -> {
            main.check();
            checkBranch(main.work, branch);
            saveParent(main);
            write(main.work, updated);
            return null;
        });
        return JSON.toJsonTree(Map.of("rebased", conflicts.isEmpty(), "branch", conflicts.isEmpty() ? updated : branch,
                "directConflicts", conflicts, "outcomes", ReconstructionBranchDiff.outcomes(after, branch))).getAsJsonObject();
    }

    private record Candidate(StitchPlan plan, Branch branch, Branch destination, List<Branch> originals) {}

    /** Plan a stitch into main. Only the content-addressed build cache is written. */
    public static StitchPlan planStitch(Path work, List<SourceSelection> sources,
                                       ReconstructionStages.Progress progress) throws IOException {
        return plan(main(work.toRealPath()), sources, "main", progress).plan;
    }

    private static Candidate plan(MainState main, List<SourceSelection> sources, String target,
                                  ReconstructionStages.Progress progress) throws IOException {
        var names = sources.stream().map(SourceSelection::branch).toList();
        require(!names.isEmpty() && !names.contains(target), "merge", "expected sources other than the destination");
        boolean intoMain = target.equals("main");
        var destination = !intoMain && Files.exists(branchFile(main.work, target), NOFOLLOW_LINKS)
                ? show(main.work, target) : null;
        if (destination != null) current(main, destination);
        var base = main.entries();
        var before = ReconstructionBranchBuild.build(main.work, main.journal, base, progress);
        var combined = destination == null ? base : apply(base, destination);
        var groups = new LinkedHashMap<String, Group>();
        var selected = new ArrayList<ReconstructionBranchDiff.Effect>();
        var conflicts = new ArrayList<DirectConflict>();
        var originals = new ArrayList<Branch>();
        if (destination != null) {
            originals.add(destination);
            destination.groups.forEach(group -> groups.put(group.id, group));
            var output = ReconstructionBranchBuild.build(main.work, main.journal, combined, progress);
            selected.addAll(ReconstructionBranchDiff.effects(target, base, combined, before, output));
        }
        int count = 0;
        var seen = new LinkedHashSet<EditRef>();
        var sourceEffects = new LinkedHashMap<String, List<ReconstructionBranchDiff.Effect>>();
        for (var request : sources) {
            var source = request.branch();
            var selection = request.selection();
            var branch = originals.stream().filter(saved -> saved.name.equals(source)).findFirst().orElse(null);
            if (branch == null) {
                branch = show(main.work, source);
                originals.add(branch);
                current(main, branch);
                var sourceEntries = apply(base, branch);
                var output = ReconstructionBranchBuild.build(main.work, main.journal, sourceEntries, progress);
                sourceEffects.put(source, ReconstructionBranchDiff.effects(source, base, sourceEntries, before, output));
            }
            var effects = sourceEffects.get(source).stream()
                    .filter(effect -> effect.matches(selection) && !request.excludedEditIds().contains(effect.id()))
                    .filter(effect -> seen.add(effect.ref())).toList();
            conflicts.addAll(ReconstructionBranchDiff.direct(selected, effects));
            var entries = indexed(combined);
            for (var effect : effects) {
                if (selected.stream().anyMatch(previous -> previous.sameChange(effect))) continue;
                if (effect.after() == null) entries.remove(effect.id());
                else {
                    var edit = effect.after().deepCopy();
                    var provenance = new JsonObject();
                    provenance.addProperty("branch", source);
                    provenance.addProperty("author", branch.author);
                    if (edit.has("group")) {
                        var group = edit.get("group").getAsString();
                        provenance.addProperty("group", group);
                        for (var metadata : branch.groups) if (metadata.id.equals(group)) {
                            var previous = groups.get(group);
                            if (previous != null && !previous.equals(metadata)) {
                                String renamed = source + ":" + group;
                                var qualified = new Group(renamed, metadata.label, metadata.rationale);
                                groups.put(renamed, qualified);
                                edit.addProperty("group", renamed);
                            } else groups.put(group, metadata);
                        }
                    }
                    if (!edit.has("provenance")) edit.add("provenance", provenance);
                    entries.put(effect.id(), edit);
                }
                count++;
            }
            if (branch.order != null) order(entries, branch.order, effects);
            combined = array(entries.values());
            selected.addAll(effects);
        }
        combined = ReconstructionBranchBuild.audible(base, combined);
        var after = ReconstructionBranchBuild.build(main.work, main.journal, combined, progress);
        var indirect = ReconstructionBranchDiff.indirect(before, after, selected);
        var candidate = combined;
        var stitched = delta(intoMain ? "main" : name(target), destination == null ? "human" : destination.author,
                destination == null ? Instant.now().toString() : destination.createdAt, main,
                List.copyOf(groups.values()), candidate);
        main.check();
        for (var original : originals) checkBranch(main.work, original);
        var plan = new StitchPlan(main.parent, main.journal, candidate, after, before, stitched.groups, count,
                List.copyOf(conflicts), indirect, ReconstructionBranchDiff.outcomes(after, stitched));
        return new Candidate(plan, stitched, destination, List.copyOf(originals));
    }

    private static void order(Map<String, JsonObject> entries, List<String> sourceOrder,
                              List<ReconstructionBranchDiff.Effect> effects) {
        var moving = new LinkedHashSet<String>();
        for (var effect : effects) if (effect.ordered() || effect.before() == null) moving.add(effect.id());
        var order = new ArrayList<>(entries.keySet());
        order.removeAll(moving);
        for (int i = 0; i < sourceOrder.size(); i++) {
            var id = sourceOrder.get(i);
            if (!moving.contains(id) || !entries.containsKey(id)) continue;
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int previous = order.indexOf(sourceOrder.get(j));
                if (previous >= 0) {
                    at = previous + 1;
                    break;
                }
            }
            order.add(at, id);
        }
        var sorted = order.stream().map(entries::get).toList();
        entries.clear();
        for (var entry : sorted) entries.put(entry.get("id").getAsString(), entry);
    }

    public static JsonObject merge(Path work, List<String> sources, String target, Selection selection, boolean dryRun,
                                   boolean allowIndirect, ReconstructionStages.Progress progress) throws IOException {
        require(Set.copyOf(sources).size() == sources.size(), "merge", "expected distinct CLI source branches");
        var main = main(work.toRealPath());
        var candidate = plan(main, sources.stream().map(source -> new SourceSelection(source, selection, Set.of())).toList(),
                target, progress);
        var plan = candidate.plan;
        var destination = candidate.destination;
        boolean merged = !dryRun && plan.directConflicts.isEmpty() && (allowIndirect || plan.indirectConflicts.isEmpty())
                && plan.selectedEdits > 0;
        if (merged) mutate(main.work, () -> {
            main.check();
            for (var original : candidate.originals) checkBranch(main.work, original);
            if (target.equals("main")) ReconstructionReview.apply(main.work, main.journal, plan.journal, progress);
            else {
                if (destination == null && Files.exists(branchFile(main.work, target), NOFOLLOW_LINKS))
                    throw new FileAlreadyExistsException(target);
                saveParent(main);
                write(main.work, candidate.branch);
            }
            return null;
        });
        return JSON.toJsonTree(Map.of("merged", merged, "target", target, "selectedEdits", plan.selectedEdits,
                "directConflicts", plan.directConflicts, "indirectConflicts", plan.indirectConflicts, "groups", plan.groups,
                "outcomes", plan.outcomes, "workDir", plan.build.toString())).getAsJsonObject();
    }

    private static void checkBranch(Path work, Branch branch) throws IOException {
        if (!branch.equals(show(work, branch.name))) throw new ReconstructionReview.Changed();
    }

    public static Path build(Path work, String name) throws IOException { return build(work, name, QUIET); }
}
