package com.opensmps.deck.codec;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.SmpsMode;
import com.opensmps.deck.model.Song;
import com.opensmps.smps.SmpsCoordFlags;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a {@link Song} model into a raw SMPS binary blob that can be
 * played by the SmpsSequencer.
 *
 * <p>The output layout is:
 * <pre>
 *   [HEADER] [TRACK_DATA_CH0] [TRACK_DATA_CH1] ... [VOICE_TABLE]
 * </pre>
 *
 * <p>All multi-byte values are little-endian. Pointers are file-relative
 * (offset from byte 0 of the output array).
 */
public class PatternCompiler {

    private static final int FM_CHANNEL_COUNT = 6;
    private static final int PSG_CHANNEL_COUNT = 4;
    private static final int TOTAL_CHANNELS = Pattern.CHANNEL_COUNT; // 10

    private static final int HEADER_BASE_SIZE = 6;
    private static final int FM_TRACK_HEADER_SIZE = 4;
    private static final int PSG_TRACK_HEADER_SIZE = 6;

    private static final int CMD_TRACK_END = SmpsCoordFlags.STOP;
    private static final int CMD_JUMP = SmpsCoordFlags.JUMP;

    /**
     * Compiles the given song into an SMPS binary byte array using
     * the song's own {@link SmpsMode}.
     *
     * @param song the song to compile
     * @return the compiled SMPS binary data
     */
    public byte[] compile(Song song) {
        return compile(song, song.getSmpsMode());
    }

    /**
     * Compiles the given song into an SMPS binary byte array for
     * the specified target mode.
     *
     * <p>When targeting S1 or S3K, note bytes (0x81-0xDF) are shifted
     * +1 to compensate for the lower {@code baseNoteOffset} used by
     * those drivers at playback time. This ensures the same pitch
     * regardless of target mode.
     *
     * @param song the song to compile
     * @param mode the target SMPS driver mode
     * @return the compiled SMPS binary data
     */
    public byte[] compile(Song song, SmpsMode mode) {
        int noteCompensation = switch (mode) {
            case S1, S3K -> 1;  // shift notes up by 1 to compensate for lower base
            case S2 -> 0;       // S2 is the native format
        };

        // Defensive snapshot — compile reads orderList and patterns which the UI thread may modify
        List<int[]> orderList = new ArrayList<>();
        for (int[] row : song.getOrderList()) {
            orderList.add(row.clone());
        }
        List<Pattern> patterns = new ArrayList<>(song.getPatterns());

        // 1. Determine active channels and build compiled track data for each
        List<Integer> activeFmChannels = new ArrayList<>();
        List<Integer> activePsgChannels = new ArrayList<>();
        List<byte[]> compiledTracks = new ArrayList<>(); // parallel to active channels
        List<Integer> loopOffsets = new ArrayList<>();    // track-relative loop target for each

        for (int ch = 0; ch < TOTAL_CHANNELS; ch++) {
            if (!isChannelActive(orderList, patterns, ch)) {
                continue;
            }
            if (ch < FM_CHANNEL_COUNT) {
                activeFmChannels.add(ch);
            } else {
                activePsgChannels.add(ch);
            }

            byte[] trackData = buildTrackData(orderList, patterns, ch, noteCompensation);
            int loopTarget = calculateLoopTarget(song.getLoopPoint(), orderList, patterns, ch);
            // Append F6 (JUMP) + 2-byte LE placeholder (track-relative offset, patched later)
            byte[] withJump = new byte[trackData.length + 3];
            System.arraycopy(trackData, 0, withJump, 0, trackData.length);
            withJump[trackData.length] = (byte) CMD_JUMP;
            withJump[trackData.length + 1] = (byte) (loopTarget & 0xFF);
            withJump[trackData.length + 2] = (byte) ((loopTarget >> 8) & 0xFF);

            compiledTracks.add(withJump);
            loopOffsets.add(loopTarget);
        }

        int fmCount = activeFmChannels.size();
        int psgCount = activePsgChannels.size();

        // 2. Calculate header size
        int headerSize = HEADER_BASE_SIZE
                + (fmCount * FM_TRACK_HEADER_SIZE)
                + (psgCount * PSG_TRACK_HEADER_SIZE);

        // 3. Calculate file-relative offsets for each track
        int[] trackOffsets = new int[compiledTracks.size()];
        int cursor = headerSize;
        for (int i = 0; i < compiledTracks.size(); i++) {
            trackOffsets[i] = cursor;
            cursor += compiledTracks.get(i).length;
        }

        // 4. Voice table offset
        int voiceTableOffset = cursor;
        int voiceDataLength = song.getVoiceBank().size() * FmVoice.VOICE_SIZE;

        // 5. Patch F6 (JUMP) loop targets: convert from track-relative to file-relative
        for (int i = 0; i < compiledTracks.size(); i++) {
            byte[] track = compiledTracks.get(i);
            // The last 3 bytes are F6 (JUMP) + 2-byte LE pointer
            int jumpPos = track.length - 3;
            int trackRelTarget = (track[jumpPos + 1] & 0xFF)
                    | ((track[jumpPos + 2] & 0xFF) << 8);
            int fileRelTarget = trackRelTarget + trackOffsets[i];
            track[jumpPos + 1] = (byte) (fileRelTarget & 0xFF);
            track[jumpPos + 2] = (byte) ((fileRelTarget >> 8) & 0xFF);
        }

        // 6. Assemble the final output
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    headerSize + (voiceTableOffset - headerSize) + voiceDataLength);

            // -- Header --
            // Voice table pointer (LE)
            int voicePtr = song.getVoiceBank().isEmpty() ? 0 : voiceTableOffset;
            writeLE16(out, voicePtr);
            // FM channel count, PSG channel count
            out.write(fmCount);
            out.write(psgCount);
            // Dividing timing, Tempo
            out.write(song.getDividingTiming() & 0xFF);
            out.write(song.getTempo() & 0xFF);

            // FM channel headers
            int trackIndex = 0;
            for (int i = 0; i < fmCount; i++) {
                writeLE16(out, trackOffsets[trackIndex]);
                out.write(0); // key offset
                out.write(0); // volume offset
                trackIndex++;
            }

            // PSG channel headers
            for (int i = 0; i < psgCount; i++) {
                writeLE16(out, trackOffsets[trackIndex]);
                out.write(0); // key offset
                out.write(0); // volume offset
                out.write(0); // modulation envelope ID
                out.write(0); // PSG instrument/envelope ID
                trackIndex++;
            }

            // -- Track data --
            for (byte[] track : compiledTracks) {
                out.write(track);
            }

            // -- Voice table --
            for (FmVoice voice : song.getVoiceBank()) {
                out.write(voice.getDataUnsafe());
            }

            return out.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws IOException in practice
            throw new RuntimeException("Unexpected I/O error during compilation", e);
        }
    }

    /**
     * Checks whether a channel has any non-empty track data across all
     * patterns referenced by the order list.
     */
    private boolean isChannelActive(List<int[]> orderList, List<Pattern> patterns, int channel) {
        for (int[] orderRow : orderList) {
            int patternIndex = orderRow[channel];
            if (patternIndex >= 0 && patternIndex < patterns.size()) {
                byte[] data = patterns.get(patternIndex).getTrackDataDirect(channel);
                if (data != null && data.length > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds concatenated track data for a channel across all order rows,
     * stripping trailing F2 (track end) bytes between patterns and applying
     * note compensation for the target SMPS mode.
     *
     * @param noteCompensation offset to add to note bytes (0x81-0xDF); 0 for S2, +1 for S1/S3K
     */
    private byte[] buildTrackData(List<int[]> orderList, List<Pattern> patterns, int channel,
                                  int noteCompensation) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        for (int[] orderRow : orderList) {
            int patternIndex = orderRow[channel];
            byte[] data;
            if (patternIndex >= 0 && patternIndex < patterns.size()) {
                data = patterns.get(patternIndex).getTrackDataDirect(channel);
            } else {
                data = new byte[0];
            }
            if (data == null || data.length == 0) {
                continue;
            }

            // Strip trailing F2 bytes
            int end = data.length;
            while (end > 0 && (data[end - 1] & 0xFF) == CMD_TRACK_END) {
                end--;
            }

            if (end > 0) {
                if (noteCompensation == 0) {
                    // No compensation needed — copy directly
                    buf.write(data, 0, end);
                } else {
                    // Walk the bytecode to find and adjust note bytes
                    applyNoteCompensation(buf, data, end, noteCompensation);
                }
            }
        }

        return buf.toByteArray();
    }

    /**
     * Walks SMPS bytecode, applies note compensation to note bytes (0x81-0xDF),
     * and writes the result to the output buffer. Coordination flag bytes
     * (0xE0-0xFF) and their parameters, duration bytes (0x01-0x7F), and rest
     * bytes (0x80) are written unchanged.
     */
    private void applyNoteCompensation(ByteArrayOutputStream buf, byte[] data, int end,
                                       int noteCompensation) {
        int pos = 0;
        while (pos < end) {
            int b = data[pos] & 0xFF;

            if (b >= 0xE0) {
                // Coordination flag — write flag byte + all parameter bytes unchanged
                buf.write(b);
                pos++;
                int paramCount = SmpsCoordFlags.getParamCount(b);
                for (int p = 0; p < paramCount && pos < end; p++) {
                    buf.write(data[pos] & 0xFF);
                    pos++;
                }
            } else if (b >= 0x81 && b <= 0xDF) {
                // Note byte — apply compensation and clamp
                int adjusted = b + noteCompensation;
                adjusted = Math.max(0x81, Math.min(0xDF, adjusted));
                buf.write(adjusted);
                pos++;
            } else {
                // Rest (0x80) or duration (0x01-0x7F) or zero — write unchanged
                buf.write(b);
                pos++;
            }
        }
    }

    /**
     * Calculates the track-relative byte offset of the loop point for a channel.
     * The loop point is the cumulative byte position at which the loop-point
     * order row's data begins within the concatenated track.
     */
    private int calculateLoopTarget(int loopRow, List<int[]> orderList, List<Pattern> patterns, int channel) {
        int offset = 0;
        for (int row = 0; row < orderList.size(); row++) {
            if (row == loopRow) {
                return offset;
            }
            int patternIndex = orderList.get(row)[channel];
            byte[] data;
            if (patternIndex >= 0 && patternIndex < patterns.size()) {
                data = patterns.get(patternIndex).getTrackDataDirect(channel);
            } else {
                data = new byte[0];
            }
            if (data != null && data.length > 0) {
                // Same stripping logic as buildTrackData
                int end = data.length;
                while (end > 0 && (data[end - 1] & 0xFF) == CMD_TRACK_END) {
                    end--;
                }
                offset += end;
            }
        }

        // If loop point is beyond order list, loop to start
        return 0;
    }

    private static void writeLE16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
