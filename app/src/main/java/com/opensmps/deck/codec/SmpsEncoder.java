package com.opensmps.deck.codec;

import com.opensmps.smps.SmpsCoordFlags;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes user input into SMPS bytecode for insertion into track data.
 *
 * <p>Uses {@link SmpsCoordFlags} for all coordination flag byte values
 * and parameter counts to ensure consistency with the Z80 driver.
 */
public class SmpsEncoder {

    /** Default duration for new notes (in frames). */
    public static final int DEFAULT_DURATION = 0x18;

    /**
     * Piano keyboard layout mapping: key character -> semitone offset.
     * Lower row (Z-M) = current octave, Upper row (Q-U) = current octave + 1.
     * Standard tracker convention (like MilkyTracker, Famitracker).
     */
    private static final int[] LOWER_ROW_SEMITONES = new int[128]; // indexed by char
    private static final int[] UPPER_ROW_SEMITONES = new int[128];
    static {
        Arrays.fill(LOWER_ROW_SEMITONES, -1);
        Arrays.fill(UPPER_ROW_SEMITONES, -1);
        // Lower row: Z=C, S=C#, X=D, D=D#, C=E, V=F, G=F#, B=G, H=G#, N=A, J=A#, M=B
        LOWER_ROW_SEMITONES['Z'] = 0;  // C
        LOWER_ROW_SEMITONES['S'] = 1;  // C#
        LOWER_ROW_SEMITONES['X'] = 2;  // D
        LOWER_ROW_SEMITONES['D'] = 3;  // D#
        LOWER_ROW_SEMITONES['C'] = 4;  // E
        LOWER_ROW_SEMITONES['V'] = 5;  // F
        LOWER_ROW_SEMITONES['G'] = 6;  // F#
        LOWER_ROW_SEMITONES['B'] = 7;  // G
        LOWER_ROW_SEMITONES['H'] = 8;  // G#
        LOWER_ROW_SEMITONES['N'] = 9;  // A
        LOWER_ROW_SEMITONES['J'] = 10; // A#
        LOWER_ROW_SEMITONES['M'] = 11; // B

        // Upper row: Q=C, 2=C#, W=D, 3=D#, E=E, R=F, 5=F#, T=G, 6=G#, Y=A, 7=A#, U=B
        UPPER_ROW_SEMITONES['Q'] = 0;  // C
        UPPER_ROW_SEMITONES['2'] = 1;  // C#
        UPPER_ROW_SEMITONES['W'] = 2;  // D
        UPPER_ROW_SEMITONES['3'] = 3;  // D#
        UPPER_ROW_SEMITONES['E'] = 4;  // E
        UPPER_ROW_SEMITONES['R'] = 5;  // F
        UPPER_ROW_SEMITONES['5'] = 6;  // F#
        UPPER_ROW_SEMITONES['T'] = 7;  // G
        UPPER_ROW_SEMITONES['6'] = 8;  // G#
        UPPER_ROW_SEMITONES['Y'] = 9;  // A
        UPPER_ROW_SEMITONES['7'] = 10; // A#
        UPPER_ROW_SEMITONES['U'] = 11; // B
    }

    /**
     * Encode a note for the given key character and octave.
     * Returns the SMPS note byte (0x81-0xDF), or -1 if not a note key.
     */
    public static int encodeNoteFromKey(char key, int currentOctave) {
        char upper = Character.toUpperCase(key);
        int semitone = -1;
        int octave = currentOctave;

        if (upper < 128 && LOWER_ROW_SEMITONES[upper] >= 0) {
            semitone = LOWER_ROW_SEMITONES[upper];
        } else if (upper < 128 && UPPER_ROW_SEMITONES[upper] >= 0) {
            semitone = UPPER_ROW_SEMITONES[upper];
            octave = currentOctave + 1;
        }

        if (semitone < 0) return -1;

        int noteValue = 0x81 + octave * 12 + semitone;
        if (noteValue < 0x81) return 0x81; // clamp low
        if (noteValue > 0xDF) return 0xDF; // clamp high
        return noteValue;
    }

    /**
     * Encode a note + duration pair as SMPS bytes.
     * Returns a 2-byte array: [noteValue, duration].
     */
    public static byte[] encodeNote(int noteValue, int duration) {
        return new byte[]{ (byte) noteValue, (byte) duration };
    }

    /**
     * Encode a rest with a duration.
     */
    public static byte[] encodeRest(int duration) {
        return new byte[]{ (byte) 0x80, (byte) duration };
    }

    /**
     * Encode a tie (sustain without re-keying).
     */
    public static byte[] encodeTie() {
        return new byte[]{ (byte) SmpsCoordFlags.TIE };
    }

    /**
     * Encode an FM voice change (EF xx).
     */
    public static byte[] encodeVoiceChange(int voiceId) {
        return new byte[]{ (byte) SmpsCoordFlags.SET_VOICE, (byte) voiceId };
    }

    /**
     * Encode a PSG envelope/instrument change (F5 xx).
     */
    public static byte[] encodePsgEnvelope(int envelopeId) {
        return new byte[]{ (byte) SmpsCoordFlags.PSG_INSTRUMENT, (byte) envelopeId };
    }

    /**
     * Transpose a note byte by the given number of semitones.
     * Clamps to the valid range 0x81-0xDF.
     * Returns the original byte unchanged if it's not a note (rest, flag, etc).
     */
    public static int transpose(int noteByte, int semitones) {
        int unsigned = noteByte & 0xFF;
        if (unsigned < 0x81 || unsigned > 0xDF) return noteByte;
        int result = unsigned + semitones;
        if (result < 0x81) result = 0x81;
        if (result > 0xDF) result = 0xDF;
        return result;
    }

    /**
     * Insert SMPS bytes into track data at a specific decoded row position.
     * This replaces the bytes corresponding to the row at {@code rowIndex} in the decoded view,
     * or appends if rowIndex is beyond the current data.
     *
     * @param trackData current track byte array
     * @param rowIndex the decoded row index to insert/replace at
     * @param newBytes the new SMPS bytes to insert
     * @return the new track byte array
     */
    public static byte[] insertAtRow(byte[] trackData, int rowIndex, byte[] newBytes) {
        // Decode to find byte offsets
        if (trackData == null) trackData = new byte[0];

        // Find the byte offset of the target row
        int[] rowOffsets = findRowByteOffsets(trackData);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (rowIndex < rowOffsets.length) {
            int startOffset = rowOffsets[rowIndex];
            int endOffset = (rowIndex + 1 < rowOffsets.length) ? rowOffsets[rowIndex + 1] : trackData.length;

            // Write bytes before the target row
            out.write(trackData, 0, startOffset);
            // Write new bytes
            out.write(newBytes, 0, newBytes.length);
            // Write bytes after the target row
            if (endOffset < trackData.length) {
                out.write(trackData, endOffset, trackData.length - endOffset);
            }
        } else {
            // Append
            out.write(trackData, 0, trackData.length);
            out.write(newBytes, 0, newBytes.length);
        }

        return out.toByteArray();
    }

    /**
     * Delete the SMPS bytes at a specific decoded row position.
     *
     * @param trackData current track byte array
     * @param rowIndex the decoded row index to delete
     * @return the new track byte array with the row removed
     */
    public static byte[] deleteRow(byte[] trackData, int rowIndex) {
        if (trackData == null || trackData.length == 0) return new byte[0];

        int[] rowOffsets = findRowByteOffsets(trackData);
        if (rowIndex >= rowOffsets.length) return trackData;

        int startOffset = rowOffsets[rowIndex];
        int endOffset = (rowIndex + 1 < rowOffsets.length) ? rowOffsets[rowIndex + 1] : trackData.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(trackData, 0, startOffset);
        if (endOffset < trackData.length) {
            out.write(trackData, endOffset, trackData.length - endOffset);
        }
        return out.toByteArray();
    }

    /**
     * Transpose note bytes in a track within a specific row range.
     * Non-note bytes (rests, coordination flags) are left unchanged.
     *
     * @param trackData the track byte array
     * @param startRow first row to transpose (inclusive)
     * @param rowCount number of rows to transpose
     * @param semitones semitones to transpose (+/-)
     * @return new track data with transposed notes
     */
    public static byte[] transposeTrackRange(byte[] trackData, int startRow, int rowCount, int semitones) {
        if (trackData == null || trackData.length == 0) return trackData;
        byte[] result = trackData.clone();
        int[] offsets = findRowByteOffsets(result);

        int endRow = Math.min(startRow + rowCount, offsets.length);
        for (int row = startRow; row < endRow; row++) {
            int offset = offsets[row];
            int b = result[offset] & 0xFF;
            if (b >= 0x81 && b <= 0xDF) {
                result[offset] = (byte) transpose(b, semitones);
            }
        }
        return result;
    }

    /**
     * Extract the raw bytes for a range of decoded rows.
     *
     * @param trackData the track byte array
     * @param startRow first row (inclusive)
     * @param rowCount number of rows
     * @return byte array containing just the selected rows' bytes
     */
    public static byte[] extractRowRange(byte[] trackData, int startRow, int rowCount) {
        if (trackData == null || trackData.length == 0) return new byte[0];
        int[] offsets = findRowByteOffsets(trackData);

        if (startRow >= offsets.length) return new byte[0];

        int startByte = offsets[startRow];
        int endRow = Math.min(startRow + rowCount, offsets.length);
        int endByte = (endRow < offsets.length) ? offsets[endRow] : trackData.length;

        // Strip any trailing track-end marker from the extracted bytes
        int len = endByte - startByte;
        byte[] extracted = new byte[len];
        System.arraycopy(trackData, startByte, extracted, 0, len);
        return extracted;
    }

    /**
     * Set or replace the instrument change command for a decoded row.
     *
     * <p>If the row already has a SET_VOICE (EF) or PSG_INSTRUMENT (F5) command
     * in the coordination flags preceding it, the parameter byte is updated in-place.
     * Otherwise, the instrument bytes are inserted immediately before the row's
     * note/rest byte.
     *
     * @param trackData current track byte array
     * @param rowIndex the decoded row index to modify
     * @param instrFlag the coordination flag byte (e.g. SmpsCoordFlags.SET_VOICE or PSG_INSTRUMENT)
     * @param instrValue the instrument index (0x00-0xFF)
     * @return the new track byte array, or the original if rowIndex is out of range
     */
    public static byte[] setRowInstrument(byte[] trackData, int rowIndex, int instrFlag, int instrValue) {
        if (trackData == null || trackData.length == 0) return trackData;

        int[] rowOffsets = findRowByteOffsets(trackData);
        if (rowIndex >= rowOffsets.length) return trackData;

        int rowStart = rowOffsets[rowIndex];

        // Determine the region before this row that could contain coordination flags.
        // Flags for this row sit between the previous row's end and this row's note byte.
        int scanStart;
        if (rowIndex > 0) {
            // End of previous row: its note byte + optional duration
            int prevNotePos = rowOffsets[rowIndex - 1];
            scanStart = prevNotePos + 1;
            if (scanStart < trackData.length) {
                int next = trackData[scanStart] & 0xFF;
                if (next >= 0x01 && next <= 0x7F) {
                    scanStart++; // skip duration byte
                }
            }
        } else {
            scanStart = 0;
        }

        // Scan for an existing matching instrument flag in [scanStart, rowStart)
        int pos = scanStart;
        while (pos < rowStart) {
            int b = trackData[pos] & 0xFF;
            if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b);
                if (b == instrFlag && paramCount >= 1 && pos + 1 < trackData.length) {
                    // Found existing instrument flag -- update its parameter in-place
                    byte[] result = trackData.clone();
                    result[pos + 1] = (byte) instrValue;
                    return result;
                }
                pos += 1 + paramCount;
            } else {
                pos++;
            }
        }

        // No existing flag found -- insert instrFlag + instrValue before the row's note byte
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(trackData, 0, rowStart);
        out.write(instrFlag);
        out.write(instrValue);
        if (rowStart < trackData.length) {
            out.write(trackData, rowStart, trackData.length - rowStart);
        }
        return out.toByteArray();
    }

    /**
     * Find the byte offset for each decoded row in the track data.
     * Uses {@link SmpsCoordFlags#getParamCount(int)} for consistent flag parsing.
     */
    static int[] findRowByteOffsets(byte[] trackData) {
        if (trackData == null || trackData.length == 0) return new int[0];

        List<Integer> offsets = new ArrayList<>();
        int pos = 0;

        while (pos < trackData.length) {
            int b = trackData[pos] & 0xFF;

            if (b == 0x00 || b == SmpsCoordFlags.STOP) {
                break;
            } else if (b >= 0x80 && b <= 0xDF) {
                // Note/rest -- this is a row
                offsets.add(pos);
                pos++;
                // Skip duration if present
                if (pos < trackData.length) {
                    int next = trackData[pos] & 0xFF;
                    if (next >= 0x01 && next <= 0x7F) {
                        pos++;
                    }
                }
            } else if (b >= 0x01 && b <= 0x7F) {
                pos++;
            } else if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b);

                if (b == SmpsCoordFlags.TIE) {
                    // Tie is also a row
                    offsets.add(pos);
                    pos++;
                } else {
                    pos += 1 + paramCount;
                }
            } else {
                pos++;
            }
        }

        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }
}
