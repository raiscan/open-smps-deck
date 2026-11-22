package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.opensmpsdeck.headless.ReconstructionTrace.*;

final class DrumEvidence {
    record Loss(String noteId, int pitch, Timing gate, String competitorNoteId, boolean psg3CanFit) {}
    record PinLane(String id, String op, String reason, List<String> noteIds, String lane) {}
    record Competitor(String noteId, int pitch, String lane, Timing busyInterval, List<PinLane> pinLaneEdits) {}
    record DroppedHit(String noteId, int pitch, String lane, String reason, Timing requestedGate,
                      List<Competitor> competitors) {}
    record Review(List<Timing> psg3Free, List<Loss> simultaneousDacLosses, List<DroppedHit> droppedHits) {}

    private DrumEvidence() {}

    static String blockedLane(Row row, Map<String, Row> all) {
        var allocation = row.allocation();
        if (allocation == null || allocation.outcome() != ReconstructionAllocation.Outcome.dropped
                || allocation.role() != ReconstructionRoles.Role.drums || allocation.reason() == null) return null;
        String reason = allocation.reason();
        if (reason.equals("simultaneous-dac-hit") || reason.equals("protect-dac-tail")) return "DAC";
        if (reason.equals("psg-noise-lane-busy")) return "PSG3";
        if (!reason.contains("busy") || allocation.competitorNoteId() == null) return null;
        var winner = all.get(allocation.competitorNoteId());
        return winner == null || winner.allocation() == null ? null : winner.allocation().lane();
    }

    static Review review(Collection<Row> selected, Map<String, Row> all, double start, double end,
                         ReconstructionTimeMap timeline, String lane, List<Integer> parts, boolean fmOnly, TargetProfile profile) {
        if (lane != null && !lane.equals("DAC") && !lane.equals("PSG3")) return null;
        var lost = selected.stream().filter(row -> {
            String blocked = blockedLane(row, all);
            return blocked != null && (lane == null || lane.equals(blocked));
        }).filter(row -> parts.isEmpty() || parts.contains(row.part())).toList();
        if (lost.isEmpty()) return null;

        // Use allocation gates, including notes that later voice a shorter gate.
        var placements = all.values().stream().filter(row -> row.allocation() != null
                && row.allocation().lane() != null && row.allocation().placement() != null).collect(java.util.stream.Collectors.groupingBy(
                        row -> row.allocation().lane()));
        var psg3 = placements.getOrDefault("PSG3", List.of());
        var busy = psg3.stream().map(row -> row.allocation().placement())
                .sorted(Comparator.comparingLong(Timing::startTick)).toList();
        int first = Math.clamp((int) timeline.clock().ticksAfterFrames((long) Math.ceil(timeline.project(start) * 60 - 1e-8)), 0, timeline.ticks());
        int limit = Math.clamp((int) timeline.clock().ticksAfterFrames((long) Math.floor(timeline.project(end) * 60 + 1e-8)), first, timeline.ticks());
        var losses = new ArrayList<Loss>();
        var dropped = new ArrayList<DroppedHit>();
        for (var row : lost) {
            var allocation = row.allocation();
            var effective = allocation.effective().timing();
            if (effective.sourceEnd() <= start || effective.sourceStart() >= end) continue;
            int from = (int) Math.clamp(effective.startTick(), 0, timeline.ticks() - 1);
            int to = (int) Math.clamp(effective.endTick(), from + 1, timeline.ticks());
            String reason = allocation.reason();
            String blocked = blockedLane(row, all);
            boolean simultaneous = reason.equals("simultaneous-dac-hit");
            var gate = ticks(from, to, timeline);
            if (simultaneous) {
                var authoredGate = gate;
                boolean fits = !fmOnly && busy.stream().noneMatch(occupied -> overlaps(occupied, authoredGate));
                losses.add(new Loss(row.id(), row.pitch(), gate, allocation.competitorNoteId(), fits));
            }
            if (blocked.equals("PSG3") && ReconstructionCymbals.eligible(row.pitch())
                    && (allocation.laneType() == ReconstructionLaneType.NOISE
                    || reason.equals("psg-noise-lane-busy") && allocation.expectedLanes().contains("DAC"))) {
                gate = ticks(from, Math.min(timeline.ticks(), from + ReconstructionCymbals.gate(row.pitch())), timeline);
            }
            var requested = blocked.equals("DAC") ? ticks(from, from + 1, timeline) : gate;

            var opponents = new ArrayList<Competitor>();
            var ids = new java.util.LinkedHashSet<String>();
            if (allocation.competitorNoteId() != null) ids.add(allocation.competitorNoteId());
            if (allocation.blockingNoteIds() != null) ids.addAll(allocation.blockingNoteIds());
            for (String id : ids) {
                var opponent = all.get(id);
                if (opponent == null || opponent.allocation() == null || opponent.allocation().placement() == null) continue;
                var placement = opponent.allocation().placement();
                if (reason.equals("protect-dac-tail"))
                    placement = ticks((int) placement.startTick(),
                            Math.min(timeline.ticks(), (int) placement.startTick() + ReconstructionAllocation.DAC_PROTECTED_TICKS), timeline);
                opponents.add(competitor(opponent, placement, placements, fmOnly, timeline, profile));
            }
            if ((simultaneous || blocked.equals("PSG3")) && !fmOnly) {
                for (var opponent : psg3) {
                    var occupied = opponent.allocation().placement();
                    if (overlaps(occupied, gate) && ids.add(opponent.id()))
                        opponents.add(competitor(opponent, occupied, placements, fmOnly, timeline, profile));
                }
            }
            dropped.add(new DroppedHit(row.id(), row.pitch(), blocked, reason, requested, List.copyOf(opponents)));
        }
        return dropped.isEmpty() ? null : new Review(free(busy, first, limit, timeline), losses, dropped);
    }

    private static Competitor competitor(Row row, Timing interval, Map<String, List<Row>> placements,
                                         boolean fmOnly, ReconstructionTimeMap timeline, TargetProfile profile) {
        var allocation = row.allocation();
        var alternatives = new ArrayList<PinLane>();
        if (allocation.role() != ReconstructionRoles.Role.drums && row.roles() != null) {
            var source = row.roles().effective();
            var note = source.note();
            // A new pin requests the authored role gate, which can be longer than the current placement.
            var gate = ticks((int) Math.min(interval.startTick(), source.timing().startTick()),
                    (int) Math.min(timeline.ticks(), Math.max(interval.endTick(), source.timing().endTick())), timeline);
            for (var lane : Stream.concat(ReconstructionAllocation.FM.stream(), ReconstructionAllocation.PSG.stream()).toList()) {
                if (lane.equals(allocation.lane()) || !ReconstructionEdits.pinCompatible(note, false, lane, fmOnly, profile)
                        || lane.startsWith("PSG") && ReconstructionEdits.laneTypeIssue(note, false, ReconstructionLaneType.PSG, fmOnly, profile) != null)
                    continue;
                if (placements.getOrDefault(lane, List.of()).stream().map(other -> other.allocation().placement())
                        .noneMatch(occupied -> overlaps(occupied, gate))) {
                    alternatives.add(new PinLane("move-" + lane.toLowerCase(java.util.Locale.ROOT) + "-"
                            + Integer.toUnsignedString(row.id().hashCode(), 16),
                            "pinLane", "Free " + allocation.lane() + " for percussion", List.of(row.id()), lane));
                }
            }
        }
        return new Competitor(row.id(), row.pitch(), allocation.lane(), interval, List.copyOf(alternatives));
    }

    private static boolean overlaps(Timing a, Timing b) {
        return a.startTick() < b.endTick() && b.startTick() < a.endTick();
    }

    private static List<Timing> free(List<Timing> busy, int start, int end, ReconstructionTimeMap timeline) {
        var gaps = new ArrayList<Timing>();
        int cursor = start;
        for (var gate : busy) {
            if (gate.endTick() <= cursor) continue;
            if (gate.startTick() >= end) break;
            if (gate.startTick() > cursor) gaps.add(ticks(cursor, (int) gate.startTick(), timeline));
            cursor = Math.max(cursor, (int) gate.endTick());
        }
        if (cursor < end) gaps.add(ticks(cursor, end, timeline));
        return gaps;
    }
}
