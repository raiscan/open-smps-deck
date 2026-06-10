package com.opensmpsdeck.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FmVoice accessors must use the real SMPS 25-byte voice layout — the one the
 * YM2612 chip layer (Ym2612Chip.setInstrument) and the sequencer read:
 *
 * <pre>
 * byte 0      : feedback/algorithm
 * bytes 1-4   : DT/MUL   (operators in musical order OP1, OP2, OP3, OP4)
 * bytes 5-8   : RS/AR
 * bytes 9-12  : AM/D1R
 * bytes 13-16 : D2R
 * bytes 17-20 : D1L/RR
 * bytes 21-24 : TL
 * </pre>
 *
 * Regression test: the accessors previously assumed an operator-grouped layout
 * (6 consecutive bytes per operator), so the voice editor edited bytes the
 * synth never read and displayed garbage for imported voices.
 */
class TestFmVoiceSmpsLayout {

    @Test
    void gettersReadParameterGroupedLayout() {
        byte[] data = new byte[FmVoice.VOICE_SIZE];
        data[0] = (byte) ((3 << 3) | 5);   // feedback 3, algorithm 5
        data[1] = 0x71;                     // DT/MUL slot 0
        data[5] = (byte) 0xDF;              // RS/AR slot 0: RS=3, AR=31
        data[9] = (byte) 0x8A;              // AM/D1R slot 0: AM set, D1R=10
        data[13] = 0x0C;                    // D2R slot 0
        data[17] = (byte) 0xF7;             // D1L/RR slot 0: D1L=15, RR=7
        data[21] = 0x28;                    // TL slot 0

        FmVoice voice = new FmVoice("Test", data);

        assertEquals(5, voice.getAlgorithm());
        assertEquals(3, voice.getFeedback());
        assertEquals(7, voice.getDt(0));
        assertEquals(1, voice.getMul(0));
        assertEquals(3, voice.getRs(0));
        assertEquals(31, voice.getAr(0));
        assertTrue(voice.getAm(0));
        assertEquals(10, voice.getD1r(0));
        assertEquals(12, voice.getD2r(0));
        assertEquals(15, voice.getD1l(0));
        assertEquals(7, voice.getRr(0));
        assertEquals(0x28, voice.getTl(0));
    }

    @Test
    void perOperatorValuesMapToTheRightBytes() {
        // Within each group the file stores operators in straight musical order
        // OP1, OP2, OP3, OP4. (SMPSPlay's INSOPS_DEFAULT maps file bytes to
        // registers 30, 38, 34, 3C — the swap is on the register side, where
        // +0/+8/+4/+C are OP1/OP2/OP3/OP4 musically.)
        byte[] data = new byte[FmVoice.VOICE_SIZE];
        data[21] = 0x10; // TL OP1
        data[22] = 0x20; // TL OP2
        data[23] = 0x30; // TL OP3
        data[24] = 0x40; // TL OP4
        FmVoice voice = new FmVoice("Test", data);

        assertEquals(0x10, voice.getTl(0));
        assertEquals(0x20, voice.getTl(1));
        assertEquals(0x30, voice.getTl(2));
        assertEquals(0x40, voice.getTl(3));
    }

    @Test
    void displayOrderIsMusicalOrder() {
        // The editor's OP2 column must read the file's OP2 byte: the display
        // index passes straight through to the accessors
        byte[] data = new byte[FmVoice.VOICE_SIZE];
        data[22] = 0x20; // TL OP2
        data[23] = 0x30; // TL OP3
        FmVoice voice = new FmVoice("Test", data);

        assertEquals(0x20, voice.getTl(FmVoice.displayToSmps(1)),
                "Display OP2 must read the file's OP2 byte (22)");
        assertEquals(0x30, voice.getTl(FmVoice.displayToSmps(2)),
                "Display OP3 must read the file's OP3 byte (23)");
    }

    @Test
    void carrierTableUsesMusicalOperatorOrder() {
        FmVoice voice = new FmVoice("Test", new byte[FmVoice.VOICE_SIZE]);
        // Algorithm 4: OP1→OP2 and OP3→OP4 pairs; carriers are OP2 and OP4
        voice.setAlgorithm(4);
        assertFalse(voice.isCarrier(0), "Algo 4: OP1 is a modulator");
        assertTrue(voice.isCarrier(1), "Algo 4: OP2 is a carrier");
        assertFalse(voice.isCarrier(2), "Algo 4: OP3 is a modulator");
        assertTrue(voice.isCarrier(3), "Algo 4: OP4 is a carrier");

        // Algorithm 0: serial chain, only OP4 carries
        voice.setAlgorithm(0);
        assertFalse(voice.isCarrier(0));
        assertTrue(voice.isCarrier(3));
    }

    @Test
    void settersWriteParameterGroupedLayout() {
        FmVoice voice = new FmVoice("Test", new byte[FmVoice.VOICE_SIZE]);
        voice.setTl(0, 0x55);
        voice.setTl(3, 0x66);
        voice.setAr(1, 31);
        voice.setMul(2, 4);

        byte[] data = voice.getData();
        assertEquals(0x55, data[21] & 0xFF, "TL slot 0 lives at byte 21");
        assertEquals(0x66, data[24] & 0xFF, "TL slot 3 lives at byte 24");
        assertEquals(31, data[6] & 0xFF, "RS/AR slot 1 lives at byte 6");
        assertEquals(4, data[3] & 0xFF, "DT/MUL slot 2 lives at byte 3");
    }
}
