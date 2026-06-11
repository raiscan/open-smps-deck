package com.opensmpsdeck.io.midi;

import java.util.ArrayList;
import java.util.List;

/** Produces the dialog's pre-filled line → channel assignment. */
public final class MappingSuggester {

    public record Suggestion(String stemName, VoiceSeparator.SeparatedLine line,
                             int gmProgram, int targetChannel) {}

    private static final int[] MELODIC_CHANNELS = {0, 1, 2, 3, 4, 6, 7, 8};
    private static final int MAX_LINES_PER_TRACK = 4;
    private static final int CHORD_EPSILON_TICKS = 15;

    private MappingSuggester() {}

    public static List<Suggestion> suggest(List<MidiStem> stems) {
        record Candidate(String stem, VoiceSeparator.SeparatedLine line, int program,
                         double medianPitch, boolean bassLike) {}
        List<Candidate> candidates = new ArrayList<>();

        for (MidiStem stem : stems) {
            for (MidiStem.MidiNoteTrack track : stem.tracks()) {
                if (track.drumTrack()) continue; // routed via GmDrumMapper
                int program = track.programs().stream().findFirst().orElse(80);
                var sep = VoiceSeparator.separate(track.notes(), MAX_LINES_PER_TRACK,
                        CHORD_EPSILON_TICKS);
                for (var line : sep.lines()) {
                    double median = line.notes().stream()
                            .mapToInt(NoteEvent::pitch).sorted()
                            .skip(line.notes().size() / 2).findFirst().orElse(60);
                    boolean bassLike = (program >= 32 && program <= 39) || median < 48;
                    candidates.add(new Candidate(stem.name(), line, program, median, bassLike));
                }
            }
        }
        // bass-like first (they must get FM), then by line rank, then by note count desc
        candidates.sort((a, b) -> {
            if (a.bassLike() != b.bassLike()) return a.bassLike() ? -1 : 1;
            if (a.line().rank() != b.line().rank())
                return Integer.compare(a.line().rank(), b.line().rank());
            return Integer.compare(b.line().notes().size(), a.line().notes().size());
        });

        List<Suggestion> out = new ArrayList<>();
        int next = 0;
        for (Candidate c : candidates) {
            if (next >= MELODIC_CHANNELS.length) break; // overflow stays unmapped
            out.add(new Suggestion(c.stem(), c.line(), c.program(), MELODIC_CHANNELS[next++]));
        }
        return out;
    }
}
