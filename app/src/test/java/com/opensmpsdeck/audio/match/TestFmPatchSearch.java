package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.GmVoiceSuggestions;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestFmPatchSearch {

    /** Ground truth: render a known voice, use it as the target, search must approach it. */
    @Test
    void recoversKnownVoiceSpectrally() {
        FmVoice truth = GmVoiceSuggestions.fmBass();
        var renderer = new CandidateRenderer();
        float[] audio = renderer.render(truth.getData(), 48, 0.5, 0.25);
        SpectralTarget target = SpectralTarget.extract(audio, 44100, midiHz(48));

        var cfg = new FmPatchSearch.Config(16, 12, Long.MAX_VALUE, 42L, 3);
        var results = FmPatchSearch.search(
                List.of(new FmPatchSearch.Target(target, 48, 0.5)),
                GmVoiceSuggestions.seedBank(), cfg, () -> 0L, g -> {});

        assertFalse(results.isEmpty());
        // fmBass itself is in the seed bank, so the best score must be near zero
        assertTrue(results.get(0).score() < 0.01,
                "best score " + results.get(0).score());
    }

    @Test
    void searchIsDeterministicForSameSeed() {
        FmVoice truth = GmVoiceSuggestions.bell();
        var renderer = new CandidateRenderer();
        SpectralTarget target = SpectralTarget.extract(
                renderer.render(truth.getData(), 60, 0.4, 0.2), 44100, midiHz(60));
        var cfg = new FmPatchSearch.Config(8, 4, Long.MAX_VALUE, 7L, 2);
        var targets = List.of(new FmPatchSearch.Target(target, 60, 0.4));

        var a = FmPatchSearch.search(targets, GmVoiceSuggestions.seedBank(), cfg, () -> 0L, g -> {});
        var b = FmPatchSearch.search(targets, GmVoiceSuggestions.seedBank(), cfg, () -> 0L, g -> {});
        assertArrayEquals(a.get(0).voice().getData(), b.get(0).voice().getData());
    }

    @Test
    void budgetExpiryReturnsBestSoFar() {
        var renderer = new CandidateRenderer();
        SpectralTarget target = SpectralTarget.extract(
                renderer.render(GmVoiceSuggestions.pad().getData(), 60, 0.4, 0.2),
                44100, midiHz(60));
        // elapsed supplier that is instantly over budget → only generation 0 runs
        var cfg = new FmPatchSearch.Config(8, 100, 1L, 1L, 2);
        var results = FmPatchSearch.search(
                List.of(new FmPatchSearch.Target(target, 60, 0.4)),
                GmVoiceSuggestions.seedBank(), cfg, () -> 99999L, g -> {});
        assertFalse(results.isEmpty());
    }

    private static double midiHz(int pitch) {
        return 440.0 * Math.pow(2, (pitch - 69) / 12.0);
    }
}
