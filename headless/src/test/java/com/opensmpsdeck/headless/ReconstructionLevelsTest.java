package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        String variant = builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), ReconstructionCatalogueFixture.stock());
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
        probe.compile(List.of(lead), ideal).writeWave(stem, frames);
        probe.compile(probe.notes(), ideal).writeWave(mix, frames);

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

    @ParameterizedTest
    @EnumSource(value = TargetGame.class, names = {"S1", "S2"})
    void classicStockBalanceChangesTonalVolumeAndPreservesDac(TargetGame target) throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), StockTable.bytes(patch.bytes()));
        String variant = builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), ReconstructionCatalogueFixture.stock());
        var catalogue = builder.build();
        Path drums = directory.resolve("drums.wav");
        Path tone = directory.resolve("tone.wav");
        int frames = 120;
        var inputs = new ReconstructionStages.Inputs(null, directory.resolve("mix.wav").toString(), "unused",
                "Stock balance", 30, false, false, false, false, Map.of(0, drums.toString(), 1, tone.toString()),
                null, 0, null, false, null, null, null, null, null, null, target.id(), null);
        var notes = List.of(
                new ReconstructionStages.Note("kick", 0, .5, .55, 36, 100, "test", 1),
                new ReconstructionStages.Note("lead", 1, .25, 1.5, 69, 90, "test", 1));
        var source = new MidiScore(List.of(new MidiScore.Part(0, "Drums", 0, true),
                new MidiScore.Part(1, "Lead", 0, false)), List.of(), List.of());
        var score = new ReconstructionStages.Score(VERSION, 2_000_000, frames, 60, inputs, List.of(), source,
                Map.of(), notes, List.of(), List.of(), null, null);
        var kick = placement(notes.getFirst(), "DAC", Role.drums, frames);
        var lead = placement(notes.getLast(), "FM1", Role.melody, frames);
        var allocation = new ReconstructionAllocation.Result(VERSION, List.of(lead, kick));

        byte[] pcm = new byte[2400];
        for (int i = 0; i < pcm.length; i++)
            pcm[i] = (byte) (128 + Math.round(60 * Math.sin(i * .10) * Math.exp(-i / 700.0)));
        var ref = StockTable.ref(target.id(), "dac", null, 0x81, StockTable.hash(pcm));
        var sample = DacSample.of("synthetic-stock", "Synthetic stock fixture", 12, pcm).withOrigin(AssetOrigin.stock(ref));
        var evidence = new ReconstructionDrums.Evidence("stem", ReconstructionTimeMap.of(score).timeBase(),
                List.of(), null, List.of(), null, ReconstructionDrums.Source.stock, "test",
                null, null, null, null, null, null, null);
        var drum = new Instrument(0, 36, null, new ReconstructionDrums.Selection(null, sample.rate(), sample, evidence));
        var dac = new Lane(Role.drums, null, List.of(), 1, WRITTEN, DRY, DEFAULT, DEFAULT, DEFAULT, List.of(drum));
        var normal = lane(Role.melody, variant, 1);
        var base = new Result(VERSION, Map.of("DAC", dac, "FM1", normal), null);
        var softTone = new Result(VERSION, Map.of("DAC", dac, "FM1", normal.withGain(.25)), null);
        var probe = new ReconstructionLevels.Probe(score, catalogue, allocation, base);
        probe.compile(List.of(kick), base).writeWave(drums, frames);
        probe.compile(List.of(lead), softTone).writeWave(tone, frames);

        var fitted = ReconstructionDrumBalance.fit(score, catalogue, allocation, base);
        assertTrue(fitted.lanes().get("FM1").gain() < normal.gain());
        assertEquals(dac, fitted.lanes().get("DAC"));
        var played = probe.compile(probe.notes(), fitted).compiled().samples().getFirst();
        assertEquals(sample, played);
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
