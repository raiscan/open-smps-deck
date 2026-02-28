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
}
