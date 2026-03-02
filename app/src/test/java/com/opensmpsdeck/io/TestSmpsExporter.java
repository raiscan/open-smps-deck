package com.opensmpsdeck.io;

import com.opensmpsdeck.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class TestSmpsExporter {

    @TempDir
    File tempDir;

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

    private static void setLoopOnActiveChains(Song song, int entryIndex) {
        var arr = song.getHierarchicalArrangement();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            var chain = arr.getChain(ch);
            if (!chain.getEntries().isEmpty() && entryIndex < chain.getEntries().size()) {
                chain.setLoopEntryIndex(entryIndex);
            }
        }
    }

    @Test
    void testExportWritesValidFile() throws IOException {
        Song song = new Song();
        song.setTempo(0x80);
        song.getVoiceBank().add(new FmVoice("Test", new byte[25]));
        addPhrase(song, 0,
                new byte[]{(byte) 0xEF, 0x00, (byte) 0xA1, 0x30, (byte) 0xF2});

        File file = new File(tempDir, "test.bin");
        new SmpsExporter().export(song, file);

        assertTrue(file.exists());
        byte[] data = Files.readAllBytes(file.toPath());
        assertTrue(data.length > 0);

        // Verify it starts with a valid SMPS header
        // fmCount=2 because dummy DAC entry is inserted when FM channels are active
        assertEquals(2, data[2] & 0xFF, "2 FM channels (dummy DAC + FM1)");
        assertEquals(0, data[3] & 0xFF, "0 PSG channels");
        assertEquals(0x80, data[5] & 0xFF, "Tempo");
    }

    @Test
    void testCompileMatchesExport() throws IOException {
        Song song = new Song();
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));
        addPhrase(song, 0,
                new byte[]{(byte) 0xA1, 0x30});

        SmpsExporter exporter = new SmpsExporter();
        byte[] compiled = exporter.compile(song);

        File file = new File(tempDir, "test2.bin");
        exporter.export(song, file);
        byte[] fromFile = Files.readAllBytes(file.toPath());

        assertArrayEquals(compiled, fromFile);
    }

    @Test
    void testExportContainsVoiceData() throws IOException {
        Song song = new Song();
        song.setTempo(120);
        song.setDividingTiming(1);
        // Add an FM voice
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32; // algo=2, fb=6
        song.getVoiceBank().add(new FmVoice("Lead", voiceData));
        // Add some FM1 data
        addPhrase(song, 0,
                new byte[]{(byte) 0x80, 0x30});

        File file = new File(tempDir, "test-voice.bin");
        new SmpsExporter().export(song, file);

        byte[] exported = Files.readAllBytes(file.toPath());
        // Voice pointer is at bytes 0-1 (LE)
        int voicePtr = (exported[0] & 0xFF) | ((exported[1] & 0xFF) << 8);
        assertTrue(voicePtr > 0, "Voice pointer should be non-zero");
        assertTrue(voicePtr + 25 <= exported.length, "Voice data should fit within exported file");
        // Verify the voice data at the pointer offset matches
        assertEquals(0x32, exported[voicePtr] & 0xFF, "First voice byte (FB_ALG) should match");
    }

    @Test
    void testExportSongWithPsgChannel() throws IOException {
        Song song = new Song();
        song.setTempo(0x70);

        // Add an FM voice so FM1 can reference it
        song.getVoiceBank().add(new FmVoice("FM", new byte[25]));

        // Add a PSG envelope to the song
        byte[] envData = {0x00, 0x01, 0x02, 0x03, (byte) 0x80}; // 4 steps + terminator
        song.getPsgEnvelopes().add(new PsgEnvelope("Env1", envData));

        // FM1 (channel 0): simple note
        addPhrase(song, 0,
                new byte[]{(byte) 0xEF, 0x00, (byte) 0xA0, 0x30});

        // PSG1 (channel 6): PSG instrument select + note
        addPhrase(song, 6,
                new byte[]{(byte) 0xF5, 0x00, (byte) 0xA0, 0x30});

        File file = new File(tempDir, "test-psg.bin");
        new SmpsExporter().export(song, file);

        byte[] data = Files.readAllBytes(file.toPath());
        assertTrue(data.length > 0, "Exported file should be non-empty");

        // Header: byte[2]=FM count, byte[3]=PSG count
        // fmCount=2 because dummy DAC entry is inserted when FM channels are active
        assertEquals(2, data[2] & 0xFF, "2 FM channels (dummy DAC + FM1)");
        assertEquals(1, data[3] & 0xFF, "1 PSG channel");
        // Verify the PSG instrument command (F5 00) appears in the track data
        boolean foundPsgInstrument = false;
        for (int i = 0; i < data.length - 1; i++) {
            if ((data[i] & 0xFF) == 0xF5 && (data[i + 1] & 0xFF) == 0x00) {
                foundPsgInstrument = true;
                break;
            }
        }
        assertTrue(foundPsgInstrument, "Exported binary should contain PSG instrument command (F5 00)");
    }

    @Test
    void testExportSongWithDacSamples() throws IOException {
        Song song = new Song();
        song.setTempo(0x80);

        // Add a DAC sample to the song model
        byte[] sampleData = new byte[]{0x40, 0x50, 0x60, 0x70};
        song.getDacSamples().add(new DacSample("Kick", sampleData, 0x0C));

        // DAC channel is channel 5. DAC note 0x81 = first DAC sample.
        addPhrase(song, 5,
                new byte[]{(byte) 0x81, 0x30});

        File file = new File(tempDir, "test-dac.bin");
        new SmpsExporter().export(song, file);

        byte[] data = Files.readAllBytes(file.toPath());
        assertTrue(data.length > 0, "Exported file should be non-empty");

        // Header should show 1 FM channel (DAC counts as FM channel 6)
        assertEquals(1, data[2] & 0xFF, "DAC channel counted as 1 FM channel");

        // Verify the DAC note byte 0x81 appears in the track data section
        boolean foundDacNote = false;
        // Skip the header (6 base + 4 per FM = 10 bytes minimum) to look in track data
        for (int i = 6; i < data.length; i++) {
            if ((data[i] & 0xFF) == 0x81) {
                foundDacNote = true;
                break;
            }
        }
        assertTrue(foundDacNote, "Exported binary should contain DAC note byte 0x81");
    }

    @Test
    void testExportMultiPatternSong() throws IOException {
        Song song = new Song();
        song.setTempo(0x80);
        song.getVoiceBank().add(new FmVoice("V", new byte[25]));

        // First phrase on FM1
        addPhrase(song, 0,
                new byte[]{(byte) 0xEF, 0x00, (byte) 0xA0, 0x18});

        // Second phrase on FM1 with different notes
        addPhrase(song, 0,
                new byte[]{(byte) 0xA4, 0x30, (byte) 0xA8, 0x30});

        File file = new File(tempDir, "test-multi.bin");
        new SmpsExporter().export(song, file);
        byte[] multiData = Files.readAllBytes(file.toPath());

        // Now export a single-phrase version for size comparison
        Song singleSong = new Song();
        singleSong.setTempo(0x80);
        singleSong.getVoiceBank().add(new FmVoice("V", new byte[25]));
        addPhrase(singleSong, 0,
                new byte[]{(byte) 0xEF, 0x00, (byte) 0xA0, 0x18});

        File singleFile = new File(tempDir, "test-single.bin");
        new SmpsExporter().export(singleSong, singleFile);
        byte[] singleData = Files.readAllBytes(singleFile.toPath());

        assertTrue(multiData.length > singleData.length,
                "Multi-phrase export (" + multiData.length + " bytes) should be larger than " +
                        "single-phrase export (" + singleData.length + " bytes)");

        // Verify both phrase note bytes appear in the multi-phrase binary
        boolean foundA0 = false;
        boolean foundA4 = false;
        boolean foundA8 = false;
        for (byte b : multiData) {
            if ((b & 0xFF) == 0xA0) foundA0 = true;
            if ((b & 0xFF) == 0xA4) foundA4 = true;
            if ((b & 0xFF) == 0xA8) foundA8 = true;
        }
        assertTrue(foundA0, "Should contain note 0xA0 from phrase 1");
        assertTrue(foundA4, "Should contain note 0xA4 from phrase 2");
        assertTrue(foundA8, "Should contain note 0xA8 from phrase 2");
    }

    @Test
    void testExportEmptySongProducesValidBinary() throws IOException {
        Song song = new Song();
        song.setTempo(0x60);

        // Song() creates empty chains by default in hierarchical mode,
        // so no data means no active channels.

        File file = new File(tempDir, "test-empty.bin");
        // Should not throw
        new SmpsExporter().export(song, file);

        assertTrue(file.exists(), "File should be created");
        byte[] data = Files.readAllBytes(file.toPath());
        // With no active channels, we get just the 6-byte base header
        assertTrue(data.length >= 6, "Should contain at least the base SMPS header");
        assertEquals(0, data[2] & 0xFF, "0 FM channels");
        assertEquals(0, data[3] & 0xFF, "0 PSG channels");
        assertEquals(0x60, data[5] & 0xFF, "Tempo should be preserved");
    }
}
