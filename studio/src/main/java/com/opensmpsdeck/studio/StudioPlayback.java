package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.CheckpointPlayback;
import com.opensmpsdeck.headless.ProjectRenderTiming;
import com.opensmpsdeck.headless.ReconstructionLivePreview;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.project.AudioReference;
import com.opensmpsdeck.headless.TargetPlayback;
import com.opensmpsdeck.project.StudioProject;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/** Streams both sources through a single 48 kHz stereo device and project-time cursor. */
final class StudioPlayback implements AutoCloseable {
    private static final int RATE = S3kRenderer.SAMPLE_RATE;
    private static final int BLOCK = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;

    record Reference(AudioReference source, ReferenceWave wave) {
        long endFrame() { return frame(source.offsetSeconds() + wave.duration()); }
    }
    @FunctionalInterface
    interface Samples {
        double sample(double projectSeconds, int channel);
    }

    private static final Samples SILENCE = (seconds, channel) -> 0;

    private record Segment(long deviceStart, long projectStart, int length) {}

    private final ExecutorService worker = worker("studio-audio");
    private final ExecutorService renderer = worker("studio-render");
    private final ExecutorService edits = worker("studio-live-render");
    private final ExecutorService warming = worker("studio-checkpoints");
    private final LineFactory lines;
    private Song editableSong;
    private LiveStream activeStream;
    private final AtomicLong generation = new AtomicLong();
    private SourceDataLine line;
    private Future<?> activePlayback;
    private volatile boolean playing;

    @FunctionalInterface
    interface LineFactory {
        SourceDataLine open(AudioFormat format) throws LineUnavailableException;
    }

    StudioPlayback() { this(AudioSystem::getSourceDataLine); }
    StudioPlayback(LineFactory lines) { this.lines = lines; }

    private record CachedRevision(ReconstructionLivePreview.Prepared edit, CheckpointPlayback audio) {}

    private final class Song {
        final StudioProject project;
        final Future<CheckpointPlayback> built;
        volatile CachedRevision latest;
        volatile CachedRevision canonical;
        Future<?> baselineWarmup;
        Future<?> editedWarmup;

        Song(StudioProject project) {
            this.project = project;
            built = renderer.submit(() -> TargetPlayback.prepare(project).checkpoints());
            baselineWarmup = warming.submit(() -> {
                built.get().warmThrough(presentationCount(project));
                return null;
            });
        }

        synchronized void warm(CachedRevision revision) {
            cancelEditedWarmup();
            editedWarmup = warming.submit(() -> {
                revision.audio().warmThrough(presentationCount(project));
                return null;
            });
        }

        synchronized void cancelEditedWarmup() {
            if (editedWarmup != null) {
                editedWarmup.cancel(true);
                editedWarmup = null;
            }
        }

        synchronized void close() {
            built.cancel(true);
            baselineWarmup.cancel(true);
            cancelEditedWarmup();
        }
    }

    synchronized void prepareEditable(StudioProject project) {
        if (editableSong != null && editableSong.project == project) return;
        if (editableSong != null) editableSong.close();
        editableSong = project == null ? null : new Song(project);
    }

    /** Each handle belongs to one transport run. The playback thread owns its readers. */
    final class LiveStream {
        private record Handoff(CachedRevision revision, CheckpointPlayback.Bookmark bookmark,
                               long eventNanos, long replayAt, long replayEnd, Runnable settled) {}

        private final Runnable renderFailed;
        private Song song;
        private CachedRevision built;
        private CachedRevision active;
        private Future<Handoff> pending;
        private CachedRevision otherComparison;
        private Runnable deferredSettlement;
        private boolean preparing;
        private boolean reset;
        private boolean closed;
        private volatile long nextFrame;
        private long requestedSeek = -1;
        private long rangeStart;

        LiveStream(Runnable renderFailed) { this.renderFailed = renderFailed; }

        synchronized void initialize(Song song, CheckpointPlayback baseline, long initial, long rangeStart) {
            if (closed) return;
            this.song = song;
            built = new CachedRevision(null, baseline);
            active = built;
            nextFrame = initial;
            this.rangeStart = rangeStart;
        }

        synchronized boolean ready() { return built != null && !closed; }

        synchronized void compare(ReconstructionLivePreview.Prepared edit, double gesture) {
            replace(edit, 0, gesture, true, true, null);
        }

        synchronized void replace(ReconstructionLivePreview.Prepared edit, long eventNanos,
                                  double gesture, boolean looped) {
            replace(edit, eventNanos, gesture, looped, false, null);
        }

        synchronized void settle(ReconstructionLivePreview.Prepared edit, Runnable callback, long eventNanos) {
            if (!ready()) return;
            Runnable complete = () -> {
                if (eventNanos != 0 && Boolean.getBoolean("opensmpsdeck.liveTiming"))
                    java.util.logging.Logger.getLogger(StudioPlayback.class.getName()).info(String.format(
                            java.util.Locale.ROOT, "Live canonical settled: %.3f ms", (System.nanoTime() - eventNanos) / 1e6));
                callback.run();
            };
            if (pending != null) {
                deferredSettlement = () -> settle(edit, callback, eventNanos);
            } else if (active.edit() != null && edit.sameAudio(active.edit())) {
                song.canonical = active;
                complete.run();
            } else {
                replace(edit, 0, 0, true, false, complete);
            }
        }

        private void replace(ReconstructionLivePreview.Prepared edit, long eventNanos, double gesture,
                             boolean looped, boolean comparing, Runnable settled) {
            if (!ready()) return;
            var alternate = comparing ? otherComparison : null;
            otherComparison = comparing ? active : null;
            cancelPending();
            preparing = false;
            reset = false;
            var previous = active;
            var original = built;
            var cached = song.latest;
            var canonical = song.canonical;
            pending = edits.submit(() -> {
                var changes = edit.changedGates(previous.edit());
                int cutoff = changes.isEmpty() ? Integer.MAX_VALUE : presentation(frame(changes.getFirst().start()));
                var audio = edit.isBuilt() ? original
                        : cached != null && cached.edit() == edit ? cached
                        : alternate != null && alternate.edit() == edit ? alternate
                        : new CachedRevision(edit, previous.audio().revise(edit.playback(), cutoff));

                long replayAt = -1, replayEnd = -1;
                if (!looped && eventNanos != 0) {
                    long heard = frame(gesture);
                    for (var gate : changes) {
                        if (frame(gate.end()) <= heard || frame(gate.start()) > heard + RATE / 8) continue;
                        // Re-cue only an edit heard at the gesture or just ahead of it.
                        replayAt = Math.max(rangeStart, Math.max(heard - RATE / 8, frame(gate.start())));
                        replayEnd = frame(gate.end());
                        break;
                    }
                }
                long target = replayAt >= 0 ? Math.min(replayAt, nextFrame) : nextFrame;
                int at = Math.min(presentation(target), S3kRenderer.MAX_PRESENTATIONS);
                // Provisional audio can invalidate an otherwise reusable canonical prefix.
                // Keep the most recent settled grid available during the next handoff.
                if (settled != null && canonical != null && canonical != previous && audio != original
                        && canonical.audio().checkpointAtOrBefore(at) > audio.audio().checkpointAtOrBefore(at)) {
                    var revised = canonical.audio().revise(edit.playback(), Integer.MAX_VALUE);
                    if (revised.checkpointAtOrBefore(at) > audio.audio().checkpointAtOrBefore(at))
                        audio = new CachedRevision(edit, revised);
                }
                try (var reader = audio.audio().reader()) {
                    return new Handoff(audio, reader.bookmark(at), eventNanos, replayAt, replayEnd, settled);
                }
            });
            notifyAll();
        }

        synchronized void beginEdit() {
            cancelPending();
            if (song != null) song.cancelEditedWarmup();
            preparing = true;
        }

        synchronized void reset() {
            cancelPending();
            preparing = false;
            reset = true;
            notifyAll();
        }

        synchronized boolean seek(double seconds) {
            if (!ready()) return false;
            requestedSeek = frame(seconds);
            return true;
        }

        synchronized long takeSeek() {
            long frame = requestedSeek;
            requestedSeek = -1;
            return frame;
        }

        synchronized boolean editInFlight() {
            return preparing || pending != null;
        }

        Handoff take(boolean waitForEdit) throws InterruptedException {
            Future<Handoff> task;
            synchronized (this) {
                while (waitForEdit && preparing && !closed) wait();
                if (reset) {
                    reset = false;
                    active = built;
                    return new Handoff(built, null, 0, -1, -1, null);
                }
                task = pending;
                if (closed || task == null || !waitForEdit && !task.isDone()) return null;
            }
            Handoff handoff;
            try {
                handoff = task.get();
            } catch (java.util.concurrent.CancellationException cancelled) {
                return null;
            } catch (java.util.concurrent.ExecutionException failure) {
                synchronized (this) {
                    if (pending != task || closed) return null;
                    pending = null;
                }
                renderFailed.run();
                return null;
            }
            synchronized (this) {
                if (closed || pending != task) return null;
                pending = null;
                active = handoff.revision();
                song.latest = active;
                if (handoff.settled() != null) song.canonical = active;
                if (active != built) song.warm(active);
                if (deferredSettlement != null) {
                    var settle = deferredSettlement;
                    deferredSettlement = null;
                    settle.run();
                }
            }
            return handoff;
        }

        synchronized void close() {
            closed = true;
            cancelPending();
            notifyAll();
        }

        private void cancelPending() {
            if (pending != null) pending.cancel(true);
            pending = null;
            deferredSettlement = null;
        }
    }

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(task -> Thread.ofPlatform().name(name).daemon().unstarted(task));
    }

    boolean playing() { return playing; }

    void play(StudioProject project, Consumer<String> status, DoubleConsumer position) {
        play(project, new ReviewTransport(), null, S3kRenderer.ChannelMask.NONE, status,
                seconds -> position.accept(ReviewTransport.tickAt(project, seconds)));
    }

    synchronized void play(StudioProject project, ReviewTransport transport, Reference reference,
                           S3kRenderer.ChannelMask mask, Consumer<String> status, DoubleConsumer position) {
        double gain = reference == null ? 1 : Math.pow(10, reference.source().gainDb() / 20);
        Samples samples = reference == null ? SILENCE
                : (seconds, side) -> reference.wave().sample(seconds - reference.source().offsetSeconds(), side) * gain;
        long referenceEnd = reference == null ? 0 : reference.endFrame();
        start(project, transport, mask, referenceEnd, () -> transport.referenceAudible() ? samples : null, status, position, null, null, null);
    }

    /** A null monitor source selects the live chip renderer. Resolve once per audio block. */
    void playMonitor(StudioProject project, ReviewTransport transport, S3kRenderer.ChannelMask mask,
                     double duration, Supplier<Samples> monitor, Consumer<String> status, DoubleConsumer position) {
        playMonitor(project, transport, mask, duration, monitor, status, position, null);
    }

    void playMonitor(StudioProject project, ReviewTransport transport, S3kRenderer.ChannelMask mask,
                     double duration, Supplier<Samples> monitor, Consumer<String> status, DoubleConsumer position,
                     ReviewTransport.Range span) {
        start(project, transport, mask, frame(duration), monitor, status, position, span, null, null);
    }

    synchronized void playEditable(StudioProject project, ReviewTransport transport, Supplier<Samples> monitor,
                                   Consumer<String> status, DoubleConsumer position, ReviewTransport.Range span,
                                   LiveStream live, ReconstructionLivePreview.Prepared initialPreview) {
        start(project, transport, S3kRenderer.ChannelMask.NONE, 0, monitor, status, position, span, live, initialPreview);
    }

    private synchronized void start(StudioProject project, ReviewTransport transport, S3kRenderer.ChannelMask mask,
                                    long monitorEnd, Supplier<Samples> monitor,
                                    Consumer<String> status, DoubleConsumer position, ReviewTransport.Range span,
                                    LiveStream live, ReconstructionLivePreview.Prepared initialPreview) {
        stop();
        if (live != null) prepareEditable(project);
        var song = live == null ? null : editableSong;
        activeStream = live;
        long version = generation.get(), initial = frame(transport.seconds);
        playing = true;
        activePlayback = worker.submit(() -> {
            Future<?> renderTask = null;
            try {
                int presentations = project == null ? 0 : presentationCount(project);
                long end = span == null ? Math.max((long) presentations * BLOCK, monitorEnd) : frame(span.end());
                if (song != null) {
                    live.initialize(song, song.built.get(), initial, span == null ? 0 : frame(span.start()));
                    if (initialPreview != null) live.replace(initialPreview, 0, 0, true);
                }
                var rendered = live == null ? new RenderBuffer(presentations) : null;
                if (rendered != null && presentations > 0) renderTask = renderer.submit(() -> rendered.render(project, mask));
                var format = new AudioFormat(RATE, 16, 2, true, false);
                var output = lines.open(format);
                synchronized (this) {
                    if (version != generation.get()) return;
                    line = output;
                    output.open(format, BLOCK * 8);
                    output.start();
                }
                status.accept("Playing");
                stream(output, rendered, transport, monitor, initial, end, position, version, live, initialPreview != null);
                if (version == generation.get()) status.accept("Playback finished");
            } catch (Exception error) {
                if (version == generation.get()) status.accept("Preview unavailable: " + error.getMessage());
            } finally {
                if (renderTask != null) renderTask.cancel(true);
                if (live != null) live.close();
                synchronized (this) {
                    if (version == generation.get()) {
                        releaseLine();
                        playing = false;
                    }
                }
            }
        });
    }

    private void stream(SourceDataLine output, RenderBuffer render, ReviewTransport state, Supplier<Samples> monitor,
                        long initial, long end, DoubleConsumer position, long version, LiveStream live,
                        boolean resumeEdit) throws Exception {
        byte[] bytes = new byte[BLOCK * 4];
        var queued = new ArrayDeque<Segment>();
        long written = 0, cursor = initial < end ? initial : 0;
        try (var audio = new EditableReader(live)) {
            while (version == generation.get()) {
                var loop = state.loop;
                long boundary = end;
                long seek = live == null ? -1 : live.takeSeek();
                if (seek >= 0) {
                    output.flush();
                    queued.clear();
                    written = output.getLongFramePosition();
                    cursor = seek;
                    audio.clearTiming();
                }
                if (live != null && seek < 0 && (loop != null || cursor < end)
                        && written - output.getLongFramePosition() >= BLOCK) {
                    // Some mixers accept more queued PCM than the requested line buffer.
                    // Keep at most two presentations ahead of the device for live swaps.
                    LockSupport.parkNanos(750_000);
                    continue;
                }
                if (loop != null) {
                    long first = frame(loop.start());
                    boundary = Math.max(first + 1, frame(loop.end()));
                    if (cursor >= boundary || cursor < first) cursor = first;
                }
                if (live != null) {
                    live.nextFrame = cursor;
                    var handoff = live.take(resumeEdit);
                    resumeEdit = false;
                    if (handoff != null) {
                        if (loop == null && seek < 0 && monitor.get() == null && handoff.replayAt() >= 0
                                && cursor >= handoff.replayEnd())
                            cursor = handoff.replayAt();
                        audio.swap(handoff);
                        if (handoff.settled() != null) handoff.settled().run();
                    }
                }
                if (cursor >= end && loop == null) {
                    if (live != null) {
                        long played = output.getLongFramePosition();
                        audio.report(played);
                        // The last queued block may still be audible and editable.
                        if (played < written || live.editInFlight()) {
                            LockSupport.parkNanos(750_000);
                            continue;
                        }
                    }
                    output.drain();
                    audio.report(output.getLongFramePosition());
                    position.accept(end / (double) RATE);
                    return;
                }
                int index = (int) (cursor % BLOCK);
                int count = (int) Math.min(BLOCK - index, boundary - cursor);
                var samples = monitor.get();
                if (samples != null) {
                    for (int i = 0; i < count; i++) {
                        double time = (cursor + i) / (double) RATE;
                        for (int channel = 0; channel < 2; channel++) {
                            double sample = samples.sample(time, channel);
                            int pcm = sample == 0 ? 0 : (int) Math.round(Math.clamp(sample * 32768.0, -32768, 32767));
                            put(bytes, i * 2 + channel, pcm);
                        }
                    }
                } else {
                    int presentation = presentation(cursor);
                    short[] pcm = live != null ? audio.at(presentation)
                            : presentation >= render.size() ? null : render.get(presentation);
                    for (int i = 0; i < count * 2; i++) put(bytes, i, pcm == null ? 0 : pcm[index * 2 + i]);
                    audio.compare(pcm, cursor, index, count, written);
                }
                if (version != generation.get() || output.write(bytes, 0, count * 4) != count * 4) return;
                queued.addLast(new Segment(written, cursor, count));
                written += count;
                cursor += count;
                long played = output.getLongFramePosition();
                audio.report(played);
                while (queued.size() > 1 && played >= queued.getFirst().deviceStart() + queued.getFirst().length()) {
                    queued.removeFirst();
                }
                var playingSegment = queued.getFirst();
                long progress = Math.clamp(played - playingSegment.deviceStart(), 0, playingSegment.length());
                position.accept((playingSegment.projectStart() + progress) / (double) RATE);
            }
        }
    }

    private static final class EditableReader implements AutoCloseable {
        private CheckpointPlayback.Reader current;
        private CheckpointPlayback.Reader previous;
        private long eventNanos;
        private long changedFrame = -1;
        private long changedDeviceFrame;
        private long handed;

        EditableReader(LiveStream live) {
            if (live != null) current = live.built.audio().reader();
        }

        short[] at(int presentation) throws InterruptedIOException { return current.at(presentation); }

        void swap(LiveStream.Handoff handoff) {
            clearTiming();
            if (handoff.eventNanos() != 0 && Boolean.getBoolean("opensmpsdeck.liveTiming")) {
                previous = current;
                eventNanos = handoff.eventNanos();
            } else current.close();
            current = handoff.bookmark() == null ? handoff.revision().audio().reader() : handoff.bookmark().reader();
        }

        void compare(short[] pcm, long cursor, int index, int count, long written) throws InterruptedIOException {
            if (previous == null) return;
            short[] before = previous.at(presentation(cursor));
            for (int i = index * 2; i < (index + count) * 2; i++) {
                if (before[i] == pcm[i]) continue;
                int offset = i / 2 - index;
                changedFrame = cursor + offset;
                changedDeviceFrame = written + offset;
                handed = System.nanoTime();
                previous.close();
                previous = null;
                break;
            }
        }

        void report(long played) {
            if (changedFrame < 0 || played <= changedDeviceFrame) return;
            System.getLogger(StudioPlayback.class.getName()).log(System.Logger.Level.INFO,
                    String.format(java.util.Locale.ROOT, "Live PCM frame %d: %.3f ms write, %.3f ms device cursor",
                            changedFrame, (handed - eventNanos) / 1_000_000.0,
                            (System.nanoTime() - eventNanos) / 1_000_000.0));
            changedFrame = -1;
        }

        void clearTiming() {
            changedFrame = -1;
            if (previous != null) previous.close();
            previous = null;
        }

        @Override public void close() {
            clearTiming();
            if (current != null) current.close();
        }
    }

    private static int presentation(long frame) { return (int) (frame / BLOCK); }

    private static void put(byte[] bytes, int index, int value) {
        bytes[index * 2] = (byte) value;
        bytes[index * 2 + 1] = (byte) (value >> 8);
    }

    private static long frame(double seconds) {
        return Math.clamp(Math.round(seconds * RATE), 0, Long.MAX_VALUE / 2);
    }

    static int presentationCount(StudioProject project) {
        return ProjectRenderTiming.presentationCount(project);
    }

    static double tickAtPresentation(StudioProject project, int presentations) {
        return ReviewTransport.tickAt(project, presentations / 60.0);
    }

    synchronized void stop() {
        generation.incrementAndGet();
        playing = false;
        if (activePlayback != null) activePlayback.cancel(true);
        if (activeStream != null) activeStream.close();
        activeStream = null;
        releaseLine();
    }

    private void releaseLine() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
    }

    @Override public void close() {
        stop();
        worker.shutdownNow();
        renderer.shutdownNow();
        edits.shutdownNow();
        prepareEditable(null);
        warming.shutdownNow();
    }

    /** Keep each rendered presentation for sample-aligned looping and monitor switches. */
    static final class RenderBuffer {
        private final short[][] frames;
        private int next;
        private Exception error;

        RenderBuffer(int presentations) { frames = new short[presentations][]; }
        int size() { return frames.length; }

        void render(StudioProject source, S3kRenderer.ChannelMask mask) {
            try {
                render(TargetPlayback.prepare(source), mask);
            } catch (Exception failure) {
                failed(failure);
            }
        }

        void render(TargetPlayback playback, S3kRenderer.ChannelMask mask) {
            try {
                playback.render(size(), mask, this::append);
            } catch (Exception failure) {
                failed(failure);
            }
        }

        private synchronized void failed(Exception failure) {
            error = failure;
            notifyAll();
        }

        private synchronized void append(short[] pcm) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            frames[next++] = pcm.clone();
            notifyAll();
        }

        synchronized short[] get(int index) throws Exception {
            while (index >= next && error == null) wait();
            if (index >= next) throw error;
            return frames[index];
        }
    }
}
