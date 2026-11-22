package com.opensmpsdeck.headless;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.TargetProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionPsgEnvelopesTest {
    @Test
    void translatesS3kHoldAndStop() {
        assertEquals(List.of(2, 4, 0x80), translate(10, 2, 4, 0x81));
        assertEquals(List.of(2, 4, 15, 0x80), translate(10, 2, 4, 0x83));
        assertEquals(List.of(15, 0x80), translate(10, 0x83));
    }

    @Test
    void followsResetAndIndexedLoopForTheGate() {
        assertEquals(List.of(1, 3, 1, 3, 1, 0x80), translate(5, 1, 3, 0x80));
        assertEquals(List.of(1, 3, 5, 3, 5, 3, 0x80), translate(6, 1, 3, 5, 0x82, 1));
    }

    @Test
    void consumesMultiplierOperandLikeThePinnedVolumeInterpreter() {
        var result = ReconstructionPsgEnvelopes.translate(List.of(1, 0x84, 0x83, 4, 0x81), 10);
        assertEquals(List.of(1, 4, 0x80), result.bytes());
        assertTrue(result.reasons().getFirst().contains("84"));
    }

    @Test
    void reportsMalformedOrUnsupportedControlsAndCapsLongLoops() {
        for (var program : List.of(List.of(1, 0x85), List.of(1, 0x82, 7),
                List.of(1, 0x82), List.of(1, 0x84), List.of(0x80), List.of(1))) {
            var result = ReconstructionPsgEnvelopes.translate(program, 10);
            assertEquals(0x80, result.bytes().getLast());
            assertFalse(result.reasons().isEmpty(), program.toString());
        }
        var result = ReconstructionPsgEnvelopes.translate(List.of(0, 1, 0x80), 500);
        assertEquals(255, result.bytes().size());
        assertEquals(0x80, result.bytes().getLast());
        assertTrue(result.reasons().getFirst().contains("254"));
    }

    @Test
    void selectsThePresetForEachTarget() {
        for (var target : TargetGame.values()) {
            var preset = ReconstructionLanePolicies.defaultPreset(target);
            assertEquals(target.id() + "-layering", ReconstructionLanePolicies.Configuration.preset(preset).preset());
        }
    }

    @Test
    void translatedProgramsRenderTheSameAttenuationAtEverySoundingTick() throws Exception {
        for (var bytes : List.of(List.of(0, 3, 5, 0x81), List.of(0, 3, 5, 0x83),
                List.of(0, 1, 3, 0x80), List.of(0, 1, 3, 0x82, 1),
                List.of(0, 3, 0x84, 4, 6, 0x81))) {
            var envelope = new Envelope("probe", "Envelope", Envelope.PSG_VOLUME, bytes);
            int gate = 18;
            var source = probe(TargetGame.S3K, envelope, gate, 72, "PSG1", List.of());
            var expected = attenuation(source, gate);
            assertEquals(0, expected.getFirst());
            assertEquals(bytes.get(1), expected.get(1));
            for (var target : List.of(TargetGame.S1, TargetGame.S2)) {
                var converted = ReconstructionPsgEnvelopes.forProbe(envelope, target, gate);
                var actual = attenuation(probe(target, converted, gate, 72, "PSG1", List.of()), gate);
                assertEquals(expected, actual, target + " " + bytes);
            }
        }
    }

    @Test
    void assemblyUnrollsOverAClassicTempoHold() {
        var envelope = new Envelope("probe", "Envelope", Envelope.PSG_VOLUME, List.of(0, 2, 4, 0x80));
        var base = probe(TargetGame.S1, envelope, 2, 72, "PSG1", List.of());
        var project = new StudioProject(4, base.name(), base.mode(), base.ticksPerBeat(), base.tempo(),
                base.voices(), base.sequences(), List.of(new Clip("clip", "probe", "PSG1", 253, 2)),
                base.envelopes(), base.samples(), base.target(), base.nativeTempo());
        assertEquals(List.of(0, 2, 4, 0x80),
                ReconstructionPsgEnvelopes.forAssembly(project, java.util.Set.of()).project().envelopes().getFirst().bytes());
    }

    @Test
    void classicNoiseUsesToneThreeForWhiteAndPeriodicModes() throws Exception {
        for (var target : TargetGame.values()) {
            var profile = TargetProfile.of(target);
            for (int mode : new int[]{0xe3, 0xe7}) {
                int expectedPeriod = mode == 0xe3 ? 12 : 8;
                int detune = ReconstructionCymbals.detune(expectedPeriod, profile);
                var project = probe(target, null, 3, ReconstructionCymbals.pitch(profile), "PSG3", List.of(
                        new CommandEvent("noise", 0, NativeCommand.PSG_NOISE, List.of(mode)),
                        new CommandEvent("detune", 0, NativeCommand.DETUNE, List.of(detune & 255))));
                var writes = new ArrayList<Integer>();
                TargetPlayback.prepare(project).render(1, S3kRenderer.ChannelMask.NONE, ignored -> {}, observer(writes));
                assertTrue(writes.contains(mode), target + " " + mode + " " + writes);
                int low = writes.lastIndexOf(0xc0 | (expectedPeriod & 15));
                assertTrue(low >= 0 && low + 1 < writes.size(), target + " " + writes);
                assertEquals(expectedPeriod >> 4, writes.get(low + 1), target + " " + writes);
                assertTrue(writes.contains(0xf0), target + " noise channel must sound");
                assertEquals(target == TargetGame.S3K ? 117 : 116, project.sequences().getFirst().notes().getFirst().pitch());
            }
        }
    }

    @Test
    void classicReconstructionKeepsCymbalsAndFixedDrumsInOneNoiseClip(@TempDir Path directory) throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        String fm = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), StockTable.bytes(patch.bytes()));
        String variant = builder.variant(fm, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        ReconstructionCliTest.classicVariants(builder, fm, patch.name(), InstrumentCatalogue.Recipe.plain());
        var catalogue = builder.build();
        var index = directory.resolve("catalogue.json");
        catalogue.save(index);
        var wav = directory.resolve("reference.wav");
        TargetPlayback.prepare(CataloguePlayback.matchProbe(catalogue, variant, 72, 48)).writeWave(wav, 72);

        var midi = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 30);
        var lead = midi.createTrack();
        midiNote(lead, 0, 72, 0, 48);
        var drums = midi.createTrack();
        byte[] name = "PSG3".getBytes(StandardCharsets.US_ASCII);
        drums.add(new MidiEvent(new MetaMessage(3, name, name.length), 0));
        midiNote(drums, 9, 38, 12, 3);
        var gm = midi.createTrack();
        midiNote(gm, 9, 36, 6, 3);
        midiNote(gm, 9, 42, 6, 3);
        midiNote(gm, 9, 36, 24, 9);
        midiNote(gm, 9, 46, 24, 9);
        var score = directory.resolve("score.mid");
        MidiSystem.write(midi, 1, score.toFile());

        for (var target : ReconstructionCliTest.classicTargets()) {
            var profile = TargetProfile.of(target);
            var work = directory.resolve(target.id());
            var project = ReconstructionCliTest.classicRun(score, wav, index, work, target, "--steady-tempo", "off");
            var clips = project.clips().stream().filter(c -> c.lane().equals("PSG3")).toList();
            assertEquals(1, clips.size());
            var sequence = project.sequences().stream().filter(s -> s.id().equals(clips.getFirst().sequenceId()))
                    .findFirst().orElseThrow();
            assertEquals(1, sequence.commands().stream().filter(c -> c.command() == NativeCommand.PSG_NOISE).count());
            assertTrue(sequence.commands().stream().anyMatch(c -> c.command() == NativeCommand.PSG_ENVELOPE
                    && c.assetId() == null), "fixed drum clears the cymbal envelope");
            assertTrue(sequence.notes().stream().anyMatch(n -> n.pitch() == 81));
            assertTrue(sequence.notes().stream().anyMatch(n -> n.pitch() == profile.psg().high()));
            assertTrue(sequence.notes().stream().allMatch(n -> profile.psg().contains(n.pitch())));
        }
    }

    private static void midiNote(javax.sound.midi.Track track, int channel, int pitch, int tick, int duration)
            throws Exception {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, 100), tick));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), tick + duration));
    }

    @Test
    void stockFitRejectsAHoldThatWouldRevealASilentTail() {
        var stock = Map.of(1, new byte[]{0, 2, 14, (byte) 0x80}, 2, new byte[]{0, 3, 15, (byte) 0x80});
        assertEquals(2, ReconstructionPsgEnvelopes.closeStock(List.of(0, 2, 15, 0x80), 8, stock));
        assertEquals(1, ReconstructionPsgEnvelopes.closeStock(List.of(0, 2, 15, 0x80), 2, stock));
    }

    private static StudioProject probe(TargetGame game, Envelope envelope, int gate, int pitch,
                                       String lane, List<CommandEvent> commands) {
        var sequence = new Sequence("probe", "Probe", "PSG", gate, null, 127,
                List.of(new NoteEvent("attack", 0, gate, pitch)), commands, "RESET",
                envelope == null ? null : envelope.id(), null);
        return new StudioProject(4, "Probe", "S3K", 30, 0, List.of(), List.of(sequence),
                List.of(new Clip("probe", "probe", lane, 0, gate)),
                envelope == null ? List.of() : List.of(envelope), List.of(),
                game, game == TargetGame.S3K ? null : 255);
    }

    private static List<Integer> attenuation(StudioProject project, int gate) throws Exception {
        var writes = new ArrayList<Integer>();
        var values = new ArrayList<Integer>();
        int[] last = {15};
        S3kRenderer.PcmSink collect = ignored -> {
            for (int value : writes)
                if ((value & 0xf0) == 0x90) last[0] = value & 15;
            values.add(last[0]);
            writes.clear();
        };
        var envelope = project.envelopes().getFirst();
        if (project.target() == TargetGame.S3K && (envelope.bytes().contains(0x82) || envelope.bytes().contains(0x84))) {
            // Project preflight restricts these controls. Feed the synthetic
            // raw envelope to the same pinned S3K session independently.
            var setup = new Envelope(envelope.id(), envelope.name(), envelope.type(), List.of(0, 0x81));
            byte[] bank = ProjectCompiler.compile(project.withAssets(List.of(), List.of(setup), List.of()),
                    ProjectCompiler.Encoding.LINEAR);
            var data = new Sonic3kSmpsData(bank, 0);
            data.setPsgEnvelopes(Map.of(1, StockTable.bytes(envelope.bytes())));
            var config = Sonic3kSmpsSequencerConfig.create(new Sonic3kCoordFlagHandler());
            S3kRenderer.renderPackageMusic("s3k", data, new DacData(Map.of(), Map.of(), 297), config,
                    Sonic3kSmpsPhysicalPolicy.INSTANCE, gate, collect, observer(writes));
        } else {
            TargetPlayback.prepare(project).render(gate, S3kRenderer.ChannelMask.NONE, collect, observer(writes));
        }
        return values;
    }

    private static ChipWriteObserver observer(List<Integer> writes) {
        return new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {}
            @Override public void onPsgWrite(int value) { writes.add(value); }
        };
    }

    private static List<Integer> translate(int ticks, Integer... bytes) {
        return ReconstructionPsgEnvelopes.translate(List.of(bytes), ticks).bytes();
    }
}
