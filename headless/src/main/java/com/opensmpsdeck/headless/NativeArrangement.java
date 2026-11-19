package com.opensmpsdeck.headless;

import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.audio.smps.SmpsNoteListener;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
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
        if (source.nativeProgram() == null) throw new IllegalArgumentException("A native bank is required");
        var copy = build(source, new CopyAssets(source));
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
        var recorder = new Recorder(assets);
        var sequencer = S3kRenderer.timelineSequencer(ProjectCompiler.compileWithAssets(source), recorder::restore, recorder);
        var lanes = sequencer.getTracks().stream().map(track -> new Lane(track, assets)).toList();
        for (Lane lane : lanes) recorder.lanes.put(lane.track, lane);
        for (var slot : program.dacSlots()) {
            recorder.samples[slot.note()] = slot.sampleId();
            recorder.rates[slot.note()] = slot.rate();
        }
        sequencer.setNoteListener(recorder);

        while (recorder.frame < program.previewFrames() && !sequencer.isComplete() && !recorder.restored) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            for (Lane lane : lanes) lane.beforeFrame(recorder.frame);
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
                sequences, clips, assets == null ? List.of() : List.copyOf(assets.envelopes.values()),
                assets == null ? source.samples() : List.copyOf(assets.samples.values()));
    }

    private static final class Recorder extends Sonic3kCoordFlagHandler implements SmpsNoteListener {
        final CopyAssets assets;
        final IdentityHashMap<Track, Lane> lanes = new IdentityHashMap<>();
        final String[] samples = new String[256];
        final int[] rates = new int[256];
        int frame;
        boolean restored;

        Recorder(CopyAssets assets) { this.assets = assets; }

        @Override public boolean handleFlag(CoordFlagContext context, Track track, int opcode) {
            int operand = track.pos;
            boolean handled = super.handleFlag(context, track, opcode);
            if (assets == null || track.type == TrackType.DAC) return handled;
            Lane lane = lanes.get(track);
            var program = context.programView();
            switch (opcode) {
                case 0xE0 -> {
                    if (track.type == TrackType.FM) lane.command(frame, NativeCommand.PAN, program.dataByteAt(operand) & 0xFF);
                }
                case 0xE1 -> lane.command(frame, NativeCommand.DETUNE, program.dataByteAt(operand) & 0xFF);
                case 0xE4 -> lane.command(frame, NativeCommand.VOLUME, program.dataByteAt(operand) & 0xFF);
                case 0xE5, 0xE6 -> {
                    if (track.type == TrackType.FM) {
                        int delta = program.dataByteAt(operand + (opcode == 0xE5 ? 1 : 0)) & 0xFF;
                        lane.command(frame, NativeCommand.FM_VOLUME, delta);
                    }
                }
                case 0xE7 -> lane.command(frame, NativeCommand.HOLD);
                case 0xEC -> {
                    if (track.type == TrackType.PSG) lane.command(frame, NativeCommand.PSG_VOLUME, program.dataByteAt(operand) & 0xFF);
                }
                case 0xEF, 0xF5 -> {
                    if (track.type == TrackType.PSG) {
                        lane.command(frame, NativeCommand.PSG_ENVELOPE, assets.envelope(Envelope.PSG_VOLUME, track.instrumentId), List.of());
                    } else if (opcode == 0xEF) {
                        lane.command(frame, NativeCommand.FM_VOICE, assets.voice(track.voiceData), List.of());
                    }
                }
                case 0xF0 -> lane.command(frame, NativeCommand.MODULATION, modulation(track));
                case 0xF1, 0xF4 -> lane.command(frame, NativeCommand.MODULATION_ENVELOPE,
                        assets.envelope(Envelope.MODULATION, track.modEnvId), List.of());
                case 0xF3 -> {
                    if (track.type == TrackType.PSG) lane.command(frame, NativeCommand.PSG_NOISE, track.rawPsgNoise);
                }
                case 0xFA -> lane.command(frame, NativeCommand.MODULATION_OFF);
                case 0xFF -> {
                    if (track.type == TrackType.FM) {
                        int subcommand = program.dataByteAt(operand) & 0xFF;
                        if (subcommand == 0x05) {
                            lane.command(frame, NativeCommand.SSG_EG, IntStream.of(track.ssgEg).map(value -> value & 0xFF).boxed().toList());
                        } else if (subcommand == 0x06) {
                            int mask = program.dataByteAt(operand + 2) & 0xFF;
                            int slot = mask % 16 == 0 ? 0 : program.dataByteAt(operand + 1) & 0xFF;
                            lane.command(frame, NativeCommand.FM_VOLUME_ENVELOPE, assets.envelope(Envelope.PSG_VOLUME, slot), List.of(mask));
                        }
                    }
                }
                default -> { }
            }
            return handled;
        }

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
            Lane lane = lanes.get(track);
            if (!lane.envelopeRest()) lane.finish(frame);
        }
    }

    private static final class Lane {
        final Track track;
        final String name;
        final CopyAssets assets;
        final List<NoteEvent> notes = new ArrayList<>();
        final List<SampleTrigger> hits = new ArrayList<>();
        final List<CommandEvent> effects = new ArrayList<>();
        final List<Setup> setups = new ArrayList<>();
        Setup pendingSetup;
        int nextCut = CLIP_FRAMES;
        int start = -1;
        int pitch;
        int note;
        int frequency;
        boolean attacked;

        Lane(Track track, CopyAssets assets) {
            this.track = track;
            this.assets = assets;
            name = track.type == TrackType.DAC ? "DAC" : track.type.name() + (track.channelId + 1);
            if (assets != null && track.type != TrackType.DAC) setups.add(assets.setup(track, 0));
        }

        void command(int frame, NativeCommand command, Integer... arguments) {
            command(frame, command, List.of(arguments));
        }

        void command(int frame, NativeCommand command, List<Integer> arguments) {
            command(frame, command, null, arguments);
        }

        void command(int frame, NativeCommand command, String asset, List<Integer> arguments) {
            effects.add(new CommandEvent("effect-" + effects.size(), frame, command, arguments, asset));
        }

        void beforeFrame(int frame) {
            // FM volume envelopes retain their cursor between attacks.
            // A clip boundary must leave that uninterrupted.
            if (assets != null && track.type != TrackType.DAC && frame >= nextCut
                    && track.active && track.duration <= 1 && track.fmVolEnvData == null) {
                pendingSetup = assets.setup(track, frame);
            }
        }

        void afterFrame(int frame) {
            if (track.type == TrackType.DAC) return;
            if (attacked && pendingSetup != null && pendingSetup.tick() == frame) {
                setups.add(pendingSetup);
                nextCut = (frame / CLIP_FRAMES + 1) * CLIP_FRAMES;
            }
            pendingSetup = null;
            if (!track.active || track.note < 0x81 || (track.resting && !envelopeRest())) {
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
                }
                note = track.note;
                frequency = newFrequency;
            }
            attacked = false;
        }

        // The copied envelope reproduces its own rest or mute. Retain
        // the stream duration so it can still run on that last frame.
        boolean envelopeRest() {
            if (assets == null || track.type != TrackType.PSG || !track.active || track.note < 0x81
                    || track.envData == null || track.envPos >= track.envData.length
                    || (track.fill != 0 && track.fillCounter == 0)) return false;
            int control = track.envData[track.envPos] & 0xFF;
            return control == 0x81 || control == 0x83;
        }

        void finish(int frame) {
            if (start >= 0 && frame > start) {
                notes.add(new NoteEvent("note-" + start, start, frame - start, pitch));
            }
            start = -1;
        }

        void buildEditableClips(List<Sequence> sequences, List<Clip> clips) {
            if (notes.isEmpty()) return;
            int noteIndex = 0;
            int effectIndex = 0;
            for (int i = 0; i < setups.size(); i++) {
                var setup = setups.get(i);
                int startFrame = setup.tick();
                int limit = i + 1 < setups.size() ? setups.get(i + 1).tick() : Integer.MAX_VALUE;
                var commands = new ArrayList<>(setup.commands());
                var localNotes = new ArrayList<NoteEvent>();
                int length = 0;
                int transpose = 0;
                while (effectIndex < effects.size() && effects.get(effectIndex).tick() < limit) {
                    var event = effects.get(effectIndex++);
                    int tick = event.tick() - startFrame;
                    commands.add(new CommandEvent(event.id(), tick, event.command(), event.arguments(), event.assetId()));
                    length = Math.max(length, tick + 1);
                }
                while (noteIndex < notes.size() && notes.get(noteIndex).tick() < limit) {
                    var item = notes.get(noteIndex++);
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
                    length = Math.max(length, tick + item.duration());
                }
                if (length == 0) continue;
                String voice = setup.voice();
                if (track.type == TrackType.FM && voice == null) {
                    voice = commands.stream().filter(event -> event.command() == NativeCommand.FM_VOICE)
                            .map(CommandEvent::assetId).findFirst().orElseThrow(
                                    () -> new IllegalArgumentException(name + " has no complete FM voice"));
                }
                String id = name + "-" + startFrame;
                sequences.add(new Sequence(id, title(startFrame), track.type.name(), length,
                        voice, setup.volume(), localNotes, commands, "RESET", null, null, List.of()));
                clips.add(new Clip(id, id, name, startFrame, length));
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

    private static List<Integer> modulation(Track track) {
        return List.of(track.modPendingDelayInit, track.modPendingRate & 0xFF,
                track.modPendingDelta & 0xFF, track.modPendingStepsFull);
    }

    private record Setup(int tick, String voice, int volume, List<CommandEvent> commands) {}
    private record EnvelopeSlot(String type, int slot) {}
    private record SampleRate(String id, int rate) {}

    private static final class CopyAssets {
        final Map<List<Integer>, Voice> voices = new LinkedHashMap<>();
        final Map<SampleRate, DacSample> samples = new LinkedHashMap<>();
        final Map<String, DacSample> sourceSamples;
        final Map<EnvelopeSlot, Envelope> sourceEnvelopes = new LinkedHashMap<>();
        final Map<String, Envelope> envelopes = new LinkedHashMap<>();
        final IdentityHashMap<byte[], Voice> loadedVoices = new IdentityHashMap<>();

        CopyAssets(StudioProject source) {
            sourceSamples = source.samples().stream().collect(Collectors.toMap(DacSample::id, Function.identity()));
            var byId = source.envelopes().stream().collect(Collectors.toMap(Envelope::id, Function.identity()));
            for (var slot : source.nativeProgram().envelopeSlots()) {
                var item = byId.get(slot.assetId());
                if (item != null) sourceEnvelopes.put(new EnvelopeSlot(item.type(), slot.slot()), item);
            }
        }

        Setup setup(Track track, int tick) {
            String voice = track.type == TrackType.FM && track.voiceData != null ? voice(track.voiceData) : null;
            int volume = track.type == TrackType.FM
                    ? 127 - Math.clamp(track.volumeOffset, 0, 127)
                    : 127 - 8 * Math.clamp(track.volumeOffset, 0, 15);
            int pan = (track.pan & 0xC8) | ((track.ams & 3) << 4) | (track.fms & 7);
            var commands = new ArrayList<CommandEvent>();
            if (track.type == TrackType.FM && pan != 0xC0)
                commands.add(new CommandEvent("pan", 0, NativeCommand.PAN, List.of(pan)));
            if (track.detune != 0)
                commands.add(new CommandEvent("detune", 0, NativeCommand.DETUNE, List.of(track.detune & 0xFF)));
            if (track.type == TrackType.FM && IntStream.of(track.ssgEg).anyMatch(value -> value != 0))
                commands.add(new CommandEvent("ssg-eg", 0, NativeCommand.SSG_EG, IntStream.of(track.ssgEg).boxed().toList()));
            if (track.type == TrackType.PSG && track.instrumentId != 0)
                commands.add(new CommandEvent("instrument", 0, NativeCommand.PSG_ENVELOPE, List.of(),
                        envelope(Envelope.PSG_VOLUME, track.instrumentId)));
            if (track.type == TrackType.PSG && track.noiseMode)
                commands.add(new CommandEvent("noise", 0, NativeCommand.PSG_NOISE, List.of(track.rawPsgNoise)));
            if (track.customModEnabled)
                commands.add(new CommandEvent("modulation", 0, NativeCommand.MODULATION, modulation(track)));
            else if (track.modEnvId != 0)
                commands.add(new CommandEvent("modulation-envelope", 0, NativeCommand.MODULATION_ENVELOPE, List.of(),
                        envelope(Envelope.MODULATION, track.modEnvId)));
            return new Setup(tick, voice, volume, commands);
        }

        String envelope(String type, int slot) {
            if (slot == 0) return null;
            var value = sourceEnvelopes.get(new EnvelopeSlot(type, slot));
            if (value == null) throw new IllegalArgumentException("Missing " + type + " envelope slot " + slot);
            envelopes.putIfAbsent(value.id(), value);
            return value.id();
        }

        String voice(byte[] bytes) {
            if (bytes == null || bytes.length != 25)
                throw new IllegalArgumentException("A sounding FM track has no complete voice");
            return loadedVoices.computeIfAbsent(bytes, patch -> {
                var values = IntStream.range(0, patch.length).map(i -> patch[i] & 0xFF).boxed().toList();
                return voices.computeIfAbsent(values, key ->
                        new Voice("fm-" + (voices.size() + 1), "Imported FM " + (voices.size() + 1), key));
            }).id();
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
