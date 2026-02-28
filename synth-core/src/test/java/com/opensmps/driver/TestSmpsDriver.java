package com.opensmps.driver;

import com.opensmps.smps.*;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class TestSmpsDriver {

    @Test
    void getTrackPositionDelegatesToMusicSequencer() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        SmpsSequencer seq = buildMinimalSequencer(driver);
        driver.addSequencer(seq, false);

        int pos = driver.getTrackPosition(SmpsSequencer.TrackType.DAC, 5);
        assertEquals(0x23, pos, "Delegation to music sequencer should return track start position 0x23");
    }

    @Test
    void getTrackPositionReturnsNegativeWhenNoSequencer() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        assertEquals(-1, driver.getTrackPosition(SmpsSequencer.TrackType.FM, 0),
                "No sequencer loaded — should return -1");
    }

    /**
     * Builds a minimal SMPS sequencer with 1 FM channel mapped to DAC channel 5.
     * Track data starts at offset 0x23: note (0x90), duration (0x10), stop (0xF2).
     * Uses the same binary layout as TestSmpsSequencer.
     */
    private SmpsSequencer buildMinimalSequencer(SmpsDriver driver) {
        byte[] smps = new byte[0x26];

        smps[0] = 0x0A; smps[1] = 0x00; // voice ptr
        smps[2] = 1; smps[3] = 0;        // 1 FM, 0 PSG
        smps[4] = 1; smps[5] = (byte) 0xC0; // timing, tempo

        smps[6] = 0x23; smps[7] = 0x00;  // track ptr
        smps[8] = 0x00; smps[9] = 0x00;  // transpose, volume

        // Minimal voice at offset 0x0A (25 bytes)
        smps[0x0A] = 0x00;
        for (int i = 0x0B; i <= 0x0E; i++) smps[i] = 0x01; // DT/MUL
        for (int i = 0x0F; i <= 0x12; i++) smps[i] = 0x1F; // RS/AR
        for (int i = 0x13; i <= 0x1E; i++) smps[i] = 0x00; // D1R, D2R, SL/RR
        smps[0x1F] = 0x00; smps[0x20] = 0x00; smps[0x21] = 0x00; smps[0x22] = 0x00; // TL

        smps[0x23] = (byte) 0x90; // note
        smps[0x24] = 0x10;        // duration
        smps[0x25] = (byte) 0xF2; // stop

        StubSmpsData data = new StubSmpsData(smps, 0);
        data.setId(1);

        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .speedUpTempos(Collections.emptyMap())
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .build();

        DacData dacData = new DacData(Collections.emptyMap(), Collections.emptyMap());
        return new SmpsSequencer(data, dacData, driver, config);
    }
}
