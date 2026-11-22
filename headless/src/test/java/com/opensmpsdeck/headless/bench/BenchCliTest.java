package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.CataloguePlayback;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.Main;
import com.opensmpsdeck.headless.ReferenceStretch;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.TargetGame;
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

    @Test void stemsSeparateTonalPsg3FromNoiseAndDac() {
        var groups = BenchStems.groups(new BenchTimeline.Truth(List.of(
                new BenchTimeline.Channel("FM1", List.of(new BenchTimeline.Note(0, 1, 84, null, null)), List.of()),
                new BenchTimeline.Channel("FM2", List.of(new BenchTimeline.Note(0, 1, 36, null, null)), List.of()),
                new BenchTimeline.Channel("PSG3", List.of(new BenchTimeline.Note(0, 1, 60, null, null)), List.of()),
                new BenchTimeline.Channel("noise", List.of(new BenchTimeline.Note(0, 1, 60, null, null)), List.of()))));
        assertEquals(List.of("FM1"), groups.get("lead"));
        assertEquals(List.of("FM2"), groups.get("bass"));
        assertTrue(groups.get("backing").contains("PSG3"));
        assertEquals(List.of("FM6-DAC", "noise"), groups.get("drums"));
        assertEquals(0b1000, BenchStems.mask(groups.get("backing")).psg());
        assertEquals(0b1011, BenchStems.mask(List.of("PSG3")).psg());
        assertEquals(0b0111, BenchStems.mask(groups.get("drums")).psg());
        assertEquals(0b011111, BenchStems.mask(groups.get("drums")).fm());
    }

    @Test void stemWavsShareTheMixShiftDegradationAndDrift() throws Exception {
        var project = ProjectCompiler.compileWithAssets(DemoProject.create());
        var truth = new BenchTimeline.Truth(List.of(new BenchTimeline.Channel("FM1",
                List.of(new BenchTimeline.Note(0, 1, 72, null, null)), List.of())));
        var drums = new BenchDrums(new InstrumentCatalogue.Builder().build(), "fixture");
        int frames = 60;
        var grid = new BenchStructure.Grid(120, 5, 24);
        var clock = new BenchCorpus.Accelerando(1, .07);
        var alignments = new java.util.EnumMap<BenchAudio.Variant, ReferenceStretch.Alignment>(BenchAudio.Variant.class);
        Path nativeMix = directory.resolve("mix.wav");
        WaveFile.render(project, nativeMix, frames);
        var mix = BenchAudio.shift(BenchAudio.read(nativeMix), grid.offsetFrames());
        for (var variant : BenchAudio.Variant.values()) {
            Path variantMix = directory.resolve("mix-" + variant + ".wav");
            Path ordinary = directory.resolve("ordinary-" + variant + ".wav");
            Path captured = directory.resolve("captured-" + variant + ".wav");
            BenchAudio.write(BenchAudio.degrade(mix, variant), variantMix);
            int presentations = (int) Math.ceil(clock.inputDuration() * 60);
            ReferenceStretch.write(variantMix, ordinary, presentations, clock::truthTime);
            alignments.put(variant, ReferenceStretch.writeAndCaptureAlignment(variantMix, captured,
                    presentations, clock::truthTime));
            assertEquals(-1, Files.mismatch(ordinary, captured));
        }
        BenchStems.build(project, truth, drums, directory, 1, frames, grid, clock, alignments);

        Path nativeLead = directory.resolve("native.wav");
        var mask = BenchStems.mask(List.of("FM1"));
        WaveFile.write(nativeLead, frames, sink ->
                S3kRenderer.render(project, frames, mask, sink));
        var shifted = BenchAudio.shift(BenchAudio.read(nativeLead), grid.offsetFrames());
        assertFalse(java.util.Arrays.equals(shifted.stereo(), new short[shifted.stereo().length]));
        for (var variant : BenchAudio.Variant.values()) {
            Path expected = directory.resolve("expected-" + variant + ".wav");
            BenchAudio.write(BenchAudio.degrade(shifted, variant), expected);
            Path warped = BenchCorpus.driftPath(expected);
            alignments.get(variant).writeStem(expected, warped);
            assertEquals(-1, Files.mismatch(warped, directory.resolve("lead-" + variant + "-drift.wav")));
        }
    }

    @Test void alternateMidiComparisonVerifiesManifestIdentityAndNonMidiInputs() throws Exception {
        Path beforeManifest = directory.resolve("before-manifest.json");
        Path afterManifest = directory.resolve("after-manifest.json");
        Files.writeString(beforeManifest, """
                {"frames":60,"cases":[{"id":"fixture","audio":{"path":"mix.wav"},"midi":{"path":"a.mid"}}]}
                """);
        Files.writeString(afterManifest, """
                {"frames":60,"cases":[{"id":"fixture","audio":{"path":"mix.wav"},"midi":{"path":"b.mid"}}]}
                """);
        Path before = directory.resolve("before.json");
        Path after = directory.resolve("after.json");
        var sample = card(.90, .4).cases();
        BenchFiles.write(before, BenchScorecard.create(BenchFiles.hash(beforeManifest), "catalogue",
                false, false, Map.of(), sample, TargetGame.S3K));
        BenchFiles.write(after, BenchScorecard.create(BenchFiles.hash(afterManifest), "catalogue",
                false, false, Map.of(), sample, TargetGame.S3K));
        var command = new String[]{"bench", "compare", before.toString(), after.toString(), "--alternate-midi",
                beforeManifest.toString(), afterManifest.toString()};
        var captured = new ByteArrayOutputStream();
        try (var output = new PrintStream(captured)) {
            assertEquals(0, Main.run(command, output, output));
            Files.writeString(afterManifest, Files.readString(afterManifest).replace("mix.wav", "other.wav"));
            assertEquals(2, Main.run(command, output, output));
            BenchFiles.write(after, BenchScorecard.create(BenchFiles.hash(afterManifest), "catalogue",
                    false, false, Map.of(), sample, TargetGame.S3K));
            assertEquals(2, Main.run(command, output, output));
        }
    }

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

    @Test void holdoutKeepsLibraryAssetsUsedByEnoughOtherSongs() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        String kit = builder.asset(FM_VOICE, "kit", voice(0));
        String own = builder.asset(FM_VOICE, "own", voice(1));
        var song = new InstrumentCatalogue.Occurrence("test", "S3K", "rom", "Song", 1, 0, null, null, false);
        var second = new InstrumentCatalogue.Occurrence("test", "S3K", "rom", "Second", 2, 0, null, null, false);
        var third = new InstrumentCatalogue.Occurrence("test", "S3K", "rom", "Third", 3, 0, null, null, false);
        builder.variant(kit, "kit in song", InstrumentCatalogue.Recipe.plain(), song);
        builder.variant(kit, "kit elsewhere", new InstrumentCatalogue.Recipe(null, null, 99, 60,
                null, null, List.of(), List.of()), second);
        builder.variant(kit, "kit third", new InstrumentCatalogue.Recipe(null, null, 98, 60,
                null, null, List.of(), List.of()), third);
        builder.variant(own, "own", InstrumentCatalogue.Recipe.plain(), song);
        var filtered = directory.resolve("library.json");
        BenchFiles.write(filtered, BenchHoldout.filter(builder.build(), "rom", 1, "Song"));
        var reopened = InstrumentCatalogue.open(filtered);
        assertEquals(List.of(kit), reopened.assets().stream().map(InstrumentCatalogue.Asset::id).toList());
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

    @Test void comparesLeadFidelityAdditivelyWithLegacyCards() {
        var legacy = scorecard(Map.of("melodyF1", .9));
        var measured = scorecard(Map.of("melodyF1", .9, "leadFidelityF1", .98));
        var lower = scorecard(Map.of("melodyF1", .9, "leadFidelityF1", .96));
        var added = BenchScorecard.compare(legacy, measured, .01);
        assertTrue(added.passed());
        assertEquals(List.of("melodyF1", "leadFidelityF1"),
                added.deltas().stream().map(BenchScorecard.Delta::metric).toList());
        assertEquals("new", added.deltas().getLast().result());
        assertTrue(BenchScorecard.compare(measured, legacy, .01).passed());
        assertEquals("regression", BenchScorecard.compare(measured, lower, .01).deltas().getLast().result());
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
        assertEquals(0, cli("run", manifest.toString(), index.toString(), second.toString(), "--quick", "--jobs", "2", "--target", "s3k"));
        var card = BenchFiles.read(first.resolve("scorecard.json"), BenchScorecard.Card.class);
        // The default layering adds a PSG octave and an FM echo to this one unaccompanied note.
        assertEquals(1.0, card.overall().mean().get("noteF1"));
        assertEquals(1.0, card.overall().mean().get("notePrecision"));
        assertEquals(1.0, card.overall().mean().get("noteRecall"));
        assertEquals(1.0, card.overall().mean().get("offsetF1"));
        assertEquals(1.0, card.overall().mean().get("melodyF1"));
        assertEquals(.5, card.overall().mean().get("noteF1WithLayers"));
        assertEquals(.5, card.overall().mean().get("offsetF1WithLayers"));
        assertEquals(0.0, card.overall().mean().get("melodyF1WithLayers"));
        assertEquals(1.0, card.overall().mean().get("voiceTop1"));
        assertEquals(1.0, card.overall().mean().get("voiceTop3"));
        assertEquals(-1, Files.mismatch(first.resolve("scorecard.json"), second.resolve("scorecard.json")));
    }

    @Test void comparesTargetKeysOnlyWithExplicitOptIn() throws Exception {
        Path legacy = directory.resolve("legacy.json");
        Path classic = directory.resolve("classic.json");
        BenchFiles.write(legacy, card(.9, .4));
        var json = BenchFiles.read(legacy, com.google.gson.JsonObject.class);
        assertFalse(json.has("target"));
        json.addProperty("target", "s2");
        BenchFiles.write(classic, json);
        var roundTrip = directory.resolve("round-trip.json");
        BenchFiles.write(roundTrip, BenchFiles.read(classic, BenchScorecard.Card.class));
        assertEquals("s2", BenchFiles.read(roundTrip, com.google.gson.JsonObject.class).get("target").getAsString());

        var captured = new ByteArrayOutputStream();
        try (var output = new PrintStream(captured)) {
            assertEquals(2, Main.run(new String[]{"bench", "compare", legacy.toString(), classic.toString(),
                    "--tolerance", "0"}, output, output));
            assertTrue(captured.toString().contains("--cross-target"), captured.toString());
        }
        assertEquals(0, cli("compare", legacy.toString(), classic.toString(), "--cross-target", "--tolerance", "0"));
    }

    @Test void compareUsesTheSameDrumClassDefinitionOnBothSides() {
        var legacy = scorecard(Map.of("drumClassAccuracy", .7));
        var old = new BenchScorecard.Card(1, legacy.manifestSha256(), legacy.catalogueSha256(),
                legacy.holdout(), legacy.audioRefine(), legacy.goldInputs(), legacy.cases(), legacy.overall(),
                legacy.profiles(), legacy.variants(), legacy.gold(), null);
        var fixed = scorecard(Map.of("drumClassAccuracy", .5, "drumClassAccuracyLegacy", .7));
        var regressed = scorecard(Map.of("drumClassAccuracy", .4, "drumClassAccuracyLegacy", .7));

        assertEquals(List.of(new BenchScorecard.Delta("drumClassAccuracyLegacy", .7, .7, 0.0, "pass")),
                BenchScorecard.compare(old, fixed, 0).deltas());
        assertTrue(BenchScorecard.compare(old, fixed, 0).passed());
        assertFalse(BenchScorecard.compare(fixed, regressed, 0).passed());
    }

    private static BenchScorecard.Card card(double f1, double distance) {
        return scorecard(Map.of("noteF1", f1, "logMelDistance", distance));
    }

    private static BenchScorecard.Card scorecard(Map<String, Double> metrics) {
        var sample = new BenchScorecard.Case("fixture", BenchMidi.Profile.clean, BenchAudio.Variant.dry,
                metrics, null, null, null, null, null, null, null);
        return BenchScorecard.create("manifest", "catalogue", false, false, Map.of(), List.of(sample), TargetGame.S3K);
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
