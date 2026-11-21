package com.opensmpsdeck.headless;

import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.audio.smps.SmpsNoteListener;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Walk compiled S3K events, including the clip setup emitted by the compiler. */
final class ReconstructionCompiledTimeline extends Sonic3kCoordFlagHandler implements SmpsNoteListener {
    record Difference(boolean available, int changedEvents, Integer firstTick, List<String> lanes) {}
    private record Position(int tick, String lane) {}
    private record Event(int opcode, Object value) {}

    private static final int ATTACK = -1;
    private static final int RELEASE = -2;

    private final Map<Position, List<Event>> events = new HashMap<>();
    private final Map<Integer, List<Integer>> psgEnvelopes;
    private final Map<Integer, List<Integer>> modulationEnvelopes;
    private final List<String> samples;
    private final Set<Track> sounding = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ReconstructionTimeMap time;
    private final ReconstructionTrace.Span span;
    private int tick;

    private ReconstructionCompiledTimeline(CompiledProject project,
                                           ReconstructionTimeMap time, ReconstructionTrace.Span span) {
        psgEnvelopes = project.psgEnvelopes();
        modulationEnvelopes = project.modulationEnvelopes();
        samples = project.samples().stream()
                .map(sample -> sample.rate() + ":" + InstrumentCatalogue.digest(sample.pcm())).toList();
        this.time = time;
        this.span = span;
    }

    static Difference diff(Path before, Path after, ReconstructionTrace.Span span) throws IOException {
        var old = read(before, span);
        var updated = read(after, span);
        if (old == null || updated == null) return new Difference(false, 0, null, List.of());
        var positions = new java.util.HashSet<>(old.keySet());
        positions.addAll(updated.keySet());
        var lanes = new TreeSet<String>();
        int count = 0;
        int first = Integer.MAX_VALUE;
        for (var position : positions) {
            int changed = changed(old.getOrDefault(position, List.of()), updated.getOrDefault(position, List.of()));
            if (changed == 0) continue;
            count += changed;
            first = Math.min(first, position.tick());
            lanes.add(position.lane());
        }
        return new Difference(true, count, count == 0 ? null : first, List.copyOf(lanes));
    }

    // Pair ordered events at a lane/tick, skipping a common suffix so an inserted
    // reset command does not make every following event at that tick look changed.
    private static int changed(List<Event> before, List<Event> after) {
        if (before.equals(after)) return 0;
        int oldEnd = before.size(), newEnd = after.size();
        while (oldEnd > 0 && newEnd > 0 && before.get(oldEnd - 1).equals(after.get(newEnd - 1))) {
            oldEnd--;
            newEnd--;
        }
        int count = Math.abs(oldEnd - newEnd);
        for (int i = 0; i < Math.min(oldEnd, newEnd); i++) {
            if (!before.get(i).equals(after.get(i))) count++;
        }
        return count;
    }

    private static Map<Position, List<Event>> read(Path work, ReconstructionTrace.Span span) throws IOException {
        Path file = work.resolve("arrangement.smps.json");
        if (!Files.exists(file)) return null;
        var project = ProjectFiles.load(file).project();
        if (project.nativeProgram() != null) return null;
        var time = ReconstructionTimeMap.of(ReconstructionStages.readScore(work.resolve("score.json")));
        var compiled = ProjectCompiler.compileWithAssets(project);
        var recorder = new ReconstructionCompiledTimeline(compiled, time, span);
        var sequencer = S3kRenderer.timelineSequencer(compiled, () -> {}, recorder);
        sequencer.setNoteListener(recorder);
        // Include the stop immediately after the final gate.
        int limit = Math.max(time.frames(), project.clips().stream()
                .mapToInt(clip -> clip.startTick() + clip.lengthTicks()).max().orElse(0));
        for (; recorder.tick <= limit && !sequencer.isComplete(); recorder.tick++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Compiled timeline interrupted");
            sequencer.serviceOuterFrame();
        }
        return recorder.events;
    }

    private void add(Track track, int opcode, Object value) {
        double source = time.source(tick / (double) S3kRenderer.PRESENTATIONS_PER_SECOND);
        if (span != null && (span.start() == null || source >= span.start())
                && (span.end() == null || source < span.end())) return;
        String lane = track.type == TrackType.DAC ? "DAC" : track.type.name() + (track.channelId + 1);
        events.computeIfAbsent(new Position(tick, lane), ignored -> new ArrayList<>()).add(new Event(opcode, value));
    }

    @Override public boolean handleFlag(CoordFlagContext context, Track track, int opcode) {
        int start = track.pos;
        boolean handled = super.handleFlag(context, track, opcode);
        var args = new ArrayList<Integer>();
        for (int i = start; i < track.pos; i++) args.add(context.programView().dataByteAt(i) & 0xff);
        Object value = switch (opcode) {
            // Resolve compiled table indices to asset content.
            case 0xEF -> track.type == TrackType.FM && track.voiceData != null
                    ? InstrumentCatalogue.digest(track.voiceData) : args;
            case 0xF1, 0xF4 -> modulationEnvelopes.getOrDefault(track.modEnvId, List.of());
            case 0xF5 -> psgEnvelopes.getOrDefault(args.getFirst(), List.of());
            default -> args;
        };
        add(track, opcode, value);
        return handled;
    }

    @Override public void attack(Track track) {
        if (track.type == TrackType.DAC) add(track, ATTACK, samples.get(track.note - 0x81));
        else {
            sounding.add(track);
            add(track, ATTACK, track.note);
        }
    }

    @Override public void release(Track track) {
        if (sounding.remove(track)) add(track, RELEASE, List.of());
    }
}
