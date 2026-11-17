package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.NativeProgram;
import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.session.*;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.FmCoreSelection;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import java.io.IOException;
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
        validatePresentations(presentations);
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(project, "project");
        var nativeProgram = project.nativeProgram();
        var data = nativeProgram == null
                ? new Sonic3kSmpsData(S3kBinary.validateAndCopy(project), 0)
                : new NativeBankData(project, S3kBinary.inspectNative(project));
        Map<Integer, List<Integer>> psg = project.psgEnvelopes(), mod = project.modulationEnvelopes();
        // The pinned setters retain their inputs. These arrays belong only to
        // this render and are installed before any sequencer state is captured.
        data.setPsgEnvelopes(nativeEnvelopes(psg));
        data.setModEnvelopes(nativeEnvelopes(mod));
        var dac = nativeDac(project.samples(), nativeProgram);
        var config = Sonic3kSmpsSequencerConfig.create(new com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler());
        var source = SmpsSourceDescriptor.from(data);
        MusicRestoreSink restore = () -> { throw new IllegalStateException("music restoration is outside the standalone proof"); };
        var detached = new SmpsDriver();
        var sequencer = new SmpsSequencer(data, dac, detached, detached, restore, config, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        var incoming = new SmpsDriverSnapshot.SequencerEntry(false, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE,
                null, data, dac, restore, config, sequencer.captureSnapshot());
        var prepared = new PreparedSmpsMusicActivation(
                new SmpsMusicActivation(source, data.getChannels(), data.getPsgChannels()),
                incoming, SmpsLogicalTransitionPolicies.forConfig(config), new SmpsDacSelection(source, dac));
        var settings = SmpsHardwareFrameBudget.presentationSettings(SAMPLE_RATE, PRESENTATIONS_PER_SECOND,
                false, false, FmCoreSelection.ACCURATE, null);
        var policy = Sonic3kSmpsPhysicalPolicy.INSTANCE;
        try (var session = new SmpsDriverSession(settings, policy, observer,
                new SmpsSessionProfileFingerprint("s3k", 0, policy.identity(), settings),
                SmpsDriverSessionConfiguration.DEFAULT)) {
            session.install();
            session.queueActivation(prepared);
            short[] pcm = new short[STEREO_FRAMES_PER_PRESENTATION * 2];
            for (int frame = 0; frame < presentations; frame++) {
                session.serviceForward();
                session.advanceDacIdleLoop(pcm, STEREO_FRAMES_PER_PRESENTATION);
                sink.accept(pcm);
            }
        }
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

    private static DacData nativeDac(List<DacSample> declarations, NativeProgram nativeProgram) {
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

    static void validatePresentations(int presentations) {
        if (presentations < 1 || presentations > MAX_PRESENTATIONS)
            throw new IllegalArgumentException("--frames must be between 1 and " + MAX_PRESENTATIONS);
    }
}
