package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Measures canonical replay and a presentation-aligned handoff without an audio line. */
public final class LiveEditBenchmark {
    private LiveEditBenchmark() {}

    private record Result(String target, int samples, int presentation, Double provisionalP95Ms,
                          double settledP95Ms, int provisionalUnavailable, int provisionalDifferent,
                          boolean bankParity, boolean pcmParity) {}

    public static int run(String[] args, PrintStream out) throws IOException {
        if (args.length < 4) throw new IllegalArgumentException("Expected bench live-edit WORK_DIR JOURNAL.json");
        Path work = Path.of(args[2]).toAbsolutePath().normalize();
        var edits = ReconstructionEdits.batch(ReconstructionArtifacts.readTree(Path.of(args[3])), args[3]);
        ReconstructionReview.parse(edits);
        var current = ProjectExport.open(work);
        var target = current.project().target();
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        int end = ReconstructionTimeMap.of(score).frames();
        double seconds = 0;
        int samples = 6;
        for (int i = 4; i < args.length; i += 2) {
            if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
            String value = args[i + 1];
            switch (args[i]) {
                case "--at" -> seconds = Double.parseDouble(value);
                case "--samples" -> samples = Integer.parseInt(value);
                case "--target" -> {
                    if (TargetGame.parse(value) != target) throw new IllegalArgumentException("Target differs from the saved reconstruction");
                }
                default -> throw new IllegalArgumentException("Unknown live-edit option: " + args[i]);
            }
        }
        if (!(seconds >= 0 && seconds < end / 60.0) || samples < 1 || samples > 100)
            throw new IllegalArgumentException("Expected --at within the song and --samples in 1..100");
        int at = (int) (seconds * 60);
        var revision = ReconstructionReview.revision(work);
        var built = new JsonArray();
        var outcomes = ReconstructionEdits.Journal.read(work).all().stream().map(ReconstructionEdits.Outcome::id)
                .collect(java.util.stream.Collectors.toSet());
        ReconstructionReview.parse(revision.entries()).accepted().intents().stream()
                .filter(edit -> outcomes.contains(edit.id())).forEach(edit -> built.add(edit.json()));
        if (built.equals(edits)) throw new IllegalArgumentException("The benchmark journal must change the built revision");
        var original = TargetPlayback.prepare(current.project()).checkpoints();
        original.warmThrough(at);
        var live = ReconstructionLivePreview.open(work, revision);
        var replay = new ReconstructionReview.LiveReplay();
        replay.warm(work);
        var provisionalTimes = new ArrayList<Double>();
        var settledTimes = new ArrayList<Double>();
        int different = 0;
        ReconstructionLivePreview.Prepared canonical = null;
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            ReconstructionLivePreview.Prepared provisional = null;
            try {
                provisional = live.prepareProvisional(edits, 0, end / 60.0, seconds);
                var provisionalAudio = original.revise(provisional.playback(), Integer.MAX_VALUE);
                readAt(provisionalAudio, at);
                provisionalTimes.add(milliseconds(start));
            } catch (ReconstructionLivePreview.RebuildNeeded unavailable) {
                // Report the missing preview along with canonical replay time.
            }
            canonical = replay.rebuild(work, revision, built, edits, false, (done, total, message) -> {}).playback();
            if (provisional == null || !canonical.sameAudio(provisional)) {
                var settled = original.revise(canonical.playback(), Integer.MAX_VALUE);
                readAt(settled, at);
                if (provisional != null) different++;
            }
            settledTimes.add(milliseconds(start));
        }
        var full = ReconstructionReview.exportSource(work, revision, built, edits, (done, total, message) -> {});
        var reference = TargetPlayback.prepare(full.project());
        boolean bank = sameBank(canonical.playback(), reference);
        boolean pcm = full.frames() == end && samePcm(canonical.playback(), reference, end);
        out.println(new Gson().toJson(new Result(target.id(), samples, at,
                provisionalTimes.isEmpty() ? null : p95(provisionalTimes), p95(settledTimes),
                samples - provisionalTimes.size(), different, bank, pcm)));
        return bank && pcm ? 0 : 1;
    }

    private static void readAt(CheckpointPlayback playback, int presentation) throws IOException {
        try (var reader = playback.reader()) {
            reader.at(presentation);
        }
    }

    private static boolean sameBank(TargetPlayback left, TargetPlayback right) {
        var a = left.compiled();
        var b = right.compiled();
        return Arrays.equals(a.song(), b.song()) && a.envelopes().equals(b.envelopes()) && a.samples().equals(b.samples());
    }

    private static boolean samePcm(TargetPlayback left, TargetPlayback right, int end) throws IOException {
        try (var a = left.checkpoints().reader(); var b = right.checkpoints().reader()) {
            for (int frame = 0; frame < end; frame++) {
                if (!Arrays.equals(a.at(frame), b.at(frame))) return false;
            }
        }
        return true;
    }

    private static double milliseconds(long start) { return (System.nanoTime() - start) / 1e6; }

    private static double p95(List<Double> values) {
        return values.stream().sorted().toList().get((int) Math.ceil(.95 * values.size()) - 1);
    }
}
