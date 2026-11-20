package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionRoles.Decision;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconstructionMelodyGapFillTest {
    @Test void promotesAConnectedCounterPhraseWithoutMovingItsAttacks() {
        var lead = decision("lead", 0, 0, .5, 73, Role.melody, null);
        var first = decision("first", 0, .51, .65, 72, Role.counter, null);
        var second = decision("second", 0, .66, .98, 73, Role.counter, null);
        var returnToLead = decision("return", 0, 1, 1.4, 72, Role.melody, null);
        var result = ReconstructionMelodyGapFill.promote(List.of(lead, first, second, returnToLead), (anchor, note) -> true);
        assertEquals(new Decision(first.note(), Role.melody, "melody-gapfill", null), result.get(1));
        assertEquals(new Decision(second.note(), Role.melody, "melody-gapfill", null), result.get(2));
        var diagnostic = ReconstructionMelodyGapFill.Stats.of(result);
        assertEquals(.46, diagnostic.beforeSeconds(), 1e-9);
        assertEquals(0, diagnostic.afterSeconds());
    }

    @Test void leavesExplicitRolesOtherPartsOverlapsAndDisconnectedPhrasesAlone() {
        var lead = decision("lead", 0, 0, .5, 73, Role.melody, null);
        var returnToLead = decision("return", 0, 1, 1.4, 72, Role.melody, null);
        for (var excluded : List.of(
                decision("planned", 0, .51, .99, 72, Role.counter, "roleplan#0"),
                decision("other", 1, .51, .99, 72, Role.counter, null),
                decision("overlap", 0, .49, .99, 72, Role.counter, null),
                decision("leap", 0, .51, .99, 48, Role.counter, null),
                decision("short", 0, .725, .775, 72, Role.counter, null))) {
            var notes = List.of(lead, excluded, returnToLead);
            assertEquals(notes, ReconstructionMelodyGapFill.promote(notes, (anchor, note) -> true));
        }
        var changedPart = decision("return", 1, 1, 1.4, 72, Role.melody, null);
        var counter = decision("counter", 0, .51, .99, 72, Role.counter, null);
        var notes = List.of(lead, counter, changedPart);
        assertEquals(notes, ReconstructionMelodyGapFill.promote(notes, (anchor, note) -> true));
    }

    private static Decision decision(String id, int part, double start, double end, int pitch, Role role, String reason) {
        var note = new ReconstructionStages.Note(id, part, start, end, pitch, 90, "midi", 1);
        return new Decision(note, role, reason, null);
    }
}
