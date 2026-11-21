package com.opensmpsdeck.headless;

import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;
import com.openggf.game.sonic3k.audio.smps.Sonic3kDpcmDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

final class S3kDisasmDac {
    private static final double Z80_CLOCK = 53693175.0 / 15;

    private S3kDisasmDac() {}

    static int sampleRate(int rate) {
        // Pick the middle of the truncation interval used by dpcmLoopCounter.
        // ASL truncates negative integer quotients towards zero. For rate zero
        // the clock quotient must be in 120..132 so that (quotient - 145) / 13
        // is -1. DJNZ interprets the resulting byte as 256 iterations.
        double divisor = rate == 0 ? 126 : 132 + 13 * (rate + 0.5);
        return (int) Math.round(Z80_CLOCK / divisor);
    }

    static String scale(int baseRate, int rate) {
        return String.format(Locale.ROOT, "%.12f", (sampleRate(rate) + 0.25) / baseRate);
    }

    static byte[] quantize(byte[] pcm) {
        int[] deltas = Sonic3kSmpsConstants.DPCM_DELTA_TABLE;
        byte[] packed = new byte[(pcm.length + 1) / 2];
        int previous = 128;
        for (int i = 0; i < packed.length * 2; i++) {
            int target = i < pcm.length ? pcm[i] & 255 : previous;
            int best = 0, error = Integer.MAX_VALUE;
            for (int index = 0; index < deltas.length; index++) {
                int candidate = (previous + deltas[index]) & 255;
                int distance = Math.abs(target - candidate);
                if (distance < error) {
                    best = index;
                    error = distance;
                }
            }
            packed[i / 2] |= (byte) (best << (i % 2 == 0 ? 4 : 0));
            previous = (previous + deltas[best]) & 255;
        }
        return new Sonic3kDpcmDecoder().decode(packed);
    }

    static byte[] wav(byte[] pcm, int hz) {
        int pad = pcm.length % 2;
        var file = ByteBuffer.allocate(44 + pcm.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        file.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(file.capacity() - 8);
        file.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        file.putShort((short) 1).putShort((short) 1).putInt(hz).putInt(hz);
        file.putShort((short) 1).putShort((short) 8);
        file.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return file.array();
    }

    static byte[] padded(byte[] pcm) {
        if (pcm.length % 2 == 0) return pcm;
        byte[] result = Arrays.copyOf(pcm, pcm.length + 1);
        result[pcm.length] = pcm[pcm.length - 1];
        return result;
    }
}
