package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Keeps independent source tracks intact, and follows pitch/timing continuity inside polyphonic tracks. */
final class ReconstructionMelody {
    private static final double SUSTAIN = .100;
    private static final double RELEASE_OVERLAP = .035;
    private static final double ATTACK_WINDOW = .024;

    private static final double PATH_CONTEXT = 1.25;
    private static final double REGISTER_CONTEXT = .35;
    private static final double GATE_CAP = .24;
    private static final double PHRASE_RESTART = .08;

    private record Line(int part, List<Note> notes) {}
    private record Register(double pitch, boolean sustained) {}
    record Selection(Set<String> lead, Set<String> octaveDoubles) {}

    private ReconstructionMelody() {}

    static Selection extract(ReconstructionStructure.Layout structure, List<Note> tones,
                               Map<Integer, Integer> programs, Map<String, ReconstructionLeadAudio.Evidence> evidence,
                               Map<String, Double> accents, Integer leadPart) {
        var byPart = new LinkedHashMap<Integer, List<Note>>();
        for (var note : tones) {
            if (leadPart == null || note.part() == leadPart)
                byPart.computeIfAbsent(note.part(), ignored -> new ArrayList<>()).add(note);
        }
        var lines = new ArrayList<Line>();
        var octaveCopies = new HashSet<String>();
        byPart.forEach((part, notes) -> {
            if (isMonophonic(notes)) {
                lines.add(new Line(part, notes));
            } else {
                findOctaveCopies(notes, octaveCopies);
                var distinct = notes.stream().filter(note -> !octaveCopies.contains(note.id())).toList();
                if (isMonophonic(distinct)) lines.add(new Line(part, distinct));
                else lines.add(new Line(part, track(distinct, false, evidence, accents)));
            }
        });
        var result = new HashSet<String>();
        if (leadPart != null) {
            for (var line : lines) for (var note : line.notes) result.add(note.id());
            return new Selection(result, octaveCopies);
        }
        var bars = new LinkedHashMap<String, ReconstructionStructure.Bar>();
        for (var bar : structure.bars()) bars.put(bar.id(), bar);
        var activeByBar = liveNotesByBar(structure.bars(), lines);
        for (var section : structure.sections()) {
            int previous = -1;
            for (String id : section.bars()) {
                var bar = bars.get(id);
                var active = activeByBar.get(id);
                double bestScore = Double.NEGATIVE_INFINITY;
                boolean bestSustained = false;
                int best = -1;
                for (var entry : active.entrySet()) {
                    int index = entry.getKey();
                    var line = lines.get(index);
                    var register = register(bar, entry.getValue());
                    int program = programs.get(line.part);
                    double score = register.pitch + (index == previous ? 1.0 : 0)
                            + (program >= 80 && program <= 87 ? 2 : 0)
                            - (program >= 32 && program <= 39 ? 5 : 0);
                    if (!evidence.isEmpty()) score += 2.5 * motion(bar, entry.getValue(), evidence);
                    if (bestSustained && !register.sustained) continue;
                    if (best < 0 || register.sustained && !bestSustained || score > bestScore) {
                        best = index;
                        bestScore = score;
                        bestSustained = register.sustained;
                    }
                }
                if (best >= 0) {
                    for (var note : active.get(best))
                        if (note.start() >= bar.start()) result.add(note.id());
                    previous = best;
                }
            }
        }
        return new Selection(result, octaveCopies);
    }

    private static Map<String, Map<Integer, List<Note>>> liveNotesByBar(List<ReconstructionStructure.Bar> bars, List<Line> lines) {
        var result = new LinkedHashMap<String, Map<Integer, List<Note>>>();
        for (var bar : bars) result.put(bar.id(), new LinkedHashMap<>());
        for (int i = 0; i < lines.size(); i++) {
            int index = 0;
            for (var note : lines.get(i).notes) {
                while (bars.get(index).end() <= note.start()) index++;
                for (int b = index; b < bars.size() && (b == index || bars.get(b).start() < note.end()); b++) {
                    result.get(bars.get(b).id()).computeIfAbsent(i, ignored -> new ArrayList<>()).add(note);
                }
            }
        }
        return result;
    }

    private static double motion(ReconstructionStructure.Bar bar, List<Note> notes,
                                 Map<String, ReconstructionLeadAudio.Evidence> evidence) {
        double total = 0, weighted = 0;
        for (var note : notes) {
            double duration = Math.max(.001, Math.min(bar.end(), note.end()) - Math.max(bar.start(), note.start()));
            total += duration;
            weighted += duration * evidence.getOrDefault(note.id(), ReconstructionLeadAudio.Evidence.NONE).confidence();
        }
        return weighted / total;
    }

    private static Register register(ReconstructionStructure.Bar bar, List<Note> notes) {
        double total = 0, weighted = 0, sustained = 0, weightedSustained = 0;
        for (var note : notes) {
            double duration = Math.max(.001, Math.min(bar.end(), note.end()) - Math.max(bar.start(), note.start()));
            double weight = duration * (.80 + .20 * note.velocity() / 127.0) * (.85 + .15 * note.confidence());
            int pitch = note.pitch();
            total += weight;
            weighted += pitch * weight;
            if (duration + 1e-7 >= SUSTAIN) {
                sustained += weight;
                weightedSustained += pitch * weight;
            }
        }
        // Keep a sparse line eligible, but discount a brief appearance against an established line.
        double isolated = notes.size() <= 2 ? Math.max(0, .40 - total) * 20 : 0;
        return new Register((sustained > 0 ? weightedSustained / sustained : weighted / total) - isolated, sustained > 0);
    }

    static boolean isMonophonic(List<Note> notes) {
        int clashes = 0;
        double release = 0;
        double onset = -1;
        for (var note : notes) {
            if (note.start() - onset < .016 || note.start() < release - RELEASE_OVERLAP) clashes++;
            release = Math.max(release, note.end());
            onset = note.start();
        }
        return clashes * 30 <= notes.size();
    }

    /**
     * Best monophonic path through the sorted attacks. A path can skip a note or
     * leave silence. When it cuts a release, only the played gate earns credit.
     */
    static List<Note> track(List<Note> notes, boolean low, Map<String, ReconstructionLeadAudio.Evidence> evidence,
                            Map<String, Double> accents) {
        if (notes.isEmpty() || isMonophonic(notes)) return notes;
        var contour = trackPath(notes, low, evidence, Map.of(), null);
        if (low || accents.isEmpty()) return contour;
        var selected = new HashSet<String>();
        for (var note : contour) selected.add(note.id());
        int first = 0;
        for (var note : notes) {
            if (selected.contains(note.id()) || !accents.containsKey(note.id())) continue;
            while (first < contour.size() && contour.get(first).end() <= note.start()) first++;
            if (canReplace(note, contour, first)) selected.add(note.id());
        }
        return selected.size() == contour.size() ? contour : trackPath(notes, false, evidence, accents, selected);
    }

    static List<Note> trackUpperLine(List<Note> notes, Map<String, Double> salience) {
        return trackPath(notes, false, Map.of(), salience, null);
    }

    private static boolean canReplace(Note note, List<Note> contour, int first) {
        boolean replacesBacking = false;
        for (int i = first; i < contour.size() && contour.get(i).start() < note.end(); i++) {
            Note original = contour.get(i);
            if (original.end() <= note.start()) continue;
            if (note.velocity() >= original.velocity() + 18) {
                replacesBacking = true;
            } else if (note.start() < original.start() && original.end() - original.start() >= GATE_CAP
                    && note.end() > original.start() + RELEASE_OVERLAP) {
                // Keep a pickup from masking the next sustained attack of comparable velocity.
                return false;
            }
        }
        return replacesBacking;
    }

    private static List<Note> trackPath(List<Note> notes, boolean low,
                                       Map<String, ReconstructionLeadAudio.Evidence> evidence,
                                       Map<String, Double> accents, Set<String> eligible) {
        int size = notes.size();
        var releaseOrder = java.util.stream.IntStream.range(0, size)
                .filter(i -> eligible == null || eligible.contains(notes.get(i).id())).boxed()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).end())).toList();
        double[] scores = new double[size];
        double[] weights = new double[size];
        double[] accentWeights = new double[size];
        int[] previous = new int[size];
        Arrays.fill(previous, -1);
        var recent = new LinkedHashSet<Integer>();
        int expired = 0, restart = -1, best = -1;
        int firstNeighbor = 0;
        for (int i = 0; i < size; i++) {
            Note current = notes.get(i);
            if (eligible != null && !eligible.contains(current.id())) continue;
            double onset = current.start();
            while (expired < releaseOrder.size() && notes.get(releaseOrder.get(expired)).end() <= onset - PATH_CONTEXT) {
                int candidate = releaseOrder.get(expired++);
                recent.remove(candidate);
                if (restart < 0 || scores[candidate] > scores[restart]) restart = candidate;
            }
            while (notes.get(firstNeighbor).start() < onset - REGISTER_CONTEXT) firstNeighbor++;
            weights[i] = weight(current, notes, firstNeighbor, low, evidence);
            accentWeights[i] = low ? 0 : accents.getOrDefault(current.id(), 0.0);
            if (accentWeights[i] > 0 && evidence.getOrDefault(current.id(), ReconstructionLeadAudio.Evidence.NONE)
                    .vibrato() >= ReconstructionLeadAudio.MODULATED) accentWeights[i] *= 1.25;
            double reward = reward(current.end() - onset, weights[i], accentWeights[i]);
            scores[i] = reward;
            if (restart >= 0 && scores[restart] > PHRASE_RESTART) {
                scores[i] += scores[restart] - PHRASE_RESTART;
                previous[i] = restart;
            }
            for (int j : recent) {
                Note prior = notes.get(j);
                double interval = onset - prior.start();
                if (interval <= ATTACK_WINDOW || prior.startTick(Integer.MAX_VALUE) == current.startTick(Integer.MAX_VALUE))
                    continue;
                double gate = prior.end() - prior.start();
                double gap = onset - prior.end();
                if (gap < -RELEASE_OVERLAP && interval < .060) continue;
                double cut = reward(gate, weights[j], accentWeights[j])
                        - reward(Math.min(interval, gate), weights[j], accentWeights[j]);
                double cost = ReconstructionRoles.transition(prior, current, Math.clamp(gap, 0, .40))
                        + cut + Math.min(.08, .20 * Math.max(0, -gap));
                double score = scores[j] + reward - cost;
                if (score > scores[i]) {
                    scores[i] = score;
                    previous[i] = j;
                }
            }
            if (best < 0 || scores[i] > scores[best]) best = i;
            recent.add(i);
        }
        var line = new ArrayList<Note>();
        for (int i = best; i >= 0; i = previous[i]) line.add(notes.get(i));
        return line.reversed();
    }

    private static double reward(double gate, double weight, double accent) {
        return (.045 + Math.min(GATE_CAP, gate) + 2 * accent * Math.min(.75, .06 + gate)) * weight;
    }

    private static double weight(Note note, List<Note> notes, int first, boolean low,
                                 Map<String, ReconstructionLeadAudio.Evidence> evidence) {
        int edge = note.pitch();
        for (int j = first; j < notes.size() && notes.get(j).start() <= note.start() + REGISTER_CONTEXT; j++) {
            Note other = notes.get(j);
            if (other.end() < note.start() - .10) continue;
            edge = low ? Math.min(edge, other.pitch()) : Math.max(edge, other.pitch());
        }
        double register = 1 - .018 * Math.min(24, Math.abs(note.pitch() - edge));
        double motion = evidence.getOrDefault(note.id(), ReconstructionLeadAudio.Evidence.NONE).confidence();
        return register * (.80 + .20 * note.velocity() / 127.0) * (.70 + .30 * note.confidence())
                * (1 + .55 * motion);
    }

    private static void findOctaveCopies(List<Note> notes, Set<String> copies) {
        var candidates = new ArrayList<Note>();
        for (int i = 0; i < notes.size(); i++) {
            var lower = notes.get(i);
            for (int j = i + 1; j < notes.size(); j++) {
                var upper = notes.get(j);
                if (upper.start() - lower.start() > .001) break;
                if (upper.pitch() == lower.pitch() + 12 && upper.velocity() == lower.velocity()
                        && Math.abs(upper.end() - lower.end()) <= .012) {
                    candidates.add(upper);
                    break;
                }
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            boolean followsOctaves = i > 0 && candidate.start() - candidates.get(i - 1).start() < .26;
            boolean leadsOctaves = i + 1 < candidates.size() && candidates.get(i + 1).start() - candidate.start() < .26;
            if (!followsOctaves && !leadsOctaves) copies.add(candidate.id());
        }
    }
}
