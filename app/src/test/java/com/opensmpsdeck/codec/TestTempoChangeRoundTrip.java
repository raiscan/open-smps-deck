package com.opensmpsdeck.codec;

import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmpsdeck.model.*;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.DacData;
import com.opensmps.smps.SmpsCoordFlags;
import com.opensmps.smps.SmpsSequencer;
import com.opensmps.smps.SmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test verifying that an inline tempo change (0xEA) embedded
 * in phrase bytecode survives the full compile -> playback pipeline.
 *
 * <p>The test creates a Song with an EA 60 (set tempo to 0x60) command
 * in its FM channel 0 phrase, compiles it via PatternCompiler, then
 * plays it back through SmpsSequencer and checks that the sequencer's
 * normalTempo has changed from the header value (0x80) to 0x60.
 */
class TestTempoChangeRoundTrip {

    /**
     * Verify that the compiled SMPS binary contains the EA 60 byte pair
     * somewhere in its track data section.
     */
    @Test
    void compiledBinaryContainsTempoChangeBytes() {
        Song song = createSongWithTempoChange();
        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        assertNotNull(smps, "Compiled SMPS data should not be null");
        assertTrue(smps.length > 0, "Compiled SMPS data should not be empty");

        boolean found = false;
        for (int i = 0; i < smps.length - 1; i++) {
            if ((smps[i] & 0xFF) == 0xEA && (smps[i + 1] & 0xFF) == 0x60) {
                found = true;
                break;
            }
        }
        assertTrue(found,
                "Compiled binary should contain EA 60 (SET_TEMPO 0x60) in the track data");
    }

    /**
     * Verify that after playback the sequencer's normalTempo has changed
     * from the initial header value (0x80) to 0x60, proving the EA
     * coordination flag was executed.
     */
    @Test
    void sequencerTempoChangesAfterPlayback() {
        Song song = createSongWithTempoChange();
        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        // Wrap the compiled binary for the sequencer
        int baseNoteOffset = 1; // S2 mode
        SimpleSmpsData data = new SimpleSmpsData(smps, baseNoteOffset);

        // Verify header tempo is 0x80 as configured
        assertEquals(0x80, data.getTempo(),
                "Header tempo should be 0x80 as set in the Song model");

        // Build S2 config (same as PlaybackEngine)
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();

        // Create sequencer with a real SmpsDriver as synthesizer
        SmpsDriver driver = new SmpsDriver(44100.0);
        SmpsSequencer sequencer = new SmpsSequencer(data, null, driver, config);
        driver.addSequencer(sequencer, false);

        // Render enough audio for the sequencer to process the track data,
        // including the EA 60 tempo change command
        short[] buffer = new short[8192];
        for (int i = 0; i < 10; i++) {
            driver.read(buffer);
        }

        // After playback, the sequencer should have processed EA 60 and
        // updated normalTempo from 0x80 to 0x60
        assertEquals(0x60, sequencer.getNormalTempo(),
                "After playback, normalTempo should be 0x60 (changed from 0x80 by EA coord flag)");
    }

    /**
     * Verify the tempo change is also visible via the debugState() API.
     */
    @Test
    void debugStateReflectsTempoChangeAfterPlayback() {
        Song song = createSongWithTempoChange();
        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        SimpleSmpsData data = new SimpleSmpsData(smps, 1);

        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();

        SmpsDriver driver = new SmpsDriver(44100.0);
        SmpsSequencer sequencer = new SmpsSequencer(data, null, driver, config);
        driver.addSequencer(sequencer, false);

        short[] buffer = new short[8192];
        for (int i = 0; i < 10; i++) {
            driver.read(buffer);
        }

        SmpsSequencer.DebugState state = sequencer.debugState();
        assertNotNull(state, "debugState() should return non-null after playback");

        // tempoWeight in OVERFLOW2 mode is the tempo value itself (before accumulator math).
        // After EA 60, the normalTempo is 0x60 and calculateTempo() should produce
        // tempoWeight = 0x60 (no speed shoes, no PAL adjustment).
        assertEquals(0x60, state.tempoWeight,
                "debugState().tempoWeight should reflect the updated tempo 0x60");
    }

    // --- Helpers ---

    /**
     * Creates a Song with SmpsMode.S2, initial tempo 0x80, and a single
     * FM channel 0 phrase containing:
     *   EF 00 - set voice 0
     *   EA 60 - set tempo to 0x60
     *   90 30 - note 0x90, duration 0x30
     *   F2    - stop
     *
     * The phrase uses hierarchical arrangement so it is compiled by
     * HierarchyCompiler via PatternCompiler.
     */
    private Song createSongWithTempoChange() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S2);
        song.setTempo(0x80);
        song.setDividingTiming(1);

        // Minimal FM voice: algo 0, Op4 as sole carrier with fast attack
        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        voiceData[0] = 0x00;   // algo 0, feedback 0
        voiceData[2] = 0x7F;   // Op1 TL = 127 (silent modulator)
        voiceData[7] = 0x7F;   // Op3 TL = 127 (silent modulator)
        voiceData[12] = 0x7F;  // Op2 TL = 127 (silent modulator)
        voiceData[16] = 0x01;  // Op4 DT_MUL: DT=0, MUL=1
        voiceData[17] = 0x00;  // Op4 TL = 0 (full volume carrier)
        voiceData[18] = 0x1F;  // Op4 RS_AR: RS=0, AR=31 (fastest attack)
        voiceData[19] = 0x00;  // Op4 AM_D1R: AM=0, D1R=0
        voiceData[20] = 0x00;  // Op4 D2R = 0
        voiceData[21] = 0x0F;  // Op4 D1L_RR: D1L=0, RR=15
        song.getVoiceBank().add(new FmVoice("Sine", voiceData));

        // Create phrase bytecode:
        //   EF 00 - set voice 0
        //   EA 60 - set tempo to 0x60
        //   90 30 - note 0x90, duration 0x30
        byte[] phraseData = new byte[] {
                (byte) 0xEF, 0x00,       // Set voice 0
                (byte) 0xEA, 0x60,       // Set tempo to 0x60
                (byte) 0x90, 0x30        // Note 0x90, duration 0x30
        };

        // Add phrase to hierarchical arrangement on FM channel 0
        var arr = song.getHierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("FM0", ChannelType.FM);
        phrase.setData(phraseData);
        arr.getChain(0).getEntries().add(new ChainEntry(phrase.getId()));

        // Set loop so the compiler generates F6 JUMP instead of F2 STOP
        arr.getChain(0).setLoopEntryIndex(0);

        return song;
    }
}
