package com.opensmpsdeck.headless;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.opensmpsdeck.headless.ReconstructionRoles.Decision;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconstructionPolyphonicLeadTest {
    @Test
    void followsSeparateUpperAttacksOverRepeatedChordTones() {
        var chord = note("chord", 0, .8, 60);
        var first = note("first", .08, .32, 76);
        var doubling = note("doubling", .08, .32, 88);
        var second = note("second", .33, .58, 79);
        var third = note("third", .61, .85, 77);
        var notes = List.of(chord, first, doubling, second, third);

        assertEquals(Set.of("first", "second", "third"), ReconstructionPolyphonicLead.line(notes,
                note -> note == doubling ? 0 : .8));
    }

    @Test
    void repeatedBlockChordsAloneDoNotCreateALead() {
        var notes = List.of(note("root1", 0, .25, 60), note("top1", 0, .25, 67),
                note("root2", .26, .50, 60), note("top2", .26, .50, 67));

        assertEquals(Set.of(), ReconstructionPolyphonicLead.line(notes, note -> .8));
    }

    @Test
    void distinguishesASeparateAttackFromTheLowerNotesOvertone() {
        var lower = note("lower", .1, .65, 60);
        var coattack = note("partial", .1, .3, 72);
        var separate = note("separate", .33, .55, 72);
        int rate = 12_000;
        var samples = new float[rate];
        double angularFrequency = Math.TAU * ReconstructionLeadAudio.hz(lower.pitch());
        for (int i = 0; i < samples.length; i++) {
            double time = i / (double) rate;
            if (time < lower.start() || time >= lower.end()) continue;
            double upperAmplitude = time >= separate.start() && time < separate.end() ? .25 : .04;
            samples[i] = (float) (.1 * Math.sin(angularFrequency * time)
                    + upperAmplitude * Math.sin(2 * angularFrequency * time));
        }
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, rate));
        assertEquals(0, track.upperAttackLevel(coattack, List.of(lower)));
        assertTrue(track.upperAttackLevel(separate, List.of(lower)) > .42);
    }

    @Test
    void authoredRolesRemainAuthoritative() {
        var backing = new Decision(note("chord", 0, .8, 60), Role.melody, "edit:role", null);
        var upper = new Decision(note("upper", .1, .3, 76), Role.harmony, null, null);
        var roles = new ReconstructionRoles.Result(1, 0, List.of(backing, upper), null);

        assertSame(roles, ReconstructionPolyphonicLead.prioritize(roles, Set.of("upper"), Set.of("chord"), 1));
        assertSame(roles, ReconstructionPolyphonicLead.prioritize(roles, Set.of("upper"), Set.of("upper"), 1));

        var updated = ReconstructionPolyphonicLead.prioritize(roles, Set.of("upper"), Set.of(), 1);
        assertEquals(List.of(Role.harmony, Role.melody), updated.notes().stream().map(Decision::role).toList());
    }

    @Test
    void upperCounterDisplacesAChordBeforeItLosesItsOwnGate() {
        var notes = List.of(
                new Decision(note("bass", 0, .7, 35), Role.bass, null, null),
                new Decision(note("primary", 0, .7, 76), Role.melody, null, null),
                new Decision(note("chord1", 0, .7, 60), Role.harmony, null, null),
                new Decision(note("chord2", 0, .7, 64), Role.harmony, null, null),
                new Decision(note("chord3", 0, .7, 67), Role.harmony, null, null),
                new Decision(note("upper", .2, .6, 72), Role.counter, ReconstructionPolyphonicLead.REASON, null),
                new Decision(note("lateChord", .3, .6, 74), Role.harmony, null, null));
        var roles = new ReconstructionRoles.Result(1, 0, notes, null);
        var allocation = ReconstructionAllocation.allocate(roles, List.of(new MidiScore.Part(1, "synth", 80, false)),
                60, true);
        var upper = allocation.notes().stream().filter(row -> row.note().id().equals("upper")).findFirst().orElseThrow();
        var bass = allocation.notes().stream().filter(row -> row.role() == Role.bass).findFirst().orElseThrow();
        assertEquals(ReconstructionAllocation.Outcome.kept, upper.outcome());
        assertEquals(ReconstructionAllocation.Outcome.kept, bass.outcome());
        assertEquals("FM2", bass.lane());
    }

    private static Note note(String id, double start, double end, int pitch) {
        return new Note(id, 1, start, end, pitch, 96, "audio-transcribed", 1);
    }
}
