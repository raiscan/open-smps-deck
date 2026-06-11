package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestGmDrumMapper {

    @Test
    void defaultTableMapsCoreKit() {
        var m = GmDrumMapper.defaultMapping();
        assertEquals(GmDrumMapper.DrumTarget.DAC_KICK, m.targetFor(36));
        assertEquals(GmDrumMapper.DrumTarget.DAC_SNARE, m.targetFor(38));
        assertEquals(GmDrumMapper.DrumTarget.DAC_TOM, m.targetFor(45));
        assertEquals(GmDrumMapper.DrumTarget.NOISE_SHORT, m.targetFor(42));
        assertEquals(GmDrumMapper.DrumTarget.NOISE_LONG, m.targetFor(46));
        assertEquals(GmDrumMapper.DrumTarget.NOISE_LONG, m.targetFor(49));
        assertEquals(GmDrumMapper.DrumTarget.DROP, m.targetFor(81)); // triangle: unmapped
    }

    @Test
    void splitRoutesDacAndNoiseSeparately() {
        var notes = List.of(
                new NoteQuantizer.QuantizedNote(0, 1, 36, 100),  // kick → DAC
                new NoteQuantizer.QuantizedNote(0, 1, 42, 80),   // hat → noise
                new NoteQuantizer.QuantizedNote(2, 1, 38, 100)); // snare → DAC
        var split = GmDrumMapper.split(notes, GmDrumMapper.defaultMapping());
        assertEquals(2, split.dacHits().size());
        assertEquals(1, split.noiseHits().size());
        assertEquals(0, split.droppedPitches().size());
    }

    @Test
    void simultaneousDacHitsKeepHighestPriority() {
        // kick + snare same step → kick wins (kick > snare > tom)
        var notes = List.of(
                new NoteQuantizer.QuantizedNote(0, 1, 38, 100),
                new NoteQuantizer.QuantizedNote(0, 1, 36, 100));
        var split = GmDrumMapper.split(notes, GmDrumMapper.defaultMapping());
        assertEquals(1, split.dacHits().size());
        assertEquals(GmDrumMapper.DrumTarget.DAC_KICK, split.dacHits().get(0).target());
    }

    @Test
    void unmappedPitchesAreReported() {
        var notes = List.of(new NoteQuantizer.QuantizedNote(0, 1, 81, 100));
        var split = GmDrumMapper.split(notes, GmDrumMapper.defaultMapping());
        assertTrue(split.dacHits().isEmpty());
        assertEquals(List.of(81), split.droppedPitches());
    }
}
