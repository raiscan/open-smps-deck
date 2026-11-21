package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionVoicePlanTest {
    @Test void identifiesRegistersAndReplaysAnAtomicSelectionJournal(@TempDir Path work) throws Exception {
        var low = new ArrayList<Note>();
        var high = new ArrayList<Note>();
        for (int i = 0; i < 8; i++) {
            low.add(note("low-" + i, 0, i * .1, 48));
            high.add(note("high-" + i, 0, i * .1, 72));
        }
        var decisions = new ArrayList<ReconstructionRoles.Decision>();
        low.forEach(note -> decisions.add(decision(note, Role.bass)));
        high.forEach(note -> decisions.add(decision(note, Role.melody)));
        decisions.add(decision(note("kick", 1, 0, 35), Role.drums));
        decisions.add(decision(note("kick-2", 1, .1, 36), Role.drums));
        decisions.add(decision(note("hat", 1, .1, 42), Role.drums));
        writeFixture(work, decisions);
        var proposal = ReconstructionVoicePlan.load(work);
        assertEquals(List.of("part-0-lower", "part-0-upper", "part-1-kick", "part-1-closed-hat"),
                proposal.voices().stream().map(ReconstructionVoicePlan.Voice::id).toList());
        assertEquals(List.of("synth.wav"), proposal.voices().getFirst().evidence().stems());
        assertEquals(List.of(35, 36), proposal.voices().get(2).evidence().drumPitches());
        assertEquals("part-0-upper", proposal.byNote().get("high-0").id());

        run(work, "create", """
                {"id":"new","author":"human","voiceId":"answer","kind":"tonal","name":"Answer"}
                """);
        var moved = run(work, "move", """
                {"id":"point","author":"agent","voiceId":"answer","selectors":[{"at":0,"pitch":72,"part":0}]}
                """);
        assertEquals("answer", moved.byNote().get("high-0").id());
        var span = run(work, "move", """
                {"id":"span","author":"agent","voiceId":"answer","span":[0.1,0.25],"part":0,"pitchMin":72}
                """);
        assertEquals(List.of("high-0", "high-1", "high-2"), span.voices().getLast().noteIds());
        assertThrows(IllegalArgumentException.class, () -> run(work, "move", """
                [{"id":"rollback","author":"agent","voiceId":"answer","noteIds":["high-3"]},
                 {"id":"bad","author":"agent","voiceId":"answer","noteIds":["missing","high-4"]}]
                """));
        assertEquals(span, ReconstructionVoicePlan.load(work));

        writeFixture(work, decisions.stream().filter(item -> !item.note().id().equals("high-0")).toList());
        var replayed = ReconstructionVoicePlan.load(work);
        assertEquals("orphaned", replayed.issues().getFirst().status());
        assertEquals("point", replayed.issues().getFirst().id());
        assertEquals(List.of("high-1", "high-2"), replayed.voices().getLast().noteIds());
    }

    @Test void separatesARecurringLineFromStackedAccompanimentAndReportsOldId(@TempDir Path work) throws Exception {
        var line = new ArrayList<ReconstructionRoles.Decision>();
        var chords = new ArrayList<ReconstructionRoles.Decision>();
        for (int i = 0; i < 12; i++) {
            double at = i * .06;
            line.add(decision(note("line-" + i, 0, at, 68 + i % 4), Role.melody));
            if (i % 2 == 0) {
                chords.add(decision(note("inner-" + i, 0, at + .005, 65), Role.counter));
                chords.add(decision(note("chord-" + i, 0, at + .01, 60), Role.harmony));
            }
        }
        writeFixture(work, line);
        run(work, "rename", """
                {"id":"old","author":"human","voiceId":"part-0","name":"Original part"}
                """);
        var mixed = new ArrayList<>(line);
        mixed.addAll(chords);
        writeFixture(work, mixed);
        var split = ReconstructionVoicePlan.load(work);
        assertEquals(List.of("part-0-line", "part-0-chords"),
                split.voices().stream().map(ReconstructionVoicePlan.Voice::id).toList());
        assertEquals(line.stream().map(item -> item.note().id()).toList(), split.voices().getFirst().noteIds());
        assertEquals(List.of(new ReconstructionVoicePlan.Issue("old", "orphaned", "voice part-0 is missing")), split.issues());
        run(work, "rename", """
                {"id":"new-line","author":"agent","voiceId":"part-0-line","name":"Lead instrument 1"}
                """);
        mixed.removeLast();
        writeFixture(work, mixed);
        assertEquals("Lead instrument 1", ReconstructionVoicePlan.load(work).byNote().get("line-0").name());
    }

    @Test void assignedFmLinePreservesNormalMelodyOverlapHandling() {
        var lead = new Note("lead-a", 0, 0, .25, 72, 96, "midi", 1);
        var next = note("lead-b", 0, .2, 74);
        var parts = List.of(new MidiScore.Part(0, "Lead", 81, false));
        var roles = new ReconstructionRoles.Result(1, 7.5, List.of(
                decision(lead, Role.melody), decision(next, Role.melody)), null);
        var result = ReconstructionAllocation.allocateWithAffinity(roles, parts, 60, false, false, Map.of(), Map.of(),
                Map.of(lead.id(), ReconstructionLaneType.FM, next.id(), ReconstructionLaneType.FM));
        assertEquals(Map.of(lead.id(), "FM1", next.id(), "FM1"), ReconstructionAllocation.lanes(result));
        var tail = result.notes().getFirst();
        assertEquals(ReconstructionAllocation.Outcome.shortened, tail.outcome());
        assertEquals(ReconstructionLaneType.FM, tail.laneType());
        assertEquals(result.notes().getLast().startTick(), tail.endTick());
    }

    @Test void oneAssignedPatchFollowsAnInstrumentAcrossLanesAndSplitsSharedClips() throws Exception {
        var part = new MidiScore.Part(0, "Lead", 81, false);
        var backing = new MidiScore.Part(1, "Backing", 1, false);
        var drum = new MidiScore.Part(2, "Drums", 0, true);
        var parts = List.of(part, backing, drum);
        var roles = new ReconstructionRoles.Result(1, 7.5, List.of(
                decision(note("lead-a", 0, 0, 72), Role.melody),
                decision(note("backing", 1, .2, 60), Role.melody),
                decision(note("lead-b", 0, .2, 74), Role.counter),
                decision(note("hat", 2, .2, 42), Role.drums)), null);
        var allocated = ReconstructionAllocation.allocateWithAffinity(roles, parts, 60, false, false, Map.of(),
                Map.of(), Map.of("lead-a", ReconstructionLaneType.FM, "lead-b", ReconstructionLaneType.FM,
                        "hat", ReconstructionLaneType.NOISE));
        var lanes = ReconstructionAllocation.lanes(allocated);
        assertEquals("FM1", lanes.get("lead-a"));
        assertEquals("FM3", lanes.get("lead-b"));
        assertEquals("PSG3", lanes.get("hat"));

        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        var fixed = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Fixed", bytes);
        var variant = builder.variant(fixed, "Fixed", InstrumentCatalogue.Recipe.plain(), null);
        bytes[0] ^= 1;
        var other = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Other", bytes);
        var fallback = new ReconstructionVoices.Choice(other, null, "Other", null, "matched", List.of(), List.of());
        var fixedPatch = new ReconstructionVoicePlan.Assignment(ReconstructionLaneType.FM, variant);
        var hat = new ReconstructionVoicePlan.Assignment(ReconstructionLaneType.NOISE, "closed-hat");
        var arrangement = S3kReconstruction.arrangeAllocated("Fixture", 30, 60,
                new S3kReconstruction.Score(parts, List.of(), List.of()), allocated, null,
                builder.build(), Map.of("FM1", Map.of(new ReconstructionVoices.Key(1, null), fallback)),
                Map.of(), Map.of(), false, false, Map.of(), Map.of(), Map.of(), null,
                Map.of("lead-a", fixedPatch, "lead-b", fixedPatch, "hat", hat));
        assertEquals(1, arrangement.assignmentClipCost());
        var byId = arrangement.project().sequences().stream()
                .collect(Collectors.toMap(sequence -> sequence.id(), sequence -> sequence));
        var clips = arrangement.project().clips();
        var fm1 = clips.stream().filter(clip -> clip.lane().equals("FM1")).toList();
        assertEquals(List.of(fixed, other), fm1.stream().map(clip -> byId.get(clip.sequenceId()).voiceId()).toList());
        var fm3 = clips.stream().filter(clip -> clip.lane().equals("FM3")).findFirst().orElseThrow();
        assertEquals(fixed, byId.get(fm3.sequenceId()).voiceId());
        assertTrue(arrangement.voicedGates().stream().anyMatch(gate -> gate.id().equals("hat")
                && gate.pitch() == ReconstructionCymbals.PITCH));
        assertDoesNotThrow(() -> ProjectCompiler.compileWithAssets(arrangement.project()));
    }

    private static ReconstructionVoicePlan.Result run(Path work, String action, String json) throws Exception {
        return ReconstructionVoicePlan.run(work, action, JsonParser.parseString(json));
    }

    private static void writeFixture(Path work, List<ReconstructionRoles.Decision> decisions) throws Exception {
        var inputs = new ReconstructionStages.Inputs("source.mid", "mix.wav", "index.json", "Fixture",
                30, false, false, false, false, Map.of(0, "synth.wav", 1, "drums.wav"), null, 7.5, null, null);
        var parts = List.of(new MidiScore.Part(0, "Synth", 81, false),
                new MidiScore.Part(1, "Drums", 0, true));
        var notes = decisions.stream().map(ReconstructionRoles.Decision::note)
                .sorted(ReconstructionStages.NOTE_ORDER).toList();
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs,
                List.of(new ReconstructionStructure.Bar("b1", 0, 1, false)),
                new MidiScore(parts, List.of(), List.of()), Map.of(), notes, List.of(), List.of(), null, null);
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
        var sorted = decisions.stream().sorted(java.util.Comparator.comparing(ReconstructionRoles.Decision::note,
                ReconstructionStages.NOTE_ORDER)).toList();
        ReconstructionArtifacts.write(work.resolve("roles.json"), new ReconstructionRoles.Result(1, 7.5, sorted, null));
    }

    private static ReconstructionRoles.Decision decision(Note note, Role role) {
        return new ReconstructionRoles.Decision(note, role, null, null);
    }

    private static Note note(String id, int part, double start, int pitch) {
        return new Note(id, part, start, start + .07, pitch, 96, "midi", 1);
    }
}
