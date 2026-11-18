package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CatalogueMatcherTest {
    @TempDir Path directory;

    @Test void ranksAReferenceAtItsRequestedPitchThroughThePackagedPublicCommand() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var voices = DemoProject.create().voices();
        for (var voice : voices) {
            byte[] bytes = new byte[voice.bytes().size()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = voice.bytes().get(i).byteValue();
            String id = builder.asset(Kind.FM_VOICE, voice.name(), bytes);
            builder.variant(id, voice.name(), Recipe.plain(), null);
        }
        var index = builder.build();
        var correct = index.variants().getFirst();
        Path catalogue = directory.resolve("index.json");
        index.save(catalogue);
        var probe = CataloguePlayback.matchProbe(index, correct.id(), 72, 18);
        Path reference = directory.resolve("reference.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(probe), reference, 30);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"catalog", "match", catalogue.toString(), reference.toString(),
                        "--kind", "FM_VOICE", "--midi", "72", "--gate-ticks", "18", "--limit", "2"},
                new PrintStream(output), new PrintStream(errors));
        assertEquals(0, code, errors.toString(StandardCharsets.UTF_8));
        var result = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("s3k-ntsc-match-v1", result.get("featureProfile").getAsString());
        assertEquals(2, result.get("compared").getAsInt());
        var rows = result.getAsJsonArray("matches");
        var best = rows.get(0).getAsJsonObject();
        assertEquals(correct.id(), best.get("variantId").getAsString());
        assertEquals(0, best.getAsJsonObject("distance").get("total").getAsDouble(), 1e-8);
        assertTrue(rows.get(1).getAsJsonObject().getAsJsonObject("distance").get("total").getAsDouble() > 0);

        Path replay = directory.resolve("replay.wav");
        int audition = Main.run(new String[]{"catalog", "audition", catalogue.toString(), correct.id(), replay.toString(),
                        "--midi", "72", "--gate-ticks", "18", "--frames", "30"},
                new PrintStream(output), new PrintStream(errors));
        assertEquals(0, audition, errors.toString(StandardCharsets.UTF_8));
        assertArrayEquals(Files.readAllBytes(reference), Files.readAllBytes(replay));
    }

    @Test void keepsAnAudibleDacHitNearTheTopWithGainChangeAndLeadingSilence() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        byte[] low = new byte[1_500];
        byte[] bright = new byte[1_500];
        var random = new Random(91);
        for (int i = 0; i < low.length; i++) {
            double fade = 1 - (double) i / low.length;
            low[i] = (byte) Math.round(128 + 100 * fade * Math.sin(i * 0.06));
            bright[i] = (byte) Math.round(128 + 100 * fade * (2 * random.nextDouble() - 1));
        }
        var recipe = new Recipe(12, null, 127, 60, null, null, List.of(), List.of());
        String expected = builder.variant(builder.asset(Kind.DAC_SAMPLE, "Low hit", low), "Low hit", recipe, null);
        builder.variant(builder.asset(Kind.DAC_SAMPLE, "Bright hit", bright), "Bright hit", recipe, null);
        var catalogue = builder.build();
        var project = CataloguePlayback.audition(catalogue, expected);
        int[] at = {1_200};
        float[] query = new float[25 * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION + at[0]];
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), 25, pcm -> {
            for (int i = 0; i < pcm.length; i += 2)
                query[at[0]++] = (pcm[i] + pcm[i + 1]) * (0.4f / 65536.0f);
        });
        var result = CatalogueMatcher.match(catalogue, new ReferenceAudio(query, S3kRenderer.SAMPLE_RATE),
                new CatalogueMatcher.Options(Kind.DAC_SAMPLE, "one-shot", null, 60, null, 1));
        assertEquals(2, result.compared());
        assertEquals(expected, result.matches().getFirst().variantId());
    }
}
