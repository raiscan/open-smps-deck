package com.opensmpsdeck.headless;

import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.session.PreparedSmpsMusicActivation;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsDriverSessionSnapshot;
import com.opensmpsdeck.project.TargetGame;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** In-memory driver checkpoints at NTSC presentation boundaries. Readers are thread-confined. */
public final class CheckpointPlayback {
    public static final int INTERVAL = S3kRenderer.PRESENTATIONS_PER_SECOND;
    private final TargetPlayback playback;
    private final PreparedSmpsMusicActivation activation;
    private final TreeMap<Integer, State> checkpoints = new TreeMap<>();

    private record State(SmpsDriverSnapshot logical, SmpsDriverSessionSnapshot physical) {}

    CheckpointPlayback(TargetPlayback playback) {
        this.playback = playback;
        activation = playback.prepareCheckpointPlayback();
    }

    public Reader reader() { return new Reader(); }

    /** A transient exact position for transferring a prepared handoff between owner threads. */
    public final class Bookmark {
        private final int position;
        private final State state;

        private Bookmark(int position, State state) {
            this.position = position;
            this.state = state;
        }

        public int position() { return position; }

        public Reader reader() {
            var reader = new Reader();
            try {
                reader.restore(position, state);
                return reader;
            } catch (RuntimeException | Error failure) {
                reader.close();
                throw failure;
            }
        }
    }

    public synchronized int checkpointAtOrBefore(int presentation) {
        var key = checkpoints.floorKey(presentation);
        return key == null ? 0 : key;
    }

    public synchronized int checkpointCount() { return checkpoints.size(); }

    private synchronized List<java.util.Map.Entry<Integer, State>> before(int presentation) {
        return new ArrayList<>(checkpoints.headMap(presentation, false).entrySet());
    }

    /** Rebuild missing regular checkpoints. Can run concurrently with a playback reader. */
    public void warmThrough(int presentation) throws InterruptedIOException {
        checkPosition(presentation);
        int last = presentation / INTERVAL * INTERVAL;
        int missing = INTERVAL;
        try (var reader = reader()) {
            while (missing <= last) {
                synchronized (this) {
                    while (missing <= last && checkpoints.containsKey(missing)) missing += INTERVAL;
                }
                if (missing > last) return;
                reader.seek(missing);
                missing += INTERVAL;
            }
        }
    }

    /**
     * Fork the compatible checkpoint prefix into a new revision. The cutoff is exclusive.
     * Reuse requires matching assets, channel headers and executed events up to each live
     * cursor. An unproven prefix is rebuilt from the last verified checkpoint (or origin).
     */
    public CheckpointPlayback revise(TargetPlayback replacement, int cutoff) throws InterruptedIOException {
        var next = replacement.checkpoints();
        // A changed classic bank starts a new grid. The relocation proof covers S3K packing.
        if (!playback.target().s3kRules() || !replacement.target().s3kRules()) return next;
        var prefix = S3kProgramPrefix.between(playback.compiled(), replacement.compiled());
        if (prefix == null) return next;
        for (var entry : before(cutoff)) {
            checkInterrupted();
            var state = entry.getValue();
            var logical = state.logical();
            if (logical.sequencers().size() != 1 || logical.pendingService() != null
                    || !logical.savedOverrides().isEmpty()) break;
            var old = logical.sequencers().getFirst();
            if (old.sfx() || old.fallbackVoiceSource() != null || old.snapshot().tracks().size() != prefix.trackCount()) break;
            var mapped = new ArrayList<SmpsTrackSnapshot>(prefix.trackCount());
            for (int i = 0; i < prefix.trackCount(); i++) {
                var track = old.snapshot().tracks().get(i);
                var cursor = prefix.relocate(i, track);
                if (cursor == null) break;
                mapped.add(withCursor(track, cursor));
            }
            if (mapped.size() != prefix.trackCount()) break;
            var music = next.activation.incomingMusic();
            var rebased = new SmpsDriverSnapshot.SequencerEntry(false, music.source(), music.sourceDescriptorTrust(),
                    null, music.smpsData(), music.dacData(), music.audioManager(), music.config(),
                    withTracks(old.snapshot(), mapped));
            next.checkpoints.put(entry.getKey(), new State(withMusic(logical, rebased), withSource(state.physical(), music)));
        }
        return next;
    }

    private static SmpsDriverSessionSnapshot withSource(SmpsDriverSessionSnapshot s, SmpsDriverSnapshot.SequencerEntry music) {
        return new SmpsDriverSessionSnapshot(s.initialized(), s.pendingGlobalCommand(), s.profile(),
                s.selectedDacSource() == null ? null : music.source(), s.speedShoesEnabled(), s.speedMultiplier(),
                s.musicTempoPercent(), s.ringLeft(), s.musicFmDacTrackCount(), s.segaPcmTransport(), s.physical(),
                s.nativePause(), s.nativeHistoryCursor());
    }

    private static SmpsDriverSnapshot withMusic(SmpsDriverSnapshot s, SmpsDriverSnapshot.SequencerEntry music) {
        return new SmpsDriverSnapshot(s.region(), s.readMode(), s.continuousSfxId(), s.continuousSfxFlag(),
                s.contSfxLoopCnt(), s.palUpdateCounter(), List.of(music), s.fmLockSequencerIds(),
                s.psgLockSequencerIds(), s.savedOverrides(), s.pendingService(), s.fadeDelay(),
                s.fadeDelayTimeout(), s.fadeOutTimeout(), s.fadeInTimeout(), s.driverOwnedFade());
    }

    private static SmpsSequencerSnapshot withTracks(SmpsSequencerSnapshot s, List<SmpsTrackSnapshot> tracks) {
        return new SmpsSequencerSnapshot(s.region(), s.speedShoes(), s.sfxMode(), s.normalTempo(), s.commData(),
                s.fm6DacOff(), s.maxTicks(), s.pitch(), s.sfxPriority(), s.specialSfx(), s.sfx(),
                s.psgLatchChannel(), s.speedMultiplier(), s.speedupTimeout(), s.musicTempoPercent(),
                s.musicTempoPhase(), s.fade(), s.sampleRate(), s.samplesPerFrame(), s.sampleCounter(),
                s.tempoWeight(), s.tempoAccumulator(), s.dividingTiming(), s.primed(), tracks);
    }

    private static void checkPosition(int presentation) {
        if (presentation < 0 || presentation > S3kRenderer.MAX_PRESENTATIONS)
            throw new IllegalArgumentException("Checkpoint presentation outside the render limit");
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Checkpoint rendering cancelled");
    }

    public final class Reader implements AutoCloseable {
        private final short[] pcm = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
        private SmpsDriverSession session;
        private int next;
        private int last = -1;

        private Reader() {}

        /** Returns a reused buffer. A caller retaining PCM must copy it before advancing. */
        public short[] at(int presentation) throws InterruptedIOException {
            if (last != presentation) {
                seek(presentation);
                advance();
            }
            return pcm;
        }

        /** Position the session immediately before a presentation without emitting PCM. */
        public void seek(int presentation) throws InterruptedIOException {
            checkPosition(presentation);
            checkInterrupted();
            java.util.Map.Entry<Integer, State> checkpoint;
            synchronized (CheckpointPlayback.this) {
                checkpoint = checkpoints.floorEntry(presentation);
            }
            if (session == null || presentation < next || checkpoint != null && checkpoint.getKey() > next) {
                if (checkpoint == null) {
                    close();
                    open();
                    next = 0;
                } else {
                    restore(checkpoint.getKey(), checkpoint.getValue());
                }
            }
            while (next < presentation) advance();
        }

        public Bookmark bookmark(int presentation) throws InterruptedIOException {
            seek(presentation);
            return new Bookmark(presentation, snapshot());
        }

        private void open() {
            session = playback.openCheckpointPlayback(activation);
        }

        private void restore(int position, State state) {
            if (session == null) open();
            session.commitRestore(session.prepareRestore(state.physical(), state.logical(),
                    source -> activation.selectedDac().data()));
            next = position;
            last = -1;
        }

        private State snapshot() {
            return new State(session.captureLogicalSnapshot(), session.captureSnapshot());
        }

        private void advance() throws InterruptedIOException {
            checkInterrupted();
            S3kRenderer.advance(session, pcm);
            last = next++;
            if (next % INTERVAL == 0 && next <= S3kRenderer.MAX_PRESENTATIONS) capture();
        }

        private void capture() {
            synchronized (CheckpointPlayback.this) {
                if (!checkpoints.containsKey(next)) {
                    checkpoints.put(next, snapshot());
                }
            }
        }

        @Override public void close() {
            if (session != null) session.close();
            session = null;
            last = -1;
        }
    }

    // All non-packing state comes from the original native sequencer.
    private static SmpsTrackSnapshot withCursor(SmpsTrackSnapshot s, S3kProgramPrefix.Cursor cursor) {
        int[] counters = s.loopCounters();
        counters[0] = cursor.loopCount;
        int[] returns = new int[s.returnStack().length];
        int depth = cursor.returnAddress < 0 ? 0 : 1;
        if (depth != 0) returns[0] = cursor.returnAddress;
        return new SmpsTrackSnapshot(
                cursor.pos, s.type(), s.channelId(), s.duration(), s.note(),
                s.active(), s.overridden(), s.rawDuration(), s.scaledDuration(), s.fill(),
                s.fillCounter(), s.resting(), s.keyOffset(), s.volumeOffset(), s.tieNext(),
                s.pan(), s.ams(), s.fms(), s.voiceData(), s.voiceScratch(),
                s.voiceId(), s.baseFnum(), s.baseBlock(), counters, s.loopTarget(),
                returns, depth, s.dividingTiming(), s.modDelay(), s.modDelayInit(),
                s.modRate(), s.modDelta(), s.modSteps(), s.modStepsFull(), s.modPendingDelayInit(),
                s.modPendingRate(), s.modPendingDelta(), s.modPendingSteps(), s.modPendingStepsFull(), s.modRateCounter(),
                s.modStepCounter(), s.modAccumulator(), s.modCurrentDelta(), s.modEnabled(), s.customModEnabled(),
                s.detune(), s.modEnvId(), s.modEnvData(), s.modEnvPos(), s.modEnvMult(),
                s.modEnvCache(), s.modEnvHold(), s.sustainFmFrequency(), s.rawFreqMode(), s.rawFrequency(),
                s.instrumentId(), s.noiseMode(), s.psgNoiseParam(), s.decayOffset(), s.decayTimer(),
                s.envData(), s.envPos(), s.envValue(), s.envHold(), s.envAtRest(),
                s.fmVolEnvData(), s.fmVolEnvPos(), s.fmVolEnvValue(), s.fmVolEnvHold(), s.fmVolEnvOpMask(),
                s.forceRefresh(), s.ssgEg(), s.dacMuted(), s.modStepInEffect(), s.modStepChanged(),
                s.modStepDelta(), s.modEnvStepInEffect(), s.modEnvStepChanged(), s.modEnvStepDelta(), s.fm3SpecialMode(),
                s.customSsgEgPresent(), s.customSsgEgPayload(), s.customSsgEgPayloadKnown(), s.rawPsgNoise(), s.rawPsgNoiseKnown(),
                s.s3kTlPointerInitialized());
    }
}
