package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionInspectionTest {
    private static final ReconstructionTrace.Timing ORIGINAL =
            new ReconstructionTrace.Timing(1, 2, 1, 1.2, 2.4, 1.2, 72, 144, 72);
    private static final ReconstructionTrace.Timing GATE =
            new ReconstructionTrace.Timing(1.1, 1.5, .4, 1.32, 1.8, .48, 79, 108, 29);

    @Test void usesVoicedGatePitchLaneAndPatchWithBothClocks() {
        var row = row(ReconstructionTrace.Status.kept, "FM1", null,
                List.of(new ReconstructionTrace.Voiced("note", "FM2", 65, GATE, null, "assembled")));
        var patches = new TreeMap<Double, String>();
        patches.put(0.0, "Old voice");
        patches.put(1.1, "New voice");
        var drawable = ReconstructionInspection.overlay(row, java.util.Map.of("FM2", patches), List.of(), false)
                .getFirst();
        assertEquals("note", drawable.id());
        assertEquals(ReconstructionInspection.Role.MELODY, drawable.role());
        assertEquals(65, drawable.pitch());
        assertEquals("FM2", drawable.lane());
        assertEquals("New voice", drawable.patch());
        assertEquals(1.1, drawable.interval().sourceStart());
        assertEquals(1.8, drawable.interval().projectEnd());
        assertEquals(ReconstructionInspection.Style.SOLID, drawable.style());
    }

    @Test void drawsLostNotesAtTheirEffectivePitchAndRetainsTheCompetingId() {
        for (var fate : List.of(ReconstructionTrace.Status.dropped, ReconstructionTrace.Status.merged)) {
            var row = row(fate, null, "blocker", List.of());
            var drawable = ReconstructionInspection.overlay(row, java.util.Map.of(), List.of(), false).getFirst();
            assertEquals(ReconstructionInspection.Style.DASHED, drawable.style());
            assertEquals(64, drawable.pitch());
            assertEquals(List.of("blocker"), drawable.competitors());
            assertEquals("kept elsewhere", drawable.reason());
            assertEquals(1.5, drawable.interval().sourceEnd());
        }
    }

    @Test void roleColorsAreDistinctAndStable() {
        assertEquals(0x52dec4, ReconstructionInspection.Role.MELODY.rgb());
        var roles = ReconstructionInspection.Role.values();
        assertEquals(roles.length, java.util.Arrays.stream(roles).mapToInt(ReconstructionInspection.Role::rgb)
                .distinct().count());
    }

    @Test void chipTilesSampleProjectAudioAtWarpedSourceTimes(@TempDir Path work) throws Exception {
        var inputs = new ReconstructionStages.Inputs("source.mid", "mix.wav", "index.json", "Fixture",
                30, false, false, false, false, java.util.Map.of(), null, 7.5,
                new ReconstructionTempo.Options(false, null, null, "120"), null);
        var score = new ReconstructionStages.Score(1, 2_000_000, 120, 60, inputs,
                List.of(new ReconstructionStructure.Bar("b1", 0, 2, true)),
                new MidiScore(List.of(), List.of(), List.of()), java.util.Map.of(), List.of(), List.of(), List.of(),
                null, null, new ReconstructionBeatMap(List.of(0.0, 1.0, 2.0), 0, 4, 1));
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
        int rate = 12_000;
        var pcm = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rate; i++) {
            double time = i / (double) rate;
            pcm.putShort(time >= .2 && time < .8 ? (short) (26_214 * Math.sin(time * 440 * Math.TAU)) : 0);
        }
        try (var audio = new AudioInputStream(new ByteArrayInputStream(pcm.array()),
                new AudioFormat(rate, 16, 1, true, false), rate)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, work.resolve("render.wav").toFile());
        }

        var inspection = ReconstructionInspection.open(work);
        assertEquals(.55, inspection.toProject(1.1), 1e-9);
        assertEquals(1.1, inspection.toSource(.55), 1e-9);
        var chip = inspection.sources().getFirst();
        assertEquals(ReconstructionInspection.AudioKind.CHIP, chip.kind());
        var tile = inspection.spectrum(chip, 1.0, 1.2, 8, 60, 77);
        var strongest = tile.ridges().stream().max(java.util.Comparator.comparingDouble(
                ReconstructionInspection.Ridge::dbfs)).orElseThrow();
        assertEquals(69, strongest.pitch(), .15);
        assertEquals(20 * Math.log10(.8), strongest.dbfs(), 1);
        assertEquals(strongest.dbfs(), java.util.stream.IntStream.range(0, tile.dbfs().length)
                .mapToDouble(i -> tile.dbfs()[i]).max().orElseThrow(), 1);
    }

    private static ReconstructionTrace.Row row(ReconstructionTrace.Status fate, String lane, String competitor,
                                               List<ReconstructionTrace.Voiced> voiced) {
        var note = new ReconstructionStages.Note("note", 0, 1.1, 1.5, 64, 90, "midi", 1);
        var effective = new ReconstructionTrace.NoteAt(note, GATE);
        var allocation = new ReconstructionTrace.Allocation(effective, ReconstructionRoles.Role.melody,
                List.of("FM1"), lane, lane == null ? null : GATE, ReconstructionAllocation.Outcome.kept,
                null, null, competitor, null, null, List.of());
        return new ReconstructionTrace.Row("note", 0, "Synth", 60, ORIGINAL, GATE, null, null, null, null, null,
                allocation, voiced, fate, "allocate", "kept elsewhere", null, null);
    }
}
