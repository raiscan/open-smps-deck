package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                 Outcome outcome, @Nullable String reason, @Nullable String mergedInto) {
        Entry shorten(int end, String why) {
            return new Entry(note, role, lane, startTick, end, Outcome.shortened, why, null);
        }
    }
    record Result(int version, List<Entry> notes, @Nullable ReconstructionTimeMap.TimeBase timeBase,
                  @Nullable ReconstructionLeadContinuity.Stats leadContinuity,
                  @Nullable ReconstructionMelodyGapFill.Stats melodyGapFill) {
        Result(int version, List<Entry> notes, ReconstructionTimeMap.TimeBase timeBase) {
            this(version, notes, timeBase, null, null);
        }

        Result(int version, List<Entry> notes) {
            this(version, notes, null);
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
        return new Result(VERSION, List.copyOf(allocator.entries.values()), roles.timeBase(),
                continuity.isEmpty() ? null : continuity, ReconstructionMelodyGapFill.Stats.of(roles.notes()));
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
            case melody, bass -> 12;
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
                drop(attack, "simultaneous-dac-hit");
            } else if (last != null && attack.start - last.startTick < 5
                    && S3kReconstruction.drumPriority(last.note.pitch()) >= 2
                    && S3kReconstruction.drumPriority(last.note.pitch()) > S3kReconstruction.drumPriority(attack.note().pitch())) {
                drop(attack, "protect-dac-tail");
            } else {
                keep(attack, "DAC");
            }
            return;
        }
        List<String> primary = switch (attack.role()) {
            case melody -> List.of("FM1");
            case bass -> List.of("FM2");
            case drums -> List.of("PSG3");
            default -> INNER_FM;
        };
        List<String> secondary = primary == INNER_FM && !fmOnly && attack.note().pitch() >= 45 ? PSG : List.of();
        String lane = free(primary, attack);
        if (lane == null) lane = free(secondary, attack);
        if (lane == null && primary == INNER_FM && secondary.isEmpty() && !fmOnly)
            lane = freeFmForLowAttack();
        if (lane == null) lane = shorten(primary, attack);
        if (lane == null) lane = shorten(secondary, attack);
        if (lane == null) {
            drop(attack, switch (attack.role()) {
                case melody -> "melody-lane-busy";
                case bass -> "bass-lane-busy";
                case drums -> "psg-noise-lane-busy";
                default -> secondary.isEmpty() ? "fm-lanes-busy" : "fm-and-psg-lanes-busy";
            });
        } else {
            keep(attack, lane);
        }
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

    private String freeFmForLowAttack() {
        for (String lane : INNER_FM.reversed()) {
            Entry prior = tail.get(lane);
            if (prior.note.pitch() < 45) continue;
            for (String destination : PSG) {
                Entry psg = tail.get(destination);
                if (psg != null && psg.endTick > prior.startTick) continue;
                var moved = new Entry(prior.note, prior.role, destination, prior.startTick, prior.endTick,
                        prior.outcome, prior.reason == null ? "relocated-for-low-fm-note" : prior.reason, null);
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
                    earlier.endTick - attack.start <= RELEASE_TICKS ? "quantized-release-overlap" : "higher-priority-attack"));
        }
        return lane;
    }

    private static boolean canShorten(Entry last, Attack next) {
        if (last == null || last.startTick >= next.start || last.endTick <= next.start || last.role == Role.drums) return false;
        return last.endTick - next.start <= RELEASE_TICKS || next.role() == Role.melody || next.role() == Role.bass
                || next.role() == Role.drums || next.role() == Role.counter && last.role == Role.harmony;
    }

    private void keep(Attack attack, String lane) {
        var entry = new Entry(attack.note(), attack.role(), lane, attack.start, attack.end, Outcome.kept, null, null);
        entries.put(attack.note().id(), entry);
        tail.put(lane, entry);
    }

    private void drop(Attack attack, String reason) {
        entries.put(attack.note().id(), new Entry(attack.note(), attack.role(), null, null, null,
                Outcome.dropped, reason, attack.decision.mergedInto()));
    }
}
