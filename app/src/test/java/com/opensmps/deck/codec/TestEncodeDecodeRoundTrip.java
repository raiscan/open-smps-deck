package com.opensmps.deck.codec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: encode via SmpsEncoder, decode via SmpsDecoder, verify
 * that the decoded representation matches the original intent.
 */
class TestEncodeDecodeRoundTrip {

    @Test
    void encodeDecodeNoteSequence() {
        // Encode three notes: C-3 (0xA5) dur=0x18, D#4 (0xB4) dur=0x20, B-5 (0xC8) dur=0x30
        // B-5 = 0x81 + 5*12 + 11 = 0xC8
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(SmpsEncoder.encodeNote(0xA5, 0x18));
        buf.writeBytes(SmpsEncoder.encodeNote(0xB4, 0x20));
        buf.writeBytes(SmpsEncoder.encodeNote(0xC8, 0x30));
        byte[] encoded = buf.toByteArray();

        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(encoded);
        assertEquals(3, rows.size(), "Should decode three note rows");

        assertEquals("C-3", rows.get(0).note());
        assertEquals(0x18, rows.get(0).duration());

        assertEquals("D#4", rows.get(1).note());
        assertEquals(0x20, rows.get(1).duration());

        assertEquals("B-5", rows.get(2).note());
        assertEquals(0x30, rows.get(2).duration());
    }

    @Test
    void encodeDecodeRestAndTie() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // Note first (tie needs a preceding note context)
        buf.writeBytes(SmpsEncoder.encodeNote(0xBD, 0x18)); // C-5 dur=0x18
        buf.writeBytes(SmpsEncoder.encodeTie());
        buf.writeBytes(SmpsEncoder.encodeRest(0x10));
        byte[] encoded = buf.toByteArray();

        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(encoded);
        assertEquals(3, rows.size(), "Should decode note, tie, and rest");

        assertEquals("C-5", rows.get(0).note());
        assertEquals(0x18, rows.get(0).duration());

        assertEquals("===", rows.get(1).note(), "Tie should decode as ===");

        assertEquals("---", rows.get(2).note(), "Rest should decode as ---");
        assertEquals(0x10, rows.get(2).duration());
    }

    @Test
    void encodeDecodeVoiceChangePlusNote() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(SmpsEncoder.encodeVoiceChange(7));
        buf.writeBytes(SmpsEncoder.encodeNote(0xA5, 0x18)); // C-3
        byte[] encoded = buf.toByteArray();

        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(encoded);
        assertEquals(1, rows.size(), "Voice change + note should be one row");

        assertEquals("C-3", rows.get(0).note());
        assertEquals(0x18, rows.get(0).duration());
        assertEquals("07", rows.get(0).instrument(), "Instrument column should show voice index 07");
    }

    @Test
    void encodeDecodePsgEnvelopePlusNote() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(SmpsEncoder.encodePsgEnvelope(3));
        buf.writeBytes(SmpsEncoder.encodeNote(0xBD, 0x20)); // C-5
        byte[] encoded = buf.toByteArray();

        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(encoded);
        assertEquals(1, rows.size(), "PSG envelope + note should be one row");

        assertEquals("C-5", rows.get(0).note());
        assertEquals(0x20, rows.get(0).duration());
        assertEquals("03", rows.get(0).instrument(), "Instrument column should show PSG envelope index 03");
    }

    @Test
    void encodeDecodeAllOctaves() {
        // Encode C note for each octave 0-7 and verify round-trip
        for (int octave = 0; octave <= 7; octave++) {
            int noteValue = 0x81 + octave * 12;
            if (noteValue > 0xDF) break; // max valid note

            byte[] encoded = SmpsEncoder.encodeNote(noteValue, 0x18);
            List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(encoded);
            assertEquals(1, rows.size());
            assertEquals("C-" + octave, rows.get(0).note(),
                    "Octave " + octave + " C note should round-trip");
        }
    }

    @Test
    void encodeDecodeKeyboardNoteRoundTrip() {
        // Encode from keyboard key, decode back, verify note name matches
        int note = SmpsEncoder.encodeNoteFromKey('Z', 3); // C-3
        assertNotEquals(-1, note);
        byte[] encoded = SmpsEncoder.encodeNote(note, 0x18);

        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(encoded);
        assertEquals(1, rows.size());
        assertEquals("C-3", rows.get(0).note());

        // Sharp note: S at octave 4 = C#4
        int sharpNote = SmpsEncoder.encodeNoteFromKey('S', 4);
        assertNotEquals(-1, sharpNote);
        byte[] sharpEncoded = SmpsEncoder.encodeNote(sharpNote, 0x20);

        List<SmpsDecoder.TrackerRow> sharpRows = SmpsDecoder.decode(sharpEncoded);
        assertEquals(1, sharpRows.size());
        assertEquals("C#4", sharpRows.get(0).note());
    }
}
