package com.opensmpsdeck.io.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a polyphonic note list into up to maxLines monophonic lines.
 * Line 0 is the skyline (top voice); chords assign descending pitch to
 * ascending line index; isolated overlaps go to the free line with the
 * nearest previous pitch. Notes with no free line are dropped and counted.
 */
public final class VoiceSeparator {

    public record SeparatedLine(int rank, List<NoteEvent> notes) {}

    public record Result(List<SeparatedLine> lines, int droppedNotes) {}

    private VoiceSeparator() {}

    public static Result separate(List<NoteEvent> notes, int maxLines, int chordEpsilonTicks) {
        List<NoteEvent> sorted = new ArrayList<>(notes);
        sorted.sort(Comparator.comparingLong(NoteEvent::startTick)
                .thenComparing(Comparator.comparingInt(NoteEvent::pitch).reversed()));

        List<List<NoteEvent>> lines = new ArrayList<>();
        long[] lineEnd = new long[maxLines];
        int[] linePitch = new int[maxLines];
        boolean[] lineUsed = new boolean[maxLines];
        int dropped = 0;

        int i = 0;
        while (i < sorted.size()) {
            // collect a chord: onsets within epsilon of the first
            long chordStart = sorted.get(i).startTick();
            List<NoteEvent> chord = new ArrayList<>();
            while (i < sorted.size() && sorted.get(i).startTick() - chordStart <= chordEpsilonTicks) {
                chord.add(sorted.get(i));
                i++;
            }
            chord.sort(Comparator.comparingInt(NoteEvent::pitch).reversed());

            if (chord.size() > 1) {
                // chord: highest pitch → lowest-numbered free line
                int nextLine = 0;
                for (NoteEvent note : chord) {
                    int target = -1;
                    for (int l = nextLine; l < maxLines; l++) {
                        if (lineEnd[l] <= note.startTick()) { target = l; break; }
                    }
                    if (target < 0) { dropped++; continue; }
                    place(lines, lineEnd, linePitch, lineUsed, target, note);
                    nextLine = target + 1;
                }
            } else {
                NoteEvent note = chord.get(0);
                int target = -1;
                int bestDist = Integer.MAX_VALUE;
                for (int l = 0; l < maxLines; l++) {
                    if (lineEnd[l] > note.startTick()) continue;
                    // prefer previously-used lines with nearby pitch; unused lines are
                    // a distant fallback so material stays consolidated
                    int dist = lineUsed[l] ? Math.abs(linePitch[l] - note.pitch())
                                           : Integer.MAX_VALUE / 2;
                    if (dist < bestDist) { bestDist = dist; target = l; }
                }
                if (target < 0) { dropped++; }
                else place(lines, lineEnd, linePitch, lineUsed, target, note);
            }
        }

        List<SeparatedLine> result = new ArrayList<>();
        for (int l = 0; l < lines.size(); l++) {
            if (!lines.get(l).isEmpty()) {
                result.add(new SeparatedLine(l, List.copyOf(lines.get(l))));
            }
        }
        return new Result(List.copyOf(result), dropped);
    }

    private static void place(List<List<NoteEvent>> lines, long[] lineEnd, int[] linePitch,
                              boolean[] lineUsed, int l, NoteEvent note) {
        while (lines.size() <= l) lines.add(new ArrayList<>());
        lines.get(l).add(note);
        lineEnd[l] = note.endTick();
        linePitch[l] = note.pitch();
        lineUsed[l] = true;
    }
}
