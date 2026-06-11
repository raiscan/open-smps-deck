package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.*;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TestVoiceMatchEndToEnd {

    /**
     * Full pipeline with ground truth: a known voice "performs" a melody where one
     * note is isolated; the matcher must return a candidate spectrally close to it.
     */
    @Test
    void matchedVoiceSoundsLikeTheTruth() throws Exception {
        FmVoice truth = GmVoiceSuggestions.brass();
        var renderer = new CandidateRenderer();

        // stem: 1.0 s isolated note at MIDI 55 starting at 0.5 s, padded with silence
        float[] note = renderer.render(truth.getData(), 55, 1.0, 0.2);
        float[] stem = new float[44100 * 2];
        System.arraycopy(note, 0, stem, 22050, Math.min(note.length, stem.length - 22050));

        // matching MIDI: 480ppq, 120 BPM → 1.0 s = 960 ticks, start 0.5 s = 480 ticks
        var notes = List.of(new NoteEvent(480, 960, 55, 110));
        var map = new TickTimeMapper(480, List.of(new MidiStem.TempoEvent(0, 500000)));

        var service = new VoiceMatchService();
        var result = service.match(stem, notes, map,
                        new FmPatchSearch.Config(16, 8, 120_000, 99L, 3), g -> {})
                .get(5, TimeUnit.MINUTES);
        service.shutdown();

        assertFalse(result.candidates().isEmpty());
        double best = result.candidates().get(0).score();
        assertTrue(best < 0.02, "expected near-recovery of seeded truth, score=" + best);
    }
}
