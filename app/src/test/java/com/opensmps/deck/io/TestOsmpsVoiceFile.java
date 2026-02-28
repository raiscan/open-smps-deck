package com.opensmps.deck.io;

import com.opensmps.deck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

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
}
