package com.opensmps.deck.io;

import com.opensmps.deck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class TestOsmpsVoiceFile {

    @TempDir
    File tempDir;

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x3C; // algo=4, fb=7
        voiceData[1] = 0x71;
        voiceData[2] = 0x22;
        FmVoice voice = new FmVoice("BrassLead", voiceData);

        File file = new File(tempDir, "test.osmpsvoice");
        OsmpsVoiceFile.save(voice, file);
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        FmVoice loaded = OsmpsVoiceFile.load(file);
        assertEquals("BrassLead", loaded.getName());
        assertEquals(4, loaded.getAlgorithm());
        assertEquals(7, loaded.getFeedback());
        assertArrayEquals(voiceData, loaded.getData());
    }

    @Test
    void loadRejectsUnsupportedVersion() throws IOException {
        File file = new File(tempDir, "bad_version.osmpsvoice");
        String json = """
                {"version": 99, "name": "Test", "data": "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"}
                """;
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class, () -> OsmpsVoiceFile.load(file));
        assertTrue(ex.getMessage().contains("99"), "Expected message to mention version 99");
    }

    @Test
    void loadRejectsMissingData() throws IOException {
        File file = new File(tempDir, "no_data.osmpsvoice");
        String json = """
                {"version": 1, "name": "Test"}
                """;
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> OsmpsVoiceFile.load(file));
    }

    @Test
    void loadRejectsWrongDataLength() throws IOException {
        File file = new File(tempDir, "short_data.osmpsvoice");
        String json = """
                {"version": 1, "name": "Test", "data": "00 01 02"}
                """;
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class, () -> OsmpsVoiceFile.load(file));
        assertTrue(ex.getMessage().contains("got 3"), "Expected message to mention 3 bytes received");
    }

    @Test
    void loadRejectsCorruptJson() throws IOException {
        File file = new File(tempDir, "corrupt.osmpsvoice");
        Files.writeString(file.toPath(), "not json {{{", StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> OsmpsVoiceFile.load(file));
    }

    @Test
    void specialCharactersInNamePreserved() throws IOException {
        String specialName = "Slap Bass (v2) \u2014 \u00e9dition";
        byte[] voiceData = new byte[25];
        FmVoice voice = new FmVoice(specialName, voiceData);

        File file = new File(tempDir, "special.osmpsvoice");
        OsmpsVoiceFile.save(voice, file);

        FmVoice loaded = OsmpsVoiceFile.load(file);
        assertEquals(specialName, loaded.getName());
    }
}
