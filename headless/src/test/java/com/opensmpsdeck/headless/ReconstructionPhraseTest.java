package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionPhraseTest {
    @Test void recoversTempoAndPhaseWithJitterAndMissingBeats() {
        var notes = new ArrayList<MidiScore.Note>();
        var random = new Random(17);
        double beat = 60.0 / 137, phase = .38;
        for (int bar = 0; bar < 28; bar++) {
            for (int beatIndex = 0; beatIndex < 4; beatIndex++) {
                if (random.nextDouble() < .2) continue;
                int pitch = beatIndex % 2 == 0 ? 36 : 38;
                long time = Math.round(1e6 * (phase + (bar * 4 + beatIndex) * beat + random.nextGaussian() * .008));
                notes.add(new MidiScore.Note(0, pitch, 100, time, time + 50_000));
            }
            for (int i : new int[]{0, 3, 5, 9, 12, 14}) {
                long time = Math.round(1e6 * (phase + (bar * 4 + i / 4.0) * beat + random.nextGaussian() * .01));
                notes.add(new MidiScore.Note(1, 52 + i, 95, time, time + 120_000));
            }
        }
        var score = new MidiScore(List.of(new MidiScore.Part(0, "Drums", 0, true),
                new MidiScore.Part(1, "Lead", 81, false)), notes, List.of());
        var estimate = ReconstructionTempo.estimate(score, 50, List.of(), 0);
        assertEquals(137, estimate.selected().bpm(), .15);
        assertEquals(phase, estimate.selected().barOffset(), .025);
        assertEquals(4, estimate.selected().beatsPerBar());
        assertFalse(estimate.alternatives().isEmpty());
    }

    @Test void alignsOctavesAndJitterAndRequiresTwoIndependentVotesForMissingAttack() {
        var notes = new ArrayList<Note>();
        var bars = new ArrayList<ReconstructionStructure.Bar>();
        for (int bar = 0; bar < 3; bar++) {
            bars.add(new ReconstructionStructure.Bar("b" + bar, bar * 2, bar * 2 + 2, true));
            for (int i = 0; i < 8; i++) {
                if (bar == 0 && i == 7) continue;
                int pitch = 60 + (i * 5 % 11) + (bar == 0 ? 12 : 0);
                double time = bar * 2 + .10 + .20 * i + bar * .025;
                notes.add(new Note("n-" + bar + "-" + i, 0, time, time + .15, pitch, 95, "midi", 1));
            }
        }
        var parts = List.of(new MidiScore.Part(0, "Lead", 81, false));
        var two = ReconstructionStructure.analyze(bars.subList(0, 2),
                notes.stream().filter(n -> n.start() < 4).toList(), parts, null, true);
        assertEquals(1, two.phrases().size());
        assertTrue(two.corrections().isEmpty());
        assertTrue(two.repeats().isEmpty());
        var three = ReconstructionStructure.analyze(bars, notes, parts, null, true);
        var fixed = three.corrections().stream().filter(ReconstructionStructure.Correction::applied).toList();
        assertEquals(1, fixed.size());
        assertEquals(2, fixed.getFirst().votes().size());
        assertEquals("b0", fixed.getFirst().barId());
        assertEquals(62, fixed.getFirst().note().pitch());
    }

    @Test void chromaConfirmsARepeatedSyntheticCell() {
        int rate = 8192;
        float[] samples = new float[rate * 6];
        int[] pitches = {60, 64, 67, 65, 60, 64, 67, 65, 66, 70, 73, 71};
        for (int i = 0; i < samples.length; i++) {
            double frequency = 440 * Math.pow(2, (pitches[i / (rate / 2)] - 69) / 12.0);
            samples[i] = (float) Math.sin(2 * Math.PI * frequency * i / rate);
        }
        var audio = ReconstructionRhythmAudio.extract(new ReferenceAudio(samples, rate));
        assertTrue(audio.similarity(0, 2, 2, 2, 4, java.util.function.DoubleUnaryOperator.identity()) > .98);
        assertTrue(audio.similarity(0, 4, 2, 2, 4, java.util.function.DoubleUnaryOperator.identity()) < .4);
        var bars = List.of(new ReconstructionStructure.Bar("a", 0, 2, true),
                new ReconstructionStructure.Bar("b", 2, 4, true),
                new ReconstructionStructure.Bar("c", 4, 6, true));
        var notes = new ArrayList<Note>();
        for (int bar = 0; bar < 3; bar++) {
            for (int i = 0; i < 8; i++) {
                double start = bar * 2 + i * .25 + .10;
                int pitch = 60 + i + (bar == 1 && i >= 5 ? 3 : 0);
                notes.add(new Note("audio-" + bar + "-" + i, 0, start, start + .10, pitch, 95, "midi", 1));
            }
        }
        var parts = List.of(new MidiScore.Part(0, "Lead", 81, false));
        var midiOnly = ReconstructionStructure.analyze(bars, notes, parts, null, true).phrases().getFirst();
        var withAudio = ReconstructionStructure.analyze(bars, notes, parts, audio, true).phrases().getFirst();
        assertEquals(List.of("a", "c"), midiOnly.instances().stream().flatMap(p -> p.bars().stream()).toList());
        assertEquals(List.of("a", "b"), withAudio.instances().stream().flatMap(p -> p.bars().stream()).toList());
        assertTrue(withAudio.audioSimilarity() > .98);
    }
}
