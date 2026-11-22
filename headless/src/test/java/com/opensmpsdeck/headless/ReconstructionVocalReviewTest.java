package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionVocalReviewTest {
    @Test void proposesRepairsForEveryFlagType() {
        var notes = List.of(note("one", 60, .10, .30), note("octave", 72, .30, .40),
                note("three", 60, .40, .60), note("held", 60, .60, .85),
                note("unsupported", 57, .85, 1.02), note("six", 60, 1.04, 1.12),
                note("seven", 60, 1.12, 1.30));
        var contour = new double[200];
        Arrays.fill(contour, 10, 130, 60);
        Arrays.fill(contour, 160, 180, 62);
        var boundaries = new ReconstructionVocalReview.Boundaries(new int[]{10, 30, 40, 60, 72, 104, 112, 160, 175},
                new int[]{10, 160}, new int[0]);
        var phrase = ReconstructionVocalReview.analyze(1, new ReconstructionVocalReview.Span("phrase", 0, 2),
                notes, contour, boundaries, new ReconstructionTimeMap(null, null, 2));

        assertEquals(notes.size(), phrase.notes().size());
        assertEquals(Set.of("octaveJump", "shortBetweenSamePitch", "outsidePhraseRange", "contourDisagreement",
                        "noOnsetEvidence", "missingSyllableNote"),
                phrase.flags().stream().map(ReconstructionVocalReview.Flag::type).collect(Collectors.toSet()));
        var repairs = phrase.flags().stream().map(flag -> ReconstructionEdits.parse(flag.proposal(), flag.type())).toList();
        assertTrue(repairs.stream().allMatch(ReconstructionEdits.Intent::proposed));
        assertEquals(Set.of("splitNote", "insertNote"), phrase.flags().stream()
                .filter(flag -> flag.type().equals("missingSyllableNote"))
                .map(flag -> flag.proposal().get("op").getAsString()).collect(Collectors.toSet()));
        var precedingInsert = phrase.flags().stream().filter(flag -> flag.sourceTime() == 1.60).findFirst().orElseThrow();
        assertEquals(1.75, precedingInsert.proposal().get("end").getAsDouble());
        var octave = phrase.notes().get(1);
        assertEquals(.30, octave.syllableOnset());
        assertEquals(60, octave.contour().medianMidi());
        assertEquals(0, octave.contour().pitchConfidence());
        assertEquals(1, octave.contour().voicingFraction());
    }

    private static Note note(String id, int pitch, double start, double end) {
        return new Note(id, 1, start, end, pitch, 96, "audio-transcribed", 1);
    }
}
