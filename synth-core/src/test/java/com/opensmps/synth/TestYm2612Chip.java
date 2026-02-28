package com.opensmps.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the extracted YM2612 chip emulator.
 */
class TestYm2612Chip {

    @Test
    void chipInitializesAndProducesSilence() {
        Ym2612Chip chip = new Ym2612Chip();

        int[] left = new int[256];
        int[] right = new int[256];
        chip.renderStereo(left, right);

        // With no key-on, all samples should be a constant DC value (discrete
        // chip model adds a fixed DC bias). Verify output is flat (no variation).
        int dcLevel = left[left.length / 2]; // Use mid-point after resampler settles
        for (int i = left.length / 2; i < left.length; i++) {
            assertEquals(dcLevel, left[i], "Left sample " + i + " should be constant DC");
            assertEquals(dcLevel, right[i], "Right sample " + i + " should be constant DC");
        }
    }

    @Test
    void keyOnProducesNonZeroOutput() {
        Ym2612Chip chip = new Ym2612Chip();

        // Set up a simple instrument on channel 0 with a basic voice
        // Enable both L/R panning
        chip.write(0, 0xB4, 0xC0); // Pan L+R

        // Set algorithm 0, feedback 4
        chip.write(0, 0xB0, (4 << 3) | 0);

        // Set frequency (A4 ~440Hz: block=4, fnum=0x1A2)
        chip.write(0, 0xA4, (4 << 3) | 0x01); // Block 4, fnum high bits
        chip.write(0, 0xA0, 0xA2);             // Fnum low bits

        // Set up operator 1 (slot 0) with fast attack, moderate decay
        chip.write(0, 0x30, 0x71); // DT1=7, MUL=1
        chip.write(0, 0x40, 0x00); // TL=0 (max volume)
        chip.write(0, 0x50, 0x1F); // RS=0, AR=31 (fastest attack)
        chip.write(0, 0x60, 0x00); // AM=0, D1R=0
        chip.write(0, 0x70, 0x00); // D2R=0
        chip.write(0, 0x80, 0x00); // D1L=0, RR=0

        // Set up operator 4 (slot 3) - carrier for algo 0
        chip.write(0, 0x3C, 0x01); // DT1=0, MUL=1
        chip.write(0, 0x4C, 0x00); // TL=0 (max volume)
        chip.write(0, 0x5C, 0x1F); // RS=0, AR=31 (fastest attack)
        chip.write(0, 0x6C, 0x00); // AM=0, D1R=0
        chip.write(0, 0x7C, 0x00); // D2R=0
        chip.write(0, 0x8C, 0x00); // D1L=0, RR=0

        // Key on: all 4 operators on channel 0
        chip.write(0, 0x28, 0xF0);

        // Render several frames to let envelope develop
        int[] left = new int[1024];
        int[] right = new int[1024];
        chip.renderStereo(left, right);

        // Check that at least some samples are non-zero
        boolean hasNonZero = false;
        for (int i = 0; i < left.length; i++) {
            if (left[i] != 0 || right[i] != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Key-on should produce non-zero audio output");
    }

    @Test
    void setInstrumentAndKeyOn() {
        Ym2612Chip chip = new Ym2612Chip();

        // Build a 25-byte SMPS voice (algo=4, fb=5, 4 operators)
        byte[] voice = new byte[25];
        voice[0] = (byte) ((5 << 3) | 4); // FB=5, ALG=4

        // DT/MUL for ops 1,3,2,4 (SMPS order)
        voice[1] = 0x71; voice[2] = 0x31; voice[3] = 0x32; voice[4] = 0x01;
        // RS/AR
        voice[5] = 0x1F; voice[6] = 0x1F; voice[7] = 0x1F; voice[8] = 0x1F;
        // AM/D1R
        voice[9] = 0x05; voice[10] = 0x05; voice[11] = 0x05; voice[12] = 0x05;
        // D2R
        voice[13] = 0x02; voice[14] = 0x02; voice[15] = 0x02; voice[16] = 0x02;
        // D1L/RR
        voice[17] = 0x11; voice[18] = 0x11; voice[19] = 0x11; voice[20] = 0x11;
        // TL for ops 1,3,2,4 (SMPS order)
        voice[21] = 0x20; voice[22] = 0x28; voice[23] = 0x18; voice[24] = 0x00;

        chip.setInstrument(0, voice);

        // Enable panning
        chip.write(0, 0xB4, 0xC0);

        // Set frequency
        chip.write(0, 0xA4, (4 << 3) | 0x01);
        chip.write(0, 0xA0, 0xA2);

        // Key on all operators
        chip.write(0, 0x28, 0xF0);

        int[] left = new int[1024];
        int[] right = new int[1024];
        chip.renderStereo(left, right);

        boolean hasNonZero = false;
        for (int i = 0; i < left.length; i++) {
            if (left[i] != 0 || right[i] != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "setInstrument + key-on should produce audio");
    }

    @Test
    void resetProducesSilence() {
        Ym2612Chip chip = new Ym2612Chip();

        // Set up and key on
        chip.write(0, 0xB4, 0xC0);
        chip.write(0, 0xB0, 0x04);
        chip.write(0, 0xA4, 0x22);
        chip.write(0, 0xA0, 0xA2);
        chip.write(0, 0x40, 0x00);
        chip.write(0, 0x4C, 0x00);
        chip.write(0, 0x50, 0x1F);
        chip.write(0, 0x5C, 0x1F);
        chip.write(0, 0x28, 0xF0);

        int[] left = new int[256];
        int[] right = new int[256];
        chip.renderStereo(left, right);

        // Reset should return to constant DC (no signal variation)
        chip.reset();
        left = new int[256];
        right = new int[256];
        chip.renderStereo(left, right);

        // After reset, output should be flat DC (discrete chip adds constant bias)
        int dcLevel = left[left.length / 2];
        for (int i = left.length / 2; i < left.length; i++) {
            assertEquals(dcLevel, left[i], "After reset, left sample " + i + " should be constant DC");
            assertEquals(dcLevel, right[i], "After reset, right sample " + i + " should be constant DC");
        }
    }
}
