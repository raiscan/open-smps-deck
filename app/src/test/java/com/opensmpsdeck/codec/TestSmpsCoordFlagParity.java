package com.opensmpsdeck.codec;

import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that SmpsDecoder and SmpsEncoder correctly use SmpsCoordFlags
 * for coordination flag parameter counts and semantic identification.
 */
class TestSmpsCoordFlagParity {

    @Test
    void encodeVoiceChangeUsesEF() {
        byte[] bytes = SmpsEncoder.encodeVoiceChange(3);
        assertEquals((byte) SmpsCoordFlags.SET_VOICE, bytes[0],
            "Voice change should use EF (Set Voice), not E1 (Detune)");
    }

    @Test
    void encodePsgEnvelopeUsesF5() {
        byte[] bytes = SmpsEncoder.encodePsgEnvelope(2);
        assertEquals((byte) SmpsCoordFlags.PSG_INSTRUMENT, bytes[0],
            "PSG envelope should use F5 (PSG Instrument), not E4 (Fade In)");
    }

    @Test
    void encodeTieUsesE7() {
        byte[] bytes = SmpsEncoder.encodeTie();
        assertEquals((byte) SmpsCoordFlags.TIE, bytes[0]);
    }

    @Test
    void decoderShowsInstrumentForSetVoice() {
        // EF 05 = Set Voice 5, then a note
        byte[] data = { (byte) SmpsCoordFlags.SET_VOICE, 0x05, (byte) 0xBD, 0x18 };
        var rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("05", rows.get(0).instrument());
    }

    @Test
    void decoderShowsInstrumentForPsgInstrument() {
        // F5 03 = PSG Instrument 3, then a note
        byte[] data = { (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x03, (byte) 0xBD, 0x18 };
        var rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("03", rows.get(0).instrument());
    }

    @Test
    void decoderShowsDetuneAsEffect() {
        // E1 04 = Detune (NOT voice change), then a note
        byte[] data = { (byte) SmpsCoordFlags.DETUNE, 0x04, (byte) 0xBD, 0x18 };
        var rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("E1 04", rows.get(0).effect());
        assertEquals("", rows.get(0).instrument()); // should NOT be in instrument column
    }

    @Test
    void decoderHandlesModulation4Params() {
        // F0 0A 01 02 04 = Modulation with 4 params, then note
        byte[] data = { (byte) SmpsCoordFlags.MODULATION, 0x0A, 0x01, 0x02, 0x04,
                        (byte) 0xBD, 0x18 };
        var rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("F0 0A 01 02 04", rows.get(0).effect());
    }

    @Test
    void decoderHandlesAccumulatedEffects() {
        // Pan E0 80, then Detune E1 02, then note
        byte[] data = { (byte) SmpsCoordFlags.PAN, (byte) 0x80,
                        (byte) SmpsCoordFlags.DETUNE, 0x02,
                        (byte) 0xBD, 0x18 };
        var rows = SmpsDecoder.decode(data);
        assertEquals(1, rows.size());
        assertEquals("E0 80; E1 02", rows.get(0).effect());
    }

    @Test
    void findRowOffsetsSkipsCorrectParamCounts() {
        // EF 00 (Set Voice, 1 param), BD 18 (note C-5 dur), E7 (tie, 0 params)
        byte[] data = { (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                        (byte) 0xBD, 0x18,
                        (byte) SmpsCoordFlags.TIE };
        int[] offsets = SmpsEncoder.findRowByteOffsets(data);
        assertEquals(2, offsets.length);
        assertEquals(2, offsets[0]); // note at byte 2 (after EF 00)
        assertEquals(4, offsets[1]); // tie at byte 4
    }
}
