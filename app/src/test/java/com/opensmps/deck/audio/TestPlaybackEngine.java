package com.opensmps.deck.audio;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestPlaybackEngine {

    @Test
    void testLoadAndRenderProducesAudio() {
        Song song = createTestSong();
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        short[] buffer = new short[2048];
        int samples = engine.renderBuffer(buffer);
        assertTrue(samples > 0, "Should render audio samples");

        // Check that at least some samples are non-zero
        boolean hasAudio = false;
        for (short s : buffer) {
            if (s != 0) { hasAudio = true; break; }
        }
        assertTrue(hasAudio, "Rendered audio should contain non-zero samples");
    }

    @Test
    void testReloadDoesNotThrow() {
        Song song = createTestSong();
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);
        engine.renderBuffer(new short[1024]);
        engine.reload(song); // should not throw
        engine.renderBuffer(new short[1024]);
    }

    @Test
    void testMuteDoesNotThrow() {
        PlaybackEngine engine = new PlaybackEngine();
        engine.setFmMute(0, true);
        engine.setPsgMute(0, true);
        engine.setFmMute(0, false);
        engine.setPsgMute(0, false);
    }

    private Song createTestSong() {
        Song song = new Song();
        song.setTempo(0x80);

        // Simple sine voice: algo 0, op4 as carrier with MUL=1, AR=31
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x00;   // algo 0, fb 0
        // Op1,2,3 TL = 127 (silent modulators)
        voiceData[2] = 0x7F;   // Op1 TL
        voiceData[7] = 0x7F;   // Op3 TL
        voiceData[12] = 0x7F;  // Op2 TL
        // Op4 (carrier): MUL=1, TL=0, AR=31, RR=15
        voiceData[16] = 0x01;  // Op4 DT_MUL
        voiceData[17] = 0x00;  // Op4 TL (loud)
        voiceData[18] = 0x1F;  // Op4 RS_AR (AR=31)
        voiceData[19] = 0x00;  // Op4 AM_D1R
        voiceData[20] = 0x00;  // Op4 D2R
        voiceData[21] = 0x0F;  // Op4 D1L_RR (RR=15)
        song.getVoiceBank().add(new FmVoice("Sine", voiceData));

        // Set voice, play C4, duration 48
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xE1, 0x00, (byte)0xA1, 0x30 });

        return song;
    }
}
