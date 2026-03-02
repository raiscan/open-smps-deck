package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TestInstrumentRemapper {

    @Test
    void testScanFindsVoiceAndPsgRefs() {
        // EF 02 = SET_VOICE to index 2, F5 01 = PSG_INSTRUMENT to index 1
        byte[] data = {(byte) 0x80, 0x18, (byte) 0xEF, 0x02, (byte) 0x82, 0x18, (byte) 0xF5, 0x01};
        InstrumentRemapper.ScanResult result = InstrumentRemapper.scan(data);
        assertTrue(result.voiceIndices().contains(2));
        assertTrue(result.psgIndices().contains(1));
    }

    @Test
    void testScanEmptyData() {
        InstrumentRemapper.ScanResult result = InstrumentRemapper.scan(new byte[0]);
        assertTrue(result.voiceIndices().isEmpty());
        assertTrue(result.psgIndices().isEmpty());
    }

    @Test
    void testRewriteVoiceIndex() {
        byte[] data = {(byte) 0xEF, 0x00, (byte) 0x80, 0x18};
        Map<Integer, Integer> voiceMap = Map.of(0, 5);
        byte[] rewritten = InstrumentRemapper.rewrite(data, voiceMap, Map.of());
        assertEquals((byte) 0xEF, rewritten[0]);
        assertEquals(5, rewritten[1] & 0xFF);
    }

    @Test
    void testRewritePsgIndex() {
        byte[] data = {(byte) 0xF5, 0x03, (byte) 0x80, 0x18};
        Map<Integer, Integer> psgMap = Map.of(3, 7);
        byte[] rewritten = InstrumentRemapper.rewrite(data, Map.of(), psgMap);
        assertEquals((byte) 0xF5, rewritten[0]);
        assertEquals(7, rewritten[1] & 0xFF);
    }

    @Test
    void testAutoRemapByteIdenticalVoices() {
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32;
        List<FmVoice> src = List.of(new FmVoice("Lead", voiceData));
        List<FmVoice> dst = List.of(new FmVoice("Bass", voiceData.clone()));
        Map<Integer, Integer> map = InstrumentRemapper.autoRemap(src, dst, Set.of(0));
        assertEquals(0, map.get(0));
    }

    @Test
    void testAutoRemapNoMatch() {
        byte[] srcData = new byte[25];
        srcData[0] = 0x32;
        byte[] dstData = new byte[25];
        dstData[0] = 0x07;
        List<FmVoice> src = List.of(new FmVoice("Lead", srcData));
        List<FmVoice> dst = List.of(new FmVoice("Other", dstData));
        Map<Integer, Integer> map = InstrumentRemapper.autoRemap(src, dst, Set.of(0));
        assertFalse(map.containsKey(0));
    }

    @Test
    void testAutoRemapPsgByteIdentical() {
        byte[] envData = {0x00, 0x01, 0x02, (byte) 0x80};
        List<PsgEnvelope> src = List.of(new PsgEnvelope("Env1", envData));
        List<PsgEnvelope> dst = List.of(new PsgEnvelope("Env2", envData.clone()));
        Map<Integer, Integer> map = InstrumentRemapper.autoRemapPsg(src, dst, Set.of(0));
        assertEquals(0, map.get(0));
    }

    @Test
    void testAutoRemapPsgNoMatch() {
        byte[] srcData = {0x00, 0x01, (byte) 0x80};
        byte[] dstData = {0x03, 0x04, (byte) 0x80};
        List<PsgEnvelope> src = List.of(new PsgEnvelope("Env1", srcData));
        List<PsgEnvelope> dst = List.of(new PsgEnvelope("Env2", dstData));
        Map<Integer, Integer> map = InstrumentRemapper.autoRemapPsg(src, dst, Set.of(0));
        assertFalse(map.containsKey(0));
    }

    @Test
    void testRewriteBothVoiceAndPsgInSameTrack() {
        byte[] data = {
            (byte) 0xEF, 0x02,        // SET_VOICE index 2
            (byte) 0x80, 0x18,        // rest
            (byte) 0xF5, 0x03         // PSG_INSTRUMENT index 3
        };
        Map<Integer, Integer> voiceMap = Map.of(2, 7);
        Map<Integer, Integer> psgMap = Map.of(3, 5);
        byte[] result = InstrumentRemapper.rewrite(data, voiceMap, psgMap);
        assertEquals(7, result[1] & 0xFF, "voice index should be remapped");
        assertEquals(5, result[5] & 0xFF, "PSG index should be remapped");
    }
}
