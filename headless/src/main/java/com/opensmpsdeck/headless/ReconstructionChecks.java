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
    private static final Set<String> SOURCES = Set.of("edit", "midi", "audio-refine", "transcriber", "audio-transcribed", ReconstructionAudioOnly.DRUM_SOURCE, ReconstructionStemTemplates.SOURCE, "external", "consensus", "audio", ReconstructionFastRuns.REASON, ReconstructionRetriggers.REASON);

    private ReconstructionChecks() {}

    static void rate(double value) {
        require(Double.isFinite(value) && value >= 0 && value <= 60,
                "harmonyMaxHz", "use 0 (disabled) or a rate greater than 0 and at most 60");
    }

    static void score(ReconstructionStages.Score value) {
        String at = "score.json";
        double ticksPerSecond = value.profile().ticksPerSecond();
        require(value.ticksPerSecond() != null && value.ticksPerSecond().doubleValue() == ticksPerSecond,
                at + ".ticksPerSecond", "expected " + ticksPerSecond);
        require(value.durationUs() > 0 && value.durationUs() <= 600_000_000
                        && (value.durationUs() * 60 + 999_999) / 1_000_000 == value.frames(),
                at + ".frames", "must be the 60 Hz presentation count for durationUs (maximum 600 seconds)");
        var inputs = value.inputs();
        require(inputs != null, at + ".inputs", "required");
        require(!inputs.name().isBlank() && inputs.ticksPerBeat() > 0, at + ".inputs", "invalid name or ticksPerBeat");
        for (String path : List.of(inputs.wav(), inputs.catalogue()))
            require(!path.isBlank(), at + ".inputs", "input paths must be nonblank");
        var audio = inputs.audioOnly();
        require(audio == null ? inputs.midi() != null && !inputs.midi().isBlank()
                        : inputs.midi() == null && ReconstructionAudioOnly.SOURCE.equals(audio.source())
                        && inputs.stems().keySet().equals(audio.stems().keySet())
                        && audio.drumDetector() != null && audio.drumDetector().hits().equals(value.source().notes()),
                at + ".inputs", "expected MIDI path or audio-only provenance matching the score");
        rate(inputs.harmonyMaxHz());
        var parts = new HashSet<Integer>();
        for (var part : value.source().parts()) {
            require(part.id() >= 0 && parts.add(part.id()), at + ".source.parts", "duplicate or invalid part ID " + part.id());
            require(part.program() >= 0 && part.program() <= 127 && !part.name().isBlank(),
                    at + ".source.parts", "invalid MIDI program or name for part " + part.id());
        }
        require(parts.containsAll(inputs.stems().keySet()), at + ".inputs.stems", "unknown MIDI part ID");
        require(inputs.leadPart() == null || value.source().parts().stream()
                        .anyMatch(part -> part.id() == inputs.leadPart() && !part.drums()),
                at + ".inputs.leadPart", "--lead-part must name an existing non-drum MIDI part ID");
        if (inputs.externalNotes() != null) {
            for (var entry : inputs.externalNotes().entrySet()) {
                ExternalTranscribers.validatePart(entry.getKey(), value.source().parts());
                var imported = entry.getValue();
                require(imported.provenance().noteCount() == imported.notes().size(), at + ".inputs.externalNotes",
                        "provenance noteCount does not match the snapshot");
                for (var note : imported.notes()) ExternalTranscribers.validateNote(note, at + ".inputs.externalNotes");
            }
        }
        require(parts.containsAll(value.sourceCounts().keySet()) && value.sourceCounts().values().stream().allMatch(n -> n >= 0),
                at + ".sourceCounts", "invalid source note counts");
        for (var note : value.source().notes()) {
            require(parts.contains(note.partId()) && note.pitch() >= 0 && note.pitch() <= 127
                    && note.startUs() >= 0 && note.endUs() >= note.startUs() && note.velocity() >= 1 && note.velocity() <= 127,
                    at + ".source.notes", "invalid original MIDI note for part " + note.partId());
        }
        checkBars(at, value.bars(), duration(value));
        var beatMap = value.beatMap();
        if (beatMap != null) {
            String where = at + ".beatMap";
            var beats = beatMap.beats();
            require(beats.size() >= 2 && beats.size() <= 4100 && unit(beatMap.confidence())
                            && beatMap.beatsPerBar() >= 1 && beatMap.beatsPerBar() <= 255
                            && beatMap.downbeat() >= 0 && beatMap.downbeat() < beatMap.beatsPerBar(),
                    where, "invalid beat count, meter, phase or confidence");
            require(beats.getFirst() >= -3 && beats.getFirst() <= 3 && beats.getLast() >= duration(value) - 3
                    && beats.getLast() <= duration(value) + 6, where, "beat map must cover the score");
            for (int i = 1; i < beats.size(); i++) {
                double period = beats.get(i) - beats.get(i - 1);
                require(period >= .14999 && period <= 3.00001, where, "beat times must increase (20–400 BPM)");
            }
            int frames = ReconstructionTimeMap.of(value).frames();
            require(frames >= 1 && frames <= S3kRenderer.MAX_PRESENTATIONS, where,
                    "project duration exceeds 600 seconds at the requested steady tempo");
        }
        if (value.tempo() != null) {
            require(!value.tempo().source().equals("tracked") || beatMap != null, at + ".beatMap", "required for a tracked grid");
            var tempo = value.tempo();
            require(Set.of("estimated", "override", "midi", "tracked").contains(tempo.source()), at + ".tempo.source",
                    "unknown grid source");
            require(unit(tempo.confidence()) && unit(tempo.midiEvidence()) && unit(tempo.audioEvidence()),
                    at + ".tempo", "confidence and evidence must be between 0 and 1");
            validateGrid(tempo.selected(), at + ".tempo.selected");
            for (var grid : tempo.alternatives()) validateGrid(grid, at + ".tempo.alternatives");
        }
        if (value.midiBars() != null) checkBars(at + ".midiBars", value.midiBars(), duration(value));
        checkNotes(at, value.notes(), value, duration(value), Set.of("edit", "midi", "audio-refine", "transcriber", "audio-transcribed", ReconstructionAudioOnly.DRUM_SOURCE, ReconstructionStemTemplates.SOURCE, "external", ReconstructionFastRuns.REASON, ReconstructionRetriggers.REASON));
        var figures = new HashMap<String, Note>();
        for (var note : value.notes()) if (note.run() != null) figures.put(note.id(), note);
        if (value.retriggers() != null) {
            var figureParts = partIds(value);
            var anchors = new HashMap<String, Note>();
            for (var note : value.notes()) anchors.put(note.id(), note);
            var releases = new HashMap<String, Double>();
            for (var burst : value.retriggers()) {
                var anchor = anchors.get(burst.anchor());
                var figure = burst.figure();
                String where = at + ".retriggers." + figure.id();
                require(anchor != null && anchor.run() == null && anchors.get(figure.id()) == null
                                && value.inputs().leadPart() != null && anchor.part() == value.inputs().leadPart(),
                        where, "expected a distinct figure on an existing explicit lead note");
                note(figure, figureParts, duration(value), where);
                require(figure.source().equals(ReconstructionRetriggers.REASON) && figure.run() != null
                                && figure.part() == anchor.part() && figure.pitch() == anchor.pitch()
                                && figure.start() >= Math.max(anchor.start(), releases.getOrDefault(anchor.id(), 0.0))
                                && figure.end() <= anchor.end()
                                && figure.run().stream().allMatch(step -> step.pitch() == anchor.pitch()),
                        where, "retriggers must preserve anchor pitch and fit its gate without overlapping");
                require(figures.putIfAbsent(figure.id(), figure) == null, where, "duplicate figure ID");
                releases.put(anchor.id(), figure.end());
            }
        }
        if (value.figureVoices() != null) {
            for (var entry : value.figureVoices().entrySet()) {
                var figure = figures.get(entry.getKey());
                var voices = entry.getValue();
                String where = at + ".figureVoices." + entry.getKey();
                require(figure != null && voices.size() >= 2, where, "expected two or more voices for an existing figure");
                int previous = Integer.MAX_VALUE;
                boolean anchor = false;
                for (var voice : voices) {
                    int interval = voice.interval();
                    require(interval >= -12 && interval <= 12 && interval < previous && unit(voice.confidence())
                                    && voice.role().equals(previous == Integer.MAX_VALUE ? "lead" : "harmony"),
                            where, "expected ordered pitches and a single top lead");
                    require(voice.pitches().size() == figure.run().size(), where, "expected one pitch per step");
                    for (int step = 0; step < figure.run().size(); step++) {
                        int pitch = voice.pitches().get(step);
                        require(pitch >= 12 && pitch <= 106 && pitch == figure.run().get(step).pitch() + interval,
                                where, "companion pitches must follow the figure at the declared interval");
                    }
                    anchor |= interval == 0;
                    previous = interval;
                }
                require(anchor, where, "the figure's allocated pitch must be present");
            }
        }
    }

    private static void validateGrid(ReconstructionTempo.Grid grid, String at) {
        require(grid.bpm() >= 20 && grid.bpm() <= 400 && grid.beatsPerBar() >= 1
                        && grid.beatsPerBar() <= 255 && grid.beatPhase() >= 0 && grid.beatPhase() < 60 / grid.bpm()
                        && grid.barOffset() >= 0 && grid.barOffset() < grid.barLength(),
                at, "invalid tempo, meter or phase");
    }

    private static boolean unit(double value) {
        return value >= 0 && value <= 1;
    }

    static void structure(ReconstructionStructure.Result result, ReconstructionStages.Score score) {
        String at = "structure.json";
        var timeline = ReconstructionTimeMap.of(score);
        checkTimeBase(at, result.timeBase(), timeline, "structure");
        double duration = timeline.projectDuration();
        var bars = checkBars(at, result.bars(), duration);
        var order = result.bars().stream().map(ReconstructionStructure.Bar::id).toList();
        var repeated = validateLayout(at, result.sections(), result.repeats(), order, bars);
        var votingBars = bars;
        if (result.roleLayout() != null) {
            var layout = result.roleLayout();
            votingBars = checkBars(at + ".roleLayout", layout.bars(), duration);
            repeated = validateLayout(at + ".roleLayout", layout.sections(), layout.repeats(),
                    layout.bars().stream().map(ReconstructionStructure.Bar::id).toList(), votingBars);
        }
        var phrases = new HashMap<String, Map<String, Integer>>();
        if (result.phrases() != null) {
            unique(at + ".phrases", result.phrases(), ReconstructionPhrases.Group::id);
            var index = new HashMap<String, Integer>();
            for (int i = 0; i < order.size(); i++) index.put(order.get(i), i);
            for (var phrase : result.phrases()) {
                String where = at + ".phrases." + phrase.id();
                require(unit(phrase.similarity()) && unit(phrase.melodySimilarity()) && unit(phrase.partSimilarity())
                                && (phrase.audioSimilarity() == null || unit(phrase.audioSimilarity())),
                        where, "similarities must be between 0 and 1");
                require(phrase.instances().size() >= 2, where, "expected at least two instances");
                int length = phrase.instances().getFirst().bars().size();
                require(length == 1 || length == 2 || length == 4, where, "expected 1, 2 or 4 bars per instance");
                var members = new HashMap<String, Integer>();
                int instance = 0;
                for (var span : phrase.instances()) {
                    require(span.bars().size() == length && index.containsKey(span.bars().getFirst()),
                            where, "invalid phrase span");
                    int start = index.get(span.bars().getFirst());
                    require(start + length <= order.size() && span.bars().equals(order.subList(start, start + length)),
                            where, "expected consecutive bars");
                    for (String id : span.bars())
                        require(bars.get(id).complete() && members.putIfAbsent(id, instance) == null,
                                where, "partial or overlapping bar " + id);
                    instance++;
                }
                phrases.put(phrase.id(), members);
            }
        }
        unique(at + ".corrections", result.corrections(), ReconstructionStructure.Correction::id);
        var original = new HashMap<String, Note>();
        for (var note : score.notes()) original.put(note.id(), timeline.projectNote(note));
        for (var correction : result.corrections()) {
            String where = at + ".corrections." + correction.id();
            var group = repeated.get(correction.barId());
            var phrase = correction.phraseId() == null ? null : phrases.get(correction.phraseId());
            require(correction.phraseId() == null ? group != null
                            : phrase != null && phrase.containsKey(correction.barId()),
                    where, "target bar must belong to its repeat or phrase group");
            var correctionBars = phrase == null ? votingBars : bars;
            var target = correctionBars.get(correction.barId());
            var candidate = correction.note();
            note(candidate, partIds(score), duration, where);
            require(candidate.source().equals("consensus") && candidate.start() >= target.start() && candidate.start() < target.end(),
                    where, "consensus note must attack in the target bar");
            var voterBars = new HashSet<String>();
            var voterInstances = new HashSet<Integer>();
            require(correction.votes().size() >= 2, where, "at least two other instances must vote");
            for (var vote : correction.votes()) {
                var votingNote = original.get(vote.noteId());
                var votingBar = correctionBars.get(vote.barId());
                boolean member = phrase == null
                        ? group.contains(vote.barId()) && !vote.barId().equals(correction.barId()) && voterBars.add(vote.barId())
                        : phrase.containsKey(vote.barId()) && !phrase.get(vote.barId()).equals(phrase.get(correction.barId()))
                            && voterInstances.add(phrase.get(vote.barId()));
                require(member && votingNote != null && !votingNote.source().equals("external")
                                && votingNote.part() == candidate.part() && votingNote.pitch() == candidate.pitch()
                                && votingNote.start() >= votingBar.start() && votingNote.start() < votingBar.end(),
                        where, "invalid vote from " + vote.barId() + " for " + vote.noteId());
            }
            int votes = phrase == null ? voterBars.size() : voterInstances.size();
            int instances = phrase == null ? group.size() : new HashSet<>(phrase.values()).size();
            require(!correction.applied() || votes * 3 >= instances * 2, where,
                    "applied correction needs votes from at least two thirds of its repeat group");
            require(correction.replaces() == null || correction.replaces().equals(candidate.id())
                            && original.containsKey(correction.replaces()),
                    where, "replacement must keep the original note ID");
            if (correction.externalCandidate() != null) {
                var external = original.get(correction.externalCandidate());
                require(external != null && external.source().equals("external"), where, "unknown external candidate");
            }
        }
        checkNotes(at, result.notes(), score, duration, Set.of("edit", "midi", "audio-refine", "transcriber", "audio-transcribed", ReconstructionAudioOnly.DRUM_SOURCE, ReconstructionStemTemplates.SOURCE, "consensus", ReconstructionFastRuns.REASON, ReconstructionRetriggers.REASON));
    }

    private static Map<String, Set<String>> validateLayout(String at, List<ReconstructionStructure.Section> sections,
                                                               List<ReconstructionStructure.Repeat> repeats,
                                                               List<String> order, Map<String, ReconstructionStructure.Bar> bars) {
        unique(at + ".sections", sections, ReconstructionStructure.Section::id);
        var flattened = sections.stream().flatMap(s -> s.bars().stream()).toList();
        require(flattened.equals(order) && sections.stream().noneMatch(s -> s.bars().isEmpty()),
                at + ".sections", "sections must partition the bars in order");
        unique(at + ".repeats", repeats, ReconstructionStructure.Repeat::id);
        var repeated = new HashMap<String, Set<String>>();
        for (var repeat : repeats) {
            var instances = new HashSet<>(repeat.bars());
            require(instances.size() >= 2 && instances.size() == repeat.bars().size(),
                    at + ".repeats." + repeat.id(), "repeat must have at least two distinct bars");
            for (String id : repeat.bars()) {
                require(bars.containsKey(id) && bars.get(id).complete() && repeated.putIfAbsent(id, instances) == null,
                        at + ".repeats." + repeat.id(), "unknown, partial or already grouped bar " + id);
            }
        }
        return repeated;
    }

    static void rolePlan(ReconstructionRolePlan plan, ReconstructionStructure.Result structure,
                         ReconstructionStages.Score score) {
        var timeline = ReconstructionTimeMap.of(score);
        var sections = structure.sections().stream().map(ReconstructionStructure.Section::id)
                .collect(java.util.stream.Collectors.toSet());
        var parts = partIndex(score);
        for (int i = 0; i < plan.rules().size(); i++) {
            var rule = plan.rules().get(i);
            String at = "roleplan.json.rules[" + i + "]";
            require(rule.sections() != null || rule.bars() != null || rule.startSeconds() != null
                    || rule.endSeconds() != null || rule.section() != null, at, "expected sections, bars or a time bound");
            double duration = plan.timeBase(rule, timeline) == ReconstructionTimeMap.TimeBase.source
                    ? timeline.sourceDuration() : timeline.projectDuration();
            if (rule.sections() != null) {
                require(!rule.sections().isEmpty(), at + ".sections", "expected at least one section");
                for (String id : rule.sections())
                    require(id.equals("*") || sections.contains(id), at + ".sections", "unknown section " + id);
            }
            if (rule.bars() != null)
                ReconstructionRolePlan.BarRange.parse(rule.bars(), structure.bars().size(), at + ".bars");
            var part = parts.get(rule.part());
            require(part != null, at + ".part", "unknown MIDI part " + rule.part());
            require(rule.role() == Role.drop || (rule.role() == Role.drums) == part.drums(), at + ".role",
                    "drums role must match MIDI percussion part");
            require(rule.line() == ReconstructionRolePlan.Voice.all || ReconstructionRolePlan.traced(rule.role()),
                    at + ".voice", "top and bottom require melody, counter or bass");
            require(rule.texture() == null || Set.of("any", "single", "chord").contains(rule.texture()),
                    at + ".texture", "expected any, single or chord");
            int min = rule.pitchMin() == null ? 0 : rule.pitchMin();
            int max = rule.pitchMax() == null ? 127 : rule.pitchMax();
            require(min >= 0 && min <= max && max <= 127, at, "expected 0 <= pitchMin <= pitchMax <= 127");
            double start = rule.startSeconds() == null ? 0 : rule.startSeconds();
            double end = rule.endSeconds() == null ? duration : rule.endSeconds();
            require(start >= 0 && start < end && end <= duration, at,
                    "expected 0 <= startSeconds < endSeconds <= score duration");
        }
    }

    static void roles(ReconstructionRoles.Result roles, ReconstructionStages.Score score) {
        String at = "roles.json";
        var timeline = ReconstructionTimeMap.of(score);
        checkTimeBase(at, roles.timeBase(), timeline, "roles");
        rate(roles.harmonyMaxHz());
        checkNotes(at, roles.notes().stream().map(ReconstructionRoles.Decision::note).toList(),
                score, timeline.projectDuration(), Set.of("edit", "midi", "audio-refine", "transcriber", "audio-transcribed", ReconstructionAudioOnly.DRUM_SOURCE, ReconstructionStemTemplates.SOURCE, "consensus", "audio", ReconstructionFastRuns.REASON, ReconstructionRetriggers.REASON));
        var parts = partIndex(score);
        var notes = new HashMap<String, ReconstructionRoles.Decision>();
        for (var decision : roles.notes()) notes.put(decision.note().id(), decision);
        if (roles.lanePins() != null) for (var target : roles.lanePins().entrySet()) {
            var decision = notes.get(target.getKey());
            var pin = target.getValue();
            require(decision != null && pin.order() > 0 && nonblank(pin.editId())
                            && (pin.laneType() == null
                            ? ReconstructionEdits.pinCompatible(decision.note(), decision.role() == Role.drums,
                                    pin.lane(), score.inputs().fmOnly(), score.inputs().profile())
                            : pin.lane() == null && decision.role() != Role.drop && ReconstructionEdits.laneTypeIssue(
                                    decision.note(), decision.role() == Role.drums, pin.laneType(), score.inputs().fmOnly(), score.inputs().profile()) == null),
                    at + ".lanePins." + target.getKey(), "invalid pin for the assigned note");
        }
        for (var choice : roles.notes()) {
            String where = at + ".notes." + choice.note().id();
            checkRole(parts, choice.note(), choice.role(), where);
            require(choice.role() != Role.drop || nonblank(choice.reason()), where, "a dropped note needs a reason");
            if (choice.preLegatoEnd() != null)
                require(choice.role() == Role.melody && choice.preLegatoEnd() > choice.note().start()
                                && choice.preLegatoEnd() < choice.note().end(),
                        where + ".preLegatoEnd", "expected the original end inside an extended melody note");
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
        var timeline = ReconstructionTimeMap.of(score);
        int projectTicks = timeline.ticks();
        String at = "allocation.json";
        checkTimeBase(at, allocation.timeBase(), timeline, "allocate");
        checkNotes(at, allocation.notes().stream().map(ReconstructionAllocation.Entry::note).toList(),
                score, timeline.projectDuration(), Set.of("edit", "midi", "audio-refine", "transcriber", "audio-transcribed", ReconstructionAudioOnly.DRUM_SOURCE, ReconstructionStemTemplates.SOURCE, "consensus", "audio", ReconstructionFastRuns.REASON, ReconstructionRetriggers.REASON));
        var continuity = allocation.leadContinuity();
        if (continuity != null)
            require(continuity.split() >= 0 && continuity.added() >= 0 && continuity.extended() >= 0
                            && continuity.legatoCount() >= 0,
                    at + ".leadContinuity", "counts must be nonnegative");
        var gapFill = allocation.melodyGapFill();
        if (gapFill != null)
            require(gapFill.afterSeconds() >= 0 && gapFill.beforeSeconds() >= gapFill.afterSeconds(),
                    at + ".melodyGapFill", "expected nonnegative, non-increasing durations");
        var parts = partIndex(score);
        var lanes = new HashMap<String, java.util.ArrayList<ReconstructionAllocation.Entry>>();
        var entries = new HashMap<String, ReconstructionAllocation.Entry>();
        for (var entry : allocation.notes()) entries.put(entry.note().id(), entry);
        var members = ReconstructionChords.validate(allocation.chords(), entries, timeline);
        for (var entry : allocation.notes()) {
            String where = at + ".notes." + entry.note().id();
            checkRole(parts, entry.note(), entry.role(), where);
            if (entry.outcome() == ReconstructionAllocation.Outcome.dropped) {
                require(entry.lane() == null && entry.startTick() == null && entry.endTick() == null
                        && nonblank(entry.reason()), where, "a dropped note needs a reason and no lane or ticks");
                if (entry.mergedInto() != null && !members.contains(entry.note().id())) {
                    var kept = entries.get(entry.mergedInto());
                    // An already merged note may itself have overflowed during allocation.
                    require(entry.role() == Role.drop && kept != null && kept.role() != Role.drop
                                    && kept.note().part() == entry.note().part() && kept.note().pitch() == entry.note().pitch()
                                    && kept.note().start() < entry.note().start(), where, "invalid merge target");
                }
                continue;
            }
            String lane = entry.lane();
            boolean pinned = entry.pinnedLane() != null && entry.pinnedLane().equals(lane) && entry.role() != Role.drop
                    && (entry.laneType() == null
                    ? ReconstructionEdits.pinCompatible(entry.note(), entry.role() == Role.drums, lane, score.inputs().fmOnly(), score.inputs().profile())
                    : entry.laneType().lanes().contains(lane) && ReconstructionEdits.laneTypeIssue(
                            entry.note(), entry.role() == Role.drums, entry.laneType(), score.inputs().fmOnly(), score.inputs().profile()) == null);
            require(entry.laneType() == null || entry.pinnedLane() != null, where, "lane type requires a pinned placement");
            require(lane != null && (entry.pinnedLane() != null ? pinned : switch (entry.role()) {
                case melody -> lane.equals("FM1");
                case bass -> lane.equals("FM2");
                case drums -> lane.equals("DAC") || lane.equals("PSG3") && !score.inputs().fmOnly();
                case harmony, counter -> ReconstructionAllocation.INNER_FM.contains(lane)
                        || ReconstructionAllocation.PSG.contains(lane) && !score.inputs().fmOnly() && entry.note().pitch() >= score.inputs().profile().psg().low();
                case drop -> false;
            }), where, "invalid lane for " + entry.role());
            require(entry.startTick() != null && entry.endTick() != null && entry.startTick() >= 0
                            && entry.startTick() < entry.endTick() && entry.endTick() <= projectTicks
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

    private static void checkTimeBase(String at, ReconstructionTimeMap.TimeBase timeBase,
                                      ReconstructionTimeMap timeline, String stage) {
        var actual = timeBase == null ? ReconstructionTimeMap.TimeBase.source : timeBase;
        require(actual == timeline.timeBase(), at + ".timeBase",
                "expected " + timeline.timeBase() + "; regenerate from structure after changing tempo "
                        + "or rerun " + stage + " with current upstream artifacts");
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

    private static void checkNotes(String at, List<Note> notes, ReconstructionStages.Score score,
                                   double duration, Set<String> sources) {
        unique(at + ".notes", notes, Note::id);
        var parts = partIndex(score);
        for (var note : notes) {
            String where = at + ".notes." + note.id();
            note(note, parts.keySet(), duration, where);
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
        if (note.run() != null) {
            require((note.source().equals(ReconstructionFastRuns.REASON)
                            || note.source().equals(ReconstructionRetriggers.REASON)) && note.run().size() >= 4
                            && note.pitch() == note.run().getFirst().pitch(), at + ".run", "invalid figure");
            double start = note.start();
            for (var step : note.run()) {
                require(step.start() == start && step.end() > start && step.pitch() >= 12 && step.pitch() <= 106,
                        at + ".run", "expected contiguous positive-duration steps in S3K range");
                start = step.end();
            }
            require(start == note.end(), at + ".run", "last step must end with the figure");
        }
        if (note.bend() != null) {
            var bend = note.bend();
            require(note.run() == null && bend.kind() != null && bend.points().size() >= 2
                            && bend.points().size() <= 512 && bend.delay() >= 0 && Double.isFinite(bend.delay())
                            && bend.confidence() >= .8 && bend.confidence() <= 1,
                    at + ".bend", "invalid bend envelope");
            double prior = -1;
            for (var point : bend.points()) {
                require(point.time() > prior && point.time() >= 0 && point.time() <= duration
                                && Math.abs((long) point.cents()) <= ReconstructionBendEncoding.MAX_CENTS,
                        at + ".bend", "expected ordered finite pitch points in the score interval");
                prior = point.time();
            }
        }
        if (note.motion() != null) {
            var motion = note.motion();
            require(motion.vibrato() >= 0 && motion.vibrato() <= 1 && motion.glide() >= 0 && motion.glide() <= 1,
                    at + ".motion", "motion scores must be within 0..1");
        }
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
