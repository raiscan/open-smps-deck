package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionAllocation.Outcome;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;

/** Replay a bar range whose lanes have no allocation dependency across either edge. */
final class ReconstructionLiveAllocation {
    private ReconstructionLiveAllocation() {}

    static ReconstructionAllocation.Result tryAllocate(ReconstructionStages.Score score,
            ReconstructionStructure.Result structure, ReconstructionRoles.Result roles,
            ReconstructionAllocation.Result built, ReconstructionEdits journal, List<ReconstructionEdits.Intent> suffix) {
        if (roles == null || roles.lanePins() != null || !built.chords().isEmpty() || built.fastRuns() != null
                || score.inputs().audioOnly() != null || !score.inputs().stems().isEmpty()
                || journal.intents().subList(0, journal.intents().size() - suffix.size()).stream()
                    .anyMatch(edit -> edit.firstStage().ordinal() < ReconstructionStages.Stage.ASSEMBLE.ordinal()))
            return null;
        var clock = ReconstructionTimeMap.of(score);
        var byId = new HashMap<String, ReconstructionRoles.Decision>();
        roles.notes().forEach(row -> byId.put(row.note().id(), row));
        double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY;
        for (var edit : suffix) {
            switch (edit.kind()) {
                case setRole, pinLane, swapRoles -> { }
                default -> { return null; }
            }
            if (edit.span() != null && edit.span().section() == null && edit.attack() == null && edit.selectors().isEmpty()) {
                double left = edit.timeBase() == ReconstructionTimeMap.TimeBase.source
                        ? clock.project(edit.span().start()) : edit.span().start();
                double right = edit.timeBase() == ReconstructionTimeMap.TimeBase.source
                        ? clock.project(edit.span().end()) : edit.span().end();
                start = Math.min(start, left);
                end = Math.max(end, Math.nextDown(right));
            } else {
                if (edit.noteIds().isEmpty()) return null;
                for (String id : edit.noteIds()) {
                    var row = byId.get(id);
                    if (row == null) return null;
                    start = Math.min(start, row.note().start());
                    end = Math.max(end, row.note().start());
                }
            }
        }
        double barStart = Double.NaN, barEnd = Double.NaN;
        int first = -1, last = -1;
        for (var bar : structure.bars()) {
            if (bar.start() <= start && start < bar.end()) {
                barStart = bar.start();
                first = clock.firstTickAtOrAfter(barStart);
            }
            if (bar.start() <= end && end < bar.end()) {
                barEnd = bar.end();
                last = clock.firstTickAtOrAfter(barEnd);
            }
        }
        if (first < 0 || last <= first) return null;
        final int left = first, right = last;
        final double onsetStart = barStart, onsetEnd = barEnd;

        // In this configuration all notes have one possible lane and no spill,
        // release recovery or reallocation. Current entries must witness that.
        if (!independent(roles.notes(), Map.of(), clock)
                || built.notes().size() != roles.notes().size()
                || built.notes().stream().anyMatch(row -> row.outcome() != Outcome.kept
                    || !primary(row.role()).equals(row.lane())
                    || row.startTick() != clock.startTick(row.note()) || row.endTick() != clock.endTick(row.note())
                    || crosses(row.startTick(), row.endTick(), left) || crosses(row.startTick(), row.endTick(), right)))
            return null;

        if (roles.notes().stream().anyMatch(row -> inside(clock.startTick(row.note()), left, right)
                != (row.note().start() >= onsetStart && row.note().start() < onsetEnd))) return null;
        var local = roles.notes().stream().filter(row -> inside(clock.startTick(row.note()), left, right)).toList();
        var window = new ReconstructionRoles.Result(roles.version(), roles.harmonyMaxHz(), local, roles.timeBase());
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var edited = journal.applyRoles(score, structure, window, List.of(), outcomes);
        if (outcomes.stream().anyMatch(outcome -> outcome.status() != ReconstructionEdits.Status.applied
                && outcome.status() != ReconstructionEdits.Status.satisfied)) return null;
        if (edited.notes().stream().anyMatch(row -> !inside(clock.startTick(row.note()), left, right)
                || clock.endTick(row.note()) > right)) return null;
        var pins = edited.lanePins() == null ? Map.<String, ReconstructionEdits.Pin>of() : edited.lanePins();
        if (!independent(edited.notes(), pins, clock)) return null;
        var allocated = ReconstructionAllocation.allocateWithAffinity(edited, score.source().parts(), clock.ticks(),
                score.inputs().fmOnly(), false, ReconstructionAllocation.lanes(built), Map.of(), Map.of(),
                Map.of(), null, Map.of(), false, score.profile(), ReconstructionArrangement.Allocation.NONE);
        var rows = new ArrayList<ReconstructionAllocation.Entry>();
        built.notes().stream().filter(row -> row.startTick() < left).forEach(rows::add);
        rows.addAll(allocated.notes());
        built.notes().stream().filter(row -> row.startTick() >= right).forEach(rows::add);
        var decisions = new ArrayList<ReconstructionRoles.Decision>();
        roles.notes().stream().filter(row -> !inside(clock.startTick(row.note()), left, right)).forEach(decisions::add);
        decisions.addAll(edited.notes());
        var continuity = ReconstructionLeadContinuity.Stats.of(decisions);
        return new ReconstructionAllocation.Result(built.version(), List.copyOf(rows), built.timeBase(),
                continuity.isEmpty() ? null : continuity, ReconstructionMelodyGapFill.Stats.of(decisions), null, null);
    }

    private static boolean independent(List<ReconstructionRoles.Decision> rows,
                                       Map<String, ReconstructionEdits.Pin> pins, ReconstructionTimeMap clock) {
        var ends = new HashMap<String, Integer>();
        for (var row : rows) {
            var note = row.note();
            if (row.mergedInto() != null || note.run() != null || row.preLegatoEnd() != null) return false;
            if (row.role() == Role.drop) continue;
            String lane = primary(row.role());
            var pin = pins.get(note.id());
            if (pin != null) {
                if (!"FM1".equals(pin.lane()) && !"FM2".equals(pin.lane())) return false;
                lane = pin.lane();
            }
            if (lane.isEmpty() || ends.getOrDefault(lane, 0) > clock.startTick(note)) return false;
            ends.put(lane, clock.endTick(note));
        }
        return true;
    }

    private static String primary(Role role) {
        return switch (role) {
            case melody -> "FM1";
            case bass -> "FM2";
            default -> "";
        };
    }

    private static boolean crosses(int start, int end, int edge) { return start < edge && edge < end; }

    private static boolean inside(int tick, int left, int right) { return tick >= left && tick < right; }
}
