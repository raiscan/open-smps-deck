package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconstructionVibratoTest {
    private static final int SAMPLE_RATE = 16_000;
    private static final long END_US = 3_000_000;
    private static final List<MidiScore.Note> NOTES = List.of(
            new MidiScore.Note(0, 55, 96, 100_000, 1_250_000),
            new MidiScore.Note(0, 78, 96, 1_550_000, 2_800_000));
    private static final MidiScore.Part LEAD = new MidiScore.Part(0, "Lead", 81, false);

    @TempDir Path directory;

    @Test void estimatesRateAndPeakDepthFromIndependentHeldNotesInMonoStem() throws Exception {
        Path stem = fixture("vibrato", Shape.VIBRATO, Shape.VIBRATO);
        var notes = new ArrayList<>(NOTES);
        notes.add(new MidiScore.Note(1, 67, 96, 0, END_US));
        var score = new MidiScore(List.of(LEAD, new MidiScore.Part(1, "Pad", 89, false)), notes, List.of());

        var estimate = ReconstructionVibrato.detect(score, null, Map.of(0, stem), END_US).get(0);
        assertNotNull(estimate);
        assertEquals(6.15, estimate.rateHz(), .1);
        assertEquals(24, estimate.depthCents(), 2);
        assertEquals(2, estimate.supportingNotes());
        assertEquals("vibrato.wav", estimate.source());
        assertTrue(estimate.stem());

        assertTrue(ReconstructionVibrato.detect(score, stem, Map.of(), END_US).isEmpty());
        assertTrue(ReconstructionVibrato.detect(score, null, Map.of(0, stem), 2_700_000).isEmpty());
    }

    @Test void leavesStaticDetuningSlidesAndBeatingUnresolved() throws Exception {
        var score = new MidiScore(List.of(LEAD), NOTES, List.of());
        for (var shape : List.of(Shape.DETUNED, Shape.SLIDE, Shape.BEATING, Shape.TREMOLO)) {
            Path mix = fixture(shape.name(), shape, shape);
            assertTrue(ReconstructionVibrato.detect(score, mix, Map.of(), END_US).isEmpty(), shape.name());
        }
    }

    @Test void leavesSingleAndIncompatibleNoteEvidenceUnresolved() throws Exception {
        var score = new MidiScore(List.of(LEAD), NOTES, List.of());
        Path single = fixture("single", Shape.VIBRATO, Shape.DETUNED);
        Path incompatible = fixture("incompatible", Shape.VIBRATO, Shape.FAST_VIBRATO);
        assertTrue(ReconstructionVibrato.detect(score, single, Map.of(), END_US).isEmpty());
        assertTrue(ReconstructionVibrato.detect(score, incompatible, Map.of(), END_US).isEmpty());
    }

    @Test void detectsQuietOpeningAndDepthChangeFromStemRidge() throws Exception {
        var note = new ReconstructionStages.Note("held", 0, .1, 1.25, 55, 96, "midi", 1);
        var tracker = new ReconstructionBends.Tracker(ReconstructionLeadAudio.readMono(
                fixture("staged", Shape.STAGED, Shape.DETUNED), 3));
        var stages = ReconstructionVibratoEnvelope.detect(note.start(), tracker.vibratoTrace(note));
        assertEquals(2, stages.size(), stages.toString());
        assertEquals(.41, stages.getFirst().time(), .045);
        assertEquals(.76, stages.getLast().time(), .08);
        assertEquals(6.15, stages.getLast().hz(), .7);
        assertTrue(stages.getLast().depth() > 1.75 * stages.getFirst().depth());

        var plain = new ReconstructionStages.Note("plain", 0, 1.55, 2.80, 78, 96, "midi", 1);
        assertTrue(ReconstructionVibratoEnvelope.detect(plain.start(), tracker.vibratoTrace(plain)).isEmpty());
    }

    private enum Shape { VIBRATO, FAST_VIBRATO, STAGED, DETUNED, SLIDE, BEATING, TREMOLO }

    private Path fixture(String name, Shape first, Shape second) throws Exception {
        var samples = new double[(int) (END_US * SAMPLE_RATE / 1_000_000)];
        play(samples, NOTES.getFirst(), first);
        play(samples, NOTES.getLast(), second);
        int bytes = samples.length * 2;
        var buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(bytes + 36);
        buffer.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
        buffer.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
                .putShort((short) 2).putShort((short) 16);
        buffer.put(new byte[]{'d', 'a', 't', 'a'}).putInt(bytes);
        for (double sample : samples) {
            buffer.putShort((short) Math.round(32767 * sample));
        }
        return Files.write(directory.resolve(name + ".wav"), buffer.array());
    }

    private static void play(double[] samples, MidiScore.Note note, Shape shape) {
        int start = (int) (note.startUs() * SAMPLE_RATE / 1_000_000);
        int length = (int) ((note.endUs() - note.startUs()) * SAMPLE_RATE / 1_000_000);
        double hz = 440 * Math.pow(2, (note.pitch() - 69) / 12.0);
        double rate = shape == Shape.FAST_VIBRATO ? 8.2 : 6.15;
        double phase = 0;
        for (int i = 0; i < length; i++) {
            double time = i / (double) SAMPLE_RATE;
            double movement = Math.sin(2 * Math.PI * rate * time + note.pitch());
            double cents = switch (shape) {
                case VIBRATO, FAST_VIBRATO -> 24 * movement + 16;
                case STAGED -> time < .31 ? 0
                        : (time < .66 ? 18 : 56) * Math.sin(Math.TAU * rate * (time - .31));
                case DETUNED -> 33;
                case SLIDE -> -55 + 110 * (i / (double) length);
                case BEATING, TREMOLO -> 0;
            };
            phase += 2 * Math.PI * hz * Math.pow(2, cents / 1_200) / SAMPLE_RATE;
            double value = .42 * Math.sin(phase) + .13 * Math.sin(2 * phase) + .08 * Math.sin(3 * phase);
            if (shape == Shape.BEATING) {
                value += .15 * Math.sin(2 * Math.PI * (hz + rate) * time);
            } else if (shape == Shape.TREMOLO) {
                value *= .7 + .3 * movement;
            }
            samples[start + i] = value;
        }
    }
}
