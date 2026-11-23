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

    @Test void holdsTheVocalThroughAnOctaveExcursionButKeepsARealLeap() {
        int rate = 12_000;
        var samples = new float[rate * 2];
        var contour = java.nio.ByteBuffer.allocate(200 * Short.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 200; i++) {
            int observed = i < 10 ? 0 : i < 45 ? 48 : i < 63 ? 60 : i < 100 ? 48 : i < 155 ? 63 : 0;
            contour.putShort((short) (observed * 100));
        }
        for (int i = rate / 10; i < rate * 1.55; i++) {
            double t = i / (double) rate;
            double phase = Math.TAU * ReconstructionLeadAudio.hz(t < 1 ? 48 : 63) * t;
            samples[i] = (float) (.18 * Math.sin(phase) + .10 * Math.sin(2 * phase));
        }
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, rate));
        var metadata = new java.util.TreeMap<String, String>();
        metadata.put("f0Cents10ms", java.util.Base64.getEncoder().encodeToString(contour.array()));
        metadata.put("vocalMode", "syllable");
        metadata.put("vocalBoundaries10ms", """
                {"syllable":[10,100],"voicing":[10],"pitchChange":[45,63,100]}
                """);
        var notes = List.of(new ExternalTranscribers.RawNote(.10, .45, 48, 96),
                new ExternalTranscribers.RawNote(.45, .63, 60, 96),
                new ExternalTranscribers.RawNote(.63, .91, 48, 96),
                new ExternalTranscribers.RawNote(.91, 1, 48, 96),
                new ExternalTranscribers.RawNote(1, 1.55, 63, 96));
        var evidence = new ExternalTranscribers.Evidence("rmvpe", "vocals", "cpu", null,
                "fixture", null, metadata, notes.size(), true);
        var selection = ReconstructionVocalLead.select(1, new ExternalTranscribers.PartNotes(evidence, notes), track, 2);

        assertEquals(List.of(48, 63), selection.notes().stream().map(Note::pitch).toList());
        assertEquals(1, selection.notes().getFirst().end(), 1e-9);
        assertTrue(metadata.get("vocalPitchDecisions").contains("\"observedPitch\":60,\"selectedPitch\":48"));

        var replayMetadata = new java.util.LinkedHashMap<>(metadata);
        var replayEvidence = new ExternalTranscribers.Evidence("rmvpe", "vocals", "cpu", null,
                "fixture", null, replayMetadata, notes.size(), true);
        var replay = ReconstructionVocalLead.select(1,
                new ExternalTranscribers.PartNotes(replayEvidence, notes), track, 2);
        assertEquals(selection, replay);
        assertEquals(List.copyOf(metadata.entrySet()), List.copyOf(replayMetadata.entrySet()));
    }

    @Test void resolvesAnInconsistentTrackerOctaveFromOddPartialsAcrossTheGate() {
        int rate = 12_000;
        var samples = new float[rate];
        var contour = java.nio.ByteBuffer.allocate(100 * Short.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < 100; frame++)
            contour.putShort((short) (frame % 3 == 0 ? 4800 : 6000));
        for (int i = 0; i < samples.length; i++) {
            double phase = Math.TAU * ReconstructionLeadAudio.hz(48) * i / rate;
            samples[i] = (float) (.04 * Math.sin(phase) + .20 * Math.sin(2 * phase)
                    + .09 * Math.sin(3 * phase) + .06 * Math.sin(5 * phase));
        }
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, rate));
        var result = VocalPitchContinuity.select(List.of(note("octave", 60, .15, .8)), track,
                ReconstructionVocalLead.contour(track), java.util.Base64.getEncoder().encodeToString(contour.array()),
                new int[]{15}, .006);

        assertEquals(List.of(48), result.notes().stream().map(Note::pitch).toList());
        assertEquals("stemSpectrumOctave", result.decisions().getFirst().reason());
    }

    @Test void aRepeatedPhraseAnchorsAnAmbiguousOctaveAndPreservesASupportedOne() {
        int rate = 12_000;
        var notes = new java.util.ArrayList<Note>();
        var samples = new float[rate * 9];
        var model = java.nio.ByteBuffer.allocate(900 * Short.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        var noise = new java.util.Random(41);
        int[] melody = {50, 54, 48, 55, 52};
        for (int phrase = 0; phrase < 3; phrase++) {
            for (int gate = 0; gate < melody.length; gate++) {
                int start = 20 + 280 * phrase + 28 * gate + (phrase == 1 && gate > 2 ? 4 : 0);
                int end = start + 24;
                boolean ambiguous = phrase == 1 && gate == 2;
                int pitch = gate == 2 && phrase != 0 ? 60 : melody[gate];
                notes.add(note("repeat-" + phrase + "-" + gate, pitch, start / 100.0, end / 100.0));
                for (int frame = start; frame < end; frame++)
                    model.putShort(frame * Short.BYTES, (short) (ambiguous ? 0 : pitch * 100));
                for (int i = start * rate / 100; i < end * rate / 100; i++) {
                    double phase = Math.TAU * ReconstructionLeadAudio.hz(pitch) * i / rate;
                    samples[i] = ambiguous ? (float) (.03 * (2 * noise.nextDouble() - 1))
                            : (float) (.18 * Math.sin(phase) + .06 * Math.sin(2 * phase));
                }
            }
        }
        var track = new ReconstructionLeadAudio.Track(new ReferenceAudio(samples, rate));
        int[] syllables = notes.stream().mapToInt(note -> (int) Math.round(note.start() * 100)).toArray();
        var periodic = ReconstructionVocalLead.contour(track);
        var encoded = java.util.Base64.getEncoder().encodeToString(model.array());
        var result = VocalPitchContinuity.select(notes, track, periodic, encoded, syllables, .006);

        // Two supported repeats disagree, so no repeat vote can resolve the middle instance.
        assertTrue(result.notes().stream().anyMatch(note -> note.id().equals("repeat-2-2") && note.pitch() == 60));
        assertFalse(result.decisions().stream().anyMatch(decision -> decision.pitchEvidence().contains("repeatedPhraseOctave")));
        var firstTwo = VocalPitchContinuity.select(notes.subList(0, 10), track, periodic, encoded, syllables, .006);
        assertTrue(firstTwo.decisions().stream().anyMatch(decision -> decision.noteId().equals("repeat-1-2")
                && Integer.valueOf(48).equals(decision.selectedPitch())
                && decision.pitchEvidence().contains("repeatedPhraseOctave")));
    }

    private static Note note(String id, int pitch, double start, double end) {
        return new Note(id, 1, start, end, pitch, 96, "audio-transcribed", 1);
    }
}
