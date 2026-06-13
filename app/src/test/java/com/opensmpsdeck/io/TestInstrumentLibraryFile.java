package com.opensmpsdeck.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.library.InstrumentAssetKind;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.SourceReference;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInstrumentLibraryFile {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsJsonAndDacPayloads() throws Exception {
        InstrumentLibrary library = new InstrumentLibrary();
        SourceReference source = SourceReference.minimal(tempDir.toString(), "DAC.ini", "81");
        byte[] voice = new byte[FmVoice.VOICE_SIZE];
        voice[0] = 0x07;
        library.addOrMerge(InstrumentLibraryEntry.fmVoice("FM", voice, source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.psgEnvelope("PSG", new byte[]{1, 2, (byte) 0x80}, source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.modEnvelope("MOD", new byte[]{3, 4, (byte) 0x80}, source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.dacSample(
                "DAC", new byte[]{0x40, 0x41}, 0x22, "DPCM", null, "01", "02", "81", source), Instant.EPOCH);

        InstrumentLibraryFile.save(library, tempDir);
        InstrumentLibrary loaded = InstrumentLibraryFile.load(tempDir);

        assertTrue(Files.exists(tempDir.resolve("library.json")));
        assertTrue(Files.isDirectory(tempDir.resolve("dac")));
        assertEquals(1, loaded.entries(InstrumentAssetKind.FM_VOICE).size());
        assertEquals(1, loaded.entries(InstrumentAssetKind.PSG_ENVELOPE).size());
        assertEquals(1, loaded.entries(InstrumentAssetKind.MOD_ENVELOPE).size());
        InstrumentLibraryEntry dac = loaded.entries(InstrumentAssetKind.DAC_SAMPLE).getFirst();
        assertArrayEquals(new byte[]{0x40, 0x41}, dac.data());
        assertEquals(0x22, dac.playbackRate());
        assertEquals("DPCM", dac.compressionLabel());
    }

    @Test
    void invalidJsonThrowsIOException() throws Exception {
        Files.writeString(tempDir.resolve("library.json"), "{ not json");

        assertThrows(java.io.IOException.class, () -> InstrumentLibraryFile.load(tempDir));
    }

    @Test
    void dacPayloadFilenameUsesPayloadSha256Only() throws Exception {
        InstrumentLibrary library = new InstrumentLibrary();
        SourceReference source = SourceReference.minimal(tempDir.toString(), "DAC.ini", "81");
        byte[] data = new byte[]{0x40, 0x41};

        library.addOrMerge(InstrumentLibraryEntry.dacSample(
                "DAC fast", data, 0x22, "PCM", null, null, null, "81", source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.dacSample(
                "DAC slow", data, 0x33, "PCM", null, null, null, "82", source), Instant.EPOCH);

        InstrumentLibraryFile.save(library, tempDir);

        String expectedPayload = "dac/" + sha256Hex(data) + ".pcm";
        JsonObject root = JsonParser.parseString(Files.readString(tempDir.resolve("library.json"))).getAsJsonObject();
        JsonArray entries = root.getAsJsonArray("entries");
        JsonArray dacEntries = new JsonArray();
        for (var entry : entries) {
            JsonObject object = entry.getAsJsonObject();
            if ("DAC_SAMPLE".equals(object.get("kind").getAsString())) {
                dacEntries.add(object);
            }
        }

        assertEquals(2, dacEntries.size());
        assertEquals(expectedPayload, dacEntries.get(0).getAsJsonObject().get("payload").getAsString());
        assertEquals(expectedPayload, dacEntries.get(1).getAsJsonObject().get("payload").getAsString());
        assertTrue(Files.exists(tempDir.resolve(expectedPayload)));
        assertEquals(2, InstrumentLibraryFile.load(tempDir).entries(InstrumentAssetKind.DAC_SAMPLE).size());
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            builder.append(String.format("%02x", b & 0xFF));
        }
        return builder.toString();
    }
}
