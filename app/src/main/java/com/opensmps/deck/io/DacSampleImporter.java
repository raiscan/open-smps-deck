package com.opensmps.deck.io;

import com.opensmps.deck.model.DacSample;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Imports DAC samples from WAV files or raw PCM files.
 *
 * <p>WAV files are automatically converted to unsigned 8-bit mono PCM,
 * which is the native format for the Mega Drive Z80 DAC driver.
 * Non-WAV files ({@code .pcm}, {@code .bin}, etc.) are read as raw
 * unsigned 8-bit PCM data.
 */
public final class DacSampleImporter {

    private DacSampleImporter() {
        // Utility class
    }

    /**
     * Import a file as a DAC sample.
     *
     * <p>If the file has a {@code .wav} extension, the WAV audio is parsed
     * and converted to unsigned 8-bit mono PCM. Otherwise the file bytes
     * are read verbatim as raw unsigned 8-bit PCM.
     *
     * @param file the audio file to import
     * @param rate the playback rate byte for the Z80 DAC driver
     * @return the imported DAC sample
     * @throws IOException if the file cannot be read or parsed
     */
    public static DacSample importFile(File file, int rate) throws IOException {
        String filename = file.getName();
        String name = filename.contains(".")
                ? filename.substring(0, filename.lastIndexOf('.'))
                : filename;

        byte[] data;
        if (filename.toLowerCase().endsWith(".wav")) {
            data = convertWavToUnsigned8Bit(file);
        } else {
            data = java.nio.file.Files.readAllBytes(file.toPath());
        }

        return new DacSample(name, data, rate);
    }

    /**
     * Parse a WAV file and convert to unsigned 8-bit mono PCM.
     *
     * <p>Handles stereo to mono conversion (first channel only),
     * 16-bit signed to 8-bit unsigned conversion, and both
     * big-endian and little-endian formats.
     */
    private static byte[] convertWavToUnsigned8Bit(File file) throws IOException {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
            AudioFormat fmt = ais.getFormat();
            int channels = fmt.getChannels();
            int sampleSizeInBits = fmt.getSampleSizeInBits();
            boolean bigEndian = fmt.isBigEndian();
            int bytesPerSample = sampleSizeInBits / 8;
            int frameSize = channels * bytesPerSample;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] frame = new byte[frameSize];

            while (ais.read(frame) == frameSize) {
                // Take only the first channel (bytes 0..bytesPerSample-1)
                int unsigned8;
                if (sampleSizeInBits == 16) {
                    int signed16 = read16BitSample(frame, 0, bigEndian);
                    unsigned8 = (signed16 >> 8) + 128;
                } else {
                    // 8-bit: pass through as-is
                    unsigned8 = frame[0] & 0xFF;
                }
                out.write(unsigned8);
            }

            return out.toByteArray();
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException("Unsupported WAV format: " + file.getName(), e);
        }
    }

    /**
     * Read a 16-bit signed sample from a byte array at the given offset.
     */
    private static int read16BitSample(byte[] data, int offset, boolean bigEndian) {
        if (bigEndian) {
            return (short) ((data[offset] << 8) | (data[offset + 1] & 0xFF));
        } else {
            return (short) ((data[offset + 1] << 8) | (data[offset] & 0xFF));
        }
    }
}
