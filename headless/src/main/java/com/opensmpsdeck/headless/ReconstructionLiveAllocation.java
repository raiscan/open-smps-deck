package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

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

    /** Keep the saved gates outside the edited attacks, even if the local allocator wants to displace one. */
    static ReconstructionAllocation.Result project(ReconstructionStages.Score score, ReconstructionStructure.Result structure,
            ReconstructionAllocation.Result built, ReconstructionRoles.Result edited, Set<String> changed,
            Set<String> inserted, ReconstructionTimeMap clock) {
        var previous = new HashMap<String, ReconstructionAllocation.Entry>();
        var fixed = new ArrayList<ReconstructionAllocation.Entry>();
        for (var row : built.notes()) {
            previous.put(row.note().id(), row);
            if (!changed.contains(row.note().id())) fixed.add(row);
        }
        var attacks = edited.notes().stream()
                .filter(row -> changed.contains(row.note().id()) && row.role() != Role.drop).toList();
        var pins = new HashMap<String, ReconstructionEdits.Pin>();
        var candidates = new ArrayList<>(attacks);
        var seed = new HashMap<String, String>();
        for (var row : attacks) {
            String id = row.note().id();
            var pin = edited.lanePins() == null ? null : edited.lanePins().get(id);
            var old = previous.get(id);
            if (old != null && old.lane() != null && old.role() == row.role()) {
                seed.put(id, old.lane());
                if (pin == null)
                    pin = new ReconstructionEdits.Pin(old.lane(), "live:retained-lane", 0, null);
            }
            if (pin != null) pins.put(id, pin);
        }
        for (var row : fixed) {
            if (row.lane() == null || attacks.stream().noneMatch(attack ->
                    clock.startTick(attack.note()) < row.endTick() && row.startTick() < clock.endTick(attack.note())))
                continue;
            var note = row.note();
            var gate = new ReconstructionStages.Note(note.id(), note.part(), clock.secondsAtTick(row.startTick()),
                    clock.secondsAtTick(row.endTick()), note.pitch(), note.velocity(), note.source(), note.confidence());
            candidates.add(new ReconstructionRoles.Decision(gate, row.role(), null, null));
            pins.put(note.id(), new ReconstructionEdits.Pin(row.lane(), "live:occupied-lane", 0, null));
        }
        if (!attacks.isEmpty()) {
            var window = new ReconstructionRoles.Result(edited.version(), edited.harmonyMaxHz(),
                    candidates, edited.timeBase(), null, pins);
            var placed = ReconstructionAllocation.allocateWithAffinity(window, score.source().parts(), clock.ticks(),
                    score.inputs().fmOnly(), false, seed, Map.of(), Map.of(), Map.of(), null, Map.of(),
                    false, score.profile(), ReconstructionArrangement.Allocation.NONE);
            var revised = placed.notes().stream().filter(row -> changed.contains(row.note().id()) && row.lane() != null)
                    .filter(row -> fixed.stream().noneMatch(other -> row.lane().equals(other.lane())
                            && row.startTick() < other.endTick() && other.startTick() < row.endTick())).toList();
            fixed.addAll(revised);
            for (var attack : attacks) {
                String id = attack.note().id();
                if (inserted.contains(id) && revised.stream().noneMatch(row -> row.note().id().equals(id)))
                    borrowLane(score, structure, built, fixed, changed, attack, pins.get(id), clock);
            }
        }
        fixed.sort(Comparator.comparingInt(row -> row.startTick() == null ? clock.startTick(row.note()) : row.startTick()));
        var chords = new HashMap<>(built.chords());
        chords.keySet().removeAll(changed);
        return new ReconstructionAllocation.Result(built.version(), List.copyOf(fixed), built.timeBase(),
                null, null, null, null, chords.isEmpty() ? null : Map.copyOf(chords));
    }

    // An inserted note needs an attack even in a full bar. Temporarily cut the smallest
    // unpinned backing conflict on a compatible lane, while keeping gates outside the bar.
    private static void borrowLane(ReconstructionStages.Score score, ReconstructionStructure.Result structure,
            ReconstructionAllocation.Result built, List<ReconstructionAllocation.Entry> fixed, Set<String> changed,
            ReconstructionRoles.Decision attack, ReconstructionEdits.Pin pin, ReconstructionTimeMap clock) {
        var note = attack.note();
        if (attack.role() == Role.drums) return;
        var bar = structure.bars().stream().filter(b -> note.start() >= b.start() && note.start() < b.end())
                .findFirst().orElse(null);
        if (bar == null || note.end() > bar.end()) return;
        int left = clock.firstTickAtOrAfter(bar.start()), right = clock.firstTickAtOrAfter(bar.end());
        int first = clock.startTick(note), last = clock.endTick(note);
        List<ReconstructionAllocation.Entry> best = List.of();
        ReconstructionAllocation.Entry replacement = null;
        int cost = Integer.MAX_VALUE;
        for (String lane : java.util.stream.Stream.concat(ReconstructionAllocation.FM.stream(),
                ReconstructionAllocation.PSG.stream()).toList()) {
            if (pin != null && (pin.lane() != null ? !lane.equals(pin.lane()) : !pin.laneType().lanes().contains(lane)))
                continue;
            var blockers = fixed.stream().filter(row -> lane.equals(row.lane())
                    && row.startTick() < last && first < row.endTick()).toList();
            if (blockers.isEmpty() || blockers.stream().anyMatch(row -> changed.contains(row.note().id())
                    || row.role() != Role.harmony && row.role() != Role.counter
                    || row.startTick() < left || row.endTick() > right || row.pinnedLane() != null || row.laneType() != null
                    || row.note().run() != null || row.note().bend() != null || built.chords().containsKey(row.note().id())))
                continue;
            int lostTicks = blockers.stream().mapToInt(row -> row.endTick() - Math.max(first, row.startTick())).sum();
            if (lostTicks >= cost) continue;
            var placement = new ReconstructionRoles.Result(ReconstructionStages.VERSION, ReconstructionRoles.DEFAULT_HARMONY_HZ,
                    List.of(attack), clock.timeBase(), null,
                    Map.of(note.id(), new ReconstructionEdits.Pin(lane, "live:insert", 0, null)));
            var proposed = ReconstructionAllocation.allocateWithAffinity(placement, score.source().parts(), clock.ticks(),
                    score.inputs().fmOnly(), false, Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of(),
                    false, score.profile(), ReconstructionArrangement.Allocation.NONE).notes().getFirst();
            if (proposed.lane() == null) continue;
            best = blockers;
            replacement = proposed;
            cost = lostTicks;
        }
        if (replacement == null) return;
        fixed.removeAll(best);
        for (var blocker : best) {
            changed.add(blocker.note().id());
            if (blocker.startTick() < first) fixed.add(blocker.shorten(first, "live-insert", note.id()));
        }
        fixed.add(replacement);
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
