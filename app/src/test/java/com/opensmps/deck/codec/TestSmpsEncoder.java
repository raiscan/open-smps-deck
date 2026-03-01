package com.opensmps.deck.codec;

import org.junit.jupiter.api.Test;
import com.opensmps.smps.SmpsCoordFlags;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestSmpsEncoder {

    @Test
    void encodeNoteFromKeyZ_Octave3_IsC3() {
        // Z at octave 3 = C-3 = 0x81 + 3*12 + 0 = 0x81 + 36 = 0xA5
        int note = SmpsEncoder.encodeNoteFromKey('Z', 3);
        assertEquals(0xA5, note);
        assertEquals("C-3", SmpsDecoder.decodeNote(note));
    }

    @Test
    void encodeNoteFromKeyQ_Octave3_IsC4() {
        // Q at octave 3 = C-(3+1) = C-4 = 0x81 + 4*12 + 0 = 0xB1
        int note = SmpsEncoder.encodeNoteFromKey('Q', 3);
        assertEquals(0xB1, note);
        assertEquals("C-4", SmpsDecoder.decodeNote(note));
    }

    @Test
    void encodeNoteFromKeyS_IsSharp() {
        // S at octave 3 = C#3 = 0x81 + 3*12 + 1 = 0xA6
        int note = SmpsEncoder.encodeNoteFromKey('S', 3);
        assertEquals(0xA6, note);
        assertEquals("C#3", SmpsDecoder.decodeNote(note));
    }

    @Test
    void encodeNoteFromKeyInvalid_ReturnsMinusOne() {
        assertEquals(-1, SmpsEncoder.encodeNoteFromKey('P', 3));
    }

    @Test
    void encodeNoteFromKeyClampsHigh() {
        // M at octave 7 = B-8 which is way past 0xDF
        int note = SmpsEncoder.encodeNoteFromKey('M', 7);
        assertEquals(0xDF, note); // clamped
    }

    @Test
    void encodeNoteBytes() {
        byte[] bytes = SmpsEncoder.encodeNote(0xBD, 0x18);
        assertEquals(2, bytes.length);
        assertEquals((byte) 0xBD, bytes[0]);
        assertEquals((byte) 0x18, bytes[1]);
    }

    @Test
    void encodeRestBytes() {
        byte[] bytes = SmpsEncoder.encodeRest(0x10);
        assertEquals(2, bytes.length);
        assertEquals((byte) 0x80, bytes[0]);
        assertEquals((byte) 0x10, bytes[1]);
    }

    @Test
    void encodeTieBytes() {
        byte[] bytes = SmpsEncoder.encodeTie();
        assertEquals(1, bytes.length);
        assertEquals((byte) 0xE7, bytes[0]);
    }

    @Test
    void encodeVoiceChangeBytes() {
        byte[] bytes = SmpsEncoder.encodeVoiceChange(5);
        assertEquals(2, bytes.length);
        assertEquals((byte) 0xEF, bytes[0]); // EF = Set Voice in Z80 convention
        assertEquals((byte) 0x05, bytes[1]);
    }

    @Test
    void encodePsgEnvelopeBytes() {
        byte[] bytes = SmpsEncoder.encodePsgEnvelope(7);
        assertEquals(2, bytes.length);
        assertEquals((byte) 0xF5, bytes[0]); // F5 = PSG Instrument in Z80 convention
        assertEquals((byte) 0x07, bytes[1]);
    }

    @Test
    void transposeSemitoneUp() {
        assertEquals(0xBE, SmpsEncoder.transpose(0xBD, 1)); // C-5 -> C#5
    }

    @Test
    void transposeSemitoneDown() {
        assertEquals(0xBC, SmpsEncoder.transpose(0xBD, -1)); // C-5 -> B-4
    }

    @Test
    void transposeOctaveUp() {
        assertEquals(0xBD + 12, SmpsEncoder.transpose(0xBD, 12)); // C-5 -> C-6
    }

    @Test
    void transposeClampsLow() {
        assertEquals(0x81, SmpsEncoder.transpose(0x81, -5)); // can't go below C-0
    }

    @Test
    void transposeClampsHigh() {
        assertEquals(0xDF, SmpsEncoder.transpose(0xDF, 5)); // can't go above max
    }

    @Test
    void transposeIgnoresNonNote() {
        assertEquals(0x80, SmpsEncoder.transpose(0x80, 3)); // rest stays rest
    }

    @Test
    void insertAtRowReplacesExisting() {
        // Track: C-5 dur=0x18
        byte[] track = { (byte) 0xBD, 0x18 };
        byte[] newNote = SmpsEncoder.encodeNote(0xBF, 0x18); // D-5
        byte[] result = SmpsEncoder.insertAtRow(track, 0, newNote);
        assertEquals(2, result.length);
        assertEquals((byte) 0xBF, result[0]);
        assertEquals((byte) 0x18, result[1]);
    }

    @Test
    void insertAtRowAppendsWhenBeyondEnd() {
        byte[] track = {};
        byte[] newNote = SmpsEncoder.encodeNote(0xBD, 0x18);
        byte[] result = SmpsEncoder.insertAtRow(track, 0, newNote);
        assertEquals(2, result.length);
        assertEquals((byte) 0xBD, result[0]);
    }

    @Test
    void deleteRowRemovesNote() {
        // Track: C-5 dur=0x18, D-5 dur=0x18
        byte[] track = { (byte) 0xBD, 0x18, (byte) 0xBF, 0x18 };
        byte[] result = SmpsEncoder.deleteRow(track, 0);
        assertEquals(2, result.length);
        assertEquals((byte) 0xBF, result[0]); // D-5 remains
    }

    @Test
    void findRowByteOffsetsMultipleNotes() {
        byte[] track = { (byte) 0xBD, 0x18, (byte) 0xBF, 0x18, (byte) 0x80, 0x10 };
        int[] offsets = SmpsEncoder.findRowByteOffsets(track);
        assertEquals(3, offsets.length);
        assertEquals(0, offsets[0]); // C-5 at byte 0
        assertEquals(2, offsets[1]); // D-5 at byte 2
        assertEquals(4, offsets[2]); // rest at byte 4
    }

    @Test
    void testEncodeCoordFlagOnlyTrack() {
        // A track that sets voice 0, then stops: EF 00 F2
        byte[] data = {(byte) 0xEF, 0x00, (byte) 0xF2};
        // Decode should produce rows
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
        assertFalse(rows.isEmpty());
    }

    @Test
    void getRowEffectsExcludesInstrumentFlags() {
        // EF 03 E0 40 F0 01 02 03 04  BD 18
        byte[] track = {
                (byte) SmpsCoordFlags.SET_VOICE, 0x03,
                (byte) SmpsCoordFlags.PAN, 0x40,
                (byte) SmpsCoordFlags.MODULATION, 0x01, 0x02, 0x03, 0x04,
                (byte) 0xBD, 0x18
        };

        List<SmpsEncoder.EffectCommand> effects = SmpsEncoder.getRowEffects(track, 0);
        assertEquals(2, effects.size());
        assertEquals(SmpsCoordFlags.PAN, effects.get(0).flag());
        assertArrayEquals(new int[]{0x40}, effects.get(0).params());
        assertEquals(SmpsCoordFlags.MODULATION, effects.get(1).flag());
        assertArrayEquals(new int[]{0x01, 0x02, 0x03, 0x04}, effects.get(1).params());
    }

    @Test
    void setRowEffectsPreservesInstrumentPrefix() {
        // EF 03 E0 40 BD 18
        byte[] track = {
                (byte) SmpsCoordFlags.SET_VOICE, 0x03,
                (byte) SmpsCoordFlags.PAN, 0x40,
                (byte) 0xBD, 0x18
        };

        List<SmpsEncoder.EffectCommand> replacement = List.of(
                new SmpsEncoder.EffectCommand(SmpsCoordFlags.DETUNE, new int[]{0x7F})
        );
        byte[] out = SmpsEncoder.setRowEffects(track, 0, replacement);

        assertArrayEquals(new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x03,
                (byte) SmpsCoordFlags.DETUNE, 0x7F,
                (byte) 0xBD, 0x18
        }, out);
    }

    @Test
    void setRowEffectsClearsEffectsWhenEmptyList() {
        // E0 40 F0 01 02 03 04 BD 18
        byte[] track = {
                (byte) SmpsCoordFlags.PAN, 0x40,
                (byte) SmpsCoordFlags.MODULATION, 0x01, 0x02, 0x03, 0x04,
                (byte) 0xBD, 0x18
        };

        byte[] out = SmpsEncoder.setRowEffects(track, 0, List.of());
        assertArrayEquals(new byte[]{(byte) 0xBD, 0x18}, out);
    }
}
