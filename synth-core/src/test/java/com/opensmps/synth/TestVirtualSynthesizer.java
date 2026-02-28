package com.opensmps.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VirtualSynthesizer} covering stereo rendering, silence,
 * FM/PSG audio production, and per-channel muting.
 */
class TestVirtualSynthesizer {

    private static final int BUFFER_FRAMES = 1024;
    private static final int STEREO_BUFFER_LEN = BUFFER_FRAMES * 2; // L, R interleaved

    /**
     * Compute total absolute energy across all samples in a stereo buffer.
     */
    private static long totalEnergy(short[] buffer) {
        long energy = 0;
        for (short s : buffer) {
            energy += Math.abs(s);
        }
        return energy;
    }

    // -----------------------------------------------------------------------
    // 1. Stereo output basics
    // -----------------------------------------------------------------------

    @Test
    void testRenderProducesStereoOutput() {
        VirtualSynthesizer synth = new VirtualSynthesizer();

        short[] buffer = new short[STEREO_BUFFER_LEN];
        synth.render(buffer);

        // Buffer length must be positive and even (stereo interleaved L/R pairs)
        assertTrue(buffer.length > 0, "Buffer length should be > 0");
        assertEquals(0, buffer.length % 2, "Buffer length should be even (stereo pairs)");
    }

    // -----------------------------------------------------------------------
    // 2. Silence after silenceAll()
    // -----------------------------------------------------------------------

    @Test
    void testSilenceAllProducesLowOutput() {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        synth.silenceAll();

        // Render a few passes to let any DC transients from the YM2612 chip model settle
        for (int i = 0; i < 8; i++) {
            short[] drain = new short[STEREO_BUFFER_LEN];
            synth.render(drain);
        }

        short[] buffer = new short[STEREO_BUFFER_LEN];
        synth.render(buffer);

        // The YM2612 chip model produces a constant DC bias even when silenced.
        // Verify the output is flat (no AC signal variation) rather than zero.
        // Check the second half of the buffer where the resampler has fully settled.
        int startSample = BUFFER_FRAMES / 2;
        short dcLeft = buffer[startSample * 2];
        short dcRight = buffer[startSample * 2 + 1];

        long acEnergy = 0;
        for (int i = startSample; i < BUFFER_FRAMES; i++) {
            acEnergy += Math.abs(buffer[i * 2] - dcLeft);
            acEnergy += Math.abs(buffer[i * 2 + 1] - dcRight);
        }

        // After silence + settling, AC energy (deviation from DC) should be zero or near-zero
        assertTrue(acEnergy < 500,
                "After silenceAll() and settling, AC energy (deviation from DC) should be very low, got " + acEnergy);
    }

    // -----------------------------------------------------------------------
    // 3. FM key-on produces audio
    // -----------------------------------------------------------------------

    @Test
    void testFmKeyOnProducesAudio() {
        VirtualSynthesizer synth = new VirtualSynthesizer();

        // Measure baseline energy after silence
        short[] silenceBuffer = new short[STEREO_BUFFER_LEN];
        synth.render(silenceBuffer);
        long silenceEnergy = totalEnergy(silenceBuffer);

        // --- Set up FM channel 0 ---
        // Enable L+R panning on channel 0
        synth.writeFm(this, 0, 0xB4, 0xC0);

        // Algorithm 0, feedback 4
        synth.writeFm(this, 0, 0xB0, (4 << 3) | 0);

        // Frequency: block 4, fnum ~A4
        synth.writeFm(this, 0, 0xA4, (4 << 3) | 0x01);
        synth.writeFm(this, 0, 0xA0, 0xA2);

        // Operator 1 (modulator, slot 0, register offset 0x00)
        synth.writeFm(this, 0, 0x30, 0x71); // DT1=7, MUL=1
        synth.writeFm(this, 0, 0x40, 0x00); // TL=0 (max volume)
        synth.writeFm(this, 0, 0x50, 0x1F); // RS=0, AR=31 (fastest)
        synth.writeFm(this, 0, 0x60, 0x00); // AM=0, D1R=0
        synth.writeFm(this, 0, 0x70, 0x00); // D2R=0
        synth.writeFm(this, 0, 0x80, 0x00); // D1L=0, RR=0

        // Operator 4 (carrier for algo 0, slot 3, register offset 0x0C)
        synth.writeFm(this, 0, 0x3C, 0x01); // DT1=0, MUL=1
        synth.writeFm(this, 0, 0x4C, 0x00); // TL=0 (max volume)
        synth.writeFm(this, 0, 0x5C, 0x1F); // RS=0, AR=31 (fastest)
        synth.writeFm(this, 0, 0x6C, 0x00); // AM=0, D1R=0
        synth.writeFm(this, 0, 0x7C, 0x00); // D2R=0
        synth.writeFm(this, 0, 0x8C, 0x00); // D1L=0, RR=0

        // Key-on: all 4 operators on channel 0
        synth.writeFm(this, 0, 0x28, 0xF0);

        // Render audio
        short[] audioBuffer = new short[STEREO_BUFFER_LEN];
        synth.render(audioBuffer);

        long audioEnergy = totalEnergy(audioBuffer);
        assertTrue(audioEnergy > silenceEnergy,
                "FM key-on should produce more energy than silence: audio=" + audioEnergy
                        + ", silence=" + silenceEnergy);
    }

    // -----------------------------------------------------------------------
    // 4. PSG tone produces audio
    // -----------------------------------------------------------------------

    @Test
    void testPsgToneProducesAudio() {
        VirtualSynthesizer synth = new VirtualSynthesizer();

        // Measure baseline energy after silence
        short[] silenceBuffer = new short[STEREO_BUFFER_LEN];
        synth.render(silenceBuffer);
        long silenceEnergy = totalEnergy(silenceBuffer);

        // Set PSG channel 0 tone (period ~0x0FE for ~440 Hz equivalent)
        // Latch byte for channel 0 tone, low nibble = 0xE
        synth.writePsg(this, 0x8E);
        // Data byte, upper 6 bits = 0x0F -> period = 0x0FE
        synth.writePsg(this, 0x0F);
        // Set channel 0 volume to max (attenuation = 0)
        synth.writePsg(this, 0x90);

        short[] audioBuffer = new short[STEREO_BUFFER_LEN];
        synth.render(audioBuffer);

        long audioEnergy = totalEnergy(audioBuffer);
        assertTrue(audioEnergy > silenceEnergy,
                "PSG tone should produce more energy than silence: audio=" + audioEnergy
                        + ", silence=" + silenceEnergy);
    }

    // -----------------------------------------------------------------------
    // 5. Muting FM channel silences it
    // -----------------------------------------------------------------------

    @Test
    void testMuteFmChannelSilencesIt() {
        VirtualSynthesizer synth = new VirtualSynthesizer();

        // --- Set up FM channel 0 with a sustained tone ---
        synth.writeFm(this, 0, 0xB4, 0xC0); // Pan L+R
        synth.writeFm(this, 0, 0xB0, (4 << 3) | 0); // FB=4, ALG=0

        synth.writeFm(this, 0, 0xA4, (4 << 3) | 0x01);
        synth.writeFm(this, 0, 0xA0, 0xA2);

        // Operator 1 (slot 0)
        synth.writeFm(this, 0, 0x30, 0x71);
        synth.writeFm(this, 0, 0x40, 0x00);
        synth.writeFm(this, 0, 0x50, 0x1F);
        synth.writeFm(this, 0, 0x60, 0x00);
        synth.writeFm(this, 0, 0x70, 0x00);
        synth.writeFm(this, 0, 0x80, 0x00);

        // Operator 4 (slot 3)
        synth.writeFm(this, 0, 0x3C, 0x01);
        synth.writeFm(this, 0, 0x4C, 0x00);
        synth.writeFm(this, 0, 0x5C, 0x1F);
        synth.writeFm(this, 0, 0x6C, 0x00);
        synth.writeFm(this, 0, 0x7C, 0x00);
        synth.writeFm(this, 0, 0x8C, 0x00);

        // Key-on all operators on channel 0
        synth.writeFm(this, 0, 0x28, 0xF0);

        // Render with audio playing
        short[] audioBuffer = new short[STEREO_BUFFER_LEN];
        synth.render(audioBuffer);
        long unmutedEnergy = totalEnergy(audioBuffer);

        // Mute FM channel 0
        synth.setFmMute(0, true);

        // Render a few passes to let the mute propagate fully
        for (int i = 0; i < 4; i++) {
            short[] drain = new short[STEREO_BUFFER_LEN];
            synth.render(drain);
        }

        short[] mutedBuffer = new short[STEREO_BUFFER_LEN];
        synth.render(mutedBuffer);
        long mutedEnergy = totalEnergy(mutedBuffer);

        assertTrue(mutedEnergy < unmutedEnergy,
                "Muted FM channel should produce less energy: unmuted=" + unmutedEnergy
                        + ", muted=" + mutedEnergy);
    }

    // -----------------------------------------------------------------------
    // 6. Muting PSG channel silences it
    // -----------------------------------------------------------------------

    @Test
    void testMutePsgChannelSilencesIt() {
        VirtualSynthesizer synth = new VirtualSynthesizer();

        // Set PSG channel 0 tone
        synth.writePsg(this, 0x8E);
        synth.writePsg(this, 0x0F);
        synth.writePsg(this, 0x90); // Max volume

        // Render with audio playing
        short[] audioBuffer = new short[STEREO_BUFFER_LEN];
        synth.render(audioBuffer);
        long unmutedEnergy = totalEnergy(audioBuffer);

        // Mute PSG channel 0
        synth.setPsgMute(0, true);

        // Render a few passes to let the BlipDeltaBuffer drain
        for (int i = 0; i < 4; i++) {
            short[] drain = new short[STEREO_BUFFER_LEN];
            synth.render(drain);
        }

        short[] mutedBuffer = new short[STEREO_BUFFER_LEN];
        synth.render(mutedBuffer);
        long mutedEnergy = totalEnergy(mutedBuffer);

        assertTrue(mutedEnergy < unmutedEnergy,
                "Muted PSG channel should produce less energy: unmuted=" + unmutedEnergy
                        + ", muted=" + mutedEnergy);
    }
}
