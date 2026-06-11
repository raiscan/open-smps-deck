package com.opensmpsdeck.audio.match;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

/** Reads a WAV file as mono float samples at 44.1 kHz. */
public final class WavStemReader {

    public static final int TARGET_RATE = 44100;

    private WavStemReader() {}

    public static float[] readMono44k(File file) throws IOException {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file)) {
            AudioFormat src = raw.getFormat();
            AudioFormat pcm16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(), 16, src.getChannels(),
                    src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm16, raw)) {
                byte[] bytes = in.readAllBytes();
                int channels = pcm16.getChannels();
                int frames = bytes.length / (channels * 2);
                float[] mono = new float[frames];
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) {
                        int off = (i * channels + c) * 2;
                        sum += (short) ((bytes[off] & 0xFF) | (bytes[off + 1] << 8));
                    }
                    mono[i] = (float) sum / channels / 32768f;
                }
                return resample(mono, src.getSampleRate(), TARGET_RATE);
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported WAV encoding: " + file.getName(), e);
        }
    }

    /** Linear-interpolation resampler — adequate for analysis targets. */
    public static float[] resample(float[] in, float fromRate, float toRate) {
        if (Math.abs(fromRate - toRate) < 0.5f) return in;
        int outLen = (int) ((long) in.length * toRate / fromRate);
        float[] out = new float[outLen];
        double step = fromRate / toRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = pos - i0;
            out[i] = (float) (in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }
}
