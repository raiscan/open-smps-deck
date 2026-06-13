package com.opensmpsdeck.io;

public final class DacCodec {

    private static final int[] DPCM_DELTA_TABLE = {
            0, 1, 2, 4, 8, 16, 32, 64,
            -128, -1, -2, -4, -8, -16, -32, -64
    };

    private DacCodec() {
    }

    /**
     * Decompress DPCM-encoded DAC sample data.
     * Each input byte produces two output samples via high/low nibble delta accumulation.
     */
    public static byte[] decompressDpcm(byte[] compressed) {
        byte[] output = new byte[compressed.length * 2];
        int accumulator = 0x80;
        for (int i = 0; i < compressed.length; i++) {
            int b = compressed[i] & 0xFF;
            accumulator = (accumulator + DPCM_DELTA_TABLE[(b >> 4) & 0x0F]) & 0xFF;
            output[i * 2] = (byte) accumulator;
            accumulator = (accumulator + DPCM_DELTA_TABLE[b & 0x0F]) & 0xFF;
            output[i * 2 + 1] = (byte) accumulator;
        }
        return output;
    }
}
