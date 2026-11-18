package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionVoicesTest {
    @TempDir Path directory;

    @Test void choosesFromStemsAndKeepsMelodicPartsSeparateFromDrumPitches() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        for (var voice : DemoProject.create().voices()) {
            byte[] bytes = new byte[voice.bytes().size()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = voice.bytes().get(i).byteValue();
            var id = builder.asset(Kind.FM_VOICE, voice.name(), bytes);
            builder.variant(id, voice.name(), Recipe.plain(), null);
        }
        byte[] pulse = new byte[2_000];
        byte[] chirp = new byte[pulse.length];
        for (int i = 0; i < pulse.length; i++) {
            double fade = 1.0 - (double) i / pulse.length;
            pulse[i] = (byte) Math.round(128 + 100 * fade * Math.sin(i * 0.05));
            chirp[i] = (byte) Math.round(128 + 100 * fade * Math.sin(i * i * 0.001));
        }
        var recipe = new Recipe(12, null, 127, 60, null, null, List.of(), List.of());
        String expectedDrum = builder.variant(builder.asset(Kind.DAC_SAMPLE, "Pulse", pulse), "Pulse", recipe, null);
        builder.variant(builder.asset(Kind.DAC_SAMPLE, "Chirp", chirp), "Chirp", recipe, null);
        var catalogue = builder.build();
        String expectedVoice = catalogue.search(new InstrumentCatalogue.Query(null, Kind.FM_VOICE, null, null,
                null, null, 0, 2)).items().getFirst().variant().id();

        Path stem = render(catalogue, expectedVoice, 36, "stem.wav");
        Path mix = render(catalogue, expectedDrum, 35, "mix.wav");
        var parts = List.of(new MidiScore.Part(163, "Bass", 33, false),
                new MidiScore.Part(1, "Drums", 0, true), new MidiScore.Part(164, "Late", 0, false));
        var notes = List.of(new MidiScore.Note(163, 36, 100, 0, 300_000),
                new MidiScore.Note(1, 35, 100, 0, 180_000),
                new MidiScore.Note(164, 60, 100, 900_000, 1_100_000));

        var score = new MidiScore(parts, notes, List.of());
        var choices = ReconstructionVoices.select(catalogue, mix, Map.of(163, stem), score,
                notes, 1_100_000, Kind.FM_VOICE);
        var voice = choices.get(new ReconstructionVoices.Key(163, null));
        var drum = choices.get(new ReconstructionVoices.Key(1, 35));
        var late = choices.get(new ReconstructionVoices.Key(164, null));
        assertEquals(expectedVoice, voice.variantId());
        assertEquals("stem", voice.probes().getFirst().source());
        assertEquals(0, voice.probes().getFirst().overlappingNotes());
        assertEquals(expectedDrum, drum.variantId());
        assertEquals(12, drum.sampleRate());
        assertEquals("mix", drum.probes().getFirst().source());
        assertEquals(1, drum.probes().getFirst().overlappingNotes());
        assertEquals("weak-evidence", drum.status());
        assertNull(late.variantId());
        assertEquals("outside-audio", late.probes().getFirst().status());
    }

    private Path render(InstrumentCatalogue catalogue, String variant, int midi, String filename) throws Exception {
        Path output = directory.resolve(filename);
        var probe = CataloguePlayback.matchProbe(catalogue, variant, midi, 18);
        WaveFile.render(ProjectCompiler.compileWithAssets(probe), output, 30);
        return output;
    }
}
