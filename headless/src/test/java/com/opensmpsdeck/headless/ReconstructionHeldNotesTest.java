package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.TargetProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionHeldNotesTest {
    @TempDir Path directory;

    @Test void reportsAnInaudibleGateAndADistinctAllocationLoss() throws Exception {
        var part = new MidiScore.Part(0, "Guitar", 27, false);
        var held = new Note("held", 0, .1, 1.5, 69, 96, "test", 1);
        var stem = directory.resolve("stem.wav");
        WaveFile.write(stem, 120, sink -> {
            for (int frame = 0; frame < 120; frame++) {
                short[] samples = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
                for (int i = 0; i < samples.length; i += 2) {
                    double time = (frame * (samples.length / 2) + i / 2) / (double) S3kRenderer.SAMPLE_RATE;
                    if (time >= held.start() && time < held.end())
                        samples[i] = samples[i + 1] = (short) (3000 * Math.sin(Math.TAU * 440 * time));
                }
                sink.accept(samples);
            }
        });
        var inputs = new ReconstructionStages.Inputs(null, "mix.wav", "unused", "Held notes", 30,
                false, false, false, false, Map.of(0, stem.toString()), null, 0, null, false);
        var score = new ReconstructionStages.Score(VERSION, 2_000_000, 120, 60, inputs, List.of(),
                new MidiScore(List.of(part), List.of(), List.of()), Map.of(), List.of(held), List.of(), List.of(), null, null);
        var allocation = new ReconstructionAllocation.Result(VERSION, List.of(new ReconstructionAllocation.Entry(
                held, Role.harmony, "FM3", 6, 90, ReconstructionAllocation.Outcome.kept, null, null)));
        var emptyLane = DemoProject.create();
        var toneGate = new S3kReconstruction.VoicedGate(held.id(), "FM3", held.pitch(), 6, 90);
        var shortGate = new S3kReconstruction.VoicedGate(held.id(), "FM3", held.pitch(), 6, 22);
        for (var gate : List.of(toneGate, shortGate)) {
            var audio = new S3kReconstruction.Result(emptyLane, 1, 0, 0, 0, List.of(), List.of(), List.of(gate));
            var report = ReconstructionHeldNotes.inspect(score, allocation, audio);
            assertEquals(1, report.sourceHolds());
            var finding = report.findings().getFirst();
            assertEquals(held.id(), finding.noteId());
            assertEquals(gate == toneGate ? "held-but-silent" : "truncated", finding.kind());
            assertEquals(0, finding.sourceDecayDb(), .1);
        }
    }

    @Test void spillWithSourceSustainSelectsAnAudibleTail() throws Exception {
        var patch = DemoProject.create().voices().getFirst();
        var builder = new InstrumentCatalogue.Builder();
        String fm = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), StockTable.bytes(patch.bytes()));
        String patchId = builder.variant(fm, "FM", InstrumentCatalogue.Recipe.plain(), null);
        String pluck = builder.asset(InstrumentCatalogue.Kind.PSG_VOLUME, "Pluck", new byte[]{0, 4, (byte) 0x83});
        builder.variant(pluck, "Pluck", InstrumentCatalogue.Recipe.plain(), null);
        String sustain = builder.asset(InstrumentCatalogue.Kind.PSG_VOLUME, "Hold", new byte[]{0, (byte) 0x81});
        String sustainId = builder.variant(sustain, "Hold", InstrumentCatalogue.Recipe.plain(), null);
        var tone = new ReconstructionVoicing.Lane(Role.harmony, patchId, List.of(), 1,
                ReconstructionVoicing.WRITTEN, ReconstructionVoicing.DRY, "", "", "", List.of());
        var note = new Note("held", 0, 0, 1.5, 75, 100, "test", 1);
        var entry = new ReconstructionAllocation.Entry(note, Role.harmony, "PSG2", 0, 90,
                ReconstructionAllocation.Outcome.kept, "psg-spill", null);
        var match = ReconstructionSpill.match(builder.build(), tone, new MidiScore.Part(0, "Guitar", 27, false),
                List.of(entry), TargetProfile.S3K, Map.of(note.id(), new ReconstructionHeldNotes.Hold(1.2, -20, -1)));
        assertEquals(sustainId, match.variantId());
    }

    @Test void preservesCrossPartHoldWithoutMovingTheBassAttack() {
        var guitar = new MidiScore.Part(0, "Guitar", 27, false);
        var bass = new MidiScore.Part(1, "Bass", 33, false);
        var held = new Note("held", 0, 0, .8, 59, 96, "test", 1);
        var attack = new Note("attack", 1, .067, .3, 31, 96, "test", 1);
        var roles = new ReconstructionRoles.Result(VERSION, 0, List.of(
                new ReconstructionRoles.Decision(held, Role.bass, null, null),
                new ReconstructionRoles.Decision(attack, Role.bass, null, null)), null);
        var allocation = ReconstructionAllocation.allocateWithAffinity(roles, List.of(guitar, bass), 60, false,
                true, Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of(), false, TargetProfile.S3K,
                ReconstructionArrangement.Allocation.NONE, Map.of(held.id(), new ReconstructionHeldNotes.Hold(.65, -20, -4)));
        var original = allocation.notes().stream().filter(n -> n.note().id().equals(held.id())).findFirst().orElseThrow();
        assertEquals("FM3", original.lane());
        assertEquals(48, original.endTick());
        assertEquals("FM2", original.relocatedFrom());
        assertEquals("FM2", allocation.notes().stream().filter(n -> n.note().id().equals(attack.id())).findFirst().orElseThrow().lane());
    }
}
