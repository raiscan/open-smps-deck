package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/** Async facade over the matching pipeline. No JavaFX dependencies. */
public final class VoiceMatchService {

    /**
     * @param referenceSlice the best validated window's audio (44.1 kHz mono) for
     *                       A/B audition against candidates; null when no candidates
     * @param referencePitch nearest MIDI pitch of the reference slice's measured
     *                       fundamental — audition candidates at this pitch
     */
    public record MatchResult(List<FmPatchSearch.ScoredVoice> candidates,
                              String failureReason,
                              float[] referenceSlice, int referencePitch,
                              ModulationDetector.Modulation referenceModulation) {

        static MatchResult failure(String reason) {
            return new MatchResult(List.of(), reason, null, -1,
                    ModulationDetector.Modulation.NONE);
        }
    }

    /** MIDI-isolated windows to consider before audio validation thins them. */
    private static final int CANDIDATE_WINDOWS = 8;
    private static final int TOP_WINDOWS = 3;
    private static final int SAMPLE_RATE = 44100;
    /** Max leading silence to trim from a misaligned window. */
    private static final double MAX_ONSET_TRIM_SEC = 0.15;
    private static final double MIN_USABLE_SEC = 0.2;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "voice-match");
                t.setDaemon(true);
                return t;
            });

    /**
     * Runs the matching pipeline asynchronously on a single-thread executor.
     * Windows found in the MIDI are validated against the audio itself
     * ({@link WindowValidator}): leading silence trimmed, pitch anchored to the
     * measured fundamental (sub-octave bass layers are common), and windows
     * whose audio is reverb mush rather than the labeled note are rejected.
     *
     * @param progress invoked on the executor thread; marshal to the UI thread yourself
     */
    public CompletableFuture<MatchResult> match(float[] stemAudio, List<NoteEvent> notes,
                                                TickTimeMapper map, FmPatchSearch.Config cfg,
                                                IntConsumer progress) {
        return CompletableFuture.supplyAsync(() -> {
            var windows = MonophonicWindowFinder.find(notes, map, CANDIDATE_WINDOWS);
            if (windows.isEmpty()) {
                return MatchResult.failure(
                        "No isolated note of at least 250 ms found in the MIDI — "
                        + "pick a voice from a bank instead.");
            }

            record Validated(float[] slice, double anchoredHz, double harmonicity,
                             double lengthSec) {}
            List<Validated> validated = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (var w : windows) {
                int start = (int) (w.startSec() * SAMPLE_RATE);
                int len = (int) (w.lengthSec() * SAMPLE_RATE);
                if (start < 0 || start + len > stemAudio.length) continue;
                float[] slice = new float[len];
                System.arraycopy(stemAudio, start, slice, 0, len);

                // trim leading silence from misaligned windows
                int onset = WindowValidator.findOnset(slice, SAMPLE_RATE);
                if (onset > 0 && onset <= MAX_ONSET_TRIM_SEC * SAMPLE_RATE
                        && len - onset >= MIN_USABLE_SEC * SAMPLE_RATE) {
                    slice = java.util.Arrays.copyOfRange(slice, onset, len);
                }

                double labeledHz = 440.0 * Math.pow(2, (w.midiPitch() - 69) / 12.0);
                var v = WindowValidator.validate(slice, SAMPLE_RATE, labeledHz);
                if (v.usable()) {
                    validated.add(new Validated(slice, v.anchoredHz(), v.harmonicity(),
                            slice.length / (double) SAMPLE_RATE));
                } else {
                    reasons.add(String.format("@%.1fs: %s", w.startSec(), v.reason()));
                }
            }
            if (validated.isEmpty()) {
                return MatchResult.failure(
                        "No window where the audio cleanly plays the MIDI's note — "
                        + (reasons.isEmpty() ? "windows fall outside the WAV."
                                             : String.join("; ", reasons)));
            }

            // keep the most harmonic windows (cleanest audio), best first — the
            // finder's MIDI-side score ordering says nothing about audio quality
            validated.sort((a, b) -> Double.compare(b.harmonicity(), a.harmonicity()));
            if (validated.size() > TOP_WINDOWS) {
                validated = validated.subList(0, TOP_WINDOWS);
            }
            List<FmPatchSearch.Target> targets = new ArrayList<>();
            for (Validated v : validated) {
                int anchoredPitch = (int) Math.round(69 + 12 * Math.log(v.anchoredHz() / 440.0)
                        / Math.log(2));
                // vibrato in the stem smears the target's spectrum; carrying the
                // measurement lets candidates render with the same wobble
                var modulation = ModulationDetector.measure(v.slice(), SAMPLE_RATE,
                        v.anchoredHz());
                targets.add(new FmPatchSearch.Target(
                        SpectralTarget.extract(v.slice(), SAMPLE_RATE, v.anchoredHz()),
                        anchoredPitch, Math.min(v.lengthSec(), 1.0), modulation));
            }

            long startMs = System.currentTimeMillis();
            var candidates = FmPatchSearch.search(targets,
                    com.opensmpsdeck.io.midi.GmVoiceSuggestions.seedBank(), cfg,
                    () -> System.currentTimeMillis() - startMs, progress);
            return new MatchResult(candidates, null,
                    validated.get(0).slice(), targets.get(0).midiPitch(),
                    targets.get(0).modulation());
        }, executor);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
