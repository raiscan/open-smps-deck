package com.opensmpsdeck.headless;

import com.openggf.data.compression.SaxmanDecompressor;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** ROM-backed zPSG_EnvTbl for the shared REV00 and REV01 sound driver layout. */
final class Sonic2RomEnvelopes {
    private static final int DECOMPRESS_SOUND_DRIVER = 0xec04a;
    private static final int PAYLOAD = 0xec0e8;
    private static final int COMPRESSED_SIZE = 0x0f64;
    private static final int TABLE = 0x1029;
    static final int COUNT = 13;

    private Sonic2RomEnvelopes() {}

    static Map<Integer, byte[]> extract(byte[] image) throws IOException {
        // LEA Snd_Driver(pc),A6 and MOVE.W #size,D7 at DecompressSoundDriver.
        int code = DECOMPRESS_SOUND_DRIVER;
        if (image.length < PAYLOAD + COMPRESSED_SIZE || word(image, code) != 0x4dfa
                || code + 2 + (short) word(image, code + 2) != PAYLOAD
                || word(image, code + 4) != 0x3e3c || word(image, code + 6) != COMPRESSED_SIZE)
            throw new IOException("Unknown S2 Z80 sound driver layout for zPSG_EnvTbl (expected REV00 or REV01)");
        byte[] driver = new SaxmanDecompressor().decompressRaw(
                Arrays.copyOfRange(image, PAYLOAD, PAYLOAD + COMPRESSED_SIZE), COMPRESSED_SIZE);
        int tableEnd = TABLE + COUNT * 2;
        if (driver.length < tableEnd) throw new IOException("Truncated S2 zPSG_EnvTbl");
        var envelopes = new HashMap<Integer, byte[]>();
        for (int id = 1; id <= COUNT; id++) {
            int slot = TABLE + (id - 1) * 2;
            int start = (driver[slot] & 255) | (driver[slot + 1] & 255) << 8;
            if (start < tableEnd || start >= driver.length)
                throw new IOException("Invalid S2 PSG envelope pointer " + id);
            int end = start;
            while (end < driver.length && (driver[end] & 255) < 0x80) end++;
            if (end == driver.length || (driver[end] & 255) != 0x80 || end - start >= 256)
                throw new IOException("Invalid S2 PSG envelope terminator " + id);
            envelopes.put(id, Arrays.copyOfRange(driver, start, end + 1));
        }
        return Map.copyOf(envelopes);
    }

    private static int word(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
    }
}
