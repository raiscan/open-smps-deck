package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionFastRunsTest {
    @Test void recoversOneFrameStepsInOrder() {
        var found = ReconstructionFastRuns.detect(tone(.30, t -> {
            int step = (int) Math.floor((t - .05) * 60 + 1e-8);
            return step >= 0 && step < 6 ? 72 + step : Double.NaN;
        }));
        assertEquals(1, found.size(), found.toString());
        var run = found.getFirst();
        assertEquals(List.of(72, 73, 74, 75, 76, 77), run.steps().stream()
                .map(ReconstructionFastRuns.Step::pitch).toList());
        for (int i = 0; i < run.steps().size(); i++)
            assertEquals(.05 + i / 60.0, run.steps().get(i).start(), .008);
    }

    @Test void rejectsGlidesAndWidePeriodicVibrato() {
        for (double duration : List.of(.083, .17, .33, .55)) {
            assertTrue(ReconstructionFastRuns.detect(tone(.80, t -> 72 + 6 * Math.clamp((t - .1) / duration, 0, 1)))
                    .isEmpty(), "Glide over " + duration);
        }
        assertTrue(ReconstructionFastRuns.detect(tone(.80, t -> 75 + 2 * Math.sin(Math.TAU * 6 * t))).isEmpty());
    }

    @Test void reservesOneLaneAndKeepsTheShortestFitInOrder() {
        var steps = List.of(new ReconstructionFastRuns.Step(72, .10, .12),
                new ReconstructionFastRuns.Step(73, .12, .123),
                new ReconstructionFastRuns.Step(74, .123, .135),
                new ReconstructionFastRuns.Step(75, .135, .15));
        var held = new ReconstructionStages.Note("held", 0, .08, .20, 72, 90, "midi", 1);
        var notes = new java.util.ArrayList<>(List.of(held));
        ReconstructionFastRuns.reconcile(notes, 0, List.of(new ReconstructionFastRuns.Figure(steps, .95)));
        var figure = notes.stream().filter(note -> note.run() != null).findFirst().orElseThrow();
        assertEquals(steps, figure.run());
        var decisions = List.of(new ReconstructionRoles.Decision(figure, ReconstructionRoles.Role.melody,
                        ReconstructionFastRuns.REASON, null),
                new ReconstructionRoles.Decision(new ReconstructionStages.Note("competing", 0, .12, .19, 80, 90, "midi", 1),
                        ReconstructionRoles.Role.melody, null, null));
        var parts = List.of(new MidiScore.Part(0, "lead", 81, false));
        var result = ReconstructionAllocation.allocate(new ReconstructionRoles.Result(1, 0, decisions, null),
                parts, 60, false);
        assertEquals(List.of(0, 2, 3), result.fastRuns().getFirst().retainedSteps());
        assertEquals("fast-run-subframe-merge", result.fastRuns().getFirst().reason());
        var placed = result.notes().stream().filter(entry -> entry.lane() != null).toList();
        assertEquals(List.of("FM1", "FM1", "FM1"), placed.stream().map(ReconstructionAllocation.Entry::lane).toList());
        assertEquals(List.of(72, 74, 75), placed.stream().map(entry -> entry.note().pitch()).toList());
        assertEquals(List.of(6, 7, 8), placed.stream().map(ReconstructionAllocation.Entry::startTick).toList());
        assertEquals(List.of(7, 8, 9), placed.stream().map(ReconstructionAllocation.Entry::endTick).toList());
    }

    @Test void tracksEnvelopeRetriggersAtAConstantPitch() {
        for (int frames = 2; frames <= 6; frames++) {
            double period = frames / 60.0;
            for (boolean staccato : List.of(false, true)) {
                int rate = 12_000;
                var samples = new float[rate * 2];
                double start = .05;
                for (int i = 0; i < samples.length; i++) {
                    double time = i / (double) rate;
                    if (time < start || time >= start + 10 * period) continue;
                    double age = (time - start) % period;
                    double level = staccato && age > period * .58 ? 0 : .08 + .22 * Math.exp(-age / .013);
                    samples[i] = (float) (level * (Math.sin(Math.TAU * 440 * time) + .3 * Math.sin(Math.TAU * 880 * time)));
                }
                var figures = ReconstructionRetriggers.find(new ReferenceAudio(samples, rate), 69, 0, 1.4);
                assertEquals(1, figures.size(), frames + " frames, staccato " + staccato + " " + figures);
                var steps = figures.getFirst().steps();
                assertEquals(10, steps.size(), steps.toString());
                for (int i = 0; i < steps.size(); i++) {
                    assertEquals(69, steps.get(i).pitch());
                    assertEquals(start + i * period, steps.get(i).start(), .009);
                }
            }
        }
        assertTrue(ReconstructionRetriggers.find(tone(.8, t -> 69 + .15 * Math.sin(Math.TAU * 6 * t)),
                69, 0, .8).isEmpty());
    }

    @Test void keepsCoMovingThirdAndRejectsAStaticPad() {
        var steps = new java.util.ArrayList<ReconstructionFastRuns.Step>();
        for (int i = 0; i < 5; i++)
            steps.add(new ReconstructionFastRuns.Step(72 + 2 * i, .10 + .08 * i, .10 + .08 * (i + 1)));
        var lead = tone(.6, t -> t < .10 || t >= .50 ? Double.NaN : 72 + 2 * Math.floor((t - .10) / .08));
        var harmony = tone(.6, t -> t < .10 || t >= .50 ? Double.NaN : 76 + 2 * Math.floor((t - .10) / .08));
        var pad = tone(.6, t -> 65);
        for (int i = 0; i < lead.samples().length; i++)
            lead.samples()[i] += .8f * harmony.samples()[i] + .8f * pad.samples()[i];
        var figure = new ReconstructionStages.Note("figure", 0, steps.getFirst().start(), steps.getLast().end(),
                72, 90, ReconstructionFastRuns.REASON, .95, null, steps);
        var voices = ReconstructionFigureVoices.find(lead, figure, List.of());
        assertEquals(List.of(4, 0), voices.stream().map(ReconstructionFigureVoices.Voice::interval).toList());
        assertEquals("lead", voices.getFirst().role());
        assertEquals("harmony", voices.getLast().role());
    }

    @Test void preservesExplicitOctaveAttacksOverAHeldPartial() {
        var steps = new java.util.ArrayList<ReconstructionFastRuns.Step>();
        var notes = new java.util.ArrayList<ReconstructionStages.Note>();
        notes.add(new ReconstructionStages.Note("held", 0, .10, .61, 56, 90, "transcriber", 1));
        for (int i = 0; i < 6; i++) {
            double start = .10 + .08 * i;
            steps.add(new ReconstructionFastRuns.Step(56, start, start + .08));
            notes.add(new ReconstructionStages.Note("attack-" + i, 0, start, start + .064, 68, 90, "transcriber", 1));
        }
        var original = List.copyOf(notes);
        ReconstructionFastRuns.reconcile(notes, 0, List.of(new ReconstructionFastRuns.Figure(
                steps, .95, ReconstructionRetriggers.REASON)));
        assertEquals(original, notes);
    }

    private static ReferenceAudio tone(double duration, DoubleUnaryOperator pitch) {
        int rate = 12_000;
        var samples = new float[(int) (rate * duration)];
        double phase = 0;
        for (int i = 0; i < samples.length; i++) {
            double p = pitch.applyAsDouble(i / (double) rate);
            if (!Double.isFinite(p)) continue;
            phase += Math.TAU * 440 * Math.pow(2, (p - 69) / 12) / rate;
            samples[i] = (float) (.3 * Math.sin(phase));
        }
        return new ReferenceAudio(samples, rate);
    }
}
