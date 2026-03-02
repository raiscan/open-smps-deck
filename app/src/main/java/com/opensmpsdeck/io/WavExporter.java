package com.opensmpsdeck.io;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.model.Song;

import java.io.*;

/**
 * Exports a Song to WAV format by offline rendering through the SMPS driver.
 * Supports configurable loop count and three fade-out modes: extend (renders
 * additional audio beyond the loops with a linear fade), inset (applies a
 * linear fade to the tail of the already-rendered PCM), or disabled (no fade).
 */
public class WavExporter {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int DEFAULT_MAX_DURATION_SECONDS = 600;
    private static final int BUFFER_FRAMES = 1024;

    private int loopCount = 2;
    private int maxDurationSeconds = DEFAULT_MAX_DURATION_SECONDS;
    private boolean[] mutedChannels;
    private boolean fadeEnabled = true;
    private double fadeDurationSeconds = 3.0;
    private boolean fadeExtend = true;

    public void setMutedChannels(boolean[] mutedChannels) {
        this.mutedChannels = mutedChannels != null ? mutedChannels.clone() : null;
    }

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

    public void setFadeEnabled(boolean fadeEnabled) {
        this.fadeEnabled = fadeEnabled;
    }

    public boolean isFadeEnabled() {
        return fadeEnabled;
    }

    /**
     * Set the fade-out duration in seconds. Values below 0.1 are clamped to 0.1.
     */
    public void setFadeDurationSeconds(double seconds) {
        this.fadeDurationSeconds = Math.max(0.1, seconds);
    }

    public double getFadeDurationSeconds() {
        return fadeDurationSeconds;
    }

    /**
     * Set whether the fade extends beyond the rendered loops (true) or is
     * applied within the last portion of the rendered audio (false, inset mode).
     */
    public void setFadeExtend(boolean extend) {
        this.fadeExtend = extend;
    }

    public boolean isFadeExtend() {
        return fadeExtend;
    }

    /**
     * Export the given song as a WAV audio file.
     *
     * <p>Renders the song through the SMPS driver {@code loopCount} times.
     * When {@code fadeEnabled} is true, a linear fade-out is applied according
     * to the configured mode:
     * <ul>
     *   <li><b>Extend mode</b> ({@code fadeExtend=true}): renders additional
     *       {@code fadeDurationSeconds} of audio after the loops complete,
     *       applying a linear fade per buffer during the extension.</li>
     *   <li><b>Inset mode</b> ({@code fadeExtend=false}): applies a linear
     *       fade-out to the last {@code fadeDurationSeconds} of the already
     *       rendered PCM data.</li>
     * </ul>
     * When {@code fadeEnabled} is false, no fade processing is applied.
     *
     * @param song       the song to export
     * @param outputFile the destination WAV file
     * @throws IOException if writing the file fails
     */
    public void export(Song song, File outputFile) throws IOException {
        PlaybackEngine engine = new PlaybackEngine();

        ByteArrayOutputStream pcmData = new ByteArrayOutputStream();
        long maxFrames = (long) SAMPLE_RATE * maxDurationSeconds;
        long totalFrames = 0;

        for (int loop = 0; loop < loopCount; loop++) {
            engine.loadSong(song);

            applyMutes(engine);

            short[] buffer = new short[BUFFER_FRAMES * CHANNELS];

            while (totalFrames < maxFrames) {
                engine.renderBuffer(buffer);
                if (engine.getDriver().isComplete()) break;

                for (short sample : buffer) {
                    pcmData.write(sample & 0xFF);
                    pcmData.write((sample >> 8) & 0xFF);
                }
                totalFrames += BUFFER_FRAMES;
            }
        }

        // Extend mode: render additional seconds with fade applied per-buffer.
        // If the driver has already completed (terminating song), reload the
        // song so there is fresh audio to fade out over the extension period.
        if (fadeEnabled && fadeExtend) {
            if (engine.getDriver().isComplete()) {
                engine.loadSong(song);
                applyMutes(engine);
            }

            int fadeTotalFrames = (int) (fadeDurationSeconds * SAMPLE_RATE);
            int fadeRendered = 0;
            short[] buffer = new short[BUFFER_FRAMES * CHANNELS];

            while (fadeRendered < fadeTotalFrames && totalFrames < maxFrames) {
                engine.renderBuffer(buffer);
                if (engine.getDriver().isComplete()) break;

                for (int i = 0; i < buffer.length; i += 2) {
                    int frameInFade = fadeRendered + (i / CHANNELS);
                    float gain = 1.0f - (float) frameInFade / fadeTotalFrames;
                    if (gain < 0) gain = 0;
                    buffer[i] = (short) (buffer[i] * gain);
                    buffer[i + 1] = (short) (buffer[i + 1] * gain);
                }

                for (short sample : buffer) {
                    pcmData.write(sample & 0xFF);
                    pcmData.write((sample >> 8) & 0xFF);
                }
                fadeRendered += BUFFER_FRAMES;
                totalFrames += BUFFER_FRAMES;
            }
        }

        byte[] pcm = pcmData.toByteArray();

        // Inset mode: fade the last N seconds of already-rendered PCM
        if (fadeEnabled && !fadeExtend) {
            int fadeSampleCount = (int) (fadeDurationSeconds * SAMPLE_RATE);
            int clampedFadeFrames = (int) Math.min(fadeSampleCount, totalFrames);
            int fadeStartFrame = (int) (totalFrames - clampedFadeFrames);
            applyFadeOut(pcm, fadeStartFrame, clampedFadeFrames);
        }

        // Write WAV file
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {
            writeWavHeader(dos, pcm.length);
            dos.write(pcm);
        }
    }

    private void applyMutes(PlaybackEngine engine) {
        if (mutedChannels == null) return;
        for (int ch = 0; ch < mutedChannels.length; ch++) {
            if (mutedChannels[ch]) {
                engine.setChannelMute(ch, true);
            }
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
