package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceRetrievalTest {
    @Test void aNotesExposureIsTheShareOfItsHarmonicsNoOtherChannelTouches() {
        var alone = new VoiceRetrieval.Truth("FM1", 0, 1, 60, "a");
        var octave = new VoiceRetrieval.Truth("FM2", 2, 3, 72, "b");
        var under = new VoiceRetrieval.Truth("FM3", 2, 3, 60, "c");
        var later = new VoiceRetrieval.Truth("PSG1", 5, 6, 67, null);
        var exposure = VoiceRetrieval.exposure(List.of(alone, octave, under, later));

        assertEquals(1, exposure.get(alone), 1e-9);
        assertEquals(1, exposure.get(later), 1e-9);
        // The lower note's harmonics cover every harmonic of the octave above it.
        assertTrue(exposure.get(octave) < VoiceRetrieval.EXPOSED);
        assertTrue(exposure.get(under) > .3 && exposure.get(under) < .8);
    }

    @Test void theKindReadingTellsAHarmonicToneFromNoise(@TempDir Path directory) throws Exception {
        int frames = 1500, perFrame = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        var tone = new short[frames * perFrame * 2];
        var noise = new short[tone.length];
        var notes = new ArrayList<VoiceNoteDecode.Note>();
        var random = new java.util.Random(7);
        for (int i = 0; i < 40; i++) {
            int pitch = 60 + i % 12;
            double start = .5 + i * .6, hz = 440 * Math.pow(2, (pitch - 69) / 12.0);
            notes.add(new VoiceNoteDecode.Note("n" + i, start, start + .4, pitch));
            int first = (int) (start * S3kRenderer.SAMPLE_RATE), length = (int) (.4 * S3kRenderer.SAMPLE_RATE);
            for (int at = 0; at < length; at++) {
                double time = at / (double) S3kRenderer.SAMPLE_RATE, value = 0;
                for (int harmonic = 1; harmonic <= 5; harmonic++) value += Math.sin(Math.TAU * harmonic * hz * time) / harmonic;
                tone[2 * (first + at)] = tone[2 * (first + at) + 1] = (short) (5000 * value);
                noise[2 * (first + at)] = noise[2 * (first + at) + 1] = (short) (8000 * random.nextGaussian() / 3);
            }
        }
        var decoder = new VoiceNoteDecode(null, null);

        var synthetic = VoiceRegime.kind(decoder, notes, write(directory.resolve("tone.wav"), tone, frames));
        var acoustic = VoiceRegime.kind(decoder, notes, write(directory.resolve("noise.wav"), noise, frames));
        var bass = VoiceRegime.kind(decoder, notes.stream().map(note -> new VoiceNoteDecode.Note(note.id(), note.start(),
                note.end(), note.pitch() - 24)).toList(), directory.resolve("tone.wav"));

        assertEquals("synthetic", synthetic.kind());
        assertTrue(synthetic.offHarmonic() <= VoiceRegime.SYNTHETIC_OFF_HARMONIC);
        assertEquals("acoustic", acoustic.kind());
        // A bass part is not classified, whatever it measures.
        assertEquals("undetermined", bass.kind());
    }

    @Test void theRegimeReportListsAStemItCannotUseAndNeverFails(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("fixture.mid"), wav = dir.resolve("mix.wav"), stem = dir.resolve("stem.wav");
        Path index = dir.resolve("catalogue.json");
        var source = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        var track = source.createTrack();
        for (int[] event : new int[][]{{60, 0, 420}, {64, 480, 900}, {67, 960, 1380}}) {
            track.add(new javax.sound.midi.MidiEvent(new javax.sound.midi.ShortMessage(
                    javax.sound.midi.ShortMessage.NOTE_ON, 0, event[0], 90), event[1]));
            track.add(new javax.sound.midi.MidiEvent(new javax.sound.midi.ShortMessage(
                    javax.sound.midi.ShortMessage.NOTE_OFF, 0, event[0], 0), event[2]));
        }
        javax.sound.midi.MidiSystem.write(source, 0, midi.toFile());
        var builder = new InstrumentCatalogue.Builder();
        var patch = com.opensmpsdeck.project.DemoProject.create().voices().getFirst();
        var bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String first = builder.variant(builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "First", bytes), "First",
                InstrumentCatalogue.Recipe.plain(), ReconstructionCatalogueFixture.stock());
        var catalogue = builder.build();
        catalogue.save(index);
        WaveFile.render(com.opensmpsdeck.project.ProjectCompiler.compileWithAssets(
                CataloguePlayback.matchProbe(catalogue, first, 68, 24)), wav, 180);
        java.nio.file.Files.copy(wav, stem);

        // A stem that is the mix under another name says nothing about the part alone.
        Path alias = java.nio.file.Files.createSymbolicLink(dir.resolve("alias.wav"), wav);
        Path shared = dir.resolve("shared");
        run(midi, wav, index, shared, alias);
        var aliased = VoiceRegime.report(shared).getAsJsonObject("parts").getAsJsonObject("part-0");
        assertTrue(aliased.has("unavailable"), aliased.toString());

        // A stem of its own is measured. Three notes are too few for a label.
        Path own = dir.resolve("own");
        run(midi, wav, index, own, stem);
        var measured = VoiceRegime.report(own).getAsJsonObject("parts").getAsJsonObject("part-0");
        assertEquals("unmeasured", measured.get("fit").getAsString(), measured.toString());
        assertEquals("undetermined", measured.getAsJsonObject("kind").get("kind").getAsString());

        // The same stem, damaged afterwards, is reported and does not stop the report.
        java.nio.file.Files.writeString(stem, "not audio");
        var report = VoiceRegime.report(own);
        assertTrue(report.has("tentative"));
        assertTrue(report.getAsJsonObject("parts").getAsJsonObject("part-0").has("unavailable"), report.toString());
    }

    private static void run(Path midi, Path wav, Path index, Path work, Path stem) throws Exception {
        var stderr = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.PrintStream(new java.io.ByteArrayOutputStream()); var err = new java.io.PrintStream(stderr)) {
            assertEquals(0, Main.run(new String[]{"reconstruct", "run", midi.toString(), wav.toString(), index.toString(),
                    work.toString(), "--seconds", "3", "--stem", "0=" + stem}, out, err), stderr.toString());
        }
    }

    private static Path write(Path file, short[] audio, int frames) throws Exception {
        int width = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2;
        WaveFile.write(file, frames, sink -> {
            for (int frame = 0; frame < frames; frame++)
                sink.accept(java.util.Arrays.copyOfRange(audio, frame * width, (frame + 1) * width));
        });
        return file;
    }
}
