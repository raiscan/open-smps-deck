package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.ReconstructionRoles.Decision;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

final class ReconstructionPolyphonicLead {
    static final String REASON = "stem-upper-line";
    private static final double ATTACK_WINDOW = .032;
    private static final double MIN_OVERLAP = .035;
    private static final double MAX_STEP_GAP = .35;
    private static final double MAX_STEP_INTERVAL = .80;
    private static final int MAX_STEP = 5;

    private ReconstructionPolyphonicLead() {}

    static Set<String> line(List<Note> notes, ToDoubleFunction<Note> attackSupport) {
        if (ReconstructionMelody.isMonophonic(notes)) return Set.of();
        var candidates = new ArrayList<Note>();
        var salience = new HashMap<String, Double>();
        var independent = new HashSet<String>();
        int first = 0;
        for (var note : notes) {
            if (note.run() != null || note.end() - note.start() < .065) continue;
            while (notes.get(first).end() <= note.start() - ATTACK_WINDOW) first++;
            int coattacks = 0;
            boolean aboveBacking = false;
            for (int i = first; i < notes.size() && notes.get(i).start() <= note.start() + ATTACK_WINDOW; i++) {
                var other = notes.get(i);
                if (other.pitch() >= note.pitch()) continue;
                if (Math.abs(other.start() - note.start()) <= ATTACK_WINDOW) coattacks++;
                else if (other.end() - note.start() >= MIN_OVERLAP) aboveBacking = true;
            }
            if (!aboveBacking && coattacks == 0) continue;
            double support = attackSupport.applyAsDouble(note);
            // A block chord needs stronger evidence than a separate attack over a hold.
            if (support < (coattacks == 0 ? .20 : .42)) continue;
            if (coattacks == 0) independent.add(note.id());
            candidates.add(note);
            salience.put(note.id(), support);
        }
        var connected = new HashSet<String>();
        int previous = 0;
        for (int i = 0; i < candidates.size(); i++) {
            var note = candidates.get(i);
            while (candidates.get(previous).start() < note.start() - MAX_STEP_INTERVAL) previous++;
            for (int j = previous; j < i; j++) {
                var prior = candidates.get(j);
                double interval = note.start() - prior.start();
                if (interval <= ATTACK_WINDOW || note.start() - prior.end() > MAX_STEP_GAP
                        || Math.abs(note.pitch() - prior.pitch()) > MAX_STEP) continue;
                if (prior.pitch() == note.pitch() && !independent.contains(prior.id())
                        && !independent.contains(note.id())) continue;
                connected.add(prior.id());
                connected.add(note.id());
            }
        }
        var phrase = candidates.stream().filter(note -> connected.contains(note.id())).toList();
        return ReconstructionMelody.trackUpperLine(phrase, salience).stream().map(Note::id).collect(Collectors.toSet());
    }

    static ReconstructionRoles.Result prioritize(ReconstructionRoles.Result roles, Set<String> line,
                                                  Set<String> authored, Integer leadPart) {
        if (line.isEmpty()) return roles;
        var changes = new HashMap<String, Decision>();
        var primary = roles.notes().stream().filter(row -> row.role() == Role.melody).toList();
        for (var row : roles.notes()) {
            var note = row.note();
            if (!line.contains(note.id()) || authored.contains(note.id()) || row.mergedInto() != null
                    || row.role() != Role.harmony && row.role() != Role.counter) continue;
            var overlaps = primary.stream().filter(other -> other.note().start() < note.end()
                    && other.note().end() > note.start()).toList();
            boolean mainPart = leadPart == null ? overlaps.stream().allMatch(other -> other.note().part() == note.part())
                    : leadPart == note.part();
            if (mainPart && overlaps.stream().anyMatch(other -> authored.contains(other.note().id())
                    || other.note().run() != null || other.note().pitch() >= note.pitch() || line.contains(other.note().id())))
                continue;
            if (mainPart) for (var other : overlaps) {
                changes.put(other.note().id(), new Decision(other.note(), Role.harmony, "under-stem-upper-line", null));
            }
            changes.put(note.id(), new Decision(note, mainPart ? Role.melody : Role.counter, REASON, null));
        }
        if (changes.isEmpty()) return roles;
        return new ReconstructionRoles.Result(roles.version(), roles.harmonyMaxHz(),
                roles.notes().stream().map(row -> changes.getOrDefault(row.note().id(), row)).toList(),
                roles.timeBase(), roles.structureNoteHashes(), roles.lanePins(), roles.repeatNotes());
    }
}
