package com.opensmps.deck.codec;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestSmpsDecoder {

    @Test
    void decodeNoteC0() {
        assertEquals("C-0", SmpsDecoder.decodeNote(0x81));
    }

    @Test
    void decodeNoteC5() {
        assertEquals("C-5", SmpsDecoder.decodeNote(0xBD));
    }

    @Test
    void decodeNoteRest() {
        assertEquals("---", SmpsDecoder.decodeNote(0x80));
    }

    @Test
    void decodeNoteCSharp2() {
        // C#2 = 0x81 + 2*12 + 1 = 0x81 + 25 = 0x9A
        assertEquals("C#2", SmpsDecoder.decodeNote(0x9A));
    }

    @Test
    void decodeSimpleNoteWithDuration() {
        // C-5 (0xBD) with duration 0x18
        byte[] data = { (byte) 0xBD, 0x18 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("C-5", rows.get(0).note());
        assertEquals(0x18, rows.get(0).duration());
    }

    @Test
    void decodeRestWithDuration() {
        // Rest (0x80) with duration 0x10
        byte[] data = { (byte) 0x80, 0x10 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("---", rows.get(0).note());
        assertEquals(0x10, rows.get(0).duration());
    }

    @Test
    void decodeTieProducesTieRow() {
        // C-5 with duration, then tie
        byte[] data = { (byte) 0xBD, 0x18, (byte) 0xE7 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(2, rows.size());
        assertEquals("C-5", rows.get(0).note());
        assertEquals("===", rows.get(1).note());
    }

    @Test
    void decodeInstrumentChange() {
        // Set FM voice 03 (EF 03), then C-5 with duration
        byte[] data = { (byte) 0xEF, 0x03, (byte) 0xBD, 0x18 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("C-5", rows.get(0).note());
        assertEquals("03", rows.get(0).instrument());
    }

    @Test
    void decodePanEffect() {
        // Pan left (E0 80), then C-5
        byte[] data = { (byte) 0xE0, (byte) 0x80, (byte) 0xBD, 0x18 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("E0 80", rows.get(0).effect());
    }

    @Test
    void decodeModulationEffect() {
        // Modulation F0 with 4 params, then note
        byte[] data = { (byte) 0xF0, 0x0A, 0x01, 0x02, 0x04, (byte) 0xBD, 0x18 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("F0 0A 01 02 04", rows.get(0).effect());
    }

    @Test
    void decodeEmptyData() {
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(new byte[0]);
        assertTrue(rows.isEmpty());
    }

    @Test
    void decodeNullData() {
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(null);
        assertTrue(rows.isEmpty());
    }

    @Test
    void decodeMultipleNotes() {
        // Two notes: C-5 dur=0x18, D-5 dur=0x18
        // D-5 = 0x81 + 5*12 + 2 = 0x81 + 62 = 0xBF
        byte[] data = { (byte) 0xBD, 0x18, (byte) 0xBF, 0x18 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(2, rows.size());
        assertEquals("C-5", rows.get(0).note());
        assertEquals("D-5", rows.get(1).note());
    }

    @Test
    void decodeNoteReusesLastDuration() {
        // C-5 dur=0x18, then E-5 with no duration (reuses 0x18)
        // E-5 = 0x81 + 5*12 + 4 = 0x81 + 64 = 0xC1
        byte[] data = { (byte) 0xBD, 0x18, (byte) 0xC1 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(2, rows.size());
        assertEquals(0x18, rows.get(0).duration());
        assertEquals(0x18, rows.get(1).duration()); // reused
    }

    @Test
    void testTrailingEffectProducesRow() {
        // SET_VOICE (0xEF) + voice index 1, no note following
        byte[] data = {(byte) 0xEF, 0x01};
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertFalse(rows.isEmpty(), "Trailing effect should produce at least one row");
        // The row should show the effect (SET_VOICE goes to instrument column)
        assertNotNull(rows.get(0).effect());
    }

    @Test
    void decodeStopsAtF2() {
        // C-5 dur 0x18, then STOP (F2), then D-5 dur 0x18 (should never be reached)
        byte[] data = { (byte) 0xBD, 0x18, (byte) 0xF2, (byte) 0xBF, 0x18 };
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size(), "Decoding should stop at F2");
        assertEquals("C-5", rows.get(0).note());
    }

    @Test
    void decodeTruncatedModulationFlagDoesNotThrow() {
        // Modulation flag 0xF0 expects 4 parameter bytes; provide fewer
        // This should not throw -- the decoder should handle truncated data gracefully
        byte[] data1 = { (byte) 0xF0 }; // flag byte only, no params
        assertDoesNotThrow(() -> SmpsDecoder.decode(data1));

        byte[] data2 = { (byte) 0xF0, 0x0A }; // only 1 of 4 params
        assertDoesNotThrow(() -> SmpsDecoder.decode(data2));

        byte[] data3 = { (byte) 0xF0, 0x0A, 0x01, 0x02 }; // only 3 of 4 params
        assertDoesNotThrow(() -> SmpsDecoder.decode(data3));
    }

    @Test
    void decodeBoundaryNotes() {
        // 0x81 = lowest valid note (C-0)
        assertEquals("C-0", SmpsDecoder.decodeNote(0x81));

        // 0xDF = highest valid note
        // 0xDF - 0x81 = 0x5E = 94, octave = 94/12 = 7, semitone = 94%12 = 10 => A#7
        String highest = SmpsDecoder.decodeNote(0xDF);
        assertNotNull(highest);
        assertFalse(highest.contains("?"), "0xDF should decode as a valid note, not ???");

        // Verify correct note name for 0xDF
        int index = 0xDF - 0x81; // 94
        int expectedOctave = index / 12; // 7
        int expectedSemitone = index % 12; // 10 = A#
        assertEquals("A#" + expectedOctave, highest);

        // Just below range: 0x80 is rest
        assertEquals("---", SmpsDecoder.decodeNote(0x80));

        // Just above range: 0xE0 is a coordination flag, should decode as ???
        assertEquals("???", SmpsDecoder.decodeNote(0xE0));
    }
}
