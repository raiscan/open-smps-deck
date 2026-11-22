package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;

import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeArrangementTest {
    @Test void projectsSharedPhrasesAndDacHitsAtPresentationTimes() throws Exception {
        var original = nativeFixture();
        var bank = original.nativeProgram().bank();
        var view = NativeArrangement.project(original);
        assertEquals(60, view.ticksPerBeat());
        assertEquals(2, view.clips().size());
        var fm = view.sequences().get(1);
        assertEquals("FM", fm.type());
        assertEquals(List.of(60, 64), fm.notes().stream().map(NoteEvent::pitch).toList());
        assertEquals(List.of(0, 4), fm.notes().stream().map(NoteEvent::tick).toList());
        assertEquals(List.of(4, 2), fm.notes().stream().map(NoteEvent::duration).toList());
        var dac = view.sequences().getFirst();
        assertEquals("beat", dac.triggers().getFirst().sampleId());
        assertEquals(0, dac.triggers().getFirst().tick());
        assertArrayEquals(bank, original.nativeProgram().bank());
        assertTrue(original.clips().isEmpty());
    }

    @Test void convertingAnS3kNativeBankPreservesTheEditableTarget() throws Exception {
        var bank = nativeFixture().withTarget(TargetGame.S1);
        var error = assertThrows(IllegalArgumentException.class, () -> TargetPlayback.prepare(bank));
        assertTrue(error.getMessage().contains("s1: native S3K banks require an editable copy"));
        var projected = NativeArrangement.project(bank);
        var editable = NativeArrangement.editableCopy(bank);
        for (var copy : List.of(projected, editable)) {
            assertEquals(bank.target(), copy.target());
            // Native banks carry the tempo in their own header, with no classic arrangement override.
            assertEquals(bank.nativeTempo(), copy.nativeTempo());
        }
        assertThrows(IllegalArgumentException.class, () -> ProjectFormat.validate(bank.withNativeTempo(37)));
    }

    @Test void cliCopyRetainsVoiceAndDacRatesAndSupportsArrangementEdits(@TempDir Path directory) throws Exception {
        var original = nativeFixture();
        Path source = directory.resolve("native.json");
        Path destination = directory.resolve("editable.json");
        ProjectFiles.save(source, original, null);
        var err = new ByteArrayOutputStream();
        var stream = new PrintStream(err);
        var args = new String[]{"copy-arrangement", source.toString(), destination.toString()};
        assertEquals(0, Main.run(args, stream, stream), err.toString());

        var file = ProjectFiles.load(destination);
        var copy = file.project();
        assertEquals(4, copy.formatVersion());
        assertNull(copy.nativeProgram());
        assertEquals(original.voices().getFirst().bytes(), copy.voices().getFirst().bytes());
        var fm = copy.sequences().stream().filter(sequence -> sequence.type().equals("FM")).findFirst().orElseThrow();
        assertEquals(copy.voices().getFirst().id(), fm.voiceId());
        var pan = fm.commands().stream().filter(event -> event.command() == NativeCommand.PAN).findFirst().orElseThrow();
        assertEquals(0, pan.tick());
        assertEquals(List.of(0x97), pan.arguments());
        var dac = copy.sequences().stream().filter(sequence -> sequence.type().equals("DAC")).findFirst().orElseThrow();
        assertEquals(List.of(0, 2), dac.triggers().stream().map(hit -> hit.tick()).toList());
        assertEquals(List.of(8, 16), dac.triggers().stream().map(hit -> copy.samples().stream()
                .filter(sample -> sample.id().equals(hit.sampleId())).findFirst().orElseThrow().rate()).toList());

        var moved = ProjectEdits.moveClip(copy, "FM1-0", "FM2", 6);
        var changedNote = new NoteEvent(fm.notes().getFirst().id(), 0, 4, 62);
        var edited = ProjectEdits.updateNote(moved, fm.id(), changedNote);
        ProjectFiles.save(destination, edited, file.revision());
        byte[] saved = Files.readAllBytes(destination);
        assertEquals(2, Main.run(args, stream, stream), err.toString());
        assertArrayEquals(saved, Files.readAllBytes(destination));
        assertEquals(edited, ProjectFiles.load(destination).project());
        assertEquals(original, ProjectFiles.load(source).project());
    }

    @Test void lowPsgNotesKeepTheirHardwarePitchThroughTranspose() throws Exception {
        byte[] bank = new byte[24];
        put(bank, 0, 0, 0, 1, 1, 1, 0, 16, 0x80, 0, 0, 17, 0x80, 0, 0, 0, 0);
        put(bank, 16, 0xF2, 0x81, 2, 0x99, 2, 0xF2);
        var program = NativeProgram.of(0x8000, 0, 0, 120, bank, List.of(), List.of());
        var source = new StudioProject(5, "Low PSG", "S3K", 24, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), program, TargetGame.S3K, null);
        var psg = NativeArrangement.editableCopy(source).sequences().getFirst();
        assertEquals(List.of(45, 60), psg.notes().stream().map(NoteEvent::pitch).toList());
        assertEquals(List.of(new CommandEvent("transpose-0", 0, NativeCommand.TRANSPOSE, List.of(0x37)),
                new CommandEvent("transpose-2", 2, NativeCommand.TRANSPOSE, List.of(0x40))), psg.commands());
    }

    @Test void tracksExecutedEffectsAndPitchChangingHoldsThroughPlayback() throws Exception {
        byte[] bank = new byte[56];
        put(bank, 0, 0, 0, 2, 0, 1, 0, 14, 0x80, 0, 0, 15, 0x80, 0, 0);
        put(bank, 14, 0xF2, 0xEF, 0, 0xF0, 1, 1, 2, 6, 0xB1, 3,
                0xF0, 2, 1, 3, 6, 0xE0, 0x97, 0xE7, 0xB5, 3,
                0xE5, 9, 2, 0xE7, 3, 0xFA, 0xE7, 3, 0xF2);
        var program = NativeProgram.of(0x8000, 0, 0, 30, bank, List.of(), List.of());
        var voice = new Voice("voice", "FM organ", List.of(7, 1, 2, 3, 4, 31, 31, 31, 31,
                0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15, 32, 40, 48, 56));
        var source = new StudioProject(5, "Held effects", "S3K", 24, 0,
                List.of(voice), List.of(), List.of(), List.of(), List.of(), program, TargetGame.S3K, null);

        var copy = NativeArrangement.editableCopy(source);
        var commands = copy.sequences().getFirst().commands();
        assertEquals(List.of(0, 3), commands.stream()
                .filter(event -> event.command() == NativeCommand.MODULATION).map(CommandEvent::tick).toList());
        assertEquals(List.of(3, 6, 9), commands.stream()
                .filter(event -> event.command() == NativeCommand.HOLD).map(CommandEvent::tick).toList());
        assertEquals(List.of(2), commands.stream().filter(event -> event.command() == NativeCommand.FM_VOLUME)
                .findFirst().orElseThrow().arguments());
        assertEquals(List.of(60, 64), copy.sequences().getFirst().notes().stream().map(NoteEvent::pitch).toList());
        var expected = observe(source, false);
        assertTrue(expected.stream().filter(write -> write.register() == 0xA0).map(Write::value).distinct().count() > 2);
        assertEquals(1, expected.stream().filter(write -> write.register() == 0x28).count());
        assertEquals(expected, observe(copy, false));
    }

    @Test void keepsNamedEnvelopeAssetsAndPsgNoiseWhenRendering() throws Exception {
        byte[] bank = new byte[64];
        put(bank, 0, 0, 0, 1, 3, 1, 0, 28, 0x80, 0, 0,
                28, 0x80, 0, 0, 0, 0, 28, 0x80, 0, 0, 0, 0,
                29, 0x80, 0, 0, 0, 0);
        put(bank, 28, 0xF2, 0xF3, 0xE7, 0xF5, 7, 0xF1, 4, 0, 0x99, 3,
                0xEC, 2, 0xE7, 3, 0xF3, 0, 0xF4, 0, 0xE7, 3, 0xF2);
        var noise = new Envelope("noise", "Noise contour", Envelope.PSG_VOLUME, List.of(0, 1, 2, 0x83));
        var bend = new Envelope("bend", "Downward bend", Envelope.MODULATION, List.of(0xFE, 0xFF, 0x83));
        var program = NativeProgram.of(0x8000, 0, 0, 24, bank, List.of(
                new NativeProgram.EnvelopeSlot(noise.id(), 7), new NativeProgram.EnvelopeSlot(bend.id(), 4)), List.of());
        var source = new StudioProject(5, "PSG effects", "S3K", 24, 0,
                List.of(), List.of(), List.of(), List.of(noise, bend), List.of(), program, TargetGame.S3K, null);

        var copy = NativeArrangement.editableCopy(source);
        assertEquals(List.of(noise, bend), copy.envelopes());
        var commands = copy.sequences().getFirst().commands();
        assertEquals(List.of(noise.id()), commands.stream().filter(event -> event.command() == NativeCommand.PSG_ENVELOPE)
                .map(CommandEvent::assetId).toList());
        assertEquals(List.of(bend.id()), commands.stream().filter(event -> event.command() == NativeCommand.MODULATION_ENVELOPE)
                .map(CommandEvent::assetId).filter(id -> id != null).toList());
        var expected = observe(source, true);
        assertTrue(expected.contains(new Write(0, -1, 0xE7)));
        assertEquals(expected, observe(copy, true));
    }

    @Test void copiesNativeFmSsgEgAndOperatorVolumeEnvelope() throws Exception {
        byte[] bank = new byte[48];
        put(bank, 0, 0, 0, 2, 0, 1, 0, 14, 0x80, 0, 0, 15, 0x80, 0, 0);
        put(bank, 14, 0xF2, 0xEF, 0, 0xFF, 0x05, 8, 9, 0, 0, 0xB1, 3,
                0xFF, 0x06, 9, 3, 0xE7, 6, 0xF2);
        var voice = new Voice("voice", "FM voice", Collections.nCopies(25, 0));
        var envelope = new Envelope("contour", "FM contour", Envelope.PSG_VOLUME, List.of(0, 8, 12, 0x81));
        var program = NativeProgram.of(0x8000, 0, 0, 24, bank,
                List.of(new NativeProgram.EnvelopeSlot(envelope.id(), 9)), List.of());
        var source = new StudioProject(5, "FM envelope", "S3K", 24, 0,
                List.of(voice), List.of(), List.of(), List.of(envelope), List.of(), program, TargetGame.S3K, null);

        var copy = NativeArrangement.editableCopy(source);
        var commands = copy.sequences().getFirst().commands();
        var ssg = commands.stream().filter(event -> event.command() == NativeCommand.SSG_EG).findFirst().orElseThrow();
        assertEquals(List.of(8, 9, 0, 0), ssg.arguments());
        var volume = commands.stream().filter(event -> event.command() == NativeCommand.FM_VOLUME_ENVELOPE)
                .findFirst().orElseThrow();
        assertEquals(3, volume.tick());
        assertEquals(envelope.id(), volume.assetId());
        assertEquals(List.of(3), volume.arguments());
        assertEquals(List.of(envelope), copy.envelopes());
        assertEquals(observe(source, false), observe(copy, false));
    }

    private record Write(int tick, int register, int value) {}

    private static List<Write> observe(StudioProject project, boolean psg) throws Exception {
        int[] tick = {0};
        var writes = new ArrayList<Write>();
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), 12, pcm -> tick[0]++, new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                if (!psg && port == 0 && (register == 0xA0 || register == 0xA4 || (register == 0x28 && value == 0xF0)))
                    writes.add(new Write(tick[0], register, value));
            }

            @Override public void onPsgWrite(int value) {
                if (psg && tick[0] < 9) writes.add(new Write(tick[0], -1, value));
            }
        });
        return writes;
    }

    private static StudioProject nativeFixture() {
        byte[] bank = new byte[48];
        put(bank, 0, 0xB1, 4, 0xE7, 0xB5, 2, 0xF9);
        put(bank, 8, 0, 0, 2, 0, 1, 0, 25, 0x80, 0, 0, 30, 0x80, 0, 0);
        put(bank, 25, 0x89, 2, 0x90, 10, 0xF2);
        put(bank, 30, 0xEF, 0, 0xE0, 0x97, 0xF8, 0, 0x80, 0xF2);
        var sample = DacSample.of("beat", "Beat", 8, new byte[]{(byte) 0x80, (byte) 0xAF});
        var program = NativeProgram.of(0x8000, 8, 0, 360, bank, List.of(),
                List.of(new NativeProgram.DacSlot(0x89, "beat", 8),
                        new NativeProgram.DacSlot(0x90, "beat", 16)));
        return new StudioProject(5, "Native fixture", "S3K", 24, 0,
                List.of(new Voice("voice", "Voice", Collections.nCopies(25, 0))),
                List.of(), List.of(), List.of(), List.of(sample), program, TargetGame.S3K, null);
    }

    private static void put(byte[] data, int start, int... bytes) {
        for (int value : bytes) data[start++] = (byte) value;
    }
}
