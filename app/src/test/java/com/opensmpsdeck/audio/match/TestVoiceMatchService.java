package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.GmVoiceSuggestions;
import com.opensmpsdeck.io.midi.MidiStem;
import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestVoiceMatchService {

    @Test
    void matchesAgainstSyntheticStem() throws Exception {
        // synthesize "stem" audio with a known voice playing an isolated note
        var renderer = new CandidateRenderer();
        float[] stemAudio = renderer.render(
                GmVoiceSuggestions.fmBass().getData(), 48, 0.6, 0.2);
        var notes = List.of(new NoteEvent(0, 576, 48, 100)); // 0.6 s at 120 BPM/480ppq
        var map = new TickTimeMapper(480, List.of(new MidiStem.TempoEvent(0, 500000)));

        var service = new VoiceMatchService();
        AtomicInteger progress = new AtomicInteger();
        var future = service.match(stemAudio, notes, map,
                new FmPatchSearch.Config(8, 4, 60_000, 5L, 3), progress::set);
        var result = future.get(3, TimeUnit.MINUTES);

        assertFalse(result.candidates().isEmpty());
        assertTrue(progress.get() >= 0);
        service.shutdown();
    }

    @Test
    void reportsInsufficientIsolation() throws Exception {
        // two fully overlapping notes → no isolated window
        var notes = List.of(
                new NoteEvent(0, 480, 60, 100),
                new NoteEvent(0, 480, 64, 100));
        var map = new TickTimeMapper(480, List.of(new MidiStem.TempoEvent(0, 500000)));
        var service = new VoiceMatchService();
        var result = service.match(new float[44100], notes, map,
                new FmPatchSearch.Config(8, 2, 60_000, 5L, 3), g -> {})
                .get(1, TimeUnit.MINUTES);
        assertTrue(result.candidates().isEmpty());
        assertNotNull(result.failureReason());
        service.shutdown();
    }
}
