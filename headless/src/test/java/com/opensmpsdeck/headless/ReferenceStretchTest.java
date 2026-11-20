package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.opensmpsdeck.headless.S3kRenderer.SAMPLE_RATE;
import static com.opensmpsdeck.headless.S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceStretchTest {
    @TempDir Path temp;

    @Test void stretchesStereoWithoutChangingPitch() throws Exception {
        Path input = temp.resolve("tone.wav"), output = temp.resolve("slow.wav");
        WaveFile.write(input, 60, sink -> {
            short[] frame = new short[2 * STEREO_FRAMES_PER_PRESENTATION];
            for (int start = 0; start < SAMPLE_RATE; start += STEREO_FRAMES_PER_PRESENTATION) {
                for (int i = 0; i < frame.length / 2; i++) {
                    frame[2 * i] = (short) (12_000 * Math.sin(2 * Math.PI * 440 * (start + i) / SAMPLE_RATE));
                    frame[2 * i + 1] = (short) (frame[2 * i] / 2);
                }
                sink.accept(frame);
            }
        });
        ReferenceStretch.write(input, output, 72, time -> time / 1.2);
        assertEquals(1_200_000, ReferenceAudio.durationMicros(output));
        var pcm = ReferenceAudio.stereoRange(output, SAMPLE_RATE, SAMPLE_RATE / 4, SAMPLE_RATE / 2);
        int crossings = 0;
        for (int i = 2; i < pcm.length; i += 2) {
            if (pcm[i - 2] <= 0 && pcm[i] > 0) crossings++;
            assertEquals(pcm[i] / 2, pcm[i + 1], .0001);
        }
        assertEquals(220, crossings, 2);
    }
}
