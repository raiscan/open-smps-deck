package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role.bass;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionArpeggioRolesTest {
    @Test void keepsARegularAlreadyEstablishedBassRunOnOneRoleAndLane() {
        int[] contour = {43, 47, 50, 65, 52, 47, 43, 47, 52, 65, 52, 47, 43, 47, 50};
        var notes = new ArrayList<ReconstructionStages.Note>();
        for (int i = 0; i < contour.length; i++) {
            double start = i * .056;
            notes.add(new ReconstructionStages.Note("step" + i, 0, start, start + .054, contour[i], 73, "midi", 1));
        }
        var parts = List.of(new MidiScore.Part(0, "keys", 81, false));
        var bars = List.of(new ReconstructionStructure.Bar("B1", 0, 2, true));
        var structure = ReconstructionStructure.analyze(bars, notes, parts);
        var roles = ReconstructionRoles.assign(structure, parts, 7.5, null, null, null, 1);
        for (var row : roles.notes()) assertEquals(bass, row.role(), row.toString());
        for (int turn : List.of(3, 9))
            assertEquals("regular-monophonic-line", roles.notes().get(turn).reason());
        var allocation = ReconstructionAllocation.allocate(roles, parts, 120, false);
        assertTrue(allocation.notes().stream().allMatch(row -> "FM2".equals(row.lane())));
    }
}
