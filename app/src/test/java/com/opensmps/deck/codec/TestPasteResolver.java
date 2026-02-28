package com.opensmps.deck.codec;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.PsgEnvelope;
import com.opensmps.deck.model.Song;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestPasteResolver {

    // --- Helpers ---

    /** Create a 25-byte FM voice with the given algorithm byte and a distinguishing fill. */
    private static FmVoice makeVoice(String name, int algoByte, int fill) {
        byte[] data = new byte[FmVoice.VOICE_SIZE];
        data[0] = (byte) algoByte;
        for (int i = 1; i < data.length; i++) {
            data[i] = (byte) fill;
        }
        return new FmVoice(name, data);
    }

    /** Create a PSG envelope with the given step values followed by a 0x80 terminator. */
    private static PsgEnvelope makeEnvelope(String name, int... steps) {
        byte[] data = new byte[steps.length + 1];
        for (int i = 0; i < steps.length; i++) {
            data[i] = (byte) steps[i];
        }
        data[steps.length] = (byte) 0x80;
        return new PsgEnvelope(name, data);
    }

    /** Build a Song pre-populated with the given voices and PSG envelopes. */
    private static Song buildSong(List<FmVoice> voices, List<PsgEnvelope> envelopes) {
        Song song = new Song();
        song.getVoiceBank().addAll(voices);
        song.getPsgEnvelopes().addAll(envelopes);
        return song;
    }

    // ---- Tests ----

    @Test
    void testScanAndAutoRemapFullyResolved() {
        // Source song has two voices at indices 0 and 1
        FmVoice voiceA = makeVoice("Lead", 0x32, 0x10);
        FmVoice voiceB = makeVoice("Bass", 0x07, 0x20);

        // Destination song has the same voices (byte-identical) but in reversed order
        Song dstSong = buildSong(
                List.of(
                        makeVoice("Bass copy", 0x07, 0x20),  // dst index 0 matches src index 1
                        makeVoice("Lead copy", 0x32, 0x10)   // dst index 1 matches src index 0
                ),
                List.of()
        );

        // Channel data: note, SET_VOICE 0, note, SET_VOICE 1, note
        byte[] ch1 = {
                (byte) 0x80, 0x18,                         // note C4 dur 24
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,     // voice index 0
                (byte) 0x82, 0x18                          // note D4 dur 24
        };
        byte[] ch2 = {
                (byte) SmpsCoordFlags.SET_VOICE, 0x01,     // voice index 1
                (byte) 0x84, 0x0C                          // note E4 dur 12
        };

        PasteResolver.ScanResult result = PasteResolver.scanAndAutoRemap(
                new byte[][]{ch1, ch2},
                List.of(voiceA, voiceB),
                List.of(),
                dstSong
        );

        // All voices should be resolved
        assertTrue(PasteResolver.isFullyResolved(result),
                "All voice references should be auto-resolved");
        assertFalse(PasteResolver.hasNoInstruments(result),
                "Channel data contains instrument references");

        // Verify the mapping: src 0 -> dst 1, src 1 -> dst 0
        assertEquals(2, result.voiceMap().size());
        assertEquals(1, result.voiceMap().get(0), "src voice 0 should map to dst voice 1");
        assertEquals(0, result.voiceMap().get(1), "src voice 1 should map to dst voice 0");

        // No unresolved entries
        assertTrue(result.unresolvedVoices().isEmpty());
        assertTrue(result.unresolvedPsg().isEmpty());
    }

    @Test
    void testScanAndAutoRemapWithUnresolved() {
        // Source song has two voices
        FmVoice voiceA = makeVoice("Lead", 0x32, 0x10);
        FmVoice voiceB = makeVoice("Unique", 0x05, 0x55); // this one has no match in dst

        // Destination song has only one voice that matches voiceA
        Song dstSong = buildSong(
                List.of(makeVoice("Lead copy", 0x32, 0x10)),
                List.of()
        );

        // Channel data references both voice 0 and voice 1
        byte[] ch = {
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,     // voice 0 - has match
                (byte) 0x80, 0x18,
                (byte) SmpsCoordFlags.SET_VOICE, 0x01,     // voice 1 - no match
                (byte) 0x82, 0x18
        };

        PasteResolver.ScanResult result = PasteResolver.scanAndAutoRemap(
                new byte[][]{ch},
                List.of(voiceA, voiceB),
                List.of(),
                dstSong
        );

        assertFalse(PasteResolver.isFullyResolved(result),
                "Should NOT be fully resolved (voice 1 has no match)");
        assertFalse(PasteResolver.hasNoInstruments(result));

        // Voice 0 was resolved, voice 1 was not
        assertEquals(1, result.voiceMap().size());
        assertEquals(0, result.voiceMap().get(0), "src voice 0 should map to dst voice 0");

        assertEquals(1, result.unresolvedVoices().size());
        assertTrue(result.unresolvedVoices().contains(1),
                "Voice index 1 should be in the unresolved set");

        // allVoices should contain both
        assertTrue(result.allVoices().contains(0));
        assertTrue(result.allVoices().contains(1));
    }

    @Test
    void testHasNoInstruments() {
        // Channel data with only notes and rests - no EF or F5 commands
        byte[] ch = {
                (byte) 0x80, 0x18,   // note
                (byte) 0x82, 0x0C,   // note
                0x7F, 0x18           // rest (0x7F below coord flag range)
        };

        Song dstSong = buildSong(List.of(), List.of());

        PasteResolver.ScanResult result = PasteResolver.scanAndAutoRemap(
                new byte[][]{ch},
                List.of(),
                List.of(),
                dstSong
        );

        assertTrue(PasteResolver.hasNoInstruments(result),
                "Data with no SET_VOICE or PSG_INSTRUMENT should have no instruments");
        assertTrue(result.allVoices().isEmpty());
        assertTrue(result.allPsg().isEmpty());
        // Fully resolved is vacuously true when there are no instruments
        assertTrue(PasteResolver.isFullyResolved(result));
    }

    @Test
    void testScanWithPsgInstruments() {
        // PSG envelopes in source
        PsgEnvelope envA = makeEnvelope("Decay", 0, 1, 2, 3);
        PsgEnvelope envB = makeEnvelope("Swell", 5, 4, 3, 2);

        // Destination has the same envelopes in different order
        Song dstSong = buildSong(
                List.of(),
                List.of(
                        makeEnvelope("Swell copy", 5, 4, 3, 2),  // dst 0 matches src 1
                        makeEnvelope("Decay copy", 0, 1, 2, 3)   // dst 1 matches src 0
                )
        );

        // Channel data with PSG instrument references
        byte[] ch = {
                (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x00,  // PSG envelope 0
                (byte) 0x80, 0x18,
                (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x01,  // PSG envelope 1
                (byte) 0x82, 0x0C
        };

        PasteResolver.ScanResult result = PasteResolver.scanAndAutoRemap(
                new byte[][]{ch},
                List.of(),
                List.of(envA, envB),
                dstSong
        );

        assertTrue(PasteResolver.isFullyResolved(result),
                "All PSG references should be auto-resolved");
        assertFalse(PasteResolver.hasNoInstruments(result));

        // Verify PSG mapping: src 0 -> dst 1, src 1 -> dst 0
        assertEquals(2, result.psgMap().size());
        assertEquals(1, result.psgMap().get(0), "src PSG 0 should map to dst PSG 1");
        assertEquals(0, result.psgMap().get(1), "src PSG 1 should map to dst PSG 0");

        // allPsg should contain both indices
        assertTrue(result.allPsg().contains(0));
        assertTrue(result.allPsg().contains(1));
        assertTrue(result.allVoices().isEmpty(), "No FM voice references expected");
    }

    @Test
    void testRewriteAllRemapsIndices() {
        // Channel data with SET_VOICE 0 in two channels
        byte[] ch1 = {
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0x80, 0x18
        };
        byte[] ch2 = {
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0x82, 0x0C,
                (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x01,
                (byte) 0x84, 0x18
        };

        // Remap voice 0 -> 2, PSG 1 -> 3
        Map<Integer, Integer> voiceMap = Map.of(0, 2);
        Map<Integer, Integer> psgMap = Map.of(1, 3);

        byte[][] rewritten = PasteResolver.rewriteAll(new byte[][]{ch1, ch2}, voiceMap, psgMap);

        assertEquals(2, rewritten.length, "Should produce same number of channel arrays");

        // ch1: SET_VOICE param should now be 2
        assertEquals((byte) SmpsCoordFlags.SET_VOICE, rewritten[0][0]);
        assertEquals(2, rewritten[0][1] & 0xFF, "voice index should be remapped to 2");
        // Rest of ch1 data unchanged
        assertEquals((byte) 0x80, rewritten[0][2]);
        assertEquals((byte) 0x18, rewritten[0][3]);

        // ch2: SET_VOICE param remapped, PSG_INSTRUMENT param remapped
        assertEquals((byte) SmpsCoordFlags.SET_VOICE, rewritten[1][0]);
        assertEquals(2, rewritten[1][1] & 0xFF, "ch2 voice index should be remapped to 2");
        assertEquals((byte) 0x82, rewritten[1][2]);
        assertEquals((byte) 0x0C, rewritten[1][3]);
        assertEquals((byte) SmpsCoordFlags.PSG_INSTRUMENT, rewritten[1][4]);
        assertEquals(3, rewritten[1][5] & 0xFF, "ch2 PSG index should be remapped to 3");

        // Verify original data is not mutated
        assertEquals(0x00, ch1[1] & 0xFF, "original ch1 data should not be modified");
        assertEquals(0x00, ch2[1] & 0xFF, "original ch2 data should not be modified");
        assertEquals(0x01, ch2[5] & 0xFF, "original ch2 PSG data should not be modified");
    }

    @Test
    void testEmptyChannelData() {
        Song dstSong = buildSong(
                List.of(makeVoice("V", 0x00, 0x00)),
                List.of()
        );

        // Scan with empty byte arrays
        PasteResolver.ScanResult result = PasteResolver.scanAndAutoRemap(
                new byte[][]{new byte[0], new byte[0]},
                List.of(makeVoice("V", 0x00, 0x00)),
                List.of(),
                dstSong
        );

        assertTrue(PasteResolver.hasNoInstruments(result),
                "Empty data should have no instruments");
        assertTrue(PasteResolver.isFullyResolved(result),
                "Empty data is vacuously fully resolved");
        assertTrue(result.allVoices().isEmpty());
        assertTrue(result.allPsg().isEmpty());
        assertTrue(result.voiceMap().isEmpty());
        assertTrue(result.psgMap().isEmpty());
        assertTrue(result.unresolvedVoices().isEmpty());
        assertTrue(result.unresolvedPsg().isEmpty());
    }

    @Test
    void testEmptyChannelDataArray() {
        Song dstSong = buildSong(List.of(), List.of());

        // Zero-length outer array
        PasteResolver.ScanResult result = PasteResolver.scanAndAutoRemap(
                new byte[0][],
                List.of(),
                List.of(),
                dstSong
        );

        assertTrue(PasteResolver.hasNoInstruments(result));
        assertTrue(PasteResolver.isFullyResolved(result));
    }

    @Test
    void testRewriteAllPreservesNonInstrumentBytes() {
        // Data with coordination flags that are NOT voice/PSG
        byte[] ch = {
                (byte) SmpsCoordFlags.PAN, 0x40,             // E0 40 - pan
                (byte) SmpsCoordFlags.MODULATION, 0x01, 0x02, 0x03, 0x04,  // F0 + 4 params
                (byte) SmpsCoordFlags.SET_TEMPO, 0x60,       // EA 60 - tempo
                (byte) 0x80, 0x18                            // note
        };

        // No remaps needed - maps are empty
        byte[][] rewritten = PasteResolver.rewriteAll(
                new byte[][]{ch}, Map.of(), Map.of()
        );

        // All bytes should be preserved exactly
        assertArrayEquals(ch, rewritten[0],
                "Non-instrument bytes should pass through unchanged");
    }

    @Test
    void testScanMixedVoiceAndPsgAcrossChannels() {
        // Voice references in ch1, PSG references in ch2
        FmVoice voice = makeVoice("Lead", 0x32, 0x10);
        PsgEnvelope env = makeEnvelope("Decay", 0, 1, 2);

        Song dstSong = buildSong(
                List.of(makeVoice("Lead copy", 0x32, 0x10)),
                List.of(makeEnvelope("Decay copy", 0, 1, 2))
        );

        byte[] ch1 = {
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0x80, 0x18
        };
        byte[] ch2 = {
                (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x00,
                (byte) 0x80, 0x18
        };

        PasteResolver.ScanResult result = PasteResolver.scanAndAutoRemap(
                new byte[][]{ch1, ch2},
                List.of(voice),
                List.of(env),
                dstSong
        );

        assertTrue(PasteResolver.isFullyResolved(result));
        assertEquals(1, result.allVoices().size());
        assertEquals(1, result.allPsg().size());
        assertTrue(result.allVoices().contains(0));
        assertTrue(result.allPsg().contains(0));
        assertEquals(0, result.voiceMap().get(0));
        assertEquals(0, result.psgMap().get(0));
    }

    @Test
    void testRewriteAllWithEmptyArrays() {
        // Rewrite should handle empty channel data gracefully
        byte[][] rewritten = PasteResolver.rewriteAll(
                new byte[][]{new byte[0]},
                Map.of(0, 1),
                Map.of()
        );

        assertEquals(1, rewritten.length);
        assertEquals(0, rewritten[0].length);
    }

    @Test
    void testDuplicateVoiceRefsAcrossChannelsDeduped() {
        // Both channels reference the same voice index 0
        FmVoice voice = makeVoice("Lead", 0x32, 0x10);
        Song dstSong = buildSong(
                List.of(makeVoice("Lead copy", 0x32, 0x10)),
                List.of()
        );

        byte[] ch1 = {(byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0x80, 0x18};
        byte[] ch2 = {(byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0x82, 0x0C};

        PasteResolver.ScanResult result = PasteResolver.scanAndAutoRemap(
                new byte[][]{ch1, ch2},
                List.of(voice),
                List.of(),
                dstSong
        );

        // allVoices should contain only one entry despite two channels referencing it
        assertEquals(1, result.allVoices().size());
        assertTrue(result.allVoices().contains(0));
        assertEquals(1, result.voiceMap().size());
    }
}
