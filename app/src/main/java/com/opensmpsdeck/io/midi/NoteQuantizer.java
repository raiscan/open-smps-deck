package com.opensmpsdeck.io.midi;

import java.util.ArrayList;
import java.util.List;

/** Snaps note on/off times to a 16th-note step grid. */
public final class NoteQuantizer {

    public record QuantizedNote(int startStep, int lengthSteps, int pitch, int velocity) {}

    private NoteQuantizer() {}

    public static List<QuantizedNote> quantize(List<NoteEvent> notes, int ppq) {
        double ticksPerStep = ppq / 4.0;
        List<QuantizedNote> out = new ArrayList<>(notes.size());
        for (NoteEvent n : notes) {
            int start = (int) Math.round(n.startTick() / ticksPerStep);
            int end = (int) Math.round(n.endTick() / ticksPerStep);
            out.add(new QuantizedNote(start, Math.max(1, end - start), n.pitch(), n.velocity()));
        }
        return out;
    }
}
