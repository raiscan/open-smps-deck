package com.opensmpsdeck.headless;

import com.openggf.audio.smps.SmpsNoteListener;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Transient display clips derived by stepping the pinned sequencer without audio output. */
public final class NativeArrangement {
    private static final int FRAMES_PER_SECOND = 60;
    private static final int CLIP_FRAMES = 8 * FRAMES_PER_SECOND;

    private NativeArrangement() {}

    /**
     * Returns display data using one tick per 60 Hz presentation. The
     * native document remains the source for playback, edits and export.
     */
    public static StudioProject project(StudioProject source) throws InterruptedException {
        var program = source.nativeProgram();
        if (program == null) throw new IllegalArgumentException("A native bank is required");
        var recorder = new Recorder();
        var sequencer = S3kRenderer.timelineSequencer(ProjectCompiler.compileWithAssets(source), recorder::restore);
        var lanes = sequencer.getTracks().stream().map(Lane::new).toList();
        for (Lane lane : lanes) recorder.lanes.put(lane.track, lane);
        for (var slot : program.dacSlots()) recorder.samples[slot.note()] = slot.sampleId();
        sequencer.setNoteListener(recorder);

        while (recorder.frame < program.previewFrames() && !sequencer.isComplete() && !recorder.restored) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            sequencer.serviceOuterFrame();
            for (Lane lane : lanes) lane.afterFrame(recorder.frame);
            recorder.frame++;
        }
        var sequences = new ArrayList<Sequence>();
        var clips = new ArrayList<Clip>();
        for (Lane lane : lanes) {
            lane.finish(recorder.frame);
            lane.buildClips(recorder.frame, sequences, clips);
        }
        return new StudioProject(4, source.name(), "S3K", FRAMES_PER_SECOND, 0,
                List.of(), sequences, clips, List.of(), source.samples());
    }

    private static final class Recorder implements SmpsNoteListener {
        final IdentityHashMap<Track, Lane> lanes = new IdentityHashMap<>();
        final String[] samples = new String[256];
        int frame;
        boolean restored;

        void restore() { restored = true; }

        @Override public void attack(Track track) {
            Lane lane = lanes.get(track);
            if (track.type == TrackType.DAC) {
                String sample = samples[track.note];
                if (sample != null) lane.hits.add(new SampleTrigger("hit-" + frame, frame, sample));
            } else {
                lane.finish(frame);
                lane.attacked = true;
            }
        }

        @Override public void release(Track track) {
            lanes.get(track).finish(frame);
        }
    }

    private static final class Lane {
        final Track track;
        final String name;
        final List<NoteEvent> notes = new ArrayList<>();
        final List<SampleTrigger> hits = new ArrayList<>();
        int start = -1;
        int pitch;
        int note;
        int frequency;
        boolean attacked;

        Lane(Track track) {
            this.track = track;
            name = track.type == TrackType.DAC ? "DAC" : track.type.name() + (track.channelId + 1);
        }

        void afterFrame(int frame) {
            if (track.type == TrackType.DAC) return;
            if (!track.active || track.resting || track.note < 0x81) {
                finish(frame);
            } else {
                int newFrequency = (track.baseBlock << 16) | track.baseFnum;
                boolean pitchChanged = track.note != note || newFrequency != frequency;
                // HOLD can change pitch without an attack. A bare duration
                // can reattack the same pitch without applying transpose again.
                if (attacked || (start >= 0 && pitchChanged)) {
                    finish(frame);
                    if (pitchChanged || note == 0) {
                        int base = track.type == TrackType.PSG ? 36 : 12;
                        pitch = Math.clamp(track.note - 0x81 + base + track.keyOffset, 12, 117);
                    }
                    start = frame;
                }
                note = track.note;
                frequency = newFrequency;
            }
            attacked = false;
        }

        void finish(int frame) {
            if (start >= 0 && frame > start)
                notes.add(new NoteEvent("note-" + start, start, frame - start, pitch));
            start = -1;
        }

        void buildClips(int end, List<Sequence> sequences, List<Clip> clips) {
            for (int startFrame = 0; startFrame < end; startFrame += CLIP_FRAMES) {
                int limit = Math.min(startFrame + CLIP_FRAMES, end);
                var localNotes = new ArrayList<NoteEvent>();
                for (NoteEvent item : notes) {
                    int left = Math.max(item.tick(), startFrame);
                    int right = Math.min(item.tick() + item.duration(), limit);
                    if (left < right)
                        localNotes.add(new NoteEvent(item.id(), left - startFrame, right - left, item.pitch()));
                }
                var localHits = new ArrayList<SampleTrigger>();
                for (SampleTrigger item : hits) {
                    if (item.tick() >= startFrame && item.tick() < limit)
                        localHits.add(new SampleTrigger(item.id(), item.tick() - startFrame, item.sampleId()));
                }
                if (localNotes.isEmpty() && localHits.isEmpty()) continue;
                int seconds = startFrame / FRAMES_PER_SECOND;
                String id = name + "-" + startFrame;
                String title = "%s · %d:%02d".formatted(name, seconds / 60, seconds % 60);
                int length = limit - startFrame;
                sequences.add(new Sequence(id, title, track.type.name(), length, null, 127,
                        localNotes, List.of(), "RESET", null, null, localHits));
                clips.add(new Clip(id, id, name, startFrame, length));
            }
        }
    }
}
