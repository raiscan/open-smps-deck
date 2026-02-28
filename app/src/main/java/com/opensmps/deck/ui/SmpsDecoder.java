package com.opensmps.deck.ui;

import java.util.ArrayList;
import java.util.List;

public class SmpsDecoder {

    /** A single decoded tracker row. */
    public record TrackerRow(String note, int duration, String instrument, String effect) {}

    /** Note names for display (C, C#, D, D#, E, F, F#, G, G#, A, A#, B) */
    private static final String[] NOTE_NAMES = {
        "C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-"
    };

    /**
     * Coordination flag parameter counts.
     * Index = flag byte - 0xE0.
     * -1 means unknown/variable (treat as 0 for safety).
     */
    private static final int[] COORD_FLAG_PARAMS = new int[32];
    static {
        // Default all to 0 (no params)
        COORD_FLAG_PARAMS[0x00] = 1; // E0: Pan (1 param)
        COORD_FLAG_PARAMS[0x01] = 1; // E1: Set FM voice (1 param)
        COORD_FLAG_PARAMS[0x02] = 1; // E2: 1 param
        COORD_FLAG_PARAMS[0x03] = 1; // E3: 1 param
        COORD_FLAG_PARAMS[0x04] = 1; // E4: Set PSG envelope (1 param)
        COORD_FLAG_PARAMS[0x05] = 1; // E5: 1 param
        COORD_FLAG_PARAMS[0x06] = 1; // E6: 1 param
        COORD_FLAG_PARAMS[0x07] = 0; // E7: Tie (0 params)
        COORD_FLAG_PARAMS[0x08] = 0; // E8: 0 params
        COORD_FLAG_PARAMS[0x09] = 1; // E9: Key offset (1 param)
        COORD_FLAG_PARAMS[0x0A] = 1; // EA: Detune (1 param)
        COORD_FLAG_PARAMS[0x0B] = 0; // EB: 0 params
        COORD_FLAG_PARAMS[0x0C] = 0; // EC: 0 params
        COORD_FLAG_PARAMS[0x0D] = 0; // ED: 0 params
        COORD_FLAG_PARAMS[0x0E] = 0; // EE: 0 params
        COORD_FLAG_PARAMS[0x0F] = 2; // EF: 2 params
        COORD_FLAG_PARAMS[0x10] = 4; // F0: Modulation (4 params)
        COORD_FLAG_PARAMS[0x11] = 0; // F1: Disable modulation (0 params)
        COORD_FLAG_PARAMS[0x12] = 0; // F2: Track end (0 params)
        COORD_FLAG_PARAMS[0x13] = 3; // F3: Loop (3 params)
        COORD_FLAG_PARAMS[0x14] = 2; // F4: Jump (2 params)
        COORD_FLAG_PARAMS[0x15] = 2; // F5: Call subroutine (2 params)
        COORD_FLAG_PARAMS[0x16] = 0; // F6: Return (0 params)
        // F7-FF: default 0
    }

    /**
     * Decode a note byte (0x81-0xDF) into a display string like "C-5" or "D#3".
     */
    public static String decodeNote(int noteByte) {
        if (noteByte == 0x80) return "---"; // rest
        if (noteByte < 0x81 || noteByte > 0xDF) return "???";

        int index = (noteByte & 0xFF) - 0x81;
        int octave = index / 12;
        int semitone = index % 12;
        return NOTE_NAMES[semitone] + octave;
    }

    /** Get the parameter count for a coordination flag. */
    public static int getCoordFlagParamCount(int flagIndex) {
        if (flagIndex < 0 || flagIndex >= COORD_FLAG_PARAMS.length) return 0;
        return COORD_FLAG_PARAMS[flagIndex];
    }

    /**
     * Decode raw SMPS track data into a list of TrackerRows.
     * Each note/rest produces one row. Coordination flags are attached to the
     * following note's effect column, or as standalone rows if no note follows.
     */
    public static List<TrackerRow> decode(byte[] trackData) {
        List<TrackerRow> rows = new ArrayList<>();
        if (trackData == null || trackData.length == 0) return rows;

        int pos = 0;
        int currentDuration = 0;
        StringBuilder pendingEffect = new StringBuilder();
        String pendingInstrument = "";

        while (pos < trackData.length) {
            int b = trackData[pos] & 0xFF;

            if (b == 0x00 || b == 0xF2) {
                // Track end — stop decoding
                break;
            } else if (b >= 0x80 && b <= 0xDF) {
                // Note or rest
                String note = decodeNote(b);
                pos++;

                // Check for duration byte
                if (pos < trackData.length) {
                    int next = trackData[pos] & 0xFF;
                    if (next >= 0x01 && next <= 0x7F) {
                        currentDuration = next;
                        pos++;
                    }
                }

                String effect = pendingEffect.length() > 0 ? pendingEffect.toString().trim() : "";
                String inst = pendingInstrument;
                rows.add(new TrackerRow(note, currentDuration, inst, effect));
                pendingEffect.setLength(0);
                pendingInstrument = "";

            } else if (b >= 0x01 && b <= 0x7F) {
                // Bare duration (re-trigger with previous note's duration)
                currentDuration = b;
                pos++;

            } else if (b >= 0xE0) {
                // Coordination flag
                int flagIndex = b - 0xE0;
                int paramCount = (flagIndex < COORD_FLAG_PARAMS.length)
                    ? COORD_FLAG_PARAMS[flagIndex] : 0;

                if (b == 0xE1 || b == 0xE4) {
                    // Voice/envelope set — store as instrument
                    if (pos + 1 < trackData.length) {
                        pendingInstrument = String.format("%02X", trackData[pos + 1] & 0xFF);
                    }
                    pos += 1 + paramCount;
                } else if (b == 0xE7) {
                    // Tie — add as a row
                    String effect = pendingEffect.length() > 0 ? pendingEffect.toString().trim() : "";
                    rows.add(new TrackerRow("===", currentDuration, pendingInstrument, effect));
                    pendingEffect.setLength(0);
                    pendingInstrument = "";
                    pos++;
                } else {
                    // Other coordination flags — format as hex effect string
                    StringBuilder flagStr = new StringBuilder(String.format("%02X", b));
                    for (int p = 0; p < paramCount && (pos + 1 + p) < trackData.length; p++) {
                        flagStr.append(String.format(" %02X", trackData[pos + 1 + p] & 0xFF));
                    }
                    if (pendingEffect.length() > 0) pendingEffect.append("; ");
                    pendingEffect.append(flagStr);
                    pos += 1 + paramCount;
                }
            } else {
                // Unknown byte — skip
                pos++;
            }
        }

        // Flush any trailing effects as a row
        if (pendingEffect.length() > 0 || !pendingInstrument.isEmpty()) {
            rows.add(new TrackerRow("", 0, pendingInstrument, pendingEffect.toString().trim()));
        }

        return rows;
    }
}
