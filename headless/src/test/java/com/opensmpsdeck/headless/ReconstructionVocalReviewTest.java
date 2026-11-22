package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionVocalReviewTest {
    private static final ReconstructionTimeMap TIME = new ReconstructionTimeMap(null, null, 4);
    private static final ReconstructionVocalReview.Span SPAN = new ReconstructionVocalReview.Span("phrase", 0, 4);

    @Test void octaveRepairsPreservePitchClassAndCoalesceOnTheSameNote() {
        var notes = List.of(note("one", 60, .10, .30), note("octave", 72, .30, .40),
                note("three", 60, .40, .60), note("four", 60, .60, .85),
                note("five", 57, .85, 1.02), note("far", 85, 1.20, 1.40));
        var contour = new double[200];
        Arrays.fill(contour, 10, 85, 60);
        Arrays.fill(contour, 85, 102, 57);
        Arrays.fill(contour, 120, 140, 85);
        var phrase = analyze(notes, contour, new int[]{10, 30, 40, 60}, null, null, Map.of());
        var flag = phrase.flags().getFirst();
        assertEquals("octaveJump", flag.type());
        assertEquals(60, flag.proposal().get("pitch").getAsInt());
        assertEquals(1, phrase.flags().size());
        assertEquals(.30, phrase.notes().get(1).syllableOnset());
        assertEquals(60, phrase.notes().get(1).contour().medianMidi());
        assertEquals(1, phrase.notes().get(1).contour().voicingFraction());
    }

    @Test void outsideRangeRequiresEvidenceForAnOctaveFold() {
        var notes = List.of(note("one", 60, .10, .30), note("two", 61, .30, .40),
                note("three", 62, .40, .60), note("four", 63, .60, .85), note("high", 74, 1.4, 1.6));
        var f0 = new double[200];
        Arrays.fill(f0, 140, 160, 62);
        var flag = analyze(notes, f0, new int[0], null, null, Map.of()).flags().stream()
                .filter(row -> row.type().equals("outsidePhraseRange")).findFirst().orElseThrow();
        assertEquals("outsidePhraseRange", flag.type());
        assertEquals(62, flag.proposal().get("pitch").getAsInt());
    }

    @Test void advancesNextNoteAndCombinesContinuousFragments() {
        var f0 = new double[200];
        Arrays.fill(f0, 28, 50, 60);
        Arrays.fill(f0, 80, 107, 62);
        var phrase = analyze(List.of(note("late", 60, .29, .5)), f0, new int[]{20, 80, 98}, null, null, Map.of());
        assertEquals(List.of("moveSyllableAttack", "mergeSyllableFragments"),
                phrase.flags().stream().map(ReconstructionVocalReview.Flag::type).toList());
        var move = ReconstructionEdits.parse(phrase.flags().getFirst().proposal(), "move");
        assertEquals(.20, move.start());
        assertEquals(List.of("late"), move.noteIds());
        var insert = ReconstructionEdits.parse(phrase.flags().getLast().proposal(), "merge");
        assertEquals(.8, insert.start());
        assertEquals(1.07, insert.end());
    }

    @Test void avoidsVowelReleasesAndAnticipatoryConsonants() {
        var f0 = new double[200];
        Arrays.fill(f0, 15, 35, 60);
        Arrays.fill(f0, 50, 65, 60);
        Arrays.fill(f0, 100, 120, 62);
        var grid = new ReconstructionBeatMap(List.of(0.0, .5, 1.0, 1.5), 0, 4, 1);
        var phrase = analyze(List.of(note("earlier", 60, .15, .32), note("on-beat", 60, .5, .65),
                note("next-beat", 62, 1, 1.2)), f0, new int[]{15, 32, 83}, null, grid, Map.of());
        assertTrue(ReconstructionVocalReview.vowelRelease(f0, 32));
        assertTrue(phrase.flags().isEmpty());
    }

    @Test void spectralEvidenceDistinguishesHalfFrequencyFromTheSungOctaveAndGatesSilence() {
        var audio = new VocalStemEvidence(tones(220, .50));
        var wrongOctave = audio.profile(0, .5, 69);
        assertTrue(wrongOctave.supportsLowerOctave());
        assertNotNull(wrongOctave.subharmonicDb());
        assertTrue(audio.profile(0, .5, 57).supportsSungOctave());
        assertNotNull(audio.profile(0, .5, 57).oddHarmonicDb());
        var f0 = new double[200];
        Arrays.fill(f0, 50, 68, 57);
        var phrase = analyze(List.of(note("bleed", 57, .5, .68)), f0, new int[0], audio, null, Map.of());
        assertEquals("noVocal", phrase.flags().getFirst().type());
        assertEquals("deleteNote", phrase.flags().getFirst().proposal().get("op").getAsString());

        var instrumental = new ReconstructionVocalReview.Span("instrumental", 0, 1, true);
        phrase = ReconstructionVocalReview.analyze(1, instrumental, List.of(note("voiced-bleed", 57, .1, .3)),
                f0, new ReconstructionVocalReview.Boundaries(new int[]{10}, new int[0], new int[0]),
                TIME, audio, null, 0, Map.of());
        assertEquals("noVocal", phrase.flags().getFirst().type());
    }

    @Test void alignsRepeatedContoursAndDefersToStrongLocalEvidence() {
        var spans = IntStream.range(0, 3)
                .mapToObj(i -> new ReconstructionVocalReview.Span("p" + i, i, i + 1)).toList();
        var notes = IntStream.range(0, 3).boxed().flatMap(phrase ->
                IntStream.range(0, 6).mapToObj(i -> note(phrase + "-" + i,
                        new int[]{59, 57, 48, 60, 58, 61}[i] - (phrase == 2 && i == 2 ? 1 : 0),
                        phrase + i * .15, phrase + i * .15 + .13))).toList();
        var repeat = VocalPhraseRepeats.align(spans, notes);
        assertEquals(48, repeat.get("2-2").pitch());
        assertEquals(2, repeat.get("2-2").votes());
        var f0 = new double[400];
        Arrays.fill(f0, 230, 243, 47.5);
        Arrays.fill(f0, 236, 242, 48);
        var phrase = analyze(notes, f0, new int[0], null, null, repeat);
        assertEquals(48, phrase.flags().stream().filter(flag -> flag.type().equals("repeatDisagreement"))
                .findFirst().orElseThrow().proposal().get("pitch").getAsInt());
        Arrays.fill(f0, 230, 243, 47);
        phrase = analyze(notes, f0, new int[0], null, null, repeat);
        assertTrue(phrase.flags().stream().noneMatch(flag -> flag.type().equals("repeatDisagreement")));
    }

    @Test void rejectedIdentitySurvivesTimeDriftAndResurfacesForChangedEvidence(@TempDir Path work) throws Exception {
        var note = note("stable", 60, .1, .3);
        var instrumental = List.of(new ReconstructionVocalReview.Span("p", 0, 1, true));
        var tone = new VocalStemEvidence(tones(220, .15));
        var quiet = new VocalStemEvidence(tones(220, 0));
        var flag = analyze(List.of(note), new double[100], new int[0], quiet, null, Map.of()).flags().getFirst();
        ReconstructionEditCommand.vocalProposals(work, List.of(flag.proposal()), true, instrumental);
        String id = flag.proposal().get("id").getAsString();
        ReconstructionEditCommand.proposals(work, "reject", id, null);
        assertTrue(ReconstructionEdits.read(work).accepted().isEmpty());
        var shifted = note.withTiming(.11, .32);
        var reraised = analyze(List.of(shifted), new double[100], new int[0], quiet, null, Map.of()).flags().getFirst();
        var seen = ReconstructionEditCommand.vocalProposals(work, List.of(reraised.proposal()), true, instrumental);
        assertEquals(0, seen.saved());
        assertEquals("rejected", seen.entries().values().iterator().next().get("status").getAsString());
        var changed = ReconstructionVocalReview.analyze(1, instrumental.getFirst(), List.of(note), new double[100],
                new ReconstructionVocalReview.Boundaries(new int[0], new int[0], new int[0]), TIME, tone, null, 0, Map.of());
        // An explicit instrumental section produces a deletion even with a different harmonic profile.
        assertEquals(1, ReconstructionEditCommand.vocalProposals(work, List.of(changed.flags().getFirst().proposal()),
                true, instrumental).saved());
    }

    private static ReconstructionVocalReview.Phrase analyze(List<Note> notes, double[] contour, int[] syllables,
                                                            VocalStemEvidence audio, ReconstructionBeatMap grid,
                                                            Map<String, VocalPhraseRepeats.Consensus> repeat) {
        return ReconstructionVocalReview.analyze(1, SPAN, notes, contour,
                new ReconstructionVocalReview.Boundaries(syllables, new int[0], new int[0]), TIME, audio, grid, 0, repeat);
    }

    private static ReferenceAudio tones(double hz, double duration) {
        int rate = 24_000;
        var samples = new float[rate];
        for (int i = 0; i < duration * rate; i++) {
            double phase = Math.TAU * hz * i / rate;
            samples[i] = (float) (.14 * Math.sin(phase) + .18 * Math.sin(2 * phase) + .09 * Math.sin(3 * phase));
        }
        return new ReferenceAudio(samples, rate);
    }

    private static Note note(String id, int pitch, double start, double end) {
        return new Note(id, 1, start, end, pitch, 96, "audio-transcribed", 1);
    }
}
