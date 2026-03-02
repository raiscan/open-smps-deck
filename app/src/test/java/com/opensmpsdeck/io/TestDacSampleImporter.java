package com.opensmpsdeck.io;

import com.opensmpsdeck.model.DacSample;
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

    @Test
    void importStereoWavMixesToMono() throws IOException {
        // Interleaved stereo 16-bit PCM:
        // Frame 0: L=32767, R=-32768 -> average ~0 -> 128 unsigned
        // Frame 1: L=32767, R=-32768 -> average ~0 -> 128 unsigned
        short[] interleaved = {(short) 32767, (short) -32768, (short) 32767, (short) -32768};

        File wavFile = new File(tempDir, "stereo.wav");
        writeMinimalWav(wavFile, interleaved, 2, 22050, 16);

        DacSample sample = DacSampleImporter.importFile(wavFile, 0x08);
        assertArrayEquals(new byte[]{(byte) 128, (byte) 128}, sample.getData());
    }

    @Test
    void testImportStereoWavAveragesChannelsNotFirstChannelOnly() throws IOException {
        // Stereo 16-bit WAV: 2 frames, 2 channels each
        // Frame 0: L=32767 (127), R=0 (0) -> avg=63 -> +128 = 191
        // Frame 1: L=-32768 (-128), R=0 (0) -> avg=-64 -> +128 = 64
        // A first-channel-only implementation would produce 255 and 0, so this
        // specifically guards the channel-averaging behavior.
        short[] interleaved = {(short) 32767, 0, (short) -32768, 0};

        File wavFile = new File(tempDir, "stereo3.wav");
        writeMinimalWav(wavFile, interleaved, 2, 22050, 16);

        DacSample sample = DacSampleImporter.importFile(wavFile, 0x0C);

        assertEquals(2, sample.getData().length,
                "Stereo WAV with 2 frames should produce 2 mono samples");
        byte[] expected = {(byte) 191, (byte) 64};
        assertArrayEquals(expected, sample.getData());
    }

    @Test
    void testImport8BitWavPassthrough() throws IOException {
        // 8-bit unsigned WAV: values 0, 128, 255
        // readSignedSample for unsigned 8-bit: v - 128
        // Then: mixedSigned + 128 = v (passthrough for unsigned 8-bit mono)
        File wavFile = new File(tempDir, "eightbit.wav");
        write8BitWav(wavFile, new byte[]{0x00, (byte) 0x80, (byte) 0xFF}, 1, 22050);

        DacSample sample = DacSampleImporter.importFile(wavFile, 0x10);

        assertEquals("eightbit", sample.getName());
        // unsigned 8-bit: 0 -> signed -128 -> +128 -> 0
        // unsigned 8-bit: 128 -> signed 0 -> +128 -> 128
        // unsigned 8-bit: 255 -> signed 127 -> +128 -> 255
        byte[] expected = {0x00, (byte) 0x80, (byte) 0xFF};
        assertArrayEquals(expected, sample.getData());
    }

    @Test
    void testImportEmptyRawFile() throws IOException {
        File pcmFile = new File(tempDir, "empty.pcm");
        Files.write(pcmFile.toPath(), new byte[0]);

        DacSample sample = DacSampleImporter.importFile(pcmFile, 0x05);

        assertEquals("empty", sample.getName());
        assertEquals(0, sample.getData().length,
                "Empty raw file should produce empty sample data");
    }

    @Test
    void testImportCorruptWavThrows() throws IOException {
        File wavFile = new File(tempDir, "corrupt.wav");
        // Write garbage bytes that are not a valid RIFF/WAV header
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                          0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};
        Files.write(wavFile.toPath(), garbage);

        assertThrows(IOException.class,
                () -> DacSampleImporter.importFile(wavFile, 0x05),
                "Corrupt WAV file should throw IOException");
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

    /**
     * Writes a minimal valid 8-bit unsigned PCM WAV file.
     */
    private void write8BitWav(File file, byte[] samples, int channels,
                               int sampleRate) throws IOException {
        int bitsPerSample = 8;
        int bytesPerSample = 1;
        int blockAlign = channels * bytesPerSample;
        int byteRate = sampleRate * blockAlign;
        int dataSize = samples.length;

        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes());

        // fmt chunk
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);            // PCM format
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitsPerSample);

        // data chunk
        buf.put("data".getBytes());
        buf.putInt(dataSize);
        buf.put(samples);

        Files.write(file.toPath(), buf.array());
    }
}
