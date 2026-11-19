package com.opensmpsdeck.headless.bench;

import com.openggf.audio.smps.SmpsNoteListener;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.opensmpsdeck.project.CompiledProject;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BenchTimeline implements SmpsNoteListener {
    record Note(double start, double end, int pitch, String voiceHash, Integer envelopeId) {}
    record Hit(double time, String sampleHash, int slot) {}
    record Channel(String kind, List<Note> notes, List<Hit> hits) {}
    record Truth(List<Channel> channels) {}

    // Same coordinate handler, sequencer preparation and outer-frame clock as NativeArrangement.
    // Read live instrument/noise state because the display arrangement omits it.
    private final Map<Track, Active> active = new IdentityHashMap<>();
    private final java.util.Set<Track> attacks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<byte[], String> voiceHashes = new IdentityHashMap<>();
    private final Map<String, Lane> lanes = new LinkedHashMap<>();
    private final Map<Integer, String> samples = new LinkedHashMap<>();
    private int frame;
    private boolean restored;

    private record Active(String lane, int frame, int pitch, int note, int frequency,
                          String voice, Integer envelope) {}

    private static final class Lane {
        final List<Note> notes = new ArrayList<>();
        final List<Hit> hits = new ArrayList<>();
    }

    private BenchTimeline(CompiledProject project) {
        var hashes = new LinkedHashMap<String, String>();
        for (var sample : project.samples()) hashes.put(sample.id(), BenchFiles.hash(sample.pcm()));
        if (project.nativeProgram() == null) {
            for (int i = 0; i < project.samples().size(); i++) {
                samples.put(0x81 + i, hashes.get(project.samples().get(i).id()));
            }
        } else {
            for (var slot : project.nativeProgram().dacSlots()) samples.put(slot.note(), hashes.get(slot.sampleId()));
        }
    }

    static Truth read(CompiledProject project, int frames, BenchCli.SequencerFactory factory)
            throws InterruptedIOException {
        var walk = new BenchTimeline(project);
        var sequencer = factory.create(project, () -> walk.restored = true, new Sonic3kCoordFlagHandler());
        sequencer.setNoteListener(walk);
        var tracks = sequencer.getTracks();
        for (Track track : tracks) walk.lane(kind(track));
        while (walk.frame < frames && !sequencer.isComplete() && !walk.restored) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Benchmark timeline interrupted");
            sequencer.serviceOuterFrame();
            for (Track track : tracks) walk.observe(track);
            walk.attacks.clear();
            walk.frame++;
        }
        for (Track track : tracks) walk.finish(track);
        return new Truth(walk.lanes.entrySet().stream()
                .map(entry -> new Channel(entry.getKey(), entry.getValue().notes, entry.getValue().hits)).toList());
    }

    private static String kind(Track track) {
        if (track.type == TrackType.DAC) return "FM6-DAC";
        return track.type.name() + (track.channelId + 1);
    }

    private Lane lane(String kind) {
        return lanes.computeIfAbsent(kind, ignored -> new Lane());
    }

    @Override public void attack(Track track) {
        if (track.type == TrackType.DAC) {
            String hash = samples.get(track.note);
            if (hash != null) lane(kind(track)).hits.add(new Hit(seconds(frame), hash, track.note));
        } else {
            finish(track);
            attacks.add(track);
        }
    }

    @Override public void release(Track track) {
        // Envelope-generated PSG rests retain the sequenced gate, as in the editable arrangement.
        if (!envelopeRest(track)) finish(track);
    }

    private void observe(Track track) {
        if (track.type == TrackType.DAC) return;
        if (!track.active || track.overridden || track.note < 0x81 || (track.resting && !envelopeRest(track))) {
            finish(track);
            return;
        }
        var previous = active.get(track);
        if (previous == null && !attacks.contains(track)) return;
        String name = track.type == TrackType.PSG && track.noiseMode && track.channelId == 2 ? "noise" : kind(track);
        int frequency = (track.baseBlock << 16) | track.baseFnum;
        if (previous != null && previous.note == track.note && previous.frequency == frequency
                && previous.lane.equals(name)) return;
        finish(track);
        int index = track.note - 0x81 + track.keyOffset;
        int pitch = track.type == TrackType.PSG ? 36 + Math.clamp(index, 0, 83) : 12 + (index & 0xff) % 96;
        String voice = track.type == TrackType.FM && track.voiceData != null
                ? voiceHashes.computeIfAbsent(track.voiceData, BenchFiles::hash) : null;
        Integer envelope = track.type == TrackType.PSG ? track.instrumentId : null;
        active.put(track, new Active(name, frame, pitch, track.note, frequency, voice, envelope));
    }

    private static boolean envelopeRest(Track track) {
        if (track.type != TrackType.PSG || !track.active || track.note < 0x81
                || track.envData == null || track.envPos >= track.envData.length
                || (track.fill != 0 && track.fillCounter == 0)) return false;
        int control = track.envData[track.envPos] & 0xff;
        return control == 0x81 || control == 0x83;
    }

    private void finish(Track track) {
        var note = active.remove(track);
        if (note != null && frame > note.frame) {
            lane(note.lane).notes.add(new Note(seconds(note.frame), seconds(frame), note.pitch, note.voice, note.envelope));
        }
    }

    private static double seconds(int frame) {
        return frame / 60.0;
    }
}
