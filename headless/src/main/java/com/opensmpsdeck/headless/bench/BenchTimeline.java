package com.opensmpsdeck.headless.bench;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsNoteListener;
import com.openggf.audio.smps.SmpsSequencer;
import com.opensmpsdeck.headless.BenchCartridge;
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
    record Playback(Truth truth, int frames, String end, Integer loopStart) {}
    record Hit(double time, String sampleHash, int slot) {}
    record Channel(String kind, List<Note> notes, List<Hit> hits) {}
    record Truth(List<Channel> channels, Map<String, byte[]> voices) {
        Truth(List<Channel> channels) { this(channels, null); }
    }

    // Same coordinate handler, sequencer preparation and outer-frame clock as NativeArrangement.
    // Read live instrument/noise state because the display arrangement omits it.
    private final Map<Track, Active> active = new IdentityHashMap<>();
    private final java.util.Set<Track> attacks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<byte[], String> voiceHashes = new IdentityHashMap<>();
    private final Map<String, Lane> lanes = new LinkedHashMap<>();
    private final Map<Integer, String> samples = new LinkedHashMap<>();
    private final BenchCartridge.Game game;
    private final Map<String, byte[]> voices;
    private int frame;
    private boolean restored;

    private record Active(String lane, int frame, int pitch, int note, int frequency,
                          String voice, Integer envelope) {}

    private static final class Lane {
        final List<Note> notes = new ArrayList<>();
        final List<Hit> hits = new ArrayList<>();
    }

    private BenchTimeline(CompiledProject project, boolean captureVoices) {
        this(BenchCartridge.Game.s3k, captureVoices);
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
        return read(project, frames, factory, false);
    }

    static Truth read(CompiledProject project, int frames, BenchCli.SequencerFactory factory, boolean captureVoices)
            throws InterruptedIOException {
        var walk = new BenchTimeline(project, captureVoices);
        return walk.walk(factory.create(project, () -> walk.restored = true, new Sonic3kCoordFlagHandler()), frames, 0).truth();
    }

    private BenchTimeline(BenchCartridge.Game game, boolean captureVoices) {
        this.game = game;
        this.voices = captureVoices ? new java.util.TreeMap<>() : null;
    }

    static Playback read(BenchCartridge.Song song, BenchCartridge.Game game, int frames, int loops)
            throws InterruptedIOException {
        var walk = new BenchTimeline(game, true);
        DacData dac = song.dac();
        var hashes = new java.util.HashMap<Integer, String>();
        for (int slot = 0x81; slot <= 0xff; slot++) {
            var entry = dac.mappingForNote(slot);
            if (entry == null) continue;
            var sample = dac.sample(entry.sampleId());
            if (sample == null) continue;
            walk.samples.put(slot, hashes.computeIfAbsent(entry.sampleId(), ignored -> {
                byte[] bytes = new byte[sample.length()];
                for (int i = 0; i < bytes.length; i++) bytes[i] = sample.byteAt(i);
                return BenchFiles.hash(bytes);
            }));
        }
        return walk.walk(song.sequencer().create(() -> walk.restored = true), frames, loops);
    }

    private Playback walk(SmpsSequencer sequencer, int frames, int loops) throws InterruptedIOException {
        sequencer.setNoteListener(this);
        var tracks = sequencer.getTracks();
        for (Track track : tracks) lane(kind(track));
        var detector = loops == 0 ? null : new LoopDetector(tracks, loops);
        int loopEnd = frames;
        while (frame < loopEnd && !sequencer.isComplete() && !restored) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Benchmark timeline interrupted");
            sequencer.serviceOuterFrame();
            for (Track track : tracks) observe(track);
            attacks.clear();
            frame++;
            if (detector != null && !sequencer.isComplete() && !restored)
                loopEnd = Math.min(frames, detector.end(tracks, frame));
        }
        for (Track track : tracks) finish(track);
        var truth = new Truth(lanes.entrySet().stream()
                .map(entry -> new Channel(entry.getKey(), entry.getValue().notes, entry.getValue().hits)).toList(), voices);
        Integer loopStart = detector == null ? null : detector.start;
        String end = sequencer.isComplete() ? "complete" : restored ? "restore"
                : loopEnd < frames ? "loop" : "duration";
        return new Playback(truth, frame, end, loopStart);
    }

    private static final class LoopDetector {
        private record Cycle(int start, int period) {}
        private final Map<Track, Map<List<Integer>, Integer>> visited = new IdentityHashMap<>();
        private final Map<Track, Cycle> cycles = new IdentityHashMap<>();
        private final Map<Track, Integer> positions = new IdentityHashMap<>();
        private final int loops;
        private Integer start;

        LoopDetector(List<Track> tracks, int loops) {
            this.loops = loops;
            tracks.forEach(track -> visited.put(track, new java.util.HashMap<>()));
        }

        int end(List<Track> tracks, int frame) {
            int end = 0, latestStart = 0;
            boolean pending = false;
            for (Track track : tracks) {
                if (!track.active) continue;
                var cycle = cycles.get(track);
                if (cycle == null) {
                    Integer position = positions.put(track, track.pos);
                    if (position == null || position != track.pos) {
                        Integer previous = visited.get(track).putIfAbsent(state(track), frame);
                        if (previous != null) {
                            cycle = new Cycle(previous, frame - previous);
                            cycles.put(track, cycle);
                        }
                    }
                }
                if (cycle == null) pending = true;
                else {
                    latestStart = Math.max(latestStart, cycle.start);
                    end = Math.max(end, cycle.start + loops * cycle.period);
                }
            }
            if (pending || end == 0) return Integer.MAX_VALUE;
            start = latestStart;
            return Math.max(frame, end);
        }

        private static List<Integer> state(Track track) {
            // Return addresses distinguish shared phrases, and loop counters
            // distinguish the finite repeats that lead into the terminal loop.
            var key = new ArrayList<Integer>(24);
            key.add(track.pos);
            key.add(track.rawDuration);
            key.add(track.note);
            key.add(track.fill);
            key.add(track.keyOffset);
            key.add(track.voiceId);
            key.add(track.instrumentId);
            key.add(track.dividingTiming);
            key.add(track.tieNext ? 1 : 0);
            key.add(track.noiseMode ? 1 : 0);
            key.add(track.returnSp);
            for (int i = 0; i < track.returnSp; i++) key.add(track.returnStack[i]);
            for (int counter : track.loopCounters) key.add(counter);
            return key;
        }
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
        if (game == BenchCartridge.Game.s1 || game == BenchCartridge.Game.s2) {
            if (track.type == TrackType.PSG) pitch = 48 + Math.clamp(index, 0, 69);
            else pitch = 11 + ((index + 1) & 0x7f) % 96;
        }
        String voice = track.type == TrackType.FM && track.voiceData != null
                ? voiceHashes.computeIfAbsent(track.voiceData, this::voiceHash) : null;
        Integer envelope = track.type == TrackType.PSG ? track.instrumentId : null;
        active.put(track, new Active(name, frame, pitch, track.note, frequency, voice, envelope));
    }

    private String voiceHash(byte[] bytes) {
        String hash = BenchFiles.hash(bytes);
        if (voices != null) {
            byte[] probe = bytes.clone();
            // S2 stores the middle operators in the opposite order from S3K.
            if (game == BenchCartridge.Game.s2)
                com.openggf.audio.smps.FmVoiceOperatorOrder.swapMiddleOperatorsInPlace(probe);
            voices.putIfAbsent(hash, probe);
        }
        return hash;
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
