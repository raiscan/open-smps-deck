package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;

final class ReconstructionBranchCommand {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ReconstructionStages.Progress QUIET = (a, b, c) -> {};

    private ReconstructionBranchCommand() {}

    static void usage(PrintStream out) {
        out.println("       reconstruct branch list WORK");
        out.println("       reconstruct branch create|import WORK NAME [COPY] [--author NAME]");
        out.println("       reconstruct branch show|diff|rebase|delete WORK NAME [--json (diff)]");
        out.println("       reconstruct branch merge WORK SOURCE... --into TARGET [--dry-run] [--allow-indirect]");
        out.println("           [--parts IDS] [--span START:END] [--groups IDS] [--edits IDS...]");
        out.println("       reconstruct run|assemble|describe|trace WORK --branch NAME [inspection options]");
        out.println("       reconstruct render WORK --branch NAME OUT.wav");
        out.println("       reconstruct edit WORK --branch NAME list|remove ID|add JSON|add --file BATCH [--groups-file FILE]");
    }

    static void run(List<String> values, PrintStream out) throws IOException {
        var args = new ArrayList<>(values);
        require(args.size() >= 2, "branch", "expected ACTION WORK [NAME]");
        String action = args.removeFirst();
        Path work = Path.of(args.removeFirst());
        if (action.equals("merge")) {
            String target = option(args, "--into", true);
            var parts = csv(option(args, "--parts", false)).stream().map(Integer::valueOf).collect(Collectors.toSet());
            var span = option(args, "--span", false);
            var groups = csv(option(args, "--groups", false));
            boolean dryRun = flag(args, "--dry-run");
            boolean allowIndirect = flag(args, "--allow-indirect");
            var editIds = new java.util.LinkedHashSet<String>();
            int at = args.indexOf("--edits");
            if (at >= 0) {
                require(at + 1 < args.size() && !args.get(at + 1).startsWith("--"), "--edits", "expected IDs");
                args.remove(at);
                while (at < args.size() && !args.get(at).startsWith("--")) editIds.addAll(csv(args.remove(at)));
            }
            require(args.stream().noneMatch(value -> value.startsWith("--")), "merge", "unexpected option");
            Double start = null, end = null;
            if (span != null) {
                var bounds = span.split(":", -1);
                require(bounds.length == 2, "--span", "expected START:END in source seconds");
                start = Double.parseDouble(bounds[0]);
                end = Double.parseDouble(bounds[1]);
            }
            out.println(JSON.toJson(ReconstructionBranches.merge(work, args, target,
                    new ReconstructionBranches.Selection(parts, start, end, groups, editIds), dryRun, allowIndirect, QUIET)));
            return;
        }
        boolean json = action.equals("diff") && flag(args, "--json");
        var author = Set.of("create", "import").contains(action) ? option(args, "--author", false) : null;
        if (author == null) author = "human";
        int positional = action.equals("list") ? 0 : action.equals("import") ? 2 : 1;
        require(args.size() == positional && args.stream().noneMatch(value -> value.startsWith("--")),
                "branch " + action, "unexpected arguments");
        var name = args.isEmpty() ? null : args.getFirst();
        Object result = switch (action) {
            case "create" -> ReconstructionBranches.create(work, name, author);
            case "list" -> ReconstructionBranches.list(work);
            case "show" -> ReconstructionBranches.show(work, name);
            case "import" -> ReconstructionBranches.importJournal(work, name, author, Path.of(args.getLast()));
            case "diff" -> ReconstructionBranches.diff(work, name, QUIET);
            case "rebase" -> ReconstructionBranches.rebase(work, name, QUIET);
            case "delete" -> {
                ReconstructionBranches.delete(work, name);
                yield Map.of("deleted", name);
            }
            default -> throw new IllegalArgumentException("Unknown branch action: " + action);
        };
        if (action.equals("diff") && !json) textDiff((JsonObject) result, out);
        else out.println(JSON.toJson(result));
    }

    static void scoped(String command, List<String> values, PrintStream out, PrintStream err) throws IOException {
        var args = new ArrayList<>(values);
        var branch = option(args, "--branch", true);
        require(!args.isEmpty() && !args.getFirst().startsWith("--"), "--branch", "expected WORK before options");
        Path work = Path.of(args.removeFirst());
        switch (command) {
            case "run", "assemble" -> {
                require(args.isEmpty(), "--branch", "a branch build takes only WORK and NAME");
                var build = ReconstructionBranches.build(work, branch);
                out.println(JSON.toJson(Map.of("branch", branch, "workDir", build.toString())));
            }
            case "describe", "trace" -> {
                if (command.equals("describe") && args.contains("--out")) {
                    var output = args.indexOf("--out");
                    require(output + 1 < args.size(), "--out", "expected a destination");
                    ReconstructionBranches.requireExport(work, Path.of(args.get(output + 1)));
                }
                args.addFirst(ReconstructionBranches.build(work, branch).toString());
                args.addFirst(command);
                args.addFirst("reconstruct");
                ReconstructionCli.run(args.toArray(String[]::new), out, err);
            }
            case "render" -> {
                require(args.size() == 1, "render", "expected OUT.wav");
                var output = Path.of(args.getFirst()).toAbsolutePath().normalize();
                ReconstructionBranches.requireExport(work, output);
                Files.copy(ReconstructionBranches.build(work, branch).resolve("render.wav"), output);
                out.println(output);
            }
            case "edit" -> {
                require(!args.isEmpty(), "edit", "expected list, add or remove");
                var action = args.removeFirst();
                var groupsPath = option(args, "--groups-file", false);
                var groups = groupsPath == null ? List.<ReconstructionBranches.Group>of()
                        : groups(ReconstructionArtifacts.readTree(Path.of(groupsPath)));
                var result = switch (action) {
                    case "list" -> {
                        require(args.isEmpty() && groupsPath == null, "edit list", "unexpected arguments");
                        yield ReconstructionBranches.show(work, branch);
                    }
                    case "add" -> {
                        require(args.size() == 1 || args.size() == 2 && args.getFirst().equals("--file"),
                                "edit add", "expected JSON or --file BATCH");
                        var input = args.size() == 1 ? JsonParser.parseString(args.getFirst())
                                : ReconstructionArtifacts.readTree(Path.of(args.getLast()));
                        var edits = args.size() == 1 || input.isJsonArray() ? intents(input)
                                : ReconstructionEdits.batch(input, "batch");
                        yield ReconstructionBranches.edit(work, branch, edits, groups, Set.of());
                    }
                    case "remove" -> {
                        require(args.size() == 1 && groupsPath == null, "edit remove", "expected an edit ID");
                        yield ReconstructionBranches.edit(work, branch, new JsonArray(), groups, Set.of(args.getFirst()));
                    }
                    default -> throw new IllegalArgumentException("Expected branch edit list, add or remove");
                };
                out.println(JSON.toJson(result));
            }
            default -> throw new IllegalArgumentException("--branch is not supported by reconstruct " + command);
        }
    }

    static List<ReconstructionBranches.Group> groups(JsonElement input) {
        require(input.isJsonArray(), "groups", "expected an array of {id, label, rationale}");
        var result = new ArrayList<ReconstructionBranches.Group>();
        for (var group : input.getAsJsonArray()) {
            ReconstructionArtifacts.shape("groups", ReconstructionBranches.Group.class, group);
            result.add(JSON.fromJson(group, ReconstructionBranches.Group.class));
        }
        return List.copyOf(result);
    }

    private static JsonArray intents(JsonElement input) {
        if (input.isJsonArray()) return input.getAsJsonArray();
        var a = new JsonArray();
        a.add(input);
        return a;
    }

    private static void textDiff(JsonObject diff, PrintStream out) {
        var counts = new java.util.TreeMap<String, Integer>();
        for (var change : diff.getAsJsonArray("changes")) {
            var row = change.getAsJsonObject();
            counts.merge(row.get("change").getAsString(), 1, Integer::sum);
        }
        out.printf("%s  %d added, %d removed, %d changed, %d part voicing changes%n",
                diff.get("branch").getAsString(), counts.getOrDefault("added", 0),
                counts.getOrDefault("removed", 0), counts.getOrDefault("changed", 0),
                diff.getAsJsonArray("voicingChanges").size());
        for (var group : diff.getAsJsonArray("groups")) {
            var value = group.getAsJsonObject();
            out.printf("  %s  %s  %s%n", value.get("id").getAsString(), value.get("label").getAsString(),
                    value.get("rationale").getAsString());
        }
        var changes = diff.getAsJsonArray("changes");
        for (var element : changes.asList().stream().limit(24).toList()) {
            var change = element.getAsJsonObject();
            out.printf("  %-7s %s %s%n", change.get("change").getAsString(), change.get("id").getAsString(),
                    change.getAsJsonArray("fields"));
        }
        if (changes.size() > 24) out.printf("  … %d more changes in --json%n", changes.size() - 24);
    }

    private static String option(List<String> args, String key, boolean required) {
        int at = args.indexOf(key);
        if (at < 0) {
            require(!required, key, "required");
            return null;
        }
        args.remove(at);
        require(!args.contains(key) && at < args.size() && !args.get(at).startsWith("--"), key, "expected one value");
        return args.remove(at);
    }

    private static boolean flag(List<String> args, String key) {
        boolean found = args.remove(key);
        require(!args.contains(key), key, "duplicate flag");
        return found;
    }

    private static Set<String> csv(String value) {
        return value == null ? Set.of() : Set.copyOf(ReconstructionView.csv(value));
    }
}
