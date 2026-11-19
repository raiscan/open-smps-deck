package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.DacSample;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Converts bounded WAV and raw PCM files to native unsigned eight-bit mono PCM. */
final class DacSampleReader {
    static final int RAW_RATE = 0x0C;
    static final int MAX_WAV_BYTES = 16 * 1024 * 1024;
    private static final int RIFF = 0x46464952;
    private static final int WAVE = 0x45564157;
    private static final int FORMAT = 0x20746D66;
    private static final int DATA = 0x61746164;
    private static final double EXACT_RATE_TOLERANCE_HZ = 0.5;

    private DacSampleReader() {}

    static DacSample read(Path path, String id) throws IOException {
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        if (dot < 1) throw invalid("Choose a .wav, .pcm or .bin file.");
        String name = filename.substring(0, dot).strip();
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "wav" -> decodeWav(id, name, readBounded(path, MAX_WAV_BYTES));
            case "pcm", "bin" -> DacSample.of(id, name, RAW_RATE, readBounded(path, DacSample.MAX_SAMPLE_BYTES));
            default -> throw invalid("Choose a .wav, .pcm or .bin file.");
        };
    }

    private static byte[] readBounded(Path path, int limit) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw invalid("Input exceeds the " + limit + "-byte file limit.");
            if (bytes.length == 0) throw invalid("Audio file is empty.");
            return bytes;
        }
    }

    static DacSample decodeWav(String id, String name, byte[] bytes) throws IOException {
        if (bytes.length > MAX_WAV_BYTES) throw invalid("WAV exceeds the " + MAX_WAV_BYTES + "-byte file limit.");
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || input.getInt() != RIFF
                || Integer.toUnsignedLong(input.getInt()) != bytes.length - 8L || input.getInt() != WAVE)
            throw invalid("Expected a complete RIFF/WAVE file.");

        Format format = null;
        int dataStart = -1;
        int dataLength = 0;
        while (input.hasRemaining()) {
            if (input.remaining() < 8) throw invalid("Truncated WAV chunk header.");
            int type = input.getInt();
            long size = Integer.toUnsignedLong(input.getInt());
            long paddedSize = size + (size & 1);
            if (paddedSize > input.remaining()) throw invalid("Truncated WAV chunk.");
            int start = input.position();
            if (type == FORMAT) {
                if (format != null) throw invalid("WAV contains multiple format chunks.");
                format = readFormat(input, (int) size);
            } else if (type == DATA) {
                if (dataStart >= 0) throw invalid("WAV contains multiple data chunks.");
                dataStart = start;
                dataLength = (int) size;
            }
            input.position(start + (int) paddedSize);
        }
        if (format == null || dataStart < 0 || dataLength == 0 || dataLength % format.frameBytes() != 0)
            throw invalid("WAV requires complete PCM frames and a format chunk.");

        int sourceFrames = dataLength / format.frameBytes();
        int rate = nearestRate(format.hz());
        double nativeHz = DacSample.sampleRateHz(rate);
        boolean preservePcm = format.bits() == 8 && format.channels() == 1
                && Math.abs(nativeHz - format.hz()) <= EXACT_RATE_TOLERANCE_HZ;
        double inputPerOutput = format.hz() / nativeHz;
        long outputFrames = preservePcm ? sourceFrames : Math.max(1, Math.round(sourceFrames / inputPerOutput));
        if (outputFrames > DacSample.MAX_SAMPLE_BYTES)
            throw invalid("Converted PCM exceeds " + DacSample.MAX_SAMPLE_BYTES + " samples.");

        byte[] pcm = new byte[(int) outputFrames];
        if (preservePcm) {
            System.arraycopy(bytes, dataStart, pcm, 0, pcm.length);
        } else {
            for (int i = 0; i < pcm.length; i++) {
                double start = i * inputPerOutput;
                double sample = inputPerOutput > 1
                        ? average(input, dataStart, format, start, Math.min(sourceFrames, start + inputPerOutput))
                        : interpolate(input, dataStart, format, Math.min(sourceFrames - 1, start), sourceFrames);
                pcm[i] = (byte) Math.clamp(Math.round(128 + sample / 256), 0, 255);
            }
        }
        return DacSample.of(id, name, rate, pcm);
    }

    private static Format readFormat(ByteBuffer input, int size) throws IOException {
        if (size < 16) throw invalid("Truncated WAV format chunk.");
        int encoding = Short.toUnsignedInt(input.getShort());
        int channels = Short.toUnsignedInt(input.getShort());
        long hz = Integer.toUnsignedLong(input.getInt());
        long byteRate = Integer.toUnsignedLong(input.getInt());
        int frameBytes = Short.toUnsignedInt(input.getShort());
        int bits = Short.toUnsignedInt(input.getShort());
        if (encoding != 1 || (channels != 1 && channels != 2) || (bits != 8 && bits != 16))
            throw invalid("WAV must be uncompressed 8- or 16-bit PCM with one or two channels.");
        if (hz == 0 || frameBytes != channels * (bits / 8) || byteRate != hz * frameBytes)
            throw invalid("WAV has inconsistent sample rate or frame size.");
        return new Format(channels, hz, frameBytes, bits);
    }

    static int nearestRate(double hz) {
        int best = 1;
        for (int loops = 2; loops <= 256; loops++) {
            int candidate = loops & 255;
            if (Math.abs(DacSample.sampleRateHz(candidate) - hz) < Math.abs(DacSample.sampleRateHz(best) - hz))
                best = candidate;
        }
        return best;
    }

    /** Average each input frame over the portion of the output interval it covers. */
    private static double average(ByteBuffer input, int dataStart, Format format, double start, double end) {
        double sum = 0;
        for (int frame = (int) start; frame < end; frame++) {
            double weight = Math.min(end, frame + 1) - Math.max(start, frame);
            sum += mono(input, dataStart, format, frame) * weight;
        }
        return sum / (end - start);
    }

    private static double interpolate(ByteBuffer input, int dataStart, Format format, double position, int frames) {
        int left = (int) position;
        double sample = mono(input, dataStart, format, left);
        if (left + 1 < frames && position > left) {
            double right = mono(input, dataStart, format, left + 1);
            sample += (right - sample) * (position - left);
        }
        return sample;
    }

    private static double mono(ByteBuffer input, int start, Format format, int frame) {
        int offset = start + frame * format.frameBytes();
        int first = channel(input, offset, format.bits());
        if (format.channels() == 1) return first;
        int second = channel(input, offset + format.bits() / 8, format.bits());
        return (first + second) / 2.0;
    }

    private static int channel(ByteBuffer input, int offset, int bits) {
        return bits == 8 ? (Byte.toUnsignedInt(input.get(offset)) - 128) * 256 : input.getShort(offset);
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }

    private record Format(int channels, long hz, int frameBytes, int bits) {}
}
