package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionStages.NOTE_ORDER;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.Score;

/** Recovers a rise into a stable written pitch before allocation splits it across lanes. */
final class ReconstructionBendRecovery {
    static final String GROUP = "stem-pitch-approach";
    private static final String PREFIX = GROUP + "-";
    private static final double FRAGMENT_MAX = .145;
    private static final double TIME_SLOP = .018;

    private ReconstructionBendRecovery() {}

    static boolean generated(String id) { return id.startsWith(PREFIX); }

    record Candidate(List<Note> members, double start, ReconstructionBends.Approach approach, boolean automatic) {
        Note landing() { return members.getLast(); }

        String id() { return PREFIX + landing().id(); }

        ReconstructionEdits.Intent intent() {
            var note = landing();
            var bend = approach.bend(start, note.end());
            var edit = new JsonObject();
            edit.addProperty("id", id());
            edit.addProperty("group", GROUP);
            edit.addProperty("reason", "Continuous rise in the mapped stem reaches the written pitch");
            if (members.size() == 1) {
                edit.addProperty("op", "bend");
                edit.addProperty("noteId", note.id());
                if (start != note.start()) edit.addProperty("start", start);
            } else {
                edit.addProperty("op", "mergeNotes");
                edit.addProperty("glide", true);
                var ids = new JsonArray();
                for (var member : members) ids.add(member.id());
                edit.add("noteIds", ids);
            }
            var points = new JsonArray();
            for (var point : bend.points()) {
                var pair = new JsonArray();
                pair.add(point.time());
                pair.add(point.cents() / 100.0);
                points.add(pair);
            }
            edit.add("points", points);
            var evidence = new JsonObject();
            evidence.addProperty("text", String.format(Locale.ROOT,
                    "Part %d rises %.1f semitones in %.0f ms, reaching MIDI pitch %d at source %.3f s",
                    note.part(), -bend.points().getFirst().cents() / 100.0, (approach.landing() - start) * 1000,
                    note.pitch(), approach.landing()));
            evidence.addProperty("path", "score.json");
            edit.add("evidence", evidence);
            return ReconstructionEdits.parse(ReconstructionProposals.proposed(edit), ReconstructionEdits.FILE);
        }
    }

    static List<Candidate> detect(ReferenceAudio audio, List<Note> part, boolean eligibleForAutomatic) {
        var sorted = part.stream().sorted(NOTE_ORDER.thenComparing(Note::id)).toList();
        var tracker = new ReconstructionBends.Tracker(audio);
        var candidates = new ArrayList<Candidate>();
        double[] endThrough = new double[sorted.size()];
        double latestEnd = 0;
        for (int i = 0; i < sorted.size(); i++) {
            latestEnd = Math.max(latestEnd, sorted.get(i).end());
            endThrough[i] = latestEnd;
        }
        for (int i = 0; i < sorted.size(); i++) {
            var landing = sorted.get(i);
            if ("external".equals(landing.source())) continue;
            var approach = tracker.approach(landing);
            if (approach == null) continue;
            var members = new ArrayList<Note>();
            boolean separate = false;
            double earliest = approach.start() - TIME_SLOP;
            // Keep a long preceding gate intact. Short fragments must actually
            // sit on the measured ridge during their own written gate.
            for (int j = i - 1; j >= 0 && endThrough[j] > earliest; j--) {
                var note = sorted.get(j);
                if (note.end() <= earliest || note.pitch() >= landing.pitch()) continue;
                if (!onRidge(note, landing.pitch(), approach)) continue;
                if (note.start() < earliest || note.end() - note.start() > FRAGMENT_MAX
                        || note.end() > approach.landing() + TIME_SLOP
                        || note.run() != null || "external".equals(note.source())) {
                    separate = true;
                } else {
                    members.add(note);
                }
            }
            if (separate) continue;
            members = new ArrayList<>(members.reversed());
            members.add(landing);
            if (members.size() == 1 && landing.bend() != null) continue;
            double estimated = Math.max(0, approach.start() - .007);
            double start = members.size() > 1 ? members.getFirst().start()
                    : Math.abs(estimated - landing.start()) <= .012 ? landing.start() : estimated;
            if (Math.abs(start - approach.start()) > .035 || approach.landing() - start < .022) continue;
            boolean ordered = true;
            for (int j = 1; j < members.size(); j++) {
                var left = members.get(j - 1);
                var right = members.get(j);
                if (left.pitch() >= right.pitch() || Math.abs(left.end() - right.start()) > .040
                        || attackDip(audio, right.start())) {
                    ordered = false;
                    break;
                }
            }
            // A large pickup with incomplete fragment evidence is reviewable,
            // but cannot silently introduce an earlier overlapping attack.
            if (!ordered) continue;
            boolean covered = members.size() >= 3 || members.size() == 1 && Math.abs(landing.start() - start) <= .027;
            boolean automatic = eligibleForAutomatic && covered && approach.strong();
            candidates.add(new Candidate(List.copyOf(members), start, approach, automatic));
        }
        return candidates.stream().filter(candidate -> candidates.stream().noneMatch(root ->
                        root.automatic() && harmonicCopy(root, candidate)))
                .sorted(java.util.Comparator.comparingInt((Candidate candidate) -> candidate.members().size()).reversed()
                        .thenComparingDouble(Candidate::start)).toList();
    }

    private static boolean attackDip(ReferenceAudio audio, double boundary) {
        // A dip between two energized gates suggests a new attack. Check
        // near the written boundary because transcription timing can drift.
        double flank = Math.min(power(audio, boundary - .022), power(audio, boundary + .022));
        if (flank <= 0) return false;
        for (int hop = -2; hop <= 2; hop++) {
            if (power(audio, boundary + hop * .006) < flank * .25) return true;
        }
        return false;
    }

    private static double power(ReferenceAudio audio, double time) {
        int radius = Math.max(1, (int) Math.round(.004 * audio.sampleRate()));
        int center = (int) Math.round(time * audio.sampleRate());
        if (center < radius || center + radius > audio.samples().length) return 0;
        double square = 0;
        for (int i = center - radius; i < center + radius; i++) {
            double sample = audio.samples()[i];
            square += sample * sample;
        }
        return square / (2 * radius);
    }

    private static boolean harmonicCopy(Candidate root, Candidate upper) {
        int interval = upper.landing().pitch() - root.landing().pitch();
        return (interval == 12 || interval == 19 || interval == 24 || interval == 28)
                && Math.abs(root.approach().start() - upper.approach().start()) < .022
                && Math.abs(root.approach().landing() - upper.approach().landing()) < .022
                && Math.abs(root.approach().samples().getFirst().cents()
                        - upper.approach().samples().getFirst().cents()) < 55;
    }

    private static boolean onRidge(Note note, int targetPitch, ReconstructionBends.Approach approach) {
        double cents = (note.pitch() - targetPitch) * 100;
        return approach.samples().stream().anyMatch(sample ->
                sample.time() >= note.start() - .006 && sample.time() <= note.end() + .006
                        && Math.abs(sample.cents() - cents) <= 66);
    }

    static Score apply(Path work, Score score, ReconstructionEdits authored, ReconstructionEdits journal,
                       List<Candidate> candidates, List<ReconstructionEdits.Outcome> outcomes) throws IOException {
        if (candidates.isEmpty()) return authored.applyScore(work, score, outcomes);
        var timeline = ReconstructionTimeMap.of(score);
        var existing = journal.intents().stream().map(ReconstructionEdits.Intent::id)
                .collect(java.util.stream.Collectors.toSet());
        var selected = new ArrayList<>(authored.intents());
        var claimed = new HashSet<String>();
        for (var candidate : candidates) {
            if (!candidate.automatic() || existing.contains(candidate.id())) continue;
            if (candidate.members().stream().anyMatch(note -> claimed.contains(note.id())
                    || authored.intents().stream().anyMatch(edit -> ReconstructionEdits.targets(edit, note, timeline))))
                continue;
            selected.add(candidate.intent().accept());
            candidate.members().forEach(note -> claimed.add(note.id()));
        }
        return new ReconstructionEdits(selected).applyScore(work, score, outcomes);
    }

    static ReconstructionEdits publish(Path work, ReconstructionEdits journal, List<Candidate> candidates,
                                       List<ReconstructionEdits.Outcome> outcomes) throws IOException {
        if (candidates.isEmpty() && journal.intents().stream().noneMatch(edit -> generated(edit.id()))) return journal;
        Set<String> written = new HashSet<>();
        for (var outcome : outcomes) if (outcome.status() == ReconstructionEdits.Status.applied) {
            written.addAll(outcome.noteIds());
            if (outcome.removedNotes() != null)
                outcome.removedNotes().forEach(note -> written.add(note.id()));
        }
        var eligible = candidates.stream().filter(candidate -> candidate.members().stream()
                .noneMatch(note -> written.contains(note.id()))).toList();
        var ids = new HashSet<String>();
        for (var candidate : eligible) ids.add(candidate.id());
        var updated = new ArrayList<>(journal.intents());
        updated.removeIf(edit -> edit.proposed() && generated(edit.id()) && !ids.contains(edit.id()));
        var existing = new HashSet<String>();
        updated.forEach(edit -> existing.add(edit.id()));
        for (var candidate : eligible) if (existing.add(candidate.id())) updated.add(candidate.intent());
        if (updated.equals(journal.intents())) return journal;
        var result = new ReconstructionEdits(updated);
        result.write(work);
        return result;
    }
}
