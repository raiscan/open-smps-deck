package com.opensmpsdeck.codec;

import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestEffectMnemonics {

    @Test
    void formatPanLeftRight() {
        assertEquals("PAN LR", EffectMnemonics.format(SmpsCoordFlags.PAN, new int[]{0xC0}));
    }

    @Test
    void formatPanLeft() {
        assertEquals("PAN L", EffectMnemonics.format(SmpsCoordFlags.PAN, new int[]{0x80}));
    }

    @Test
    void formatPanRight() {
        assertEquals("PAN R", EffectMnemonics.format(SmpsCoordFlags.PAN, new int[]{0x40}));
    }

    @Test
    void formatPanOff() {
        assertEquals("PAN --", EffectMnemonics.format(SmpsCoordFlags.PAN, new int[]{0x00}));
    }

    @Test
    void formatVolumePositive() {
        assertEquals("VOL +05", EffectMnemonics.format(SmpsCoordFlags.VOLUME, new int[]{0x05}));
    }

    @Test
    void formatVolumeNegative() {
        // 0xFB = -5 signed
        assertEquals("VOL -05", EffectMnemonics.format(SmpsCoordFlags.VOLUME, new int[]{0xFB}));
    }

    @Test
    void formatDetune() {
        assertEquals("DET +03", EffectMnemonics.format(SmpsCoordFlags.DETUNE, new int[]{0x03}));
    }

    @Test
    void formatModulation() {
        assertEquals("MOD 0A010204", EffectMnemonics.format(SmpsCoordFlags.MODULATION,
            new int[]{0x0A, 0x01, 0x02, 0x04}));
    }

    @Test
    void formatTie() {
        assertEquals("TIE", EffectMnemonics.format(SmpsCoordFlags.TIE, new int[0]));
    }

    @Test
    void formatModOff() {
        assertEquals("MOFF", EffectMnemonics.format(SmpsCoordFlags.MOD_OFF, new int[0]));
    }

    @Test
    void formatStop() {
        assertEquals("STP", EffectMnemonics.format(SmpsCoordFlags.STOP, new int[0]));
    }

    @Test
    void formatTranspose() {
        assertEquals("TRN +07", EffectMnemonics.format(SmpsCoordFlags.KEY_DISP, new int[]{0x07}));
    }

    @Test
    void formatNoteFill() {
        assertEquals("FIL 80", EffectMnemonics.format(SmpsCoordFlags.NOTE_FILL, new int[]{0x80}));
    }

    @Test
    void formatSetTempo() {
        assertEquals("TMP 78", EffectMnemonics.format(SmpsCoordFlags.SET_TEMPO, new int[]{0x78}));
    }

    @Test
    void formatPsgNoise() {
        assertEquals("NOI 03", EffectMnemonics.format(SmpsCoordFlags.PSG_NOISE, new int[]{0x03}));
    }

    @Test
    void formatTickMult() {
        assertEquals("TIK 02", EffectMnemonics.format(SmpsCoordFlags.TICK_MULT, new int[]{0x02}));
    }

    @Test
    void formatDivTiming() {
        assertEquals("DIV 02", EffectMnemonics.format(SmpsCoordFlags.SET_DIV_TIMING, new int[]{0x02}));
    }

    @Test
    void formatPsgVolume() {
        assertEquals("PVL +03", EffectMnemonics.format(SmpsCoordFlags.PSG_VOLUME, new int[]{0x03}));
    }

    @Test
    void formatSoundOff() {
        assertEquals("SOF", EffectMnemonics.format(SmpsCoordFlags.SND_OFF, new int[0]));
    }

    @Test
    void formatModOn() {
        assertEquals("MON", EffectMnemonics.format(SmpsCoordFlags.MOD_ON, new int[0]));
    }

    @Test
    void formatComm() {
        assertEquals("COM 42", EffectMnemonics.format(SmpsCoordFlags.SET_COMM, new int[]{0x42}));
    }

    @Test
    void parsePanLeftRight() {
        var cmd = EffectMnemonics.parse("PAN LR");
        assertEquals(SmpsCoordFlags.PAN, cmd.flag());
        assertArrayEquals(new int[]{0xC0}, cmd.params());
    }

    @Test
    void parseVolumePositive() {
        var cmd = EffectMnemonics.parse("VOL +05");
        assertEquals(SmpsCoordFlags.VOLUME, cmd.flag());
        assertArrayEquals(new int[]{0x05}, cmd.params());
    }

    @Test
    void parseVolumeNegative() {
        var cmd = EffectMnemonics.parse("VOL -05");
        assertEquals(SmpsCoordFlags.VOLUME, cmd.flag());
        assertArrayEquals(new int[]{0xFB}, cmd.params());
    }

    @Test
    void parseModulation() {
        var cmd = EffectMnemonics.parse("MOD 0A010204");
        assertEquals(SmpsCoordFlags.MODULATION, cmd.flag());
        assertArrayEquals(new int[]{0x0A, 0x01, 0x02, 0x04}, cmd.params());
    }

    @Test
    void parseModOff() {
        var cmd = EffectMnemonics.parse("MOFF");
        assertEquals(SmpsCoordFlags.MOD_OFF, cmd.flag());
        assertEquals(0, cmd.params().length);
    }

    @Test
    void parseInvalidReturnsNull() {
        assertNull(EffectMnemonics.parse("INVALID"));
        assertNull(EffectMnemonics.parse(""));
        assertNull(EffectMnemonics.parse(null));
    }

    @Test
    void roundTripAllFlags() {
        // Every formattable flag should round-trip
        int[][] testCases = {
            {SmpsCoordFlags.PAN, 0xC0},
            {SmpsCoordFlags.VOLUME, 0x05},
            {SmpsCoordFlags.DETUNE, 0x03},
            {SmpsCoordFlags.NOTE_FILL, 0x80},
            {SmpsCoordFlags.KEY_DISP, 0x07},
            {SmpsCoordFlags.SET_TEMPO, 0x78},
            {SmpsCoordFlags.PSG_NOISE, 0x03},
        };
        for (int[] tc : testCases) {
            String formatted = EffectMnemonics.format(tc[0], new int[]{tc[1]});
            var parsed = EffectMnemonics.parse(formatted);
            assertNotNull(parsed, "Failed to parse: " + formatted);
            assertEquals(tc[0], parsed.flag(), "Flag mismatch for: " + formatted);
            assertEquals(tc[1], parsed.params()[0], "Param mismatch for: " + formatted);
        }
    }
}
