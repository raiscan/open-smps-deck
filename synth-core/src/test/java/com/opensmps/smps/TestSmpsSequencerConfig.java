package com.opensmps.smps;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SmpsSequencerConfig builder defaults and overrides.
 */
class TestSmpsSequencerConfig {

    @Test
    void testBuilderDefaults() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();

        // Verify S2-compatible defaults
        assertEquals(SmpsSequencerConfig.TempoMode.OVERFLOW2, config.getTempoMode(),
                "Default tempo mode should be OVERFLOW2 (S2)");
        assertFalse(config.isRelativePointers(),
                "Default relativePointers should be false (S2/Z80 absolute)");
        assertFalse(config.isTempoOnFirstTick(),
                "Default tempoOnFirstTick should be false (S2)");
        assertEquals(0x100, config.getTempoModBase(),
                "Default tempoModBase should be 0x100");
        assertTrue(config.isApplyModOnNote(),
                "Default applyModOnNote should be true (S2)");
        assertTrue(config.isHalveModSteps(),
                "Default halveModSteps should be true (S2)");
        assertTrue(config.getExtraTrkEndFlags().isEmpty(),
                "Default extraTrkEndFlags should be empty");
        assertTrue(config.getCoordFlagParamOverrides().isEmpty(),
                "Default coordFlagParamOverrides should be empty");
        assertTrue(config.getSpeedUpTempos().isEmpty(),
                "Default speedUpTempos should be empty");

        // S3K-specific field defaults (S2 compatible)
        assertEquals(SmpsSequencerConfig.VolMode.ALGO, config.getVolMode(),
                "Default volMode should be ALGO");
        assertEquals(SmpsSequencerConfig.PsgEnvCmd80.HOLD, config.getPsgEnvCmd80(),
                "Default psgEnvCmd80 should be HOLD");
        assertEquals(SmpsSequencerConfig.NoteOnPrevent.REST, config.getNoteOnPrevent(),
                "Default noteOnPrevent should be REST");
        assertEquals(SmpsSequencerConfig.DelayFreq.RESET, config.getDelayFreq(),
                "Default delayFreq should be RESET");
        assertNull(config.getCoordFlagHandler(),
                "Default coordFlagHandler should be null");
        assertEquals(SmpsSequencerConfig.ModAlgo.MOD_68K, config.getModAlgo(),
                "Default modAlgo should be MOD_68K");
        assertEquals(3, config.getFadeOutDelay(), "Default fadeOutDelay should be 3");
        assertEquals(0x28, config.getFadeOutSteps(), "Default fadeOutSteps should be 0x28");
        assertEquals(0x28, config.getFadeInSteps(), "Default fadeInSteps should be 0x28");
        assertEquals(2, config.getFadeInDelay(), "Default fadeInDelay should be 2");
    }

    @Test
    void testBuilderOverrides() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .relativePointers(true)
                .tempoOnFirstTick(true)
                .tempoModBase(0x200)
                .speedUpTempos(Map.of(0x01, 0x80))
                .fmChannelOrder(new int[]{ 0, 1, 2 })
                .psgChannelOrder(new int[]{ 0x80 })
                .applyModOnNote(false)
                .halveModSteps(false)
                .extraTrkEndFlags(Set.of(0xEE))
                .coordFlagParamOverrides(Map.of(0xE0, 2))
                .volMode(SmpsSequencerConfig.VolMode.BIT7)
                .psgEnvCmd80(SmpsSequencerConfig.PsgEnvCmd80.RESET)
                .noteOnPrevent(SmpsSequencerConfig.NoteOnPrevent.HOLD)
                .delayFreq(SmpsSequencerConfig.DelayFreq.KEEP)
                .modAlgo(SmpsSequencerConfig.ModAlgo.MOD_Z80)
                .fadeOutDelay(6)
                .fadeOutSteps(0x40)
                .fadeInSteps(0x40)
                .fadeInDelay(4)
                .build();

        assertEquals(SmpsSequencerConfig.TempoMode.OVERFLOW, config.getTempoMode());
        assertTrue(config.isRelativePointers());
        assertTrue(config.isTempoOnFirstTick());
        assertEquals(0x200, config.getTempoModBase());
        assertEquals(Map.of(0x01, 0x80), config.getSpeedUpTempos());
        assertArrayEquals(new int[]{ 0, 1, 2 }, config.getFmChannelOrder());
        assertArrayEquals(new int[]{ 0x80 }, config.getPsgChannelOrder());
        assertFalse(config.isApplyModOnNote());
        assertFalse(config.isHalveModSteps());
        assertTrue(config.getExtraTrkEndFlags().contains(0xEE));
        assertEquals(2, config.getCoordFlagParamOverrides().get(0xE0));
        assertEquals(SmpsSequencerConfig.VolMode.BIT7, config.getVolMode());
        assertEquals(SmpsSequencerConfig.PsgEnvCmd80.RESET, config.getPsgEnvCmd80());
        assertEquals(SmpsSequencerConfig.NoteOnPrevent.HOLD, config.getNoteOnPrevent());
        assertEquals(SmpsSequencerConfig.DelayFreq.KEEP, config.getDelayFreq());
        assertEquals(SmpsSequencerConfig.ModAlgo.MOD_Z80, config.getModAlgo());
        assertEquals(6, config.getFadeOutDelay());
        assertEquals(0x40, config.getFadeOutSteps());
        assertEquals(0x40, config.getFadeInSteps());
        assertEquals(4, config.getFadeInDelay());
    }

    @Test
    void testFmChannelOrderDefensiveCopy() {
        int[] order = { 0x16, 0, 1 };
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .fmChannelOrder(order)
                .build();

        // Mutating the source array should not affect the config
        order[0] = 999;
        assertNotEquals(999, config.getFmChannelOrder()[0],
                "Config should hold a defensive copy of fmChannelOrder");

        // Mutating the returned array should not affect the config
        int[] returned = config.getFmChannelOrder();
        returned[0] = 888;
        assertNotEquals(888, config.getFmChannelOrder()[0],
                "getFmChannelOrder() should return a defensive copy");
    }

    @Test
    void testSpeedUpTemposImmutable() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .speedUpTempos(Map.of(1, 2))
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                config.getSpeedUpTempos().put(3, 4),
                "speedUpTempos map should be unmodifiable");
    }
}
