package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionRepeatConsensusTest {
    @TempDir Path directory;

    @Test void fillsAnAttestedRepeatAndPreservesSilenceAndAPitchChange() throws Exception {
        var bars = new ArrayList<ReconstructionStructure.Bar>();
        var scoreNotes = new ArrayList<Note>();
        var performed = new ArrayList<Note>();
        int[] pitches = {60, 64, 67, 69};
        for (int bar = 0; bar < 9; bar++) {
            double start = bar * 2;
            bars.add(new ReconstructionStructure.Bar("b" + (bar + 1), start, start + 2, true));
            for (int slot = 0; slot < pitches.length; slot++) {
                double time = start + .2 + slot * .36 + (bar == 6 ? .06 : 0);
                var note = new Note("note-" + bar + "-" + slot, 0, time, time + .20,
                        pitches[slot], 96, "audio-transcribed", 1);
                if (slot != 3 || bar < 6) scoreNotes.add(note);
                if (slot != 3 || bar < 7) performed.add(note);
                if (slot == 3 && bar == 8)
                    performed.add(new Note("variation", 0, time, time + .20, 57, 96, "audio-transcribed", 1));
            }
        }
        Path wave = directory.resolve("stem.wav");
        WaveFile.write(wave, 18 * 60, sink -> {
            for (int frame = 0; frame < 18 * 60; frame++) {
                var samples = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
                double frameStart = frame / 60.0;
                var sounding = performed.stream().filter(n -> n.end() > frameStart && n.start() < frameStart + 1.0 / 60).toList();
                for (int i = 0; i < samples.length; i += 2) {
                    double time = frameStart + i / (2.0 * S3kRenderer.SAMPLE_RATE);
                    double value = 0;
                    for (var note : sounding) if (time >= note.start() && time < note.end()) {
                        double amplitude = note.start() > 12 && note.pitch() == 69 ? 850 : 6500;
                        double phase = Math.TAU * EvidenceSpectrum.hz(note.pitch()) * time;
                        value += amplitude * Math.sin(phase);
                        if (note.id().equals("variation")) value += 1400 * Math.sin(2 * phase);
                    }
                    samples[i] = samples[i + 1] = (short) value;
                }
                sink.accept(samples);
            }
        });
        var provenance = new ReconstructionAudioOnly.Provenance("audio-only", null, null,
                Map.of(0, new ReconstructionAudioOnly.Stem("synth", "lead", "lead")), List.of(), null);
        var input = new ReconstructionStages.Inputs(null, wave.toString(), "unused", "Repeat fixture", 30,
                false, false, false, false, Map.of(0, wave.toString()), null, 0, null, false,
                null, 0, null, null, null, provenance);
        var source = new MidiScore(List.of(new MidiScore.Part(0, "synth", 80, false)), List.of(), List.of());
        var score = new ReconstructionStages.Score(VERSION, 18_000_000, 1080, 60, input, bars,
                source, Map.of(), scoreNotes, List.of(), List.of(), null, null);

        var structure = ReconstructionStages.structure(score);
        ReconstructionChecks.structure(structure, score);
        var fills = structure.notes().stream().filter(n -> ReconstructionRepeatConsensus.SOURCE.equals(n.source())).toList();
        assertEquals(1, fills.size(), () -> "Unexpected recovered notes: " + fills);
        assertTrue(structure.corrections().isEmpty());
        var fill = fills.getFirst();
        assertEquals(69, fill.pitch());
        assertEquals(13.34, fill.start(), .025);
        var evidence = structure.repeatNotes().get(fill.id());
        assertEquals(6, evidence.votes().size());
        assertNotNull(evidence.stemDb());
        assertEquals(structure, ReconstructionStages.structure(score));
    }
}
