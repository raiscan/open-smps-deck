package com.opensmpsdeck.headless.rom;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;

/** BPS1 binary patches. Image sizes are capped at 64 MiB to bound decoder allocation. */
public final class BpsPatch {
    private static final byte[] MAGIC = {'B', 'P', 'S', '1'};
    private static final int FOOTER_SIZE = 12;
    private static final int MAX_IMAGE_SIZE = 64 * 1024 * 1024;
    static final int SOURCE_READ = 0;
    private static final int TARGET_READ = 1;
    static final int SOURCE_COPY = 2;
    static final int TARGET_COPY = 3;

    private BpsPatch() {}

    public record Stats(int sourceReadBytes, int targetReadBytes, int sourceCopyBytes, int targetCopyBytes) {}

    public record Encoded(byte[] bytes, Stats stats) {
        public Encoded {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() {
            return bytes.clone();
        }
    }

    public static Encoded encode(byte[] source, byte[] target) {
        checkSize(source.length);
        checkSize(target.length);
        var patch = new ByteArrayOutputStream();
        patch.writeBytes(MAGIC);
        writeNumber(patch, source.length);
        writeNumber(patch, target.length);
        writeNumber(patch, 0); // No metadata.

        var matches = new BpsMatches(source, target);
        int[] counts = new int[4];
        int sourceCursor = 0;
        int targetCursor = 0;
        int literalStart = 0;
        for (int position = 0; position < target.length; ) {
            var match = matches.at(position);
            if (match.length() == 0) {
                position++;
                continue;
            }
            counts[TARGET_READ] += writeLiterals(patch, target, literalStart, position);
            writeAction(patch, match.action(), match.length());
            if (match.action() == SOURCE_COPY) {
                writeDelta(patch, match.start() - sourceCursor);
                sourceCursor = match.start() + match.length();
            } else if (match.action() == TARGET_COPY) {
                writeDelta(patch, match.start() - targetCursor);
                targetCursor = match.start() + match.length();
            }
            counts[match.action()] += match.length();
            position += match.length();
            literalStart = position;
        }
        counts[TARGET_READ] += writeLiterals(patch, target, literalStart, target.length);

        writeCrc(patch, crc(source, source.length));
        writeCrc(patch, crc(target, target.length));
        byte[] beforePatchCrc = patch.toByteArray();
        writeCrc(patch, crc(beforePatchCrc, beforePatchCrc.length));
        var stats = new Stats(counts[SOURCE_READ], counts[TARGET_READ], counts[SOURCE_COPY], counts[TARGET_COPY]);
        return new Encoded(patch.toByteArray(), stats);
    }

    /** Applies a BPS1 patch and rejects malformed patches and all three CRC mismatches. */
    public static byte[] apply(byte[] patch, byte[] source) {
        int footer = patch.length - FOOTER_SIZE;
        if (footer < MAGIC.length + 3 || !Arrays.equals(patch, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw invalid("invalid BPS1 header");
        }
        var checksums = ByteBuffer.wrap(patch, footer, FOOTER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        int sourceCrc = checksums.getInt();
        int targetCrc = checksums.getInt();
        int patchCrc = checksums.getInt();
        if (crc(patch, patch.length - 4) != patchCrc) throw invalid("patch CRC32 mismatch");
        if (crc(source, source.length) != sourceCrc) throw invalid("source CRC32 mismatch");

        var input = new Reader(patch, footer);
        if (input.number() != source.length) throw invalid("source size mismatch");
        int targetSize = input.number();
        checkSize(targetSize);
        input.skip(input.number());

        byte[] target = new byte[targetSize];
        int output = 0;
        int sourceCursor = 0;
        int targetCursor = 0;
        while (input.hasRemaining()) {
            int action = input.number();
            int length = (action >>> 2) + 1;
            if (length > targetSize - output) throw invalid("action exceeds target size");
            switch (action & 3) {
                case SOURCE_READ -> {
                    checkRead(output, length, source.length, "SourceRead");
                    System.arraycopy(source, output, target, output, length);
                }
                case TARGET_READ -> {
                    int start = input.skip(length);
                    System.arraycopy(patch, start, target, output, length);
                }
                case SOURCE_COPY -> {
                    sourceCursor += input.delta();
                    checkRead(sourceCursor, length, source.length, "SourceCopy");
                    System.arraycopy(source, sourceCursor, target, output, length);
                    sourceCursor += length;
                }
                case TARGET_COPY -> {
                    targetCursor += input.delta();
                    checkRead(targetCursor, 1, output, "TargetCopy");
                    // Copy forward byte by byte so overlapping runs can read newly produced bytes.
                    for (int i = 0; i < length; i++) target[output + i] = target[targetCursor++];
                }
                default -> throw new AssertionError();
            }
            output += length;
        }
        if (output != targetSize) throw invalid("target size mismatch");
        if (crc(target, target.length) != targetCrc) throw invalid("target CRC32 mismatch");
        return target;
    }

    private static void checkRead(int start, int length, int limit, String action) {
        if (start < 0 || start > limit - length) throw invalid(action + " reads outside available data");
    }

    private static void checkSize(int size) {
        if (size > MAX_IMAGE_SIZE) throw invalid("image exceeds the 64 MiB size limit");
    }

    private static int writeLiterals(ByteArrayOutputStream patch, byte[] target, int start, int end) {
        int length = end - start;
        if (length > 0) {
            writeAction(patch, TARGET_READ, length);
            patch.write(target, start, length);
        }
        return length;
    }

    private static void writeAction(ByteArrayOutputStream patch, int action, int length) {
        writeNumber(patch, ((length - 1) << 2) | action);
    }

    private static void writeDelta(ByteArrayOutputStream patch, int delta) {
        writeNumber(patch, (Math.abs(delta) << 1) | (delta < 0 ? 1 : 0));
    }

    private static void writeNumber(ByteArrayOutputStream patch, int value) {
        while (value >= 128) {
            patch.write(value & 127);
            value = (value >>> 7) - 1;
        }
        patch.write(value | 128);
    }

    private static void writeCrc(ByteArrayOutputStream patch, int crc) {
        for (int shift = 0; shift < 32; shift += 8) patch.write(crc >>> shift);
    }

    private static int crc(byte[] bytes, int length) {
        var crc = new CRC32();
        crc.update(bytes, 0, length);
        return (int) crc.getValue();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("BPS: " + reason);
    }

    private static final class Reader {
        private final byte[] patch;
        private final int limit;
        private int position = MAGIC.length;

        Reader(byte[] patch, int limit) {
            this.patch = patch;
            this.limit = limit;
        }

        boolean hasRemaining() {
            return position < limit;
        }

        int skip(int bytes) {
            if (bytes > limit - position) throw invalid("truncated metadata or action");
            int start = position;
            position += bytes;
            return start;
        }

        int number() {
            long value = 0;
            for (int shift = 0; shift <= 28 && hasRemaining(); shift += 7) {
                int next = patch[position++] & 255;
                value += (long) (next & 127) << shift;
                if ((next & 128) != 0 && value <= Integer.MAX_VALUE) return (int) value;
                if ((next & 128) != 0) break;
                value += 1L << (shift + 7);
            }
            throw invalid("truncated or oversized number");
        }

        int delta() {
            int encoded = number();
            return (encoded & 1) == 0 ? encoded >>> 1 : -(encoded >>> 1);
        }
    }
}
