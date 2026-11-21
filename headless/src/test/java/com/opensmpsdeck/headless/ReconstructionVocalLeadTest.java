package com.opensmpsdeck.headless;

import java.util.List;

import org.junit.jupiter.api.Test;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionVocalLeadTest {
    @Test void keepsSupportedLineMergesSyllablesAndFillsAStableOmission() {
        int rate = 12_000;
        var samples = new float[rate * 2];
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) rate;
            int pitch = t >= .15 && t < .86 ? 67 : t >= 1.05 && t < 1.52 ? 71 : 0;
            if (pitch != 0) {
                double phase = Math.TAU * ReconstructionLeadAudio.hz(pitch) * t;
                samples[i] = (float) (.19 * Math.sin(phase) + .055 * Math.sin(2 * phase));
            }
            samples[i] += (float) (.024 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(44) * t));
        }
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, rate));
        var inputs = List.of(note("low", 44, .15, .86), note("one", 67, .15, .45),
                note("two", 67, .47, .86), note("wrong", 79, .20, .83));
        var notes = ReconstructionVocalLead.clean(1, inputs, track, ReconstructionVocalLead.contour(track));
        assertEquals(List.of(67, 71), notes.stream().map(Note::pitch).toList());
        assertTrue(notes.getFirst().end() - notes.getFirst().start() > .6);
        assertTrue(notes.getLast().end() - notes.getLast().start() > .35);
    }

    private static Note note(String id, int pitch, double start, double end) {
        return new Note(id, 1, start, end, pitch, 96, "audio-transcribed", 1);
    }
}
