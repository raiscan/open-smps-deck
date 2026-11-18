package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeArrangementTest {
    @Test void projectsSharedPhrasesAndDacHitsAtPresentationTimes() throws Exception {
        byte[] bank = new byte[48];
        put(bank, 0, 0xB1, 4, 0xE7, 0xB5, 2, 0xF9);
        put(bank, 8, 0, 0, 2, 0, 1, 0, 26, 0x80, 0, 0, 30, 0x80, 0, 0);
        put(bank, 26, 0x89, 12, 0xF2);
        put(bank, 30, 0xEF, 0, 0xF8, 0, 0x80, 0xF2);
        var sample = DacSample.of("beat", "Beat", 8, new byte[]{(byte) 0x80, (byte) 0xAF});
        var program = NativeProgram.of(0x8000, 8, 0, 360, bank, List.of(),
                List.of(new NativeProgram.DacSlot(0x89, "beat", 8)));
        var original = new StudioProject(5, "Native fixture", "S3K", 24, 0,
                List.of(new Voice("voice", "Voice", Collections.nCopies(25, 0))),
                List.of(), List.of(), List.of(), List.of(sample), program);
        var view = NativeArrangement.project(original);
        assertEquals(60, view.ticksPerBeat());
        assertEquals(2, view.clips().size());
        var fm = view.sequences().stream().filter(sequence -> sequence.type().equals("FM")).findFirst().orElseThrow();
        assertEquals(List.of(60, 64), fm.notes().stream().map(note -> note.pitch()).toList());
        assertEquals(List.of(0, 4), fm.notes().stream().map(note -> note.tick()).toList());
        assertEquals(List.of(4, 2), fm.notes().stream().map(note -> note.duration()).toList());
        var dac = view.sequences().stream().filter(sequence -> sequence.type().equals("DAC")).findFirst().orElseThrow();
        assertEquals("beat", dac.triggers().getFirst().sampleId());
        assertEquals(0, dac.triggers().getFirst().tick());
        assertArrayEquals(bank, original.nativeProgram().bank());
        assertTrue(original.clips().isEmpty());
    }

    private static void put(byte[] data, int start, int... bytes) {
        for (int value : bytes) data[start++] = (byte) value;
    }
}
