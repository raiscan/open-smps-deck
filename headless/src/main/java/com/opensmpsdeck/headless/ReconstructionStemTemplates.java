package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionLeadAudio.HOP;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Learns the ordinary partial levels of each mapped MIDI instrument in source time. */
final class ReconstructionStemTemplates {
    static final String FILE = "stem-template-candidates.json";
    static final String SOURCE = "audio-template";
    private static final int[] INTERVAL = {0, 0, 12, 19, 24, 28, 31, 34, 36};
    private static final double MIN_EXCESS_DB = 5;
    private static final double MIN_FUNDAMENTAL = .006;
    private static final int MIN_FRAMES = 3;

    record Candidate(String id, String anchorId, int part, int anchorPitch, int pitch, int partial, double start,
                     double end, int velocity, double confidence, double observedDb, double templateDb,
                     double excessDb, double templateSpreadDb, int trainingNotes, boolean localTemplate, boolean independentAttack,
                     boolean automatic) {
        Note note() {
            return new Note(id, part, start, end, pitch, velocity, SOURCE, confidence);
        }

        ReconstructionEdits.Intent proposal() {
            var edit = new JsonObject();
            edit.addProperty("id", id);
            edit.addProperty("op", "insertNote");
            edit.addProperty("group", SOURCE);
            edit.addProperty("reason", "Stem partial exceeds the learned harmonic template");
            edit.addProperty("part", part);
            edit.addProperty("start", start);
            edit.addProperty("end", end);
            edit.addProperty("pitch", pitch);
            edit.addProperty("velocity", velocity);
            var evidence = new JsonObject();
            evidence.addProperty("text", String.format(Locale.ROOT,
                    "Partial %d of %s is %.1f dB above the template (%.1f dB measured, %.1f dB typical, %d training notes, confidence %.2f)",
                    partial, anchorId, excessDb, observedDb, templateDb, trainingNotes, confidence));
            evidence.addProperty("path", FILE);
            edit.add("evidence", evidence);
            return ReconstructionEdits.parse(ReconstructionProposals.proposed(edit), FILE);
        }
    }

    private record Report(int version, List<Candidate> candidates, List<String> issuedProposals) {}
    private record Example(Note note, double[] ratios) {}
    private record Template(double db, double spread, int count, boolean local) {
        double threshold() { return db + MIN_EXCESS_DB + spread; }
    }

    private ReconstructionStemTemplates() {}

    static ReconstructionEdits publish(Path work, ReconstructionEdits journal, List<Candidate> candidates)
            throws IOException {
        Path file = work.resolve(FILE);
        if (candidates.isEmpty() && Files.notExists(file)) return journal;
        var issued = Files.exists(file)
                ? new HashSet<>(ReconstructionArtifacts.read(file, Report.class).issuedProposals())
                : new HashSet<String>();
        var intents = new ArrayList<>(journal.intents());
        var existing = new HashSet<String>();
        intents.forEach(intent -> existing.add(intent.id()));
        for (var candidate : candidates) {
            if (!candidate.automatic() && issued.add(candidate.id()) && existing.add(candidate.id()))
                intents.add(candidate.proposal());
        }
        if (intents.size() != journal.intents().size()) {
            journal = new ReconstructionEdits(intents);
            journal.write(work);
        }
        // A rejected proposal is absent from edits.json. Keep its issued ID
        // so a retranscribe or manifest replay does not reopen it.
        ReconstructionArtifacts.write(file, new Report(ReconstructionStages.VERSION, candidates,
                issued.stream().sorted().toList()));
        return journal;
    }

    static List<Candidate> detect(ReferenceAudio audio, List<Note> anchors, List<Note> occupancy) {
        if (anchors.size() < 4) return List.of();
        var track = new ReconstructionLeadAudio.Track(audio);
        var examples = learn(track, anchors, occupancy);
        if (examples.size() < 4) return List.of();
        var registers = new HashMap<Integer, Template[]>();
        var exact = new HashMap<Integer, NavigableMap<Double, Example>>();
        for (var example : examples) {
            var note = example.note();
            exact.computeIfAbsent(note.pitch(), ignored -> new TreeMap<>()).put(note.start(), example);
        }
        var sounding = new Sounding(occupancy, track.frames());
        var found = new ArrayList<Candidate>();
        for (var anchor : anchors) {
            var samePitch = exact.getOrDefault(anchor.pitch(), java.util.Collections.emptyNavigableMap());
            var nearby = samePitch.subMap(anchor.start() - 2, true, anchor.start() + 2, true).values().stream()
                    .filter(example -> !example.note().id().equals(anchor.id())).toList();
            // A stem can change timbre between passages. Repeated local
            // notes at the same pitch are the closest control.
            var byPartial = nearby.size() >= 2 ? templates(nearby, true)
                    : registers.computeIfAbsent(anchor.pitch(), pitch -> templates(
                            samePitch.size() >= 4 ? List.copyOf(samePitch.values()) : examples.stream()
                                    .filter(example -> Math.abs(example.note().pitch() - pitch) <= 5).toList(), false));
            for (int h = 2; h <= 4; h++) {
                if (byPartial[h] != null) scan(track, sounding, anchor, h, byPartial[h], found);
            }
        }
        // The same ridge can be explained by more than one written partial.
        var retained = new ArrayList<Candidate>();
        var claimed = new HashMap<Integer, BitSet>();
        found.sort(Comparator.comparingDouble(Candidate::confidence).reversed().thenComparing(Candidate::id));
        for (var candidate : found) {
            var covered = claimed.computeIfAbsent(candidate.pitch(), ignored -> new BitSet());
            int first = frame(candidate.start()), end = frame(candidate.end());
            int collision = covered.nextSetBit(first);
            if (collision >= 0 && collision < end) continue;
            covered.set(first, end);
            retained.add(candidate);
        }
        return retained;
    }

    private static List<Example> learn(ReconstructionLeadAudio.Track track, List<Note> anchors, List<Note> occupancy) {
        var ordered = occupancy.stream().sorted(ReconstructionStages.NOTE_ORDER).toList();
        var examples = new ArrayList<Example>();
        int first = 0;
        for (var anchor : anchors.stream().sorted(ReconstructionStages.NOTE_ORDER).toList()) {
            double center = (anchor.start() + anchor.end()) / 2;
            if (anchor.end() - anchor.start() < .050) continue;
            while (first < ordered.size() && ordered.get(first).end() <= center - .022) first++;
            boolean isolated = true;
            for (int i = first; i < ordered.size() && ordered.get(i).start() < center + .022; i++) {
                var other = ordered.get(i);
                if (!other.id().equals(anchor.id()) && other.end() > center - .022) {
                    isolated = false;
                    break;
                }
            }
            if (!isolated) continue;
            int middle = frame(center);
            int[] sampled = java.util.stream.IntStream.rangeClosed(middle - 2, middle + 2)
                    .filter(at -> at * HOP > anchor.start() + .004 && at * HOP < anchor.end() - .004)
                    .filter(at -> strongFundamental(track, anchor.pitch(), at)).toArray();
            if (sampled.length < 2) continue;
            var ratios = new double[5];
            for (int h = 2; h <= 4; h++) {
                int partial = h;
                ratios[h] = median(Arrays.stream(sampled)
                        .mapToDouble(at -> ratio(track, anchor.pitch(), partial, at)).toArray());
            }
            examples.add(new Example(anchor, ratios));
        }
        return examples;
    }

    private static Template[] templates(List<Example> training, boolean local) {
        var templates = new Template[5];
        if (training.size() < (local ? 2 : 4)) return templates;
        for (int h = 2; h <= 4; h++) {
            int partial = h;
            var levels = training.stream().mapToDouble(example -> example.ratios()[partial]).toArray();
            double center = median(levels);
            double spread = median(Arrays.stream(levels).map(level -> Math.abs(level - center)).toArray());
            templates[h] = new Template(center, spread, levels.length, local);
        }
        return templates;
    }

    private static void scan(ReconstructionLeadAudio.Track track, Sounding sounding, Note anchor, int partial,
                             Template template, List<Candidate> found) {
        int pitch = anchor.pitch() + INTERVAL[partial];
        if (pitch > 106) return;
        int from = Math.max(1, (int) Math.ceil((anchor.start() + .005) / HOP));
        int to = Math.min(track.frames(), (int) Math.ceil((anchor.end() - .003) / HOP));
        int first = from;
        double total = 0;
        for (int at = from; at <= to; at++) {
            if (at < to && strongFundamental(track, anchor.pitch(), at)
                    && track.partialLevel(anchor.pitch(), partial, at) >= .004
                    && !sounding.contested(pitch, partial, at)) {
                double db = ratio(track, anchor.pitch(), partial, at);
                if (db >= Math.max(-13, template.threshold())) {
                    total += db;
                    continue;
                }
            }
            int count = at - first;
            if (count >= MIN_FRAMES) {
                double observed = total / count;
                double excess = observed - template.db();
                boolean attack = independentAttack(track, anchor, partial, first);
                boolean automatic = attack && count >= 5 && observed >= -8 && excess >= 12 + template.spread()
                        && template.count() >= 4;
                double confidence = automatic
                        ? Math.min(.99, .90 + (excess - 12 - template.spread()) / 100)
                        : proposalConfidence(excess - template.spread(), count, attack);
                String id = SOURCE + "-" + anchor.part() + "-" + pitch + "-" + first;
                double start = Math.max(anchor.start(), (first - .5) * HOP);
                double end = Math.min(anchor.end(), (at - .5) * HOP);
                int velocity = Math.clamp((int) Math.round(anchor.velocity() * Math.pow(10, observed / 40)), 1, 127);
                found.add(new Candidate(id, anchor.id(), anchor.part(), anchor.pitch(), pitch, partial, start, end,
                        velocity, confidence, observed, template.db(), excess, template.spread(), template.count(),
                        template.local(), attack, automatic));
            }
            first = at + 1;
            total = 0;
        }
    }

    private static double proposalConfidence(double excess, int count, boolean attack) {
        double acceptedExcess = excess - MIN_EXCESS_DB;
        return .50 + .27 * acceptedExcess / (14 + acceptedExcess)
                + (attack ? .06 : 0) + .06 * (count - MIN_FRAMES) / (count + 2);
    }

    private static boolean independentAttack(ReconstructionLeadAudio.Track track, Note anchor, int partial, int first) {
        int pitch = anchor.pitch();
        int before = first - 3;
        return before * HOP >= anchor.start() + .018 && strongFundamental(track, pitch, before)
                && ratio(track, pitch, partial, first + 1) - ratio(track, pitch, partial, before) >= 9
                && track.partialLevel(pitch, partial, first + 1) >= 2 * track.partialLevel(pitch, partial, before);
    }

    private static boolean strongFundamental(ReconstructionLeadAudio.Track track, int pitch, int at) {
        double level = track.partialLevel(pitch, 1, at);
        return level >= MIN_FUNDAMENTAL && level >= .35 * track.frameLevel(at);
    }

    private static double ratio(ReconstructionLeadAudio.Track track, int pitch, int partial, int at) {
        double ratio = track.partialLevel(pitch, partial, at) / track.partialLevel(pitch, 1, at);
        return 20 * Math.log10(Math.max(.008, ratio));
    }

    private static int frame(double time) { return (int) Math.round(time / HOP); }

    private static double median(double[] values) {
        Arrays.sort(values);
        return (values[(values.length - 1) / 2] + values[values.length / 2]) / 2;
    }

    private static final class Sounding {
        private final Map<Integer, BitSet> written = new HashMap<>();

        Sounding(List<Note> notes, int frames) {
            for (var note : notes) {
                int from = Math.clamp((int) Math.ceil((note.start() - .018) / HOP), 0, frames);
                int to = Math.clamp((int) Math.ceil((note.end() + .018) / HOP), 0, frames);
                written.computeIfAbsent(note.pitch(), ignored -> new BitSet()).set(from, to);
            }
        }

        boolean contested(int pitch, int partial, int at) {
            if (contains(written, pitch, at)) return true;
            for (int h = 2; h < INTERVAL.length; h++) {
                if (h != partial && contains(written, pitch - INTERVAL[h], at)) return true;
            }
            return false;
        }

        private static boolean contains(Map<Integer, BitSet> bands, int key, int at) {
            var band = bands.get(key);
            return band != null && band.get(at);
        }
    }
}
