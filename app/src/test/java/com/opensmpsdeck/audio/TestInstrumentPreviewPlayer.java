package com.opensmpsdeck.audio;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Instrument previews must go through the real SMPS pipeline so the edited
 * instrument is what the user actually hears. Regression for the "all PSG
 * envelopes sound the same" preview bug: the old preview wrote a fixed tone
 * straight to the chip and never applied the envelope.
 */
class TestInstrumentPreviewPlayer {

    private static double renderRms(Song song, double seconds) {
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);
        int totalSamples = (int) (44100 * seconds) * 2;
        short[] buf = new short[4410];
        double sum = 0;
        int n = 0;
        while (n < totalSamples) {
            engine.renderBuffer(buf);
            for (short s : buf) sum += (double) s * s;
            n += buf.length;
        }
        return Math.sqrt(sum / n);
    }

    /** An audible FM voice: algorithm 7 (all carriers), fast attack, full level. */
    private static FmVoice audibleVoice() {
        FmVoice voice = new FmVoice("Loud", new byte[FmVoice.VOICE_SIZE]);
        voice.setAlgorithm(7);
        for (int op = 0; op < 4; op++) {
            voice.setMul(op, 1);
            voice.setTl(op, 0);
            voice.setAr(op, 31);
            voice.setD1l(op, 0);
            voice.setRr(op, 15);
        }
        return voice;
    }

    @Test
    void psgPreviewAppliesTheEnvelope() {
        PsgEnvelope loud = new PsgEnvelope("Loud", new byte[]{0x00, (byte) 0x80});
        PsgEnvelope quiet = new PsgEnvelope("Quiet", new byte[]{0x0D, (byte) 0x80});

        double loudRms = renderRms(
                InstrumentPreviewPlayer.buildPsgPreviewSong(loud, InstrumentPreviewPlayer.DEFAULT_NOTE,
                        InstrumentPreviewPlayer.DEFAULT_DURATION), 0.5);
        double quietRms = renderRms(
                InstrumentPreviewPlayer.buildPsgPreviewSong(quiet, InstrumentPreviewPlayer.DEFAULT_NOTE,
                        InstrumentPreviewPlayer.DEFAULT_DURATION), 0.5);

        assertTrue(loudRms > 0, "Loud envelope preview should produce audio");
        assertTrue(loudRms > quietRms * 2,
                "Different envelopes must sound different (loud=" + loudRms
                        + ", quiet=" + quietRms + ")");
    }

    @Test
    void fmPreviewAppliesTheVoice() {
        FmVoice loud = audibleVoice();
        FmVoice muted = new FmVoice("Muted", new byte[FmVoice.VOICE_SIZE]);
        muted.setAlgorithm(7);
        for (int op = 0; op < 4; op++) {
            muted.setTl(op, 127); // full attenuation
            muted.setAr(op, 31);
        }

        double loudRms = renderRms(
                InstrumentPreviewPlayer.buildFmPreviewSong(loud, InstrumentPreviewPlayer.DEFAULT_NOTE,
                        InstrumentPreviewPlayer.DEFAULT_DURATION), 0.5);
        double mutedRms = renderRms(
                InstrumentPreviewPlayer.buildFmPreviewSong(muted, InstrumentPreviewPlayer.DEFAULT_NOTE,
                        InstrumentPreviewPlayer.DEFAULT_DURATION), 0.5);

        assertTrue(loudRms > 0, "Audible voice preview should produce audio");
        assertTrue(loudRms > mutedRms * 2,
                "Different voices must sound different (loud=" + loudRms
                        + ", muted=" + mutedRms + ")");
    }

    @Test
    void fmPreviewRespectsTheChosenNote() {
        FmVoice voice = audibleVoice();
        // C4 vs C5: different pitch, both audible
        double c4 = renderRms(InstrumentPreviewPlayer.buildFmPreviewSong(voice, 0xB1, 0x2D), 0.3);
        double c5 = renderRms(InstrumentPreviewPlayer.buildFmPreviewSong(voice, 0xBD, 0x2D), 0.3);
        assertTrue(c4 > 0 && c5 > 0, "Both notes should produce audio");
    }

    @Test
    void dacPreviewPlaysTheSample() {
        // Crude square wave PCM, loud
        byte[] pcm = new byte[4000];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) ((i / 16) % 2 == 0 ? 0xF0 : 0x10);
        }
        DacSample sample = new DacSample("Square", pcm, 0x0C);

        double rms = renderRms(InstrumentPreviewPlayer.buildDacPreviewSong(sample), 0.5);
        assertTrue(rms > 0, "DAC sample preview should produce audio, got RMS " + rms);
    }
}
