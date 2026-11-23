package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionBranches.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionBranchesTest {
    private static final ReconstructionStages.Progress QUIET = (a, b, c) -> {};
    private static final String FIRST = "n-0-0-60";
    private static final String CHORD = "n-0-500000-64";
    private static final String LAST = "n-0-1250000-62";

    @Test void importsReorderedJournalAndReplaysInAnIsolatedCache(@TempDir Path dir) throws Exception {
        var work = fixture(dir);
        var first = velocity("first", FIRST, 45, "phrase");
        var later = velocity("later", LAST, 55, "phrase");
        var inserted = velocity("copy-only", FIRST, 67, "phrase");
        saveJournal(work, first, later);
        var parent = mainParent(work);
        var copy = Files.createDirectory(dir.resolve("copy"));
        saveJournal(copy, later, inserted, first);
        var branch = importJournal(work, "copy", "agent fixture", copy);
        assertEquals(parent, branch.parent());
        assertEquals(List.of(inserted), branch.edits());
        assertEquals(List.of("later", "copy-only", "first"), branch.order());

        var built = Path.of(json(cli("reconstruct", "run", work.toString(), "--branch", "copy"))
                .get("workDir").getAsString());
        assertEquals(built, build(work, "copy"));
        assertEquals(array(List.of(later, inserted, first)), ReconstructionReview.revision(built).entries());
        assertEquals(array(List.of(first, later)), ReconstructionReview.revision(work).entries());
        assertEquals(90, ReconstructionTrace.read(work).get(FIRST).score().note().velocity());
        assertEquals(45, ReconstructionTrace.read(built).get(FIRST).score().note().velocity());
        assertEquals(45, ReconstructionTrace.read(buildMain(work, QUIET)).get(FIRST).score().note().velocity());
        assertEquals(parent, mainParent(work));
    }

    @Test void rebaseReportsAnOrphanAndUpdatesTheParent(@TempDir Path dir) throws Exception {
        var work = fixture(dir);
        create(work, "proposal", "agent fixture");
        edit(work, "proposal", array(List.of(velocity("detail", "n-0-250000-75", 50, "phrase"))), List.of(), Set.of());
        var original = show(work, "proposal").parent();
        saveJournal(work, velocity("unrelated", LAST, 40, "other"));
        assertTrue(state(work, "proposal").stale());
        var result = rebase(work, "proposal", QUIET);
        assertTrue(result.get("rebased").getAsBoolean(), result.toString());
        assertNotEquals(original, show(work, "proposal").parent());
        assertFalse(state(work, "proposal").stale());
        assertEquals("orphaned", result.getAsJsonObject("outcomes").getAsJsonArray("detail")
                .get(0).getAsJsonObject().get("status").getAsString());
    }

    @Test void rebaseKeepsItsParentOnDirectConflict(@TempDir Path dir) throws Exception {
        var work = fixture(dir);
        var parent = create(work, "candidate", "agent fixture").parent();
        edit(work, "candidate", array(List.of(velocity("wanted", FIRST, 50, "phrase"))), List.of(), Set.of());
        saveJournal(work, velocity("other", FIRST, 45, "phrase"));
        var result = rebase(work, "candidate", QUIET);
        assertFalse(result.get("rebased").getAsBoolean());
        var conflict = result.getAsJsonArray("directConflicts").get(0).getAsJsonObject();
        assertEquals("noteId", conflict.get("reason").getAsString());
        assertEquals("other", conflict.getAsJsonObject("left").get("editId").getAsString());
        assertEquals(parent, show(work, "candidate").parent());
    }

    @Test void plansRepeatedSelectionsAndCanExcludeAConflictingEdit(@TempDir Path dir) throws Exception {
        var work = fixture(dir);
        create(work, "left", "agent left");
        create(work, "right", "agent right");
        var metadata = new Group("opening", "Opening", "Keep the opening even");
        edit(work, "left", array(List.of(velocity("opening-level", FIRST, 50, "opening"),
                velocity("ending-level", LAST, 45, "ending"))), List.of(metadata), Set.of());
        edit(work, "right", array(List.of(velocity("other-level", FIRST, 65, "opening"))), List.of(), Set.of());

        var opening = new Selection(Set.of(0), 0.0, 0.25, Set.of("opening"), Set.of());
        var selection = new SourceSelection("left", opening, Set.of());
        var ending = new SourceSelection("left", new Selection(Set.of(0), 1.2, 1.5, Set.of(), Set.of("ending-level")), Set.of());
        var right = new SourceSelection("right", Selection.all(), Set.of());
        var plan = planStitch(work, List.of(selection, ending, selection, right), QUIET);
        assertEquals(3, plan.selectedEdits());
        assertEquals(1, plan.directConflicts().size());
        assertEquals(new EditRef("left", "opening-level"), plan.directConflicts().getFirst().left());
        assertEquals(new EditRef("right", "other-level"), plan.directConflicts().getFirst().right());
        assertEquals(List.of(FIRST), plan.directConflicts().getFirst().noteIds());

        var resolved = planStitch(work, List.of(selection, ending,
                new SourceSelection("right", Selection.all(), Set.of("other-level"))), QUIET);
        assertTrue(resolved.directConflicts().isEmpty());
        assertEquals(List.of(metadata), resolved.groups());
        assertEquals(array(List.of(withSource(velocity("opening-level", FIRST, 50, "opening"), "opening"),
                withSource(velocity("ending-level", LAST, 45, "ending"), "ending"))), resolved.journal());
        assertTrue(Files.isRegularFile(resolved.build().resolve("render.wav")));
        assertTrue(ReconstructionReview.revision(work).entries().isEmpty());
        assertEquals(2, list(work).size());

        var merged = json(cli("reconstruct", "branch", "merge", work.toString(), "left", "--into", "picked",
                "--parts", "0", "--span", "0:0.25", "--groups", "opening", "--allow-indirect"));
        assertTrue(merged.get("merged").getAsBoolean(), merged.toString());
        assertEquals(List.of(resolved.journal().get(0).getAsJsonObject()), show(work, "picked").edits());

        var accepted = merge(work, List.of("picked"), "main", Selection.all(), false, true, QUIET);
        assertTrue(accepted.get("merged").getAsBoolean(), accepted.toString());
        assertEquals(50, ReconstructionTrace.read(work).get(FIRST).score().note().velocity());
        var entry = ReconstructionReview.revision(work).entries().get(0).getAsJsonObject();
        assertEquals("left", entry.getAsJsonObject("provenance").get("branch").getAsString());
    }

    @Test void reportsUntouchedAllocationChangesInDryRun(@TempDir Path dir) throws Exception {
        var work = fixture(dir);
        var occupant = ReconstructionTrace.read(buildMain(work, QUIET)).get(CHORD).allocation().lane();
        assertNotNull(occupant);
        create(work, "left", "agent left");
        create(work, "right", "agent right");
        var pin = json("""
                {"id":"voice","op":"insertNote","reason":"competing fixture gate","part":0,"start":0.49,"end":0.81,
                 "pitch":76,"velocity":80,"role":"melody","lane":"%s"}
                """.formatted(occupant));
        edit(work, "left", array(List.of(pin)), List.of(), Set.of());
        edit(work, "right", array(List.of(velocity("separate", LAST, 40, "ending"))), List.of(), Set.of());
        var plan = planStitch(work, List.of(new SourceSelection("left", Selection.all(), Set.of()),
                new SourceSelection("right", Selection.all(), Set.of())), QUIET);
        assertTrue(plan.directConflicts().isEmpty(), plan.directConflicts().toString());
        assertTrue(plan.indirectConflicts().stream().anyMatch(change -> change.noteId().equals(CHORD)
                && change.allocation()), plan.indirectConflicts().toString());
        var report = json(cli("reconstruct", "branch", "merge", work.toString(), "left", "right", "--into", "main",
                "--dry-run"));
        assertEquals(2, report.getAsJsonArray("indirectCandidates").size());
        assertTrue(report.getAsJsonArray("indirectConflicts").asList().stream().map(element -> element.getAsJsonObject())
                .anyMatch(change -> change.get("noteId").getAsString().equals(CHORD)
                        && change.get("allocation").getAsBoolean() && !change.has("edits")));
    }

    @Test void selectsDeletionOfSourceNoteByItsOriginalSpan(@TempDir Path dir) throws Exception {
        var work = fixture(dir);
        create(work, "cut", "agent fixture");
        var deletion = json("""
                {"id":"cut-opening","op":"deleteNote","reason":"remove the opening attack","noteId":"%s"}
                """.formatted(FIRST));
        edit(work, "cut", array(List.of(deletion)), List.of(), Set.of());

        var selection = new Selection(Set.of(0), 0.0, 0.25, Set.of(), Set.of());
        var plan = planStitch(work, List.of(new SourceSelection("cut", selection, Set.of())), QUIET);
        assertEquals(1, plan.selectedEdits());
        assertTrue(plan.directConflicts().isEmpty());
        var deleted = ReconstructionTrace.read(plan.build()).get(FIRST);
        assertNotNull(deleted, "The source note remains in the trace after deletion");
        assertNull(deleted.effectiveTiming());
    }

    @Test void cancelledAuditionBuildDiscardsItsStageAndCanBeRetried(@TempDir Path dir) throws Exception {
        var work = fixture(dir);
        create(work, "candidate", "agent fixture");
        edit(work, "candidate", array(List.of(velocity("level", FIRST, 45, "phrase"))), List.of(), Set.of());
        var cache = work.resolve("edits/builds");
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () ->
                    build(work, "candidate", (done, total, message) -> {
                        // Interrupt after the branch has written artifacts, before it can publish.
                        if (done == total) Thread.currentThread().interrupt();
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        try (var entries = Files.list(cache)) {
            assertEquals(List.of(), entries.toList());
        }
        var rebuilt = build(work, "candidate");
        assertTrue(Files.isRegularFile(rebuilt.resolve("render.wav")));
        assertEquals(45, ReconstructionTrace.read(rebuilt).get(FIRST).score().note().velocity());
        assertEquals(rebuilt, build(work, "candidate"));
        try (var entries = Files.list(cache)) {
            assertEquals(List.of(rebuilt), entries.toList());
        }
    }

    private static JsonObject withSource(JsonObject edit, String group) {
        edit.add("provenance", json("""
                {"branch":"left","author":"agent left","group":"%s"}
                """.formatted(group)));
        return edit;
    }

    private static JsonObject velocity(String id, String note, int value, String group) {
        return json("""
                {"id":"%s","op":"setVelocity","reason":"fixture level","noteIds":["%s"],"velocity":%d,"group":"%s"}
                """.formatted(id, note, value, group));
    }

    private static JsonObject json(String input) { return JsonParser.parseString(input).getAsJsonObject(); }

    private static void saveJournal(Path work, JsonObject... edits) throws Exception {
        ReconstructionArtifacts.write(work.resolve("edits.json"), Map.of("version", 1, "edits", List.of(edits)));
    }

    private static Path fixture(Path dir) throws Exception {
        var midi = dir.resolve("fixture.mid");
        var wav = dir.resolve("mix.wav");
        var catalogue = dir.resolve("catalogue.json");
        var work = dir.resolve("work");
        ReconstructionEditsTest.writeMidi(midi, true);
        ReconstructionEditsTest.writeAudio(catalogue, wav, true);
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(),
                "--seconds", "2", "--lane-policy-preset", "off");
        return work;
    }

    private static String cli(String... args) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(stdout); var err = new PrintStream(stderr)) {
            assertEquals(0, Main.run(args, out, err), stderr.toString());
        }
        return stdout.toString();
    }
}
