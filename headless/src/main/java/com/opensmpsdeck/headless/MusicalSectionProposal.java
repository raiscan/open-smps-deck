package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionSections.Level;
import static com.opensmpsdeck.headless.ReconstructionSections.Author;
import static com.opensmpsdeck.headless.ReconstructionSections.Section;

/** Hierarchical segmentation of the source clock on a half-bar lattice. */
final class MusicalSectionProposal {
    private record Group(int start, int end, Level level) {}
    private record Features(double[] harmony, double[] melody, double[] texture, double[] audio) {}
    private record Evidence(double texture, double harmony, double cadence, double repeat) {
        double section() { return .55 * texture + .20 * harmony + .10 * cadence + .15 * repeat; }
        double phrase() { return .15 * texture + .25 * harmony + .50 * cadence + .10 * repeat; }
    }

    private final ReconstructionSections.Grid grid;
    private final Features[] features;
    private final Evidence[] evidence;
    private final Map<String, String> repeatBars = new HashMap<>();
    private final Map<String, String> phraseBars = new HashMap<>();

    private MusicalSectionProposal(ReconstructionStages.Score score, ReconstructionSections.Grid grid,
                                   ReconstructionStructure.Result structure, List<String> warnings) {
        this.grid = grid;
        var repeatEdges = new HashMap<String, Double>();
        if (structure != null) {
            for (var repeat : structure.repeats()) for (var bar : repeat.bars()) repeatBars.put(bar, repeat.id());
            if (structure.phrases() != null) for (var phrase : structure.phrases()) for (var instance : phrase.instances()) {
                for (int i = 0; i < instance.bars().size(); i++)
                    phraseBars.putIfAbsent(instance.bars().get(i), phrase.id() + "/" + i);
                repeatEdges.merge(instance.bars().getFirst(), phrase.similarity(), Math::max);
            }
        }
        var paths = new ArrayList<Path>();
        if (score.inputs() != null) {
            paths.add(Path.of(score.inputs().wav()));
            score.inputs().stems().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> Path.of(entry.getValue())).filter(path -> !paths.contains(path)).distinct().toList()
                    .forEach(paths::add);
        }

        features = new Features[grid.points().size() - 1];
        evidence = new Evidence[features.length + 1];
        var notes = score.notes().stream().sorted(java.util.Comparator.comparingDouble(ReconstructionStages.Note::start)).toList();
        var active = new ArrayList<ReconstructionStages.Note>();
        var parts = score.source().parts();
        var indices = new HashMap<Integer, Integer>();
        for (int i = 0; i < parts.size(); i++) indices.put(parts.get(i).id(), i);
        int lead = leadPart(score, parts);
        var levels = new double[paths.size()][features.length];
        var audioChroma = new double[features.length][12];
        for (int i = 0; i < paths.size(); i++)
            measure(paths.get(i), levels[i], i == 0 ? audioChroma : null, warnings);

        int cursor = 0;
        var cadence = new double[evidence.length];
        for (int i = 0; i < features.length; i++) {
            double start = point(i), end = point(i + 1), length = end - start;
            active.removeIf(note -> note.end() <= start);
            while (cursor < notes.size() && notes.get(cursor).start() < end) {
                var note = notes.get(cursor++);
                if (note.end() > start) active.add(note);
            }
            var harmonic = new double[12];
            var melody = new double[24];
            var texture = new double[parts.size() * 2];
            double leadAfter = 0, leadClose = 0;
            for (var note : active) {
                int part = indices.get(note.part());
                double share = (Math.min(end, note.end()) - Math.max(start, note.start())) / length;
                texture[part * 2] += share;
                if (note.start() >= start) texture[part * 2 + 1]++;
                if (!parts.get(part).drums()) harmonic[note.pitch() % 12] += share;
                if (note.part() == lead) {
                    melody[(note.start() < start + length / 2 ? 0 : 12) + note.pitch() % 12] += share;
                    leadAfter = Math.max(leadAfter, Math.max(0, Math.min(note.end(), start + .25 * length)
                            - Math.max(start, note.start())) / (.25 * length));
                    if (note.end() > end - .28 * length && note.end() < end + .32 * length)
                        leadClose = Math.max(leadClose, Math.min(1, (note.end() - note.start()) / length));
                }
            }
            cadence[i] = .6 * cadence[i] + .4 * (1 - leadAfter);
            cadence[i + 1] = leadClose;
            for (int j = 0; j < texture.length; j++) texture[j] = Math.log1p(texture[j]);
            unit(harmonic);
            unit(audioChroma[i]);
            if (Arrays.stream(audioChroma[i]).anyMatch(value -> value > 0)) {
                for (int j = 0; j < 12; j++) harmonic[j] = .55 * harmonic[j] + .45 * audioChroma[i][j];
                unit(harmonic);
            }
            unit(melody);
            var audio = new double[paths.size()];
            for (int j = 0; j < audio.length; j++) audio[j] = levels[j][i];
            features[i] = new Features(harmonic, melody, texture, audio);
        }
        for (int i = 1; i < features.length; i++) {
            int radius = Math.min(4, Math.min(i, features.length - i));
            var before = mean(i - radius, i);
            var after = mean(i, i + radius);
            double midiChange = distance(before.texture, after.texture, true);
            double audioChange = distance(before.audio, after.audio, false);
            double harmonic = 1 - cosine(before.harmony, after.harmony);
            double arrival = .5 * (1 - cosine(features[i - 1].harmony, features[i].harmony))
                    + .5 * cosine(features[i].harmony, after.harmony);
            double repeat = whole(i) ? repeatEdges.getOrDefault(barId(i), 0.0) : 0;
            evidence[i] = new Evidence(Math.max(midiChange, .35 * midiChange + .65 * audioChange),
                    Math.clamp(harmonic * 1.8, 0, 1), cadence[i] * arrival, repeat);
        }
    }

    static List<Section> propose(ReconstructionStages.Score score, ReconstructionSections.Grid grid,
                                 ReconstructionStructure.Result structure, List<String> warnings) {
        if (grid.points().size() < 2) return List.of();
        return new MusicalSectionProposal(score, grid, structure, warnings).propose();
    }

    private List<Section> propose() {
        var groups = new ArrayList<Group>();
        for (var section : partition(0, features.length, Level.section)) {
            groups.add(section);
            var phrases = partition(section.start, section.end, Level.phrase);
            for (int i = 0; i < phrases.size();) {
                int end = Math.min(phrases.size(), i + 2);
                // Prefer two phrases per period and attach an odd last phrase to its preceding pair.
                if (phrases.size() - end == 1) end++;
                if (end - i >= 2)
                    groups.add(new Group(phrases.get(i).start, phrases.get(end - 1).end, Level.period));
                i = end;
            }
            groups.addAll(phrases);
        }
        var result = new ArrayList<Section>();
        for (var level : Level.values()) {
            var families = new ArrayList<Group>();
            for (var group : groups) {
                if (group.level != level) continue;
                double best = 0;
                int family = -1;
                for (int i = 0; i < families.size(); i++) {
                    double similarity = similarity(group, families.get(i));
                    if (similarity > best) {
                        best = similarity;
                        family = i;
                    }
                }
                String label;
                if (best >= .73) {
                    label = letters(family) + (best >= .87 ? "" : "′");
                } else {
                    label = letters(families.size());
                    families.add(group);
                }
                result.add(grid.section(level + "-" + (group.start / 2 + 1) + (whole(group.start) ? "" : "-half"),
                        level, label, Author.automatic, "Grid, chroma, repeat, cadence and texture evidence",
                        point(group.start), point(group.end), confidence(group.start, level), confidence(group.end, level)));
            }
        }
        return List.copyOf(result);
    }

    private List<Group> partition(int start, int end, Level level) {
        boolean section = level == Level.section;
        int min = section ? 8 : 4;
        int max = section ? 40 : 8;
        if (end - start < min * 2 && (section || end - start <= max)) return List.of(new Group(start, end, level));
        var scores = new double[end - start + 1];
        var previous = new int[scores.length];
        Arrays.fill(scores, Double.NEGATIVE_INFINITY);
        scores[0] = 0;
        for (int to = start + 1; to <= end; to++) {
            if (to < end && !whole(to) && !strongHalf(to, level)) continue;
            for (int from = Math.max(start, to - max); from <= to - min; from++) {
                double bars = (to - from) / 2.0;
                double lengthCost = section
                        ? Math.min(Math.abs(bars - 8), .15 + Math.abs(bars - 16)) * .060
                        : Math.abs(bars - 4) * .075;
                double boundary = to == end ? 0 : section
                        ? 2.0 * evidence[to].section() - .53
                        : .7 * evidence[to].phrase() - .07;
                if (to < end && !whole(to)) boundary -= .11;
                double value = scores[from - start] + boundary - lengthCost;
                if (value > scores[to - start]) {
                    scores[to - start] = value;
                    previous[to - start] = from;
                }
            }
        }
        if (!Double.isFinite(scores[end - start])) return List.of(new Group(start, end, level));
        var groups = new ArrayList<Group>();
        for (int to = end; to > start; to = previous[to - start])
            groups.add(new Group(previous[to - start], to, level));
        return groups.reversed();
    }

    private double confidence(int i, Level level) {
        if (i == 0 || i == features.length) return 1;
        return Math.min(.99, .15 + (level == Level.section ? 1.45 * evidence[i].section() : evidence[i].phrase()));
    }

    private boolean strongHalf(int i, Level level) {
        var at = evidence[i];
        return level == Level.section
                ? at.texture > .64 && at.section() > Math.max(evidence[i - 1] == null ? 0 : evidence[i - 1].section(),
                          evidence[i + 1] == null ? 0 : evidence[i + 1].section()) + .10
                : at.cadence > .67 && at.phrase() > .47;
    }

    private double similarity(Group a, Group b) {
        int lengthA = a.end - a.start, lengthB = b.end - b.start;
        if (Math.min(lengthA, lengthB) < .7 * Math.max(lengthA, lengthB)) return 0;
        int length = Math.min(lengthA, lengthB);
        double similarity = 0;
        for (int i = 0; i < length; i++) {
            int j = a.start + i, k = b.start + i;
            var x = features[j];
            var y = features[k];
            double texture = 1 - Math.max(distance(x.texture, y.texture, true), distance(x.audio, y.audio, false));
            double harmony = cosine(x.harmony, y.harmony);
            double melody = cosine(x.melody, y.melody);
            double score = .45 * harmony + .30 * melody + .25 * texture;
            if (shared(phraseBars, j, k)) score = Math.max(.88, score);
            if (shared(repeatBars, j, k)) score = Math.max(.94, score);
            similarity += score;
        }
        return similarity / length * (.8 + .2 * length / Math.max(lengthA, lengthB));
    }

    private boolean shared(Map<String, String> groups, int j, int k) {
        String a = groups.get(barId(j));
        return (j & 1) == (k & 1) && a != null && a.equals(groups.get(barId(k)));
    }

    private double point(int i) { return grid.points().get(i); }
    private boolean whole(int i) { return (i & 1) == 0; }
    private String barId(int i) { return grid.bars().get(i / 2).id(); }

    private Features mean(int start, int end) {
        var first = features[start];
        var average = new Features(new double[12], new double[24], new double[first.texture.length], new double[first.audio.length]);
        for (int i = start; i < end; i++) {
            add(average.harmony, features[i].harmony, end - start);
            add(average.texture, features[i].texture, end - start);
            add(average.audio, features[i].audio, end - start);
        }
        return average;
    }

    private static void add(double[] target, double[] source, int count) {
        for (int i = 0; i < target.length; i++) target[i] += source[i] / count;
    }

    private static double distance(double[] a, double[] b, boolean relative) {
        double largest = 0, sum = 0;
        for (int i = 0; i < a.length; i++) {
            double delta = Math.abs(a[i] - b[i]) / (relative ? .65 + Math.max(a[i], b[i]) : 1);
            largest = Math.max(largest, delta);
            sum += delta;
        }
        return a.length == 0 ? 0 : Math.min(1, .65 * largest + .35 * sum / a.length);
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        if (aa + bb == 0) return 1;
        return aa * bb == 0 ? 0 : Math.min(1, dot / Math.sqrt(aa * bb));
    }

    private static void unit(double[] vector) {
        double norm = Math.sqrt(Arrays.stream(vector).map(x -> x * x).sum());
        if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= norm;
    }

    private static int leadPart(ReconstructionStages.Score score, List<MidiScore.Part> parts) {
        if (score.inputs() != null && score.inputs().leadPart() != null) return score.inputs().leadPart();
        for (var part : parts) {
            String name = part.name().toLowerCase(Locale.ROOT);
            if (!part.drums() && name.matches(".*(lead|melody|vocals?).*") && !name.contains("back"))
                return part.id();
        }
        return parts.stream().filter(part -> !part.drums()).mapToInt(MidiScore.Part::id).findFirst().orElse(-1);
    }

    private void measure(Path path, double[] levels, double[][] chroma, List<String> warnings) {
        if (Files.isRegularFile(path)) {
            try {
                for (int i = 0; i < levels.length; i++) {
                    var audio = ReferenceAudio.evidenceRange(path, point(i), point(i + 1));
                    var mono = audio.samples();
                    if (mono.length == 0) break;
                    double sum = 0;
                    for (float sample : mono) sum += (double) sample * sample;
                    // Clip silence to -65 dBFS so tiny bleed does not dominate texture change.
                    levels[i] = 20 * Math.log10(Math.max(Math.sqrt(sum / mono.length), .00056));
                    if (chroma != null) {
                        var block = new ReferenceAudio(mono, audio.rate());
                        for (int j = 0; j < 6; j++)
                            add(chroma[i], ReconstructionRhythmAudio.chroma(block, (j + .5) * mono.length / (6 * audio.rate())), 6);
                    }
                }
                double peak = Arrays.stream(levels).filter(x -> x != 0).max().orElse(-65);
                for (int i = 0; i < levels.length; i++)
                    levels[i] = levels[i] == 0 || peak < -56 ? 0 : Math.clamp((levels[i] - peak + 35) / 35, 0, 1);
                return;
            } catch (IllegalArgumentException | IOException unavailable) {
                Arrays.fill(levels, 0);
                if (chroma != null) for (var values : chroma) Arrays.fill(values, 0);
            }
        }
        warnings.add("Audio evidence unavailable: " + path.getFileName());
    }

    private static String letters(int index) {
        var label = new StringBuilder();
        for (int value = index + 1; value > 0; value = (value - 1) / 26)
            label.append((char) ('A' + (value - 1) % 26));
        return label.reverse().toString();
    }
}
