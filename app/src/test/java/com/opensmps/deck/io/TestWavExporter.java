package com.opensmps.deck.io;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class TestWavExporter {

    @TempDir
    File tempDir;

    @Test
    void testExportProducesValidWavFile() throws IOException {
        Song song = createTestSong();
        File wavFile = new File(tempDir, "test.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(1); // 1 second max to keep test fast
        exporter.export(song, wavFile);

        assertTrue(wavFile.exists(), "WAV file should be created");
        assertTrue(wavFile.length() > 44, "WAV file should have audio data beyond the 44-byte header");

        // Read and verify WAV header markers
        byte[] data = Files.readAllBytes(wavFile.toPath());
        String riff = new String(data, 0, 4);
        assertEquals("RIFF", riff, "File should start with RIFF marker");

        String wave = new String(data, 8, 4);
        assertEquals("WAVE", wave, "File should contain WAVE marker");

        String fmt = new String(data, 12, 4);
        assertEquals("fmt ", fmt, "File should contain fmt  marker");
    }

    @Test
    void testExportSampleRateIs44100() throws IOException {
        Song song = createTestSong();
        File wavFile = new File(tempDir, "test-rate.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(1);
        exporter.export(song, wavFile);

        // Sample rate is at byte offset 24 in the WAV header (little-endian 32-bit)
        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            raf.seek(24);
            int b0 = raf.read();
            int b1 = raf.read();
            int b2 = raf.read();
            int b3 = raf.read();
            int sampleRate = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            assertEquals(44100, sampleRate, "Sample rate should be 44100 Hz");
        }
    }

    @Test
    void testLoopCountGetterSetter() {
        WavExporter exporter = new WavExporter();
        assertEquals(2, exporter.getLoopCount(), "Default loop count should be 2");

        exporter.setLoopCount(5);
        assertEquals(5, exporter.getLoopCount(), "Loop count should be settable");

        exporter.setLoopCount(0);
        assertEquals(1, exporter.getLoopCount(), "Loop count should be clamped to minimum 1");

        exporter.setLoopCount(-3);
        assertEquals(1, exporter.getLoopCount(), "Negative loop count should be clamped to 1");
    }

    @Test
    void testExportStereo16Bit() throws IOException {
        Song song = createTestSong();
        File wavFile = new File(tempDir, "test-fmt.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(1);
        exporter.export(song, wavFile);

        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            // Audio format (PCM=1) at offset 20, LE 16-bit
            raf.seek(20);
            int format = raf.read() | (raf.read() << 8);
            assertEquals(1, format, "Audio format should be PCM (1)");

            // Number of channels at offset 22, LE 16-bit
            int channels = raf.read() | (raf.read() << 8);
            assertEquals(2, channels, "Should be stereo (2 channels)");

            // Skip sample rate (4 bytes) + byte rate (4 bytes) = skip to offset 32
            raf.seek(34);
            int bitsPerSample = raf.read() | (raf.read() << 8);
            assertEquals(16, bitsPerSample, "Should be 16-bit audio");
        }
    }

    /**
     * Creates a minimal song with one FM channel playing a short note.
     * This produces valid SMPS data via PatternCompiler.
     */
    private Song createTestSong() {
        Song song = new Song();
        song.setTempo(0x80);

        // Simple FM voice: algo 0, op4 as carrier
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x00;   // algo 0, fb 0
        voiceData[2] = 0x7F;   // Op1 TL (silent)
        voiceData[7] = 0x7F;   // Op3 TL (silent)
        voiceData[12] = 0x7F;  // Op2 TL (silent)
        voiceData[16] = 0x01;  // Op4 DT_MUL
        voiceData[17] = 0x00;  // Op4 TL (loud)
        voiceData[18] = 0x1F;  // Op4 RS_AR (AR=31)
        voiceData[21] = 0x0F;  // Op4 D1L_RR (RR=15)
        song.getVoiceBank().add(new FmVoice("Sine", voiceData));

        // Set voice (EF 00), play note C4 (A1) duration 48 (30h)
        song.getPatterns().get(0).setTrackData(0,
                new byte[]{(byte) 0xEF, 0x00, (byte) 0xA1, 0x30});

        return song;
    }
}
