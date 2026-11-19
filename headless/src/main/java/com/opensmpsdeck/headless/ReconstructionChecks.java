package com.opensmpsdeck.headless;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;

final class ReconstructionChecks {
    private static final Set<String> SOURCES = Set.of("midi", "audio-refine", "external", "consensus");

    private ReconstructionChecks() {}

    static void rate(double value) {
        require(Double.isFinite(value) && value >= 0 && value <= 60,
                "harmonyMaxHz", "use 0 (disabled) or a rate greater than 0 and at most 60");
    }

    static void score(ReconstructionStages.Score value) {
        String at = "score.json";
        require(value.ticksPerSecond() == S3kRenderer.PRESENTATIONS_PER_SECOND, at + ".ticksPerSecond", "expected 60");
        require(value.durationUs() > 0 && value.durationUs() <= 600_000_000
                        && (value.durationUs() * 60 + 999_999) / 1_000_000 == value.frames(),
                at + ".frames", "must be the 60 Hz presentation count for durationUs (maximum 600 seconds)");
        var inputs = value.inputs();
        require(inputs != null, at + ".inputs", "required");
        require(!inputs.name().isBlank() && inputs.ticksPerBeat() > 0, at + ".inputs", "invalid name or ticksPerBeat");
        for (String path : List.of(inputs.midi(), inputs.wav(), inputs.catalogue()))
            require(!path.isBlank(), at + ".inputs", "input paths must be nonblank");
        rate(inputs.harmonyMaxHz());
        var parts = new HashSet<Integer>();
        for (var part : value.source().parts()) {
            require(part.id() >= 0 && parts.add(part.id()), at + ".source.parts", "duplicate or invalid part ID " + part.id());
            require(part.program() >= 0 && part.program() <= 127 && !part.name().isBlank(),
                    at + ".source.parts", "invalid MIDI program or name for part " + part.id());
        }
        require(parts.containsAll(inputs.stems().keySet()), at + ".inputs.stems", "unknown MIDI part ID");
        require(parts.containsAll(value.sourceCounts().keySet()) && value.sourceCounts().values().stream().allMatch(n -> n >= 0),
                at + ".sourceCounts", "invalid source note counts");
        for (var note : value.source().notes()) {
            require(parts.contains(note.partId()) && note.pitch() >= 0 && note.pitch() <= 127
                    && note.startUs() >= 0 && note.endUs() >= note.startUs() && note.velocity() >= 1 && note.velocity() <= 127,
                    at + ".source.notes", "invalid original MIDI note for part " + note.partId());
        }
        checkBars(at, value.bars(), duration(value));
        checkNotes(at, value.notes(), value, Set.of("midi", "audio-refine", "external"));
    }

    static void structure(ReconstructionStructure.Result result, ReconstructionStages.Score score) {
        String at = "structure.json";
        var bars = checkBars(at, result.bars(), duration(score));
        var order = result.bars().stream().map(ReconstructionStructure.Bar::id).toList();
        unique(at + ".sections", result.sections(), ReconstructionStructure.Section::id);
        var flattened = result.sections().stream().flatMap(s -> s.bars().stream()).toList();
        require(flattened.equals(order) && result.sections().stream().noneMatch(s -> s.bars().isEmpty()),
                at + ".sections", "sections must partition the bars in order");
        unique(at + ".repeats", result.repeats(), ReconstructionStructure.Repeat::id);
        var repeated = new HashMap<String, Set<String>>();
        for (var repeat : result.repeats()) {
            var instances = new HashSet<>(repeat.bars());
            require(instances.size() >= 2 && instances.size() == repeat.bars().size(),
                    at + ".repeats." + repeat.id(), "repeat must have at least two distinct bars");
            for (String id : repeat.bars()) {
                require(bars.containsKey(id) && bars.get(id).complete() && repeated.putIfAbsent(id, instances) == null,
                        at + ".repeats." + repeat.id(), "unknown, partial or already grouped bar " + id);
            }
        }
        unique(at + ".corrections", result.corrections(), ReconstructionStructure.Correction::id);
        var original = new HashMap<String, Note>();
        for (var note : score.notes()) original.put(note.id(), note);
        for (var correction : result.corrections()) {
            String where = at + ".corrections." + correction.id();
            var group = repeated.get(correction.barId());
            require(group != null, where, "target bar is not in a repeat group");
            var target = bars.get(correction.barId());
            var candidate = correction.note();
            note(candidate, partIds(score), duration(score), where);
            require(candidate.source().equals("consensus") && candidate.start() >= target.start() && candidate.start() < target.end(),
                    where, "consensus note must attack in the target bar");
            var voterBars = new HashSet<String>();
            require(correction.votes().size() >= 2, where, "at least two other instances must vote");
            for (var vote : correction.votes()) {
                var votingNote = original.get(vote.noteId());
                var votingBar = bars.get(vote.barId());
                require(group.contains(vote.barId()) && !vote.barId().equals(correction.barId()) && voterBars.add(vote.barId())
                                && votingNote != null && !votingNote.source().equals("external")
                                && votingNote.part() == candidate.part() && votingNote.pitch() == candidate.pitch()
                                && votingNote.start() >= votingBar.start() && votingNote.start() < votingBar.end(),
                        where, "invalid vote from " + vote.barId() + " for " + vote.noteId());
            }
            require(!correction.applied() || voterBars.size() * 3 >= group.size() * 2, where,
                    "applied correction needs votes from at least two thirds of its repeat group");
            require(correction.replaces() == null || correction.replaces().equals(candidate.id())
                            && original.containsKey(correction.replaces()),
                    where, "replacement must keep the original note ID");
            if (correction.externalCandidate() != null) {
                var external = original.get(correction.externalCandidate());
                require(external != null && external.source().equals("external"), where, "unknown external candidate");
            }
        }
        checkNotes(at, result.notes(), score, Set.of("midi", "audio-refine", "consensus"));
    }

    static void roles(ReconstructionRoles.Result roles, ReconstructionStages.Score score) {
        String at = "roles.json";
        rate(roles.harmonyMaxHz());
        checkNotes(at, roles.notes().stream().map(ReconstructionRoles.Decision::note).toList(),
                score, Set.of("midi", "audio-refine", "consensus"));
        var parts = partIndex(score);
        var notes = new HashMap<String, ReconstructionRoles.Decision>();
        for (var decision : roles.notes()) notes.put(decision.note().id(), decision);
        for (var choice : roles.notes()) {
            String where = at + ".notes." + choice.note().id();
            checkRole(parts, choice.note(), choice.role(), where);
            require(choice.role() != Role.drop || nonblank(choice.reason()), where, "a dropped note needs a reason");
            if (choice.mergedInto() != null) {
                var kept = notes.get(choice.mergedInto());
                require(choice.role() == Role.drop && kept != null && kept.role() != Role.drop
                                && kept.note().part() == choice.note().part() && kept.note().pitch() == choice.note().pitch()
                                && kept.note().start() < choice.note().start(),
                        where, "mergedInto must name a retained earlier note of the same part and pitch");
            }
        }
    }

    static void allocation(ReconstructionAllocation.Result allocation, ReconstructionStages.Score score) {
        String at = "allocation.json";
        checkNotes(at, allocation.notes().stream().map(ReconstructionAllocation.Entry::note).toList(),
                score, Set.of("midi", "audio-refine", "consensus"));
        var parts = partIndex(score);
        var lanes = new HashMap<String, java.util.ArrayList<ReconstructionAllocation.Entry>>();
        var entries = new HashMap<String, ReconstructionAllocation.Entry>();
        for (var entry : allocation.notes()) entries.put(entry.note().id(), entry);
        for (var entry : allocation.notes()) {
            String where = at + ".notes." + entry.note().id();
            checkRole(parts, entry.note(), entry.role(), where);
            if (entry.outcome() == ReconstructionAllocation.Outcome.dropped) {
                require(entry.lane() == null && entry.startTick() == null && entry.endTick() == null
                        && nonblank(entry.reason()), where, "a dropped note needs a reason and no lane or ticks");
                if (entry.mergedInto() != null) {
                    var kept = entries.get(entry.mergedInto());
                    // An already merged note may itself have overflowed during allocation.
                    require(entry.role() == Role.drop && kept != null && kept.role() != Role.drop
                                    && kept.note().part() == entry.note().part() && kept.note().pitch() == entry.note().pitch()
                                    && kept.note().start() < entry.note().start(), where, "invalid merge target");
                }
                continue;
            }
            String lane = entry.lane();
            require(lane != null && switch (entry.role()) {
                case melody -> lane.equals("FM1");
                case bass -> lane.equals("FM2");
                case drums -> lane.equals("DAC") || lane.equals("PSG3") && !score.inputs().fmOnly();
                case harmony, counter -> ReconstructionAllocation.INNER_FM.contains(lane)
                        || ReconstructionAllocation.PSG.contains(lane) && !score.inputs().fmOnly() && entry.note().pitch() >= 45;
                case drop -> false;
            }, where, "invalid lane for " + entry.role());
            require(entry.startTick() != null && entry.endTick() != null && entry.startTick() >= 0
                            && entry.startTick() < entry.endTick() && entry.endTick() <= score.frames()
                            && (!lane.equals("DAC") || entry.endTick() == entry.startTick() + 1),
                    where, "invalid startTick/endTick for " + lane);
            require(entry.mergedInto() == null && (entry.outcome() != ReconstructionAllocation.Outcome.shortened
                    || nonblank(entry.reason())), where, "shortened placements need a reason and cannot be merged");
            lanes.computeIfAbsent(lane, key -> new java.util.ArrayList<>()).add(entry);
        }
        for (var lane : lanes.entrySet()) {
            int end = 0;
            lane.getValue().sort(Comparator.comparingInt(ReconstructionAllocation.Entry::startTick));
            for (var entry : lane.getValue()) {
                require(entry.startTick() >= end, at + ".notes." + entry.note().id(),
                        "overlap on lane " + lane.getKey());
                end = entry.endTick();
            }
        }
    }

    private static Map<String, ReconstructionStructure.Bar> checkBars(String at, List<ReconstructionStructure.Bar> bars, double duration) {
        var map = new HashMap<String, ReconstructionStructure.Bar>();
        double cursor = 0;
        for (var bar : bars) {
            require(!bar.id().isBlank() && map.putIfAbsent(bar.id(), bar) == null, at + ".bars", "duplicate or blank bar ID");
            require(bar.start() == cursor && bar.end() > bar.start() && bar.end() <= duration,
                    at + ".bars." + bar.id(), "bars must be contiguous and inside the score");
            cursor = bar.end();
        }
        require(cursor == duration, at + ".bars", "bar grid must cover the score");
        return map;
    }

    private static void checkNotes(String at, List<Note> notes, ReconstructionStages.Score score, Set<String> sources) {
        unique(at + ".notes", notes, Note::id);
        var parts = partIndex(score);
        for (var note : notes) {
            String where = at + ".notes." + note.id();
            note(note, parts.keySet(), duration(score), where);
            require(sources.contains(note.source()), where + ".source", "source not valid in this artifact");
            if (!parts.get(note.part()).drums() && !note.source().equals("external")) {
                require(note.pitch() >= 12 && note.pitch() <= 106, where + ".pitch", "S3K tone pitch must be in 12..106");
            }
        }
    }

    static void note(Note note, Set<Integer> parts, double duration, String at) {
        require(!note.id().isBlank() && parts.contains(note.part()), at, "blank note ID or unknown MIDI part");
        require(note.start() >= 0 && note.start() < duration && note.end() >= note.start() && note.end() <= duration,
                at, "note timing outside the score or release before attack");
        require(note.pitch() >= 0 && note.pitch() <= 127 && note.velocity() >= 1 && note.velocity() <= 127,
                at, "MIDI pitch must be in 0..127 and velocity in 1..127");
        require(SOURCES.contains(note.source()) && note.confidence() >= 0 && note.confidence() <= 1,
                at, "unknown source or confidence outside 0..1");
    }

    private static Set<Integer> partIds(ReconstructionStages.Score score) {
        return partIndex(score).keySet();
    }

    private static void checkRole(Map<Integer, MidiScore.Part> parts, Note note, Role role, String at) {
        require(role == Role.drop || (role == Role.drums) == parts.get(note.part()).drums(),
                at + ".role", "drums role must match MIDI percussion part");
    }

    private static Map<Integer, MidiScore.Part> partIndex(ReconstructionStages.Score score) {
        var result = new HashMap<Integer, MidiScore.Part>();
        for (var part : score.source().parts()) result.put(part.id(), part);
        return result;
    }

    private static <T> void unique(String at, List<T> values, Function<T, String> id) {
        var seen = new HashSet<String>();
        for (var value : values) {
            String key = id.apply(value);
            require(!key.isBlank() && seen.add(key), at, "duplicate or blank ID " + key);
        }
    }

    private static double duration(ReconstructionStages.Score score) {
        return score.durationUs() / 1_000_000.0;
    }

    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
}
