package com.opensmpsdeck.headless;

import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.PsgChip;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.opensmpsdeck.headless.S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;

/** Diagnose PSG differences using soloed PCM, resolved bus writes and physical chip state. */
final class ReconstructionPsgTail {
    record LaneDifference(String lane, long changedFrames, long firstFrame, String cause) {}

    private ReconstructionPsgTail() {}

    static List<LaneDifference> diff(Path before, Path after, long start, long length) throws IOException {
        var oldFile = before.resolve("arrangement.smps.json");
        var newFile = after.resolve("arrangement.smps.json");
        if (!Files.isRegularFile(oldFile) || !Files.isRegularFile(newFile)) return null;
        var oldProject = ProjectFiles.load(oldFile).project();
        var newProject = ProjectFiles.load(newFile).project();
        if (oldProject.nativeProgram() != null || newProject.nativeProgram() != null) return null;
        var oldCompiled = TargetPlayback.prepare(oldProject);
        var newCompiled = TargetPlayback.prepare(newProject);
        var changes = new ArrayList<LaneDifference>();
        for (String lane : ReconstructionAllocation.PSG) {
            if (oldProject.clips().stream().noneMatch(clip -> clip.lane().equals(lane))
                    && newProject.clips().stream().noneMatch(clip -> clip.lane().equals(lane))) continue;
            var change = compare(oldCompiled, newCompiled, lane, start, length);
            if (change != null) changes.add(change);
        }
        return List.copyOf(changes);
    }

    private static LaneDifference compare(TargetPlayback before, TargetPlayback after, String lane,
                                          long start, long length) throws IOException {
        int tone = ReconstructionAllocation.PSG.indexOf(lane);
        var mask = ReconstructionView.soloMask(List.of(lane));
        var oldWrites = new LaneWrites(tone);
        var newWrites = new LaneWrites(tone);
        var oldPcm = new short[STEREO_FRAMES_PER_PRESENTATION * 2];
        var newPcm = new short[oldPcm.length];
        long changed = 0, first = -1;
        String cause = null;
        boolean differentWrites = false;
        try (var oldPlayback = before.open(mask, oldWrites);
             var newPlayback = after.open(mask, newWrites)) {
            for (long base = 0; base < length; base += STEREO_FRAMES_PER_PRESENTATION) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("PSG tail comparison interrupted");
                oldWrites.writes.clear();
                newWrites.writes.clear();
                S3kRenderer.advance(oldPlayback, oldPcm);
                S3kRenderer.advance(newPlayback, newPcm);
                long end = Math.min(length, base + STEREO_FRAMES_PER_PRESENTATION);
                if (end <= start) continue;
                long previous = changed;
                for (long frame = Math.max(start, base); frame < end; frame++) {
                    int i = (int) (frame - base) * 2;
                    if (oldPcm[i] == newPcm[i] && oldPcm[i + 1] == newPcm[i + 1]) continue;
                    if (changed++ == 0) {
                        first = frame;
                        cause = stateDifference(psg(oldPlayback), psg(newPlayback), tone);
                    }
                }
                // The sequencer can still write the old pitch during a muted rest.
                // Those writes affect the free-running counter but produce no PCM.
                if (changed != previous) differentWrites |= !oldWrites.writes.equals(newWrites.writes);
            }
        }
        if (changed == 0) return null;
        return new LaneDifference(lane, changed, first, differentWrites ? "different PSG chip writes" : cause);
    }

    private static PsgChip.Snapshot psg(SmpsDriverSession playback) {
        return playback.captureSnapshot().physical().synth().psg();
    }

    private static String stateDifference(PsgChip.Snapshot before, PsgChip.Snapshot after, int tone) {
        int[] oldLevel = before.attenuations(), newLevel = after.attenuations();
        boolean toneAudible = oldLevel[tone] != 15 || newLevel[tone] != 15;
        boolean noiseAudible = tone == 2 && (oldLevel[3] != 15 || newLevel[3] != 15);
        boolean linkedNoise = noiseAudible && ((before.noiseControl() & 3) == 3 || (after.noiseControl() & 3) == 3);
        if (oldLevel[tone] != newLevel[tone] || (toneAudible || linkedNoise)
                && before.tonePeriods()[tone] != after.tonePeriods()[tone]
                || noiseAudible && (oldLevel[3] != newLevel[3] || before.noiseControl() != after.noiseControl()))
            return "carried PSG register state";
        int[] oldCounter = before.counters(), newCounter = after.counters();
        boolean[] oldPolarity = before.polarities(), newPolarity = after.polarities();
        boolean tonePhase = toneAudible && (oldCounter[tone] != newCounter[tone] || oldPolarity[tone] != newPolarity[tone]);
        boolean noiseState = noiseAudible && (oldCounter[3] != newCounter[3] || oldPolarity[3] != newPolarity[3]
                || before.lfsr() != after.lfsr());
        if (tonePhase && noiseState) return "tone oscillator phase and noise generator state";
        if (tonePhase) return "tone oscillator phase";
        if (noiseState) return "noise generator state";
        return "PSG output history";
    }

    private record Write(long tick, int register, int value) {}

    private static final class LaneWrites implements ChipWriteObserver {
        private final int tone;
        private final List<Write> writes = new ArrayList<>();
        private int register;

        LaneWrites(int tone) {
            this.tone = tone;
        }

        @Override public void onYm2612Write(int port, int register, int value) {}
        @Override public void onPsgWrite(int value) {}
        @Override public boolean observesPhysicalWrites() { return true; }

        @Override public void onPsgBusWrite(long tick, int value) {
            if ((value & 0x80) != 0) register = (value >> 4) & 7;
            if (register / 2 == tone || tone == 2 && register / 2 == 3) writes.add(new Write(tick, register, value));
        }
    }
}
