package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** A short, full-precision mono excerpt for timbre comparison. */
public record ReferenceAudio(float[] samples, int sampleRate) {
    public static final int MAX_SECONDS = 3;
    private static final int MAX_SAMPLE_RATE = 384_000;
    private static final int MAX_CHUNKS = 4096;
    private static final int PCM = 1, IEEE_FLOAT = 3, EXTENSIBLE = 0xFFFE;
    private static final int RIFF_TAG = 0x46464952, WAVE_TAG = 0x45564157;
    private static final int FORMAT_TAG = 0x20746D66, DATA_TAG = 0x61746164;
    private static final byte[] SUBFORMAT_SUFFIX = {0, 0, 0x10, 0, (byte) 0x80, 0, 0,
            (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71};

    /**
     * Reads a forward mono or stereo WAV excerpt. Start and duration are rounded down
     * to whole source frames. Stereo is averaged and float headroom is retained.
     */
    public static ReferenceAudio read(Path path, double startSeconds, Double durationSeconds) throws IOException {
        if (!Double.isFinite(startSeconds) || startSeconds < 0)
            throw new IllegalArgumentException("--start must be a finite, nonnegative number of seconds");
        if (durationSeconds != null && (!Double.isFinite(durationSeconds)
                || durationSeconds <= 0 || durationSeconds > MAX_SECONDS))
            throw new IllegalArgumentException("--duration must be greater than zero and at most " + MAX_SECONDS + " seconds");

        if (!Files.isRegularFile(path)) throw invalid("Expected a regular WAV file: " + path);
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var wave = header(channel);
            var format = wave.format();
            long available = wave.dataBytes() / format.frameBytes();
            long startFrame = (long) Math.floor(startSeconds * format.sampleRate());
            if (startFrame >= available) throw invalid("--start is at or beyond the end of the audio");
            long remaining = available - startFrame;
            long frames = durationSeconds == null ? remaining : (long) Math.floor(durationSeconds * format.sampleRate());
            if (frames > (long) MAX_SECONDS * format.sampleRate())
                throw invalid("Audio exceeds " + MAX_SECONDS + " seconds. Choose a short excerpt with --start and --duration.");
            if (frames > remaining) throw invalid("Requested excerpt extends beyond the end of the audio");
            if (frames < 1) throw invalid("Requested duration contains no complete audio frames");

            int frameCount = Math.toIntExact(frames);
            var data = readAt(channel, wave.dataStart() + startFrame * format.frameBytes(),
                    frameCount * format.frameBytes());
            var mono = new float[frameCount];
            for (int i = 0; i < mono.length; i++) {
                float left = sample(data, format);
                mono[i] = format.channels() == 1 ? left : (float) ((left + (double) sample(data, format)) / 2);
            }
            return new ReferenceAudio(mono, format.sampleRate());
        }
    }

    /** Native-rate mono evidence, retaining the exact frame offset and WAV duration. */
    static EvidenceSpectrum.Audio evidenceRange(Path path, double start, double end) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var wave = header(channel);
            var format = wave.format();
            int rate = format.sampleRate();
            long available = wave.dataBytes() / format.frameBytes();
            long startFrame = Math.clamp((long) Math.floor(start * rate), 0, available);
            long endFrame = Math.clamp((long) Math.ceil(end * rate), startFrame, available);
            var mono = new float[Math.toIntExact(endFrame - startFrame)];
            for (int offset = 0; offset < mono.length; offset += rate) {
                int count = Math.min(rate, mono.length - offset);
                var data = readAt(channel, wave.dataStart() + (startFrame + offset) * format.frameBytes(),
                        count * format.frameBytes());
                for (int i = 0; i < count; i++) {
                    float first = sample(data, format);
                    mono[offset + i] = format.channels() == 1 ? first : (float) ((first + (double) sample(data, format)) * .5);
                }
            }
            return new EvidenceSpectrum.Audio(mono, rate, startFrame / (double) rate, available / (double) rate);
        }
    }

    /** Bounded stereo comparison prefix, with linear resampling for uncommon WAV rates. */
    static float[] stereoPrefix(Path path, int rate, int frames) throws IOException {
        return stereoRange(path, rate, 0, frames, false);
    }

    /** Read an aligned range in stereo, duplicating mono and resampling when necessary. */
    public static float[] stereoRange(Path path, int rate, long startFrame, int frames) throws IOException {
        return stereoRange(path, rate, startFrame, frames, true);
    }

    private static float[] stereoRange(Path path, int rate, long startFrame, int frames, boolean allowMono)
            throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var wave = header(channel);
            var format = wave.format();
            int channels = format.channels();
            if (channels == 1 && !allowMono) return null;
            long available = wave.dataBytes() / format.frameBytes();
            if (allowMono && ((startFrame + frames - 1) * format.sampleRate() / rate >= available))
                throw invalid("comparison range exceeds WAV duration: " + path);
            var output = new float[frames * 2];
            for (int first = 0; first < frames; first += rate) {
                int end = Math.min(frames, first + rate);
                long sourceFirst = (startFrame + first) * format.sampleRate() / rate;
                long sourceEnd = Math.min(available, (startFrame + end - 1) * format.sampleRate() / rate + 2);
                var data = readAt(channel, wave.dataStart() + sourceFirst * format.frameBytes(),
                        Math.toIntExact((sourceEnd - sourceFirst) * format.frameBytes()));
                var decoded = new float[(int) (sourceEnd - sourceFirst) * channels];
                for (int i = 0; i < decoded.length; i++) decoded[i] = sample(data, format);
                for (int i = first; i < end; i++) {
                    double position = (startFrame + i) * format.sampleRate() / (double) rate - sourceFirst;
                    int low = (int) position;
                    int high = Math.min(low + 1, decoded.length / channels - 1);
                    double fraction = position - low;
                    for (int side = 0; side < 2; side++)
                        output[2*i + side] = (float) (decoded[channels * low + side % channels] * (1 - fraction)
                                + decoded[channels * high + side % channels] * fraction);
                }
            }
            return output;
        }
    }

    /** Scan every frame, averaging into a bounded, common-rate mono signal for folder import. */
    static ImportSignal importSignal(Path path, int rate, int maxSeconds) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            var wave = header(channel);
            var format = wave.format();
            int sourceRate = format.sampleRate();
            long frames = wave.dataBytes() / format.frameBytes();
            if (frames < 1 || frames > (long) maxSeconds * sourceRate)
                throw invalid("WAV duration must be greater than zero and at most " + maxSeconds + " seconds");
            var mono = new float[(int) Math.ceil(frames * (double) rate / sourceRate)];
            double squares = 0;
            double sum = 0;
            int count = 0;
            int bin = 0;
            for (long first = 0; first < frames; first += sourceRate) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Import cancelled");
                int blockFrames = (int) Math.min(sourceRate, frames - first);
                var data = readAt(channel, wave.dataStart() + first * format.frameBytes(),
                        blockFrames * format.frameBytes());
                for (int i = 0; i < blockFrames; i++) {
                    double left = sample(data, format);
                    double right = format.channels() == 1 ? left : sample(data, format);
                    squares += (left * left + right * right) / 2;
                    int next = (int) ((first + i) * rate / sourceRate);
                    if (next != bin) {
                        Arrays.fill(mono, bin, next, (float) (sum / count));
                        bin = next;
                        sum = 0;
                        count = 0;
                    }
                    sum += (left + right) / 2;
                    count++;
                }
            }
            Arrays.fill(mono, bin, mono.length, (float) (sum / count));
            return new ImportSignal(mono, frames / (double) sourceRate, sourceRate, format.channels(),
                    Math.sqrt(squares / frames));
        }
    }

    record ImportSignal(float[] mono, double seconds, int sampleRate, int channels, double rms) {}

    private record Format(int encoding, int channels, int sampleRate, int bits, int frameBytes) {}
    private record Wave(Format format, long dataStart, long dataBytes) {}

    static long durationMicros(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var wave = header(channel);
            return (wave.dataBytes() / wave.format().frameBytes()) * 1_000_000L / wave.format().sampleRate();
        }
    }

    private static Wave header(FileChannel channel) throws IOException {
        // Chunk identifiers are literal ASCII bytes, independent of numeric field endianness.
        ByteBuffer file = readAt(channel, 0, 12);
        if (file.getInt() != RIFF_TAG || file.getInt(8) != WAVE_TAG)
            throw invalid("Expected a little-endian RIFF/WAVE audio file");
        long end = 8 + Integer.toUnsignedLong(file.getInt(4));
        if (end < 12 || end > channel.size()) throw invalid("Truncated RIFF/WAVE data");

        Format format = null;
        long dataStart = -1;
        long dataBytes = 0;
        long position = 12;
        int chunks = 0;
        while (position + 8 <= end && (format == null || dataStart < 0)) {
            if (++chunks > MAX_CHUNKS) throw invalid("Too many chunks before WAV format and audio data");
            ByteBuffer chunk = readAt(channel, position, 8);
            int tag = chunk.getInt();
            long size = Integer.toUnsignedLong(chunk.getInt());
            long start = position + 8;
            long next = start + size + (size & 1);
            if (next > end) throw invalid("Truncated RIFF/WAVE chunk");
            if (tag == FORMAT_TAG && format == null) {
                if (size < 16) throw invalid("Incomplete WAV format chunk");
                format = format(readAt(channel, start, (int) Math.min(size, 40)), size);
            } else if (tag == DATA_TAG && dataStart < 0) {
                dataStart = start;
                dataBytes = size;
            }
            position = next;
        }

        if (format == null || dataStart < 0) throw invalid("Missing WAV format or audio data chunk");
        if (dataBytes % format.frameBytes() != 0) throw invalid("WAV audio ends with an incomplete sample frame");
        return new Wave(format, dataStart, dataBytes);
    }

    private static Format format(ByteBuffer data, long size) throws IOException {
        int encoding = Short.toUnsignedInt(data.getShort());
        int channels = Short.toUnsignedInt(data.getShort());
        int rate = data.getInt();
        data.getInt(); // The byte rate is redundant with sample rate and frame size.
        int alignment = Short.toUnsignedInt(data.getShort());
        int bits = Short.toUnsignedInt(data.getShort());
        if (encoding == EXTENSIBLE) {
            if (size < 40) throw invalid("Incomplete WAVE_FORMAT_EXTENSIBLE format");
            int extensionBytes = Short.toUnsignedInt(data.getShort());
            int validBits = Short.toUnsignedInt(data.getShort());
            data.getInt(); // The mask does not change arithmetic averaging of two channels.
            encoding = data.getInt();
            byte[] suffix = new byte[SUBFORMAT_SUFFIX.length];
            data.get(suffix);
            if (extensionBytes < 22 || extensionBytes > size - 18 || validBits < 1 || validBits > bits
                    || (encoding == IEEE_FLOAT && validBits != 32) || !Arrays.equals(suffix, SUBFORMAT_SUFFIX))
                throw invalid("Invalid WAVE_FORMAT_EXTENSIBLE encoding");
        }
        if (channels != 1 && channels != 2) throw invalid("WAV audio must be mono or stereo");
        if (rate < 1 || rate > MAX_SAMPLE_RATE) throw invalid("WAV sample rate must be in 1.." + MAX_SAMPLE_RATE + " Hz");
        boolean supported = encoding == PCM && (bits == 16 || bits == 24 || bits == 32)
                || encoding == IEEE_FLOAT && bits == 32;
        if (!supported) throw invalid("Use 16-, 24-, or 32-bit PCM, or 32-bit IEEE float WAV");
        if (alignment != channels * (bits / 8)) throw invalid("Invalid WAV sample frame size");
        return new Format(encoding, channels, rate, bits, alignment);
    }

    private static float sample(ByteBuffer data, Format format) throws IOException {
        if (format.encoding() == IEEE_FLOAT) {
            float value = data.getFloat();
            if (!Float.isFinite(value)) throw invalid("WAV excerpt contains a nonfinite floating-point sample");
            return value;
        }
        return switch (format.bits()) {
            case 16 -> data.getShort() / 32768f;
            case 24 -> (Byte.toUnsignedInt(data.get()) | Byte.toUnsignedInt(data.get()) << 8 | data.get() << 16) / 8388608f;
            case 32 -> (float) (data.getInt() / 2147483648.0);
            default -> throw new IllegalStateException("Unexpected PCM sample width");
        };
    }

    private static ByteBuffer readAt(FileChannel channel, long position, int size) throws IOException {
        var data = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (data.hasRemaining()) {
            int read = channel.read(data, position + data.position());
            if (read < 0) throw invalid("Unexpected end of RIFF/WAVE data");
        }
        return data.flip();
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }
}
