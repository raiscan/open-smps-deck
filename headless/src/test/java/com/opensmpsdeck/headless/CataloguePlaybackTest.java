package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CataloguePlaybackTest {
    @Test void probesUsingEachAssetKindRenderThroughTheProjectPlayer() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        byte[] fmBytes = new byte[25];
        var fmVoice = DemoProject.create().voices().getFirst();
        for (int i = 0; i < fmBytes.length; i++) fmBytes[i] = fmVoice.bytes().get(i).byteValue();
        String voice = builder.asset(Kind.FM_VOICE, "FM", fmBytes);
        String volume = builder.asset(Kind.PSG_VOLUME, "Volume", new byte[]{0, 1, (byte) 0x83});
        String modulation = builder.asset(Kind.MODULATION, "Signed modulation", new byte[]{(byte) 0xFE, 2, (byte) 0x80});
        String dac = builder.asset(Kind.DAC_SAMPLE, "DAC", new byte[]{(byte) 128, 40, 40, 40, (byte) 230, (byte) 128});
        String noise = builder.asset(Kind.PSG_NOISE, "Tone-linked noise", new byte[]{(byte) 0xE7});
        builder.variant(voice, "FM", Recipe.plain(), null);
        builder.variant(volume, "Volume", Recipe.plain(), null);
        builder.variant(modulation, "Signed modulation", Recipe.plain(), null);
        builder.variant(dac, "DAC", new Recipe(12, null, 127, 12, null, null, List.of(), List.of()), null);
        builder.variant(noise, "Tone-linked noise", Recipe.plain(), null);
        var catalogue = builder.build();

        for (String id : List.of(voice, volume, modulation, dac, noise)) {
            var probe = CataloguePlayback.audition(catalogue, id);
            assertEquals(4, probe.formatVersion());
            boolean[] heard = {false};
            S3kRenderer.render(ProjectCompiler.compileWithAssets(probe), ProjectRenderTiming.presentationCount(probe), pcm -> {
                for (short sample : pcm) heard[0] |= sample != 0;
            });
            assertTrue(heard[0], () -> "Silent probe for " + id);
        }
    }

    @Test void matchingProbeUsesOneRequestedAttackAndPreservesOnsetState() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        byte[] fmBytes = new byte[25];
        var fmVoice = DemoProject.create().voices().getFirst();
        for (int i = 0; i < fmBytes.length; i++) fmBytes[i] = fmVoice.bytes().get(i).byteValue();
        String voice = builder.asset(Kind.FM_VOICE, "FM", fmBytes);
        String envelope = builder.asset(Kind.MODULATION, "Vibrato", new byte[]{1, (byte) 0x80});
        String variant = builder.variant(voice, "FM with onset settings", new Recipe(null, 0x80, 127, 40,
                null, null, List.of(new NoteEvent("first", 8, 4, 60), new NoteEvent("later", 22, 4, 64)),
                List.of(new CommandEvent("mod", 4, NativeCommand.MODULATION_ENVELOPE, List.of(), envelope),
                        new CommandEvent("transpose", 0, NativeCommand.TRANSPOSE, List.of(0x4C)),
                        new CommandEvent("hold", 8, NativeCommand.HOLD, List.of()),
                        new CommandEvent("volume", 8, NativeCommand.VOLUME, List.of(120)),
                        new CommandEvent("later-volume", 16, NativeCommand.VOLUME, List.of(0)))), null);
        var catalogue = builder.build();
        var probe = CataloguePlayback.matchProbe(catalogue, variant, 72, 180);
        var tone = probe.sequences().getFirst();
        assertEquals(List.of(new NoteEvent("match-note", 0, 180, 72)), tone.notes());
        assertEquals(180, tone.lengthTicks());
        assertEquals(List.of(NativeCommand.PAN, NativeCommand.MODULATION_ENVELOPE, NativeCommand.VOLUME),
                tone.commands().stream().map(CommandEvent::command).toList());
        assertTrue(tone.commands().stream().allMatch(command -> command.tick() == 0));
        assertEquals(probe.envelopes().getFirst().id(), tone.commands().get(1).assetId());
        assertThrows(IllegalArgumentException.class, () -> CataloguePlayback.matchProbe(catalogue, variant, 107, 60));
        assertThrows(IllegalArgumentException.class, () -> CataloguePlayback.matchProbe(catalogue, variant, 72, 181));
        boolean[] heard = {false};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(probe), ProjectRenderTiming.presentationCount(probe), pcm -> {
            for (short sample : pcm) heard[0] |= sample != 0;
        });
        assertTrue(heard[0]);
    }

    @Test void deploysNoiseWithLocalAssetsAndExactTimedCommandWithoutDuplicatingIt() {
        var builder = new InstrumentCatalogue.Builder();
        String volume = builder.asset(Kind.PSG_VOLUME, "Volume", new byte[]{0, 1, (byte) 0x83});
        String noise = builder.asset(Kind.PSG_NOISE, "Tone-linked noise", new byte[]{(byte) 0xE7});
        var change = new CommandEvent("native-noise", 0, NativeCommand.PSG_NOISE, List.of(0xE7));
        var notes = List.of(new NoteEvent("tone", 0, 12, 60));
        String variant = builder.variant(noise, "Noise variant", new Recipe(null, null, 112, 12,
                volume, null, notes, List.of(change)), null);
        var catalogue = builder.build();
        var target = DemoProject.create();
        var result = CataloguePlayback.apply(catalogue, variant, target);
        var added = result.sequences().getLast();
        assertEquals(notes, added.notes());
        assertEquals(List.of(change), added.commands());
        assertEquals(result.envelopes().getFirst().id(), added.psgEnvelopeId());
        assertEquals(target.clips(), result.clips().subList(0, target.clips().size()));
        assertEquals("PSG3", result.clips().getLast().lane());
        assertEquals(target.voices(), result.voices());
        assertEquals(result, ProjectFormat.read(ProjectFormat.write(result)));
        assertSame(result, CataloguePlayback.apply(catalogue, variant, result));
        ProjectCompiler.compile(result);
    }

    @Test void keepsRateVariantsOfIdenticalPcmIndependentlyReusable() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        byte[] pcm = {(byte) 128, 42, (byte) 205};
        String asset = builder.asset(Kind.DAC_SAMPLE, "Drum", pcm);
        String fast = builder.variant(asset, "Fast", new Recipe(4, null, 127, 12,
                null, null, List.of(), List.of()), null);
        String slow = builder.variant(asset, "Slow", new Recipe(28, null, 127, 12,
                null, null, List.of(), List.of()), null);
        var catalogue = builder.build();
        var empty = new StudioProject(4, "Target", "S3K", 24, 0, List.of(), List.of(), List.of());
        var first = CataloguePlayback.apply(catalogue, fast, empty);
        var result = CataloguePlayback.apply(catalogue, slow, first);
        assertEquals(List.of(4, 28), result.samples().stream().map(sample -> sample.rate()).toList());
        assertArrayEquals(pcm, result.samples().getFirst().pcm());
        assertArrayEquals(pcm, result.samples().getLast().pcm());
        assertSame(result, CataloguePlayback.apply(catalogue, slow, result));
        var drums = new Sequence("drums", "Both rates", "DAC", 12, null, 127, List.of(), List.of(),
                "RESET", null, null, List.of(new SampleTrigger("fast", 0, result.samples().getFirst().id()),
                new SampleTrigger("slow", 6, result.samples().getLast().id())));
        var arranged = ProjectEdits.addClip(ProjectEdits.addSequence(result, drums),
                new Clip("drum-clip", drums.id(), "DAC", 0, 12));
        var roundTrip = ProjectFormat.read(ProjectFormat.write(arranged));
        assertEquals(arranged, roundTrip);
        S3kRenderer.render(ProjectCompiler.compileWithAssets(roundTrip),
                ProjectRenderTiming.presentationCount(roundTrip), ignored -> {});
    }

    @Test void retainsPlayedDacPanInTheNativeAudition() throws Exception {
        byte[] bank = {0, 0, 1, 0, 1, 0, 10, 0, 0, 0, (byte) 0xE0, (byte) 0x80,
                (byte) 0x81, 12, (byte) 0xF2};
        var sample = DacSample.of("drum", "Drum", 12, new byte[]{(byte) 128, 80, 20, (byte) 228});
        var program = NativeProgram.of(0, 0, 0, 120, bank, List.of(),
                List.of(new NativeProgram.DacSlot(0x81, sample.id(), sample.rate())));
        var source = new StudioProject(5, "Panned sample", "S3K", 24, 0, List.of(), List.of(), List.of(),
                List.of(), List.of(sample), program);
        var builder = new InstrumentCatalogue.Builder();
        CatalogueSources.scanProject(source, "sample.s3k", "test", builder);
        var catalogue = builder.build();
        var selected = catalogue.variants().stream().filter(variant -> Integer.valueOf(0x80).equals(variant.recipe().pan()))
                .findFirst().orElseThrow();
        assertTrue(selected.occurrences().getFirst().played());
        var probe = CataloguePlayback.audition(catalogue, selected.id());
        boolean[] pan = {false};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(probe), 4, pcm -> {}, new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                if (port == 1 && register == 0xB6 && value == 0x80) pan[0] = true;
            }
            @Override public void onPsgWrite(int value) {}
        });
        assertTrue(pan[0], "DAC audition must write the selected pan to YM2612 channel six");
    }

    @Test void reportsAnUnresolvedTimedDependencyAndRejectsNativeBankDeployment() {
        var builder = new InstrumentCatalogue.Builder();
        String noise = builder.asset(Kind.PSG_NOISE, "Noise", new byte[]{(byte) 0xE7});
        var missing = new CommandEvent("envelope", 0, NativeCommand.MODULATION_ENVELOPE, List.of(), "missing");
        String variant = builder.variant(noise, "Incomplete noise", new Recipe(null, null, 127, 12,
                null, null, List.of(), List.of(missing)), null);
        var catalogue = builder.build();
        var problem = assertThrows(IllegalArgumentException.class, () -> CataloguePlayback.audition(catalogue, variant));
        assertTrue(problem.getMessage().contains("Unknown asset missing"));

        byte[] bank = {0, 0, 1, 0, 1, 0, 10, (byte) 128, 0, 0, (byte) 0xF2};
        var program = NativeProgram.of(0x8000, 0, 0, 120, bank, List.of(), List.of());
        var nativeTarget = new StudioProject(5, "Native", "S3K", 15, 0, List.of(), List.of(),
                List.of(), List.of(), List.of(), program);
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> CataloguePlayback.apply(catalogue, variant, nativeTarget));
        assertTrue(rejected.getMessage().contains("editable copy"));
    }

}
