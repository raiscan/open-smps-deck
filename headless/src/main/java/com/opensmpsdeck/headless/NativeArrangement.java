package com.opensmpsdeck.headless;

import com.openggf.audio.smps.SmpsNoteListener;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Derives timeline clips by stepping the pinned sequencer without audio output. */
public final class NativeArrangement {
    private static final int FRAMES_PER_SECOND = 60;
    private static final int CLIP_FRAMES = 8 * FRAMES_PER_SECOND;

    private NativeArrangement() {}

    /**
     * Returns display data using one tick per 60 Hz presentation. The
     * native document remains the source for playback, edits and export.
     */
    public static StudioProject project(StudioProject source) throws InterruptedException {
        return build(source, null);
    }

    /** Reconstruct an editable arrangement for the native preview interval. */
    public static StudioProject editableCopy(StudioProject source) throws InterruptedException {
        var assets = new CopyAssets(source);
        var copy = build(source, assets);
        if (copy.clips().isEmpty()) throw new IllegalArgumentException("No note or DAC events to copy");
        try {
            ProjectCompiler.compile(copy);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Cannot fit the editable copy. " + failure.getMessage(), failure);
        }
        return copy;
    }

    private static StudioProject build(StudioProject source, CopyAssets assets) throws InterruptedException {
        var program = source.nativeProgram();
        if (program == null) throw new IllegalArgumentException("A native bank is required");
        var recorder = new Recorder();
        var sequencer = S3kRenderer.timelineSequencer(ProjectCompiler.compileWithAssets(source), recorder::restore);
        var lanes = sequencer.getTracks().stream().map(track -> new Lane(track, assets)).toList();
        for (Lane lane : lanes) recorder.lanes.put(lane.track, lane);
        for (var slot : program.dacSlots()) {
            recorder.samples[slot.note()] = slot.sampleId();
            recorder.rates[slot.note()] = slot.rate();
        }
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
            if (assets != null && lane.track.type != TrackType.DAC) lane.buildEditableClips(sequences, clips);
            else lane.buildClips(recorder.frame, sequences, clips);
        }
        return new StudioProject(4, assets == null ? source.name() : source.name() + " (editable)",
                "S3K", FRAMES_PER_SECOND, 0, assets == null ? List.of() : List.copyOf(assets.voices.values()),
                sequences, clips, List.of(), assets == null ? source.samples() : List.copyOf(assets.samples.values()));
    }

    private static final class Recorder implements SmpsNoteListener {
        final IdentityHashMap<Track, Lane> lanes = new IdentityHashMap<>();
        final String[] samples = new String[256];
        final int[] rates = new int[256];
        int frame;
        boolean restored;

        void restore() { restored = true; }

        @Override public void attack(Track track) {
            Lane lane = lanes.get(track);
            if (track.type == TrackType.DAC) {
                String sample = samples[track.note];
                if (sample != null) {
                    if (lane.assets != null) sample = lane.assets.sample(sample, rates[track.note]);
                    lane.hits.add(new SampleTrigger("hit-" + frame, frame, sample));
                }
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
        final CopyAssets assets;
        final List<NoteEvent> notes = new ArrayList<>();
        final List<SampleTrigger> hits = new ArrayList<>();
        final List<Tone> tones = new ArrayList<>();
        Tone tone;
        int start = -1;
        int pitch;
        int note;
        int frequency;
        boolean attacked;

        Lane(Track track, CopyAssets assets) {
            this.track = track;
            this.assets = assets;
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
                        int index = track.note - 0x81 + track.keyOffset;
                        if (assets == null) {
                            int base = track.type == TrackType.PSG ? 36 : 12;
                            pitch = Math.clamp(index + base, 12, 117);
                        } else if (track.type == TrackType.PSG) {
                            pitch = 36 + Math.clamp(index, 0, 83);
                        } else {
                            // The driver masks the FM octave to three bits.
                            pitch = 12 + (index & 0xFF) % 96;
                        }
                    }
                    start = frame;
                    if (assets != null) tone = assets.tone(track);
                }
                note = track.note;
                frequency = newFrequency;
            }
            attacked = false;
        }

        void finish(int frame) {
            if (start >= 0 && frame > start) {
                if (assets != null) tones.add(tone);
                notes.add(new NoteEvent("note-" + start, start, frame - start, pitch));
            }
            start = -1;
        }

        void buildEditableClips(List<Sequence> sequences, List<Clip> clips) {
            int first = 0;
            while (first < notes.size()) {
                Tone sound = tones.get(first);
                int startFrame = notes.get(first).tick();
                int window = startFrame / CLIP_FRAMES;
                int next = first;
                int end = startFrame;
                var localNotes = new ArrayList<NoteEvent>();
                var commands = new ArrayList<CommandEvent>();
                if (track.type == TrackType.FM && sound.pan() != 0xC0)
                    commands.add(new CommandEvent("pan", 0, NativeCommand.PAN, List.of(sound.pan())));
                if (sound.detune() != 0)
                    commands.add(new CommandEvent("detune", 0, NativeCommand.DETUNE, List.of(sound.detune())));
                int transpose = 0;
                while (next < notes.size() && sound.equals(tones.get(next))
                        && notes.get(next).tick() / CLIP_FRAMES == window) {
                    var item = notes.get(next++);
                    end = item.tick() + item.duration();
                    int tick = item.tick() - startFrame;
                    int writtenPitch = track.type == TrackType.PSG
                            ? Math.clamp(item.pitch(), 45, 117) : Math.min(item.pitch(), 106);
                    int nextTranspose = item.pitch() - writtenPitch;
                    if (nextTranspose != transpose) {
                        commands.add(new CommandEvent("transpose-" + tick, tick,
                                NativeCommand.TRANSPOSE, List.of(0x40 + nextTranspose)));
                        transpose = nextTranspose;
                    }
                    localNotes.add(new NoteEvent(item.id(), tick, item.duration(), writtenPitch));
                }
                String id = name + "-" + startFrame;
                int length = end - startFrame;
                sequences.add(new Sequence(id, title(startFrame), track.type.name(), length,
                        sound.voice(), sound.volume(), localNotes, commands, "RESET", null, null, List.of()));
                clips.add(new Clip(id, id, name, startFrame, length));
                first = next;
            }
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
                String id = name + "-" + startFrame;
                int length = limit - startFrame;
                sequences.add(new Sequence(id, title(startFrame), track.type.name(), length, null, 127,
                        localNotes, List.of(), "RESET", null, null, localHits));
                clips.add(new Clip(id, id, name, startFrame, length));
            }
        }

        private String title(int startFrame) {
            int seconds = startFrame / FRAMES_PER_SECOND;
            return "%s · %d:%02d".formatted(name, seconds / 60, seconds % 60);
        }
    }

    private record Tone(String voice, int volume, int pan, int detune) {}
    private record SampleRate(String id, int rate) {}

    private static final class CopyAssets {
        final Map<List<Integer>, Voice> voices = new LinkedHashMap<>();
        final Map<SampleRate, DacSample> samples = new LinkedHashMap<>();
        final Map<String, DacSample> sourceSamples;
        final IdentityHashMap<byte[], Voice> loadedVoices = new IdentityHashMap<>();

        CopyAssets(StudioProject source) {
            sourceSamples = source.samples().stream().collect(Collectors.toMap(DacSample::id, Function.identity()));
        }

        Tone tone(Track track) {
            Voice voice = track.type == TrackType.FM ? voice(track.voiceData) : null;
            int volume = track.type == TrackType.FM
                    ? 127 - Math.clamp(track.volumeOffset, 0, 127)
                    : 127 - 8 * Math.clamp(track.volumeOffset, 0, 15);
            int pan = (track.pan & 0xC8) | ((track.ams & 3) << 4) | (track.fms & 7);
            return new Tone(voice == null ? null : voice.id(), volume, pan, track.detune & 0xFF);
        }

        Voice voice(byte[] bytes) {
            if (bytes == null || bytes.length != 25)
                throw new IllegalArgumentException("A sounding FM track has no complete voice");
            return loadedVoices.computeIfAbsent(bytes, patch -> {
                var values = IntStream.range(0, patch.length).map(i -> patch[i] & 0xFF).boxed().toList();
                return voices.computeIfAbsent(values, key ->
                        new Voice("fm-" + (voices.size() + 1), "Imported FM " + (voices.size() + 1), key));
            });
        }

        String sample(String id, int rate) {
            return samples.computeIfAbsent(new SampleRate(id, rate), key -> {
                var source = sourceSamples.get(id);
                return new DacSample("sample-" + (samples.size() + 1),
                        "%s (rate %02X)".formatted(source.name(), rate), rate, source.pcmBase64());
            }).id();
        }
    }
}
