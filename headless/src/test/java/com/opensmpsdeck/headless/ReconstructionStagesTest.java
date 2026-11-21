package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionStagesTest {
    @Test void rerunRetainsFreeBackingLanesAfterAnEarlyAttackIsRemoved() {
        var parts = List.of(part(0));
        var decisions = new java.util.ArrayList<ReconstructionRoles.Decision>();
        for (int i = 0; i < 4; i++) {
            for (int pitch : List.of(60, 72)) {
                var note = new ReconstructionStages.Note("note-" + i + "-" + pitch, 0, i * .2, i * .2 + .1,
                        pitch, 90, "midi", 1);
                decisions.add(new ReconstructionRoles.Decision(note, ReconstructionRoles.Role.harmony, null, null));
            }
        }
        var original = new ReconstructionRoles.Result(1, 7.5, decisions, null);
        var previous = ReconstructionAllocation.allocate(original, parts, 60, false);
        var edited = new ReconstructionRoles.Result(1, 7.5, decisions.subList(1, decisions.size()), null);
        var fresh = ReconstructionAllocation.allocate(edited, parts, 60, false);
        var rerun = ReconstructionAllocation.allocate(edited, parts, 60, false, false, previous);

        assertNotEquals(previous.notes().subList(1, previous.notes().size()), fresh.notes());
        assertEquals(previous.notes().subList(1, previous.notes().size()), rerun.notes());
    }

    @Test void roundTripsLegatoCountsAndGapHistograms(@TempDir Path dir) throws Exception {
        var prior = note("prior", 0, .1, .4, 72);
        var next = note("next", 0, .4, .6, 74);
        var roles = new ReconstructionRoles.Result(ReconstructionStages.VERSION, 0, List.of(
                new ReconstructionRoles.Decision(prior, Role.melody, ReconstructionLeadContinuity.LEGATO, null, .3),
                new ReconstructionRoles.Decision(next, Role.melody, null, null)), null);
        var allocation = ReconstructionAllocation.allocate(roles, List.of(part(0)), 60, false, true);
        var file = dir.resolve("allocation.json");
        ReconstructionArtifacts.write(file, allocation);
        var restored = ReconstructionArtifacts.read(file, ReconstructionAllocation.Result.class);
        assertEquals(Map.of(6, 1), restored.fm1InterNoteGaps().before());
        assertEquals(Map.of(0, 1), restored.fm1InterNoteGaps().after());
        assertEquals(1L, restored.leadContinuity().reportCounts(true).get("lead-legato"));

        var legacy = JsonParser.parseString("""
                {"version":%d,"notes":[],"leadContinuity":{"split":1,"added":0,"extended":0}}
                """.formatted(ReconstructionStages.VERSION));
        var old = ReconstructionArtifacts.decode(file, ReconstructionAllocation.Result.class, legacy);
        assertEquals(0, old.leadContinuity().legatoCount());
        assertNull(old.fm1InterNoteGaps());
    }

    @Test void repeatConsensusUsesTwoOtherInstancesAndKeepsTheIdOfAReplacedNote() {
        var bars = bars(3);
        var notes = phrases(3);
        notes.removeIf(note -> note.id().equals("b2-2"));
        notes.add(note("wrong", 0, 4.6, 4.79, 67));
        notes.add(new Note("external", 0, 4.6, 4.79, 66, 96, "external", .35));

        var result = structure(bars, notes);
        assertEquals(List.of("b0", "b1", "b2"), result.repeats().getFirst().bars());
        var correction = assertDoesNotThrow(() -> result.corrections().getFirst());
        assertTrue(correction.applied());
        assertEquals("wrong", correction.replaces());
        assertEquals("wrong", correction.note().id());
        assertEquals("consensus", correction.note().source());
        assertEquals(List.of("b0", "b1"), correction.votes().stream().map(ReconstructionStructure.Vote::barId).toList());
        assertEquals("external", correction.externalCandidate());
        assertFalse(result.notes().stream().anyMatch(note -> note.source().equals("external")));
    }

    @Test void oneOtherInstanceAndAnExternalNoteCannotSupplyConsensus() {
        var notes = phrases(2);
        notes.removeIf(n -> n.id().equals("b1-2"));
        notes.add(new Note("external", 0, 2.6, 2.79, 66, 96, "external", .35));
        var result = structure(bars(2), notes);
        assertEquals(1, result.repeats().size());
        assertTrue(result.corrections().isEmpty());
        assertEquals(9, result.notes().size());
    }

    @Test void keepsTheMelodyThroughABriefCrossingAndMergesInnerReattacksInChordParts() {
        var notes = List.of(note("m1", 0, 0, .36, 74), note("m2", 0, .36, .72, 76),
                note("m3", 0, .72, 1.08, 77), note("cross", 1, .34, .43, 83),
                note("held-chord-tone", 1, .06, .33, 66),
                note("bass", 2, 0, 1.08, 42), note("counter", 3, 0, 1.08, 68),
                note("h1", 1, .10, .21, 61), note("h2", 1, .21, .32, 61));
        var parts = List.of(part(0), part(1), part(2), part(3));
        var structure = ReconstructionStructure.analyze(bars(1),
                notes.stream().sorted(ReconstructionStages.NOTE_ORDER).toList(), parts);
        var roles = ReconstructionRoles.assign(structure, parts, 7.5);
        assertEquals(List.of("m1", "m2", "m3"), roles.notes().stream().filter(n -> n.role() == Role.melody)
                .map(d -> d.note().id()).toList());
        var drop = roles.notes().stream().filter(n -> n.note().id().equals("h2")).findFirst().orElseThrow();
        assertEquals(Role.drop, drop.role());
        assertEquals("rapid-inner-tone-reattack", drop.reason());
        assertEquals("h1", drop.mergedInto());
        var allocation = ReconstructionAllocation.allocate(roles, parts, 120, false);
        assertTrue(allocation.notes().stream().filter(n -> n.role() == Role.melody).allMatch(n -> "FM1".equals(n.lane())));
        assertTrue(allocation.notes().stream().filter(n -> n.role() == Role.bass).allMatch(n -> "FM2".equals(n.lane())));
    }

    @Test void preservesInnerReattacksOnAnIndependentPart() {
        var notes = List.of(note("lead", 0, 0, 1.08, 77), note("bass", 1, 0, 1.08, 42),
                note("counter", 2, 0, 1.08, 68),
                note("first", 3, .10, .21, 61), note("second", 3, .21, .32, 61));
        var parts = List.of(part(0), part(1), part(2), part(3));
        var structure = ReconstructionStructure.analyze(bars(1),
                notes.stream().sorted(ReconstructionStages.NOTE_ORDER).toList(), parts);
        var roles = ReconstructionRoles.assign(structure, parts, 7.5);
        var inner = roles.notes().stream().filter(n -> n.note().part() == 3).toList();
        assertEquals(List.of(Role.harmony, Role.harmony), inner.stream().map(ReconstructionRoles.Decision::role).toList());
        assertEquals(.21, inner.getFirst().note().end());
        assertNull(inner.getLast().mergedInto());
    }

    @Test void tracksSequentialReattacksThroughSamePartSustainAndReservesFm1() {
        var notes = List.of(note("held", 0, 0, 1.15, 79),
                note("a", 0, .15, .25, 70), note("b", 0, .25, .35, 72),
                note("c", 0, .35, .45, 72), note("d", 0, .45, .60, 70),
                note("e", 0, .60, .76, 69), note("f", 0, .76, .93, 70),
                note("upper-backing", 1, 0, 1.20, 91)).stream().sorted(ReconstructionStages.NOTE_ORDER).toList();
        var parts = List.of(part(0), part(1));
        var structure = ReconstructionStructure.analyze(bars(1), notes, parts);
        var roles = ReconstructionRoles.assign(structure, parts, 0, null, null, null, 0);
        var allocation = ReconstructionAllocation.allocate(roles, parts, 120, false);
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), allocation.notes().stream()
                .filter(n -> "FM1".equals(n.lane())).map(n -> n.note().id()).toList());
        assertTrue(allocation.notes().stream().anyMatch(n -> n.note().id().equals("upper-backing")
                && n.lane() != null && !n.lane().equals("FM1")));
    }

    @Test void minorityVotesStaySuggestions() {
        var notes = phrases(4);
        notes.add(note("optional-a", 0, 1.5, 1.7, 71));
        notes.add(note("optional-b", 0, 3.5, 3.7, 71));
        var result = structure(bars(4), notes);
        assertEquals(1, result.repeats().size());
        assertEquals(2, result.corrections().size());
        assertTrue(result.corrections().stream().noneMatch(ReconstructionStructure.Correction::applied));
        assertEquals(22, result.notes().size());
    }

    @Test void linksIdenticalRepeatClipsAndKeepsDifferentArticulationIndependent() throws java.io.IOException {
        var notes = phrases(3);
        notes.replaceAll(n -> n.id().equals("b2-2")
                ? new Note(n.id(), n.part(), n.start(), n.end(), n.pitch(), 64, n.source(), n.confidence()) : n);
        var parts = List.of(part(0));
        var structure = structure(bars(3), notes);
        var roles = ReconstructionRoles.assign(structure, parts, 0);
        var allocation = ReconstructionAllocation.allocate(roles, parts, 6 * 60, false);
        var patch = DemoProject.create().voices().getFirst();
        var builder = new InstrumentCatalogue.Builder();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        var asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        var variant = builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        var existing = new ReconstructionVoices.Choice(asset, variant, patch.name(), null, "matched", List.of(), List.of());
        var choices = Map.of("FM1", Map.of(new ReconstructionVoices.Key(0, null), existing));
        var result = S3kReconstruction.arrangeAllocated("Repeats", 30, 360,
                new S3kReconstruction.Score(parts, List.of(), List.of()), allocation, structure,
                builder.build(), choices, Map.of());
        var project = result.project();
        assertEquals(3, project.clips().size());
        assertEquals(2, project.sequences().size());
        assertEquals(project.clips().get(0).sequenceId(), project.clips().get(1).sequenceId());
        assertNotEquals(project.clips().get(0).sequenceId(), project.clips().get(2).sequenceId());
        assertDoesNotThrow(() -> ProjectCompiler.compileWithAssets(project));
    }

    @Test void barGridIntegratesAChangeOfTempoInsideMeter() throws Exception {
        var file = java.nio.file.Files.createTempFile("stage-meter", ".mid");
        try {
            var midi = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
            var track = midi.createTrack();
            track.add(new javax.sound.midi.MidiEvent(
                    new javax.sound.midi.MetaMessage(0x58, new byte[]{3, 2, 24, 8}, 4), 0));
            track.add(new javax.sound.midi.MidiEvent(
                    new javax.sound.midi.MetaMessage(0x51, new byte[]{0x0f, 0x42, 0x40}, 3), 480));
            javax.sound.midi.MidiSystem.write(midi, 0, file.toFile());
            var grid = MidiScore.bars(file, 3_000_000);
            assertEquals(List.of(new ReconstructionStructure.Bar("b1", 0, 2.5, true),
                    new ReconstructionStructure.Bar("b2", 2.5, 3, false)), grid);
        } finally {
            java.nio.file.Files.delete(file);
        }
    }

    private static List<ReconstructionStructure.Bar> bars(int count) {
        var result = new ArrayList<ReconstructionStructure.Bar>();
        for (int i = 0; i < count; i++) result.add(new ReconstructionStructure.Bar("b" + i, i * 2, i * 2 + 2, true));
        return result;
    }

    private static ArrayList<Note> phrases(int count) {
        var result = new ArrayList<Note>();
        for (int i = 0; i < count; i++) for (int j = 0; j < 5; j++) {
            double start = 2 * i + .3 * j;
            result.add(note("b" + i + "-" + j, 0, start, start + .19, 64 + j));
        }
        return result;
    }

    private static ReconstructionStructure.Result structure(List<ReconstructionStructure.Bar> bars, List<Note> notes) {
        return ReconstructionStructure.analyze(bars, notes.stream().sorted(ReconstructionStages.NOTE_ORDER).toList(),
                List.of(part(0)));
    }

    private static Note note(String id, int part, double start, double end, int pitch) {
        return new Note(id, part, start, end, pitch, 96, "midi", 1);
    }

    private static MidiScore.Part part(int id) {
        return new MidiScore.Part(id, "Part " + id, 0, false);
    }
}
