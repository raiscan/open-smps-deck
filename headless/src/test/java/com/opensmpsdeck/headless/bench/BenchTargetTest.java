package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.Main;
import com.opensmpsdeck.headless.TargetPlayback;
import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchTargetTest {
    @TempDir Path directory;

    // Asymmetric middle operators distinguish the sequencer's order from
    // the catalogue's editable order.
    private static final List<Integer> PATCH = List.of(
            4, 1, 2, 1, 2, 31, 31, 31, 31, 8, 7, 8, 7, 3, 3, 3, 3, 42, 42, 42, 42, 34, 8, 40, 8);
    private static final int FRAMES = 360;

    @ParameterizedTest
    @EnumSource(value = TargetGame.class, names = {"S1", "S2"})
    void timelineUsesPresentationSecondsAndEditableVoiceOrder(TargetGame target) throws Exception {
        var truth = BenchTimeline.read(project(target), FRAMES, null, true);
        var lead = truth.channels().stream().filter(lane -> lane.kind().equals("FM1")).findFirst().orElseThrow();
        assertEquals(List.of(fixtureNote()), lead.notes());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S1_ROM", matches = ".+")
    void benchRunForSonic1() throws Exception {
        runClassic(TargetGame.S1, "OPENSMPSDECK_S1_ROM");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSMPSDECK_S2_ROM", matches = ".+")
    void benchRunForSonic2() throws Exception {
        runClassic(TargetGame.S2, "OPENSMPSDECK_S2_ROM");
    }

    @Test void rejectsCatalogueWithoutTargetVariants() throws Exception {
        var input = fixture(TargetGame.S2, TargetGame.S3K);
        var result = bench(TargetGame.S2, input);
        assertEquals(2, result.status());
        assertTrue(result.output().contains("catalogue has no s2 variants"), result.output());
    }

    @Test void requiresConfiguredTargetRom() throws Exception {
        var input = fixture(TargetGame.S2, TargetGame.S2);
        var result = bench(TargetGame.S2, input);
        assertEquals(2, result.status());
        assertTrue(result.output().contains("No s2 ROM configured"), result.output());
    }

    private void runClassic(TargetGame target, String romVariable) throws Exception {
        var configured = command("rom", "set", target.id(), System.getenv(romVariable));
        assertEquals(0, configured.status(), configured.output());
        var input = fixture(target, target);
        var result = bench(target, input);
        assertEquals(0, result.status(), result.output());
        var card = BenchFiles.read(directory.resolve("results/scorecard.json"), BenchScorecard.Card.class);
        assertEquals(target, card.targetGame());
        assertEquals(1.0, card.overall().mean().get("noteF1"));
        assertEquals(1.0, card.overall().mean().get("voiceTop1"));
        assertEquals(1.0, card.overall().mean().get("voiceTop3"));
        assertTrue(card.overall().mean().get("onsetCorrelation") > .8);
    }

    private record Inputs(Path manifest, Path catalogue) {}
    private record Result(int status, String output) {}

    private Inputs fixture(TargetGame target, TargetGame catalogueTarget) throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Fixture voice", patch());
        builder.variant(asset, "Fixture voice", InstrumentCatalogue.Recipe.plain(), catalogueTarget, null);
        var catalogue = builder.build();
        Path index = directory.resolve("catalogue.json");
        catalogue.save(index);
        var playback = TargetPlayback.prepare(project(target));
        Path wav = directory.resolve("reference.wav");
        WaveFile.write(wav, FRAMES, sink -> playback.render(FRAMES, sink));
        var truth = new BenchTimeline.Truth(List.of(new BenchTimeline.Channel("FM1", List.of(fixtureNote()), List.of())));
        Path truthPath = directory.resolve("truth.json");
        BenchFiles.write(truthPath, truth);
        Path midi = directory.resolve("input.mid");
        BenchMidi.write(BenchMidi.events(truth, new BenchDrums(catalogue, "test"),
                BenchMidi.Profile.clean, FRAMES / 60.0, 1), BenchMidi.Profile.clean, midi);
        var wavFile = BenchCorpus.File.at(directory, wav);
        var sample = new BenchCorpus.Case("fixture", 1, "Fixture", BenchMidi.Profile.clean, BenchAudio.Variant.dry,
                BenchCorpus.File.at(directory, truthPath), BenchCorpus.File.at(directory, midi), wavFile, wavFile);
        Path manifest = directory.resolve("manifest.json");
        BenchFiles.write(manifest, new BenchCorpus.Manifest(1, "test", BenchFiles.hash(index), FRAMES, List.of(sample)));
        return new Inputs(manifest, index);
    }

    private Result bench(TargetGame target, Inputs input) throws Exception {
        return command("bench", "run", input.manifest().toString(), input.catalogue().toString(),
                directory.resolve("results").toString(), "--target", target.id(), "--steady-tempo", "off", "--jobs", "1");
    }

    private Result command(String... args) throws Exception {
        var argv = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), Main.class.getName()));
        argv.addAll(List.of(args));
        var log = directory.resolve("command.log");
        var command = new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(log.toFile());
        command.environment().put("XDG_CONFIG_HOME", directory.resolve("config").toString());
        return new Result(command.start().waitFor(), Files.readString(log));
    }

    private static StudioProject project(TargetGame target) {
        var voice = new Voice("fixture-voice", "Fixture voice", PATCH);
        var sequence = new Sequence("fixture-sequence", "Fixture phrase", "FM", 330, voice.id(), 127,
                List.of(new NoteEvent("fixture-note", 270, 24, 72)));
        return new StudioProject(1, "Fixture", "S3K", 30, 0, List.of(voice), List.of(sequence),
                List.of(new Clip("fixture-clip", sequence.id(), "FM1", 0, 330)), target, 255);
    }

    private static BenchTimeline.Note fixtureNote() {
        // The held service before tick 270 adds one 60 Hz presentation on both targets.
        return new BenchTimeline.Note(271 / 60.0, 295 / 60.0, 72, BenchFiles.hash(patch()), null);
    }

    private static byte[] patch() {
        byte[] bytes = new byte[PATCH.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = PATCH.get(i).byteValue();
        return bytes;
    }
}
