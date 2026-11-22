package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionEditing.VibratoSegment;

/** Suggests staged FM vibrato from sustained periodic pitch movement in mapped stems. */
public final class ReconstructionVibratoEnvelope {
    private static final Gson JSON = new Gson();
    private static final String GROUP = "staged-vibrato";
    private static final double MIN_SWING = 11;
    private static final double MIN_HALF_CYCLE = 1.0 / 30;
    private static final double MAX_HALF_CYCLE = 1.0 / 5;

    private ReconstructionVibratoEnvelope() {}

    record Stage(double time, double hz, double depth) {}
    private record HalfCycle(double start, double period, double depth, double center) {}
    private record Fit(double period, double depth, double error) {}

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length == 0) throw new IllegalArgumentException("reconstruct vibrato-review expects WORK_DIR");
        Integer part = null;
        boolean propose = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--part" -> {
                    require(part == null && i + 1 < args.length, "vibrato-review", "expected one part ID");
                    part = Integer.parseInt(args[++i]);
                }
                case "--propose" -> {
                    require(!propose, "vibrato-review", "duplicate --propose");
                    propose = true;
                }
                default -> throw new IllegalArgumentException("Unknown vibrato-review option " + args[i]);
            }
        }
        out.println(JSON.toJson(review(Path.of(args[0]), part, propose)));
    }

    public static JsonObject review(Path work, Integer part, boolean propose) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        require(score.profile().game() == TargetGame.S3K, GROUP, "vibratoEnvelope requires the S3K target");
        require(part == null || score.source().parts().stream().anyMatch(p -> p.id() == part),
                GROUP, "unknown part");
        var time = ReconstructionTimeMap.of(score);
        var retriggers = ReconstructionNoteVoicing.retriggerAnchors(score);
        var journal = ReconstructionEdits.read(work);
        var assigned = new HashSet<String>();
        for (var edit : journal.intents()) {
            if (edit.kind() != ReconstructionEdits.Kind.noteVoicing) continue;
            var body = edit.json();
            if (body.has("vibratoEnvelope") || body.has("vibrato") || body.has("modulationOff"))
                assigned.addAll(edit.noteIds());
        }
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        var trackers = new HashMap<String, ReconstructionBends.Tracker>();
        var candidates = new JsonArray();
        int examined = 0;
        for (var entry : allocation.notes()) {
            var note = entry.note();
            if (entry.lane() == null || !entry.lane().startsWith("FM") || part != null && note.part() != part
                    || assigned.contains(note.id()) || retriggers.contains(note.id()) || note.run() != null
                    || allocation.chords().containsKey(note.id())
                    || note.bend() != null && note.bend().editReason() != null) continue;
            String stem = score.inputs().stems().get(note.part());
            if (stem == null) continue;
            if (allocation.timeBase() == ReconstructionTimeMap.TimeBase.project) note = time.sourceNote(note);
            if (note.end() - note.start() > 2.5 || note.end() - note.start() < .24 || note.pitch() < 36 || note.pitch() > 94) continue;
            var tracker = trackers.get(stem);
            if (tracker == null) {
                tracker = new ReconstructionBends.Tracker(ReconstructionLeadAudio.readMono(Path.of(stem), time.sourceDuration()));
                trackers.put(stem, tracker);
            }
            examined++;
            var stages = detect(note.start(), tracker.vibratoTrace(note));
            var segments = new java.util.TreeMap<Integer, VibratoSegment>();
            for (var stage : stages) {
                int offset = time.tick(time.project(stage.time())) - entry.startTick();
                if (offset >= 0 && offset < entry.endTick() - entry.startTick()) {
                    double projectPeriod = time.project(stage.time() + 1 / stage.hz()) - time.project(stage.time());
                    double rate = round(Math.clamp(1 / projectPeriod, .12, 15));
                    segments.put(offset, new VibratoSegment(offset, rate, round(stage.depth())));
                }
            }
            if (segments.isEmpty() || segments.size() == 1 && segments.firstKey() == 0) continue;
            var edit = new JsonObject();
            edit.addProperty("id", GROUP + "-" + ReconstructionArtifacts.fingerprint(note.id()).substring(0, 16));
            edit.addProperty("op", "noteVoicing");
            edit.addProperty("author", "agent");
            edit.addProperty("reason", "Periodic pitch movement changes within a held stem note");
            edit.addProperty("group", GROUP);
            edit.add("noteIds", JSON.toJsonTree(List.of(note.id())));
            edit.add("vibratoEnvelope", JSON.toJsonTree(segments.values()));
            candidates.add(edit);
        }
        var result = new JsonObject();
        result.addProperty("examined", examined);
        result.add("candidates", candidates);
        if (propose && !candidates.isEmpty())
            result.add("proposals", ReconstructionEditCommand.run(work, "propose", candidates, null, false, (completed, total, message) -> {}));
        return result;
    }

    static List<Stage> detect(double attack, List<ReconstructionBends.Sample> track) {
        if (track.size() < 25) return List.of();
        track = smooth(track);
        var cycles = cycles(track);
        if (cycles.size() < 3) return List.of();

        // Choose the longest contiguous run with a stable center and period.
        // This rejects slides, pitch steps, and isolated swings at a release.
        int begin = 0;
        List<HalfCycle> best = List.of();
        for (int i = 1; i <= cycles.size(); i++) {
            if (i < cycles.size() && continuous(cycles.get(i - 1), cycles.get(i))) continue;
            if (i - begin > best.size()) best = cycles.subList(begin, i);
            begin = i;
        }
        if (best.size() < 3) return List.of();

        var fit = fit(best);
        double firstPeak = best.getFirst().start();
        double onset = firstPeak - fit.period() / 2;
        var quiet = track.stream().filter(p -> p.time() >= attack + .055 && p.time() < onset - .018).toList();
        double quietCenter = median(quiet.stream().mapToDouble(ReconstructionBends.Sample::cents).toArray());
        double quietSpread = median(quiet.stream().mapToDouble(p -> Math.abs(p.cents() - quietCenter)).toArray());
        boolean delayed = onset - attack >= .10 && quiet.size() >= 6
                && Math.abs(quietCenter - best.getFirst().center()) <= 30 && quietSpread <= 9
                && quiet.stream().filter(p -> Math.abs(p.cents() - quietCenter) <= 20).count() >= .88 * quiet.size();
        int split = -1;
        double error = fit.error() * .78;
        for (int i = 2; i <= best.size() - 2; i++) {
            var left = fit(best.subList(0, i));
            var right = fit(best.subList(i, best.size()));
            boolean changed = Math.abs(left.depth() - right.depth()) >= 10
                    && Math.max(left.depth(), right.depth()) >= 1.55 * Math.min(left.depth(), right.depth())
                    || Math.abs(left.period() - right.period()) >= .22 * Math.min(left.period(), right.period());
            if (changed && left.error() + right.error() < error) {
                split = i;
                error = left.error() + right.error();
            }
        }
        if (!delayed && (split < 0 || firstPeak - attack > fit.period() * 1.8)) return List.of();
        if (split < 0) return List.of(stage(onset, fit));
        var left = fit(best.subList(0, split));
        var right = fit(best.subList(split, best.size()));
        // A deeper peak already reflects the new stage. Locate the boundary
        // between that peak and the preceding one, at the preceding crossing.
        double change = best.get(split).start() - left.period() / 2;
        return List.of(stage(delayed ? firstPeak - left.period() / 2 : attack, left), stage(change, right));
    }

    private static double median(double[] values) {
        if (values.length == 0) return Double.NaN;
        java.util.Arrays.sort(values);
        return (values[values.length / 2] + values[(values.length - 1) / 2]) / 2;
    }

    private static Stage stage(double onset, Fit fit) {
        return new Stage(onset, .5 / fit.period(), Math.min(200, fit.depth()));
    }

    private static boolean continuous(HalfCycle a, HalfCycle b) {
        return Math.abs(a.start() + a.period() - b.start()) < .001
                && Math.abs(a.center() - b.center()) <= Math.max(18, .72 * Math.max(a.depth(), b.depth()))
                && Math.max(a.period(), b.period()) <= 1.45 * Math.min(a.period(), b.period());
    }

    private static Fit fit(List<HalfCycle> cycles) {
        double period = cycles.stream().mapToDouble(HalfCycle::period).average().orElseThrow();
        double depth = cycles.stream().mapToDouble(HalfCycle::depth).average().orElseThrow();
        double error = 0;
        for (var cycle : cycles) {
            error += Math.pow((cycle.period() - period) / .012, 2) + Math.pow((cycle.depth() - depth) / 5, 2);
        }
        return new Fit(period, depth, error);
    }

    private static List<ReconstructionBends.Sample> smooth(List<ReconstructionBends.Sample> track) {
        var result = new ArrayList<ReconstructionBends.Sample>(track.size());
        for (int i = 0; i < track.size(); i++) {
            var point = track.get(i);
            double[] neighbors = new double[7];
            int count = 0;
            for (int j = Math.max(0, i - 3); j < Math.min(track.size(), i + 4); j++) {
                var neighbor = track.get(j);
                if (Math.abs(neighbor.time() - point.time()) <= .019) neighbors[count++] = neighbor.cents();
            }
            java.util.Arrays.sort(neighbors, 0, count);
            result.add(new ReconstructionBends.Sample(point.time(),
                    (neighbors[count / 2] + neighbors[(count - 1) / 2]) / 2, point.support()));
        }
        return result;
    }

    private static List<HalfCycle> cycles(List<ReconstructionBends.Sample> track) {
        var extrema = new ArrayList<ReconstructionBends.Sample>();
        var peak = track.getFirst();
        int direction = 0;
        for (int i = 1; i + 1 < track.size(); i++) {
            var sample = track.get(i);
            double cents = (track.get(i - 1).cents() + 2 * sample.cents() + track.get(i + 1).cents()) / 4;
            var smooth = new ReconstructionBends.Sample(sample.time(), cents, sample.support());
            double step = smooth.cents() - peak.cents();
            if (direction == 0) {
                if (Math.abs(step) >= MIN_SWING) {
                    direction = step > 0 ? 1 : -1;
                    peak = smooth;
                }
            } else if (direction * step > 0) peak = smooth;
            else if (direction * step <= -MIN_SWING) {
                extrema.add(peak);
                peak = smooth;
                direction = -direction;
            }
        }
        var result = new ArrayList<HalfCycle>();
        for (int i = 1; i < extrema.size(); i++) {
            var a = extrema.get(i - 1);
            var b = extrema.get(i);
            double period = b.time() - a.time();
            double depth = Math.abs(a.cents() - b.cents()) / 2;
            if (period >= MIN_HALF_CYCLE && period <= MAX_HALF_CYCLE)
                result.add(new HalfCycle(a.time(), period, depth, (a.cents() + b.cents()) / 2));
        }
        return result;
    }

    private static double round(double value) { return Math.round(value * 100) / 100.0; }
}
