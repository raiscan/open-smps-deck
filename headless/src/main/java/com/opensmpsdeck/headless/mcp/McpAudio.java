package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReferenceAudio;
import com.opensmpsdeck.headless.ReconstructionStages;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.util.Map;

import static com.opensmpsdeck.headless.S3kRenderer.*;
import static com.opensmpsdeck.headless.mcp.McpArguments.string;
import static com.opensmpsdeck.headless.mcp.McpJson.object;

final class McpAudio {
    static final String[] LANES = {"FM1", "FM2", "FM3", "FM4", "FM5", "DAC", "PSG1", "PSG2", "PSG3"};
    private static final int ALL_LANES = (1 << LANES.length) - 1;
    private static final int MAX_AB_PRESENTATIONS = 60 * PRESENTATIONS_PER_SECOND;

    private McpAudio() {}

    // Ranges are rounded to the nearest 60 Hz boundary, with exclusive end.
    private record Range(int start, int end) {
        int count() { return end - start; }
    }

    static JsonObject clip(JsonObject args) throws IOException {
        var range = range(args);
        var project = McpPaths.project(string(args, "project"));
        var output = McpPaths.output(project.getParent(), string(args, "output"));
        int muted = laneBits(args, "mute");
        if (args.has("solo")) muted |= ALL_LANES ^ laneBits(args, "solo");
        int psg = muted >> 6;
        // PSG3 owns both tone 3 and the noise output.
        if ((psg & 0b100) != 0) psg |= 0b1000;
        var mask = new ChannelMask(muted & 63, psg);
        var compiled = ProjectCompiler.compileWithAssets(ProjectFiles.load(project).project());
        WaveFile.write(output, range.count(), sink -> {
            int[] frame = {0};
            S3kRenderer.render(compiled, range.end, mask, samples -> {
                if (frame[0]++ >= range.start) sink.accept(samples);
            });
        });
        return object(Map.of("output", output.toString(), "startFrame", range.start, "endFrame", range.end,
                "presentations", range.count(), "sampleRate", SAMPLE_RATE));
    }

    static JsonObject compare(JsonObject args) throws IOException {
        var range = range(args);
        if (range.count() > MAX_AB_PRESENTATIONS)
            throw new IllegalArgumentException("A/B comparison range may be at most 60 seconds");
        var render = McpPaths.input(string(args, "render"));
        var reference = McpPaths.input(string(args, "reference"));
        var work = McpPaths.directory(string(args, "workDir"));
        var output = McpPaths.output(work, string(args, "output"));
        if (render.equals(work.resolve("render.wav")) && ReconstructionStages.usesSteadyReference(
                McpPaths.managed(work, "report.json"), reference)) {
            reference = McpPaths.input(McpPaths.managed(work, ReconstructionStages.STEADY_REFERENCE).toString());
        }
        int frames = range.count() * STEREO_FRAMES_PER_PRESENTATION;
        long start = (long) range.start * STEREO_FRAMES_PER_PRESENTATION;
        double offset = args.has("referenceOffset") ? args.get("referenceOffset").getAsDouble() : 0;
        if (Math.abs(offset) > 600) throw new IllegalArgumentException("referenceOffset must be in [-600, 600]");
        long referenceStart = start - Math.round(offset * SAMPLE_RATE);
        if (referenceStart < 0) throw new IllegalArgumentException("Range starts before the aligned reference");
        float[] a = ReferenceAudio.stereoRange(render, SAMPLE_RATE, start, frames);
        float[] b = ReferenceAudio.stereoRange(reference, SAMPLE_RATE, referenceStart, frames);
        var levelA = measure(a);
        var levelB = measure(b);
        double gainA = 1, gainB = 1;
        boolean matched = levelA.rms > 0 && levelB.rms > 0;
        if (matched) {
            double target = Math.min(levelA.rms, levelB.rms);
            gainA = target / levelA.rms;
            gainB = target / levelB.rms;
        }
        double headroom = Math.max(1, Math.max(levelA.peak * gainA, levelB.peak * gainB));
        double scaleA = gainA / headroom, scaleB = gainB / headroom;
        WaveFile.write(output, range.count() * 2, sink -> {
            emit(a, scaleA, sink);
            emit(b, scaleB, sink);
        });
        return object(Map.of("output", output.toString(), "order", new String[]{"render", "reference"},
                "splitSeconds", frames / (double) SAMPLE_RATE, "renderGain", scaleA, "referenceGain", scaleB,
                "levelMatched", matched));
    }

    private record Level(double rms, double peak) {}

    private static Level measure(float[] samples) {
        double squares = 0, peak = 0;
        for (double sample : samples) {
            squares += sample * sample;
            peak = Math.max(peak, Math.abs(sample));
        }
        return new Level(Math.sqrt(squares / samples.length), peak);
    }

    private static void emit(float[] samples, double gain, PcmSink sink) throws IOException {
        var pcm = new short[STEREO_FRAMES_PER_PRESENTATION * 2];
        for (int offset = 0; offset < samples.length; offset += pcm.length) {
            for (int i = 0; i < pcm.length; i++)
                pcm[i] = (short) Math.round(samples[offset + i] * gain * Short.MAX_VALUE);
            sink.accept(pcm);
        }
    }

    private static Range range(JsonObject args) {
        double start = args.get("start").getAsDouble(), end = args.get("end").getAsDouble();
        if (start < 0 || end <= start || end > MAX_PRESENTATIONS / (double) PRESENTATIONS_PER_SECOND)
            throw new IllegalArgumentException("Expected 0 <= start < end <= 600 seconds");
        var range = new Range((int) Math.round(start * PRESENTATIONS_PER_SECOND),
                (int) Math.round(end * PRESENTATIONS_PER_SECOND));
        if (range.count() == 0) throw new IllegalArgumentException("Range is empty at 60 Hz");
        return range;
    }

    private static int laneBits(JsonObject args, String key) {
        if (!args.has(key)) return 0;
        int bits = 0;
        var list = java.util.List.of(LANES);
        for (var lane : args.getAsJsonArray(key)) bits |= 1 << list.indexOf(lane.getAsString());
        return bits;
    }
}
