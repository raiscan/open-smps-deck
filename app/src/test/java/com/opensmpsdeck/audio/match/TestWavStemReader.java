package com.opensmpsdeck.audio.match;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class TestWavStemReader {

    @TempDir
    File tempDir;

    /** Writes a WAV of the given format containing a 440 Hz sine. */
    private File writeSineWav(float sampleRate, int channels) throws Exception {
        int frames = (int) sampleRate; // 1 second
        byte[] pcm = new byte[frames * channels * 2];
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 12000);
            for (int c = 0; c < channels; c++) {
                int off = (i * channels + c) * 2;
                pcm[off] = (byte) (v & 0xFF);
                pcm[off + 1] = (byte) (v >> 8);
            }
        }
        AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
        File f = new File(tempDir, "sine.wav");
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), fmt, frames),
                AudioFileFormat.Type.WAVE, f);
        return f;
    }

    @Test
    void readsStereo44kToMonoFloat() throws Exception {
        float[] mono = WavStemReader.readMono44k(writeSineWav(44100, 2));
        assertEquals(44100, mono.length, 100);
        double peak = 0;
        for (float v : mono) peak = Math.max(peak, Math.abs(v));
        assertEquals(12000.0 / 32768.0, peak, 0.02);
    }

    @Test
    void resamples48kTo44k() throws Exception {
        float[] mono = WavStemReader.readMono44k(writeSineWav(48000, 1));
        assertEquals(44100, mono.length, 200);
        // frequency must be preserved: count zero crossings ≈ 880/sec
        int crossings = 0;
        for (int i = 1; i < mono.length; i++) {
            if (mono[i - 1] < 0 != mono[i] < 0) crossings++;
        }
        assertEquals(880, crossings, 20);
    }
}
