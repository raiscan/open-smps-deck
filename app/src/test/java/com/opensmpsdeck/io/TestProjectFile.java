package com.opensmpsdeck.io;

import com.opensmpsdeck.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class TestProjectFile {

    @TempDir
    File tempDir;

    @Test
    void testSaveAndLoadRoundTrip() throws IOException {
        Song original = new Song();
        original.setName("Test Song");
        original.setSmpsMode(SmpsMode.S3K);
        original.setTempo(0xA0);
        original.setDividingTiming(2);
        original.setLoopPoint(1);

        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32; // algo=2, fb=6
        voiceData[1] = 0x01;
        original.getVoiceBank().add(new FmVoice("Lead", voiceData));

        byte[] envData = { 0, 1, 2, 3, 4, 5, (byte) 0x80 };
        original.getPsgEnvelopes().add(new PsgEnvelope("Pluck", envData));

        original.getPatterns().get(0).setTrackData(0,
                new byte[]{ (byte) 0xEF, 0x00, (byte) 0xA1, 0x30, (byte) 0xF2 });
        original.getPatterns().get(0).setTrackData(6,
                new byte[]{ (byte) 0xA1, 0x20 });

        original.getOrderList().add(new int[]{ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });

        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        Song loaded = ProjectFile.load(file);

        assertEquals("Test Song", loaded.getName());
        assertEquals(SmpsMode.S3K, loaded.getSmpsMode());
        assertEquals(0xA0, loaded.getTempo());
        assertEquals(2, loaded.getDividingTiming());
        assertEquals(1, loaded.getLoopPoint());

        // Voices
        assertEquals(1, loaded.getVoiceBank().size());
        FmVoice loadedVoice = loaded.getVoiceBank().get(0);
        assertEquals("Lead", loadedVoice.getName());
        assertEquals(2, loadedVoice.getAlgorithm());
        assertEquals(6, loadedVoice.getFeedback());
        assertEquals(voiceData[1], loadedVoice.getData()[1]);

        // PSG envelopes
        assertEquals(1, loaded.getPsgEnvelopes().size());
        assertEquals("Pluck", loaded.getPsgEnvelopes().get(0).getName());
        assertEquals(6, loaded.getPsgEnvelopes().get(0).getStepCount());

        // Order list
        assertEquals(2, loaded.getOrderList().size());

        // Patterns - track data
        assertArrayEquals(
                new byte[]{ (byte) 0xEF, 0x00, (byte) 0xA1, 0x30, (byte) 0xF2 },
                loaded.getPatterns().get(0).getTrackData(0));
        assertArrayEquals(
                new byte[]{ (byte) 0xA1, 0x20 },
                loaded.getPatterns().get(0).getTrackData(6));
        // Empty channels should remain empty
        assertEquals(0, loaded.getPatterns().get(0).getTrackData(3).length);
    }

    @Test
    void testHexConversion() {
        byte[] data = { 0x00, (byte) 0xFF, 0x7F, (byte) 0x80 };
        String hex = HexUtil.bytesToHex(data);
        assertEquals("00 FF 7F 80", hex);
        assertArrayEquals(data, HexUtil.hexToBytes(hex));
    }

    @Test
    void testCorruptJsonThrows() {
        File file = new File(tempDir, "corrupt.osmpsd");
        assertThrows(Exception.class, () -> {
            Files.writeString(file.toPath(), "{ not valid json !!!");
            ProjectFile.load(file);
        });
    }

    @Test
    void testLoadRejectsFutureVersion() throws Exception {
        // Create a valid project file, then manually bump the version
        Song song = new Song();
        File file = File.createTempFile("test-version", ".osmpsd");
        file.deleteOnExit();
        ProjectFile.save(song, file);

        // Read the JSON, bump version to 999, write back
        String json = java.nio.file.Files.readString(file.toPath());
        json = json.replaceFirst("\"version\": 2", "\"version\": 999");
        java.nio.file.Files.writeString(file.toPath(), json);

        // Should throw IOException
        assertThrows(java.io.IOException.class, () -> ProjectFile.load(file));
    }

    @Test
    void testEmptyHexConversion() {
        assertEquals("", HexUtil.bytesToHex(new byte[0]));
        assertArrayEquals(new byte[0], HexUtil.hexToBytes(""));
        assertArrayEquals(new byte[0], HexUtil.hexToBytes("  "));
    }

    @Test
    void testSaveLoadEmptySong() throws IOException {
        // A default Song() with no voices, no PSG envelopes, no track data
        Song original = new Song();

        File file = new File(tempDir, "empty.osmpsd");
        ProjectFile.save(original, file);
        assertTrue(file.exists());

        Song loaded = ProjectFile.load(file);

        assertEquals("Untitled", loaded.getName());
        assertEquals(SmpsMode.S2, loaded.getSmpsMode());
        assertEquals(0x80, loaded.getTempo());
        assertEquals(1, loaded.getDividingTiming());
        assertEquals(0, loaded.getLoopPoint());
        assertTrue(loaded.getVoiceBank().isEmpty(), "Empty song should have no voices");
        assertTrue(loaded.getPsgEnvelopes().isEmpty(), "Empty song should have no PSG envelopes");
        assertEquals(1, loaded.getPatterns().size(), "Should have 1 pattern");
        assertEquals(1, loaded.getOrderList().size(), "Should have 1 order row");

        // All channels in the pattern should be empty
        Pattern p = loaded.getPatterns().get(0);
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            assertEquals(0, p.getTrackData(ch).length,
                    "Channel " + ch + " should be empty in default song");
        }

        // Empty song should have no DAC samples
        assertTrue(loaded.getDacSamples().isEmpty(), "Empty song should have no DAC samples");
    }

    @Test
    void testSaveLoadAllChannelsPopulated() throws IOException {
        Song original = new Song();
        original.setName("Full");
        original.setSmpsMode(SmpsMode.S3K);
        original.setTempo(0xC0);
        original.setDividingTiming(3);

        // Populate all 10 channels with distinct byte data
        byte[][] channelData = new byte[Pattern.CHANNEL_COUNT][];
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            // Each channel gets unique 4-byte data: [ch, ch+0x80, ch+0x90, ch+0xA0]
            channelData[ch] = new byte[]{
                    (byte) (0x81 + ch), // valid note byte per channel
                    (byte) (0x18 + ch), // unique duration
                    (byte) (0x81 + ch + 1 < 0xE0 ? 0x81 + ch + 1 : 0xBD), // second note
                    (byte) 0x30
            };
            original.getPatterns().get(0).setTrackData(ch, channelData[ch]);
        }

        File file = new File(tempDir, "full.osmpsd");
        ProjectFile.save(original, file);

        Song loaded = ProjectFile.load(file);

        assertEquals("Full", loaded.getName());
        assertEquals(SmpsMode.S3K, loaded.getSmpsMode());
        assertEquals(0xC0, loaded.getTempo());
        assertEquals(3, loaded.getDividingTiming());

        // Verify each channel byte-for-byte
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            byte[] loadedTrack = loaded.getPatterns().get(0).getTrackData(ch);
            assertArrayEquals(channelData[ch], loadedTrack,
                    "Channel " + ch + " data should match byte-for-byte");
        }
    }

    @Test
    void testSaveLoadDacSamples(@TempDir Path dacTempDir) throws Exception {
        Song song = new Song();
        song.getDacSamples().add(new DacSample("Kick", new byte[]{(byte) 0x80, 0x7F, 0x60}, 0x0C));
        song.getDacSamples().add(new DacSample("Snare", new byte[]{0x40, (byte) 0xC0}, 0x10));

        File file = dacTempDir.resolve("dac-test.osmpsd").toFile();
        ProjectFile.save(song, file);
        Song loaded = ProjectFile.load(file);

        assertEquals(2, loaded.getDacSamples().size());
        assertEquals("Kick", loaded.getDacSamples().get(0).getName());
        assertEquals(0x0C, loaded.getDacSamples().get(0).getRate());
        assertArrayEquals(new byte[]{(byte) 0x80, 0x7F, 0x60}, loaded.getDacSamples().get(0).getData());
        assertEquals("Snare", loaded.getDacSamples().get(1).getName());
        assertEquals(0x10, loaded.getDacSamples().get(1).getRate());
        assertArrayEquals(new byte[]{0x40, (byte) 0xC0}, loaded.getDacSamples().get(1).getData());
    }

    @Test
    void testLoadBackwardCompatNoDacSamples(@TempDir Path compatTempDir) throws Exception {
        // Create a project file, then strip the dacSamples field to simulate an old file
        Song song = new Song();
        File file = compatTempDir.resolve("old-format.osmpsd").toFile();
        ProjectFile.save(song, file);

        // Remove the dacSamples key from the JSON
        String json = Files.readString(file.toPath());
        json = json.replaceAll(",?\\s*\"dacSamples\":\\s*\\[\\s*\\]", "");
        Files.writeString(file.toPath(), json);

        Song loaded = ProjectFile.load(file);
        assertTrue(loaded.getDacSamples().isEmpty(),
                "Loading an old file without dacSamples should result in empty list");
    }

    @Test
    void testSaveLoadStructuredArrangement(@TempDir Path structuredTempDir) throws Exception {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.STRUCTURED_BLOCKS);

        StructuredArrangement structured = new StructuredArrangement();
        structured.setTicksPerRow(3);
        BlockDefinition block = new BlockDefinition(10, "A", 24);
        block.setTrackData(0, new byte[] { (byte) 0xA1, 0x18 });
        structured.getBlocks().add(block);
        BlockRef ref = new BlockRef(10, 12);
        ref.setRepeatCount(2);
        ref.setTransposeSemitones(1);
        structured.getChannels().get(0).getBlockRefs().add(ref);
        song.setStructuredArrangement(structured);

        File file = structuredTempDir.resolve("structured.osmpsd").toFile();
        ProjectFile.save(song, file);

        Song loaded = ProjectFile.load(file);
        assertEquals(ArrangementMode.STRUCTURED_BLOCKS, loaded.getArrangementMode());
        assertNotNull(loaded.getStructuredArrangement());
        assertEquals(3, loaded.getStructuredArrangement().getTicksPerRow());
        assertEquals(1, loaded.getStructuredArrangement().getBlocks().size());
        assertEquals(10, loaded.getStructuredArrangement().getBlocks().get(0).getId());
        assertEquals(1, loaded.getStructuredArrangement().getChannels().get(0).getBlockRefs().size());
        BlockRef loadedRef = loaded.getStructuredArrangement().getChannels().get(0).getBlockRefs().get(0);
        assertEquals(10, loadedRef.getBlockId());
        assertEquals(12, loadedRef.getStartTick());
        assertEquals(2, loadedRef.getRepeatCount());
        assertEquals(1, loadedRef.getTransposeSemitones());
    }
}
