package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import static com.opensmpsdeck.headless.ReconstructionRoles.Decision;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.NOTE_ORDER;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Repairs gaps between selected lead notes without moving existing lead attacks. */
final class ReconstructionLeadContinuity {
    static final String SPLIT = "lead-continuation";
    static final String ADDED = "lead-audio-added";
    static final String EXTENDED = "lead-audio-extended";
    static final String LEGATO = "lead-legato";
    private static final double MIN_HEAD = .034;
    private static final double MAX_LOOKAHEAD = .85;
    private static final double MAX_LEGATO_GAP = .350;

    record Stats(long split, long added, long extended, @ReconstructionStages.Nullable Long legato) {
        long legatoCount() { return legato == null ? 0 : legato; }

        boolean isEmpty() { return split == 0 && added == 0 && extended == 0 && legatoCount() == 0; }

        static Stats of(List<Decision> notes) {
            long split = 0, added = 0, extended = 0, legato = 0;
            for (var decision : notes) {
                if (decision.role() != Role.melody || decision.reason() == null) continue;
                for (String reason : decision.reason().split(" ")) {
                    switch (reason) {
                        case SPLIT -> split++;
                        case ADDED -> added++;
                        case EXTENDED -> extended++;
                        case LEGATO -> legato++;
                        default -> { }
                    }
                }
            }
            return new Stats(split, added, extended, legato == 0 ? null : legato);
        }

        Map<String, Long> reportCounts(boolean withLegato) {
            var counts = new LinkedHashMap<String, Long>();
            counts.put("split", split);
            counts.put("added", added);
            counts.put("extended", extended);
            if (withLegato) counts.put(LEGATO, legatoCount());
            return counts;
        }
    }

    private record Stem(ReconstructionLeadAudio.Track track, List<Note> notes) {
        List<Note> overlapping(double start, double end) {
            return notes.stream().takeWhile(note -> note.start() < end)
                    .filter(note -> note.end() > start).toList();
        }
    }

    private record Candidate(Note note, Decision held, double cost) {}

    private final Map<Integer, Stem> stems;
    private final ReconstructionTimeMap timeline;
    private final Map<String, ReconstructionLeadAudio.Evidence> evidence;
    private final Map<String, Double> accents;
    private final boolean fastRunsOff;

    private ReconstructionLeadContinuity(Map<Integer, Stem> stems, ReconstructionTimeMap timeline,
                                         Map<String, ReconstructionLeadAudio.Evidence> evidence,
                                         Map<String, Double> accents, boolean fastRunsOff) {
        this.stems = stems;
        this.timeline = timeline;
        this.evidence = evidence;
        this.accents = accents;
        this.fastRunsOff = fastRunsOff;
    }

    static ReconstructionLeadContinuity analyze(ReconstructionStages.Score score, List<Note> notes) throws IOException {
        var timeline = ReconstructionTimeMap.of(score);
        var evidence = new HashMap<String, ReconstructionLeadAudio.Evidence>();
        var stems = new HashMap<Integer, Stem>();
        var accents = new HashMap<String, Double>();
        for (var part : score.source().parts()) {
            String path = score.inputs().stems().get(part.id());
            if (part.drums() || path == null) continue;
            var local = notes.stream().filter(note -> note.part() == part.id()).map(timeline::sourceNote).toList();
            if (local.isEmpty()) continue;
            var track = ReconstructionLeadAudio.Track.read(Path.of(path), timeline.sourceDuration());
            var stem = new Stem(track, local);
            stems.put(part.id(), stem);
            for (var note : local) {
                if (note.run() != null) continue;
                double trim = Math.clamp((note.end() - note.start() - .150) / 2, 0, .020);
                var overlapping = stem.overlapping(note.start(), note.end());
                var motion = track.evidence(note.pitch(), note.start() + trim, note.end() - trim,
                        overlapping);
                if (motion.confidence() > 0) evidence.put(note.id(), motion);
                // Accent is evidence only against an actually overlapping, softer voice.
                int softest = overlapping.stream()
                        .filter(other -> other.pitch() != note.pitch()
                                && Math.min(note.end(), other.end()) - Math.max(note.start(), other.start())
                                        >= Math.min(.040, (note.end() - note.start()) / 2))
                        .mapToInt(Note::velocity).min().orElse(note.velocity());
                double contrast = Math.clamp((note.velocity() - softest - 20) / 20.0, 0, 1);
                if (contrast > 0) {
                    boolean modulated = motion.vibrato() >= ReconstructionLeadAudio.MODULATED;
                    double level = track.independentLevel(note, overlapping, modulated);
                    if (level >= .30) accents.put(note.id(), contrast * Math.min(1, level / .50));
                }
            }
        }
        return new ReconstructionLeadContinuity(stems, timeline, evidence, accents, Boolean.TRUE.equals(score.inputs().fastRunsOff()));
    }

    Map<String, ReconstructionLeadAudio.Evidence> evidence() { return evidence; }

    Map<String, Double> accents() { return accents; }

    /**
     * Resonant leaps can make the lead path skip two attacks of a single articulated phrase.
     * Restore them when a monophonic group on the explicit lead stem supplies both pitch and
     * rhythm evidence, and the existing path already retains the other attacks.
     */
    void restoreArticulatedPhrases(Integer part, List<Note> tones, Map<String, Role> roles, Map<String, String> reasons) {
        if (fastRunsOff || part == null || !stems.containsKey(part)) return;
        var stem = stems.get(part);
        var phrase = new ArrayList<Note>();
        var local = tones.stream().filter(note -> note.part() == part).toList();
        for (int i = 0; i < local.size(); i++) {
            var note = local.get(i);
            var previous = i > 0 ? local.get(i - 1) : null;
            var next = i + 1 < local.size() ? local.get(i + 1) : null;
            if (previous != null && (note.start() - previous.start() > .22 || previous.end() > note.start() + .010)) {
                restore(phrase, tones, roles, reasons);
                phrase.clear();
            }
            if (note.run() != null || (next != null && next.start() - note.start() < .030)
                    || (previous != null && note.start() - previous.start() < .030)
                    || !stem.track.clearAttack(timeline.sourceNote(note))) {
                restore(phrase, tones, roles, reasons);
                phrase.clear();
                continue;
            }
            phrase.add(note);
        }
        restore(phrase, tones, roles, reasons);
    }

    private static void restore(List<Note> phrase, List<Note> tones, Map<String, Role> roles, Map<String, String> reasons) {
        if (phrase.size() < 5) return;
        double pulse = Double.POSITIVE_INFINITY;
        for (int i = 1; i < phrase.size(); i++)
            pulse = Math.min(pulse, phrase.get(i).start() - phrase.get(i - 1).start());
        if (pulse < .058 || pulse > .110) return;
        boolean longInterval = false;
        for (int i = 1; i < phrase.size(); i++) {
            double distance = (phrase.get(i).start() - phrase.get(i - 1).start()) / pulse;
            if (Math.min(Math.abs(distance - 1), Math.abs(distance - 2)) > .18) return;
            longInterval |= distance > 1.5;
        }
        if (!longInterval || phrase.stream().filter(note -> roles.get(note.id()) == Role.melody).count() < 3) return;
        for (var note : phrase) {
            if (roles.get(note.id()) == Role.melody || tones.stream().anyMatch(other ->
                    roles.get(other.id()) == Role.melody && other.start() < note.end() - .010
                            && other.end() > note.start() + .010)) continue;
            roles.put(note.id(), Role.melody);
            reasons.put(note.id(), "stem-phrase-attack");
        }
    }

    List<Decision> legato(List<Decision> decisions, BiPredicate<Decision, Note> permitted) {
        if (stems.isEmpty()) return decisions;
        var result = new ArrayList<>(decisions);
        int previous = -1;
        for (int i = 0; i < decisions.size(); i++) {
            var next = decisions.get(i);
            if (next.role() != Role.melody) continue;
            if (previous >= 0) {
                var anchor = decisions.get(previous);
                Note prior = anchor.note();
                Note following = legatoTarget(prior, decisions.subList(previous + 1, i), next.note());
                var stem = stems.get(prior.part());
                // Motion-based releases and inferred attacks get first claim on the gap.
                // Apply legato only to the remaining consecutive attacks after role promotion.
                if (stem != null && prior.run() == null && following.run() == null && prior.part() == following.part()
                        && prior.endTick(timeline.frames()) < following.startTick(timeline.frames())
                        && !hasReason(anchor, EXTENDED)) {
                    var sourcePrior = timeline.sourceNote(prior);
                    var sourceFollowing = timeline.sourceNote(following);
                    if (sourceFollowing.start() - sourcePrior.end() <= MAX_LEGATO_GAP
                            && stem.track.bridges(sourcePrior, sourceFollowing,
                            stem.overlapping(sourcePrior.end(), sourceFollowing.start()).stream()
                                    .filter(note -> !note.id().equals(prior.id()) && !note.id().equals(following.id()))
                                    .toList())) {
                        Note longer = prior.withTiming(prior.start(), following.start());
                        if (permitted.test(anchor, longer)) result.set(previous, new Decision(longer, Role.melody,
                                append(anchor.reason(), LEGATO), null, prior.end()));
                    }
                }
            }
            previous = i;
        }
        return List.copyOf(result);
    }

    static Note legatoTarget(Note prior, List<Decision> between, Note nextLead) {
        // A written re-attack can have a different function from the lead.
        // Its onset still ends the extension of the previous note.
        return between.stream().filter(decision -> decision.role() != Role.drop && decision.role() != Role.drums)
                .map(Decision::note)
                .filter(note -> note.part() == prior.part() && note.pitch() == prior.pitch()
                        && note.start() >= prior.end()
                        && (note.source().equals("midi") || note.source().equals("edit")))
                .findFirst().orElse(nextLead);
    }

    static boolean hasReason(Decision decision, String reason) {
        if (decision.reason() == null) return false;
        for (String token : decision.reason().split(" ")) if (token.equals(reason)) return true;
        return false;
    }

    List<Decision> repair(List<Decision> decisions, BiPredicate<Decision, Note> permitted, Set<String> fixedGates) {
        var edited = new HashMap<String, Decision>();
        var extra = new ArrayList<Decision>();
        var ids = new HashSet<String>();
        var lead = new ArrayList<Decision>();
        var held = new HashMap<Integer, List<Decision>>();
        for (var decision : decisions) {
            Note note = decision.note();
            ids.add(note.id());
            if (decision.role() == Role.melody) lead.add(decision);
            else if (note.run() == null && !fixedGates.contains(note.id())
                    && (decision.role() == Role.counter || decision.role() == Role.harmony)
                    && note.end() - note.start() >= MIN_HEAD + ReconstructionLeadAudio.MIN_TONE)
                held.computeIfAbsent(note.part(), ignored -> new ArrayList<>()).add(decision);
        }
        double occupied = 0;
        for (int i = 0; i < lead.size(); i++) {
            Decision anchor = lead.get(i);
            Note prior = anchor.note();
            Note next = i + 1 < lead.size() ? lead.get(i + 1).note() : null;
            double end = next == null ? timeline.projectDuration() : next.start();
            double at = Math.max(occupied, prior.end());
            occupied = at;
            if (prior.run() != null || at - prior.end() > 1e-7 || end - at < .049) continue;
            Stem stem = stems.get(prior.part());
            if (stem != null) {
                double start = timeline.source(prior.start()), release = timeline.source(prior.end());
                double bound = Math.min(timeline.source(end), release + MAX_LOOKAHEAD);
                double extended = stem.track.release(prior.pitch(), start, release, bound,
                        stem.overlapping(start, bound));
                if (extended > release) {
                    Note longer = prior.withTiming(prior.start(), Math.min(end, timeline.project(extended)))
                            .withMotion(stem.track.evidence(prior.pitch(), start, extended,
                                    stem.overlapping(start, extended)));
                    if (permitted.test(anchor, longer)) {
                        edited.put(prior.id(), new Decision(longer, Role.melody, append(anchor.reason(), EXTENDED), null));
                        prior = longer;
                        at = prior.end();
                    }
                }
            }
            if (end - at < .149) continue;
            Candidate best = heldTail(anchor, prior, next, end, held.getOrDefault(prior.part(), List.of()),
                    edited, stem, permitted);
            if (best == null && stem != null) best = missing(anchor, prior, next, end, stem, permitted);
            if (best == null) continue;
            Note tail = best.note();
            if (best.held() != null) {
                var original = best.held();
                Note head = original.note();
                edited.put(head.id(), new Decision(head.withTiming(head.start(), tail.start()),
                        original.role(), append(original.reason(), SPLIT + "-head"), null));
                if (head.end() - tail.end() >= MIN_HEAD) {
                    var remainder = copy(head, unique(ids, head.id() + "-remainder"), tail.end(), head.end());
                    extra.add(new Decision(remainder, original.role(), original.reason(), null));
                }
            }
            String id = unique(ids, tail.id());
            extra.add(new Decision(copy(tail, id, tail.start(), tail.end()), Role.melody,
                    append(anchor.reason(), best.held() == null ? ADDED : SPLIT), null));
            occupied = tail.end();
        }
        var result = new ArrayList<Decision>(decisions.size() + extra.size());
        for (var decision : decisions) {
            var revised = edited.getOrDefault(decision.note().id(), decision);
            var motion = evidence.get(revised.note().id());
            if (revised.note().motion() == null && motion != null && !isSplitHead(revised))
                revised = new Decision(revised.note().withMotion(motion), revised.role(), revised.reason(), revised.mergedInto());
            result.add(revised);
        }
        result.addAll(extra);
        result.sort(Comparator.comparing(Decision::note, NOTE_ORDER));
        return List.copyOf(result);
    }

    private static boolean isSplitHead(Decision decision) {
        return decision.reason() != null && decision.reason().endsWith(SPLIT + "-head");
    }

    private Candidate heldTail(Decision anchor, Note prior, Note next, double end, List<Decision> backing,
                               Map<String, Decision> edited, Stem stem, BiPredicate<Decision, Note> permitted) {
        Candidate best = null;
        double runnerUp = Double.POSITIVE_INFINITY;
        for (var decision : backing) {
            Note held = decision.note();
            if (held.start() > prior.end() - MIN_HEAD) break;
            if (edited.containsKey(held.id()) || held.end() < prior.end() + .145
                    || Math.abs(held.pitch() - prior.pitch()) > 7 || held.pitch() == prior.pitch()) continue;
            double start = prior.end();
            double stop = Math.min(end, held.end());
            ReconstructionLeadAudio.Evidence motion = null;
            double bonus = 0;
            if (stem != null) {
                double from = timeline.source(start), to = timeline.source(stop);
                var tone = stem.track.tone(held.pitch(), from, to, stem.overlapping(from, to));
                if (tone == null || tone.start() - from > .150) continue;
                start = Math.max(start, timeline.project(tone.start()));
                motion = tone.evidence();
                bonus = .12 * motion.vibrato();
            } else if (!midiHandoff(anchor, prior, next, held, backing)) continue;
            var tail = new Note(held.id() + "-lead", held.part(), start, stop, held.pitch(), held.velocity(),
                    held.source(), held.confidence(), motion);
            double cost = contourCost(prior, tail, next) - bonus;
            if (cost > .18 || !permitted.test(anchor, tail)) continue;
            if (best == null || cost < best.cost()) {
                if (best != null) runnerUp = best.cost();
                best = new Candidate(tail, decision, cost);
            } else {
                runnerUp = Math.min(runnerUp, cost);
            }
        }
        return best != null && (stem != null || runnerUp - best.cost() >= .030) ? best : null;
    }

    private static boolean midiHandoff(Decision anchor, Note prior, Note next, Note held, List<Decision> backing) {
        boolean planned = anchor.reason() != null && anchor.reason().startsWith("roleplan#");
        if (held.start() >= prior.start() + .024 || Math.abs(held.pitch() - prior.pitch()) > (planned ? 5 : 3))
            return false;
        if (next == null || next.part() != prior.part() || next.start() - held.end() > (planned ? .25 : .075)
                || (!planned && held.end() - next.start() > .075) || Math.abs(next.pitch() - held.pitch()) > 5)
            return false;
        if (!planned && (prior.end() - prior.start() > .16 || held.end() - held.start() < .45
                || held.start() > prior.start() - .07 || held.pitch() >= prior.pitch())) return false;
        return backing.stream().anyMatch(other -> other.note().pitch() < held.pitch()
                && Math.abs(other.note().start() - held.start()) <= .024);
    }

    private Candidate missing(Decision anchor, Note prior, Note next, double end, Stem stem,
                              BiPredicate<Decision, Note> permitted) {
        double start = timeline.source(prior.end()), stop = Math.min(timeline.source(end), start + MAX_LOOKAHEAD);
        var around = stem.overlapping(start, stop);
        Candidate best = null;
        for (int pitch = prior.pitch() - 5; pitch <= prior.pitch() + 5; pitch++) {
            if (pitch == prior.pitch()) continue;
            var tone = stem.track.tone(pitch, start, stop, around);
            if (tone == null || tone.start() - start > .120 || tone.evidence().vibrato() < .82
                    || represented(pitch, tone, around)) continue;
            String id = "lead-audio-" + prior.part() + "-" + Math.round(tone.start() * 1e6) + "-" + pitch;
            var note = new Note(id, prior.part(), Math.max(prior.end(), timeline.project(tone.start())),
                    Math.min(end, timeline.project(tone.end())), pitch, prior.velocity(), "audio",
                    tone.evidence().vibrato(), tone.evidence());
            double cost = contourCost(prior, note, next) - .12 * tone.evidence().vibrato();
            if (cost <= .12 && permitted.test(anchor, note) && (best == null || cost < best.cost()))
                best = new Candidate(note, null, cost);
        }
        return best;
    }

    private static boolean represented(int pitch, ReconstructionLeadAudio.Tone tone, List<Note> notes) {
        return notes.stream().anyMatch(note -> note.start() < tone.end() && note.end() > tone.start()
                && (note.pitch() == pitch || note.pitch() <= pitch - 12 && aliases(pitch, note.pitch())));
    }

    private static boolean aliases(int pitch, int lower) {
        double ratio = ReconstructionLeadAudio.hz(pitch) / ReconstructionLeadAudio.hz(lower);
        return ratio <= 4.05 && Math.abs(ratio - Math.round(ratio)) < .045;
    }

    private static double contourCost(Note prior, Note tail, Note next) {
        double cost = ReconstructionRoles.transition(prior, tail, tail.start() - prior.end());
        if (next != null && next.part() == tail.part() && next.start() - tail.end() < .4)
            cost += .6 * ReconstructionRoles.transition(tail, next, next.start() - tail.end());
        return cost;
    }

    private static Note copy(Note note, String id, double start, double end) {
        return new Note(id, note.part(), start, end, note.pitch(), note.velocity(), note.source(), note.confidence(),
                note.motion());
    }

    private static String unique(HashSet<String> ids, String base) {
        String id = base;
        for (int i = 2; !ids.add(id); i++) id = base + "-" + i;
        return id;
    }

    private static String append(String prior, String reason) {
        return prior == null ? reason : prior + " " + reason;
    }
}
