package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionStemTemplatesTest {
    @Test void distinguishesAnOctaveWithAnIndependentAttackFromTheLearnedHarmonic() {
        var fixture = fixture(false);
        var candidates = ReconstructionStemTemplates.detect(fixture.audio(), fixture.notes(), fixture.notes());
        assertEquals(1, candidates.size(), candidates.toString());
        var candidate = candidates.getFirst();
        assertEquals(65, candidate.pitch());
        assertEquals(2, candidate.partial());
        assertEquals(1.66, candidate.start(), .025);
        assertEquals(1.79, candidate.end(), .025);
        assertTrue(candidate.automatic());
        assertEquals("audio-template", candidate.note().source());
    }

    @Test void offersAmbiguousExcessOnlyForReviewAndRespectsWrittenPitch() {
        var fixture = fixture(true);
        var candidates = ReconstructionStemTemplates.detect(fixture.audio(), fixture.notes(), fixture.notes());
        assertEquals(1, candidates.size(), candidates.toString());
        assertFalse(candidates.getFirst().automatic());
        var occupancy = new ArrayList<>(fixture.notes());
        occupancy.add(new ReconstructionStages.Note("written-octave", 0, 1.6, 1.84, 65, 90, "midi", 1));
        assertTrue(ReconstructionStemTemplates.detect(fixture.audio(), fixture.notes(), occupancy).isEmpty());
    }

    @Test void publishesReviewableEditsOnceAndHonorsRejection(@TempDir Path work) throws Exception {
        var fixture = fixture(true);
        var candidates = ReconstructionStemTemplates.detect(fixture.audio(), fixture.notes(), fixture.notes());
        var journal = ReconstructionStemTemplates.publish(work, ReconstructionEdits.read(work), candidates);
        assertEquals(1, journal.intents().size());
        assertTrue(journal.accepted().isEmpty());

        var edit = ReconstructionEdits.read(work).intents().getFirst();
        assertTrue(edit.proposed());
        assertEquals(ReconstructionEdits.Kind.insertNote, edit.kind());
        assertEquals("agent", edit.json().get("author").getAsString());
        assertEquals(ReconstructionStemTemplates.FILE,
                edit.json().getAsJsonObject("evidence").get("path").getAsString());

        new ReconstructionEdits(List.of()).write(work);
        assertTrue(ReconstructionStemTemplates.publish(work, ReconstructionEdits.read(work), candidates).isEmpty());
    }

    private record Fixture(ReferenceAudio audio, List<ReconstructionStages.Note> notes) {}

    private static Fixture fixture(boolean simultaneous) {
        int rate = 12_000;
        var samples = new float[rate * 2];
        var notes = new ArrayList<ReconstructionStages.Note>();
        double hz = ReconstructionLeadAudio.hz(53);
        for (int n = 0; n < 7; n++) {
            double start = .10 + n * .25;
            double end = start + .24;
            notes.add(new ReconstructionStages.Note("f" + n, 0, start, end, 53, 90, "midi", 1));
            for (int i = (int) Math.round(start * rate); i < Math.round(end * rate); i++) {
                double time = i / (double) rate;
                double phase = Math.TAU * hz * time;
                double octave = .04;
                if (n == 6 && (simultaneous || time >= start + .06 && time < start + .19))
                    octave += simultaneous ? .11 : .26;
                samples[i] = (float) (.3 * Math.sin(phase) + octave * Math.sin(2 * phase)
                        + .03 * Math.sin(3 * phase) + .015 * Math.sin(4 * phase));
            }
        }
        return new Fixture(new ReferenceAudio(samples, rate), notes);
    }
}
