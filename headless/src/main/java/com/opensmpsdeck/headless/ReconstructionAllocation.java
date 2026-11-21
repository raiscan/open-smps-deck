package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

final class ReconstructionAllocation {
    static final List<String> FM = List.of("FM1", "FM2", "FM3", "FM4", "FM5");
    static final List<String> PSG = List.of("PSG1", "PSG2", "PSG3");
    static final List<String> INNER_FM = FM.subList(2, FM.size());
    private static final String VOICE_ASSIGNMENT = "voice:assignment";
    private static final int RELEASE_TICKS = 2;
    private static final int SUSTAINED_SPILL_TICKS = 6;
    private static final int MAX_STAB_TICKS = 18;
    private static final int MIN_PAD_TICKS = 18;
    private static final int MIN_RESUMED_TICKS = 6;

    enum Outcome { kept, shortened, dropped }
    record Entry(Note note, Role role, @Nullable String lane, @Nullable Integer startTick, @Nullable Integer endTick,
                 Outcome outcome, @Nullable String reason, @Nullable String mergedInto,
                 @Nullable String competitorNoteId, @Nullable List<String> blockingNoteIds,
                 @Nullable String relocatedFrom, @Nullable String pinnedLane, @Nullable ReconstructionLaneType laneType) {
        Entry(Note note, Role role, String lane, Integer startTick, Integer endTick, Outcome outcome,
              String reason, String mergedInto, String competitorNoteId, List<String> blockingNoteIds,
              String relocatedFrom, String pinnedLane) {
            this(note, role, lane, startTick, endTick, outcome, reason, mergedInto, competitorNoteId,
                    blockingNoteIds, relocatedFrom, pinnedLane, null);
        }

        Entry(Note note, Role role, String lane, Integer startTick, Integer endTick, Outcome outcome,
              String reason, String mergedInto, String competitorNoteId, List<String> blockingNoteIds,
              String relocatedFrom) {
            this(note, role, lane, startTick, endTick, outcome, reason, mergedInto, competitorNoteId,
                    blockingNoteIds, relocatedFrom, null);
        }
        Entry(Note note, Role role, String lane, Integer startTick, Integer endTick,
              Outcome outcome, String reason, String mergedInto) {
            this(note, role, lane, startTick, endTick, outcome, reason, mergedInto, null, null, null);
        }
        Entry shorten(int end, String why) {
            return shorten(end, why, null);
        }

        Entry shorten(int end, String why, String competitor) {
            return new Entry(note, role, lane, startTick, end, Outcome.shortened, why, null, competitor, null,
                    relocatedFrom, pinnedLane, laneType);
        }
    }
    record Result(int version, List<Entry> notes, @Nullable ReconstructionTimeMap.TimeBase timeBase,
                  @Nullable ReconstructionLeadContinuity.Stats leadContinuity,
                  @Nullable ReconstructionMelodyGapFill.Stats melodyGapFill,
                  @Nullable Fm1Gaps fm1InterNoteGaps,
                  @Nullable List<RunPlacement> fastRuns,
                  @Nullable Map<String, ReconstructionChords.Group> chordVoicings) {
        Result(int version, List<Entry> notes, ReconstructionTimeMap.TimeBase timeBase,
               ReconstructionLeadContinuity.Stats leadContinuity, ReconstructionMelodyGapFill.Stats melodyGapFill,
               Fm1Gaps fm1InterNoteGaps, List<RunPlacement> fastRuns) {
            this(version, notes, timeBase, leadContinuity, melodyGapFill, fm1InterNoteGaps, fastRuns, null);
        }

        Map<String, ReconstructionChords.Group> chords() {
            return chordVoicings == null ? Map.of() : chordVoicings;
        }

        Result(int version, List<Entry> notes, ReconstructionTimeMap.TimeBase timeBase) {
            this(version, notes, timeBase, null, null, null, null);
        }

        Result(int version, List<Entry> notes) {
            this(version, notes, null);
        }
    }

    record RunPlacement(String id, @Nullable String lane, @Nullable Integer startTick, @Nullable Integer endTick,
                        List<Integer> retainedSteps, String reason) {}

    record Fm1Gaps(Map<Integer, Integer> before, Map<Integer, Integer> after) {
        static Fm1Gaps of(List<Entry> allocated, List<ReconstructionRoles.Decision> decisions, int frames) {
            var originalEnds = new HashMap<String, Double>();
            for (var decision : decisions) {
                if (decision.preLegatoEnd() != null)
                    originalEnds.put(decision.note().id(), decision.preLegatoEnd());
            }
            var before = new TreeMap<Integer, Integer>();
            var after = new TreeMap<Integer, Integer>();
            var lead = allocated.stream().filter(entry -> "FM1".equals(entry.lane))
                    .sorted(Comparator.comparingInt(Entry::startTick)).toList();
            for (int i = 1; i < lead.size(); i++) {
                var prior = lead.get(i - 1);
                int onset = lead.get(i).startTick;
                int end = prior.endTick;
                var original = originalEnds.get(prior.note.id());
                int beforeEnd = original == null ? end : Math.min(end,
                        prior.note.withTiming(prior.note.start(), original).endTick(frames));
                before.merge(onset - beforeEnd, 1, Integer::sum);
                after.merge(onset - end, 1, Integer::sum);
            }
            return new Fm1Gaps(before, after);
        }
    }
    private record Attack(ReconstructionRoles.Decision decision, int start, int end, boolean noise,
                          ReconstructionEdits.Pin pin, String cymbalRoute, boolean fmRequired) {
        Attack withChord(ReconstructionChords.Group chord) {
            return new Attack(decision, start, chord.endTick(), noise, pin, cymbalRoute, chord.fmOnly());
        }
        Note note() { return decision.note(); }
        Role role() { return decision.role(); }

        Attack onNoise(String reason, int frames) {
            return new Attack(decision, start, Math.min(frames, start + ReconstructionCymbals.gate(note().pitch())),
                    true, null, reason, false);
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, Entry> tail = new HashMap<>();
    private final boolean fmOnly;
    private final NavigableMap<Integer, Integer> cymbals = new TreeMap<>();
    private final java.util.NavigableSet<Integer> explicitNoise = new java.util.TreeSet<>();
    private final Map<String, ReconstructionChordReduction.Tone> tones;
    private final Map<String, Double> salience;
    private final Map<String, String> previousLanes;
    private final java.util.Set<String> chordAnchors = new java.util.HashSet<>();
    private final java.util.Set<String> fmChordAnchors = new java.util.HashSet<>();
    private final Map<String, String> continuations = new HashMap<>();
    private final java.util.PriorityQueue<Attack> pending;
    private final Map<String, NavigableMap<Integer, String>> previousAttacks = new HashMap<>();

    private ReconstructionAllocation(boolean fmOnly, Map<String, String> previousLanes, List<Attack> attacks,
                                     Map<String, ReconstructionChordReduction.Tone> tones, Map<String, Double> salience) {
        this.fmOnly = fmOnly;
        this.previousLanes = previousLanes;
        this.tones = tones;
        this.salience = salience;
        var ordinal = new HashMap<String, Integer>();
        for (int i = 0; i < attacks.size(); i++) ordinal.put(attacks.get(i).note().id(), i);
        pending = new java.util.PriorityQueue<>(Comparator.comparingInt(Attack::start)
                .thenComparing(a -> continuations.containsKey(a.note().id()))
                .thenComparing(attackOrder())
                .thenComparingInt(a -> ordinal.getOrDefault(a.note().id(), Integer.MAX_VALUE))
                .thenComparing(a -> a.note().id()));
        for (var attack : attacks) {
            String lane = previousLanes.get(attack.note().id());
            if (lane == null || attack.pin != null || attack.role() == Role.drop) continue;
            if (expectedLanes(attack.role(), attack.noise).contains(lane) || spillLanes(attack).contains(lane))
                previousAttacks.computeIfAbsent(lane, ignored -> new TreeMap<>()).put(attack.start, attack.note().id());
        }
    }

    record LaneChange(String noteId, @Nullable String previousLane, @Nullable String freshLane) {}

    record Affinity(int version, Map<String, String> seed, List<LaneChange> changes, String allocationHash) {
        static final String FILE = "allocation-affinity.json";

        static Affinity read(Path work) throws IOException {
            var path = work.resolve(FILE);
            return Files.exists(path) ? ReconstructionArtifacts.read(path, Affinity.class) : null;
        }

        static Affinity compare(Map<String, String> seed, Result actual, Result fresh) {
            var chosen = lanes(actual);
            var clean = lanes(fresh);
            var ids = new java.util.TreeSet<>(chosen.keySet());
            ids.addAll(clean.keySet());
            var differences = ids.stream().filter(id -> !java.util.Objects.equals(chosen.get(id), clean.get(id)))
                    .map(id -> new LaneChange(id, chosen.get(id), clean.get(id))).toList();
            return new Affinity(ReconstructionStages.VERSION, seed, differences, ReconstructionArtifacts.fingerprint(actual));
        }
    }

    static Map<String, String> lanes(Result allocation) {
        if (allocation == null) return Map.of();
        var lanes = new TreeMap<String, String>();
        for (var entry : allocation.notes()) {
            if (entry.lane() != null) lanes.put(entry.note().id(), entry.lane());
        }
        if (allocation.fastRuns() != null) for (var run : allocation.fastRuns()) {
            if (run.lane() != null) lanes.put(run.id(), run.lane());
        }
        return lanes;
    }

    static Result allocate(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames, boolean fmOnly) {
        return allocate(roles, parts, frames, fmOnly, false);
    }

    static Result allocate(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames, boolean fmOnly,
                           boolean hasStems) {
        return allocate(roles, parts, frames, fmOnly, hasStems, null);
    }

    static Result allocate(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames, boolean fmOnly,
                           boolean hasStems, Result previous) {
        return allocateWithAffinity(roles, parts, frames, fmOnly, hasStems, lanes(previous));
    }

    static Result allocate(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames, boolean fmOnly,
                           boolean hasStems, Result previous, Map<ReconstructionVoices.Key, Double> cymbalLevels) {
        return allocateWithAffinity(roles, parts, frames, fmOnly, hasStems, lanes(previous), cymbalLevels);
    }

    static Result allocateWithAffinity(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames,
                                       boolean fmOnly, boolean hasStems, Map<String, String> seed) {
        return allocateWithAffinity(roles, parts, frames, fmOnly, hasStems, seed, Map.of());
    }

    static Result allocateWithAffinity(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames,
                                       boolean fmOnly, boolean hasStems, Map<String, String> seed,
                                       Map<ReconstructionVoices.Key, Double> cymbalLevels) {
        return allocateWithAffinity(roles, parts, frames, fmOnly, hasStems, seed, cymbalLevels, Map.of());
    }

    static Result allocateWithAffinity(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames,
                                       boolean fmOnly, boolean hasStems, Map<String, String> seed,
                                       Map<ReconstructionVoices.Key, Double> cymbalLevels,
                                       Map<String, ReconstructionLaneType> voiceTypes) {
        return allocateWithAffinity(roles, parts, frames, fmOnly, hasStems, seed, cymbalLevels,
                voiceTypes, Map.of(), null, Map.of(), false);
    }

    static Result allocateWithAffinity(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames,
                                       boolean fmOnly, boolean hasStems, Map<String, String> seed,
                                       Map<ReconstructionVoices.Key, Double> cymbalLevels,
                                       Map<String, ReconstructionLaneType> voiceTypes,
                                       Map<String, ReconstructionChords.Group> chords,
                                       List<ReconstructionEdits.Outcome> outcomes, Map<String, Double> salience, boolean chordReduction) {
        var names = new HashMap<Integer, String>();
        for (var part : parts) names.put(part.id(), part.name());
        var attacks = roles.notes().stream().map(decision -> {
            Note note = decision.note();
            int start = note.startTick(frames);
            boolean drum = decision.role() == Role.drums;
            var pin = roles.lanePins() == null ? null : roles.lanePins().get(note.id());
            var voiceType = voiceTypes.get(note.id());
            if (pin == null && voiceType != null)
                pin = new ReconstructionEdits.Pin(null, VOICE_ASSIGNMENT, Integer.MAX_VALUE, voiceType);
            boolean noise = drum && (pin == null ? !fmOnly && isNoise(names.get(note.part()))
                    : "PSG3".equals(pin.lane()) || pin.laneType() == ReconstructionLaneType.NOISE);
            int end = drum && !noise ? start + 1 : note.endTick(frames);
            if (noise && pin != null && pin.laneType() == ReconstructionLaneType.NOISE && ReconstructionCymbals.eligible(note.pitch()))
                end = Math.min(frames, start + ReconstructionCymbals.gate(note.pitch()));
            return new Attack(decision, start, end, noise, pin, null, false);
        }).sorted(attackOrder()).toList();
        // Pick the first attack in normal allocation priority as the representative.
        // Its peers share the same tick and can fall back to normal placement if it cannot fit.
        var anchors = new HashMap<ReconstructionChords.Group, String>();
        var anchorChords = new TreeMap<String, ReconstructionChords.Group>();
        for (var attack : attacks) {
            var chord = chords.get(attack.note().id());
            if (chord != null && anchors.putIfAbsent(chord, attack.note().id()) == null)
                anchorChords.put(attack.note().id(), chord);
        }
        var scheduled = chords.isEmpty() ? attacks : attacks.stream().filter(attack -> {
            var chord = chords.get(attack.note().id());
            return chord == null || anchorChords.containsKey(attack.note().id());
        }).map(attack -> {
            var chord = anchorChords.get(attack.note().id());
            return chord == null ? attack : attack.withChord(chord);
        }).toList();
        var tones = chordReduction ? ReconstructionChordReduction.classify(attacks.stream().filter(a -> a.role() != Role.drums && a.role() != Role.drop)
                .map(a -> new ReconstructionChordReduction.Voice(a.note(), a.role())).toList()) : Map.<String, ReconstructionChordReduction.Tone>of();
        var allocator = new ReconstructionAllocation(fmOnly, seed, scheduled, tones, salience);
        var louderParts = new java.util.HashSet<Integer>();
        cymbalLevels.keySet().forEach(key -> louderParts.add(key.partId()));
        cymbalLevels.forEach((key, level) -> {
            if (ReconstructionCymbals.quiet(level)) louderParts.remove(key.partId());
        });
        var remaining = new ArrayList<Attack>();
        // Resolve the DAC first so tonal spill can avoid upcoming noise hits.
        // Both passes retain the same attack ordering within their priority tiers.
        for (var attack : attacks) {
            var voiceType = voiceTypes.get(attack.note().id());
            if (attack.role() != Role.drop && voiceType != null) {
                var issue = ReconstructionEdits.laneTypeIssue(attack.note(), attack.role() == Role.drums, voiceType, fmOnly);
                var pin = attack.pin;
                if (issue == null && (pin.laneType() != null ? pin.laneType() != voiceType
                        : !voiceType.lanes().contains(pin.lane())))
                    issue = "conflicts with " + pin.tag() + " " + pin.target();
                if (issue != null) {
                    allocator.drop(attack, "voice:assignment " + voiceType + " " + issue);
                    continue;
                }
            }
            if (attack.noise || attack.role() != Role.drop && attack.pin != null
                    && ("PSG3".equals(attack.pin.lane()) || attack.pin.laneType() == ReconstructionLaneType.NOISE))
                allocator.explicitNoise.add(attack.start);
            if (attack.role() != Role.drums || attack.noise) {
                remaining.add(attack);
            } else if (attack.pin != null) {
                allocator.placePinned(attack);
            } else {
                var cymbal = allocator.placeDac(attack, cymbalLevels, louderParts, frames);
                if (cymbal != null) remaining.add(cymbal);
            }
        }
        remaining.sort(attackOrder());
        var placedChords = new TreeMap<String, ReconstructionChords.Group>();
        allocator.pending.addAll(remaining);
        while (!allocator.pending.isEmpty()) {
            var attack = allocator.pending.remove();
            var chord = chords.get(attack.note().id());
            if (chord == null) {
                if (!hasStems || !allocator.dropCoveredUnison(attack)) allocator.place(attack);
                continue;
            }
            String anchor = anchors.get(chord);
            if (attack.note().id().equals(anchor)) {
                allocator.place(attack.withChord(chord));
                boolean placed = allocator.entries.get(anchor).lane() != null;
                if (placed) {
                    placedChords.put(anchor, chord);
                    allocator.chordAnchors.add(anchor);
                    if (chord.fmOnly()) allocator.fmChordAnchors.add(anchor);
                } else allocator.place(attack);
                if (outcomes != null) outcomes.add(new ReconstructionEdits.Outcome(chord.editId(),
                        chord.arpeggio() ? ReconstructionEdits.Kind.arpeggio : ReconstructionEdits.Kind.chordInstrument,
                        placed ? ReconstructionEdits.Status.applied : ReconstructionEdits.Status.conflict,
                        chord.members().stream().map(ReconstructionChords.Member::id).toList(), null,
                        placed ? chord.label() : "no compatible free lane for " + chord.label(), null));
            } else if (placedChords.containsKey(anchor)) {
                allocator.entries.put(attack.note().id(), new Entry(attack.note(), attack.role(), null, null, null,
                        Outcome.dropped, chord.reason(), anchor));
            } else {
                allocator.place(attack);
            }
        }
        var continuity = ReconstructionLeadContinuity.Stats.of(roles.notes());
        var placed = new ArrayList<Entry>();
        var runs = new ArrayList<RunPlacement>();
        for (var attack : attacks) {
            var entry = allocator.entries.get(attack.note().id());
            var figure = entry.note().run();
            if (figure == null) {
                placed.add(entry);
                continue;
            }
            if (entry.lane() == null) {
                placed.add(entry);
                runs.add(new RunPlacement(entry.note().id(), null, null, null, List.of(), entry.reason()));
                continue;
            }
            var steps = ReconstructionFastRuns.quantize(figure, entry.startTick(), entry.endTick());
            boolean merged = steps.size() < figure.size();
            String reason = merged ? entry.note().source() + "-subframe-merge" : entry.note().source();
            runs.add(new RunPlacement(entry.note().id(), entry.lane(), entry.startTick(), entry.endTick(),
                    steps.stream().map(ReconstructionFastRuns.Frames::firstStep).toList(), reason));
            for (var step : steps) {
                var evidence = figure.get(step.firstStep());
                var note = new Note(entry.note().id() + "-step-" + step.firstStep(), entry.note().part(),
                        evidence.start(), evidence.end(), step.pitch(), entry.note().velocity(),
                        entry.note().source(), entry.note().confidence());
                placed.add(new Entry(note, entry.role(), entry.lane(), step.start(), step.end(), Outcome.kept,
                        entry.pinnedLane() == null ? reason : entry.reason(), null, null, null, null, entry.pinnedLane(), entry.laneType()));
            }
        }
        allocator.continuations.keySet().stream().map(allocator.entries::get).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Entry::startTick).thenComparing(e -> e.note.id())).forEach(placed::add);
        return new Result(VERSION, List.copyOf(placed), roles.timeBase(), !hasStems && continuity.isEmpty() ? null : continuity,
                ReconstructionMelodyGapFill.Stats.of(roles.notes()),
                hasStems ? Fm1Gaps.of(placed, roles.notes(), frames) : null, runs.isEmpty() ? null : List.copyOf(runs),
                placedChords.isEmpty() ? null : placedChords);
    }

    private static Comparator<Attack> attackOrder() {
        return Comparator.comparingInt(Attack::start)
                .thenComparingInt(a -> a.pin == null ? Integer.MAX_VALUE : a.pin.order())
                .thenComparing(Comparator.comparingInt(ReconstructionAllocation::priority).reversed())
                .thenComparingInt(a -> a.role() == Role.harmony && a.note().pitch() < 45 ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Attack a) -> a.note().velocity()).reversed())
                .thenComparingInt(a -> a.role() == Role.harmony || a.role() == Role.counter ? a.start - a.end : 0)
                .thenComparing(Attack::note, ReconstructionStages.NOTE_ORDER);
    }

    /** Project a kept lane layout back into source time for the existing voice probes. */
    static Result sourcePlacement(Result allocation, int frames, ReconstructionTimeMap timeline) {
        if (!timeline.warped()) return allocation;
        var entries = new ArrayList<Entry>();
        var nextAttack = new HashMap<String, Integer>();
        var placement = allocation.notes().stream().filter(entry -> entry.lane != null)
                .sorted(Comparator.comparingInt(Entry::startTick).reversed()).toList();
        for (var entry : placement) {
            var note = timeline.sourceNote(entry.note);
            int start = note.startTick(frames);
            int next = nextAttack.getOrDefault(entry.lane, frames);
            if (start >= next) continue;
            int end = entry.lane.equals("DAC") ? start + 1 : note.endTick(frames);
            if (ReconstructionCymbals.voiced(entry))
                end = Math.min(frames, start + entry.endTick - entry.startTick);
            if (allocation.chords().containsKey(entry.note.id())) {
                end = Math.clamp((int) Math.round(timeline.source(entry.endTick / 60.0) * 60), start + 1, frames);
            } else if (entry.outcome == Outcome.shortened) {
                int shortened = (int) Math.round(timeline.source(entry.endTick / 60.0) * 60);
                end = Math.min(end, Math.max(start + 1, shortened));
            }
            entries.add(new Entry(note, entry.role, entry.lane, start, Math.min(end, next),
                    entry.outcome, entry.reason, entry.mergedInto));
            nextAttack.put(entry.lane, start);
        }
        return new Result(allocation.version, entries.stream().sorted(Comparator.comparingInt(Entry::startTick)).toList());
    }

    static boolean isNoise(String name) {
        return name.equalsIgnoreCase("PSG3") || name.strip().equalsIgnoreCase("PSG noise");
    }

    private static int priority(Attack attack) {
        return switch (attack.role()) {
            case melody, bass -> attack.note().run() == null ? 12 : 13;
            case drums -> attack.cymbalRoute != null ? 4
                    : 8 + S3kReconstruction.drumPriority(attack.note().pitch());
            case counter -> 5;
            case harmony -> 3;
            case drop -> 0;
        };
    }

    private static boolean backing(Role role) {
        return role == Role.harmony || role == Role.counter;
    }

    private String origin(Note note) {
        return continuations.getOrDefault(note.id(), note.id());
    }

    private int tonePriority(Note note) {
        return tones.getOrDefault(origin(note), ReconstructionChordReduction.Tone.extension).priority;
    }

    private double salience(Note note) {
        return salience.getOrDefault(origin(note), 0.0);
    }

    private boolean dropCoveredUnison(Attack attack) {
        // A merged MIDI part can contain real written doublings. Remove only
        // repeat-consensus fills, which should not double a retained attack.
        if (!backing(attack.role()) || !attack.note().source().equals("consensus")
                || attack.pin != null || attack.note().run() != null) return false;
        var cover = tail.values().stream()
                .filter(entry -> entry.role() != Role.drums && entry.pinnedLane() == null
                        && entry.note().run() == null && !chordAnchors.contains(entry.note().id())
                        && entry.note().part() == attack.note().part() && entry.note().pitch() == attack.note().pitch()
                        && entry.startTick() <= attack.start && entry.endTick() >= attack.end)
                .min(Comparator.comparingInt(Entry::startTick).thenComparing(entry -> entry.note().id())).orElse(null);
        if (cover == null) return false;
        drop(attack, "covered-source-unison", List.of(cover.note().id()));
        return true;
    }

    private void place(Attack attack) {
        if (attack.role() == Role.drop) {
            drop(attack, attack.decision.reason());
            return;
        }
        if (attack.pin != null) {
            placePinned(attack);
            return;
        }
        if (attack.cymbalRoute != null) {
            placeCymbal(attack);
            return;
        }
        List<String> primary = expectedLanes(attack.role(), attack.noise);
        List<String> secondary = spillLanes(attack);
        String lane = free(primary, attack);
        if (lane == null) lane = free(secondary, attack);
        if (lane == null && primary == INNER_FM && secondary.isEmpty() && !fmOnly)
            lane = freeFmForLowAttack(attack);
        if (lane == null && backing(attack.role()) && !tones.isEmpty()) {
            var eligible = new ArrayList<>(primary);
            eligible.addAll(secondary);
            lane = shorten(eligible, attack);
        } else if (lane == null) {
            lane = shorten(primary, attack);
            if (lane == null) lane = shorten(secondary, attack);
        }
        if (lane == null && secondary.size() == 2 && attack.end - attack.start >= SUSTAINED_SPILL_TICKS)
            lane = sustainedSpill(attack);
        if (lane == null) {
            drop(attack, switch (attack.role()) {
                case melody -> "melody-lane-busy";
                case bass -> "bass-lane-busy";
                case drums -> "psg-noise-lane-busy";
                default -> secondary.isEmpty() ? "fm-lanes-busy" : "fm-and-psg-lanes-busy";
            }, java.util.stream.Stream.concat(primary.stream(), secondary.stream())
                    .map(tail::get).filter(java.util.Objects::nonNull).map(entry -> entry.note.id()).toList());
        } else {
            String reason = continuations.containsKey(attack.note().id()) ? "pad-resumed"
                    : PSG.contains(lane) && !attack.noise ? "psg-spill" : null;
            keep(attack, lane, reason);
        }
    }

    private String sustainedSpill(Attack attack) {
        String lane = "PSG3";
        Entry active = tail.get(lane);
        if (active == null || active.endTick <= attack.start) return lane;
        if (!ReconstructionCymbals.automatic(active.reason)) return null;
        if (active.startTick == attack.start) {
            entries.put(active.note.id(), new Entry(active.note, active.role, null, null, null, Outcome.dropped,
                    "psg-noise-lane-busy", null, attack.note().id(), null, null));
        } else {
            shortenCymbal(active, attack);
        }
        return lane;
    }

    private Attack placeDac(Attack attack, Map<ReconstructionVoices.Key, Double> cymbalLevels,
                            java.util.Set<Integer> louderParts, int frames) {
        Entry last = tail.get("DAC");
        int pitch = attack.note().pitch();
        String blocked = null;
        if (last != null && last.startTick == attack.start) {
            blocked = "simultaneous-dac-hit";
        } else if (last != null && attack.start - last.startTick < 5
                && S3kReconstruction.drumPriority(last.note.pitch()) >= 2
                && S3kReconstruction.drumPriority(last.note.pitch()) > S3kReconstruction.drumPriority(pitch)) {
            blocked = "protect-dac-tail";
        }
        if (!fmOnly && ReconstructionCymbals.eligible(pitch)) {
            Double level = cymbalLevels.get(ReconstructionCymbals.key(attack.note()));
            boolean quiet = ReconstructionCymbals.quiet(level);
            // Preserve the existing DAC strategy when the stem calls for a
            // cymbal louder than a faithful PSG substitute can reproduce.
            if (quiet || blocked != null && level == null && !louderParts.contains(attack.note().part())) {
                var routed = attack.onNoise(blocked != null ? ReconstructionCymbals.COLLISION : ReconstructionCymbals.QUIET, frames);
                cymbals.merge(routed.start, routed.end, Math::max);
                return routed;
            }
        }
        if (blocked == null) keep(attack, "DAC", null);
        else drop(attack, blocked, List.of(last.note.id()));
        return null;
    }

    private void placeCymbal(Attack attack) {
        Entry active = tail.get("PSG3");
        if (active != null && active.endTick > attack.start) {
            if (!ReconstructionCymbals.automatic(active.reason) || active.startTick == attack.start) {
                drop(attack, "psg-noise-lane-busy", List.of(active.note.id()));
                return;
            }
            shortenCymbal(active, attack);
        }
        var next = explicitNoise.higher(attack.start);
        int end = next == null ? attack.end : Math.min(attack.end, next);
        keep(new Attack(attack.decision, attack.start, end, true, null, attack.cymbalRoute, false),
                "PSG3", attack.cymbalRoute);
    }

    private void shortenCymbal(Entry earlier, Attack next) {
        entries.put(earlier.note.id(), new Entry(earlier.note, earlier.role, earlier.lane, earlier.startTick,
                next.start, Outcome.shortened, earlier.reason, null, next.note().id(), null, null));
    }

    private void placePinned(Attack attack) {
        if (attack.pin.laneType() != null) {
            placeByType(attack);
            return;
        }
        String lane = attack.pin.lane();
        Entry last = tail.get(lane);
        if (last != null && last.endTick > attack.start) {
            if (last.pinnedLane != null || last.startTick >= attack.start) {
                drop(attack, attack.pin.tag() + " pinned-lane-busy", List.of(last.note.id()));
                return;
            }
            entries.put(last.note.id(), last.shorten(attack.start, attack.pin.tag() + " pinned-attack", attack.note().id()));
        }
        keep(attack, lane, attack.pin.tag());
    }

    private void placeByType(Attack attack) {
        var type = attack.pin.laneType();
        var lanes = type.lanes();
        String previous = previousLanes.get(attack.note().id());
        String lane = null;
        if (attack.role() == Role.melody && type == ReconstructionLaneType.FM
                && VOICE_ASSIGNMENT.equals(attack.pin.tag())) {
            // An instrument assignment does not need another chip channel
            // for a release overlap on the normal melody lane.
            var melody = expectedLanes(attack.role(), attack.noise);
            lane = free(melody, attack);
            if (lane == null) lane = shorten(melody, attack);
        }
        if (lane == null && previous != null && lanes.contains(previous) && available(previous, attack)) lane = previous;
        if (lane == null) {
            var preferred = expectedLanes(attack.role(), attack.noise).stream().filter(lanes::contains).toList();
            lane = free(preferred, attack);
        }
        if (lane == null) lane = free(lanes, attack);
        if (lane == null) {
            drop(attack, attack.pin.tag() + " " + type + " lanes busy",
                    lanes.stream().map(tail::get).map(entry -> entry.note().id()).toList());
        } else {
            keep(attack, lane, attack.pin.tag() + " laneType=" + type);
        }
    }

    private boolean available(String lane, Attack attack) {
        var last = tail.get(lane);
        return last == null || last.endTick <= attack.start;
    }

    static List<String> expectedLanes(Role role, boolean noise) {
        return switch (role) {
            case melody -> List.of("FM1");
            case bass -> List.of("FM2");
            case drums -> List.of(noise ? "PSG3" : "DAC");
            case harmony, counter -> INNER_FM;
            case drop -> List.of();
        };
    }

    private List<String> spillLanes(Attack attack) {
        if (expectedLanes(attack.role(), attack.noise) != INNER_FM || fmOnly || attack.fmRequired
                || attack.note().pitch() < 45 || attack.note().run() != null) return List.of();
        return attack.role() == Role.harmony && overlapsCymbal(attack.start, attack.end) ? PSG.subList(0, 2) : PSG;
    }

    private boolean overlapsCymbal(int start, int end) {
        return cymbals.subMap(Math.max(0, start - ReconstructionCymbals.MAX_GATE + 1), true, end, false).values().stream().anyMatch(tail -> tail > start);
    }

    private String free(List<String> lanes, Attack attack) {
        var original = entries.get(continuations.get(attack.note().id()));
        String previous = original == null ? previousLanes.get(attack.note().id()) : original.lane();
        // Affinity depends on the preceding attack. Let the old placement break that
        // dependency when the lane is just as playable, without bypassing FM priority.
        return lanes.stream().filter(lane -> available(lane, attack))
                .min(Comparator.comparing((String lane) -> !lane.equals(previous))
                        .thenComparing(lane -> reserved(lane, attack))
                        .thenComparingInt(lane -> affinity(tail.get(lane), attack))).orElse(null);
    }

    private boolean reserved(String lane, Attack attack) {
        var schedule = previousAttacks.get(lane);
        var next = schedule == null ? null : schedule.ceilingEntry(attack.start);
        return next != null && next.getKey() < attack.end && !next.getValue().equals(attack.note().id());
    }

    private static int affinity(Entry last, Attack attack) {
        if (last == null) return 64;
        int gap = attack.start - last.endTick;
        int distance = Math.abs(last.note.pitch() - attack.note().pitch());
        if (last.role == Role.drums) return 80;
        return (last.note.part() == attack.note().part() ? 0 : 64) + distance + Math.min(24, gap);
    }

    private String freeFmForLowAttack(Attack attack) {
        for (String lane : INNER_FM.reversed()) {
            Entry prior = tail.get(lane);
            if (prior == null || prior.pinnedLane != null || prior.note.pitch() < 45 || prior.note.run() != null
                    || fmChordAnchors.contains(prior.note.id())) continue;
            for (String destination : PSG) {
                if (destination.equals("PSG3") && prior.role == Role.harmony && overlapsCymbal(prior.startTick, prior.endTick)) continue;
                Entry psg = tail.get(destination);
                if (psg != null && psg.endTick > prior.startTick) continue;
                var moved = new Entry(prior.note, prior.role, destination, prior.startTick, prior.endTick,
                        prior.outcome, prior.reason == null ? "relocated-for-low-fm-note" : prior.reason, null,
                        prior.competitorNoteId == null ? attack.note().id() : prior.competitorNoteId, null, lane);
                tail.put(destination, moved);
                entries.put(prior.note.id(), moved);
                return lane;
            }
        }
        return null;
    }

    private String shorten(List<String> lanes, Attack attack) {
        String lane = lanes.stream().filter(l -> canShorten(tail.get(l), attack))
                .min(Comparator.comparingInt((String l) -> backing(attack.role()) ? tonePriority(tail.get(l).note()) : 0)
                        .thenComparingDouble(l -> backing(attack.role()) ? salience(tail.get(l).note()) : 0)
                        .thenComparingInt(l -> tail.get(l).endTick - attack.start)
                        .thenComparing(l -> !l.equals(previousLanes.get(attack.note().id())))).orElse(null);
        if (lane != null) {
            Entry earlier = tail.get(lane);
            if (ReconstructionCymbals.automatic(earlier.reason)) {
                shortenCymbal(earlier, attack);
                return lane;
            }
            if (earlier.startTick == attack.start) {
                if (continuations.containsKey(earlier.note.id())) entries.remove(earlier.note.id());
                else entries.put(earlier.note.id(), new Entry(earlier.note, earlier.role, null, null, null,
                        Outcome.dropped, "higher-priority-chord-tone", null, attack.note().id(), null, null));
                return lane;
            }
            scheduleResume(earlier, attack);
            String reason = earlier.endTick - attack.start <= RELEASE_TICKS ? "quantized-release-overlap" : "higher-priority-attack";
            entries.put(earlier.note.id(), earlier.shorten(attack.start, reason, attack.note().id()));
        }
        return lane;
    }

    private boolean canShorten(Entry last, Attack next) {
        if (last == null || last.note.run() != null || last.endTick <= next.start) return false;
        if (last.startTick >= next.start && (tones.isEmpty() || chordAnchors.contains(last.note.id()))) return false;
        if (last.pinnedLane != null && !(last.laneType == ReconstructionLaneType.FM
                && last.role == Role.melody && next.role() == Role.melody
                && (VOICE_ASSIGNMENT + " laneType=FM").equals(last.reason)))
            return false;
        if (last.role == Role.drums)
            return last.startTick < next.start && next.role() == Role.counter && ReconstructionCymbals.automatic(last.reason);
        if (last.startTick < next.start && (last.endTick - next.start <= RELEASE_TICKS
                || next.role() == Role.melody || next.role() == Role.bass || next.role() == Role.drums)) return true;
        if (tones.isEmpty()) return next.role() == Role.counter && last.role == Role.harmony;
        if (chordAnchors.contains(last.note.id())
                || !backing(last.role) || !backing(next.role()) || continuations.containsKey(next.note().id())) return false;
        int byTone = Integer.compare(tonePriority(next.note()), tonePriority(last.note()));
        return byTone > 0 || byTone == 0 && (salience(next.note()) > salience(last.note())
                || last.startTick < next.start && next.role() == Role.counter && last.role == Role.harmony
                && salience(next.note()) == salience(last.note()));
    }

    private void scheduleResume(Entry pad, Attack stab) {
        if (tones.isEmpty() || chordAnchors.contains(pad.note.id()) || !backing(pad.role) || !backing(stab.role()) || pad.note.bend() != null
                || pad.note.motion() != null || pad.endTick - pad.startTick < MIN_PAD_TICKS
                || stab.end - stab.start > MAX_STAB_TICKS || pad.endTick - stab.end < MIN_RESUMED_TICKS
                || continuations.containsKey(pad.note.id())) return;
        var original = pad.note;
        String id = original.id() + "-resume-" + stab.end;
        var resumed = new Note(id, original.part(), stab.end / 60.0, original.end(),
                original.pitch(), original.velocity(), original.source(), original.confidence());
        continuations.put(id, original.id());
        pending.add(new Attack(new ReconstructionRoles.Decision(resumed, pad.role, "pad-resumed", null),
                stab.end, pad.endTick, false, null, null, false));
    }

    private void keep(Attack attack, String lane, String reason) {
        var entry = new Entry(attack.note(), attack.role(), lane, attack.start, attack.end, Outcome.kept, reason, null,
                null, null, null, attack.pin == null ? null : lane, attack.pin == null ? null : attack.pin.laneType());
        entries.put(attack.note().id(), entry);
        tail.put(lane, entry);
        String parent = continuations.get(attack.note().id());
        if (parent != null) {
            var head = entries.get(parent);
            entries.put(parent, head.shorten(head.endTick(), "pad-yield", head.competitorNoteId()));
        }
    }

    private void drop(Attack attack, String reason) {
        drop(attack, reason, List.of());
    }

    private void drop(Attack attack, String reason, List<String> blockers) {
        if (continuations.containsKey(attack.note().id())) return;
        entries.put(attack.note().id(), new Entry(attack.note(), attack.role(), null, null, null,
                Outcome.dropped, reason, attack.decision.mergedInto(),
                blockers.size() == 1 ? blockers.getFirst() : null, blockers.size() > 1 ? blockers : null, null,
                null, attack.pin == null ? null : attack.pin.laneType()));
    }
}
