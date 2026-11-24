package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.TargetGame;
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

    @Test void staleStockVoteIsAbsentWhenTheCatalogueChanges() {
        var catalogue = new InstrumentCatalogue.Builder().build();
        assertTrue(catalogue.stockVariant("missing-variant", TargetGame.S3K).isEmpty());
    }

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

    @Test void rawReconstructionMatchesOnlyVariantsForTheRenderingDriver() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var voice = DemoProject.create().voices().getFirst();
        String asset = builder.asset(Kind.FM_VOICE, voice.name(), StockTable.bytes(voice.bytes()));
        builder.variant(asset, voice.name(), Recipe.plain(), TargetGame.S1, null);
        builder.variant(asset, voice.name(), Recipe.plain(), TargetGame.S2, null);
        builder.variant(asset, voice.name(), Recipe.plain(), null);
        var index = builder.build();
        var options = new CatalogueMatcher.Options(Kind.FM_VOICE, null, null, 60, 18, 10);
        for (var game : List.of(TargetGame.S1, TargetGame.S2)) {
            var variant = index.variants().stream().filter(v -> v.target() == game).findFirst().orElseThrow();
            Path reference = directory.resolve(game.id() + ".wav");
            var probe = CataloguePlayback.matchProbe(index, variant.id(), 60, 18);
            WaveFile.render(probe, reference, 30);
            var result = CatalogueMatcher.matchAssets(index, ReferenceAudio.read(reference, 0, null),
                    options, java.util.Set.of(), game);
            assertEquals(game.id() + "-ntsc-match-assets-v2", result.featureProfile());
            assertEquals(1, result.eligible());
            assertEquals(1, result.compared());
            assertEquals(variant.id(), result.matches().getFirst().variantId());
            assertEquals(0, result.matches().getFirst().distance().total(), 1e-8);
        }
    }

    @Test void rawReconstructionSkipsAzureLakeDataWithoutIndependentVoiceProvenance() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var voices = DemoProject.create().voices();
        for (int i = 0; i < voices.size(); i++) {
            var voice = voices.get(i);
            byte[] raw = new byte[voice.bytes().size()];
            for (int j = 0; j < raw.length; j++) raw[j] = voice.bytes().get(j).byteValue();
            String asset = builder.asset(Kind.FM_VOICE, voice.name(), raw);
            var occurrence = new InstrumentCatalogue.Occurrence("rom", "S3K", CatalogueMatcher.LOCKED_ON_S3K,
                    "Azure Lake", 32, i == 0 ? 16 : 4, null, null, false, "local-fm");
            builder.variant(asset, voice.name(), Recipe.plain(), occurrence);
        }
        var index = builder.build();
        var unused = index.variants().stream().filter(v -> v.occurrences().getFirst().slot() == 16)
                .findFirst().orElseThrow();
        var audio = directory.resolve("query.wav");
        var probe = CataloguePlayback.matchProbe(index, unused.id(), 72, 16);
        WaveFile.render(ProjectCompiler.compileWithAssets(probe), audio, 20);
        var reference = ReferenceAudio.read(audio, 0, null);
        var options = new CatalogueMatcher.Options(Kind.FM_VOICE, null, null, 72, 16, 5);
        assertEquals(unused.id(), CatalogueMatcher.match(index, reference, options).matches().getFirst().variantId());
        var result = CatalogueMatcher.matchAssets(index, reference, options);
        assertEquals(1, result.compared());
        assertEquals(unused.id(), result.skipped().getFirst().variantId());
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
