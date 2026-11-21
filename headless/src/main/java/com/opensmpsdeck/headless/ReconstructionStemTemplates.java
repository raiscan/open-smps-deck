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
import java.util.stream.Collectors;

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
    private static final int DEFAULT_MAX_PITCH = 96; // C7
    private static final int REPORT_MAX_PITCH = 106;
    private static final int PASSAGE_SECONDS = 16;
    private static final int PROPOSALS_PER_PASSAGE = 3;
    private static final Comparator<Candidate> RANK = Comparator
            .comparing((Candidate candidate) -> candidate.automatic()
                    || candidate.support().reviewable(candidate.independentAttack())).reversed()
            .thenComparing(Comparator.comparingDouble(Candidate::confidence).reversed())
            .thenComparing(Candidate::id);

    record Support(boolean inRange, double phaseExcessDb, double isolatedExcessDb,
                   boolean separatePitch, boolean early, boolean late, boolean motionLocked, double envelopeChangeDb) {
        boolean detached() { return separatePitch || early || late; }

        boolean reviewable(boolean attack) {
            return inRange && !motionLocked && phaseExcessDb >= 5 && isolatedExcessDb >= 3
                    && (detached() || envelopeChangeDb >= 7 || attack && isolatedExcessDb >= 8);
        }
    }

    record Candidate(String id, String anchorId, int part, int anchorPitch, int pitch, int partial, double start,
                     double end, int velocity, double confidence, double observedDb, double templateDb,
                     double excessDb, double templateSpreadDb, int trainingNotes, boolean localTemplate, boolean independentAttack,
                     boolean automatic, @ReconstructionStages.Nullable Support support) {
        Note note() {
            return new Note(id, part, start, end, pitch, velocity, SOURCE, confidence);
        }

        ReconstructionEdits.Intent proposal() {
            var edit = new JsonObject();
            edit.addProperty("id", id);
            edit.addProperty("op", "insertNote");
            edit.addProperty("group", SOURCE);
            edit.addProperty("reason", "Stem partial has evidence of a separate note");
            edit.addProperty("part", part);
            edit.addProperty("start", start);
            edit.addProperty("end", end);
            edit.addProperty("pitch", pitch);
            edit.addProperty("velocity", velocity);
            var evidence = new JsonObject();
            evidence.addProperty("text", String.format(Locale.ROOT,
                    "Partial %d of %s has separate pitch or timing evidence, with %.1f dB of phase-matched excess and %.1f dB after accounting for adjacent partials",
                    partial, anchorId, support.phaseExcessDb(), support.isolatedExcessDb()));
            evidence.addProperty("path", FILE);
            edit.add("evidence", evidence);
            return ReconstructionEdits.parse(ReconstructionProposals.proposed(edit), FILE);
        }
    }

    private record Report(int version, List<Candidate> candidates, List<String> issuedProposals,
                          @ReconstructionStages.Nullable List<String> shortlistedProposals) {}
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
        var shortlisted = shortlist(candidates);
        var selectedIds = shortlisted.stream().map(Candidate::id).collect(Collectors.toSet());
        var intents = new ArrayList<>(journal.intents());
        // Retranscription also replaces the old broad queue. Accepted and
        // unrelated edits stay in the journal, and rejected IDs stay issued.
        intents.removeIf(intent -> intent.proposed() && !selectedIds.contains(intent.id())
                && issued.remove(intent.id()));
        var existing = new HashSet<String>();
        intents.forEach(intent -> existing.add(intent.id()));
        for (var candidate : shortlisted) {
            if (issued.add(candidate.id()) && existing.add(candidate.id()))
                intents.add(candidate.proposal());
        }
        if (!intents.equals(journal.intents())) {
            journal = new ReconstructionEdits(intents);
            journal.write(work);
        }
        // A rejected proposal is absent from edits.json. Keep its issued ID
        // so a retranscribe or manifest replay does not reopen it.
        ReconstructionArtifacts.write(file, new Report(ReconstructionStages.VERSION, candidates,
                issued.stream().sorted().toList(), shortlisted.stream().map(Candidate::id).toList()));
        return journal;
    }

    private static List<Candidate> shortlist(List<Candidate> candidates) {
        var passages = new HashMap<Integer, Integer>();
        var selected = new ArrayList<Candidate>();
        candidates.stream().filter(candidate -> !candidate.automatic()
                        && candidate.support().reviewable(candidate.independentAttack()))
                .sorted(RANK).forEach(candidate -> {
                    int passage = (int) (candidate.start() / PASSAGE_SECONDS);
                    if (passages.getOrDefault(passage, 0) >= PROPOSALS_PER_PASSAGE) return;
                    boolean duplicate = selected.stream().anyMatch(other -> other.part() == candidate.part()
                            && other.pitch() == candidate.pitch() && other.anchorId().equals(candidate.anchorId())
                            && other.start() <= candidate.end() + 2 * HOP
                            && candidate.start() <= other.end() + 2 * HOP);
                    if (!duplicate) {
                        selected.add(candidate);
                        passages.merge(passage, 1, Integer::sum);
                    }
                });
        return selected;
    }

    static List<Candidate> detect(ReferenceAudio audio, List<Note> anchors, List<Note> occupancy) {
        if (anchors.size() < 4) return List.of();
        var track = new ReconstructionLeadAudio.Track(audio);
        var examples = learn(track, anchors, occupancy);
        if (examples.size() < 4) return List.of();
        var registers = new HashMap<Integer, Template[]>();
        var registerExamples = new HashMap<Integer, List<Example>>();
        var exact = new HashMap<Integer, NavigableMap<Double, Example>>();
        for (var example : examples) {
            var note = example.note();
            exact.computeIfAbsent(note.pitch(), ignored -> new TreeMap<>()).put(note.start(), example);
        }
        var sounding = new Sounding(occupancy, track.frames());
        var found = new ArrayList<Candidate>();
        int maxPitch = Math.max(DEFAULT_MAX_PITCH, anchors.stream().mapToInt(Note::pitch).max().orElseThrow());
        for (var anchor : anchors) {
            var samePitch = exact.getOrDefault(anchor.pitch(), java.util.Collections.emptyNavigableMap());
            var nearby = samePitch.subMap(anchor.start() - 2, true, anchor.start() + 2, true).values().stream()
                    .filter(example -> !example.note().id().equals(anchor.id())).toList();
            var register = registerExamples.computeIfAbsent(anchor.pitch(), pitch -> examples.stream()
                    .filter(example -> Math.abs(example.note().pitch() - pitch) <= 5).toList());
            // Repeated local notes at the same pitch are the closest control.
            var byPartial = nearby.size() >= 2 ? templates(nearby, true)
                    : registers.computeIfAbsent(anchor.pitch(), pitch ->
                            templates(samePitch.size() >= 4 ? List.copyOf(samePitch.values()) : register, false));
            if (byPartial[2] == null) continue;
            var controls = nearby.size() >= 2 ? nearby : register;
            var comparable = controls.stream().filter(example -> !example.note().id().equals(anchor.id())).sorted(Comparator.comparingDouble(
                            (Example example) -> controlDistance(anchor, example.note()))
                            .thenComparing(example -> example.note().id())).limit(8).toList();
            for (int h = 2; h <= 4; h++) {
                scan(track, sounding, anchor, h, byPartial, comparable, maxPitch, found);
            }
        }
        // The same ridge can be explained by more than one written partial.
        var retained = new ArrayList<Candidate>();
        var claimed = new HashMap<Integer, BitSet>();
        found.sort(RANK);
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
                             Template[] templates, List<Example> controls, int maxPitch, List<Candidate> found) {
        int pitch = anchor.pitch() + INTERVAL[partial];
        if (pitch > REPORT_MAX_PITCH) return;
        Template template = templates[partial];
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
                var support = support(track, sounding, anchor, partial, first, at, controls,
                        partial <= 3 && pitch <= maxPitch);
                boolean automatic = support.inRange() && !support.motionLocked() && attack && count >= 7 && observed >= -8
                        && excess >= 12 + template.spread() && template.count() >= 4
                        && support.phaseExcessDb() >= 15 && support.isolatedExcessDb() >= 14;
                double confidence = automatic
                        ? Math.min(.99, .90 + (support.isolatedExcessDb() - 14) / 100)
                        : proposalConfidence(support, attack);
                String id = SOURCE + "-" + anchor.part() + "-" + pitch + "-" + first;
                double start = Math.max(anchor.start(), (first - .5) * HOP);
                double end = Math.min(anchor.end(), (at - .5) * HOP);
                int velocity = Math.clamp((int) Math.round(anchor.velocity() * Math.pow(10, observed / 40)), 1, 127);
                found.add(new Candidate(id, anchor.id(), anchor.part(), anchor.pitch(), pitch, partial, start, end,
                        velocity, confidence, observed, template.db(), excess, template.spread(), template.count(),
                        template.local(), attack, automatic, support));
            }
            first = at + 1;
            total = 0;
        }
    }

    private static double controlDistance(Note anchor, Note control) {
        double durationRatio = (control.end() - control.start()) / (anchor.end() - anchor.start());
        return .4 * Math.abs(control.pitch() - anchor.pitch()) + Math.abs(Math.log(durationRatio))
                + Math.abs(control.velocity() - anchor.velocity()) / 20.0
                + Math.abs(control.start() - anchor.start()) / PASSAGE_SECONDS;
    }

    private static Support support(ReconstructionLeadAudio.Track track, Sounding sounding, Note anchor, int partial,
                                   int from, int to, List<Example> controls, boolean inRange) {
        int pitch = anchor.pitch();
        int count = to - from;
        // The fourth is itself a harmonic of a real octave doubling.
        int[] neighbors = partial == 3 ? new int[]{2, 4} : new int[]{3};
        var phaseExcess = new double[count];
        var isolatedExcess = new double[count];
        var levels = new double[count];
        var fundamentals = new double[count];
        var offsets = new double[count];
        var sampleFrames = new int[controls.size()];
        int above = 0, below = 0, matched = 0;

        for (int i = 0; i < count; i++) {
            int at = from + i;
            levels[i] = track.partialLevel(pitch, partial, at);
            fundamentals[i] = track.partialLevel(pitch, 1, at);
            offsets[i] = track.partialCents(pitch, partial, at);
            double difference = offsets[i] - track.partialCents(pitch, 1, at);
            if (Math.abs(offsets[i]) <= 19) {
                if (difference >= 20) above++;
                if (difference <= -20) below++;
            }

            // Filter envelopes evolve over a note. Compare the same relative
            // position in duration- and velocity-matched isolated controls.
            double phase = (at * HOP - anchor.start()) / (anchor.end() - anchor.start());
            int usable = 0;
            for (int j = 0; j < controls.size(); j++) {
                var note = controls.get(j).note();
                int sample = frame(note.start() + phase * (note.end() - note.start()));
                boolean valid = strongFundamental(track, note.pitch(), sample);
                sampleFrames[j] = valid ? sample : -1;
                if (valid) usable++;
            }
            if (usable < 2) continue;
            double excess = ratio(track, pitch, partial, at)
                    - phaseTemplate(track, controls, sampleFrames, usable, partial);
            double brightness = 0;
            for (int neighbor : neighbors) {
                double rise = ratio(track, pitch, neighbor, at)
                        - phaseTemplate(track, controls, sampleFrames, usable, neighbor);
                brightness = Math.max(brightness, rise);
            }
            phaseExcess[matched] = excess;
            isolatedExcess[matched++] = excess - brightness;
        }
        boolean separatePitch = Math.max(above, below) >= Math.max(MIN_FRAMES, .7 * count);
        double level = median(levels);
        double fundamental = median(fundamentals);
        double cents = median(offsets);
        int before = (int) Math.floor((anchor.start() - .03) / HOP);
        int after = (int) Math.ceil((anchor.end() + .03) / HOP);
        boolean early = from * HOP <= anchor.start() + .04
                && detachedRidge(track, sounding, pitch, partial, neighbors, before, -1, level, fundamental, cents);
        boolean late = to * HOP >= anchor.end() - .04
                && detachedRidge(track, sounding, pitch, partial, neighbors, after, 1, level, fundamental, cents);
        double envelope = envelopeChange(track, pitch, partial, neighbors, from, to);
        boolean locked = motionLocked(track, pitch, partial, from, to, level);
        double matchedExcess = matched * 2 >= count ? median(Arrays.copyOf(phaseExcess, matched)) : 0;
        double matchedContrast = matched * 2 >= count ? median(Arrays.copyOf(isolatedExcess, matched)) : 0;
        return new Support(inRange, matchedExcess, matchedContrast, separatePitch, early, late, locked, envelope);
    }

    private static double phaseTemplate(ReconstructionLeadAudio.Track track, List<Example> controls, int[] frames,
                                        int usable, int partial) {
        var values = new double[usable];
        int i = 0;
        for (int j = 0; j < frames.length; j++) {
            if (frames[j] >= 0) values[i++] = ratio(track, controls.get(j).note().pitch(), partial, frames[j]);
        }
        return median(values);
    }

    private static boolean detachedRidge(ReconstructionLeadAudio.Track track, Sounding sounding, int pitch, int partial,
                                         int[] neighbors, int frame, int step, double level, double fundamental, double cents) {
        int consecutive = 0;
        for (int i = 0; i < 7; i++, frame += step) {
            if (frame < 0 || frame >= track.frames()) break;
            double current = track.partialLevel(pitch, partial, frame);
            boolean separate = current >= Math.max(.004, .45 * level)
                    && Math.abs(track.partialCents(pitch, partial, frame) - cents) < 18
                    && track.partialLevel(pitch, 1, frame) < .33 * fundamental
                    && !sounding.sustains(pitch, frame)
                    && !sounding.contested(pitch + INTERVAL[partial], partial, frame);
            for (int neighbor : neighbors) {
                separate &= track.partialLevel(pitch, neighbor, frame) < .4 * current;
            }
            if (!separate && i > 0) break;
            if (separate && ++consecutive >= MIN_FRAMES) return true;
        }
        return false;
    }

    private static boolean motionLocked(ReconstructionLeadAudio.Track track, int pitch, int partial,
                                        int from, int to, double level) {
        int paired = 0, locked = 0;
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (int at = from - 3; at < to + 3; at++) {
            if (track.partialLevel(pitch, 1, at) < MIN_FUNDAMENTAL
                    || track.partialLevel(pitch, partial, at) < .35 * level) continue;
            double fundamental = track.partialCents(pitch, 1, at);
            low = Math.min(low, fundamental);
            high = Math.max(high, fundamental);
            if (Math.abs(track.partialCents(pitch, partial, at) - fundamental) <= 6) locked++;
            paired++;
        }
        // Matching a real bend or vibrato is stronger evidence of a harmonic
        // than both pitches simply being in tune on a held note.
        return paired >= 5 && high - low >= 12 && locked >= .8 * paired;
    }

    private static double envelopeChange(ReconstructionLeadAudio.Track track, int pitch, int partial, int[] neighbors,
                                         int from, int to) {
        double change = 0;
        for (int distance : new int[]{4, 6}) {
            for (int i = 0; i < Math.min(4, to - from); i++) {
                int onset = from + i;
                int release = to - 1 - i;
                change = Math.max(change, isolatedEdge(track, pitch, partial, neighbors, onset - distance, onset));
                change = Math.max(change, isolatedEdge(track, pitch, partial, neighbors, release + distance, release));
            }
        }
        return change;
    }

    private static double isolatedEdge(ReconstructionLeadAudio.Track track, int pitch, int partial, int[] neighbors,
                                       int quiet, int loud) {
        double peak = smoothedLevel(track, pitch, partial, loud);
        double change = levelChange(peak, smoothedLevel(track, pitch, partial, quiet));
        if (change < 7 || peak < .006) return 0;
        double fundamental = smoothedLevel(track, pitch, 1, loud);
        double priorFundamental = smoothedLevel(track, pitch, 1, quiet);
        if (Math.min(fundamental, priorFundamental) < MIN_FUNDAMENTAL
                || Math.abs(levelChange(fundamental, priorFundamental)) > 5) return 0;
        double minCents = Double.POSITIVE_INFINITY, maxCents = Double.NEGATIVE_INFINITY;
        for (int at = loud - 1; at <= loud + 1; at++) {
            if (track.partialLevel(pitch, partial, at) < .004) return 0;
            double cents = track.partialCents(pitch, partial, at);
            minCents = Math.min(minCents, cents);
            maxCents = Math.max(maxCents, cents);
        }
        if (maxCents - minCents > 17) return 0;
        for (int neighbor : neighbors) {
            double level = smoothedLevel(track, pitch, neighbor, loud);
            if (level > .15 * peak && levelChange(level, smoothedLevel(track, pitch, neighbor, quiet))
                    > Math.min(change - 6, 7)) return 0;
        }
        return change;
    }

    private static double smoothedLevel(ReconstructionLeadAudio.Track track, int pitch, int partial, int at) {
        double a = track.partialLevel(pitch, partial, at - 1);
        double b = track.partialLevel(pitch, partial, at);
        double c = track.partialLevel(pitch, partial, at + 1);
        return Math.clamp(b, Math.min(a, c), Math.max(a, c));
    }

    private static double levelChange(double current, double previous) {
        return 20 * Math.log10(Math.max(.002, current) / Math.max(.002, previous));
    }

    private static double proposalConfidence(Support support, boolean attack) {
        double excess = Math.max(0, support.isolatedExcessDb());
        return .50 + .19 * excess / (14 + excess) + (attack || support.detached() ? .12 : 0)
                + (support.separatePitch() ? .06 : 0);
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

        boolean sustains(int pitch, int at) { return contains(written, pitch, at); }

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
