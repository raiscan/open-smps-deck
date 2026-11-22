package com.opensmpsdeck.headless;

import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.nio.file.Path;

import static com.opensmpsdeck.headless.S3kRenderer.ChannelMask;
import static com.opensmpsdeck.headless.S3kRenderer.PcmSink;

/** A project compiled for its declared driver, ready for NTSC playback or diagnostic tracing. */
public final class TargetPlayback {
    private final TargetGame target;
    private final ClassicPackageDriver classic;
    private final CompiledProject compiled;

    private TargetPlayback(StudioProject project, ProjectCompiler.Encoding encoding) {
        target = project.target();
        classic = classic(target);
        if (classic == null) {
            compiled = ProjectCompiler.compileWithAssets(project, encoding);
        } else {
            classic.require(project.nativeProgram() == null,
                    "native S3K banks require an editable copy for classic playback");
            int tempo = classic.projectTempo(project).targetByte();
            var bank = classic.compile(project, tempo, encoding);
            compiled = new CompiledProject(bank.bytes(), project.envelopes(), project.samples());
            classic.preflight(compiled);
        }
    }

    public static TargetPlayback prepare(StudioProject project) {
        return prepare(project, ProjectCompiler.Encoding.COMPACT);
    }

    public static TargetPlayback prepare(StudioProject project, ProjectCompiler.Encoding encoding) {
        return new TargetPlayback(project, encoding);
    }

    static ClassicPackageDriver classic(TargetGame target) {
        return switch (target) {
            case S3K -> null;
            case S1 -> ClassicPackageDriver.S1;
            case S2 -> ClassicPackageDriver.S2;
        };
    }

    public TargetGame target() { return target; }

    /** Independent readers for exact presentation-aligned S3K seeks. */
    public CheckpointPlayback checkpoints() {
        if (classic != null || compiled.nativeProgram() != null)
            throw new IllegalStateException("Checkpoints require a compiled S3K arrangement");
        return new CheckpointPlayback(this);
    }

    CompiledProject compiled() { return compiled; }

    void writeWave(Path output, int frames) throws IOException {
        WaveFile.write(output, frames, sink -> render(frames, sink));
    }

    public void render(int frames, PcmSink sink) throws IOException {
        render(frames, ChannelMask.NONE, sink);
    }

    public void render(int frames, ChannelMask mask, PcmSink sink) throws IOException {
        render(frames, mask, sink, ChipWriteObserver.NONE);
    }

    public void render(int frames, ChannelMask mask, PcmSink sink, ChipWriteObserver observer) throws IOException {
        if (classic == null) S3kRenderer.render(compiled, frames, mask, sink, observer);
        else classic.render(compiled, frames, mask, sink, observer);
    }

    SmpsDriverSession open(ChannelMask mask, ChipWriteObserver observer) {
        return classic == null ? S3kRenderer.openPlayback(compiled, mask, observer)
                : classic.openPlayback(compiled, mask, observer);
    }

    public SmpsSequencer timeline(MusicRestoreSink restore, Sonic3kCoordFlagHandler handler) {
        return classic == null ? S3kRenderer.timelineSequencer(compiled, restore, handler)
                : classic.timeline(compiled, restore);
    }
}
