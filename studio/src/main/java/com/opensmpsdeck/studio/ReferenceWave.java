package com.opensmpsdeck.studio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;

/** Immutable source-rate PCM shared by the waveform worker and the transport. */
final class ReferenceWave {
    private static final int MAX_BYTES = 256 * 1024 * 1024;

    record Overview(float[] minimum, float[] maximum) {}

    private final AudioFormat format;
    private final byte[] pcm;
    private final int frames;
    private final int sampleBytes;

    private ReferenceWave(AudioFormat format, byte[] pcm) {
        this.format = format;
        this.pcm = pcm;
        frames = pcm.length / format.getFrameSize();
        sampleBytes = format.getSampleSizeInBits() / 8;
    }

    static ReferenceWave read(Path path) throws IOException {
        try {
            if (!AudioFileFormat.Type.WAVE.equals(AudioSystem.getAudioFileFormat(path.toFile()).getType())) {
                throw new UnsupportedAudioFileException();
            }
            return readPcm(path);
        } catch (UnsupportedAudioFileException error) {
            throw new IOException("Unsupported WAV: " + path.getFileName(), error);
        }
    }

    private static ReferenceWave readPcm(Path path) throws IOException, UnsupportedAudioFileException {
        try (var input = AudioSystem.getAudioInputStream(path.toFile())) {
            var format = input.getFormat();
            int bits = format.getSampleSizeInBits(), channels = format.getChannels();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.isBigEndian()
                    || bits != 16 && bits != 24 || channels != 1 && channels != 2
                    || format.getSampleRate() <= 0 || format.getFrameSize() != bits / 8 * channels) {
                throw new IOException("Use a 16- or 24-bit PCM WAV with one or two channels");
            }
            long frames = input.getFrameLength();
            if (frames <= 0 || frames > MAX_BYTES / format.getFrameSize()) {
                throw new IOException("WAV must contain between one frame and 256 MiB of PCM");
            }
            byte[] pcm = new byte[Math.toIntExact(frames * format.getFrameSize())];
            for (int written = 0; written < pcm.length;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                int limit = 65536 / format.getFrameSize() * format.getFrameSize();
                int count = input.read(pcm, written, Math.min(pcm.length - written, limit));
                if (count <= 0) throw new IOException("Truncated WAV data");
                written += count;
            }
            return new ReferenceWave(format, pcm);
        }
    }

    double duration() { return frames / (double) format.getSampleRate(); }

    float sample(double seconds, int channel) {
        double frame = seconds * format.getSampleRate();
        if (frame < 0 || frame >= frames) return 0;
        int low = (int) frame;
        float first = at(low, channel), second = at(Math.min(low + 1, frames - 1), channel);
        return (float) (first + (second - first) * (frame - low));
    }

    Overview overview(double pixelsPerSecond) throws InterruptedIOException {
        int columns = Math.max(1, (int) Math.ceil(duration() * pixelsPerSecond));
        float[] min = new float[columns], max = new float[columns];
        double framesPerColumn = format.getSampleRate() / pixelsPerSecond;
        for (int column = 0; column < columns; column++) {
            if ((column & 1023) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int start = Math.min(frames - 1, (int) (column * framesPerColumn));
            int end = Math.min(frames, Math.max(start + 1, (int) ((column + 1) * framesPerColumn)));
            float low = 1, high = -1;
            for (int frame = start; frame < end; frame++) {
                for (int channel = 0; channel < format.getChannels(); channel++) {
                    float sample = at(frame, channel);
                    low = Math.min(low, sample);
                    high = Math.max(high, sample);
                }
            }
            min[column] = low;
            max[column] = high;
        }
        return new Overview(min, max);
    }

    private float at(int frame, int channel) {
        int offset = frame * format.getFrameSize() + (format.getChannels() == 1 ? 0 : channel * sampleBytes);
        int value = (pcm[offset] & 255) | pcm[offset + 1] << 8;
        if (sampleBytes == 3) value = (value & 65535) | pcm[offset + 2] << 16;
        return value / (float) (1 << (format.getSampleSizeInBits() - 1));
    }
}
