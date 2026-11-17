package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.TreeSet;

/** Input boundary for the deliberately finite, local-resource first proof. */
final class S3kBinary {
    static final int MAX_BYTES = 0x8000;
    private S3kBinary() {}

    static byte[] validateAndCopy(byte[] input) {
        if (input == null || input.length < 10) fail("truncated S3K header (at least one DAC slot required)");
        if (input.length > MAX_BYTES) fail("binary exceeds one 32 KiB bank");
        byte[] data = input.clone();
        int fmCount = u8(data, 2), psgCount = u8(data, 3);
        if (fmCount < 1 || fmCount > 6 || psgCount > 3) fail("invalid channel counts: DAC + up to five FM, up to three PSG");
        int headerEnd = 6 + 4 * fmCount + 6 * psgCount;
        if (headerEnd > data.length) fail("truncated channel header");
        if (u8(data, 4) == 0) fail("dividing timing must be nonzero");
        int voiceStart = le16(data, 0);
        if (voiceStart != 0 && (voiceStart < headerEnd || voiceStart + 25 > data.length)) fail("local voice pointer is out of bounds");
        if (voiceStart != 0 && (data.length - voiceStart) % 25 != 0) fail("local voice table must contain complete 25-byte voices");
        int voices = voiceStart == 0 ? 0 : (data.length - voiceStart) / 25;
        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(data.length);
        if (voiceStart != 0) boundaries.add(voiceStart);
        ArrayList<Track> tracks = new ArrayList<>();
        for (int i = 0; i < fmCount + psgCount; i++) {
            boolean psg = i >= fmCount;
            int at = psg ? 6 + fmCount * 4 + (i - fmCount) * 6 : 6 + i * 4;
            int pointer = le16(data, at);
            if (pointer < headerEnd || pointer >= data.length || (voiceStart != 0 && pointer >= voiceStart)) fail("track pointer is outside the track-data region");
            if (psg && u8(data, at + 4) != 0) fail("external modulation envelopes are unsupported in this proof");
            if (psg && u8(data, at + 5) != 0) fail("external PSG envelopes are unsupported in this proof");
            boundaries.add(pointer);
            tracks.add(new Track(pointer, i == 0, psg));
        }
        for (Track track : tracks) {
            int end = boundaries.higher(track.pointer);
            scan(data, track, end, voices);
        }
        return data;
    }

    private static void scan(byte[] data, Track track, int end, int voices) {
        if (track.dac) {
            int first = u8(data, track.pointer);
            if (first != 0xF2 && first != 0xE3) fail("DAC sample playback is unsupported; DAC slot must stop immediately");
            return;
        }
        int position = track.pointer;
        while (position < end) {
            int at = position, command = u8(data, position++);
            if (command == 0xF2 || command == 0xE3) return;
            if (command == 0) fail("zero-duration event is unsupported at " + hex(at));
            if (command < 0xE0) {
                if (!track.psg && command >= 0x81 && voices == 0) fail("FM notes require a local voice table");
                continue;
            }
            if (command == 0xE7 || command == 0xFA) continue; // HOLD / modulation off
            if (command == 0xF0) {
                if (end - position < 4) fail("truncated modulation operands at " + hex(at));
                position += 4; // Delay, rate, signed delta, steps: all are raw bytes.
                continue;
            }
            boolean both = command == 0xE1 || command == 0xE4 || command == 0xE8 || command == 0xED || command == 0xFB;
            boolean fm = command == 0xE0 || command == 0xE6 || command == 0xEF;
            boolean psg = command == 0xEC || command == 0xF3;
            if (!(both || (fm && !track.psg) || (psg && track.psg))) {
                fail("unsupported command " + hex(command) + " at " + hex(at)
                        + "; this proof accepts finite linear tracks without external resources");
            }
            if (position >= end) fail("truncated command operand at " + hex(at));
            int operand = u8(data, position++);
            if (command == 0xEF && operand >= voices) fail("undefined local FM voice " + operand + " at " + hex(at));
        }
        fail("track has no stop before its data boundary at " + hex(end));
    }

    private static int le16(byte[] data, int at) { return u8(data, at) | u8(data, at + 1) << 8; }
    private static int u8(byte[] data, int at) { return data[at] & 255; }
    private static String hex(int value) { return String.format("0x%02X", value); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
    private record Track(int pointer, boolean dac, boolean psg) {}
}
