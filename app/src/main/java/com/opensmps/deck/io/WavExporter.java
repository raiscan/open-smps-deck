package com.opensmps.deck.io;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.model.Song;

import java.io.*;

/**
 * Exports a Song to WAV format by offline rendering through the SMPS driver.
 * Supports configurable loop count with linear fade-out on the final loop.
 */
public class WavExporter {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int DEFAULT_MAX_DURATION_SECONDS = 600;
    private static final int BUFFER_FRAMES = 1024;

    private int loopCount = 2;
    private int maxDurationSeconds = DEFAULT_MAX_DURATION_SECONDS;

    public void setLoopCount(int loopCount) {
        this.loopCount = Math.max(1, loopCount);
    }

    public int getLoopCount() {
        return loopCount;
    }

    /**
     * Set the maximum total render duration in seconds.
     * Defaults to 600 seconds. Useful for testing with shorter limits.
     */
    public void setMaxDurationSeconds(int seconds) {
        this.maxDurationSeconds = Math.max(1, seconds);
    }

    /**
     * Export the given song as a WAV audio file.
     *
     * <p>Renders the song through the SMPS driver {@code loopCount} times.
     * On the final loop (when {@code loopCount > 1}), a linear fade-out is
     * applied so the audio tapers to silence rather than cutting off abruptly.
     *
     * @param song       the song to export
     * @param outputFile the destination WAV file
     * @throws IOException if writing the file fails
     */
    public void export(Song song, File outputFile) throws IOException {
        PlaybackEngine engine = new PlaybackEngine();

        ByteArrayOutputStream pcmData = new ByteArrayOutputStream();
        int maxFrames = SAMPLE_RATE * maxDurationSeconds;
        int totalFrames = 0;
        int fadeStartFrame = -1;
        int fadeLengthFrames = 0;

        for (int loop = 0; loop < loopCount; loop++) {
            engine.loadSong(song);
            short[] buffer = new short[BUFFER_FRAMES * CHANNELS];
            int loopStartFrame = totalFrames;

            while (totalFrames < maxFrames) {
                engine.renderBuffer(buffer);
                if (engine.getDriver().isComplete()) break;

                for (short sample : buffer) {
                    pcmData.write(sample & 0xFF);
                    pcmData.write((sample >> 8) & 0xFF);
                }
                totalFrames += BUFFER_FRAMES;
            }

            if (loop == loopCount - 1 && loopCount > 1) {
                fadeStartFrame = loopStartFrame;
                fadeLengthFrames = totalFrames - loopStartFrame;
            }
        }

        byte[] pcm = pcmData.toByteArray();

        // Apply fade-out on final loop
        if (fadeStartFrame >= 0 && fadeLengthFrames > 0) {
            applyFadeOut(pcm, fadeStartFrame, fadeLengthFrames);
        }

        // Write WAV file
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {
            writeWavHeader(dos, pcm.length);
            dos.write(pcm);
        }
    }

    private void applyFadeOut(byte[] pcm, int fadeStartFrame, int fadeLengthFrames) {
        int bytesPerFrame = CHANNELS * (BITS_PER_SAMPLE / 8);
        int startByte = fadeStartFrame * bytesPerFrame;
        int totalFadeSamples = fadeLengthFrames * CHANNELS;

        for (int i = 0; i < totalFadeSamples; i++) {
            int bytePos = startByte + i * 2;
            if (bytePos + 1 >= pcm.length) break;

            short sample = (short) ((pcm[bytePos] & 0xFF) | (pcm[bytePos + 1] << 8));
            float progress = (float) (i / CHANNELS) / fadeLengthFrames;
            float gain = 1.0f - progress;
            sample = (short) (sample * gain);
            pcm[bytePos] = (byte) (sample & 0xFF);
            pcm[bytePos + 1] = (byte) ((sample >> 8) & 0xFF);
        }
    }

    private void writeWavHeader(DataOutputStream dos, int dataSize) throws IOException {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;

        // RIFF header
        dos.writeBytes("RIFF");
        writeLittleEndianInt(dos, 36 + dataSize);
        dos.writeBytes("WAVE");

        // fmt chunk
        dos.writeBytes("fmt ");
        writeLittleEndianInt(dos, 16);
        writeLittleEndianShort(dos, (short) 1);     // PCM format
        writeLittleEndianShort(dos, (short) CHANNELS);
        writeLittleEndianInt(dos, SAMPLE_RATE);
        writeLittleEndianInt(dos, byteRate);
        writeLittleEndianShort(dos, (short) blockAlign);
        writeLittleEndianShort(dos, (short) BITS_PER_SAMPLE);

        // data chunk header
        dos.writeBytes("data");
        writeLittleEndianInt(dos, dataSize);
    }

    private void writeLittleEndianInt(DataOutputStream dos, int value) throws IOException {
        dos.write(value & 0xFF);
        dos.write((value >> 8) & 0xFF);
        dos.write((value >> 16) & 0xFF);
        dos.write((value >> 24) & 0xFF);
    }

    private void writeLittleEndianShort(DataOutputStream dos, short value) throws IOException {
        dos.write(value & 0xFF);
        dos.write((value >> 8) & 0xFF);
    }
}
