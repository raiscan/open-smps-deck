package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionTrace.*;

final class DrumEvidence {
    record Loss(String noteId, int pitch, Timing gate, String competitorNoteId, boolean psg3CanFit) {}
    record Review(List<Timing> psg3Free, List<Loss> simultaneousDacLosses) {}

    private DrumEvidence() {}

    static Review review(Collection<Row> selected, Collection<Row> all, double start, double end,
                         ReconstructionTimeMap timeline, boolean includeDac, List<Integer> parts) {
        if (!includeDac) return null;
        var lost = selected.stream().filter(row -> row.allocation() != null
                && "simultaneous-dac-hit".equals(row.allocation().reason()))
                .filter(row -> parts.isEmpty() || parts.contains(row.part())).toList();
        if (lost.isEmpty()) return null;

        // Allocation owns the availability decision, even when an allocated
        // PSG3 note later has no playable patch or a shortened voiced gate.
        var busy = all.stream().map(Row::allocation).filter(java.util.Objects::nonNull)
                .filter(entry -> "PSG3".equals(entry.lane()) && entry.placement() != null)
                .map(Allocation::placement).sorted(Comparator.comparingLong(Timing::startTick)).toList();
        int first = (int) Math.clamp(Math.ceil(timeline.project(start) * 60 - 1e-8), 0, timeline.frames());
        int limit = (int) Math.clamp(Math.floor(timeline.project(end) * 60 + 1e-8), first, timeline.frames());
        var gaps = free(busy, first, limit, timeline);
        var losses = new ArrayList<Loss>();
        for (var row : lost) {
            var effective = row.allocation().effective().timing();
            if (effective.sourceEnd() <= start || effective.sourceStart() >= end) continue;
            int from = (int) Math.clamp(effective.startTick(), 0, timeline.frames() - 1);
            int to = (int) Math.clamp(effective.endTick(), from + 1, timeline.frames());
            boolean fits = free(busy, from, to, timeline).stream()
                    .anyMatch(gap -> gap.startTick() == from && gap.endTick() == to);
            losses.add(new Loss(row.id(), row.pitch(), ticks(from, to, timeline), row.allocation().competitorNoteId(), fits));
        }
        return losses.isEmpty() ? null : new Review(gaps, losses);
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
