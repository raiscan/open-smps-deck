package com.opensmps.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the extracted PSG (SN76489) chip emulator.
 */
class TestPsgChipGPGX {

    @Test
    void chipInitializesWithoutError() {
        PsgChipGPGX psg = new PsgChipGPGX();
        assertNotNull(psg);
    }

    @Test
    void silencedChipProducesZeroOutput() {
        PsgChipGPGX psg = new PsgChipGPGX();
        // Silence all channels
        psg.silenceAll();

        int[] left = new int[256];
        int[] right = new int[256];
        psg.renderStereo(left, right);

        // All output should be zero (or effectively zero after filter settling)
        long totalEnergy = 0;
        for (int i = 0; i < left.length; i++) {
            totalEnergy += Math.abs(left[i]) + Math.abs(right[i]);
        }
        // Allow a small margin for DC filter settling
        assertTrue(totalEnergy < 100, "Silenced PSG should produce near-zero output, got energy=" + totalEnergy);
    }

    @Test
    void toneFrequencyAndVolumeProducesAudio() {
        PsgChipGPGX psg = new PsgChipGPGX();

        // Set channel 0 tone frequency (period = 0x0FE ~440Hz equivalent)
        // Latch byte: 1 | (ch<<5) | (type<<4) | (data & 0xF)
        // Channel 0 tone: 0x80 | low nibble
        psg.write(0x8E); // Channel 0 tone, low 4 bits = 0xE
        psg.write(0x0F); // Data byte, upper 6 bits = 0x0F -> period = 0x0FE

        // Set channel 0 volume to max (attenuation = 0)
        psg.write(0x90); // Channel 0 volume = 0 (loudest)

        int[] left = new int[512];
        int[] right = new int[512];
        psg.renderStereo(left, right);

        // Check that at least some samples are non-zero
        boolean hasNonZero = false;
        for (int i = 0; i < left.length; i++) {
            if (left[i] != 0 || right[i] != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "PSG tone with volume should produce non-zero audio output");
    }

    @Test
    void resetReturnsToCalmState() {
        PsgChipGPGX psg = new PsgChipGPGX();

        // Generate some audio
        psg.write(0x8E);
        psg.write(0x0F);
        psg.write(0x90);

        int[] left = new int[256];
        int[] right = new int[256];
        psg.renderStereo(left, right);

        // Reset then silence all channels
        psg.reset();
        psg.silenceAll();

        left = new int[256];
        right = new int[256];
        psg.renderStereo(left, right);

        // After reset + silenceAll, all volumes are at max attenuation.
        // Output should be zero or near-zero.
        long totalEnergy = 0;
        for (int i = 0; i < left.length; i++) {
            totalEnergy += Math.abs(left[i]) + Math.abs(right[i]);
        }
        assertTrue(totalEnergy < 100, "After reset + silenceAll, PSG should be near-silent, got energy=" + totalEnergy);
    }

    @Test
    void muteChannelSilencesIt() {
        PsgChipGPGX psg = new PsgChipGPGX();
        // Silence all channels first, then set up only channel 0
        psg.silenceAll();

        // Set channel 0 tone
        psg.write(0x8E);
        psg.write(0x0F);
        psg.write(0x90); // Volume max (attenuation 0)

        // Mute all 4 channels
        for (int ch = 0; ch < 4; ch++) {
            psg.setMute(ch, true);
        }

        // Render several frames of silence to let delta buffer drain
        for (int frame = 0; frame < 4; frame++) {
            int[] left = new int[256];
            int[] right = new int[256];
            psg.renderStereo(left, right);
        }

        // Final render should be near-silent
        int[] left = new int[256];
        int[] right = new int[256];
        psg.renderStereo(left, right);

        long totalEnergy = 0;
        for (int i = 0; i < left.length; i++) {
            totalEnergy += Math.abs(left[i]) + Math.abs(right[i]);
        }
        assertTrue(totalEnergy < 500, "All channels muted should produce near-zero output, got energy=" + totalEnergy);
    }
}
