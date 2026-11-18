package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionPitchRefinementTest {
    @TempDir Path folder;

    @Test void splitsAHeldNoteWhenAnAlignedStemSustainsADifferentPitch() throws Exception {
        Path stem = wave("stem.wav", (sample, time) -> tone(time, time < .28 ? 60 : 64));
        MidiScore original = score(note(60, 0, 600_000));

        var refined = ReconstructionPitchRefinement.refine(original, null, Map.of(0, stem), 600_000);

        assertEquals(1, refined.changes().size());
        var notes = refined.score().notes();
        assertEquals(List.of(60, 64), notes.stream().map(MidiScore.Note::pitch).toList());
        assertEquals(notes.getFirst().endUs(), notes.getLast().startUs());
        assertTrue(Math.abs(notes.getLast().startUs() - 280_000) <= 25_000);
        assertEquals(600_000, notes.getLast().endUs());
        assertEquals(80, notes.getLast().velocity());
        assertEquals(1, original.notes().size());
    }

    @Test void preservesTheOriginalPitchAfterAnInteriorChangeAcrossAnAudioChunk() throws Exception {
        Path stem = wave("chunked.wav", 2, (sample, time) -> tone(time, time < .93 || time >= 1.12 ? 60 : 65));
        MidiScore original = score(note(60, 650_000, 1_450_000));

        var refined = ReconstructionPitchRefinement.refine(original, null, Map.of(0, stem), 1_450_000);

        assertEquals(List.of(60, 65, 60), refined.score().notes().stream().map(MidiScore.Note::pitch).toList());
        var change = refined.changes().getFirst();
        assertTrue(Math.abs(change.startUs() - 930_000) <= 25_000);
        assertTrue(Math.abs(change.endUs() - 1_120_000) <= 25_000);
        assertEquals(stem.getFileName().toString(), change.source());
        assertTrue(new com.google.gson.Gson().toJson(refined.changes()).contains(change.source()));
        assertTrue(change.stem());
        assertTrue(change.supportingFrames() >= 7);
    }

    @Test void recoversAShortOmissionBetweenCorroboratedNeighbors() throws Exception {
        Path stem = wave("gap.wav", (sample, time) -> tone(time, time < .22 ? 60 : time < .42 ? 62 : 64));
        MidiScore original = score(note(60, 0, 220_000), note(64, 420_000, 650_000));

        var refined = ReconstructionPitchRefinement.refine(original, stem, Map.of(), 650_000);

        assertEquals(List.of(note(60, 0, 220_000), note(62, 220_000, 420_000),
                note(64, 420_000, 650_000)), refined.score().notes());
    }

    @Test void leavesADenseMixAndUnpitchedStemAlone() throws Exception {
        Path mix = wave("mix.wav", (sample, time) -> tone(time, time < .28 ? 60 : 64) + tone(time, 67));
        Random random = new Random(7);
        Path noise = wave("noise.wav", (sample, time) -> time < .28
                ? tone(time, 60) : .35 * random.nextDouble(-1, 1));
        var first = new MidiScore.Part(0, "Lead", 0, false);
        var second = new MidiScore.Part(1, "Accompaniment", 0, false);
        MidiScore arrangement = new MidiScore(List.of(first, second),
                List.of(note(60, 0, 600_000), new MidiScore.Note(1, 67, 80, 0, 600_000)), List.of());

        assertEquals(arrangement, ReconstructionPitchRefinement.refine(arrangement, mix, Map.of(), 600_000).score());
        var single = score(note(60, 0, 600_000));
        assertEquals(single, ReconstructionPitchRefinement.refine(single, mix, Map.of(), 600_000).score());
        assertEquals(single, ReconstructionPitchRefinement.refine(single, null, Map.of(0, noise), 600_000).score());
    }

    @Test void leavesShortTransientsAndNotesCrossingTheAnalysisLimitAlone() throws Exception {
        Path shortChange = wave("transient.wav",
                (sample, time) -> tone(time, time < .28 || time >= .34 ? 60 : 64));
        Path change = wave("transition.wav", (sample, time) -> tone(time, time < .28 ? 60 : 64));
        MidiScore score = score(note(60, 0, 600_000));

        assertEquals(score, ReconstructionPitchRefinement.refine(score, null, Map.of(0, shortChange), 600_000).score());
        assertEquals(score, ReconstructionPitchRefinement.refine(score, null, Map.of(0, change), 400_000).score());
    }

    @Test void neverChangesAPercussionPart() throws Exception {
        Path stem = wave("drums.wav", (sample, time) -> tone(time, time < .28 ? 60 : 64));
        MidiScore drums = new MidiScore(List.of(new MidiScore.Part(0, "Drums", 0, true)),
                List.of(note(60, 0, 600_000)), List.of());
        assertEquals(drums, ReconstructionPitchRefinement.refine(drums, null, Map.of(0, stem), 600_000).score());
    }

    private static MidiScore score(MidiScore.Note... notes) {
        return new MidiScore(List.of(new MidiScore.Part(0, "Lead", 0, false)), List.of(notes), List.of());
    }

    private static MidiScore.Note note(int pitch, long start, long end) {
        return new MidiScore.Note(0, pitch, 80, start, end);
    }

    private static double tone(double seconds, int pitch) {
        double phase = 2 * Math.PI * 440 * Math.pow(2, (pitch - 69) / 12.0) * seconds;
        return .24 * Math.sin(phase) + .10 * Math.sin(phase * 2) + .05 * Math.sin(phase * 3);
    }

    @FunctionalInterface
    private interface Signal {
        double sample(int index, double seconds);
    }

    private Path wave(String name, Signal signal) throws Exception {
        return wave(name, 1, signal);
    }

    private Path wave(String name, int seconds, Signal signal) throws Exception {
        int rate = 16_000;
        int frames = rate * seconds;
        var data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < frames; index++) {
            data.putShort((short) Math.round(32767 * signal.sample(index, index / (double) rate)));
        }
        var path = folder.resolve(name);
        try (var audio = new AudioInputStream(new ByteArrayInputStream(data.array()),
                new AudioFormat(rate, 16, 1, true, false), frames)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, path.toFile());
        }
        return path;
    }
}
