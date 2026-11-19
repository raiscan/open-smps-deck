package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.CataloguePlayback;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.Main;
import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.InstrumentCatalogue.Kind.FM_VOICE;
import static com.opensmpsdeck.headless.InstrumentCatalogue.Kind.PSG_VOLUME;
import static org.junit.jupiter.api.Assertions.*;

class BenchCliTest {
    @TempDir Path directory;

    @Test void holdoutRemovesSharedContentAndRecipesDependingOnRemovedAssets() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        String shared = builder.asset(FM_VOICE, "shared", voice(0));
        String unaffected = builder.asset(FM_VOICE, "unaffected", voice(1));
        String env = builder.asset(PSG_VOLUME, "envelope", new byte[]{0, 2, (byte) 0x83});
        var song = new InstrumentCatalogue.Occurrence("test", "S3K", "rom", "Song", 1, 0, null, null, false);
        var other = new InstrumentCatalogue.Occurrence("test", "S3K", "rom", "Other", 2, 0, null, null, true);
        builder.variant(shared, "shared", InstrumentCatalogue.Recipe.plain(), other);
        builder.variant(shared, "other recipe", new InstrumentCatalogue.Recipe(null, null, 99, 60,
                null, null, List.of(), List.of()), song);
        builder.variant(env, "envelope", InstrumentCatalogue.Recipe.plain(), song);
        builder.variant(unaffected, "keep", InstrumentCatalogue.Recipe.plain(), other);
        builder.variant(unaffected, "dangling", new InstrumentCatalogue.Recipe(null, null, 90, 60,
                env, null, List.of(), List.of()), other);
        var filtered = directory.resolve("holdout.json");
        BenchFiles.write(filtered, BenchHoldout.filter(builder.build(), "rom", 1, "Song"));
        var reopened = InstrumentCatalogue.open(filtered);
        assertEquals(List.of(unaffected), reopened.assets().stream().map(InstrumentCatalogue.Asset::id).toList());
        assertEquals(List.of("keep"), reopened.variants().stream().map(InstrumentCatalogue.Variant::name).toList());
    }

    @Test void compareUsesMetricDirectionAndAbsoluteTolerance() throws Exception {
        Path old = directory.resolve("old.json");
        Path changed = directory.resolve("changed.json");
        BenchFiles.write(old, card(.90, .4));
        BenchFiles.write(changed, card(.89, .41));
        assertEquals(0, cli("compare", old.toString(), changed.toString()));
        BenchFiles.write(changed, card(.88, .39));
        assertEquals(1, cli("compare", old.toString(), changed.toString()));
        BenchFiles.write(changed, card(.91, .42));
        assertEquals(1, cli("compare", old.toString(), changed.toString()));
        assertEquals(0, cli("compare", old.toString(), changed.toString(), "--tolerance", ".03"));
    }

    @Test void goldParsesRegionSchemaAndScoresOnlyAnnotatedLeadWindows() throws Exception {
        Path annotations = directory.resolve("labels.json");
        Files.writeString(annotations, """
                {"source":"synthetic.wav","regions":[{"id":"phrase","start_s":1,"end_s":2,"notes":[
                  {"midi":72,"attack_s":1.1,"release_s":null,"voice":"lead","confidence":"high"},
                  {"midi":68,"attack_s":1.1,"release_s":1.2,"voice":"harmony"},
                  {"midi":75,"attack_s":1.5,"release_s":1.7,"voice":"lead"},
                  {"midi":76,"attack_s":null,"voice":"lead"}
                ]}]}
                """);
        Path spec = directory.resolve("gold.json");
        Files.writeString(spec, """
                {"id":"fixture","annotations":"labels.json","reference":"mix.wav","midi":"input.mid",
                 "stems":{"0":"lead.wav"},"leadPart":0}
                """);
        var gold = BenchGold.read(spec);
        assertEquals(directory.resolve("lead.wav"), gold.stems().get(0));
        assertEquals(0, gold.leadPart());
        assertNull(gold.regions().getFirst().notes().getFirst().release());
        var score = BenchGold.score(gold.regions(), List.of(BenchMetricsTest.note(.8, .9, 72),
                BenchMetricsTest.note(1.1, 1.2, 72), BenchMetricsTest.note(1.52, 1.7, 76),
                BenchMetricsTest.note(1.85, 1.9, 67), BenchMetricsTest.note(2, 2.2, 75)));
        assertEquals(new BenchMetrics.F1(2, 3, 2), score.attacks());
        assertEquals(.5, score.pitchAccuracy());
    }

    @Test void runsExistingReconstructionInProcessAndProducesIdenticalCards() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(FM_VOICE, "Demo lead", voice(0));
        String variant = builder.variant(asset, "Demo lead", InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();
        Path index = directory.resolve("index.json");
        catalogue.save(index);
        Path wav = directory.resolve("reference.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(CataloguePlayback.matchProbe(catalogue, variant, 72, 24)), wav, 60);
        String hash = BenchFiles.hash(voice(0));
        var truth = new BenchTimeline.Truth(List.of(new BenchTimeline.Channel("FM1",
                List.of(new BenchTimeline.Note(0, .4, 72, hash, null)), List.of())));
        Path truthPath = directory.resolve("truth.json");
        BenchFiles.write(truthPath, truth);
        Path midi = directory.resolve("input.mid");
        BenchMidi.write(BenchMidi.events(truth, new BenchDrums(catalogue, "test"),
                BenchMidi.Profile.clean, 1, 1), BenchMidi.Profile.clean, midi);
        var wavFile = BenchCorpus.File.at(directory, wav);
        var sample = new BenchCorpus.Case("fixture", 1, "Fixture", BenchMidi.Profile.clean, BenchAudio.Variant.dry,
                BenchCorpus.File.at(directory, truthPath), BenchCorpus.File.at(directory, midi), wavFile, wavFile);
        Path manifest = directory.resolve("manifest.json");
        BenchFiles.write(manifest, new BenchCorpus.Manifest(1, "test", BenchFiles.hash(index), 60, List.of(sample)));
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        assertEquals(0, cli("run", manifest.toString(), index.toString(), first.toString(), "--quick", "--jobs", "1"));
        assertEquals(0, cli("run", manifest.toString(), index.toString(), second.toString(), "--quick", "--jobs", "2"));
        var card = BenchFiles.read(first.resolve("scorecard.json"), BenchScorecard.Card.class);
        assertEquals(1.0, card.overall().mean().get("noteF1"));
        assertEquals(1.0, card.overall().mean().get("offsetF1"));
        assertEquals(1.0, card.overall().mean().get("voiceTop1"));
        assertEquals(1.0, card.overall().mean().get("voiceTop3"));
        assertEquals(-1, Files.mismatch(first.resolve("scorecard.json"), second.resolve("scorecard.json")));
    }

    private static BenchScorecard.Card card(double f1, double distance) {
        var sample = new BenchScorecard.Case("fixture", BenchMidi.Profile.clean, BenchAudio.Variant.dry,
                Map.of("noteF1", f1, "logMelDistance", distance), null, null, null, null, null, null, null);
        return BenchScorecard.create("manifest", "catalogue", false, false, Map.of(), List.of(sample));
    }

    private static byte[] voice(int id) {
        var bytes = DemoProject.create().voices().get(id).bytes();
        byte[] result = new byte[bytes.size()];
        for (int i = 0; i < result.length; i++) result[i] = bytes.get(i).byteValue();
        return result;
    }

    private static int cli(String... args) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            var command = new java.util.ArrayList<>(List.of("bench"));
            command.addAll(List.of(args));
            int status = Main.run(command.toArray(String[]::new), stream, stream);
            assertTrue(status < 2, output.toString());
            return status;
        }
    }
}
