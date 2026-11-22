package com.opensmpsdeck.headless;

import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.audio.smps.SmpsNoteListener;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.opensmpsdeck.headless.rom.VerifiedRom;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.headless.InstrumentCatalogue.Builder;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Occurrence;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.EnvelopeProgram;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Asset declarations and bounded sequencer observations from supported S3K sources. */
public final class CatalogueSources {
    private static final int VOICE_BYTES = 25;
    private static final int GLOBAL_VOICE_ADDRESS = 0x17D8;
    private static final int NOISE_AUDITION_FRAMES = 240;

    private CatalogueSources() {}

    public static void scanRom(Path rom, Builder catalogue) throws IOException, InterruptedException {
        scanRom(VerifiedRom.read(rom), catalogue);
    }

    static void scanRom(VerifiedRom image, Builder catalogue) throws IOException, InterruptedException {
        byte[] bytes = image.bytes();
        String revision = "sha256:" + InstrumentCatalogue.digest(bytes);
        String source = image.path().getFileName().toString();
        TargetGame game = TargetGame.parse(image.revision().game().id());
        try (var rom = Rom.fromReader(RomByteReader.fromBytes(bytes), source)) {
            if (game != TargetGame.S3K) {
                ClassicCatalogueSources.scanRom(game, rom, bytes, source, revision, catalogue);
                return;
            }
            var loader = new Sonic3kSmpsLoader(rom);
            for (var song : S3kRomImporter.songs()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    scan(S3kRomImporter.readSong(rom, loader, song), source, revision, song.id(), catalogue);
                } catch (IllegalArgumentException failure) {
                    catalogue.issue(source, "song-import", "%02X %s: %s".formatted(
                            song.id(), song.name(), failure.getMessage()));
                }
            }
        }
    }

    /** Project declaration and playback scan. The supplied source/revision identify its input. */
    public static void scanProject(StudioProject project, String source, String revision, Builder catalogue)
            throws InterruptedException {
        if (project.target() == TargetGame.S3K) {
            scan(project, source, revision, null, catalogue);
        } else {
            var playback = TargetPlayback.prepare(project);
            var bank = playback.compiled();
            ClassicCatalogueSources.scanBank(project.target(), bank, project.voices().size(),
                    ProjectRenderTiming.presentationCount(project), source, revision, project.name(),
                    project.voices().stream().map(com.opensmpsdeck.project.Voice::name).toList(), catalogue);
        }
    }

    private static void scan(StudioProject project, String source, String revision, Integer songId, Builder catalogue)
            throws InterruptedException {
        ProjectFormat.validate(project);
        catalogue.song();
        var observer = new Observer(project, source, revision, songId, catalogue);
        observer.collectDeclared();
        try {
            var compiled = ProjectCompiler.compileWithAssets(project);
            var sequencer = S3kRenderer.timelineSequencer(compiled, observer::restore, observer);
            sequencer.setNoteListener(observer);
            int limit = ProjectRenderTiming.presentationCount(project);
            while (observer.frame < limit && !sequencer.isComplete() && !observer.restored) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                sequencer.serviceOuterFrame();
                for (Track track : sequencer.getTracks()) observer.observe(track);
                observer.attacks.clear();
                observer.frame++;
            }
            for (NoiseSpan span : observer.noise.values()) observer.finishNoise(span);
            observer.finishNoiseFlags();
            if (observer.restored) catalogue.issue(source, "restore-context",
                    project.name() + ": scan ended at the request to restore prior music");
        } catch (IllegalArgumentException failure) {
            catalogue.issue(source, "playback-scan", project.name() + ": " + failure.getMessage());
        }
    }

    private record Reference(String asset, String name, Integer slot, String table) {}
    private record Seen(Reference reference, String lane, Recipe recipe) {}
    private record PsgPitch(int note, int period, int pitch) {}
    private record NoiseState(int latch, int volume, int detune, Reference envelope,
                              Reference modulation, List<Integer> customModulation) {}
    private record NoisePitch(int frame, int pitch) {}
    private record NoiseSpan(String lane, int onset, NoiseState state, List<NoisePitch> pitches) {}
    private record NoiseFlag(String lane, NoiseState state) {}

    private static final class Observer extends Sonic3kCoordFlagHandler implements SmpsNoteListener {
        final StudioProject project;
        final String source;
        final String revision;
        final Integer songId;
        final Builder catalogue;
        final Map<Integer, Reference> voices = new HashMap<>();
        final Map<Integer, Reference> psg = new HashMap<>();
        final Map<Integer, Reference> modulation = new HashMap<>();
        final Map<Integer, Reference> samples = new HashMap<>();
        final Map<Integer, Integer> rates = new HashMap<>();
        final Map<Track, Integer> fmEnvelope = new IdentityHashMap<>();
        final Set<Track> attacks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Seen> heard = new HashSet<>();
        final Map<Track, PsgPitch> psgPitches = new IdentityHashMap<>();
        final Map<Track, NoiseSpan> noise = new LinkedHashMap<>();
        final Map<NoiseFlag, NoiseSpan> noiseFlags = new LinkedHashMap<>();
        final Set<NoiseFlag> heardNoise = new HashSet<>();
        int frame;
        boolean restored;

        Observer(StudioProject project, String source, String revision, Integer songId, Builder catalogue) {
            this.project = project;
            this.source = source;
            this.revision = revision;
            this.songId = songId;
            this.catalogue = catalogue;
        }

        void collectDeclared() {
            var nativeProgram = project.nativeProgram();
            if (nativeProgram == null) {
                for (int index = 0; index < project.voices().size(); index++) {
                    var voice = project.voices().get(index);
                    voices.put(index, declare(Kind.FM_VOICE, voice.name(), bytes(voice.bytes()), index, "project-fm"));
                }
                for (var envelope : project.envelopes()) {
                    var table = Envelope.PSG_VOLUME.equals(envelope.type()) ? psg : modulation;
                    int slot = table.size() + 1;
                    table.put(slot, declareEnvelope(envelope, slot));
                }
                for (int index = 0; index < project.samples().size(); index++) {
                    var sample = project.samples().get(index);
                    declareSample(sample, DacSample.FIRST_NOTE + index, sample.rate());
                }
                return;
            }

            byte[] bank = nativeProgram.bank();
            int header = nativeProgram.headerOffset();
            int voicePointer = (bank[header] & 0xFF) | ((bank[header + 1] & 0xFF) << 8);
            int globalOffset = voicePointer == 0 ? 0 : (voicePointer - GLOBAL_VOICE_ADDRESS) / VOICE_BYTES;
            for (int index = 0; index < project.voices().size(); index++) {
                var voice = project.voices().get(index);
                var ref = declare(Kind.FM_VOICE, voice.name(), bytes(voice.bytes()), index, "global-fm");
                if (nativeProgram.localVoiceCount() == 0 && index >= globalOffset) {
                    voices.put(index - globalOffset, ref);
                }
            }
            int localStart = voicePointer - nativeProgram.bankBase();
            int localEnd = localStart + nativeProgram.localVoiceCount() * VOICE_BYTES;
            if (nativeProgram.localVoiceCount() > 0 && (localStart < 0 || localEnd > bank.length)) {
                catalogue.issue(source, "voice-table", project.name() + ": local voice table outside the bank");
            } else if (nativeProgram.localVoiceCount() > 0) {
                for (int index = 0; index < nativeProgram.localVoiceCount(); index++) {
                    int start = localStart + VOICE_BYTES * index;
                    String name = "Local FM %02X".formatted(index);
                    voices.put(index, declare(Kind.FM_VOICE, name,
                            Arrays.copyOfRange(bank, start, start + VOICE_BYTES), index, "local-fm"));
                }
            }

            var byEnvelope = new HashMap<String, Envelope>();
            for (var envelope : project.envelopes()) byEnvelope.put(envelope.id(), envelope);
            var boundEnvelopes = new HashSet<String>();
            for (var binding : nativeProgram.envelopeSlots()) {
                var envelope = byEnvelope.get(binding.assetId());
                var table = Envelope.PSG_VOLUME.equals(envelope.type()) ? psg : modulation;
                table.put(binding.slot(), declareEnvelope(envelope, binding.slot()));
                boundEnvelopes.add(envelope.id());
            }
            for (var envelope : project.envelopes()) {
                if (boundEnvelopes.contains(envelope.id())) continue;
                if (Envelope.MODULATION.equals(envelope.type()) && envelope.id().endsWith("-rom")) {
                    String id = catalogue.asset(Kind.MODULATION, envelope.name(), bytes(envelope.bytes()));
                    catalogue.issue(source, "unresolved-envelope", envelope.name() + " (" + id
                            + "): original cartridge bytes lack the resolved driver operands");
                } else {
                    declareEnvelope(envelope, null);
                }
            }

            var bySample = new HashMap<String, DacSample>();
            for (var sample : project.samples()) bySample.put(sample.id(), sample);
            var boundSamples = new HashSet<String>();
            for (var binding : nativeProgram.dacSlots()) {
                var sample = bySample.get(binding.sampleId());
                declareSample(sample, binding.note(), binding.rate());
                boundSamples.add(sample.id());
            }
            for (var sample : project.samples()) {
                if (!boundSamples.contains(sample.id())) declareSample(sample, null, sample.rate());
            }
        }

        Reference declareEnvelope(Envelope envelope, Integer slot) {
            Kind kind = Envelope.PSG_VOLUME.equals(envelope.type()) ? Kind.PSG_VOLUME : Kind.MODULATION;
            byte[] payload = bytes(envelope.bytes());
            try {
                EnvelopeProgram.validateNativePlayback(envelope.type(), envelope.bytes());
            } catch (IllegalArgumentException failure) {
                String id = catalogue.asset(kind, envelope.name(), payload);
                catalogue.issue(source, "unresolved-envelope", envelope.name() + " (" + id + "): " + failure.getMessage());
                return null;
            }
            return declare(kind, envelope.name(), payload, slot, kind == Kind.PSG_VOLUME ? "psg-envelope" : "modulation");
        }

        void declareSample(DacSample sample, Integer note, int rate) {
            var ref = new Reference(catalogue.asset(Kind.DAC_SAMPLE, sample.name(), sample.pcm()),
                    sample.name(), note, "dac");
            catalogue.variant(ref.asset(), ref.name(), dacRecipe(rate, 0xC0), occurrence(note, null, null, false, ref.table()));
            if (note != null) {
                samples.put(note, ref);
                rates.put(note, rate);
            }
        }

        Reference declare(Kind kind, String name, byte[] payload, Integer slot, String table) {
            var ref = new Reference(catalogue.asset(kind, name, payload), name, slot, table);
            catalogue.variant(ref.asset(), ref.name(), Recipe.plain(), occurrence(slot, null, null, false, table));
            return ref;
        }

        @Override public boolean handleFlag(CoordFlagContext context, Track track, int opcode) {
            int at = track.pos;
            boolean handled = super.handleFlag(context, track, opcode);
            if (opcode == 0xFF && track.type == TrackType.FM && context.programView().dataByteAt(at) == 0x06) {
                fmEnvelope.put(track, context.programView().dataByteAt(at + 1) & 0xFF);
            }
            if (opcode == 0xF3 && track.type == TrackType.PSG && track.rawPsgNoise != 0) {
                var span = startNoise(track, captureNoise(track));
                noiseFlags.putIfAbsent(new NoiseFlag(span.lane(), span.state()), span);
            }
            return handled;
        }

        void restore() { restored = true; }

        @Override public void attack(Track track) {
            if (track.type == TrackType.DAC) {
                var sample = samples.get(track.note);
                if (sample != null) markHeard(sample, lane(track), dacRecipe(rates.get(track.note), track.pan & 0xC0));
            } else {
                attacks.add(track);
            }
        }

        @Override public void release(Track track) {
            var span = noise.remove(track);
            if (span != null) finishNoise(span);
        }

        void observe(Track track) {
            if (track.type == TrackType.DAC) return;
            String lane = lane(track);
            boolean sounding = track.active && !track.resting && !track.overridden && track.note >= 0x81;
            if (sounding) {
                if (track.type == TrackType.FM && track.voiceData != null) {
                    markHeard(voices.get(track.voiceId), lane, Recipe.plain());
                    if (track.fmVolEnvData != null) {
                        markHeard(psg.get(fmEnvelope.get(track)), lane, Recipe.plain());
                    }
                } else if (track.type == TrackType.PSG && track.envData != null) {
                    markHeard(psg.get(track.instrumentId), lane, Recipe.plain());
                }
                if (track.modEnvData != null && track.modEnabled) {
                    markHeard(modulation.get(track.modEnvId), lane, Recipe.plain());
                }
            }
            if (track.type != TrackType.PSG || track.channelId != 2) return;
            if (sounding) rememberPitch(track);
            NoiseState current = sounding && !track.envAtRest && track.noiseMode && track.rawPsgNoise != 0
                    ? captureNoise(track) : null;
            NoiseSpan active = noise.get(track);
            if (active != null && (attacks.contains(track) || !active.state().equals(current))) {
                noise.remove(track);
                finishNoise(active);
            }
            if (current != null) {
                NoiseSpan span = noise.computeIfAbsent(track, ignored -> startNoise(track, current));
                int pitch = noisePitch(track);
                if (frame - span.onset() < NOISE_AUDITION_FRAMES && span.pitches().getLast().pitch() != pitch) {
                    span.pitches().add(new NoisePitch(frame, pitch));
                }
                heardNoise.add(new NoiseFlag(lane, current));
            }
        }

        void rememberPitch(Track track) {
            var last = psgPitches.get(track);
            if (last == null || track.note != last.note() || track.baseFnum != last.period())
                psgPitches.put(track, new PsgPitch(track.note, track.baseFnum, transposedPitch(track)));
        }

        int noisePitch(Track track) {
            var saved = psgPitches.get(track);
            return saved == null ? transposedPitch(track) : saved.pitch();
        }

        NoiseSpan startNoise(Track track, NoiseState state) {
            var pitches = new ArrayList<NoisePitch>();
            pitches.add(new NoisePitch(frame, noisePitch(track)));
            return new NoiseSpan(lane(track), frame, state, pitches);
        }

        NoiseState captureNoise(Track track) {
            int volume = 127 - 8 * Math.clamp(track.volumeOffset, 0, 15);
            var custom = track.customModEnabled ? List.of(track.modPendingDelayInit, track.modPendingRate & 0xFF,
                    track.modPendingDelta & 0xFF, track.modPendingStepsFull) : List.<Integer>of();
            return new NoiseState(track.rawPsgNoise, volume, track.detune & 0xFF,
                    psg.get(track.instrumentId), modulation.get(track.modEnvId), custom);
        }

        void finishNoise(NoiseSpan span) {
            int duration = Math.min(NOISE_AUDITION_FRAMES, frame - span.onset());
            if (duration > 0) putNoise(span, duration, true);
        }

        void finishNoiseFlags() {
            noiseFlags.forEach((flag, span) -> {
                if (!heardNoise.contains(flag)) putNoise(span, 12, false);
            });
        }

        void putNoise(NoiseSpan span, int duration, boolean played) {
            var state = span.state();
            String name = "PSG noise %02X".formatted(state.latch());
            String asset = catalogue.asset(Kind.PSG_NOISE, name, new byte[]{(byte) state.latch()});
            var commands = new ArrayList<CommandEvent>();
            commands.add(new CommandEvent("noise", 0, NativeCommand.PSG_NOISE, List.of(state.latch())));
            if (state.detune() != 0)
                commands.add(new CommandEvent("detune", 0, NativeCommand.DETUNE, List.of(state.detune())));
            if (!state.customModulation().isEmpty())
                commands.add(new CommandEvent("modulation", 0, NativeCommand.MODULATION, state.customModulation()));
            var notes = new ArrayList<NoteEvent>();
            int transpose = 0;
            for (int index = 0; index < span.pitches().size(); index++) {
                var item = span.pitches().get(index);
                int tick = item.frame() - span.onset();
                if (tick >= duration) break;
                int next = index + 1 < span.pitches().size()
                        ? Math.min(duration, span.pitches().get(index + 1).frame() - span.onset()) : duration;
                int pitch = TargetProfile.S3K.psg().clamp(item.pitch());
                int nextTranspose = item.pitch() - pitch;
                if (nextTranspose != transpose) {
                    commands.add(new CommandEvent("transpose-" + index, tick, NativeCommand.TRANSPOSE,
                            List.of(0x40 + nextTranspose)));
                    transpose = nextTranspose;
                }
                if (index != 0)
                    commands.add(new CommandEvent("hold-" + index, tick, NativeCommand.HOLD, List.of()));
                notes.add(new NoteEvent("note-" + index, tick, next - tick, pitch));
            }
            var recipe = new Recipe(null, null, state.volume(), duration, id(state.envelope()), id(state.modulation()),
                    notes, commands);
            catalogue.variant(asset, name, recipe, occurrence(state.latch(), span.lane(), span.onset(), played, "psg-noise"));
        }

        void markHeard(Reference ref, String lane, Recipe recipe) {
            if (ref != null && heard.add(new Seen(ref, lane, recipe))) {
                catalogue.variant(ref.asset(), ref.name(), recipe, occurrence(ref.slot(), lane, frame, true, ref.table()));
            }
        }

        Occurrence occurrence(Integer slot, String lane, Integer tick, boolean played, String table) {
            return new Occurrence(source, project.mode(), revision, project.name(), songId, slot, lane, tick, played, table);
        }
    }

    private static int transposedPitch(Track track) {
        return 36 + Math.clamp(track.note - 0x81 + track.keyOffset, 0, 83);
    }

    private static Recipe dacRecipe(int rate, int pan) {
        return new Recipe(rate, pan == 0xC0 ? null : pan, 127, 60, null, null, List.of(), List.of());
    }

    private static String id(Reference ref) {
        return ref == null ? null : ref.asset();
    }

    private static String lane(Track track) {
        return track.type == TrackType.DAC ? "DAC" : track.type.name() + (track.channelId + 1);
    }

    private static byte[] bytes(List<Integer> values) {
        byte[] result = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i).byteValue();
        return result;
    }

}
