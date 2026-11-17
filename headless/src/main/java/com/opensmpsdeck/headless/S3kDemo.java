package com.opensmpsdeck.headless;

import java.io.ByteArrayOutputStream;

/** Small original, finite FM/PSG phrase. Contains no ROM or third-party musical assets. */
public final class S3kDemo {
    private S3kDemo() {}

    public static byte[] song() {
        byte[] fm = {(byte) 0xEF, 0, (byte) 0xE0, (byte) 0xC0,
                (byte) 0xA5, 12, (byte) 0xA9, 12, (byte) 0xAC, 12,
                (byte) 0xB1, 24, (byte) 0x80, 12, (byte) 0xF2};
        byte[] psg = {(byte) 0xE4, 0x58, (byte) 0x99, 24,
                (byte) 0x9D, 24, (byte) 0xA0, 24, (byte) 0x80, 12, (byte) 0xF2};
        int headerSize = 20; // DAC + FM1 + PSG1
        int fmStart = headerSize + 1;
        int psgStart = fmStart + fm.length;
        int voices = psgStart + psg.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le16(out, voices);
        out.write(2); out.write(1); out.write(1); out.write(0x80);
        le16(out, headerSize); out.write(0); out.write(0);
        le16(out, fmStart); out.write(0); out.write(0);
        le16(out, psgStart); out.write(0); out.write(0); out.write(0); out.write(0);
        out.write(0xF2); // inactive DAC slot
        out.writeBytes(fm); out.writeBytes(psg);
        // Native SMPS parameter groups, four operators per group. Algorithm 7
        // makes all four carriers; differing multipliers make swaps observable.
        out.writeBytes(new byte[]{7, 1, 2, 3, 4, 31, 31, 31, 31,
                0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15, 32, 40, 48, 56});
        return out.toByteArray();
    }

    private static void le16(ByteArrayOutputStream out, int value) {
        out.write(value); out.write(value >>> 8);
    }
}
