package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.headless.ProjectRenderTiming;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.StudioProject;
import javax.sound.sampled.*;
import java.io.InterruptedIOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Owns the audio device and renders immutable project snapshots off the FX thread. */
final class StudioPlayback implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r,"studio-audio"); t.setDaemon(true); return t;
    });
    private final AtomicLong generation = new AtomicLong();
    private volatile SourceDataLine line;
    private volatile boolean playing;
    boolean playing() { return playing; }

    void play(StudioProject project, Consumer<String> status, DoubleConsumer position) {
        stop();
        long version = generation.incrementAndGet(); playing = true;
        worker.submit(() -> {
            try {
                var binary = ProjectCompiler.compileWithAssets(project);
                int frames = presentationCount(project);
                var format = new AudioFormat(48000,16,2,true,false);
                SourceDataLine output = AudioSystem.getSourceDataLine(format);
                synchronized (this) {
                    if (version != generation.get()) return;
                    line = output; output.open(format, 8192); output.start();
                }
                status.accept("Playing · pinned S3&K accurate core");
                byte[] bytes = new byte[3200]; int[] presentation = {0};
                S3kRenderer.render(binary,(int)Math.max(1,frames),pcm -> {
                    if (version != generation.get()) throw new InterruptedIOException();
                    for (int i=0;i<pcm.length;i++) { bytes[i*2]=(byte)pcm[i]; bytes[i*2+1]=(byte)(pcm[i]>>>8); }
                    int written=0;
                    while (written<bytes.length) {
                        if (version != generation.get()) throw new InterruptedIOException();
                        int count=output.write(bytes,written,bytes.length-written);
                        if (count<=0) throw new InterruptedIOException();
                        written+=count;
                    }
                    position.accept(tickAtPresentation(project,++presentation[0]));
                });
                if (version == generation.get()) output.drain();
                if (version == generation.get()) status.accept("Playback finished");
            } catch (LineUnavailableException | IllegalArgumentException error) {
                if (version == generation.get()) status.accept("Preview unavailable: "+error.getMessage()+". You can still export WAV.");
            } catch (Exception error) {
                if (version == generation.get()) status.accept("Playback failed: "+error.getMessage());
            } finally {
                synchronized (this) {
                    if (version == generation.get()) { releaseLine(); playing=false; }
                }
            }
        });
    }
    static int presentationCount(StudioProject project) {
        return ProjectRenderTiming.presentationCount(project);
    }
    static double tickAtPresentation(StudioProject project,int presentations) {
        return project.nativeProgram()!=null?presentations:presentations*(256-project.tempo())/256.0;
    }
    synchronized void stop() { generation.incrementAndGet(); playing=false; releaseLine(); }
    private void releaseLine() { if(line!=null) { line.stop(); line.flush(); line.close(); line=null; } }
    @Override public void close() { stop(); worker.shutdownNow(); }
}
