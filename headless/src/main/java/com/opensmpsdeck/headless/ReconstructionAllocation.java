package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

final class ReconstructionAllocation {
    static final List<String> FM = List.of("FM1", "FM2", "FM3", "FM4", "FM5");
    static final List<String> PSG = List.of("PSG1", "PSG2", "PSG3");
    static final List<String> INNER_FM = FM.subList(2, FM.size());
    private static final int RELEASE_TICKS = 2;

    enum Outcome { kept, shortened, dropped }
    record Entry(Note note, Role role, @Nullable String lane, @Nullable Integer startTick, @Nullable Integer endTick,
                 Outcome outcome, @Nullable String reason, @Nullable String mergedInto,
                 @Nullable String competitorNoteId, @Nullable List<String> blockingNoteIds,
                 @Nullable String relocatedFrom) {
        Entry(Note note, Role role, String lane, Integer startTick, Integer endTick,
              Outcome outcome, String reason, String mergedInto) {
            this(note, role, lane, startTick, endTick, outcome, reason, mergedInto, null, null, null);
        }
        Entry shorten(int end, String why) {
            return shorten(end, why, null);
        }

        Entry shorten(int end, String why, String competitor) {
            return new Entry(note, role, lane, startTick, end, Outcome.shortened, why, null, competitor, null, relocatedFrom);
        }
    }
    record Result(int version, List<Entry> notes, @Nullable ReconstructionTimeMap.TimeBase timeBase,
                  @Nullable ReconstructionLeadContinuity.Stats leadContinuity,
                  @Nullable ReconstructionMelodyGapFill.Stats melodyGapFill,
                  @Nullable Fm1Gaps fm1InterNoteGaps,
                  @Nullable List<RunPlacement> fastRuns) {
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
    private record Attack(ReconstructionRoles.Decision decision, int start, int end, boolean noise) {
        Note note() { return decision.note(); }
        Role role() { return decision.role(); }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Entry> tail = new HashMap<>();
    private final boolean fmOnly;

    private ReconstructionAllocation(boolean fmOnly) { this.fmOnly = fmOnly; }

    static Result allocate(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames, boolean fmOnly) {
        return allocate(roles, parts, frames, fmOnly, false);
    }

    static Result allocate(ReconstructionRoles.Result roles, List<MidiScore.Part> parts, int frames, boolean fmOnly,
                           boolean hasStems) {
        var allocator = new ReconstructionAllocation(fmOnly);
        var names = new HashMap<Integer, String>();
        for (var part : parts) names.put(part.id(), part.name());
        var attacks = roles.notes().stream().map(decision -> {
            Note note = decision.note();
            int start = note.startTick(frames);
            boolean drum = decision.role() == Role.drums;
            boolean noise = drum && !fmOnly && isNoise(names.get(note.part()));
            return new Attack(decision, start, drum && !noise ? start + 1 : note.endTick(frames), noise);
        }).sorted(Comparator.comparingInt(Attack::start)
                .thenComparing(Comparator.comparingInt(ReconstructionAllocation::priority).reversed())
                .thenComparingInt(a -> a.role() == Role.harmony && a.note().pitch() < 45 ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Attack a) -> a.note().velocity()).reversed())
                .thenComparing(Attack::note, ReconstructionStages.NOTE_ORDER)).toList();
        for (var attack : attacks) allocator.place(attack);
        var continuity = ReconstructionLeadContinuity.Stats.of(roles.notes());
        var placed = new ArrayList<Entry>();
        var runs = new ArrayList<RunPlacement>();
        for (var entry : allocator.entries.values()) {
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
                placed.add(new Entry(note, entry.role(), entry.lane(), step.start(), step.end(), Outcome.kept, reason, null));
            }
        }
        return new Result(VERSION, List.copyOf(placed), roles.timeBase(), !hasStems && continuity.isEmpty() ? null : continuity,
                ReconstructionMelodyGapFill.Stats.of(roles.notes()),
                hasStems ? Fm1Gaps.of(placed, roles.notes(), frames) : null, runs.isEmpty() ? null : List.copyOf(runs));
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
            if (entry.outcome == Outcome.shortened) {
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
            case drums -> 8 + S3kReconstruction.drumPriority(attack.note().pitch());
            case counter -> 5;
            case harmony -> 3;
            case drop -> 0;
        };
    }

    private void place(Attack attack) {
        if (attack.role() == Role.drop) {
            drop(attack, attack.decision.reason());
            return;
        }
        if (attack.role() == Role.drums && !attack.noise) {
            Entry last = tail.get("DAC");
            if (last != null && last.startTick == attack.start) {
                drop(attack, "simultaneous-dac-hit", List.of(last.note.id()));
            } else if (last != null && attack.start - last.startTick < 5
                    && S3kReconstruction.drumPriority(last.note.pitch()) >= 2
                    && S3kReconstruction.drumPriority(last.note.pitch()) > S3kReconstruction.drumPriority(attack.note().pitch())) {
                drop(attack, "protect-dac-tail", List.of(last.note.id()));
            } else {
                keep(attack, "DAC", null);
            }
            return;
        }
        List<String> primary = expectedLanes(attack.role(), attack.noise);
        List<String> secondary = primary == INNER_FM && !fmOnly && attack.note().pitch() >= 45 && attack.note().run() == null ? PSG : List.of();
        String lane = free(primary, attack);
        if (lane == null) lane = free(secondary, attack);
        if (lane == null && primary == INNER_FM && secondary.isEmpty() && !fmOnly)
            lane = freeFmForLowAttack(attack);
        if (lane == null) lane = shorten(primary, attack);
        if (lane == null) lane = shorten(secondary, attack);
        if (lane == null) {
            drop(attack, switch (attack.role()) {
                case melody -> "melody-lane-busy";
                case bass -> "bass-lane-busy";
                case drums -> "psg-noise-lane-busy";
                default -> secondary.isEmpty() ? "fm-lanes-busy" : "fm-and-psg-lanes-busy";
            }, java.util.stream.Stream.concat(primary.stream(), secondary.stream())
                    .map(tail::get).map(entry -> entry.note.id()).toList());
        } else {
            keep(attack, lane, secondary.contains(lane) ? "psg-spill" : null);
        }
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

    private String free(List<String> lanes, Attack attack) {
        return lanes.stream().filter(lane -> !tail.containsKey(lane) || tail.get(lane).endTick <= attack.start)
                .min(Comparator.comparingInt(lane -> affinity(tail.get(lane), attack))).orElse(null);
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
            if (prior == null || prior.note.pitch() < 45 || prior.note.run() != null) continue;
            for (String destination : PSG) {
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
                .min(Comparator.comparingInt(l -> tail.get(l).endTick - attack.start)).orElse(null);
        if (lane != null) {
            Entry earlier = tail.get(lane);
            entries.put(earlier.note.id(), earlier.shorten(attack.start,
                    earlier.endTick - attack.start <= RELEASE_TICKS ? "quantized-release-overlap" : "higher-priority-attack",
                    attack.note().id()));
        }
        return lane;
    }

    private static boolean canShorten(Entry last, Attack next) {
        if (last == null || last.note.run() != null || last.startTick >= next.start || last.endTick <= next.start || last.role == Role.drums) return false;
        return last.endTick - next.start <= RELEASE_TICKS || next.role() == Role.melody || next.role() == Role.bass
                || next.role() == Role.drums || next.role() == Role.counter && last.role == Role.harmony;
    }

    private void keep(Attack attack, String lane, String reason) {
        var entry = new Entry(attack.note(), attack.role(), lane, attack.start, attack.end, Outcome.kept, reason, null);
        entries.put(attack.note().id(), entry);
        tail.put(lane, entry);
    }

    private void drop(Attack attack, String reason) {
        drop(attack, reason, List.of());
    }

    private void drop(Attack attack, String reason, List<String> blockers) {
        entries.put(attack.note().id(), new Entry(attack.note(), attack.role(), null, null, null,
                Outcome.dropped, reason, attack.decision.mergedInto(),
                blockers.size() == 1 ? blockers.getFirst() : null, blockers.size() > 1 ? blockers : null, null));
    }
}
