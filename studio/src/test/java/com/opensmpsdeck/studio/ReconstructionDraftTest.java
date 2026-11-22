package com.opensmpsdeck.studio;

import com.google.gson.JsonArray;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.headless.ReconstructionLaneType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import com.opensmpsdeck.headless.ReconstructionSections.Level;
import com.opensmpsdeck.headless.ReconstructionSections.Author;
import com.opensmpsdeck.headless.ReconstructionSections.Section;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionDraftTest {
    @TempDir Path work;

    @Test void transposeKeysHandleBothKeyboardsAndReserveOtherModifiers() {
        for (var code : List.of(KeyCode.PLUS, KeyCode.EQUALS, KeyCode.ADD)) {
            assertEquals(1, TransposeKeys.semitones(key(code, false, false)));
            assertEquals(12, TransposeKeys.semitones(key(code, true, false)));
        }
        for (var code : List.of(KeyCode.MINUS, KeyCode.SUBTRACT)) {
            assertEquals(-1, TransposeKeys.semitones(key(code, false, false)));
            assertEquals(-12, TransposeKeys.semitones(key(code, true, false)));
        }
        assertEquals(-12, TransposeKeys.semitones(key(KeyCode.UNDERSCORE, true, false)));
        assertEquals(0, TransposeKeys.semitones(key(KeyCode.EQUALS, false, true)));
        assertEquals(0, TransposeKeys.semitones(key(KeyCode.ENTER, false, false)));
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false);
    }

    @Test void mixedChipTransposeIsOneDraftStep() throws Exception {
        var draft = new ReconstructionDraft(new JsonArray());
        var fm = ReconstructionDraft.transpose(List.of("lead", "answer"), 12, ReconstructionLaneType.FM);
        var psg = ReconstructionDraft.transpose(List.of("pad"), 12, ReconstructionLaneType.PSG);
        draft.change(entries -> { entries.add(fm); entries.add(psg); });
        assertEquals("transcribe", ReconstructionReview.firstStage(work, new JsonArray(), draft.entries()));
        assertNull(ReconstructionReview.validationIssue(fm));
        assertNull(ReconstructionReview.validationIssue(psg));
        draft.undo(false);
        assertTrue(draft.entries().isEmpty());
        draft.undo(true);
        assertEquals(List.of(fm, psg), draft.entries().asList());
    }

    @Test void insertionIsAnUndoableHumanIntentUntilApply() {
        var draft = new ReconstructionDraft(new JsonArray());
        var edit = ReconstructionDraft.insert(2, 1.5, 2.0, 60);
        draft.add(edit);
        assertFalse(Files.exists(work.resolve("edits.json")));
        assertEquals("human", draft.entries().get(0).getAsJsonObject().get("author").getAsString());
        assertEquals(1.5, edit.get("start").getAsDouble());
        assertEquals(2, edit.get("part").getAsInt());
        assertTrue(draft.pending());
        draft.undo(false);
        assertFalse(draft.pending());
        draft.undo(true);
        assertEquals(edit, draft.entries().get(0));
    }

    @Test void choosesEarliestStageIncludingRemovals() throws Exception {
        var empty = new JsonArray();
        var roles = new JsonArray();
        roles.add(ReconstructionDraft.target("n1", "setRole", "role", "drop", "Delete generated note"));
        assertEquals("roles", ReconstructionReview.firstStage(work, empty, roles));
        var score = roles.deepCopy();
        score.add(ReconstructionDraft.insert(0, 0, .5, 60));
        assertEquals("transcribe", ReconstructionReview.firstStage(work, roles, score));
        assertEquals("transcribe", ReconstructionReview.firstStage(work, score, roles));
    }

    @Test void cleanAdoptionAndDirtyConflictPreserveLocalIntent() throws Exception {
        var draft = new ReconstructionDraft(new JsonArray());
        var agent = new JsonArray();
        agent.add(ReconstructionDraft.insert(0, 0, .2, 60));
        assertTrue(draft.adopt(agent, false));
        draft.add(ReconstructionDraft.insert(0, .5, .7, 61));
        var pending = draft.entries();
        assertFalse(draft.adopt(agent, true));
        assertFalse(draft.adopt(new JsonArray(), false));
        assertEquals(pending, draft.entries());
        draft.discard(agent);
        assertFalse(draft.pending());
        assertEquals(agent, draft.entries());

        var expected = ReconstructionReview.revision(work);
        Files.writeString(work.resolve("sections.json"), "{}");
        assertThrows(ReconstructionReview.Changed.class,
                () -> ReconstructionReview.apply(work, expected, agent, (a, b, c) -> {}));
        assertFalse(Files.exists(work.resolve("edits.json")));
    }

    @Test void sharedSectionBoundaryMovesBothNeighborsInOneMutation() {
        var left = section("left", 0, 2);
        var right = section("right", 2, 4);
        var mutation = SectionEdits.resize(List.of(left, right), right, false, 2.5);
        assertEquals("set", mutation.action());
        assertEquals(2, mutation.entries().size());
        var first = mutation.entries().get(0).getAsJsonObject();
        var second = mutation.entries().get(1).getAsJsonObject();
        assertEquals("right", first.get("id").getAsString());
        assertEquals(2.5, first.get("sourceStart").getAsDouble());
        assertEquals("left", second.get("id").getAsString());
        assertEquals(2.5, second.get("sourceEnd").getAsDouble());
        assertEquals("human", first.get("author").getAsString());
    }

    private static Section section(String id, double start, double end) {
        return new Section(id, Level.phrase, id, Author.automatic, "fixture", start, end, start, end,
                1, 2, 1, 1, null);
    }
}
