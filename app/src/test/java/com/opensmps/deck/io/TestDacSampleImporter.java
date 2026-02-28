package com.opensmps.deck.io;

import com.opensmps.deck.model.DacSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class TestDacSampleImporter {

    @TempDir
    File tempDir;

    @Test
    void importRawPcm() throws IOException {
        byte[] rawData = {0x10, 0x20, (byte) 0x80, (byte) 0xFF};
        File pcmFile = new File(tempDir, "kick.pcm");
        Files.write(pcmFile.toPath(), rawData);

        DacSample sample = DacSampleImporter.importFile(pcmFile, 0x1A);

        assertEquals("kick", sample.getName());
        assertEquals(0x1A, sample.getRate());
        assertArrayEquals(rawData, sample.getData());
    }

    @Test
    void importWavConvertsTo8BitUnsigned() throws IOException {
        // 4 samples of 16-bit signed mono PCM: 0, 16384, -16384, 0
        short[] samples = {0, 16384, -16384, 0};

        File wavFile = new File(tempDir, "test.wav");
        writeMinimalWav(wavFile, samples, 1, 22050, 16);

        DacSample sample = DacSampleImporter.importFile(wavFile, 0x05);

        assertEquals("test", sample.getName());
        assertEquals(0x05, sample.getRate());

        // Expected conversion: (sample >> 8) + 128
        // 0 >> 8 = 0      + 128 = 128
        // 16384 >> 8 = 64  + 128 = 192
        // -16384 >> 8 = -64 + 128 = 64
        // 0 >> 8 = 0       + 128 = 128
        byte[] expected = {(byte) 128, (byte) 192, (byte) 64, (byte) 128};
        assertArrayEquals(expected, sample.getData());
    }

    /**
     * Writes a minimal valid WAV file with the given 16-bit signed PCM samples.
     */
    private void writeMinimalWav(File file, short[] samples, int channels,
                                  int sampleRate, int bitsPerSample) throws IOException {
        int bytesPerSample = bitsPerSample / 8;
        int blockAlign = channels * bytesPerSample;
        int byteRate = sampleRate * blockAlign;
        int dataSize = samples.length * bytesPerSample;

        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataSize);          // file size - 8
        buf.put("WAVE".getBytes());

        // fmt chunk
        buf.put("fmt ".getBytes());
        buf.putInt(16);                     // chunk size
        buf.putShort((short) 1);            // PCM format
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitsPerSample);

        // data chunk
        buf.put("data".getBytes());
        buf.putInt(dataSize);
        for (short s : samples) {
            buf.putShort(s);
        }

        Files.write(file.toPath(), buf.array());
    }
}
