package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionRepeatPolicy.Fill;
import static com.opensmpsdeck.headless.ReconstructionRepeatPolicy.Lanes;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStructure.Bar;
import static com.opensmpsdeck.headless.ReconstructionStructure.Vote;

/** Bar-aligned, per-stem repeats. Written notes vote and local stem evidence can veto any fill. */
final class ReconstructionRepeatConsensus {
    static final String SOURCE = "repeat-consensus";
    private static final double ONSET_TOLERANCE = .028;
    private static final double MAX_PHASE_SHIFT = .125;
    private static final double HOP = .020;

    record Member(String slot, boolean core, @ReconstructionStages.Nullable String reason,
                  @ReconstructionStages.Nullable List<Vote> votes,
                  @ReconstructionStages.Nullable Double stemDb,
                  @ReconstructionStages.Nullable Lanes lanes) {
        boolean keepsLanes() { return lanes != null && lanes != Lanes.off; }
        boolean strict() { return lanes == Lanes.strict; }
    }

    private record Pattern(int index, Bar bar, List<Note> notes) {
        double phase(Note note) { return bar.fraction(note.start()); }
    }

    private record Match(Pattern pattern, double shift, Map<String, Note> notes) {}
    private record Candidate(String slot, Pattern target, Note anchor, double phase,
                        double duration, List<Note> donors, List<Vote> votes) {}

    private ReconstructionRepeatConsensus() {}

    static Map<String, Member> apply(ReconstructionStages.Score score, List<Bar> bars,
                                     ReconstructionTimeMap timeline, Set<String> authoredIds,
                                     List<Note> suppressed, Map<String, Note> notes,
                                     Map<Integer, ReconstructionRepeatPolicy> policies) throws IOException {
        if (score.inputs().audioOnly() == null || bars.size() < 3) return Map.of();
        var result = new LinkedHashMap<String, Member>();
        var ordered = notes.values().stream().sorted(ReconstructionStages.NOTE_ORDER).toList();
        for (var part : score.source().parts()) {
            var policy = policies.get(part.id());
            if (policy == null) continue;
            String stem = score.inputs().stems().get(part.id());
            var label = score.inputs().audioOnly().stems().get(part.id());
            var local = ordered.stream().filter(n -> n.part() == part.id()).toList();
            var originals = local.stream().filter(n -> n.source().equals("audio-transcribed")
                    || n.source().equals("transcriber")).filter(n -> n.run() == null && !authoredIds.contains(n.id())).toList();
            var fills = new ArrayList<Candidate>();
            // Bass transcription often contains upper partials and stem bleed.
            // Compare the low line separately so those attacks cannot mask the groove.
            boolean bass = "bass".equals(label.role());
            collect(patterns(bars, originals.stream().filter(n -> n.pitch() < 48).toList()), result, fills, policy);
            if (!bass)
                collect(patterns(bars, originals.stream().filter(n -> n.pitch() >= 48).toList()), result, fills, policy);
            if (fills.isEmpty()) continue;
            var evidence = new StemEvidence(Path.of(stem), score.durationUs() / 1e6, timeline, fills);
            var occupied = new ArrayList<>(local);
            suppressed.stream().filter(n -> n.part() == part.id()).forEach(occupied::add);
            for (var fill : fills) {
                double start = fill.target.bar.at(fill.phase);
                double end = Math.min(timeline.projectDuration(), fill.target.bar.at(fill.phase + fill.duration));
                int pitch = fill.anchor.pitch();
                end = Math.min(end, nextAttack(occupied, pitch, start));
                if (start < fill.target.bar.start() || start >= fill.target.bar.end() || end - start < .045
                        || conflicts(occupied, pitch, start, end, bass)) continue;
                Double support = evidence.support(fill, start, end, policy.fill());
                if (support == null) continue;
                String id = SOURCE + "-" + fill.target.bar.id() + "-" + fill.anchor.id();
                var note = new Note(id, part.id(), start, end, pitch, fill.anchor.velocity(), SOURCE, .78);
                notes.put(id, note);
                occupied.add(note);
                String reason = fill.votes.size() >= 2 ? "aligned attacks in other bars and local stem pitch rise"
                        : "recurrent upper attack in an aligned bar and local stem pitch rise";
                result.put(id, new Member(fill.slot, true, reason, fill.votes, support, policy.lanes()));
                if (policy.lanes() != Lanes.off)
                    result.put(fill.anchor.id(), new Member(fill.slot, true, null, null, null, policy.lanes()));
            }
        }
        return result;
    }

    static void alignBass(Map<String, Member> members, List<Note> notes,
                          Map<String, ReconstructionRoles.Role> roles, Map<Integer, Integer> programs,
                          Set<String> fixedGates, Map<String, String> reasons) {
        if (members == null) return;
        var bass = ReconstructionRoles.Role.bass;
        var votes = new HashMap<String, Integer>();
        for (var note : notes) {
            var member = members.get(note.id());
            if (member != null && member.keepsLanes() && member.core && roles.get(note.id()) == bass)
                votes.merge(member.slot, 1, Integer::sum);
        }
        var protectedLine = new ArrayList<Note>();
        var anchors = new HashMap<Integer, List<Note>>();
        for (var note : notes) {
            var member = members.get(note.id());
            if (member != null && member.keepsLanes() && member.core && roles.get(note.id()) == bass
                    && votes.getOrDefault(member.slot, 0) >= 2)
                anchors.computeIfAbsent(note.part(), ignored -> new ArrayList<>()).add(note);
        }
        for (var note : notes) {
            var member = members.get(note.id());
            int program = programs.getOrDefault(note.part(), -1);
            if (program < 32 || program > 39 || note.pitch() >= 48 || fixedGates.contains(note.id())
                    || roles.get(note.id()) == ReconstructionRoles.Role.melody) continue;
            boolean repeated = member != null && member.keepsLanes() && member.core && votes.getOrDefault(member.slot, 0) >= 2;
            // A decoder can collapse several pulses into one gate. Keep that
            // written gate on the same lane if the repeated line surrounds it.
            var nearby = anchors.getOrDefault(note.part(), List.of());
            boolean continues = nearby.stream().anyMatch(n -> n.pitch() == note.pitch()
                    && n.start() < note.start() && note.start() - n.end() < 3)
                    && nearby.stream().anyMatch(n -> n.pitch() == note.pitch()
                    && n.start() > note.start() && n.start() - note.end() < 3);
            if (!repeated && !continues) continue;
            protectedLine.add(note);
            if (roles.put(note.id(), bass) != bass) reasons.put(note.id(), "repeat-consensus-bass");
        }
        for (var note : notes) {
            int program = programs.getOrDefault(note.part(), -1);
            if (roles.get(note.id()) != bass || fixedGates.contains(note.id()) || program >= 32 && program <= 39)
                continue;
            if (protectedLine.stream().anyMatch(low -> low.start() < note.end() && low.end() > note.start())) {
                roles.put(note.id(), ReconstructionRoles.Role.harmony);
                reasons.put(note.id(), "repeat-consensus-bass-lane");
            }
        }
    }

    private static List<Pattern> patterns(List<Bar> bars, List<Note> notes) {
        var result = new ArrayList<Pattern>();
        int cursor = 0;
        for (int i = 0; i < bars.size(); i++) {
            var bar = bars.get(i);
            int first = cursor;
            while (cursor < notes.size() && notes.get(cursor).start() < bar.end()) cursor++;
            if (bar.complete() && cursor > first) result.add(new Pattern(i, bar, notes.subList(first, cursor)));
        }
        return result;
    }

    private static void collect(List<Pattern> patterns, Map<String, Member> members, List<Candidate> fills,
                                ReconstructionRepeatPolicy policy) {
        var claimed = new HashSet<Integer>();
        var anchors = patterns.stream().sorted(Comparator.comparingInt((Pattern p) -> p.notes.size()).reversed()).toList();
        for (var anchor : anchors) {
            if (claimed.contains(anchor.index) || anchor.notes.size() < 3) continue;
            var peers = new ArrayList<Match>();
            var sparse = new ArrayList<Match>();
            for (var target : patterns) {
                if (target == anchor || claimed.contains(target.index)
                        || Math.abs(target.index - anchor.index) > 12) continue;
                var match = align(anchor, target);
                int count = match.notes.size();
                if (count >= 2 && count >= .32 * anchor.notes.size() && count >= .45 * target.notes.size()) {
                    peers.add(match);
                } else if (target.notes.size() <= 2 && count == target.notes.size()) {
                    sparse.add(match);
                }
            }
            if (peers.isEmpty()) continue;
            var family = new ArrayList<>(peers);
            var anchorNotes = new LinkedHashMap<String, Note>();
            anchor.notes.forEach(n -> anchorNotes.put(n.id(), n));
            family.addFirst(new Match(anchor, 0, anchorNotes));
            if (family.size() >= 3) sparse.stream()
                    .filter(m -> family.stream().anyMatch(p -> Math.abs(p.pattern.index - m.pattern.index) <= 2))
                    .forEach(peers::add);
            for (var note : anchor.notes) {
                var voters = family.stream().filter(m -> m.notes.containsKey(note.id())).toList();
                boolean consensus = voters.size() >= 2 && voters.size() * 3 >= family.size() * 2;
                boolean recurrentUpperAttack = anchor.notes.stream().filter(n -> n.pitch() == note.pitch()).count() >= 2
                        && anchor.notes.stream().anyMatch(n -> n.pitch() <= note.pitch() - 12
                        && n.start() <= note.start() + .035 && n.end() > note.start());
                if (!consensus && !recurrentUpperAttack) continue;
                String slot = note.id();
                boolean core = voters.stream().anyMatch(m -> !octaveDouble(m.pattern, m.notes.get(slot)));
                double phase = median(voters.stream().mapToDouble(m -> m.pattern.phase(m.notes.get(slot)) - m.shift).toArray());
                double duration = median(voters.stream().mapToDouble(m -> {
                    var donor = m.notes.get(slot);
                    return m.pattern.bar.fraction(donor.end()) - m.pattern.phase(donor);
                }).toArray());
                var donors = voters.stream().map(m -> m.notes.get(slot)).toList();
                var votes = voters.stream().map(m -> new Vote(m.pattern.bar.id(), m.notes.get(slot).id())).toList();
                if (consensus && policy.lanes() != Lanes.off) members.put(note.id(), new Member(slot, core, null, null, null, policy.lanes()));
                for (var peer : peers) {
                    var observed = peer.notes.get(slot);
                    if (observed != null) {
                        if (consensus && policy.lanes() != Lanes.off) members.put(observed.id(), new Member(slot, core, null, null, null, policy.lanes()));
                    } else {
                        boolean highLayer = anchor.notes.stream().anyMatch(n -> n.pitch() <= note.pitch() - 12
                                && n.start() <= note.start() + .035 && n.end() > note.start());
                        boolean hasLayer = peer.notes.values().stream().anyMatch(n -> Math.abs(n.pitch() - note.pitch()) <= 4);
                        boolean sharedContour = recurrentUpperAttack && peer.notes.values().stream()
                                .mapToInt(Note::pitch).distinct().count() >= 3;
                        if (policy.fill() != Fill.off && (!highLayer || hasLayer) && (consensus || sharedContour))
                            fills.add(new Candidate(slot, peer.pattern, note, phase + peer.shift, duration, donors, votes));
                    }
                }
            }
            claimed.add(anchor.index);
            family.forEach(m -> claimed.add(m.pattern.index));
        }
    }

    private static Match align(Pattern anchor, Pattern target) {
        // Search small phase offsets to absorb tracker drift, then require one-to-one attacks.
        var offsets = new java.util.TreeSet<Integer>();
        for (var a : anchor.notes) for (var b : target.notes) {
            if (a.pitch() != b.pitch()) continue;
            double offset = target.phase(b) - anchor.phase(a);
            if (Math.abs(offset) <= MAX_PHASE_SHIFT) offsets.add((int) Math.round(offset * 100));
        }
        var shifts = offsets.stream().mapToDouble(i -> i / 100.0).toArray();
        double best = 0;
        Match result = new Match(target, 0, Map.of());
        for (double shift : shifts) {
            var matches = new HashMap<String, Note>();
            var used = new HashSet<String>();
            double error = 0;
            for (var a : anchor.notes) {
                Note nearest = null;
                double distance = ONSET_TOLERANCE;
                for (var b : target.notes) {
                    if (a.pitch() != b.pitch() || used.contains(b.id())) continue;
                    double delta = Math.abs(target.phase(b) - anchor.phase(a) - shift);
                    if (delta < distance) {
                        distance = delta;
                        nearest = b;
                    }
                }
                if (nearest != null) {
                    matches.put(a.id(), nearest);
                    used.add(nearest.id());
                    error += distance;
                }
            }
            double quality = matches.size() - error - .6 * Math.abs(shift);
            if (quality > best) {
                best = quality;
                result = new Match(target, shift, matches);
            }
        }
        return result;
    }

    private static boolean octaveDouble(Pattern pattern, Note note) {
        return pattern.notes.stream().anyMatch(other -> note.pitch() - other.pitch() == 12
                && Math.abs(other.start() - note.start()) <= .035
                && Math.abs(other.end() - note.end()) <= .070);
    }

    private static double nextAttack(List<Note> notes, int pitch, double start) {
        return notes.stream().filter(n -> Math.abs(n.pitch() - pitch) <= 1 && n.start() > start + .050)
                .mapToDouble(Note::start).min().orElse(Double.POSITIVE_INFINITY);
    }

    private static boolean conflicts(List<Note> notes, int pitch, double start, double end, boolean bass) {
        return notes.stream().anyMatch(n -> n.start() < end && n.end() > start + .025
                && (n.pitch() == pitch || Math.abs(n.pitch() - pitch) == 1
                || bass && pitch < 48 && n.pitch() < 48 && Math.abs(n.start() - start) <= .075));
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return (values[(values.length - 1) / 2] + values[values.length / 2]) / 2;
    }

    private static final class StemEvidence {
        private final ReconstructionTimeMap timeline;
        private final EvidenceSpectrum.Spectrum spectrum;

        StemEvidence(Path path, double duration, ReconstructionTimeMap timeline, List<Candidate> fills) throws IOException {
            this.timeline = timeline;
            int low = Math.max(0, fills.stream().mapToInt(f -> f.anchor.pitch() - 27).min().orElseThrow());
            int high = Math.min(127, fills.stream().mapToInt(f -> f.anchor.pitch() + 3).max().orElseThrow());
            var audio = ReferenceAudio.evidenceRange(path, 0, duration);
            var times = new double[Math.max(1, (int) Math.ceil(Math.min(duration, audio.duration()) / HOP))];
            Arrays.setAll(times, i -> i * HOP);
            spectrum = EvidenceSpectrum.measure(audio, times, low, high, (high - low + 1) * 4, duration, false);
        }

        Double support(Candidate fill, double projectStart, double projectEnd, Fill sensitivity) {
            int pitch = fill.anchor.pitch();
            double start = timeline.source(projectStart);
            double length = timeline.source(projectEnd) - start;
            double probe = Math.min(.095, length * .46);
            double donorLevel = median(fill.donors.stream().mapToDouble(n ->
                    ridge(timeline.source(n.start()) + Math.min(.095, (n.end() - n.start()) * .46), pitch)).toArray());
            double early = Math.max(ridge(start + probe, pitch), ridge(start + probe + HOP, pitch));
            double late = ridge(start + Math.min(length * .78, probe + .055), pitch);
            double before = level(start - (pitch < 42 ? .14 : .10), pitch);
            boolean upper = fill.votes.size() == 1;
            int allowance = sensitivity.allowanceDb;
            return donorLevel > -62 && early >= Math.max(-62 - allowance, donorLevel - (upper ? 11 : 19) - allowance)
                    && late >= early - 12 && early >= before + (upper ? 6 : 4)
                    && !tracksLowerPartial(pitch, start, probe, length) ? early : null;
        }

        private boolean tracksLowerPartial(int pitch, double start, double probe, double length) {
            if (pitch < 52) return false;
            for (int interval : new int[] {12, 19, 24}) {
                double partial = interval == 19 ? EvidenceSpectrum.harmonicPitch(pitch - 19, 3) - (pitch - 19) : interval;
                int aligned = 0;
                int checks = 0;
                for (double offset = probe; offset <= Math.min(length * .88, probe + .080); offset += HOP) {
                    int column = column(start + offset);
                    var upper = spectrum.near(column, pitch, .65);
                    if (upper == null) continue;
                    checks++;
                    var lower = spectrum.near(column, pitch - interval, .65);
                    if (lower != null && lower.strength() >= upper.strength() + 3
                            && Math.abs(upper.pitch() - lower.pitch() - partial) < .23) aligned++;
                }
                // A ridge attached to a stronger lower partial supplies no
                // independent evidence for an extra attack at that pitch.
                if (checks >= 2 && aligned == checks) return true;
            }
            return false;
        }

        private double ridge(double time, int pitch) {
            var peak = spectrum.near(column(time), pitch, .65);
            return peak == null ? -120 : peak.strength();
        }

        private double level(double time, int pitch) {
            var peak = spectrum.at(column(time), pitch);
            return peak == null ? -120 : peak.strength();
        }

        private int column(double time) {
            return Math.clamp((int) Math.round(time / HOP), 0, spectrum.columns() - 1);
        }
    }
}
