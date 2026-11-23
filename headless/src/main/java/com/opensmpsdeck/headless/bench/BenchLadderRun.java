package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchCartridge;
import com.opensmpsdeck.headless.BenchClock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;

final class BenchLadderRun {
    record Row(String id, BenchCartridge.Game game, int song, String songName, BenchLadder.Rung rung, boolean torture,
               double seconds, String nativeEnd, Integer nativeLoopStart, double wallSeconds,
               String lastCompletedStage, Long projectBytes, Map<String, Double> metrics,
               BenchLadderTrace.Detail trace, Map<String, Long> reasonCounts, String error, String listeningError) {}

    record Inputs(Map<Integer, Path> stems, Map<String, Path> audioStems, Integer leadPart,
                  DoubleUnaryOperator toTruth, DoubleUnaryOperator toInput) {
        Inputs(Map<Integer, Path> stems, Integer leadPart, DoubleUnaryOperator toTruth, DoubleUnaryOperator toInput) {
            this(stems, Map.of(), leadPart, toTruth, toInput);
        }

        boolean audioOnly() { return !audioStems.isEmpty(); }

        DoubleUnaryOperator projectToTruth(BenchClock clock) {
            return project -> toTruth.applyAsDouble(clock.toSource().applyAsDouble(project));
        }
    }

    private BenchLadderRun() {}

    static Inputs inputs(BenchCorpus.Case c, Path root, BenchCli.Hooks hooks) throws IOException {
        var input = c.ladder();
        if (input.rung() == BenchLadder.Rung.LOCAL) {
            var labelled = new TreeMap<String, Path>();
            input.stems().forEach((label, file) -> labelled.put(label, file.resolve(root)));
            if (c.midi() != null || labelled.isEmpty())
                throw new IllegalArgumentException("Local ladder case needs separated stems and no MIDI: " + c.id());
            return new Inputs(Map.of(), labelled, null, DoubleUnaryOperator.identity(), DoubleUnaryOperator.identity());
        }
        var midi = BenchFiles.JSON.fromJson(hooks.midi().read(c.midi().resolve(root)), BenchReconstruction.Midi.class);
        var stems = new TreeMap<Integer, Path>();
        Integer lead = null;
        for (var part : midi.parts()) {
            var file = input.stems().get(part.name());
            if (file != null) stems.put(part.id(), file.resolve(root));
            if (!part.drums() && part.name().equals(input.leadTrack())) lead = part.id();
        }
        double duration = input.frames() / 60.0;
        if (c.drift() == null) return new Inputs(stems, lead,
                DoubleUnaryOperator.identity(), DoubleUnaryOperator.identity());
        var time = new BenchCorpus.Accelerando(duration, c.drift());
        return new Inputs(stems, lead, time::truthTime, time::inputTime);
    }

    static void save(BenchCorpus.Case c, Path root, Path work, Path output, long started,
                     Map<String, Double> metrics, BenchLadderTrace.Detail trace, Map<String, Long> reductions,
                     String error) throws IOException {
        var input = c.ladder();
        Path dir = Files.createDirectories(output.resolve("cases").resolve(input.game().name())
                .resolve("%02x".formatted(c.song())).resolve(input.rung().label));
        double elapsed = (System.nanoTime() - started) / 1e9;
        double seconds = input.frames() / 60.0;
        Path arrangement = work.resolve("arrangement.smps.json");
        Long bytes = Files.exists(arrangement) ? Files.size(arrangement) : null;
        String stage = lastStage(work);
        String listeningError = null;
        try {
            if (input.rung() == BenchLadder.Rung.EXACT)
                encode(c.reference().resolve(root), dir.resolve("original.mp3"), seconds);
            boolean listen = switch (input.rung()) {
                case EXACT, TRANSCRIBED, SUNO_LIKE, LOCAL -> true;
                case GROUPED -> input.torture();
                default -> false;
            };
            if (listen && Files.isRegularFile(work.resolve("render.wav")))
                encode(work.resolve("render.wav"), dir.resolve("reconstruction.mp3"), seconds);
        } catch (InterruptedIOException failure) {
            throw failure;
        } catch (IOException failure) {
            listeningError = failure.toString();
        }
        BenchFiles.write(dir.resolve("ladder-case.json"), new Row(c.id(), input.game(), c.song(), c.songName(),
                input.rung(), input.torture(), seconds, input.end(), input.loopStartFrame(), elapsed, stage, bytes,
                metrics, trace, reductions, error, listeningError));
    }

    private static String lastStage(Path work) {
        for (var name : List.of("report", "voicing", "allocation", "roles", "structure", "score")) {
            if (Files.isRegularFile(work.resolve(name + ".json"))) return name;
        }
        return "none";
    }

    private static void encode(Path wav, Path mp3, double seconds) throws IOException {
        var process = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                "-i", wav.toString(), "-af", "apad", "-t", Double.toString(seconds), "-c:a", "libmp3lame", "-q:a",
                "4", mp3.toString()).redirectErrorStream(true).start();
        try {
            byte[] log = process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) {
                Files.deleteIfExists(mp3);
                throw new IOException("MP3 export failed: "
                        + new String(log, java.nio.charset.StandardCharsets.UTF_8).strip());
            }
        } catch (InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("MP3 export interrupted");
        }
    }
}
