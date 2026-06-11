package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TestMappingSuggester {

    private static MidiStem stem(String name, boolean drum, int program, NoteEvent... notes) {
        return new MidiStem(name, 480, List.of(), new MidiStem.TimeSignature(4, 4),
                List.of(new MidiStem.MidiNoteTrack(drum, Set.of(program), List.of(notes))));
    }

    @Test
    void melodicLinesFillFmThenPsg() {
        // two 4-line stems = 8 lines; separation caps at 4 lines per track,
        // so suggestions fill FM1-5 then PSG1-3
        NoteEvent[] chordA = new NoteEvent[4];
        NoteEvent[] chordB = new NoteEvent[4];
        for (int i = 0; i < 4; i++) {
            chordA[i] = new NoteEvent(0, 480, 60 + i * 3, 100);
            chordB[i] = new NoteEvent(0, 480, 61 + i * 3, 100);
        }
        var suggestions = MappingSuggester.suggest(List.of(
                stem("SynthA", false, 80, chordA), stem("SynthB", false, 80, chordB)));
        var channels = suggestions.stream()
                .map(MappingSuggester.Suggestion::targetChannel).toList();
        assertEquals(List.of(0, 1, 2, 3, 4, 6, 7, 8), channels);
    }

    @Test
    void drumTracksAreExcludedFromMelodicSuggestions() {
        var s = MappingSuggester.suggest(List.of(
                stem("Drums", true, 118, new NoteEvent(0, 100, 36, 100))));
        assertTrue(s.isEmpty()); // drums route through GmDrumMapper, not line mapping
    }

    @Test
    void lowMonoLinesPreferFmOverPsg() {
        // bass stem (program 32) should land on an FM channel even when listed last
        var s = MappingSuggester.suggest(List.of(
                stem("Synth", false, 80,
                        new NoteEvent(0, 480, 70, 100), new NoteEvent(0, 480, 74, 100)),
                stem("Bass", false, 32, new NoteEvent(0, 480, 40, 100))));
        var bass = s.stream().filter(x -> x.stemName().equals("Bass")).findFirst().orElseThrow();
        assertTrue(bass.targetChannel() <= 4);
    }
}
