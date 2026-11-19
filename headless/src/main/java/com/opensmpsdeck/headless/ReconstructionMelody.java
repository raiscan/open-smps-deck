package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Keeps independent source tracks intact, and follows pitch/timing continuity inside polyphonic tracks. */
final class ReconstructionMelody {
    private static final double SUSTAIN = .100;
    private static final double RELEASE_OVERLAP = .035;
    private static final double ATTACK_WINDOW = .024;

    private record Link(int voice, int attack, double cost) {}
    private record Line(int part, List<Note> notes) {}
    private record Register(double pitch, boolean sustained) {}
    record Selection(Set<String> lead, Set<String> octaveDoubles) {}

    private ReconstructionMelody() {}

    static Selection extract(ReconstructionStructure.Result structure, List<Note> tones,
                               Map<Integer, Integer> programs) {
        var byPart = new LinkedHashMap<Integer, List<Note>>();
        for (var note : tones) byPart.computeIfAbsent(note.part(), ignored -> new ArrayList<>()).add(note);
        var lines = new ArrayList<Line>();
        var octaveCopies = new HashSet<String>();
        byPart.forEach((part, notes) -> {
            if (isMonophonic(notes)) {
                lines.add(new Line(part, notes));
            } else {
                findOctaveCopies(notes, octaveCopies);
                var distinct = notes.stream().filter(note -> !octaveCopies.contains(note.id())).toList();
                if (isMonophonic(distinct)) lines.add(new Line(part, distinct));
                else for (var line : split(distinct)) lines.add(new Line(part, line));
            }
        });
        var result = new HashSet<String>();
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

    private static boolean isMonophonic(List<Note> notes) {
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

    private static List<List<Note>> split(List<Note> notes) {
        var voices = new ArrayList<List<Note>>();
        for (int start = 0; start < notes.size();) {
            int end = start + 1;
            while (end < notes.size() && notes.get(end).start() - notes.get(start).start() <= ATTACK_WINDOW) end++;
            var group = notes.subList(start, end);
            var links = new ArrayList<Link>();
            for (int i = 0; i < voices.size(); i++) {
                Note previous = voices.get(i).getLast();
                if (group.getFirst().start() - previous.end() > .75) continue;
                for (int j = 0; j < group.size(); j++) {
                    var note = group.get(j);
                    double gap = note.start() - previous.end();
                    double releaseTolerance = Math.clamp(.20 * (previous.end() - previous.start()), .045, .075);
                    if (gap < -releaseTolerance) continue;
                    int leap = Math.abs(previous.pitch() - note.pitch());
                    double oldLength = .06 + previous.end() - previous.start();
                    double newLength = .06 + note.end() - note.start();
                    double cost = .065 * leap + 1.4 * Math.abs(gap) + .19 * Math.abs(Math.log(newLength / oldLength));
                    if (cost <= 1.05) links.add(new Link(i, j, cost));
                }
            }
            links.sort(Comparator.comparingDouble(Link::cost));
            var used = new boolean[voices.size()];
            var assigned = new boolean[group.size()];
            for (var link : links) {
                if (used[link.voice] || assigned[link.attack]) continue;
                voices.get(link.voice).add(group.get(link.attack));
                used[link.voice] = true;
                assigned[link.attack] = true;
            }
            for (int i = 0; i < group.size(); i++) if (!assigned[i]) {
                var voice = new ArrayList<Note>();
                voice.add(group.get(i));
                voices.add(voice);
            }
            start = end;
        }
        return voices;
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
