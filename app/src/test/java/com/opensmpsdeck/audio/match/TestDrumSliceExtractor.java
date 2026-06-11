package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.model.DacSample;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestDrumSliceExtractor {

    /** Synthetic kick: 60 Hz sine burst decaying over 150 ms at the given offset. */
    private static float[] clickTrain(double... onsetsSec) {
        float[] audio = new float[44100 * 2];
        for (double onset : onsetsSec) {
            int start = (int) (onset * 44100);
            for (int i = 0; i < 6615 && start + i < audio.length; i++) { // 150 ms
                audio[start + i] += (float) (Math.sin(2 * Math.PI * 60 * i / 44100.0)
                        * 0.8 * Math.exp(-i / 2000.0));
            }
        }
        return audio;
    }

    @Test
    void extractsSliceWithCorrectBounds() {
        DacSample s = DrumSliceExtractor.extract(clickTrain(0.5), 44100, 0.5, 1.5,
                "Kick", 0x0C);
        assertEquals("Kick", s.getName());
        assertEquals(0x0C, s.getRate());
        assertTrue(s.getData().length > 0);
        // slice should be ≲ 200 ms of DAC-rate audio, not the whole 2 s
        assertTrue(s.getData().length < DrumSliceExtractor.dacPlaybackHz(0x0C) / 2);
    }

    @Test
    void sliceEndsAtNextOnset() {
        DacSample s = DrumSliceExtractor.extract(clickTrain(0.5, 0.6), 44100, 0.5, 0.6,
                "Kick", 0x0C);
        // hard-capped at 100 ms by maxEndSec
        assertTrue(s.getData().length <= DrumSliceExtractor.dacPlaybackHz(0x0C) / 9);
    }

    @Test
    void outputIsNormalizedUnsigned8Bit() {
        DacSample s = DrumSliceExtractor.extract(clickTrain(0.5), 44100, 0.5, 1.5,
                "Kick", 0x0C);
        int min = 255, max = 0;
        for (byte b : s.getData()) {
            min = Math.min(min, b & 0xFF);
            max = Math.max(max, b & 0xFF);
        }
        assertTrue(max > 200, "should be normalized near full scale, max=" + max);
        assertTrue(min < 55);
    }
}
