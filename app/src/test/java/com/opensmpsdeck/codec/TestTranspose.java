package com.opensmpsdeck.codec;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestTranspose {

    @Test
    void transposeSingleNoteBytesUp() {
        // C-5 (0xBD) + 1 = C#5 (0xBE)
        byte[] track = { (byte) 0xBD, 0x18 };
        byte[] result = SmpsEncoder.transposeTrackRange(track, 0, 1, 1);
        assertEquals((byte) 0xBE, result[0]);
        assertEquals((byte) 0x18, result[1]); // duration unchanged
    }

    @Test
    void transposeSingleNoteBytesDown() {
        byte[] track = { (byte) 0xBD, 0x18 };
        byte[] result = SmpsEncoder.transposeTrackRange(track, 0, 1, -1);
        assertEquals((byte) 0xBC, result[0]);
    }

    @Test
    void transposeOctaveUp() {
        byte[] track = { (byte) 0xBD, 0x18 };
        byte[] result = SmpsEncoder.transposeTrackRange(track, 0, 1, 12);
        assertEquals((byte) 0xC9, result[0]); // C-5 + 12 = C-6
    }

    @Test
    void transposeOctaveDown() {
        byte[] track = { (byte) 0xBD, 0x18 };
        byte[] result = SmpsEncoder.transposeTrackRange(track, 0, 1, -12);
        assertEquals((byte) 0xB1, result[0]); // C-5 - 12 = C-4
    }

    @Test
    void transposeClampsAtMinimum() {
        byte[] track = { (byte) 0x81, 0x18 }; // C-0
        byte[] result = SmpsEncoder.transposeTrackRange(track, 0, 1, -5);
        assertEquals((byte) 0x81, result[0]); // stays at C-0
    }

    @Test
    void transposeClampsAtMaximum() {
        byte[] track = { (byte) 0xDF, 0x18 }; // max note
        byte[] result = SmpsEncoder.transposeTrackRange(track, 0, 1, 5);
        assertEquals((byte) 0xDF, result[0]); // stays at max
    }

    @Test
    void transposeSkipsRest() {
        byte[] track = { (byte) 0x80, 0x18 }; // rest
        byte[] result = SmpsEncoder.transposeTrackRange(track, 0, 1, 3);
        assertEquals((byte) 0x80, result[0]); // rest stays rest
    }

    @Test
    void transposeMultipleNotes() {
        // C-5 dur=0x18, D-5 dur=0x18
        byte[] track = { (byte) 0xBD, 0x18, (byte) 0xBF, 0x18 };
        byte[] result = SmpsEncoder.transposeTrackRange(track, 0, 2, 2);
        assertEquals((byte) 0xBF, result[0]); // C-5 + 2 = D-5
        assertEquals((byte) 0xC1, result[2]); // D-5 + 2 = E-5
    }

    @Test
    void transposePartialRange() {
        // 3 notes, only transpose rows 1-1 (second note only)
        byte[] track = { (byte) 0xBD, 0x18, (byte) 0xBF, 0x18, (byte) 0xC1, 0x18 };
        byte[] result = SmpsEncoder.transposeTrackRange(track, 1, 1, 1);
        assertEquals((byte) 0xBD, result[0]); // first unchanged
        assertEquals((byte) 0xC0, result[2]); // second transposed
        assertEquals((byte) 0xC1, result[4]); // third unchanged
    }

    @Test
    void extractRowRangeBytes() {
        // 3 notes
        byte[] track = { (byte) 0xBD, 0x18, (byte) 0xBF, 0x18, (byte) 0xC1, 0x18 };
        byte[] extracted = SmpsEncoder.extractRowRange(track, 1, 2);
        assertEquals(4, extracted.length); // rows 1-2 = 2 notes x 2 bytes
        assertEquals((byte) 0xBF, extracted[0]);
        assertEquals((byte) 0xC1, extracted[2]);
    }
}
