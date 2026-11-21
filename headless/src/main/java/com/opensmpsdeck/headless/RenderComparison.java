package com.opensmpsdeck.headless;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static com.opensmpsdeck.headless.S3kRenderer.*;

public final class RenderComparison {
    public record Result(String output, List<String> order, double splitSeconds, double renderGain,
                         double referenceGain, boolean levelMatched) {}

    private RenderComparison() {}

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length < 7 || args.length % 2 == 0)
            throw new IllegalArgumentException("Expected render-compare WORK_DIR --start S --end S [options] OUT.wav|OUT.mp3");
        Path work = Path.of(args[1]);
        Path render = work.resolve("render.wav"), reference = null;
        Double start = null, end = null;
        var seen = new HashSet<String>();
        for (int i = 2; i < args.length - 1; i += 2) {
            if (!seen.add(args[i])) throw new IllegalArgumentException("Repeated option " + args[i]);
            switch (args[i]) {
                case "--start" -> start = Double.parseDouble(args[i + 1]);
                case "--end" -> end = Double.parseDouble(args[i + 1]);
                case "--render" -> render = Path.of(args[i + 1]);
                case "--reference" -> reference = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown render-compare option " + args[i]);
            }
        }
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var timeline = ReconstructionTimeMap.of(score);
        if (start == null || end == null || !(start >= 0 && end > start && end <= timeline.sourceDuration()
                && end - start <= 60))
            throw new IllegalArgumentException("Expected a nonnegative source span of at most 60 seconds within the run");
        var range = WaveFile.Range.seconds(timeline.project(start), timeline.project(end));
        validateRange(range);
        Path steady = work.resolve(ReconstructionStages.STEADY_REFERENCE);
        boolean customReference = reference != null;
        if (!customReference && timeline.warped()) {
            reference = steady;
        } else if (!customReference) {
            reference = Path.of(score.inputs().wav());
        }
        boolean stretch = customReference && timeline.warped()
                && !(Files.isRegularFile(steady) && Files.isSameFile(reference, steady));
        if (stretch && ReconstructionStages.usesSteadyReference(work.resolve("report.json"), reference)) {
            reference = steady;
            stretch = false;
        }

        Path output = Path.of(args[args.length - 1]);
        String filename = output.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean mp3 = filename.endsWith(".mp3");
        if (!mp3 && !filename.endsWith(".wav"))
            throw new IllegalArgumentException("Comparison output must end in .wav or .mp3");
        if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.nio.file.FileAlreadyExistsException(output.toString());
        long referenceStart = (long) range.start() * STEREO_FRAMES_PER_PRESENTATION;
        if (!stretch && !mp3) {
            out.println(new Gson().toJson(write(render, reference, output, range, referenceStart)));
            return;
        }
        Path scratch = Files.createTempDirectory(output.toAbsolutePath().getParent(), ".render-compare-");
        Path warped = scratch.resolve("reference.wav"), wav = scratch.resolve("pair.wav"), encoded = scratch.resolve("pair.mp3");
        try {
            if (stretch) {
                if (ReferenceAudio.durationMicros(reference) / 1e6 < end)
                    throw new IllegalArgumentException("Source span exceeds the reference duration");
                ReferenceStretch.write(reference, warped, range.count(), time ->
                        timeline.source(range.start() / (double) PRESENTATIONS_PER_SECOND + time));
                reference = warped;
                referenceStart = 0;
            }
            var result = write(render, reference, mp3 ? wav : output, range, referenceStart);
            if (mp3) {
                encodeMp3(wav, encoded);
                Files.move(encoded, output);
                result = new Result(output.toString(), result.order(), result.splitSeconds(), result.renderGain(),
                        result.referenceGain(), result.levelMatched());
            }
            out.println(new Gson().toJson(result));
        } finally {
            Files.deleteIfExists(encoded);
            Files.deleteIfExists(wav);
            Files.deleteIfExists(warped);
            Files.delete(scratch);
        }
    }

    public static Result write(Path render, Path reference, Path output, WaveFile.Range range, long referenceStart)
            throws IOException {
        validateRange(range);
        if (referenceStart < 0) throw new IllegalArgumentException("Range starts before the aligned reference");
        int frames = range.count() * STEREO_FRAMES_PER_PRESENTATION;
        long start = (long) range.start() * STEREO_FRAMES_PER_PRESENTATION;
        float[] a = ReferenceAudio.stereoRange(render, SAMPLE_RATE, start, frames);
        float[] b = ReferenceAudio.stereoRange(reference, SAMPLE_RATE, referenceStart, frames);
        var match = PhraseAudio.write(output, range.count(), a, b);
        return new Result(output.toString(), List.of("render", "reference"), frames / (double) SAMPLE_RATE,
                match.renderGain(), match.referenceGain(), match.matched());
    }

    private static void validateRange(WaveFile.Range range) {
        if (range.count() > 60 * PRESENTATIONS_PER_SECOND)
            throw new IllegalArgumentException("A/B comparison range may be at most 60 project seconds");
    }

    private static void encodeMp3(Path wav, Path output) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-n", "-i", wav.toString(),
                    "-codec:a", "libmp3lame", "-q:a", "2", output.toString()).redirectErrorStream(true).start();
        } catch (IOException failure) {
            throw new IOException("MP3 output requires ffmpeg with libmp3lame on PATH", failure);
        }
        try {
            String diagnostic = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new IOException("ffmpeg MP3 encoding failed: " + diagnostic.strip());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("MP3 encoding cancelled");
        } finally {
            process.destroyForcibly();
        }
    }
}
