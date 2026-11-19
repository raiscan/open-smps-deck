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
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionReverbTest {
    @TempDir Path directory;

    private final List<MidiScore.Note> notes = IntStream.range(0, 6).mapToObj(i -> new MidiScore.Note(
            0, 67 + i % 3, 86, 200_000 + i * 700_000L, 300_000 + i * 700_000L)).toList();

    @Test void estimatesDiffuseDecayFromSeveralIsolatedNotes() throws Exception {
        var estimate = detect("diffuse", notes);
        assertEquals("decay-supported", estimate.status());
        assertEquals(6, estimate.decayProbes());
        assertEquals(.62, estimate.decayTimeSeconds(), .08);
        assertTrue(estimate.tailAmplitudeRatio() > .25 && estimate.tailAmplitudeRatio() < .6);
    }

    @Test void distinguishesSilenceFromAmbiguousReleaseAndRepeats() throws Exception {
        assertEquals("dry-like", detect("dry", notes).status());
        for (String effect : List.of("release", "repeat")) {
            var estimate = detect(effect, notes);
            assertEquals("tail-unresolved", estimate.status(), effect);
            assertNull(estimate.decayTimeSeconds());
            assertNull(estimate.tailAmplitudeRatio());
        }
    }

    @Test void ignoresTailsOccupiedByLaterScoredNotesAndHonorsTheAudioLimit() throws Exception {
        var busy = new ArrayList<>(notes);
        for (var note : notes) {
            busy.add(new MidiScore.Note(1, 70, 85, note.endUs() + 140_000, note.endUs() + 200_000));
        }
        Path audio = fixture("diffuse");
        var score = new MidiScore(List.of(new MidiScore.Part(0, "Lead", 81, false),
                new MidiScore.Part(1, "Harmony", 81, false)), busy, List.of());
        var estimate = ReconstructionReverb.detect(score, audio, Map.of(), 5_000_000).get(0);
        assertEquals("insufficient-evidence", estimate.status());
        assertEquals(0, estimate.probes());
        Path stem = fixture("diffuse-stem");
        var isolated = ReconstructionReverb.detect(score, audio, Map.of(0, stem), 5_000_000).get(0);
        assertTrue(isolated.stem());
        assertEquals("decay-supported", isolated.status());
        var shared = ReconstructionReverb.detect(score, audio, Map.of(0, stem, 1, stem), 5_000_000).get(0);
        assertFalse(shared.stem());
        assertEquals("insufficient-evidence", shared.status());
        assertEquals("insufficient-evidence",
                ReconstructionReverb.detect(score, audio, Map.of(), 530_000).get(0).status());
    }

    private ReconstructionReverb.Estimate detect(String effect, List<MidiScore.Note> scoreNotes) throws Exception {
        Path audio = fixture(effect);
        var score = new MidiScore(List.of(new MidiScore.Part(0, "Lead", 81, false)), scoreNotes, List.of());
        return ReconstructionReverb.detect(score, audio, Map.of(), 5_000_000).get(0);
    }

    private Path fixture(String effect) throws Exception {
        int rate = 12_000;
        int onFrames = rate / 10;
        var random = new Random(481);
        var samples = new double[rate * 5];
        for (var note : notes) {
            int start = Math.toIntExact(note.startUs() * rate / 1_000_000);
            double phaseStep = 2 * Math.PI * 440 * Math.pow(2, (note.pitch() - 69) / 12.0) / rate;
            for (int i = 0; i < onFrames; i++) {
                double value = .3 * Math.sin(phaseStep * i);
                samples[start + i] += value;
                if (effect.equals("repeat")) samples[start + i + rate / 4] += value * .33;
            }
            for (int i = 0; i < rate * .33; i++) {
                double envelope = .16 * Math.exp(-i / (rate * .09));
                if (effect.equals("release")) {
                    samples[start + onFrames + i] += envelope * Math.sin(phaseStep * (i + onFrames));
                } else if (effect.startsWith("diffuse")) {
                    samples[start + onFrames + i] += envelope * (2 * random.nextDouble() - 1);
                }
            }
        }
        int bytes = samples.length * 2;
        var buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R','I','F','F'}).putInt(bytes + 36);
        buffer.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16);
        buffer.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        buffer.put(new byte[]{'d','a','t','a'}).putInt(bytes);
        for (double x : samples) buffer.putShort((short) Math.round(x * 32767));
        return Files.write(directory.resolve(effect + ".wav"), buffer.array());
    }
}
