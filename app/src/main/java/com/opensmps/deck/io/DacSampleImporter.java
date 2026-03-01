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
     * <p>Handles stereo to mono conversion (channel average),
     * 8/16-bit signed or unsigned PCM conversion, and both
     * big-endian and little-endian formats.
     */
    private static byte[] convertWavToUnsigned8Bit(File file) throws IOException {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
            AudioFormat fmt = ais.getFormat();
            int channels = fmt.getChannels();
            int sampleSizeInBits = fmt.getSampleSizeInBits();
            AudioFormat.Encoding encoding = fmt.getEncoding();
            boolean bigEndian = fmt.isBigEndian();
            int bytesPerSample = sampleSizeInBits / 8;
            int frameSize = channels * bytesPerSample;

            if (channels <= 0) {
                throw new IOException("Invalid WAV channel count: " + channels);
            }
            if (sampleSizeInBits != 8 && sampleSizeInBits != 16) {
                throw new IOException("Unsupported WAV bit depth: " + sampleSizeInBits);
            }
            if (!(AudioFormat.Encoding.PCM_SIGNED.equals(encoding)
                    || AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding))) {
                throw new IOException("Unsupported WAV encoding: " + encoding);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] frame = new byte[frameSize];

            while (readWholeFrame(ais, frame, frameSize)) {
                int mixedSigned = 0;
                for (int ch = 0; ch < channels; ch++) {
                    int sampleOffset = ch * bytesPerSample;
                    mixedSigned += readSignedSample(
                            frame,
                            sampleOffset,
                            sampleSizeInBits,
                            bigEndian,
                            AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding));
                }
                mixedSigned /= channels;
                int unsigned8 = Math.max(0, Math.min(255, mixedSigned + 128));
                out.write(unsigned8);
            }

            return out.toByteArray();
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException("Unsupported WAV format: " + file.getName(), e);
        }
    }

    /**
     * Reads a whole frame. Returns false on EOF/short trailing frame.
     */
    private static boolean readWholeFrame(AudioInputStream ais, byte[] frame, int frameSize) throws IOException {
        int read = ais.readNBytes(frame, 0, frameSize);
        return read == frameSize;
    }

    /**
     * Reads one sample and normalizes it to signed 8-bit centered range.
     */
    private static int readSignedSample(byte[] data, int offset, int bits, boolean bigEndian, boolean unsigned) {
        if (bits == 8) {
            int v = data[offset] & 0xFF;
            return unsigned ? (v - 128) : (byte) v;
        }

        int signed16 = read16BitSample(data, offset, bigEndian, unsigned);
        return signed16 >> 8;
    }

    /**
     * Reads a 16-bit sample from a byte array at the given offset.
     */
    private static int read16BitSample(byte[] data, int offset, boolean bigEndian, boolean unsigned) {
        int raw;
        if (bigEndian) {
            raw = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        } else {
            raw = ((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF);
        }
        return unsigned ? (raw - 32768) : (short) raw;
    }
}
