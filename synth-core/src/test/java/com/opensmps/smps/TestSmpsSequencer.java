package com.opensmps.smps;

import com.opensmps.driver.SmpsDriver;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test: build a minimal SMPS binary by hand, create a sequencer, and
 * verify that the driver produces non-zero audio output.
 */
class TestSmpsSequencer {

    @Test
    void driverProducesNonZeroOutput() {
        // Build a minimal SMPS binary:
        // Layout:
        //   [0x00] Header (6 bytes): voicePtr(LE16), fmChannels, psgChannels, dividingTiming, tempo
        //   [0x06] FM track 0 entry (4 bytes): pointer(LE16), transpose, volume
        //   [0x0A] Voice 0 data (25 bytes): algo/fb + DT/MUL*4 + TL*4 + RS/AR*4 + AM/D1R*4 + D2R*4 + SL/RR*4
        //   [0x23] Track data: note, duration, F2(stop)
        //
        // Total size = 0x26 (38 bytes)

        byte[] smps = new byte[0x26];

        // Voice pointer: offset 0x0A (where voice data starts)
        smps[0] = 0x0A;
        smps[1] = 0x00;

        // 1 FM channel, 0 PSG channels
        smps[2] = 1;
        smps[3] = 0;

        // Dividing timing = 1, Tempo = 0xC0 (typical S2 tempo)
        smps[4] = 1;
        smps[5] = (byte) 0xC0;

        // FM track 0: pointer = 0x23 (track data offset), transpose = 0, volume = 0
        smps[6] = 0x23;
        smps[7] = 0x00;
        smps[8] = 0x00; // transpose
        smps[9] = 0x00; // volume

        // Voice 0 at offset 0x0A (25 bytes)
        // Byte 0: Algorithm/Feedback. Algo=4 (two carriers), FB=4
        smps[0x0A] = (byte) ((4 << 3) | 4); // FB=4, Algo=4

        // Bytes 1-4: DT/MUL for operators 1-4
        smps[0x0B] = 0x01; // Op1: DT=0, MUL=1
        smps[0x0C] = 0x01; // Op2: DT=0, MUL=1
        smps[0x0D] = 0x01; // Op3: DT=0, MUL=1
        smps[0x0E] = 0x01; // Op4: DT=0, MUL=1

        // Bytes 5-8: RS/AR for operators 1-4
        smps[0x0F] = 0x1F; // Op1: RS=0, AR=31 (fast attack)
        smps[0x10] = 0x1F; // Op2
        smps[0x11] = 0x1F; // Op3
        smps[0x12] = 0x1F; // Op4

        // Bytes 9-12: AM/D1R for operators 1-4
        smps[0x13] = 0x00; // Op1: no AM, D1R=0
        smps[0x14] = 0x00; // Op2
        smps[0x15] = 0x00; // Op3
        smps[0x16] = 0x00; // Op4

        // Bytes 13-16: D2R for operators 1-4
        smps[0x17] = 0x00; // Op1
        smps[0x18] = 0x00; // Op2
        smps[0x19] = 0x00; // Op3
        smps[0x1A] = 0x00; // Op4

        // Bytes 17-20: SL/RR for operators 1-4
        smps[0x1B] = (byte) 0xFF; // Op1: SL=15, RR=15
        smps[0x1C] = (byte) 0xFF; // Op2
        smps[0x1D] = (byte) 0xFF; // Op3
        smps[0x1E] = (byte) 0xFF; // Op4

        // Bytes 21-24: TL for operators 1-4
        smps[0x1F] = 0x10; // Op1 TL
        smps[0x20] = 0x10; // Op2 TL
        smps[0x21] = 0x10; // Op3 TL
        smps[0x22] = 0x00; // Op4 TL (carrier, louder)

        // Track data at offset 0x23:
        // Note 0x90 (= note index 0x10 - 0x81 = note 15 in table), duration 0x30
        smps[0x23] = (byte) 0x90; // note
        smps[0x24] = 0x30;        // duration (48 ticks)
        smps[0x25] = (byte) 0xF2; // F2 = stop track

        // Build SMPS data and config
        StubSmpsData data = new StubSmpsData(smps, 0);
        data.setId(1);

        // S2-style config
        SmpsSequencerConfig config = new SmpsSequencerConfig(
                Collections.emptyMap(),
                0x100,
                new int[]{ 0x16, 0, 1, 2, 4, 5, 6 },
                new int[]{ 0x80, 0xA0, 0xC0 },
                SmpsSequencerConfig.TempoMode.OVERFLOW2,
                null
        );

        // Create driver and sequencer
        SmpsDriver driver = new SmpsDriver(44100.0);
        DacData dacData = new DacData(Collections.emptyMap(), Collections.emptyMap());
        SmpsSequencer sequencer = new SmpsSequencer(data, dacData, driver, config);
        driver.addSequencer(sequencer, false);

        // Render ~100ms of audio (4410 stereo samples = 8820 shorts)
        short[] buffer = new short[8820];
        int read = driver.read(buffer);

        assertEquals(buffer.length, read, "read() should return full buffer length");

        // Check that at least some samples are non-zero (FM synthesis is producing sound)
        boolean hasNonZero = false;
        for (short s : buffer) {
            if (s != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Driver output should contain non-zero samples from FM synthesis");

        // After enough rendering, the sequencer should complete (track plays 48 ticks then stops)
        // Render more frames to let it finish
        short[] extraBuffer = new short[44100 * 2]; // 1 second
        driver.read(extraBuffer);

        assertTrue(driver.isComplete(), "Driver should report complete after track finishes");
    }

    @Test
    void sequencerStandaloneRead() {
        // Test SmpsSequencer.read() directly (without SmpsDriver)
        byte[] smps = new byte[0x26];

        smps[0] = 0x0A; smps[1] = 0x00; // voice ptr
        smps[2] = 1; smps[3] = 0;        // 1 FM, 0 PSG
        smps[4] = 1; smps[5] = (byte) 0xC0; // timing, tempo

        smps[6] = 0x23; smps[7] = 0x00;  // track ptr
        smps[8] = 0x00; smps[9] = 0x00;  // transpose, volume

        // Minimal voice (algo=0, all ops with basic settings)
        smps[0x0A] = 0x00;
        for (int i = 0x0B; i <= 0x0E; i++) smps[i] = 0x01; // DT/MUL
        for (int i = 0x0F; i <= 0x12; i++) smps[i] = 0x1F; // RS/AR
        for (int i = 0x13; i <= 0x1E; i++) smps[i] = 0x00; // D1R, D2R, SL/RR
        smps[0x1F] = 0x00; smps[0x20] = 0x00; smps[0x21] = 0x00; smps[0x22] = 0x00; // TL

        smps[0x23] = (byte) 0x90; // note
        smps[0x24] = 0x10;        // duration
        smps[0x25] = (byte) 0xF2; // stop

        StubSmpsData data = new StubSmpsData(smps, 0);
        SmpsSequencerConfig config = new SmpsSequencerConfig(
                Collections.emptyMap(), 0x100,
                new int[]{ 0x16, 0, 1, 2, 4, 5, 6 },
                new int[]{ 0x80, 0xA0, 0xC0 }
        );

        DacData dacData = new DacData(Collections.emptyMap(), Collections.emptyMap());
        SmpsSequencer seq = new SmpsSequencer(data, dacData, config);

        assertFalse(seq.isComplete(), "Sequencer should not be complete initially");

        // Read some audio samples
        short[] buf = new short[4410]; // ~50ms mono
        int read = seq.read(buf);
        assertEquals(buf.length, read);
    }

    /**
     * Creates a minimal SMPS sequencer with 1 FM channel mapped to DAC channel 5.
     * Track data starts at offset 0x23: note (0x90), duration (0x10), stop (0xF2).
     */
    private SmpsSequencer createSingleDacTrackSequencer() {
        byte[] smps = new byte[0x26];

        smps[0] = 0x0A; smps[1] = 0x00; // voice ptr
        smps[2] = 1; smps[3] = 0;        // 1 FM, 0 PSG
        smps[4] = 1; smps[5] = (byte) 0xC0; // timing, tempo

        smps[6] = 0x23; smps[7] = 0x00;  // track ptr
        smps[8] = 0x00; smps[9] = 0x00;  // transpose, volume

        // Minimal voice
        smps[0x0A] = 0x00;
        for (int i = 0x0B; i <= 0x0E; i++) smps[i] = 0x01;
        for (int i = 0x0F; i <= 0x12; i++) smps[i] = 0x1F;
        for (int i = 0x13; i <= 0x1E; i++) smps[i] = 0x00;
        smps[0x1F] = 0x00; smps[0x20] = 0x00; smps[0x21] = 0x00; smps[0x22] = 0x00;

        smps[0x23] = (byte) 0x90; // note
        smps[0x24] = 0x10;        // duration
        smps[0x25] = (byte) 0xF2; // stop

        StubSmpsData data = new StubSmpsData(smps, 0);
        SmpsSequencerConfig config = new SmpsSequencerConfig(
                Collections.emptyMap(), 0x100,
                new int[]{ 0x16, 0, 1, 2, 4, 5, 6 },
                new int[]{ 0x80, 0xA0, 0xC0 }
        );

        DacData dacData = new DacData(Collections.emptyMap(), Collections.emptyMap());
        return new SmpsSequencer(data, dacData, config);
    }

    @Test
    void getTrackPositionReturnsByteOffset() {
        SmpsSequencer seq = createSingleDacTrackSequencer();

        // Before any playback, the DAC track position should be at 0x23
        assertEquals(0x23, seq.getTrackPosition(SmpsSequencer.TrackType.DAC, 5),
                "DAC track position should start at 0x23");

        // Advance playback so the sequencer reads the note+duration bytes
        short[] buf = new short[4410];
        seq.read(buf);

        // After consuming note byte (0x23) and duration byte (0x24), position should be exactly 0x25
        assertEquals(0x25, seq.getTrackPosition(SmpsSequencer.TrackType.DAC, 5),
                "DAC track position should be 0x25 after reading note and duration bytes");
    }

    @Test
    void getTrackPositionReturnsNegativeForMissingChannel() {
        SmpsSequencer seq = createSingleDacTrackSequencer();

        // Query FM channel 0 which doesn't exist in this song (only DAC ch5 exists)
        assertEquals(-1, seq.getTrackPosition(SmpsSequencer.TrackType.FM, 0),
                "Should return -1 for a channel not present in the song");

        // Query PSG channel 0 which also doesn't exist
        assertEquals(-1, seq.getTrackPosition(SmpsSequencer.TrackType.PSG, 0),
                "Should return -1 for a PSG channel not present in the song");
    }
}
