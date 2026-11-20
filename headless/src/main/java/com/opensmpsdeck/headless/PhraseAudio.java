package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;

import static com.opensmpsdeck.headless.S3kRenderer.*;

/** PCM helpers shared by render comparisons and phrase auditions. */
public final class PhraseAudio {
    public record Match(double renderGain, double referenceGain, boolean matched) {}

    private PhraseAudio() {}

    static double rms(float[] samples, int from, int to) {
        double squares = 0;
        for (int i = from; i < to; i++) {
            double sample = samples[i];
            squares += sample * sample;
        }
        return Math.sqrt(squares / (to - from));
    }

    public static Match write(Path output, int presentations, float[] render, float[] reference) throws IOException {
        double a = rms(render, 0, render.length);
        double b = reference == null ? 0 : rms(reference, 0, reference.length);
        boolean matched = a > 0 && b > 0;
        double gainA = matched ? Math.min(a, b) / a : 1;
        double gainB = matched ? Math.min(a, b) / b : 1;
        double headroom = Math.max(1, Math.max(peak(render) * gainA, peak(reference) * gainB));
        var match = new Match(gainA / headroom, gainB / headroom, matched);
        WaveFile.write(output, reference == null ? presentations : presentations * 2, sink -> {
            emit(render, match.renderGain, reference == null ? 32768 : 32767, sink);
            if (reference != null) emit(reference, match.referenceGain, 32767, sink);
        });
        return match;
    }

    private static double peak(float[] samples) {
        double result = 0;
        if (samples != null) for (float sample : samples) result = Math.max(result, Math.abs(sample));
        return result;
    }

    private static void emit(float[] samples, double gain, int fullScale, PcmSink sink) throws IOException {
        var pcm = new short[STEREO_FRAMES_PER_PRESENTATION * 2];
        for (int offset = 0; offset < samples.length; offset += pcm.length) {
            for (int i = 0; i < pcm.length; i++)
                pcm[i] = (short) Math.clamp(Math.round(samples[offset + i] * gain * fullScale), -32768, 32767);
            sink.accept(pcm);
        }
    }
}
