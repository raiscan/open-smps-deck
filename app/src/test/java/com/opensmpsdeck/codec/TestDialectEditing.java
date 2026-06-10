package com.opensmpsdeck.codec;

import com.opensmps.smps.SmpsCoordFlags;
import com.opensmps.smps.SmpsCoordFlags.Dialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The editing layer must walk bytecode with dialect-correct flag sizes
 * (task: S3K phrases were misparsed and corrupted by edits), and bare
 * duration bytes re-trigger notes so they must be rows of their own
 * (task: re-trigger notes were invisible and destroyed by edits).
 */
class TestDialectEditing {

    // --- S3K dialect walking ---

    @Test
    void s3kFlagSizesParseCorrectly() {
        // EE aa bb (FM_COMMAND, 2 params in S3K; 0 params as S2 NOOP_EE)
        // followed by a note. With S2 sizes the params would be read as a
        // note (0x90) and garbage.
        byte[] track = {(byte) 0xEE, (byte) 0x90, 0x42, (byte) 0xA5, 0x10, (byte) 0xF2};

        List<SmpsDecoder.TrackerRow> s3k = SmpsDecoder.decode(track, Dialect.S3K);
        assertEquals(1, s3k.size(), "S3K: EE consumes 2 params, leaving one note row");
        assertEquals(0x10, s3k.get(0).duration());

        List<SmpsDecoder.TrackerRow> s2 = SmpsDecoder.decode(track, Dialect.S2);
        assertEquals(2, s2.size(), "S2: EE has no params, 0x90 becomes a note");
    }

    @Test
    void s3kMetaFlagConsumesSubCommandParams() {
        // FF 00 tt = TEMPO_SET (1 sub-param), then a note
        byte[] track = {(byte) 0xFF, 0x00, (byte) 0x95, (byte) 0xA5, 0x10, (byte) 0xF2};
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(track, Dialect.S3K);
        assertEquals(1, rows.size(), "Meta param byte 0x95 must not decode as a note");
        assertEquals("---", SmpsDecoder.decodeNote(0x80));
    }

    @Test
    void s3kTrackEndStopsDecoding() {
        // E3 = TRK_END in S3K (RETURN in S2)
        byte[] track = {(byte) 0xA5, 0x10, (byte) 0xE3, (byte) 0xA7, 0x10, (byte) 0xF2};
        assertEquals(1, SmpsDecoder.decode(track, Dialect.S3K).size(),
                "S3K decoding must stop at E3");
        assertEquals(2, SmpsDecoder.decode(track, Dialect.S2).size(),
                "S2 treats E3 as RETURN, not track end");
    }

    @Test
    void s3kEditSplicesAtCorrectOffsets() {
        // Deleting the note row must not eat the EE flag's parameter bytes
        byte[] track = {(byte) 0xEE, (byte) 0x90, 0x42, (byte) 0xA5, 0x10, (byte) 0xA7, 0x08, (byte) 0xF2};
        byte[] result = SmpsEncoder.deleteRow(track, 0, Dialect.S3K);
        assertEquals((byte) 0xEE, result[0]);
        assertEquals((byte) 0x90, result[1]);
        assertEquals(0x42, result[2]);
        assertEquals((byte) 0xA7, result[3], "Second note must survive the delete intact");
    }

    @Test
    void s3kRemapperSkipsFlagParams() {
        // EE's second param 0xEF must not be mistaken for SET_VOICE
        byte[] track = {(byte) 0xEE, 0x42, (byte) 0xEF, (byte) 0xA5, 0x10, (byte) 0xF2};
        InstrumentRemapper.ScanResult scan = InstrumentRemapper.scan(track, Dialect.S3K);
        assertTrue(scan.voiceIndices().isEmpty(),
                "Param byte 0xEF inside EE must not register as a voice change");
    }

    @Test
    void s3kEffectMnemonics() {
        assertEquals("TRN +02", EffectMnemonics.format(0xFB, new int[]{0x02}, Dialect.S3K));
        assertEquals("VLA 10", EffectMnemonics.format(0xE4, new int[]{0x10}, Dialect.S3K));
        assertEquals(0xFB, EffectMnemonics.parse("TRN +02", Dialect.S3K).flag());
        assertEquals(SmpsCoordFlags.KEY_DISP, EffectMnemonics.parse("TRN +02", Dialect.S2).flag());
    }

    // --- Bare-duration re-trigger rows ---

    @Test
    void bareDurationsDecodeAsReTriggerRows() {
        // A5 10 08 08 = note (dur 0x10), then two re-triggers at dur 8
        byte[] track = {(byte) 0xA5, 0x10, 0x08, 0x08, (byte) 0xF2};
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(track);
        assertEquals(3, rows.size(), "Each bare duration re-plays the note: 3 audible rows");
        assertEquals(rows.get(0).note(), rows.get(1).note(), "Re-trigger repeats the note");
        assertEquals(0x08, rows.get(1).duration());
        assertEquals(0x10, rows.get(0).duration());
    }

    @Test
    void leadingBareDurationIsNotARow() {
        // Duration prefix before any note only sets state (slice-init style)
        byte[] track = {0x10, (byte) 0xA5, (byte) 0xF2};
        List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(track);
        assertEquals(1, rows.size());
        assertEquals(0x10, rows.get(0).duration(), "Note inherits the prefix duration");
    }

    @Test
    void deletingNoteRowPreservesReTriggers() {
        // The old row model put re-trigger bytes inside the note row's span,
        // so deleting the note silently destroyed the following re-triggers.
        byte[] track = {(byte) 0xA5, 0x10, 0x08, 0x08, (byte) 0xA7, 0x18, (byte) 0xF2};
        byte[] result = SmpsEncoder.deleteRow(track, 0);
        assertEquals(0x08, result[0], "Re-trigger rows survive deleting the note row");
        assertEquals(0x08, result[1]);
        assertEquals((byte) 0xA7, result[2]);
    }

    @Test
    void deletingReTriggerRowRemovesOnlyThatByte() {
        byte[] track = {(byte) 0xA5, 0x10, 0x08, 0x08, (byte) 0xF2};
        byte[] result = SmpsEncoder.deleteRow(track, 1);
        assertArrayEquals(new byte[]{(byte) 0xA5, 0x10, 0x08, (byte) 0xF2}, result);
    }

    @Test
    void encoderAndDecoderRowModelsAgree() {
        byte[] track = {
            (byte) 0xEF, 0x01,
            (byte) 0xA5, 0x10, 0x08,
            (byte) 0xE7,                // tie row
            (byte) 0x80, 0x18, 0x18,    // rest + re-trigger
            (byte) 0xF2
        };
        int decoded = SmpsDecoder.decode(track).size();
        int offsets = SmpsEncoder.findRowByteOffsets(track).length;
        assertEquals(decoded, offsets,
                "Decoder rows and encoder row offsets must stay in lockstep");
        assertEquals(5, decoded); // note, re-trigger, tie, rest, re-trigger
    }
}
