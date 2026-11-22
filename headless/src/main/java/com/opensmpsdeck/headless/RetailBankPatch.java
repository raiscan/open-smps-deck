package com.opensmpsdeck.headless;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static com.opensmpsdeck.headless.PackageManifest.require;

/** Canonical positional delta for a retail-derived native bank. */
final class RetailBankPatch {
    static final String PATH = "track.delta";
    static final int MAX_BYTES = 2 + PortableArchive.MAX_TRACK + 4 * (PortableArchive.MAX_TRACK / 2);

    private RetailBankPatch() {}

    static byte[] encode(byte[] base, byte[] target) {
        validateTargetLength(target.length);
        var out = new ByteArrayOutputStream();
        writeWord(out, target.length);
        int offset = 0;
        while (offset < target.length) {
            if (!changed(base, target, offset)) {
                offset++;
                continue;
            }
            int start = offset++;
            while (offset < target.length && changed(base, target, offset)) offset++;
            writeWord(out, start);
            writeWord(out, offset - start);
            out.write(target, start, offset - start);
        }
        return out.toByteArray();
    }

    private static boolean changed(byte[] base, byte[] target, int offset) {
        return offset >= base.length || base[offset] != target[offset];
    }

    private static void writeWord(ByteArrayOutputStream out, int word) {
        out.write(word >>> 8);
        out.write(word & 255);
    }

    static int targetLength(byte[] delta) {
        return parse(delta, null).length();
    }

    static byte[] apply(byte[] delta, byte[] base) {
        return parse(delta, base).bank();
    }

    private record Parsed(int length, byte[] bank) {}

    private static Parsed parse(byte[] delta, byte[] base) {
        require(delta.length >= 2 && delta.length <= MAX_BYTES, "Invalid track.delta size");
        var input = ByteBuffer.wrap(delta);
        int length = readWord(input);
        validateTargetLength(length);
        byte[] target = base == null ? null : Arrays.copyOf(base, length);
        int previousEnd = -1;
        while (input.hasRemaining()) {
            require(input.remaining() >= 4, "track.delta has a truncated run header");
            int offset = readWord(input);
            int count = readWord(input);
            require(count > 0 && offset + count <= length, "track.delta run is out of range at " + offset);
            require(offset > previousEnd, "track.delta runs must be sorted and separated by at least one byte");
            require(input.remaining() >= count, "track.delta has a truncated literal run at " + offset);
            if (target == null) {
                input.position(input.position() + count);
            } else {
                require(offset <= base.length,
                        "track.delta is missing extension bytes");
                for (int i = offset; i < offset + count; i++) {
                    byte literal = input.get();
                    if (i < base.length && literal == base[i])
                        throw new IllegalArgumentException("track.delta contains an unchanged base byte at " + i);
                    target[i] = literal;
                }
            }
            previousEnd = offset + count;
        }
        if (target != null && length > base.length)
            require(previousEnd == length, "track.delta is missing extension bytes");
        return new Parsed(length, target);
    }

    private static int readWord(ByteBuffer input) {
        return Short.toUnsignedInt(input.getShort());
    }

    private static void validateTargetLength(int length) {
        require(length >= 10 && length <= PortableArchive.MAX_TRACK, "track.delta target length must be in 10..32768");
    }

    static String romNeeded(PackageManifest.Retail retail) {
        return "Retail patch needs ROM (--rom) for " + retail.game() + " song " + retail.songId();
    }

    static byte[] resolveBase(PackageManifest.Retail retail, List<? extends StockAssets> sources) {
        require(!sources.isEmpty(), romNeeded(retail));
        var actual = new LinkedHashSet<String>();
        for (var source : sources) {
            byte[] bank;
            try {
                bank = source.retailBank(retail.game(), retail.songId());
            } catch (IOException failure) {
                actual.add(failure.getMessage());
                continue;
            }
            if (bank == null) continue;
            String hash = StockTable.hash(bank);
            if (hash.equals(retail.bankSha256())) return bank;
            actual.add(hash);
        }
        throw new IllegalArgumentException("Retail base " + retail.game() + " song " + retail.songId()
                + ": expected SHA-256 " + retail.bankSha256()
                + ", actual " + (actual.isEmpty() ? "missing" : String.join(", ", actual)));
    }
}
