package com.opensmpsdeck.headless;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import com.google.gson.JsonObject;

public class StageTiming {
    static void sub(String label, long start) {
        System.out.printf(Locale.ROOT, "%s %.3f ms%n", label, (System.nanoTime() - start) / 1e6);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected a pristine copy and a new scratch work directory");
        var pristine = Path.of(args[0]);
        var work = Files.createDirectory(Path.of(args[1]));
        for (int trial = 0; trial < 3; trial++) {
            // Restore the same input artifacts and affinity/voicing seeds before every pass.
            try (var paths = Files.walk(pristine)) {
                for (var path : paths.toList()) {
                    var dest = work.resolve(pristine.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(dest);
                    else Files.copy(path, dest, REPLACE_EXISTING);
                }
            }
            System.out.printf("TRIAL %d%n", trial);
            var roles = ReconstructionArtifacts.read(work.resolve("roles.json"), ReconstructionRoles.Result.class);
            long[] boundary = {System.nanoTime()};
            ReconstructionStages.runForReview("run", new String[]{"--from", "roles", work.toString(), "--harmony-max-hz",
                    Double.toString(roles.harmonyMaxHz())}, (completed, total, message) -> {
                sub(message, boundary[0]);
                boundary[0] = System.nanoTime();
            });
            sub("Post-run", boundary[0]);

            var score = ReconstructionStages.readScore(work.resolve("score.json"));
            var note = score.notes().stream().filter(n -> n.pitch() >= 45 && n.pitch() <= 75
                    && score.source().parts().stream().anyMatch(p -> p.id() == n.part() && !p.drums())).findFirst().orElseThrow();
            if (Files.exists(work.resolve("edits.json")))
                throw new IllegalArgumentException("Timing the single-intent path requires an initially empty journal");
            var edit = new JsonObject();
            edit.addProperty("id", "measure-set-note");
            edit.addProperty("op", "setNote");
            edit.addProperty("reason", "Timing probe");
            edit.addProperty("noteId", note.id());
            edit.addProperty("pitch", note.pitch() + 1);
            var parsed = new ReconstructionEdits(List.of(ReconstructionEdits.parse(edit, "measurement")));
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
                parsed.applyScore(score, outcomes);
                sub("In-memory edit apply", start);
                if (outcomes.stream().noneMatch(o -> o.status() == ReconstructionEdits.Status.applied))
                    throw new IllegalStateException("Score edit did not apply");
                start = System.nanoTime();
                var response = ReconstructionEditCommand.run(work, "add", edit, null, false, (a, b, c) -> {});
                sub("Journal validation and write", start);
                if (response.getAsJsonArray("outcomes").asList().stream()
                        .noneMatch(o -> o.getAsJsonObject().get("status").getAsString().equals("applied")))
                    throw new IllegalStateException(response.toString());
                Files.delete(work.resolve("edits.json"));
            }
        }
    }
}
