package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.function.BiPredicate;

import static com.opensmpsdeck.headless.ReconstructionRoles.Decision;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Reconnect a counter phrase to the surrounding lead without changing note timing. */
final class ReconstructionMelodyGapFill {
    static final String REASON = "melody-gapfill";
    private static final double MIN_SOUNDING_SECONDS = .150;
    private static final double MAX_REST_SECONDS = .240;
    private static final double MAX_MIDI_HANDOFF_REST = .120;
    private static final double MAX_MIDI_GAP = 1;
    private static final double MIN_STEM_MOTION = .50;
    private static final int MAX_LEAP = 9;
    private static final double MAX_TRANSITION_COST = .150;

    private ReconstructionMelodyGapFill() {}

    static List<Decision> promote(List<Decision> notes, BiPredicate<Decision, Note> permitted) {
        var result = new ArrayList<>(notes);
        int previous = -1;
        for (int next = 0; next < notes.size(); next++) {
            var following = notes.get(next);
            if (following.role() != Role.melody) continue;
            if (previous >= 0) {
                var prior = notes.get(previous);
                if (prior.note().part() == following.note().part()
                        && following.note().start() - prior.note().end() >= MIN_SOUNDING_SECONDS) {
                    fill(notes, result, previous, next, permitted);
                }
                if (prior.note().end() >= following.note().end()) continue;
            }
            previous = next;
        }
        return List.copyOf(result);
    }

    private static void fill(List<Decision> notes, List<Decision> result, int previous, int next,
                             BiPredicate<Decision, Note> permitted) {
        var prior = notes.get(previous);
        var following = notes.get(next);
        var segment = new ArrayList<Integer>();
        var tail = prior.note();
        double sounding = 0;
        boolean stemSupport = false;
        for (int i = previous + 1; i < next; i++) {
            var candidate = notes.get(i);
            var note = candidate.note();
            if (candidate.role() != Role.counter || note.part() != prior.note().part() || note.end() <= note.start()
                    || note.start() < prior.note().end() || note.end() > following.note().start()
                    || candidate.reason() != null && candidate.reason().startsWith("roleplan#")
                    || !permitted.test(prior, note) || !permitted.test(following, note)) continue;
            if (!connects(tail, note)) return;
            segment.add(i);
            sounding += note.end() - note.start();
            stemSupport |= note.motion() != null && note.motion().confidence() >= MIN_STEM_MOTION;
            tail = note;
        }
        if (sounding < MIN_SOUNDING_SECONDS || !connects(tail, following.note())) return;
        double span = following.note().start() - prior.note().end();
        double handoffRest = notes.get(segment.getFirst()).note().start() - prior.note().end()
                + following.note().start() - tail.end();
        // MIDI alone cannot reliably distinguish a long counter passage or deliberate lead rest.
        if (!stemSupport && (span > MAX_MIDI_GAP || handoffRest > MAX_MIDI_HANDOFF_REST)) return;
        for (int index : segment)
            result.set(index, new Decision(notes.get(index).note(), Role.melody, REASON, null));
    }

    private static boolean connects(Note left, Note right) {
        double rest = right.start() - left.end();
        return rest >= 0 && rest <= MAX_REST_SECONDS && Math.abs(left.pitch() - right.pitch()) <= MAX_LEAP
                && ReconstructionRoles.transition(left, right, rest) <= MAX_TRANSITION_COST;
    }

    record Stats(double beforeSeconds, double afterSeconds) {
        static Stats of(List<Decision> notes) {
            var leadParts = new HashSet<Integer>();
            for (var decision : notes)
                if (decision.role() == Role.melody && !ReconstructionLeadContinuity.hasReason(decision, REASON))
                    leadParts.add(decision.note().part());

            // Sweep both role timelines together, counting concurrent counter notes only once.
            var events = new TreeMap<Double, Coverage>();
            for (var decision : notes) {
                var note = decision.note();
                boolean lead = decision.role() == Role.melody;
                if (!lead && (decision.role() != Role.counter || !leadParts.contains(note.part()))) continue;
                boolean promoted = lead && ReconstructionLeadContinuity.hasReason(decision, REASON);
                double end = decision.preLegatoEnd() == null ? note.end() : decision.preLegatoEnd();
                events.computeIfAbsent(note.start(), ignored -> new Coverage()).add(lead, promoted, 1);
                events.computeIfAbsent(end, ignored -> new Coverage()).add(lead, promoted, -1);
            }
            var active = new Coverage();
            double previous = 0, before = 0, after = 0;
            for (var event : events.entrySet()) {
                double duration = event.getKey() - previous;
                if (active.lead == 0 && active.counter + active.promoted > 0) before += duration;
                if (active.lead + active.promoted == 0 && active.counter > 0) after += duration;
                var change = event.getValue();
                active.lead += change.lead;
                active.promoted += change.promoted;
                active.counter += change.counter;
                previous = event.getKey();
            }
            return new Stats(before, after);
        }
    }

    private static final class Coverage {
        private int lead, promoted, counter;

        void add(boolean melody, boolean moved, int delta) {
            if (moved) promoted += delta;
            else if (melody) lead += delta;
            else counter += delta;
        }
    }
}
