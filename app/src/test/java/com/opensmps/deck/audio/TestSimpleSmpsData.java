package com.opensmps.deck.audio;

import com.opensmps.deck.codec.PatternCompiler;
import com.opensmps.deck.model.ChannelType;
import com.opensmps.deck.model.ChainEntry;
import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.Song;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TestSimpleSmpsData {

    /**
     * Adds a phrase with the given data to the hierarchical arrangement for the
     * specified channel. Trailing F2 (STOP) bytes are stripped from the phrase
     * data since the chain compiler appends its own termination.
     */
    private static void addPhrase(Song song, int channel, byte[] data) {
        var arr = song.getHierarchicalArrangement();
        int end = data.length;
        while (end > 0 && (data[end - 1] & 0xFF) == 0xF2) end--;
        byte[] phraseData = end < data.length ? Arrays.copyOf(data, end) : data;
        var phrase = arr.getPhraseLibrary().createPhrase(
            "Ch" + channel, ChannelType.fromChannelIndex(channel));
        phrase.setData(phraseData);
        arr.getChain(channel).getEntries().add(new ChainEntry(phrase.getId()));
    }

    /**
     * Sets the loop entry index on all chains that have entries, so the
     * compiled output contains an F6 JUMP instead of F2 STOP.
     */
    private static void setLoopOnActiveChains(Song song, int entryIndex) {
        var arr = song.getHierarchicalArrangement();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            var chain = arr.getChain(ch);
            if (!chain.getEntries().isEmpty() && entryIndex < chain.getEntries().size()) {
                chain.setLoopEntryIndex(entryIndex);
            }
        }
    }

    /**
     * The single-arg constructor should default to S2 base note offset of 1.
     */
    @Test
    void testBaseNoteOffsetDefaultIsOne() {
        byte[] minimalData = new byte[6]; // just enough to avoid the short-data guard
        SimpleSmpsData data = new SimpleSmpsData(minimalData);
        assertEquals(1, data.getBaseNoteOffset(), "Single-arg constructor should default baseNoteOffset to 1 (S2)");
    }

    /**
     * The two-arg constructor should respect the caller-supplied base note offset.
     */
    @Test
    void testBaseNoteOffsetCustom() {
        byte[] minimalData = new byte[6];
        SimpleSmpsData data = new SimpleSmpsData(minimalData, 0);
        assertEquals(0, data.getBaseNoteOffset(), "Two-arg constructor with 0 should set baseNoteOffset to 0");
    }

    /**
     * PSG base note offset defaults to 0 in all constructors, since PSG
     * drivers always use note - 0x81 (offset 0) regardless of SMPS mode.
     */
    @Test
    void testPsgBaseNoteOffsetDefaultsToZero() {
        byte[] minimalData = new byte[6];

        // Single-arg: FM=1, PSG=0
        SimpleSmpsData s2Default = new SimpleSmpsData(minimalData);
        assertEquals(1, s2Default.getBaseNoteOffset());
        assertEquals(0, s2Default.getPsgBaseNoteOffset(),
            "PSG offset should be 0 even when FM offset is 1 (S2 default)");

        // Two-arg: FM=0, PSG=0
        SimpleSmpsData s1 = new SimpleSmpsData(minimalData, 0);
        assertEquals(0, s1.getPsgBaseNoteOffset(),
            "PSG offset should be 0 when FM offset is 0 (S1/S3K)");

        // Three-arg: explicit PSG offset
        SimpleSmpsData explicit = new SimpleSmpsData(minimalData, 1, 0);
        assertEquals(1, explicit.getBaseNoteOffset());
        assertEquals(0, explicit.getPsgBaseNoteOffset(),
            "Three-arg constructor should respect explicit PSG offset");
    }

    /**
     * Compile a Song with a known voice, create SimpleSmpsData from the compiled
     * bytes, then verify that getVoice(0) returns the original 25-byte voice data.
     */
    @Test
    void testGetVoiceReturnsCorrectData() {
        // Build a voice with recognizable data
        byte[] voiceBytes = new byte[FmVoice.VOICE_SIZE];
        voiceBytes[0] = 0x3A; // algo=2, fb=7
        for (int i = 1; i < FmVoice.VOICE_SIZE; i++) {
            voiceBytes[i] = (byte) (i * 7); // deterministic pattern
        }

        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("TestVoice", voiceBytes));
        addPhrase(song, 0,
                new byte[]{(byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x30});
        setLoopOnActiveChains(song, 0);

        byte[] smps = new PatternCompiler().compile(song);
        SimpleSmpsData data = new SimpleSmpsData(smps, 1);

        byte[] voice = data.getVoice(0);
        assertNotNull(voice, "getVoice(0) should return non-null for valid voice index");
        assertEquals(FmVoice.VOICE_SIZE, voice.length, "Voice data should be exactly 25 bytes");

        // Verify every byte matches the original
        for (int i = 0; i < FmVoice.VOICE_SIZE; i++) {
            assertEquals(voiceBytes[i], voice[i],
                    "Voice byte [" + i + "] should match original data");
        }
    }

    /**
     * Requesting a voice index far beyond the voice table should return null
     * rather than throwing an exception.
     */
    @Test
    void testGetVoiceOutOfBoundsReturnsNull() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[FmVoice.VOICE_SIZE]));
        addPhrase(song, 0,
                new byte[]{(byte) 0xA1, 0x30});
        setLoopOnActiveChains(song, 0);

        byte[] smps = new PatternCompiler().compile(song);
        SimpleSmpsData data = new SimpleSmpsData(smps, 1);

        // Voice index 999 is way beyond the single voice in the table
        byte[] voice = data.getVoice(999);
        assertNull(voice, "getVoice(999) should return null for out-of-bounds voice index");
    }

    /**
     * Compile a Song with known tempo and dividing timing, then verify that
     * SimpleSmpsData parses the header fields correctly.
     */
    @Test
    void testHeaderFieldsParsedCorrectly() {
        Song song = new Song();
        song.setTempo(0xC0);
        song.setDividingTiming(3);

        // Add two voices to verify voice pointer calculation
        byte[] v0 = new byte[FmVoice.VOICE_SIZE];
        v0[0] = 0x01; // algo 1
        byte[] v1 = new byte[FmVoice.VOICE_SIZE];
        v1[0] = 0x07; // algo 7
        song.getVoiceBank().add(new FmVoice("V0", v0));
        song.getVoiceBank().add(new FmVoice("V1", v1));

        // FM channel 0 active
        addPhrase(song, 0,
                new byte[]{(byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x30});
        // PSG channel 0 (index 6) active
        addPhrase(song, 6,
                new byte[]{(byte) 0xA1, 0x18});
        setLoopOnActiveChains(song, 0);

        byte[] smps = new PatternCompiler().compile(song);
        SimpleSmpsData data = new SimpleSmpsData(smps, 1);

        // Verify header fields
        assertEquals(0xC0, data.getTempo(), "Tempo should match compiled value");
        assertEquals(3, data.getDividingTiming(), "Dividing timing should match compiled value");
        assertEquals(1, data.getChannels(), "Should have 1 FM channel");
        assertEquals(1, data.getPsgChannels(), "Should have 1 PSG channel");

        // getData() should return the full compiled blob
        assertNotNull(data.getData(), "getData() should return non-null");
        assertEquals(smps.length, data.getData().length, "getData() should return the full binary blob");

        // Voice pointer should point to a valid offset within the data
        int voicePtr = data.getVoicePtr();
        assertTrue(voicePtr > 0, "Voice pointer should be > 0 when voices are present");
        assertTrue(voicePtr + FmVoice.VOICE_SIZE <= smps.length,
                "Voice pointer should allow at least one voice to fit");

        // FM pointers should be populated
        int[] fmPointers = data.getFmPointers();
        assertNotNull(fmPointers, "FM pointers array should not be null");
        assertEquals(1, fmPointers.length, "Should have 1 FM pointer");
        assertTrue(fmPointers[0] > 0, "FM channel 0 pointer should be > 0");

        // PSG pointers should be populated
        int[] psgPointers = data.getPsgPointers();
        assertNotNull(psgPointers, "PSG pointers array should not be null");
        assertEquals(1, psgPointers.length, "Should have 1 PSG pointer");
        assertTrue(psgPointers[0] > 0, "PSG channel 0 pointer should be > 0");

        // Verify voice data is accessible through the parsed pointer
        byte[] voice0 = data.getVoice(0);
        assertNotNull(voice0, "Voice 0 should be retrievable");
        assertEquals(0x01, voice0[0] & 0xFF, "Voice 0 algo byte should match");

        byte[] voice1 = data.getVoice(1);
        assertNotNull(voice1, "Voice 1 should be retrievable");
        assertEquals(0x07, voice1[0] & 0xFF, "Voice 1 algo byte should match");
    }

    /**
     * A very short byte array (shorter than the header) should not cause
     * exceptions when queried -- the parseHeader guard should handle it.
     */
    @Test
    void testTooShortDataHandledGracefully() {
        byte[] shortData = new byte[]{0x00, 0x01, 0x02, 0x03};
        SimpleSmpsData data = new SimpleSmpsData(shortData, 1);

        // Should return defaults since parseHeader bails out on data.length < 6
        assertEquals(0, data.getChannels(), "Channels should be 0 for too-short data");
        assertEquals(0, data.getPsgChannels(), "PSG channels should be 0 for too-short data");
        assertEquals(1, data.getDividingTiming(), "Dividing timing should be default 1 for too-short data");
        assertEquals(0, data.getTempo(), "Tempo should be 0 for too-short data");

        // getVoice should return null since voicePtr is 0
        assertNull(data.getVoice(0), "getVoice(0) should return null for too-short data");

        // getData should still return the original bytes
        assertNotNull(data.getData(), "getData() should return non-null even for short data");
        assertEquals(4, data.getData().length, "getData() should return the original 4 bytes");

        // read16 edge cases
        assertEquals(0, data.read16(3), "read16 near end of short data should return 0");
        assertEquals(0, data.read16(4), "read16 beyond short data should return 0");
    }

    /**
     * Verify read16 parses little-endian correctly.
     */
    @Test
    void testRead16LittleEndian() {
        byte[] testData = new byte[]{(byte) 0xCD, (byte) 0xAB, 0, 0, 0, 0};
        SimpleSmpsData data = new SimpleSmpsData(testData, 1);
        assertEquals(0xABCD, data.read16(0), "read16 should parse little-endian: 0xCD 0xAB -> 0xABCD");
    }

    /**
     * Verify PSG envelope handling: null by default, settable via setPsgEnvelopes.
     */
    @Test
    void testPsgEnvelopeAccessors() {
        byte[] minimalData = new byte[6];
        SimpleSmpsData data = new SimpleSmpsData(minimalData, 1);

        // Default: no envelopes set
        assertNull(data.getPsgEnvelope(0), "PSG envelope should be null when not set");
        assertNull(data.getPsgEnvelope(-1), "Negative index should return null");

        // Set envelopes and verify retrieval
        byte[][] envelopes = {
                {0x00, 0x01, 0x02, (byte) 0x80},
                {0x10, 0x20, (byte) 0x80}
        };
        data.setPsgEnvelopes(envelopes);

        assertNotNull(data.getPsgEnvelope(0), "PSG envelope 0 should be retrievable after set");
        assertArrayEquals(envelopes[0], data.getPsgEnvelope(0), "PSG envelope 0 data should match");
        assertNotNull(data.getPsgEnvelope(1), "PSG envelope 1 should be retrievable after set");
        assertArrayEquals(envelopes[1], data.getPsgEnvelope(1), "PSG envelope 1 data should match");
        assertNull(data.getPsgEnvelope(2), "Out-of-range PSG envelope should return null");
    }

    /**
     * Verify that FM key/volume offsets are parsed from the compiled header.
     */
    @Test
    void testFmKeyAndVolumeOffsets() {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[FmVoice.VOICE_SIZE]));
        addPhrase(song, 0,
                new byte[]{(byte) 0xA1, 0x30});
        setLoopOnActiveChains(song, 0);

        byte[] smps = new PatternCompiler().compile(song);
        SimpleSmpsData data = new SimpleSmpsData(smps, 1);

        // PatternCompiler writes 0 for key/volume offsets
        int[] keyOffsets = data.getFmKeyOffsets();
        int[] volOffsets = data.getFmVolumeOffsets();
        assertNotNull(keyOffsets);
        assertNotNull(volOffsets);
        assertEquals(1, keyOffsets.length, "Should have 1 FM key offset");
        assertEquals(1, volOffsets.length, "Should have 1 FM volume offset");
        assertEquals(0, keyOffsets[0], "FM key offset should be 0 as compiled");
        assertEquals(0, volOffsets[0], "FM volume offset should be 0 as compiled");
    }
}
