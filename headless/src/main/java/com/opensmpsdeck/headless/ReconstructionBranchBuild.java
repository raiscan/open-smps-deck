package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/** Materialize a canonical work directory once for each parent and journal content. */
final class ReconstructionBranchBuild {
    private static final List<String> FILES;

    static {
        var files = new ArrayList<>(ReconstructionReview.OUTPUTS);
        files.addAll(List.of(ReconstructionSections.FILE, ReconstructionVoicePlan.JOURNAL, "roleplan.json"));
        FILES = List.copyOf(files);
    }

    private ReconstructionBranchBuild() {}

    static Path build(Path work, ReconstructionReview.Revision expected, JsonArray journal,
                      ReconstructionStages.Progress progress) throws IOException {
        ReconstructionReview.requireRevision(work, expected);
        ReconstructionArtifacts.require(Files.isRegularFile(work.resolve("score.json"), NOFOLLOW_LINKS),
                "branch", "build the main reconstruction before evaluating branches");
        var entries = audible(expected.entries(), journal);
        var inputs = inputs(work);
        var key = ReconstructionArtifacts.fingerprint(List.of("edit-branch-build-v1",
                ReconstructionManifest.tool(), inputs, entries));
        var cache = ReconstructionBranches.directory(work, "builds");
        Files.createDirectories(cache);
        var target = cache.resolve(key);
        if (complete(target, key)) {
            check(work, expected, inputs);
            return target;
        }
        var stage = Files.createTempDirectory(cache, ".build-");
        try {
            for (var entry : inputs.entrySet()) {
                var relative = entry.getKey();
                var output = stage.resolve(relative);
                Files.createDirectories(output.getParent());
                Files.copy(work.resolve(relative), output);
                if (!entry.getValue().equals(ProjectFiles.fileRevision(output))) throw new ReconstructionReview.Changed();
            }
            // Roles may have pending edits in main, even when the branch changes only assembly.
            var from = ReconstructionReview.firstStage(stage, expected.entries(), entries);
            var start = from.equals("transcribe") ? from : "roles";
            ReconstructionReview.parse(entries).write(stage);
            var args = new ArrayList<>(List.of("--from", start, stage.toString(), "--keep-voicing",
                    "--overwrite-detached", "--policy-decisions"));
            var roles = ReconstructionTrace.optional(stage, "roles.json", ReconstructionRoles.Result.class);
            if (roles != null) args.addAll(List.of("--harmony-max-hz", Double.toString(roles.harmonyMaxHz())));
            ReconstructionStages.runForReview("run", args.toArray(String[]::new), (done, total, message) -> {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                progress.report(done, total, message);
            });
            check(work, expected, inputs);
            Files.writeString(stage.resolve(".branch-build"), key);
            try {
                Files.move(stage, target, ATOMIC_MOVE);
            } catch (IOException raced) {
                if (!complete(target, key)) throw raced;
            }
            return target;
        } finally {
            if (Files.exists(stage, NOFOLLOW_LINKS)) ReconstructionReview.removeTree(stage);
        }
    }

    private static boolean complete(Path path, String key) throws IOException {
        var marker = path.resolve(".branch-build");
        if (!Files.exists(path, NOFOLLOW_LINKS)) return false;
        ReconstructionArtifacts.require(Files.isDirectory(path, NOFOLLOW_LINKS)
                        && Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.readString(marker).equals(key),
                "branch cache", "invalid cache directory " + path);
        return true;
    }

    static JsonArray audible(JsonArray base, JsonArray journal) {
        var prior = ReconstructionBranches.indexed(base);
        var result = new JsonArray();
        for (var edit : ReconstructionReview.parse(journal).intents()) {
            boolean preview = edit.proposed() && !edit.json().equals(prior.get(edit.id()));
            result.add(preview ? edit.accept().json() : edit.json());
        }
        return result;
    }

    private static Map<String, String> inputs(Path work) throws IOException {
        var files = new LinkedHashMap<String, String>();
        for (String file : FILES) stamp(work, work.resolve(file), files);
        var snapshots = work.resolve("snapshots");
        if (Files.isDirectory(snapshots, NOFOLLOW_LINKS)) {
            try (var paths = Files.list(snapshots)) {
                for (var path : paths.sorted().toList()) stamp(work, path, files);
            }
        }
        return files;
    }

    private static void stamp(Path work, Path file, Map<String, String> files) throws IOException {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return;
        ReconstructionArtifacts.require(Files.isRegularFile(file, NOFOLLOW_LINKS),
                file.toString(), "expected a regular file without symlinks");
        files.put(work.relativize(file).toString(), ProjectFiles.fileRevision(file));
    }

    private static void check(Path work, ReconstructionReview.Revision revision, Map<String, String> inputs) throws IOException {
        ReconstructionReview.requireRevision(work, revision);
        if (!inputs.equals(inputs(work))) throw new ReconstructionReview.Changed();
    }
}
