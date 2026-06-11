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

    public record MatchResult(List<FmPatchSearch.ScoredVoice> candidates,
                              String failureReason) {}

    private static final int TOP_WINDOWS = 3;
    private static final int SAMPLE_RATE = 44100;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "voice-match");
                t.setDaemon(true);
                return t;
            });

    public CompletableFuture<MatchResult> match(float[] stemAudio, List<NoteEvent> notes,
                                                TickTimeMapper map, FmPatchSearch.Config cfg,
                                                IntConsumer progress) {
        return CompletableFuture.supplyAsync(() -> {
            var windows = MonophonicWindowFinder.find(notes, map, TOP_WINDOWS);
            if (windows.isEmpty()) {
                return new MatchResult(List.of(),
                        "No isolated note of at least 250 ms found in the MIDI — "
                        + "pick a voice from a bank instead.");
            }
            List<FmPatchSearch.Target> targets = new ArrayList<>();
            for (var w : windows) {
                int start = (int) (w.startSec() * SAMPLE_RATE);
                int len = (int) (w.lengthSec() * SAMPLE_RATE);
                if (start + len > stemAudio.length) continue;
                float[] slice = new float[len];
                System.arraycopy(stemAudio, start, slice, 0, len);
                double hz = 440.0 * Math.pow(2, (w.midiPitch() - 69) / 12.0);
                targets.add(new FmPatchSearch.Target(
                        SpectralTarget.extract(slice, SAMPLE_RATE, hz),
                        w.midiPitch(), Math.min(w.lengthSec(), 1.0)));
            }
            if (targets.isEmpty()) {
                return new MatchResult(List.of(), "Isolated windows fall outside the WAV.");
            }
            long startMs = System.currentTimeMillis();
            var candidates = FmPatchSearch.search(targets,
                    com.opensmpsdeck.io.midi.GmVoiceSuggestions.seedBank(), cfg,
                    () -> System.currentTimeMillis() - startMs, progress);
            return new MatchResult(candidates, null);
        }, executor);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
