package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ProjectRenderTiming;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.project.AudioReference;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.StudioProject;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Streams both sources through a single 48 kHz stereo device and project-time cursor. */
final class StudioPlayback implements AutoCloseable {
    private static final int RATE = S3kRenderer.SAMPLE_RATE;
    private static final int BLOCK = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;

    record Reference(AudioReference source, ReferenceWave wave) {
        long endFrame() { return frame(source.offsetSeconds() + wave.duration()); }
    }
    private record Segment(long deviceStart, long projectStart, int length) {}

    private final ExecutorService worker = worker("studio-audio");
    private final ExecutorService renderer = worker("studio-render");
    private final AtomicLong generation = new AtomicLong();
    private SourceDataLine line;
    private Future<?> activePlayback;
    private volatile boolean playing;

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(task -> Thread.ofPlatform().name(name).daemon().unstarted(task));
    }

    boolean playing() { return playing; }

    void play(StudioProject project, Consumer<String> status, DoubleConsumer position) {
        play(project, new ReviewTransport(), null, S3kRenderer.ChannelMask.NONE, status,
                seconds -> position.accept(seconds * ReviewTransport.ticksPerSecond(project)));
    }

    synchronized void play(StudioProject project, ReviewTransport transport, Reference reference,
                           S3kRenderer.ChannelMask mask, Consumer<String> status, DoubleConsumer position) {
        stop();
        long version = generation.get(), initial = frame(transport.seconds);
        playing = true;
        activePlayback = worker.submit(() -> {
            Future<?> renderTask = null;
            try {
                int presentations = presentationCount(project);
                long end = Math.max((long) presentations * BLOCK, reference == null ? 0 : reference.endFrame());
                var rendered = new RenderBuffer(presentations);
                var format = new AudioFormat(RATE, 16, 2, true, false);
                var output = AudioSystem.getSourceDataLine(format);
                synchronized (this) {
                    if (version != generation.get()) return;
                    line = output;
                    output.open(format, BLOCK * 8);
                    output.start();
                }
                renderTask = renderer.submit(() -> rendered.render(project, mask));
                status.accept("Playing");
                stream(output, rendered, transport, reference, initial, end, position, version);
                if (version == generation.get()) status.accept("Playback finished");
            } catch (Exception error) {
                if (version == generation.get()) status.accept("Preview unavailable: " + error.getMessage());
            } finally {
                if (renderTask != null) renderTask.cancel(true);
                synchronized (this) {
                    if (version == generation.get()) {
                        releaseLine();
                        playing = false;
                    }
                }
            }
        });
    }

    private void stream(SourceDataLine output, RenderBuffer render, ReviewTransport state, Reference reference,
                        long initial, long end, DoubleConsumer position, long version) throws Exception {
        byte[] bytes = new byte[BLOCK * 4];
        var queued = new ArrayDeque<Segment>();
        double gain = reference == null ? 1 : Math.pow(10, reference.source().gainDb() / 20);
        long written = 0, cursor = initial < end ? initial : 0;
        while (version == generation.get()) {
            var loop = state.loop;
            long boundary = end;
            if (loop != null) {
                long first = frame(loop.start());
                boundary = Math.max(first + 1, frame(loop.end()));
                if (cursor >= boundary || cursor < first) cursor = first;
            } else if (cursor >= end) {
                output.drain();
                position.accept(end / (double) RATE);
                return;
            }
            int index = (int) (cursor % BLOCK);
            int count = (int) Math.min(BLOCK - index, boundary - cursor);
            boolean listenToReference = state.referenceAudible();
            if (listenToReference && reference != null) {
                for (int i = 0; i < count; i++) {
                    double time = (cursor + i) / (double) RATE - reference.source().offsetSeconds();
                    for (int channel = 0; channel < 2; channel++) {
                        float sample = reference.wave().sample(time, channel);
                        int pcm = sample == 0 ? 0 : (int) Math.round(Math.clamp(sample * gain * 32768, -32768, 32767));
                        put(bytes, i * 2 + channel, pcm);
                    }
                }
            } else {
                short[] pcm = listenToReference || cursor / BLOCK >= render.size()
                        ? null : render.get((int) (cursor / BLOCK));
                for (int i = 0; i < count * 2; i++) put(bytes, i, pcm == null ? 0 : pcm[index * 2 + i]);
            }
            if (version != generation.get() || output.write(bytes, 0, count * 4) != count * 4) return;
            queued.addLast(new Segment(written, cursor, count));
            written += count;
            cursor += count;
            long played = output.getLongFramePosition();
            while (queued.size() > 1 && played >= queued.getFirst().deviceStart() + queued.getFirst().length()) {
                queued.removeFirst();
            }
            var playingSegment = queued.getFirst();
            long progress = Math.clamp(played - playingSegment.deviceStart(), 0, playingSegment.length());
            position.accept((playingSegment.projectStart() + progress) / (double) RATE);
        }
    }

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
        return presentations / 60.0 * ReviewTransport.ticksPerSecond(project);
    }

    synchronized void stop() {
        generation.incrementAndGet();
        playing = false;
        if (activePlayback != null) activePlayback.cancel(true);
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
    }

    /** Keep each rendered presentation for sample-aligned looping and monitor switches. */
    private static final class RenderBuffer {
        private final short[][] frames;
        private int next;
        private Exception error;

        RenderBuffer(int presentations) { frames = new short[presentations][]; }
        int size() { return frames.length; }

        void render(StudioProject source, S3kRenderer.ChannelMask mask) {
            try {
                var binary = ProjectCompiler.compileWithAssets(source);
                S3kRenderer.render(binary, size(), mask, this::append);
            } catch (Exception failure) {
                synchronized (this) {
                    error = failure;
                    notifyAll();
                }
            }
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
