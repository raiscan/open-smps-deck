package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Occurrence;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconstructionKickTest {
    @TempDir Path directory;

    @Test void corroboratedLowAttacksPrioritizeAStandaloneKickOverAnOtherwiseExactHit() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var recipe = new Recipe(12, null, 127, 60, null, null, List.of(), List.of());
        var kick = builder.variant(builder.asset(Kind.DAC_SAMPLE, "Standalone Kick", pulse(0.10)),
                "Standalone Kick", recipe, slot(0x86));
        var hybrid = builder.variant(builder.asset(Kind.DAC_SAMPLE, "Hybrid Hit", pulse(0.08)),
                "Hybrid Hit", recipe, slot(0xC0));
        var catalogue = builder.build();
        var sample = directory.resolve("sample.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(CataloguePlayback.matchProbe(catalogue, hybrid, 36, 11)),
                sample, 12);
        var hit = ReferenceAudio.read(sample, 0, 0.18);
        var wave = fixture(hit);
        var gmKick = List.of(new MidiScore.Note(1, 36, 100, 200_000, 380_000),
                new MidiScore.Note(1, 36, 100, 600_000, 780_000));
        var nonKick = List.of(new MidiScore.Note(1, 43, 100, 200_000, 380_000),
                new MidiScore.Note(1, 43, 100, 600_000, 780_000));
        assertEquals(kick, choice(catalogue, wave, gmKick));
        assertEquals(hybrid, choice(catalogue, wave, gmKick.subList(0, 1)));
        assertEquals(hybrid, choice(catalogue, wave, nonKick));
    }

    private String choice(InstrumentCatalogue catalogue, Path wave, List<MidiScore.Note> notes) throws Exception {
        var score = new MidiScore(List.of(new MidiScore.Part(1, "Drums", 0, true)), notes, List.of());
        return ReconstructionVoices.select(catalogue, wave, Map.of(), score, notes, 1_000_000, Kind.FM_VOICE)
                .get(new ReconstructionVoices.Key(1, notes.getFirst().pitch())).variantId();
    }

    private Path fixture(ReferenceAudio hit) throws Exception {
        int rate = hit.sampleRate();
        var samples = ByteBuffer.allocate(2 * rate).order(ByteOrder.LITTLE_ENDIAN);
        for (int start : List.of(rate / 5, 3 * rate / 5)) {
            for (int i = 0; i < hit.samples().length; i++)
                samples.putShort(2 * (start + i), (short) Math.round(32767 * hit.samples()[i]));
        }
        var output = directory.resolve("two-hits.wav");
        try (var audio = new AudioInputStream(new ByteArrayInputStream(samples.array()),
                new AudioFormat(rate, 16, 1, true, false), rate)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
        return output;
    }

    private static Occurrence slot(int slot) {
        return new Occurrence("fixture.gen", "S3K", CatalogueMatcher.LOCKED_ON_S3K,
                "Test DAC", 1, slot, null, null, false, "dac");
    }

    private static byte[] pulse(double phaseStep) {
        var bytes = new byte[1_800];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Math.round(128 + 110 * Math.exp(-i / 200.0) * Math.sin(i * phaseStep));
        return bytes;
    }
}
