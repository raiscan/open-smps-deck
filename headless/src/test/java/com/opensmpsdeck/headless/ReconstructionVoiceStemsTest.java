package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionVoiceProposal.Attack;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconstructionVoiceStemsTest {
    @Test void separatesOneMidiPartAcrossDistinctStems(@TempDir Path work) throws Exception {
        var notes = phrase();
        Path first = work.resolve("first.wav");
        Path second = work.resolve("second.wav");
        write(first, notes.subList(0, 8), 0);
        write(second, notes.subList(8, 16), 0);
        var audio = stems(notes, Map.of(0, first.toString(), 1, second.toString()));
        var split = audio.split(notes);
        assertEquals(List.of("stem-part-0", "stem-part-1"), split.stream()
                .map(ReconstructionVoiceProposal.Group::suffix).toList());
        assertEquals(notes.subList(8, 16), split.getLast().attacks());
        assertEquals("second.wav", split.getLast().attributedStem());
    }

    @Test void separatesDifferentHarmonicProfilesAtTheSamePitches(@TempDir Path work) throws Exception {
        var notes = phrase();
        Path changing = work.resolve("changing.wav");
        Path steady = work.resolve("steady.wav");
        write(changing, notes, .8);
        write(steady, notes, 0);
        var split = stems(notes, Map.of(0, changing.toString())).split(notes);
        assertEquals(List.of("timbre-mellow", "timbre-bright"), split.stream()
                .map(ReconstructionVoiceProposal.Group::suffix).toList());
        assertEquals(notes.subList(0, 8), split.getFirst().attacks());
        assertEquals(notes.subList(8, 16), split.getLast().attacks());
        assertEquals(List.of(), stems(notes, Map.of(0, steady.toString())).split(notes));
    }

    private static List<Attack> phrase() {
        var notes = new ArrayList<Attack>();
        for (int i = 0; i < 16; i++) {
            double at = i * .125;
            var note = new ReconstructionStages.Note("tone-" + i, 0, at, at + .1, 65 + i % 2, 96, "midi", 1);
            notes.add(new Attack(note, Role.melody, note.start(), note.end(), i < 8 ? "first" : "second"));
        }
        return notes;
    }

    private static ReconstructionVoiceStems stems(List<Attack> notes, Map<Integer, String> paths) {
        var inputs = new ReconstructionStages.Inputs("source.mid", "mix.wav", "index.json", "Fixture",
                30, false, false, false, false, paths, null, 7.5, null, null);
        var parts = List.of(new MidiScore.Part(0, "Synth", 81, false),
                new MidiScore.Part(1, "Keys", 0, false));
        var score = new ReconstructionStages.Score(1, 2_000_000, 120, 60, inputs,
                List.of(new ReconstructionStructure.Bar("b1", 0, 2, false)),
                new MidiScore(parts, List.of(), List.of()), Map.of(), notes.stream().map(Attack::note).toList(),
                List.of(), List.of(), null, null);
        return new ReconstructionVoiceStems(score, Map.of(0, notes));
    }

    private static void write(Path path, List<Attack> notes, double secondHarmonic) throws IOException {
        int rate = S3kRenderer.SAMPLE_RATE;
        int samples = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        var pcm = new short[rate * 2 * 2];
        for (var attack : notes) {
            double hz = ReconstructionLeadAudio.hz(attack.note().pitch());
            double harmonic = attack.section().equals("second") ? secondHarmonic : 0;
            for (int i = (int) (attack.onset() * rate); i < (int) (attack.end() * rate); i++) {
                double phase = Math.TAU * hz * (i / (double) rate - attack.onset());
                short value = (short) (8000 * (Math.sin(phase) + harmonic * Math.sin(2 * phase)));
                pcm[2 * i] = pcm[2 * i + 1] = value;
            }
        }
        WaveFile.write(path, 120, sink -> {
            for (int i = 0; i < pcm.length; i += samples * 2)
                sink.accept(java.util.Arrays.copyOfRange(pcm, i, i + samples * 2));
        });
    }
}
