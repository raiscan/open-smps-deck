package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Finds a repeating point shared by the native tracks, including their call and counted-loop state. */
final class NativeExportLength {
    record Loop(int start, int period) {}
    record Playback(int end, Loop loop, boolean finished) {}

    private NativeExportLength() {}

    static Playback measure(CompiledProject compiled, int passes) throws InterruptedIOException {
        var detector = new Jumps();
        var sequencer = S3kRenderer.timelineSequencer(compiled, () -> {}, detector);
        var visited = new HashMap<List<Integer>, Integer>();
        List<Integer> repeated = null;
        Loop loop = null;
        int completed = 0;
        for (int frame = 1; frame <= S3kRenderer.MAX_PRESENTATIONS; frame++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Loop analysis cancelled");
            int[] positions = sequencer.getTracks().stream().mapToInt(track -> track.pos).toArray();
            detector.backward = false;
            sequencer.serviceOuterFrame();
            if (sequencer.isComplete()) return new Playback(frame, null, true);
            var tracks = sequencer.getTracks();
            boolean advanced = detector.backward;
            for (int index = 0; index < positions.length; index++)
                advanced |= tracks.get(index).pos != positions[index];
            if (!advanced) continue;
            var key = state(tracks, sequencer.getNormalTempo());
            if (loop == null) {
                Integer previous = visited.putIfAbsent(key, frame);
                if (previous != null && detector.backward) {
                    loop = new Loop(previous, frame - previous);
                    repeated = key;
                    visited.clear();
                }
            }
            // Tempo carries can make successive musical passes differ by a presentation.
            if (key.equals(repeated) && detector.backward && ++completed == passes)
                return new Playback(frame, loop, true);
        }
        return new Playback(compiled.nativeProgram().previewFrames(), null, false);
    }

    private static List<Integer> state(List<Track> tracks, int tempo) {
        var key = new ArrayList<Integer>();
        key.add(tempo);
        for (var track : tracks) {
            key.add(track.active ? track.pos : -1);
            if (!track.active) continue;
            key.add(track.note);
            key.add(track.duration);
            key.add(track.rawDuration);
            key.add(track.dividingTiming);
            key.add(track.returnSp);
            for (int i = 0; i < track.returnSp; i++) key.add(track.returnStack[i]);
            for (int counter : track.loopCounters) key.add(counter);
        }
        return key;
    }

    private static final class Jumps extends Sonic3kCoordFlagHandler {
        boolean backward;

        @Override public boolean handleFlag(CoordFlagContext context, Track track, int opcode) {
            int operand = track.pos;
            boolean handled = super.handleFlag(context, track, opcode);
            if (opcode == 0xf6 && track.pos < operand) backward = true;
            return handled;
        }
    }
}
