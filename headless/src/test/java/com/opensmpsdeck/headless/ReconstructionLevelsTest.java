package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DemoProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;
import static com.opensmpsdeck.headless.ReconstructionVoicing.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionLevelsTest {
    @TempDir Path directory;

    @Test void recoversQuietSparseSourceInstrumentAndReportsLeadBoost() throws Exception {
        var patch = DemoProject.create().voices().getFirst();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        // Mark the two algorithm 4 carriers so S3K applies channel volume.
        bytes[22] |= (byte) 0x80;
        bytes[24] |= (byte) 0x80;
        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        String variant = builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();

        Path stem = directory.resolve("lead.wav");
        Path mix = directory.resolve("mix.wav");
        int frames = 480;
        var inputs = new ReconstructionStages.Inputs(null, mix.toString(), "unused", "Sparse lead", 30,
                false, true, false, false, Map.of(0, stem.toString()), null, 0, null, false);
        var notes = List.of(
                new ReconstructionStages.Note("lead", 0, 3, 4.2, 76, 88, "test", 1),
                new ReconstructionStages.Note("bass-1", 1, 1, 3.8, 50, 88, "test", 1),
                new ReconstructionStages.Note("bass-2", 1, 3.8, 7, 53, 88, "test", 1));
        var source = new MidiScore(List.of(new MidiScore.Part(0, "Lead", 0, false),
                new MidiScore.Part(1, "Bass", 0, false)), List.of(), List.of());
        var score = new ReconstructionStages.Score(VERSION, 8_000_000, frames, 60, inputs, List.of(), source,
                Map.of(), notes, List.of(), List.of(), null, null);
        var lead = placement(notes.getFirst(), "FM1", Role.melody, frames);
        var allocation = new ReconstructionAllocation.Result(VERSION, List.of(placement(notes.get(1), "FM2", Role.bass, frames),
                lead, placement(notes.get(2), "FM2", Role.bass, frames)));
        var lanes = Map.of("FM1", lane(Role.melody, null, 1), "FM2", lane(Role.bass, variant, .75));
        var ideal = new Result(VERSION, lanes, null).withSourceInstruments(
                Map.of("part-0", new SourceSound(lane(Role.melody, variant, 1), null, 0)));
        var probe = new ReconstructionLevels.Probe(score, catalogue, allocation, ideal);
        WaveFile.render(probe.compile(List.of(lead), ideal), stem, frames);
        WaveFile.render(probe.compile(probe.notes(), ideal), mix, frames);

        var quiet = ideal.withSourceInstruments(
                Map.of("part-0", new SourceSound(lane(Role.melody, variant, .004), null, 0)));
        var fitted = ReconstructionLevels.calibrate(score, catalogue, allocation, quiet, Set.of("FM1"));
        assertEquals(lanes.get("FM2"), fitted.lanes().get("FM2"));
        assertEquals(variant, fitted.sourceSound("FM1", 0).fm().variantId());
        assertNotNull(fitted.leadProminenceWarning());
        assertTrue(fitted.leadProminenceWarning().contains("raised the lead"));
        var active = ReconstructionLevels.referenceLevel(List.of(stem), List.of(), frames * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION).meter().activeBlocks();
        double expected = probe.render(List.of(lead), ideal).powerOn(active, probe.timeline());
        double actual = probe.render(List.of(lead), fitted).powerOn(active, probe.timeline());
        assertEquals(0, 10 * Math.log10(actual / expected), 2);
    }

    @Test void patchRefitRetainsCalibratedSourceGain() {
        var laneDefault = lane(Role.melody, null, 1);
        var saved = new Result(VERSION, Map.of("FM1", laneDefault), null).withSourceInstruments(
                Map.of("part-0", new SourceSound(lane(Role.melody, "previous", 2), null, 0)));
        var fitted = saved.withSourceInstruments(
                Map.of("part-0", new SourceSound(lane(Role.melody, "next", .1), null, 0)));
        var lead = new ReconstructionStages.Note("lead", 0, 1, 2, 76, 88, "test", 1);
        var allocated = Map.of("FM1", List.of(placement(lead, "FM1", Role.melody, 180)));

        var retained = preserveRefit(fitted, saved, Set.of(), false, Set.of(), allocated);
        assertEquals("next", retained.sourceSound("FM1", 0).fm().variantId());
        assertEquals(2, retained.sourceSound("FM1", 0).fm().gain());
        var refitted = preserveRefit(fitted, saved, Set.of(), true, Set.of(), allocated);
        assertEquals(.1, refitted.sourceSound("FM1", 0).fm().gain());
    }

    private static Lane lane(Role role, String variant, double gain) {
        return new Lane(role, variant, List.of(), gain, WRITTEN, DRY, DEFAULT, DEFAULT, DEFAULT, List.of());
    }

    private static ReconstructionAllocation.Entry placement(ReconstructionStages.Note note, String lane, Role role,
                                                              int frames) {
        return new ReconstructionAllocation.Entry(note, role, lane, note.startTick(frames), note.endTick(frames),
                ReconstructionAllocation.Outcome.kept, null, null);
    }
}
