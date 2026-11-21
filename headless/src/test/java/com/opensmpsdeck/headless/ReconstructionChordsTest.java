package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionChordsTest {
    @Test void thePinnedDriverSoundsBothCarriersOfAnInvertedPowerChord() throws Exception {
        // G3 below C4 is the inverted voicing. Its reduced multiples are 3:4.
        var tuning = ReconstructionChords.tune(List.of(55, 60));
        assertEquals(ReconstructionChords.Quality.power, tuning.quality());
        assertEquals(0, tuning.rootPitchClass());
        assertEquals(List.of(3, 4), tuning.multiples());

        byte[] held = {7, 1, 1, 1, 1, 31, 31, 31, 31, 0, 0, 0, 0, 0, 0, 0, 0,
                15, 15, 15, 15, (byte) 0x98, (byte) 0x98, (byte) 0x98, (byte) 0x98};
        var patch = ReconstructionChords.patch("Held", held, tuning, Set.of(0, 1));
        var sequence = new Sequence("held", "Inverted power chord", "FM", 60, patch.id(), 100,
                List.of(new NoteEvent("note", 0, 60, tuning.note())),
                List.of(new CommandEvent("tune", 0, NativeCommand.DETUNE, List.of(tuning.detune() & 255))), "RESET");
        var project = new StudioProject(4, "Chord probe", "S3K", 30, 0, List.of(patch),
                List.of(sequence), List.of(new Clip("probe", "held", "FM1", 0, 60)));
        double[] pcm = new double[S3kRenderer.SAMPLE_RATE];
        var offset = new AtomicInteger();
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), 60, stereo -> {
            for (int i = 0; i < stereo.length; i += 2)
                pcm[offset.getAndIncrement()] = (stereo[i] + stereo[i + 1]) / 2.0;
        });
        // Measuring the combined PCM catches a wrong carrier slot or base-note octave.
        assertTrue(peak(pcm, 196) > 10 * peak(pcm, 180));
        assertTrue(peak(pcm, 261.63) > 10 * peak(pcm, 243));
    }

    @Test void qualityAndRegisterLimitsKeepUnrepresentableTonesOutOfThePatch() {
        assertEquals(List.of(5, 6, 8), ReconstructionChords.tune(List.of(64, 67, 72)).multiples());
        assertEquals(List.of(10, 12, 15), ReconstructionChords.tune(List.of(60, 63, 67)).multiples());
        assertEquals(List.of(8, 9, 12), ReconstructionChords.tune(List.of(60, 62, 67)).multiples());
        assertEquals(List.of(6, 8, 9), ReconstructionChords.tune(List.of(60, 65, 67)).multiples());
        assertEquals(List.of(4, 5, 6, 8), ReconstructionChords.tune(List.of(60, 64, 67, 72)).multiples());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ReconstructionChords.tune(List.of(60, 64, 67, 71)))
                .getMessage().contains("unsupported chord quality"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ReconstructionChords.tune(List.of(48, 64, 91)))
                .getMessage().contains("carrier multiples"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ReconstructionChords.tune(List.of(36, 39, 43)))
                .getMessage().contains("F-number/block"));
    }

    @Test void aCollapsedGroupLeavesFmLanesForOtherAttacks() {
        var parts = List.of(new MidiScore.Part(0, "Chord", 0, false), new MidiScore.Part(1, "Backing", 0, false));
        var role = ReconstructionRoles.Role.harmony;
        var notes = List.of(
                new ReconstructionStages.Note("root", 0, 0, 1, 60, 96, "midi", 1),
                new ReconstructionStages.Note("third", 0, 0, 1, 64, 96, "midi", 1),
                new ReconstructionStages.Note("fifth", 0, 0, 1, 67, 96, "midi", 1),
                new ReconstructionStages.Note("other-1", 1, .1, .8, 51, 96, "midi", 1),
                new ReconstructionStages.Note("other-2", 1, .1, .8, 55, 96, "midi", 1));
        var roles = new ReconstructionRoles.Result(1, 7.5,
                notes.stream().map(note -> new ReconstructionRoles.Decision(note, role, null, null)).toList(), null);
        var original = ReconstructionAllocation.allocate(roles, parts, 60, true);
        assertNull(original.notes().get(3).lane());
        assertNull(original.notes().get(4).lane());

        var group = new ReconstructionChords.Group("one-lane", 2, ReconstructionChords.Order.down, null,
                List.of(new ReconstructionChords.Member("root", 60, 60),
                        new ReconstructionChords.Member("third", 64, 60),
                        new ReconstructionChords.Member("fifth", 67, 60)));
        var result = ReconstructionAllocation.allocateWithAffinity(roles, parts, 60, true, false, Map.of(),
                Map.of(), Map.of(), Map.of("root", group, "third", group, "fifth", group), null);
        assertEquals(3, result.notes().stream().filter(note -> note.lane() != null).count());
        assertNotNull(result.notes().get(3).lane());
        assertNotNull(result.notes().get(4).lane());
        assertEquals("root", result.notes().get(1).mergedInto());
        assertEquals(List.of(67, 64, 60), ReconstructionChords.steps(group, 0, 6).stream()
                .map(step -> step.member().pitch()).toList());
    }

    private static double peak(double[] pcm, double hz) {
        double real = 0, imaginary = 0;
        for (int i = 4000; i < pcm.length - 4000; i++) {
            double phase = 2 * Math.PI * hz * i / S3kRenderer.SAMPLE_RATE;
            real += pcm[i] * Math.cos(phase);
            imaginary += pcm[i] * Math.sin(phase);
        }
        return Math.hypot(real, imaginary);
    }
}
