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

    @Test void leadRequiresBothSustainedPeriodicityAndCompetitiveLevel() {
        assertFalse(new ReconstructionVocalLead.Selection(List.of(), .7, .04).supportsLead(100, .1));
        assertFalse(new ReconstructionVocalLead.Selection(List.of(), 12, .002).supportsLead(100, .1));
        assertTrue(new ReconstructionVocalLead.Selection(List.of(), 12, .018).supportsLead(100, .1));
        assertTrue(new ReconstructionVocalLead.Selection(List.of(), .3, .018).supportsLead(1, .1));
    }

    @Test void scoresSegmentedVocalAgainstTheStemAndUsesItsContourForHeldBends() {
        int rate = 12_000;
        var samples = new float[rate];
        for (int i = 0; i < samples.length; i++)
            samples[i] = (float) (.2 * Math.sin(Math.TAU * ReconstructionLeadAudio.hz(48) * i / rate));
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, rate));
        var pitches = java.nio.ByteBuffer.allocate(100 * Short.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 100; i++) {
            int pitch = i < 50 ? 6000 : 4800 + Math.clamp((i - 61) * 5, 0, 85);
            pitches.putShort((short) pitch);
        }
        var metadata = new java.util.TreeMap<String, String>();
        metadata.put("f0Cents10ms", java.util.Base64.getEncoder().encodeToString(pitches.array()));
        var evidence = new ExternalTranscribers.Evidence("rmvpe", "vocals", "cpu", null,
                "fixture", null, metadata, 2, true);
        var selection = ReconstructionVocalLead.select(1, new ExternalTranscribers.PartNotes(evidence,
                List.of(new ExternalTranscribers.RawNote(.1, .45, 60, 96),
                        new ExternalTranscribers.RawNote(.55, .9, 48, 96))), track, 1);

        assertEquals("stem-periodicity", metadata.get("vocalReference"));
        var metrics = com.google.gson.JsonParser.parseString(metadata.get("vocalComparison")).getAsJsonObject()
                .getAsJsonObject("rmvpe");
        assertEquals(.5, metrics.get("pitchAgreement").getAsDouble(), .02);
        assertNull(selection.notes().getFirst().bend());
        assertEquals(ReconstructionBends.Kind.UP, selection.notes().getLast().bend().kind());
    }

    private static Note note(String id, int pitch, double start, double end) {
        return new Note(id, 1, start, end, pitch, 96, "audio-transcribed", 1);
    }
}
