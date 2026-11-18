package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

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
        assertEquals(List.of(new CommandEvent("pan", 0, NativeCommand.PAN, List.of(0x97))), fm.commands());
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
                List.of(), List.of(), List.of(), List.of(), List.of(), program);
        var psg = NativeArrangement.editableCopy(source).sequences().getFirst();
        assertEquals(List.of(45, 60), psg.notes().stream().map(NoteEvent::pitch).toList());
        assertEquals(List.of(new CommandEvent("transpose-0", 0, NativeCommand.TRANSPOSE, List.of(0x37)),
                new CommandEvent("transpose-2", 2, NativeCommand.TRANSPOSE, List.of(0x40))), psg.commands());
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
                List.of(), List.of(), List.of(), List.of(sample), program);
    }

    private static void put(byte[] data, int start, int... bytes) {
        for (int value : bytes) data[start++] = (byte) value;
    }
}
