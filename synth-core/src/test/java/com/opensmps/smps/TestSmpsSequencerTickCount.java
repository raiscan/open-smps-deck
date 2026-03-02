package com.opensmps.smps;

import com.opensmps.driver.SmpsDriver;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the totalTicksElapsed counter on SmpsSequencer, exposed via SmpsDriver.getTickCount().
 */
class TestSmpsSequencerTickCount {

    @Test
    void tickCountStartsAtZero() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        assertEquals(0, driver.getTickCount(),
                "Tick count should be 0 when no sequencer is loaded");
    }

    @Test
    void tickCountStartsAtZeroAfterLoad() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        SmpsSequencer seq = createMinimalSequencer(driver);
        driver.addSequencer(seq, false);

        assertEquals(0, seq.getTotalTicksElapsed(),
                "Tick count should be 0 immediately after loading a sequencer");
        assertEquals(0, driver.getTickCount(),
                "Driver tick count should be 0 immediately after loading a sequencer");
    }

    @Test
    void tickCountIncreasesAfterRendering() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        SmpsSequencer seq = createMinimalSequencer(driver);
        driver.addSequencer(seq, false);

        // Render ~500ms of audio — enough for tempo system to fire multiple ticks
        short[] buffer = new short[44100]; // 0.5 seconds stereo
        driver.read(buffer);

        assertTrue(driver.getTickCount() > 0,
                "Tick count should be > 0 after rendering audio");
        assertTrue(seq.getTotalTicksElapsed() > 0,
                "Sequencer tick count should be > 0 after rendering audio");
    }

    @Test
    void tickCountMatchesBetweenDriverAndSequencer() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        SmpsSequencer seq = createMinimalSequencer(driver);
        driver.addSequencer(seq, false);

        // Render a small amount of audio
        short[] buffer = new short[8820]; // ~100ms stereo
        driver.read(buffer);

        assertEquals(seq.getTotalTicksElapsed(), driver.getTickCount(),
                "Driver.getTickCount() should match sequencer.getTotalTicksElapsed()");
    }

    @Test
    void sfxSequencerIsIgnoredByDriverGetTickCount() {
        SmpsDriver driver = new SmpsDriver(44100.0);

        // Add a music sequencer
        SmpsSequencer musicSeq = createMinimalSequencer(driver);
        driver.addSequencer(musicSeq, false);

        // Add an SFX sequencer
        SmpsSequencer sfxSeq = createMinimalSequencer(driver);
        driver.addSequencer(sfxSeq, true);

        // Render some audio
        short[] buffer = new short[8820];
        driver.read(buffer);

        // Driver should report the music sequencer's tick count, not the SFX one
        assertEquals(musicSeq.getTotalTicksElapsed(), driver.getTickCount(),
                "Driver.getTickCount() should return the music sequencer's tick count, not SFX");
    }

    /**
     * Creates a minimal SMPS sequencer with 1 FM channel playing a note then stopping.
     * Based on the pattern in TestSmpsSequencer.
     */
    private SmpsSequencer createMinimalSequencer(SmpsDriver driver) {
        byte[] smps = new byte[0x26];

        // Voice pointer at offset 0x0A
        smps[0] = 0x0A; smps[1] = 0x00;
        // 1 FM channel, 0 PSG channels
        smps[2] = 1; smps[3] = 0;
        // Dividing timing = 1, Tempo = 0xC0
        smps[4] = 1; smps[5] = (byte) 0xC0;

        // FM track 0: pointer = 0x23, transpose = 0, volume = 0
        smps[6] = 0x23; smps[7] = 0x00;
        smps[8] = 0x00; smps[9] = 0x00;

        // Voice 0 at offset 0x0A (25 bytes)
        smps[0x0A] = (byte) ((4 << 3) | 4); // FB=4, Algo=4
        for (int i = 0x0B; i <= 0x0E; i++) smps[i] = 0x01; // DT/MUL
        for (int i = 0x0F; i <= 0x12; i++) smps[i] = 0x1F; // RS/AR
        for (int i = 0x13; i <= 0x16; i++) smps[i] = 0x00; // AM/D1R
        for (int i = 0x17; i <= 0x1A; i++) smps[i] = 0x00; // D2R
        for (int i = 0x1B; i <= 0x1E; i++) smps[i] = (byte) 0xFF; // SL/RR
        smps[0x1F] = 0x10; smps[0x20] = 0x10; smps[0x21] = 0x10; smps[0x22] = 0x00; // TL

        // Track data: note 0x90, duration 0x30 (48 ticks), stop
        smps[0x23] = (byte) 0x90;
        smps[0x24] = 0x30;
        smps[0x25] = (byte) 0xF2;

        StubSmpsData data = new StubSmpsData(smps, 0);
        data.setId(1);

        SmpsSequencerConfig config = new SmpsSequencerConfig(
                Collections.emptyMap(), 0x100,
                new int[]{ 0x16, 0, 1, 2, 4, 5, 6 },
                new int[]{ 0x80, 0xA0, 0xC0 },
                SmpsSequencerConfig.TempoMode.OVERFLOW2,
                null
        );

        DacData dacData = new DacData(Collections.emptyMap(), Collections.emptyMap());
        return new SmpsSequencer(data, dacData, driver, config);
    }
}
