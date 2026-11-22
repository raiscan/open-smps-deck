package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.ReconstructionAllocation.Outcome.*;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionRecoveryTest {
    @Test void usesIdleRoleLanesForFullGatesOnly() {
        var notes = List.of(note("one", 60, 0, 60, Role.harmony),
                note("two", 64, 0, 60, Role.harmony), note("three", 67, 0, 60, Role.harmony),
                note("extra", 72, 0, 30, Role.harmony), note("lead", 80, 15, 60, Role.melody),
                note("bass", 35, 30, 60, Role.bass));
        var entries = allocate(notes, true);
        assertEquals(dropped, allocate(notes, false).get("extra").outcome());
        assertEquals("FM2", entries.get("extra").lane());
        assertEquals(30, entries.get("extra").endTick());
        assertEquals("FM1", entries.get("lead").lane());
        assertEquals("FM2", entries.get("bass").lane());
    }

    @Test void restoresAReleaseOverlapWithoutShorteningTheOtherNote() {
        var notes = List.of(note("one", 60, 0, 32, Role.harmony),
                note("two", 64, 0, 60, Role.harmony), note("three", 67, 0, 60, Role.harmony),
                note("next", 61, 30, 60, Role.counter));
        var entries = allocate(notes, true);
        assertEquals(shortened, allocate(notes, false).get("one").outcome());
        assertEquals(kept, entries.get("one").outcome());
        assertEquals(32, entries.get("one").endTick());
        assertEquals(30, entries.get("next").startTick());
        assertEquals(60, entries.get("next").endTick());
        assertNotEquals(entries.get("one").lane(), entries.get("next").lane());
    }

    @Test void letsAQuietBackingAttackYieldTwoTicksWhenEveryLaneIsOccupied() {
        var notes = List.of(note("clear", 60, 0, 32, Role.harmony),
                note("two", 64, 0, 60, Role.harmony), note("three", 67, 0, 60, Role.harmony),
                note("next", 61, 30, 60, Role.harmony), note("lead", 80, 0, 60, Role.melody),
                note("bass", 35, 0, 60, Role.bass));
        var entries = allocate(notes, true, Map.of("clear", .04, "next", .005));
        assertEquals(32, entries.get("clear").endTick());
        assertEquals(32, entries.get("next").startTick());
        assertEquals(60, entries.get("next").endTick());
        assertEquals("yielded-to-stronger-release", entries.get("next").reason());
        assertEquals(30, allocate(notes, true, Map.of("clear", .019, "next", .005)).get("next").startTick());
    }

    @Test void labelsRedundantUnisonsAndTinyDroppedGates() {
        var notes = List.of(note("one", 60, 0, 60, Role.harmony),
                note("two", 64, 0, 60, Role.harmony), note("three", 67, 0, 60, Role.harmony),
                note("duplicate", 60, 1, 30, Role.harmony),
                note("tiny", 69, 2, 3, Role.harmony));
        var entries = allocate(notes, true);
        assertEquals(dropped, entries.get("duplicate").outcome());
        assertEquals("covered-source-unison", entries.get("duplicate").reason());
        assertEquals("one", entries.get("duplicate").competitorNoteId());
        assertEquals("too-short-for-overflow", entries.get("tiny").reason());
    }

    private static ReconstructionRoles.Decision note(String id, int pitch, int start, int end, Role role) {
        var note = new ReconstructionStages.Note(id, 0, start / 60.0, end / 60.0, pitch, 96, "audio-transcribed", 1);
        return new ReconstructionRoles.Decision(note, role, null, null);
    }

    private static Map<String, ReconstructionAllocation.Entry> allocate(List<ReconstructionRoles.Decision> notes,
                                                                        boolean recover) {
        return allocate(notes, recover, Map.of());
    }

    private static Map<String, ReconstructionAllocation.Entry> allocate(List<ReconstructionRoles.Decision> notes,
                                                                        boolean recover, Map<String, Double> salience) {
        return ReconstructionAllocation.allocateWithAffinity(new ReconstructionRoles.Result(1, 0, notes, null),
                List.of(new MidiScore.Part(0, "fixture", 4, false)), 60, true, true,
                Map.of(), Map.of(), Map.of(), Map.of(), null, salience, false, TargetProfile.S3K,
                ReconstructionArrangement.Allocation.NONE, Map.of(), recover)
                .notes().stream().collect(Collectors.toMap(entry -> entry.note().id(), entry -> entry));
    }
}
