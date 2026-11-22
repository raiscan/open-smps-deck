package com.opensmpsdeck.headless.rom;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Greedy, deterministic matching over 8-byte hashes. Candidate searches are bounded on repetitive input. */
final class BpsMatches {
    private static final int WINDOW = Long.BYTES;
    private static final int MAX_CANDIDATES = 64;
    private final byte[] source;
    private final byte[] target;
    private final ByteBuffer targetKeys;
    private Index sourceIndex;
    private Index targetIndex;

    record Match(int action, int start, int length) {}

    BpsMatches(byte[] source, byte[] target) {
        this.source = source;
        this.target = target;
        targetKeys = ByteBuffer.wrap(target);
    }

    Match at(int position) {
        int available = target.length - position;
        int aligned = position < source.length ? commonPrefix(source, position, position) : 0;
        var best = new Match(BpsPatch.SOURCE_READ, position, aligned);
        if (aligned == available || available < WINDOW) return best;

        if (sourceIndex == null) {
            sourceIndex = new Index(source);
            sourceIndex.advanceTo(source.length);
            targetIndex = new Index(target);
        }
        targetIndex.advanceTo(position);
        long key = targetKeys.getLong(position);
        best = longerMatch(sourceIndex, key, position, best, BpsPatch.SOURCE_COPY);
        return longerMatch(targetIndex, key, position, best, BpsPatch.TARGET_COPY);
    }

    private Match longerMatch(Index index, long key, int position, Match best, int action) {
        int maxLength = target.length - position;
        int offset = index.heads[index.bucket(key)] - 1;
        for (int searched = 0; offset >= 0 && searched < MAX_CANDIDATES && best.length < maxLength; searched++) {
            if (index.data.length - offset > best.length && index.keys.getLong(offset) == key) {
                int length = commonPrefix(index.data, offset, position);
                if (length > best.length) best = new Match(action, offset, length);
            }
            offset = index.previous[offset] - 1;
        }
        return best;
    }

    private int commonPrefix(byte[] data, int offset, int position) {
        int limit = Math.min(data.length - offset, target.length - position);
        int mismatch = Arrays.mismatch(data, offset, offset + limit, target, position, position + limit);
        return mismatch < 0 ? limit : mismatch;
    }

    private static final class Index {
        final byte[] data;
        final ByteBuffer keys;
        final int[] heads;
        final int[] previous;
        private int indexed;

        Index(byte[] data) {
            this.data = data;
            keys = ByteBuffer.wrap(data);
            previous = new int[Math.max(0, data.length - WINDOW + 1)];
            heads = new int[Math.min(1 << 20, Integer.highestOneBit(Math.max(1, data.length)))];
        }

        void advanceTo(int limit) {
            int end = Math.min(limit, previous.length);
            while (indexed < end) {
                int bucket = bucket(keys.getLong(indexed));
                previous[indexed] = heads[bucket];
                heads[bucket] = ++indexed; // Store offsets plus one, with zero as the empty sentinel.
            }
        }

        int bucket(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return (int) key & (heads.length - 1);
        }
    }
}
