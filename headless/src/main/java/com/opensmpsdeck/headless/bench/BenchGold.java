package com.opensmpsdeck.headless.bench;

import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

final class BenchGold {
    record Spec(String id, String reference, String midi, String annotations, TreeMap<Integer, String> stems,
                Integer leadPart, Double seconds) {}
    record Annotations(List<Region> regions) {}
    record Region(String id, @SerializedName("start_s") double start, @SerializedName("end_s") double end,
                  List<Label> notes) {}
    record Label(Integer midi, @SerializedName("attack_s") Double attack, @SerializedName("release_s") Double release,
                 String voice) {}
    record Input(String id, Path reference, Path midi, Path annotations, TreeMap<Integer, Path> stems,
                 Integer leadPart, Double seconds, List<Region> regions) {}
    record Score(BenchMetrics.F1 attacks, int pitchCompared, int pitchCorrect) {
        Double pitchAccuracy() { return pitchCompared == 0 ? null : (double) pitchCorrect / pitchCompared; }
    }

    private BenchGold() {}

    static Input read(Path path) throws IOException {
        var spec = BenchFiles.read(path, Spec.class);
        if (spec.reference == null || spec.midi == null || spec.annotations == null)
            throw new IllegalArgumentException("Gold case requires reference, midi and annotations paths");
        Path root = path.toAbsolutePath().getParent();
        Path annotationPath = root.resolve(spec.annotations);
        var annotations = BenchFiles.read(annotationPath, Annotations.class);
        if (annotations.regions == null || annotations.regions.isEmpty())
            throw new IllegalArgumentException("Gold annotations need at least one region");
        var regions = annotations.regions.stream().sorted(java.util.Comparator.comparingDouble(Region::start)).toList();
        double previous = 0;
        for (var region : regions) {
            if (region.start < previous || !(region.end > region.start) || !Double.isFinite(region.end)
                    || region.notes == null)
                throw new IllegalArgumentException("Invalid or overlapping gold annotation region: " + region.id);
            previous = region.end;
        }
        if (spec.seconds != null && !(spec.seconds >= previous && spec.seconds <= 600))
            throw new IllegalArgumentException("Gold --seconds must cover every annotated region");
        var stems = new TreeMap<Integer, Path>();
        if (spec.stems != null) spec.stems.forEach((part, stem) -> stems.put(part, root.resolve(stem)));
        return new Input(spec.id == null ? "gold" : spec.id, root.resolve(spec.reference), root.resolve(spec.midi),
                annotationPath, stems, spec.leadPart, spec.seconds, regions);
    }

    static Score score(List<Region> regions, List<BenchMetrics.Event> melody) {
        int expected = 0, estimated = 0, matched = 0, pitched = 0, correct = 0;
        for (var region : regions) {
            var truth = new ArrayList<BenchMetrics.Event>();
            for (var note : region.notes) {
                if (!"lead".equalsIgnoreCase(note.voice) || note.attack == null
                        || note.attack < region.start || note.attack >= region.end) continue;
                int pitch = note.midi == null ? -1 : note.midi;
                truth.add(new BenchMetrics.Event("lead", note.attack,
                        note.release == null ? note.attack : note.release, pitch, null, null));
            }
            var estimate = melody.stream().filter(note -> note.start() >= region.start && note.start() < region.end).toList();
            var pairs = BenchMetrics.attacks(truth, estimate, .050);
            expected += truth.size();
            estimated += estimate.size();
            matched += pairs.size();
            for (var pair : pairs) {
                int pitch = truth.get(pair.truth()).pitch();
                if (pitch < 0) continue;
                pitched++;
                if (pitch == estimate.get(pair.estimate()).pitch()) correct++;
            }
        }
        return new Score(new BenchMetrics.F1(expected, estimated, matched), pitched, correct);
    }
}
