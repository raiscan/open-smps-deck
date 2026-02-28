package com.opensmps.deck.io;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.PsgEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
}
