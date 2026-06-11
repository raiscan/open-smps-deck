package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.codec.SmpsDecoder;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.PhraseLibrary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestMidiPhraseEncoder {

    private static final MidiPhraseEncoder.EncodeParams P =
            new MidiPhraseEncoder.EncodeParams(4, 16, 1, 0); // 4 units/16th, 16 steps/bar, 1 bar/phrase, no shift

    @Test
    void encodesNoteAndRest() {
        var lib = new PhraseLibrary();
        var notes = List.of(new NoteQuantizer.QuantizedNote(0, 2, 60, 100)); // C4 half of the bar? 2 steps
        var warnings = new ArrayList<String>();
        var entries = MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, P, lib,
                new java.util.HashMap<>(), "Synth-0", warnings);

        assertEquals(1, entries.size());
        byte[] data = lib.getPhrase(entries.get(0).getPhraseId()).getData();
        var rows = SmpsDecoder.decode(data);
        // row 0: C-4 (0xB1) dur 8 (2 steps × 4 units); row 1: rest filling the bar
        assertEquals("C-4", rows.get(0).note());
        assertEquals(8, rows.get(0).duration());
        assertEquals("---", rows.get(1).note());
        assertEquals(56, rows.get(1).duration()); // 14 steps × 4 units
        assertTrue(warnings.isEmpty());
    }

    @Test
    void identicalBarsDeduplicate() {
        var lib = new PhraseLibrary();
        // same one-note figure in bar 0 and bar 1
        var notes = List.of(
                new NoteQuantizer.QuantizedNote(0, 4, 60, 100),
                new NoteQuantizer.QuantizedNote(16, 4, 60, 100));
        var entries = MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, P, lib,
                new java.util.HashMap<>(), "x", new ArrayList<>());
        // dedup collapses to one phrase id; consecutive repeat → one entry repeatCount 2
        assertEquals(1, entries.size());
        assertEquals(2, entries.get(0).getRepeatCount());
        assertEquals(1, lib.getAllPhrases().size());
    }

    @Test
    void longDurationSplitsWithTie() {
        // 40 steps at 4 units = 160 units > 0x7F → must split
        var p = new MidiPhraseEncoder.EncodeParams(4, 64, 1, 0); // long bar so no phrase cut
        var lib = new PhraseLibrary();
        var notes = List.of(new NoteQuantizer.QuantizedNote(0, 40, 60, 100));
        var entries = MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, p, lib,
                new java.util.HashMap<>(), "x", new ArrayList<>());
        byte[] data = lib.getPhrase(entries.get(0).getPhraseId()).getData();
        // Expect note 0x7F then E7-continued remainder; total sounding duration preserved.
        // SmpsDecoder emits a standalone "===" row for the E7 tie flag, carrying the
        // PREVIOUS chunk's duration as a display carry-over (it does not consume the
        // continuation's note/dur bytes). That marker is not a fresh attack and must be
        // excluded when verifying total duration — the real note attacks plus the
        // trailing rest must sum to the original. See MidiPhraseEncoder.emitChunked.
        int total = SmpsDecoder.decode(data).stream()
                .filter(r -> !r.note().isEmpty() && !r.note().equals("==="))
                .mapToInt(r -> r.duration()).sum();
        assertEquals(160 + (64 - 40) * 4, total); // note chunks + trailing rest
    }

    @Test
    void outOfRangePitchClampsWithWarning() {
        var lib = new PhraseLibrary();
        var notes = List.of(new NoteQuantizer.QuantizedNote(0, 1, 5, 100)); // below MIDI 12
        var warnings = new ArrayList<String>();
        MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, P, lib,
                new java.util.HashMap<>(), "x", warnings);
        assertFalse(warnings.isEmpty());
    }

    @Test
    void noteSpanningBarBoundarySplitsWithTie() {
        var lib = new PhraseLibrary();
        // starts at step 14, length 4 → crosses the bar at step 16
        var notes = List.of(new NoteQuantizer.QuantizedNote(14, 4, 60, 100));
        var entries = MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, P, lib,
                new java.util.HashMap<>(), "x", new ArrayList<>());
        assertEquals(2, entries.size()); // two bars → two phrases
        // second phrase starts with a tied continuation of C-4
        byte[] second = lib.getPhrase(entries.get(1).getPhraseId()).getData();
        assertEquals((byte) 0xE7, second[0]);
    }
}
