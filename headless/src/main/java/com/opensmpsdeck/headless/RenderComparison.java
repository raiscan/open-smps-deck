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
                         double referenceGain, boolean levelMatched, double sourceStart, double sourceEnd,
                         double projectStart, double projectEnd, int startTick, int endTick) {}

    private RenderComparison() {}

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length < 5)
            throw new IllegalArgumentException("Expected render-compare WORK_DIR --start S --end S [options] OUT.wav|OUT.mp3");
        Path work = Path.of(args[1]);
        Path render = work.resolve("render.wav"), reference = null;
        Double start = null, end = null;
        String time = "source", section = null;
        var seen = new HashSet<String>();
        for (int i = 2; i < args.length - 1;) {
            String key = args[i++];
            if (!seen.add(key)) throw new IllegalArgumentException("Repeated option " + key);
            if (key.equals("--force")) continue;
            if (i >= args.length - 1) throw new IllegalArgumentException("Missing value for " + key);
            String value = args[i++];
            switch (key) {
                case "--start" -> start = Double.parseDouble(value);
                case "--end" -> end = Double.parseDouble(value);
                case "--time" -> time = value;
                case "--section" -> section = value;
                case "--render" -> render = Path.of(value);
                case "--reference" -> reference = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown render-compare option " + key);
            }
        }
        var bounds = ReconstructionSections.bounds(work, section, start, end, time);
        out.println(new Gson().toJson(run(work, render, reference, Path.of(args[args.length - 1]),
                bounds[0], bounds[1], time, null, seen.contains("--force"))));
    }

    public static Result run(Path work, Path render, Path reference, Path output,
                             double start, double end, String time, Double offset) throws IOException {
        return run(work, render, reference, output, start, end, time, offset, false);
    }

    private static Result run(Path work, Path render, Path reference, Path output,
                              double start, double end, String time, Double offset, boolean force) throws IOException {
        Path scoreFile = work.resolve("score.json");
        var score = Files.isRegularFile(scoreFile) ? ReconstructionStages.readScore(scoreFile) : null;
        var timeline = score == null ? new ReconstructionTimeMap(null, null, 600) : ReconstructionTimeMap.of(score);
        var base = ReconstructionTimeMap.TimeBase.valueOf(time);
        double duration = base == ReconstructionTimeMap.TimeBase.source ? timeline.sourceDuration() : timeline.frames() / 60.0;
        if (!(start >= 0 && end > start && end <= duration && end - start <= 60))
            throw new IllegalArgumentException("Expected a nonnegative span of at most 60 seconds within the run");
        if (offset != null && !(Math.abs(offset) <= 600))
            throw new IllegalArgumentException("Reference offset must be in [-600, 600] project seconds");
        var range = base == ReconstructionTimeMap.TimeBase.source
                ? WaveFile.Range.seconds(timeline.project(start), timeline.project(end))
                : WaveFile.Range.seconds(start, end);
        validateRange(range);
        Path steady = work.resolve(ReconstructionStages.STEADY_REFERENCE);
        boolean customReference = reference != null;
        if (!customReference && timeline.warped()) {
            reference = steady;
        } else if (!customReference) {
            if (score == null) throw new IllegalArgumentException("Expected a reference WAV or reconstruction score");
            reference = Path.of(score.inputs().wav());
        }
        boolean stretch = customReference && timeline.warped() && offset == null
                && !(Files.isRegularFile(steady) && Files.isSameFile(reference, steady));
        if (customReference && timeline.warped() && ReconstructionStages.usesSteadyReference(work.resolve("report.json"), reference)) {
            reference = steady;
            stretch = false;
        }

        String filename = output.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean mp3 = filename.endsWith(".mp3");
        if (!mp3 && !filename.endsWith(".wav"))
            throw new IllegalArgumentException("Comparison output must end in .wav or .mp3");
        try (var target = new ExportTarget(output, force)) {
            if (Files.exists(output) && (Files.isSameFile(output, render) || Files.isSameFile(output, reference)))
                throw new IllegalArgumentException("Comparison output must differ from both input WAVs");
            long referenceStart = (long) range.start() * STEREO_FRAMES_PER_PRESENTATION
                    - (offset == null ? 0 : Math.round(offset * SAMPLE_RATE));
            double sourceEnd = base == ReconstructionTimeMap.TimeBase.source ? end : timeline.source(end);
            var result = export(render, reference, target.path(), range, referenceStart, timeline, sourceEnd, stretch, mp3);
            target.publish();
            return new Result(output.toString(), result.order(), result.splitSeconds(), result.renderGain(),
                    result.referenceGain(), result.levelMatched(), result.sourceStart(), result.sourceEnd(),
                    result.projectStart(), result.projectEnd(), result.startTick(), result.endTick());
        }
    }

    private static Result export(Path render, Path reference, Path output, WaveFile.Range range, long referenceStart,
                                 ReconstructionTimeMap timeline, double sourceEnd, boolean stretch, boolean mp3)
            throws IOException {
        if (!stretch && !mp3) return write(render, reference, output, range, referenceStart, timeline);
        Path scratch = Files.createTempDirectory(output.toAbsolutePath().getParent(), ".render-compare-");
        Path warped = scratch.resolve("reference.wav"), wav = scratch.resolve("pair.wav"), encoded = scratch.resolve("pair.mp3");
        try {
            if (stretch) {
                if (ReferenceAudio.durationMicros(reference) / 1e6 < sourceEnd)
                    throw new IllegalArgumentException("Source span exceeds the reference duration");
                ReferenceStretch.write(reference, warped, range.count(), elapsed ->
                        timeline.source(range.start() / (double) PRESENTATIONS_PER_SECOND + elapsed));
                reference = warped;
                referenceStart = 0;
            }
            var result = write(render, reference, mp3 ? wav : output, range, referenceStart, timeline);
            if (mp3) {
                encodeMp3(wav, encoded);
                Files.move(encoded, output);
            }
            return result;
        } finally {
            Files.deleteIfExists(encoded);
            Files.deleteIfExists(wav);
            Files.deleteIfExists(warped);
            Files.delete(scratch);
        }
    }

    private static Result write(Path render, Path reference, Path output, WaveFile.Range range, long referenceStart,
                                ReconstructionTimeMap timeline) throws IOException {
        validateRange(range);
        if (referenceStart < 0) throw new IllegalArgumentException("Range starts before the aligned reference");
        int frames = range.count() * STEREO_FRAMES_PER_PRESENTATION;
        long start = (long) range.start() * STEREO_FRAMES_PER_PRESENTATION;
        float[] a = ReferenceAudio.stereoRange(render, SAMPLE_RATE, start, frames);
        float[] b = ReferenceAudio.stereoRange(reference, SAMPLE_RATE, referenceStart, frames);
        var match = PhraseAudio.write(output, range.count(), a, b);
        return new Result(output.toString(), List.of("render", "reference"), frames / (double) SAMPLE_RATE,
                match.renderGain(), match.referenceGain(), match.matched(), timeline.source(range.start() / 60.0),
                timeline.source(range.end() / 60.0), range.start() / 60.0, range.end() / 60.0, range.start(), range.end());
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
