package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.GmVoiceSuggestions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestCandidateRenderer {

    @Test
    void rendersAudibleNote() {
        var r = new CandidateRenderer();
        float[] audio = r.render(GmVoiceSuggestions.squareLead().getData(), 60, 0.5, 0.25);
        assertEquals((int) (0.75 * 44100), audio.length, 100);
        double peak = 0;
        for (float v : audio) peak = Math.max(peak, Math.abs(v));
        assertTrue(peak > 0.01, "rendered note must be audible, peak was " + peak);
    }

    @Test
    void energyDecaysAfterKeyOff() {
        var r = new CandidateRenderer();
        float[] audio = r.render(GmVoiceSuggestions.squareLead().getData(), 60, 0.5, 0.5);
        double sustain = rms(audio, (int) (0.3 * 44100), (int) (0.5 * 44100));
        double tail = rms(audio, (int) (0.8 * 44100), audio.length);
        assertTrue(tail < sustain * 0.7, "tail " + tail + " vs sustain " + sustain);
    }

    @Test
    void renderIsRepeatable() {
        var r = new CandidateRenderer();
        byte[] v = GmVoiceSuggestions.fmBass().getData();
        float[] a = r.render(v, 48, 0.3, 0.1);
        float[] b = r.render(v, 48, 0.3, 0.1);
        assertArrayEquals(a, b, 1e-9f);
    }

    private static double rms(float[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += a[i] * a[i];
        return Math.sqrt(s / (to - from));
    }
}
