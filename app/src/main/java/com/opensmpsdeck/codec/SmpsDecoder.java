package com.opensmpsdeck.codec;

import com.opensmps.smps.SmpsCoordFlags;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes raw SMPS track bytecode into displayable tracker rows.
 *
 * <p>Uses {@link SmpsCoordFlags} for all coordination flag parameter counts
 * and semantic identification (voice set, PSG instrument, tie, etc.).
 * Flag sizes are dialect-dependent; the single-argument overloads assume the
 * S2 dialect for backward compatibility.
 *
 * <p>A bare duration byte (0x01-0x7F) after a note RE-TRIGGERS that note in
 * the driver, so it decodes as its own row (repeating the note display).
 * A leading bare duration with no preceding note only sets the duration and
 * produces no row.
 */
public class SmpsDecoder {

    /** A single decoded tracker row. */
    public record TrackerRow(String note, int duration, String instrument, String effect) {}

    /** A decoded tracker row plus its starting byte offset in the source track. */
    public record DecodedRow(int byteOffset, TrackerRow row) {}

    /**
     * Format a coordination flag and its parameters as a human-readable mnemonic.
     * Delegates to {@link EffectMnemonics#format(int, int[])}.
     */
    public static String formatEffectMnemonic(int flag, int[] params) {
        return EffectMnemonics.format(flag, params);
    }

    /** Note names for display (C, C#, D, D#, E, F, F#, G, G#, A, A#, B). */
    private static final String[] NOTE_NAMES = {
        "C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-"
    };

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

    /** Decode with the default S2 dialect. */
    public static List<TrackerRow> decode(byte[] trackData) {
        return decode(trackData, SmpsCoordFlags.Dialect.S2);
    }

    /**
     * Decode raw SMPS track data into a list of TrackerRows.
     * Each note/rest/re-trigger produces one row. Coordination flags are
     * attached to the following note's effect column, or as standalone rows
     * if no note follows.
     */
    public static List<TrackerRow> decode(byte[] trackData, SmpsCoordFlags.Dialect dialect) {
        List<DecodedRow> decoded = decodeWithOffsets(trackData, dialect);
        List<TrackerRow> rows = new ArrayList<>(decoded.size());
        for (DecodedRow row : decoded) {
            rows.add(row.row());
        }
        return rows;
    }

    /** Decode with offsets using the default S2 dialect. */
    public static List<DecodedRow> decodeWithOffsets(byte[] trackData) {
        return decodeWithOffsets(trackData, SmpsCoordFlags.Dialect.S2);
    }

    /**
     * Decode raw SMPS track data into rows with source byte offsets.
     *
     * <p>Row decoding semantics are identical to {@link #decode(byte[], SmpsCoordFlags.Dialect)}.
     */
    public static List<DecodedRow> decodeWithOffsets(byte[] trackData, SmpsCoordFlags.Dialect dialect) {
        if (trackData == null || trackData.length == 0) {
            return List.of();
        }

        int pos = 0;
        int currentDuration = 0;
        int lastNoteByte = -1;
        StringBuilder pendingEffect = new StringBuilder();
        String pendingInstrument = "";
        int pendingRowOffset = -1;
        List<DecodedRow> rows = new ArrayList<>();

        while (pos < trackData.length) {
            int b = trackData[pos] & 0xFF;

            if (b == 0x00 || SmpsCoordFlags.isTrackEnd(b, dialect)) {
                // Track end - stop decoding.
                break;
            } else if (b >= 0x80 && b <= 0xDF) {
                // Note or rest.
                int noteOffset = pos;
                String note = decodeNote(b);
                lastNoteByte = b;
                pos++;

                // Check for inline duration byte.
                if (pos < trackData.length) {
                    int next = trackData[pos] & 0xFF;
                    if (next >= 0x01 && next <= 0x7F) {
                        currentDuration = next;
                        pos++;
                    }
                }

                String effect = pendingEffect.length() > 0 ? pendingEffect.toString().trim() : "";
                String inst = pendingInstrument;
                rows.add(new DecodedRow(noteOffset, new TrackerRow(note, currentDuration, inst, effect)));
                pendingEffect.setLength(0);
                pendingInstrument = "";
                pendingRowOffset = -1;

            } else if (b >= 0x01 && b <= 0x7F) {
                // Bare duration: re-triggers the previous note in the driver,
                // so it is a row of its own. With no preceding note it only
                // sets the duration.
                currentDuration = b;
                if (lastNoteByte >= 0) {
                    String effect = pendingEffect.length() > 0 ? pendingEffect.toString().trim() : "";
                    rows.add(new DecodedRow(pos, new TrackerRow(
                            decodeNote(lastNoteByte), currentDuration, pendingInstrument, effect)));
                    pendingEffect.setLength(0);
                    pendingInstrument = "";
                    pendingRowOffset = -1;
                }
                pos++;

            } else if (b >= 0xE0) {
                // Coordination flag.
                int paramCount = flagSpan(trackData, pos, b, dialect);
                if (pendingRowOffset < 0) {
                    pendingRowOffset = pos;
                }

                if (SmpsCoordFlags.isSetVoice(b) || SmpsCoordFlags.isPsgInstrument(b)) {
                    // Voice/envelope set - store as instrument column.
                    if (pos + 1 < trackData.length) {
                        pendingInstrument = String.format("%02X", trackData[pos + 1] & 0xFF);
                    }
                    pos += 1 + paramCount;
                } else if (b == SmpsCoordFlags.TIE) {
                    // Tie - add as a row.
                    String effect = pendingEffect.length() > 0 ? pendingEffect.toString().trim() : "";
                    rows.add(new DecodedRow(pos, new TrackerRow("===", currentDuration, pendingInstrument, effect)));
                    pendingEffect.setLength(0);
                    pendingInstrument = "";
                    pendingRowOffset = -1;
                    pos++;
                } else {
                    // Other coordination flags - format as hex effect string.
                    StringBuilder flagStr = new StringBuilder(String.format("%02X", b));
                    for (int p = 0; p < paramCount && (pos + 1 + p) < trackData.length; p++) {
                        flagStr.append(String.format(" %02X", trackData[pos + 1 + p] & 0xFF));
                    }
                    if (pendingEffect.length() > 0) pendingEffect.append("; ");
                    pendingEffect.append(flagStr);
                    pos += 1 + paramCount;
                }
            } else {
                // Unknown byte - skip.
                pos++;
            }
        }

        // Flush any trailing effects/instrument as a row.
        if (pendingEffect.length() > 0 || !pendingInstrument.isEmpty()) {
            int offset = pendingRowOffset >= 0 ? pendingRowOffset : pos;
            rows.add(new DecodedRow(offset, new TrackerRow("", 0, pendingInstrument, pendingEffect.toString().trim())));
        }

        return rows;
    }

    /**
     * Parameter byte count for the flag at {@code pos} in the dialect,
     * including the S3K FF meta sub-command's parameters.
     */
    static int flagSpan(byte[] data, int pos, int cmd, SmpsCoordFlags.Dialect dialect) {
        int params = SmpsCoordFlags.getParamCount(cmd, dialect);
        if (dialect == SmpsCoordFlags.Dialect.S3K && cmd == SmpsCoordFlags.S3K_META
                && pos + 1 < data.length) {
            params += SmpsCoordFlags.getMetaParamCount(data[pos + 1] & 0xFF);
        }
        return params;
    }
}
