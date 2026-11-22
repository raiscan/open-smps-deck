package com.opensmpsdeck.headless;

import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Throwaway S3K experiment. The timed sink consumes real PCM at 60 Hz. */
public final class LiveEditSpike {
    private static final long SECOND = 1_000_000_000L;
    private static final int HZ = S3kRenderer.PRESENTATIONS_PER_SECOND;
    private static final int PCM_SIZE = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2;
    private static final int DEVICE_QUEUE = 2;
    private static final int TRIALS = 8;

    private record Edit(Sequence sequence, NoteEvent note, int onset) {
        StudioProject apply(StudioProject project) {
            return ProjectEdits.updateNote(project, sequence.id(), new NoteEvent(
                    note.id(), note.tick(), note.duration() - 4, note.pitch() + 7));
        }
    }

    private record Block(int frame, short[] pcm, long compiledAt, long caughtUpAt) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a copied arrangement.smps.json");
        var project = ProjectFiles.load(Path.of(args[0])).project();
        if (project.target() != TargetGame.S3K || project.nativeProgram() != null)
            throw new IllegalArgumentException("Expected an editable S3K reconstruction");

        var edit = findEdit(project);
        int editFrame = edit.onset() + 2;
        int firstFrame = Math.max(0, editFrame - 12);
        int endFrame = editFrame + 48;
        System.out.printf("Editing a held FM1 note at %.3f s, pitch +7, gate -4 ticks%n", editFrame / (double) HZ);

        var master = TargetPlayback.prepare(project);
        var original = reference(master, endFrame);
        var expected = reference(TargetPlayback.prepare(edit.apply(project)), endFrame);
        try (var builder = Executors.newSingleThreadExecutor()) {
            for (int trial = 0; trial <= TRIALS; trial++) {
                runTrial(builder, project, master, edit, editFrame, firstFrame, endFrame, original, expected, trial);
            }
        }
        int replayFrames = Math.min(60 * HZ, ProjectRenderTiming.presentationCount(project));
        long replayStart = System.nanoTime();
        master.render(replayFrames, pcm -> {});
        System.out.printf(java.util.Locale.ROOT, "Replay to %.2f s took %.2f ms%n",
                replayFrames / (double) HZ, (System.nanoTime() - replayStart) / 1e6);
    }

    private static Edit findEdit(StudioProject project) {
        return project.clips().stream().filter(Clip.class::isInstance).map(Clip.class::cast)
                .filter(clip -> clip.lane().equals("FM1"))
                .filter(clip -> project.clips().stream().filter(Clip.class::isInstance).map(Clip.class::cast)
                        .filter(placement -> placement.sequenceId().equals(clip.sequenceId())).count() == 1)
                .flatMap(clip -> project.sequences().stream()
                        .filter(sequence -> sequence.id().equals(clip.sequenceId())
                                && clip.lengthTicks() <= sequence.lengthTicks())
                        .flatMap(sequence -> sequence.notes().stream()
                                .filter(note -> note.duration() >= 18 && note.pitch() <= 80
                                        && note.tick() + note.duration() <= clip.lengthTicks())
                                .map(note -> new Edit(sequence, note, (int) Math.ceil(
                                        (clip.startTick() + note.tick()) * 256.0 / (256 - project.tempo()))))))
                .min(Comparator.comparingInt(Edit::onset))
                .orElseThrow(() -> new IllegalArgumentException("No suitable unlinked, sustained FM1 note"));
    }

    private static short[][] reference(TargetPlayback playback, int endFrame) throws Exception {
        var blocks = new short[endFrame][];
        int[] frame = {0};
        playback.render(endFrame, pcm -> blocks[frame[0]++] = pcm.clone());
        return blocks;
    }

    private static void runTrial(
            ExecutorService builder, StudioProject project, TargetPlayback master, Edit edit, int editFrame,
            int firstFrame, int endFrame, short[][] original, short[][] expected, int trial) throws Exception {
        var head = new AtomicInteger(firstFrame);
        var pcm = new short[PCM_SIZE];
        var ready = new ArrayBlockingQueue<Block>(3);
        Future<?> job = null;
        Block swap = null;
        long editedAt = 0;
        long firstDifferentAt = 0;
        double differenceRms = 0;
        long maxLate = 0;
        try (var session = open(master)) {
            advance(session, 0, firstFrame, pcm);
            long start = System.nanoTime();
            for (int frame = firstFrame; frame < endFrame; frame++) {
                long deadline = start + (frame - firstFrame) * SECOND / HZ;
                waitUntil(deadline);
                head.set(frame);
                if (frame == editFrame) {
                    editedAt = System.nanoTime();
                    job = builder.submit(() -> {
                        prepare(project, edit, head, endFrame, ready);
                        return null;
                    });
                }
                if (job != null && job.isDone()) job.get();
                if (swap == null) {
                    while (ready.peek() != null && ready.peek().frame() < frame) ready.remove();
                    if (ready.peek() != null && ready.peek().frame() == frame) {
                        swap = ready.remove();
                        pcm = swap.pcm();
                    } else {
                        S3kRenderer.advance(session, pcm);
                    }
                } else {
                    var next = ready.poll(100, TimeUnit.MILLISECONDS);
                    if (next == null || next.frame() != frame)
                        throw new IllegalStateException("Replacement missed block " + frame);
                    pcm = next.pcm();
                }
                long submittedAt = System.nanoTime();
                maxLate = Math.max(maxLate, submittedAt - deadline);
                if (!Arrays.equals(swap == null ? original[frame] : expected[frame], pcm))
                    throw new IllegalStateException("PCM diverged from continuous render at frame " + frame);
                if (swap != null && firstDifferentAt == 0) {
                    double rms = rmsDifference(pcm, original[frame]);
                    if (rms >= 32) {
                        differenceRms = rms;
                        firstDifferentAt = submittedAt + DEVICE_QUEUE * SECOND / HZ;
                    }
                }
            }
            if (firstDifferentAt == 0)
                throw new IllegalStateException("No changed PCM above the threshold at the advancing playhead");
            System.out.printf(java.util.Locale.ROOT,
                    "%s%d compile-ready=%.2f ms catch-up=%.2f ms edit-to-PCM=%.2f ms delta=%.1f RMS worst-block-late=%.2f ms%n",
                    trial == 0 ? "warmup " : "trial ", trial,
                    (swap.compiledAt() - editedAt) / 1e6, (swap.caughtUpAt() - swap.compiledAt()) / 1e6,
                    (firstDifferentAt - editedAt) / 1e6, differenceRms, maxLate / 1e6);
        } finally {
            if (job != null) job.cancel(true);
        }
    }

    private static void prepare(StudioProject project, Edit edit, AtomicInteger head, int endFrame,
                                ArrayBlockingQueue<Block> ready) throws InterruptedException {
        var playback = TargetPlayback.prepare(edit.apply(project));
        long compiledAt = System.nanoTime();
        try (var session = open(playback)) {
            var pcm = new short[PCM_SIZE];
            int next = 0;
            for (int target = head.get(); next < target; target = head.get()) {
                advance(session, next, target, pcm);
                next = target;
            }
            long caughtUpAt = System.nanoTime();
            for (int frame = next; frame < endFrame; frame++) {
                S3kRenderer.advance(session, pcm);
                ready.put(new Block(frame, pcm.clone(), compiledAt, caughtUpAt));
            }
        }
    }

    private static SmpsDriverSession open(TargetPlayback playback) {
        return playback.open(S3kRenderer.ChannelMask.NONE, ChipWriteObserver.NONE);
    }

    private static void advance(SmpsDriverSession session, int first, int end, short[] pcm) {
        for (int i = first; i < end; i++) S3kRenderer.advance(session, pcm);
    }

    private static void waitUntil(long deadline) {
        for (long remaining; (remaining = deadline - System.nanoTime()) > 0; )
            LockSupport.parkNanos(remaining);
    }

    private static double rmsDifference(short[] first, short[] second) {
        double squared = 0;
        for (int i = 0; i < first.length; i++) {
            double difference = first[i] - second[i];
            squared += difference * difference;
        }
        return Math.sqrt(squared / first.length);
    }
}
