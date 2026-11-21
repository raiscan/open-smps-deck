package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.NativeProgram;
import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.session.*;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.FmCoreSelection;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.Objects;

/** Bounded, no-device rendering through the pinned production session. */
public final class S3kRenderer {
    public static final int SAMPLE_RATE = 48_000;
    public static final int PRESENTATIONS_PER_SECOND = 60;
    public static final int STEREO_FRAMES_PER_PRESENTATION = SAMPLE_RATE / PRESENTATIONS_PER_SECOND;
    public static final int MAX_PRESENTATIONS = 36_000;

    @FunctionalInterface public interface PcmSink {
        /** Called synchronously with a reused buffer; copy it if retaining samples. */
        void accept(short[] interleavedStereo) throws IOException;
    }

    /** Set bits mute hardware outputs. FM bit 5 also covers DAC. */
    public record ChannelMask(int fm, int psg) {
        public static final ChannelMask NONE = new ChannelMask(0, 0);
    }

    private S3kRenderer() {}

    public static void render(byte[] input, int presentations, PcmSink sink) throws IOException {
        render(input, presentations, sink, ChipWriteObserver.NONE);
    }

    /**
     * Render with a synchronous diagnostic observer of resolved chip writes.
     * Session initialization and each presentation may emit writes; the sink is
     * invoked after each presentation, allowing a caller to stamp that boundary.
     * The observer must not modify the session or re-enter rendering.
     */
    public static void render(byte[] input, int presentations, PcmSink sink, ChipWriteObserver observer) throws IOException {
        validatePresentations(presentations);
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(observer, "observer");
        render(new CompiledProject(S3kBinary.validateAndCopy(input), List.of()), presentations, sink, observer);
    }

    public static void render(CompiledProject project, int presentations, PcmSink sink) throws IOException {
        render(project, presentations, sink, ChipWriteObserver.NONE);
    }

    /** Render one immutable native bank together with its explicitly supplied dependencies. */
    public static void render(CompiledProject project, int presentations, PcmSink sink, ChipWriteObserver observer) throws IOException {
        render(project, presentations, ChannelMask.NONE, sink, observer);
    }

    public static void render(CompiledProject project, int presentations, ChannelMask mask, PcmSink sink) throws IOException {
        render(project, presentations, mask, sink, ChipWriteObserver.NONE);
    }

    static void render(CompiledProject project, int presentations, ChannelMask mask,
                               PcmSink sink, ChipWriteObserver observer) throws IOException {
        validatePresentations(presentations);
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(mask, "mask");
        MusicRestoreSink unexpectedRestore = () -> {
            throw new IllegalStateException("music restoration requires a previous project");
        };
        var prepared = prepare(project, 0, false, unexpectedRestore);
        renderPrepared(prepared, Sonic3kSmpsPhysicalPolicy.INSTANCE, presentations, mask, sink, observer);
    }

    static void renderMusic(AbstractSmpsData data, DacData dac, SmpsSequencerConfig config,
                            SmpsPhysicalPolicy policy, int presentations, ChannelMask mask, PcmSink sink) throws IOException {
        var activation = prepare(data, dac, config, () -> {
            throw new IllegalStateException("music restoration requires a previous project");
        });
        renderPrepared(activation, policy, presentations, mask, sink, ChipWriteObserver.NONE);
    }

    private static void renderPrepared(PreparedSmpsMusicActivation prepared, SmpsPhysicalPolicy policy,
                                       int presentations, ChannelMask mask, PcmSink sink, ChipWriteObserver observer)
            throws IOException {
        try (var session = newSession(observer, policy)) {
            start(session, prepared, mask);
            var pcm = new short[STEREO_FRAMES_PER_PRESENTATION * 2];
            for (int frame = 0; frame < presentations; frame++) {
                advance(session, pcm);
                sink.accept(pcm);
            }
        }
    }

    static SmpsDriverSession openPlayback(CompiledProject project, ChannelMask mask, ChipWriteObserver observer) {
        var prepared = prepare(project, 0, false, () -> {
            throw new IllegalStateException("music restoration requires a previous project");
        });
        var session = newSession(observer, Sonic3kSmpsPhysicalPolicy.INSTANCE);
        try {
            start(session, prepared, mask);
            return session;
        } catch (RuntimeException | Error failure) {
            session.close();
            throw failure;
        }
    }

    private static void start(SmpsDriverSession session, PreparedSmpsMusicActivation prepared, ChannelMask mask) {
        session.install();
        session.queueActivation(prepared);
        if (!mask.equals(ChannelMask.NONE)) session.applyChannelMasks(mask.fm(), mask.psg());
    }

    public static void renderWithPreviousMusic(CompiledProject previous, int previousPresentations,
                                               CompiledProject cue, int presentations, PcmSink sink) throws IOException {
        renderWithPreviousMusic(previous, previousPresentations, cue, presentations, sink, ChipWriteObserver.NONE);
    }

    /**
     * Play the previous project to the specified interruption point, then render the cue and
     * any music it restores. Warm-up PCM and chip writes are excluded from the supplied sinks.
     */
    public static void renderWithPreviousMusic(CompiledProject previous, int previousPresentations,
                                               CompiledProject cue, int presentations, PcmSink sink,
                                               ChipWriteObserver observer) throws IOException {
        renderWithPreviousMusic(previous, previousPresentations, cue, presentations, ChannelMask.NONE, sink, observer);
    }

    static void renderWithPreviousMusic(CompiledProject previous, int previousPresentations,
                                        CompiledProject cue, int presentations, ChannelMask mask, PcmSink sink,
                                        ChipWriteObserver observer) throws IOException {
        validatePreviousPresentations(previousPresentations);
        validatePresentations(presentations);
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(observer, "observer");

        var previousActivation = prepare(previous, 0, false, () -> {
            throw new IllegalStateException("the previous project requested its own restoration");
        });
        boolean[] restoreRequested = {false};
        // Distinct IDs ensure PushOverride saves the previous slot even when the banks match.
        var cueActivation = prepare(cue, 1, true, () -> restoreRequested[0] = true);
        try (var session = newSession(ChipWriteObserver.NONE, Sonic3kSmpsPhysicalPolicy.INSTANCE)) {
            session.install();
            session.queueActivation(previousActivation);
            var pcm = new short[STEREO_FRAMES_PER_PRESENTATION * 2];
            for (int frame = 0; frame < previousPresentations; frame++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("previous-project warm-up cancelled");
                }
                advance(session, pcm);
            }
            if (session.captureLogicalSnapshot().sequencers().stream().noneMatch(entry -> !entry.sfx())) {
                throw new IllegalArgumentException("previous project has no music to restore after warm-up");
            }
            session.setChipWriteObserver(observer);
            session.applyCommand(new SmpsSessionCommand.PushOverride(cueActivation));
            if (!mask.equals(ChannelMask.NONE)) session.applyChannelMasks(mask.fm(), mask.psg());
            for (int frame = 0; frame < presentations; frame++) {
                // E2 stores a flag during track traversal. Apply it at the next
                // presentation boundary, after the cue's service and DAC idle loop.
                if (restoreRequested[0]) {
                    restoreRequested[0] = false;
                    session.applyCommand(new SmpsSessionCommand.RestoreOverride());
                    if (!mask.equals(ChannelMask.NONE)) session.applyChannelMasks(mask.fm(), mask.psg());
                    session.fadeInRestoredMusic();
                }
                advance(session, pcm);
                sink.accept(pcm);
            }
        }
    }

    static void advance(SmpsDriverSession session, short[] pcm) {
        session.serviceForward();
        session.advanceDacIdleLoop(pcm, STEREO_FRAMES_PER_PRESENTATION);
    }

    private static PreparedSmpsMusicActivation prepare(CompiledProject project, int id, boolean allowRestore,
                                                       MusicRestoreSink restore) {
        Objects.requireNonNull(project, "project");
        var data = prepareData(project, allowRestore);
        data.setId(id);
        var dac = nativeDac(project.samples(), project.nativeProgram());
        var config = Sonic3kSmpsSequencerConfig.create(new com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler());
        return prepare(data, dac, config, restore);
    }

    private static PreparedSmpsMusicActivation prepare(AbstractSmpsData data, DacData dac,
                                                        SmpsSequencerConfig config, MusicRestoreSink restore) {
        var source = SmpsSourceDescriptor.from(data);
        var detached = new SmpsDriver();
        var sequencer = new SmpsSequencer(data, dac, detached, detached, restore, config, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        var incoming = new SmpsDriverSnapshot.SequencerEntry(false, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE,
                null, data, dac, restore, config, sequencer.captureSnapshot());
        return new PreparedSmpsMusicActivation(
                new SmpsMusicActivation(source, data.getChannels(), data.getPsgChannels()),
                incoming, SmpsLogicalTransitionPolicies.forConfig(config), new SmpsDacSelection(source, dac));
    }

    static SmpsSequencer timelineSequencer(CompiledProject project, MusicRestoreSink restore,
                                          Sonic3kCoordFlagHandler handler) {
        var data = prepareData(project, true);
        var dac = nativeDac(project.samples(), project.nativeProgram());
        var config = Sonic3kSmpsSequencerConfig.create(handler);
        return new SmpsSequencer(data, dac, restore, config);
    }

    private static Sonic3kSmpsData prepareData(CompiledProject project, boolean allowRestore) {
        var data = project.nativeProgram() == null
                ? new Sonic3kSmpsData(S3kBinary.validateAndCopy(project), 0)
                : new NativeBankData(project, S3kBinary.inspectNative(project, allowRestore));
        // These arrays belong to this sequencer and are installed before any
        // sequencer state is captured.
        data.setPsgEnvelopes(nativeEnvelopes(project.psgEnvelopes()));
        data.setModEnvelopes(nativeEnvelopes(project.modulationEnvelopes()));
        return data;
    }

    private static SmpsDriverSession newSession(ChipWriteObserver observer, SmpsPhysicalPolicy policy) {
        var settings = SmpsHardwareFrameBudget.presentationSettings(SAMPLE_RATE, PRESENTATIONS_PER_SECOND,
                false, false, FmCoreSelection.ACCURATE, null);
        return new SmpsDriverSession(settings, policy, observer,
                new SmpsSessionProfileFingerprint("s3k", 0, policy.identity(), settings),
                SmpsDriverSessionConfiguration.DEFAULT);
    }

    private static Map<Integer, byte[]> nativeEnvelopes(Map<Integer, List<Integer>> source) {
        Map<Integer, byte[]> owned = new HashMap<>();
        source.forEach((index, values) -> {
            byte[] bytes = new byte[values.size()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (int) values.get(i);
            owned.put(index, bytes);
        });
        return Map.copyOf(owned);
    }

    static DacData nativeDac(List<DacSample> declarations, NativeProgram nativeProgram) {
        Map<Integer, byte[]> samples = new HashMap<>();
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();
        Map<String, Integer> sampleIndices = new HashMap<>();
        for (int index = 0; index < declarations.size(); index++) {
            DacSample sample = declarations.get(index);
            samples.put(index, sample.pcm());
            if (nativeProgram == null) {
                mapping.put(DacSample.FIRST_NOTE + index, new DacData.DacEntry(index, sample.rate()));
            } else {
                sampleIndices.put(sample.id(), index);
            }
        }
        if (nativeProgram != null) {
            for (var slot : nativeProgram.dacSlots()) {
                mapping.put(slot.note(), new DacData.DacEntry(sampleIndices.get(slot.sampleId()), slot.rate()));
            }
        }
        // DacData takes another private copy. The same immutable dependency is
        // supplied both to logical sequencing and production physical activation.
        return new DacData(samples, mapping, DacSample.BASE_CYCLES);
    }

    private static final class NativeBankData extends Sonic3kSmpsData {
        private static final int VOICE_BYTES = 25;
        private final byte[] bank;
        private final byte[] globalVoices;
        private final int voiceOffset;
        private final int localVoiceCount;
        private final int globalVoiceOffset;

        NativeBankData(CompiledProject project, NativeBankAnalysis.Analysis analysis) {
            super(analysis.header(), analysis.headerAddress());
            bank = analysis.bank();
            setBankData(bank, project.nativeProgram().bankBase());
            voiceOffset = analysis.voiceOffset();
            localVoiceCount = analysis.localVoiceCount();
            globalVoiceOffset = analysis.globalVoiceOffset();
            globalVoices = new byte[project.globalVoices().size() * VOICE_BYTES];
            int at = 0;
            for (var voice : project.globalVoices()) {
                for (int value : voice.bytes()) globalVoices[at++] = (byte) value;
            }
            setGlobalVoiceData(globalVoices);
        }

        @Override
        public int fmPointerCount() {
            // The retail loader overwrites a declared seventh DAC/FM slot
            // when it initializes PSG1. Keep its header bytes for export.
            return Math.min(super.fmPointerCount(), 6);
        }

        @Override
        public byte[] getVoice(int voiceId) {
            if (voiceId < 0 || voiceId >= 128) return null;
            if (voiceOffset >= 0) {
                if (voiceId >= localVoiceCount) return null;
                int offset = voiceOffset + voiceId * VOICE_BYTES;
                return Arrays.copyOfRange(bank, offset, offset + VOICE_BYTES);
            }
            if (globalVoiceOffset < 0) return null;
            int offset = (globalVoiceOffset + voiceId) * VOICE_BYTES;
            return offset + VOICE_BYTES <= globalVoices.length
                    ? Arrays.copyOfRange(globalVoices, offset, offset + VOICE_BYTES) : null;
        }
    }

    static void validatePreviousPresentations(int presentations) {
        if (presentations < 1 || presentations > MAX_PRESENTATIONS)
            throw new IllegalArgumentException("--previous-frames must be between 1 and " + MAX_PRESENTATIONS);
    }

    static void validatePresentations(int presentations) {
        if (presentations < 1 || presentations > MAX_PRESENTATIONS)
            throw new IllegalArgumentException("--frames must be between 1 and " + MAX_PRESENTATIONS);
    }
}
