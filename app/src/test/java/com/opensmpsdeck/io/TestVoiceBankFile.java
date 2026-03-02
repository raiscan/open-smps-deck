package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestVoiceBankFile {

    @TempDir
    File tempDir;

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        // Build a 25-byte FM voice with algo=4, fb=7
        byte[] voiceData = new byte[25];
        // byte[0] = algorithm (bits 0-2) | feedback (bits 3-5)
        // algo=4 -> 0x04, fb=7 -> 0x07<<3 = 0x38, combined = 0x3C
        voiceData[0] = 0x3C;
        voiceData[1] = 0x71; // some operator data
        voiceData[2] = 0x22;
        FmVoice voice = new FmVoice("BrassLead", voiceData);
        assertEquals(4, voice.getAlgorithm());
        assertEquals(7, voice.getFeedback());

        // Build a PSG envelope: 3 steps + terminator
        byte[] envData = { 0x00, 0x02, 0x05, (byte) 0x80 };
        PsgEnvelope envelope = new PsgEnvelope("Pluck", envData);

        File file = new File(tempDir, "test.ovm");
        VoiceBankFile.save("My Bank", List.of(voice), List.of(envelope), file);
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        VoiceBankFile.LoadResult result = VoiceBankFile.load(file);

        assertEquals("My Bank", result.name());

        // Verify FM voice
        assertEquals(1, result.voices().size());
        FmVoice loaded = result.voices().get(0);
        assertEquals("BrassLead", loaded.getName());
        assertEquals(4, loaded.getAlgorithm());
        assertEquals(7, loaded.getFeedback());
        assertArrayEquals(voiceData, loaded.getData());

        // Verify PSG envelope
        assertEquals(1, result.psgEnvelopes().size());
        PsgEnvelope loadedEnv = result.psgEnvelopes().get(0);
        assertEquals("Pluck", loadedEnv.getName());
        assertEquals(3, loadedEnv.getStepCount());
        assertArrayEquals(envData, loadedEnv.getData());
    }

    @Test
    void loadRejectsInvalidVersion() throws Exception {
        // Write a JSON file with version 99
        String json = """
                {
                  "version": 99,
                  "name": "Bad",
                  "voices": [],
                  "psgEnvelopes": []
                }
                """;
        File file = new File(tempDir, "bad.ovm");
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class, () -> VoiceBankFile.load(file));
        assertTrue(ex.getMessage().contains("99"), "Error should mention the version number");
    }

    @Test
    void saveAndLoadEmptyBank() throws IOException {
        File file = new File(tempDir, "empty.ovm");
        VoiceBankFile.save("Empty Bank", List.of(), List.of(), file);
        assertTrue(file.exists());

        VoiceBankFile.LoadResult result = VoiceBankFile.load(file);
        assertEquals("Empty Bank", result.name());
        assertTrue(result.voices().isEmpty(), "Should have no voices");
        assertTrue(result.psgEnvelopes().isEmpty(), "Should have no PSG envelopes");
    }

    @Test
    void testLoadCorruptJsonThrows() throws IOException {
        File file = new File(tempDir, "corrupt.ovm");
        Files.writeString(file.toPath(), "not valid json {{{", StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> VoiceBankFile.load(file),
                "Loading corrupt JSON should throw an exception");
    }

    @Test
    void testLoadMissingVoicesArrayThrows() throws IOException {
        String json = """
                {"version":1, "name":"Test"}
                """;
        File file = new File(tempDir, "novoices.ovm");
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> VoiceBankFile.load(file),
                "Loading JSON without 'voices' field should throw IOException");
    }

    @Test
    void testLoadInvalidHexDataThrows() throws IOException {
        String json = """
                {"version":1, "name":"Test", "voices":[{"name":"Bad", "data":"ZZ GG HH"}]}
                """;
        File file = new File(tempDir, "badhex.ovm");
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> VoiceBankFile.load(file),
                "Loading a voice with invalid hex data should throw");
    }

    @Test
    void testLargeBank50Voices() throws IOException {
        List<FmVoice> voices = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            byte[] data = new byte[FmVoice.VOICE_SIZE];
            // Give each voice unique data so we can verify round-trip
            data[0] = (byte) (i & 0x3F); // algo+fb
            data[1] = (byte) i;
            data[2] = (byte) (i * 3);
            voices.add(new FmVoice("Voice_" + i, data));
        }

        File file = new File(tempDir, "large.ovm");
        VoiceBankFile.save("Large Bank", voices, List.of(), file);

        VoiceBankFile.LoadResult result = VoiceBankFile.load(file);
        assertEquals("Large Bank", result.name());
        assertEquals(50, result.voices().size(), "All 50 voices should round-trip");

        for (int i = 0; i < 50; i++) {
            FmVoice loaded = result.voices().get(i);
            assertEquals("Voice_" + i, loaded.getName(), "Voice name mismatch at index " + i);
            assertArrayEquals(voices.get(i).getData(), loaded.getData(),
                    "Voice data mismatch at index " + i);
        }
    }

    @Test
    void testBankWithOnlyPsgEnvelopes() throws IOException {
        List<PsgEnvelope> envelopes = List.of(
                new PsgEnvelope("Env1", new byte[]{0x00, 0x01, (byte) 0x80}),
                new PsgEnvelope("Env2", new byte[]{0x03, 0x05, 0x07, (byte) 0x80}),
                new PsgEnvelope("Env3", new byte[]{0x02, (byte) 0x80})
        );

        File file = new File(tempDir, "psgonly.ovm");
        VoiceBankFile.save("PSG Only", List.of(), envelopes, file);

        VoiceBankFile.LoadResult result = VoiceBankFile.load(file);
        assertEquals("PSG Only", result.name());
        assertTrue(result.voices().isEmpty(), "Voices list should be empty");
        assertEquals(3, result.psgEnvelopes().size(), "Should have 3 PSG envelopes");

        assertEquals("Env1", result.psgEnvelopes().get(0).getName());
        assertEquals(2, result.psgEnvelopes().get(0).getStepCount());
        assertArrayEquals(new byte[]{0x00, 0x01, (byte) 0x80}, result.psgEnvelopes().get(0).getData());

        assertEquals("Env2", result.psgEnvelopes().get(1).getName());
        assertEquals(3, result.psgEnvelopes().get(1).getStepCount());

        assertEquals("Env3", result.psgEnvelopes().get(2).getName());
        assertEquals(1, result.psgEnvelopes().get(2).getStepCount());
    }

    @Test
    void testVoiceNamePreserved() throws IOException {
        byte[] data = new byte[FmVoice.VOICE_SIZE];
        data[0] = 0x1A;
        String specialName = "Slap Bass (v2)";
        FmVoice voice = new FmVoice(specialName, data);

        File file = new File(tempDir, "specialname.ovm");
        VoiceBankFile.save("Name Test", List.of(voice), List.of(), file);

        VoiceBankFile.LoadResult result = VoiceBankFile.load(file);
        assertEquals(1, result.voices().size());
        assertEquals(specialName, result.voices().get(0).getName(),
                "Special characters in voice name should be preserved exactly");
    }
}
