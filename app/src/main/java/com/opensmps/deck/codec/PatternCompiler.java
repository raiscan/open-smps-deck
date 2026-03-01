package com.opensmps.deck.codec;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.SmpsMode;
import com.opensmps.deck.model.Song;
import com.opensmps.smps.SmpsCoordFlags;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int TOTAL_CHANNELS = Pattern.CHANNEL_COUNT; // 10
    private static final int DAC_CHANNEL = 5;

    private static final int HEADER_BASE_SIZE = 6;
    private static final int FM_TRACK_HEADER_SIZE = 4;
    private static final int PSG_TRACK_HEADER_SIZE = 6;

    private static final int CMD_TRACK_END = SmpsCoordFlags.STOP;
    private static final int CMD_JUMP = SmpsCoordFlags.JUMP;

    /**
     * Row position resolved from a compiled channel timeline.
     */
    public record CursorPosition(int orderIndex, int rowIndex) {}

    /**
     * Row timeline for a compiled channel track.
     */
    public static final class ChannelTimeline {
        private final int channel;
        private final int trackOffset;
        private final int[] rowOffsets;
        private final int[] orderIndices;
        private final int[] rowIndices;

        ChannelTimeline(int channel,
                        int trackOffset,
                        int[] rowOffsets,
                        int[] orderIndices,
                        int[] rowIndices) {
            this.channel = channel;
            this.trackOffset = trackOffset;
            this.rowOffsets = rowOffsets;
            this.orderIndices = orderIndices;
            this.rowIndices = rowIndices;
        }

        /** UI channel index this timeline represents (0-9). */
        public int getChannel() {
            return channel;
        }

        /** Absolute byte offset where this track starts in the compiled file. */
        public int getTrackOffset() {
            return trackOffset;
        }

        /** Number of decoded rows represented in this timeline. */
        public int getRowCount() {
            return rowOffsets.length;
        }

        /**
         * Resolve a sequencer absolute byte position to an order/pattern row.
         *
         * @param absolutePosition absolute position from sequencer debug state
         * @return resolved cursor position, or {@code null} when no rows exist
         */
        public CursorPosition resolvePosition(int absolutePosition) {
            if (rowOffsets.length == 0) {
                return null;
            }
            int relative = absolutePosition - trackOffset;
            int idx = Arrays.binarySearch(rowOffsets, relative);
            if (idx < 0) {
                idx = -idx - 2;
            }
            if (idx < 0) {
                idx = 0;
            } else if (idx >= rowOffsets.length) {
                idx = rowOffsets.length - 1;
            }
            return new CursorPosition(orderIndices[idx], rowIndices[idx]);
        }
    }

    /**
     * Full compile output: bytes plus row timelines per channel.
     */
    public static final class CompilationResult {
        private final byte[] smpsData;
        private final ChannelTimeline[] timelinesByChannel;

        CompilationResult(byte[] smpsData, ChannelTimeline[] timelinesByChannel) {
            this.smpsData = smpsData;
            this.timelinesByChannel = timelinesByChannel;
        }

        /**
         * Returns compiled SMPS bytes.
         */
        public byte[] getSmpsData() {
            return smpsData.clone();
        }

        /**
         * Returns compiled SMPS bytes without cloning.
         *
         * <p>Callers must treat the array as immutable.
         */
        public byte[] getSmpsDataUnsafe() {
            return smpsData;
        }

        /**
         * Returns timeline metadata for the given UI channel, or null if inactive.
         */
        public ChannelTimeline getChannelTimeline(int channel) {
            if (channel < 0 || channel >= timelinesByChannel.length) {
                return null;
            }
            return timelinesByChannel[channel];
        }

        /**
         * Resolve a channel position to an order/row cursor location.
         */
        public CursorPosition resolveChannelPosition(int channel, int absolutePosition) {
            ChannelTimeline timeline = getChannelTimeline(channel);
            return timeline != null ? timeline.resolvePosition(absolutePosition) : null;
        }
    }

    /**
     * Compiles the given song into an SMPS binary byte array using
     * the song's own {@link SmpsMode}.
     *
     * @param song the song to compile
     * @return the compiled SMPS binary data
     */
    public byte[] compile(Song song) {
        return compileDetailed(song).getSmpsDataUnsafe();
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
        return compileDetailed(song, mode).getSmpsDataUnsafe();
    }

    /**
     * Compile song bytes and expose per-channel row timelines.
     */
    public CompilationResult compileDetailed(Song song) {
        return compileDetailed(song, song.getSmpsMode());
    }

    /**
     * Compile song bytes and expose per-channel row timelines for a target mode.
     */
    public CompilationResult compileDetailed(Song song, SmpsMode mode) {
        int noteCompensation = switch (mode) {
            case S1, S3K -> 1;
            case S2 -> 0;
        };

        SongSnapshot snapshot = snapshotSong(song);

        List<Integer> activeFmChannels = new ArrayList<>();
        List<Integer> activePsgChannels = new ArrayList<>();
        List<Integer> activeChannels = new ArrayList<>();
        List<byte[]> compiledTracks = new ArrayList<>();
        List<ChannelTimelineBuilder> timelineBuilders = new ArrayList<>();

        for (int ch = 0; ch < TOTAL_CHANNELS; ch++) {
            if (!isChannelActive(snapshot.orderList, snapshot.patterns, ch)) {
                continue;
            }
            if (ch < FM_CHANNEL_COUNT) {
                activeFmChannels.add(ch);
            } else {
                activePsgChannels.add(ch);
            }

            int compensationForChannel = (ch == DAC_CHANNEL) ? 0 : noteCompensation;
            byte[] trackData = buildTrackData(snapshot.orderList, snapshot.patterns, ch, compensationForChannel);
            int loopTarget = calculateLoopTarget(snapshot.loopPoint, snapshot.orderList, snapshot.patterns, ch);

            byte[] withJump = new byte[trackData.length + 3];
            System.arraycopy(trackData, 0, withJump, 0, trackData.length);
            withJump[trackData.length] = (byte) CMD_JUMP;
            withJump[trackData.length + 1] = (byte) (loopTarget & 0xFF);
            withJump[trackData.length + 2] = (byte) ((loopTarget >> 8) & 0xFF);

            compiledTracks.add(withJump);
            activeChannels.add(ch);
            timelineBuilders.add(buildChannelTimeline(snapshot.orderList, snapshot.patterns, ch));
        }

        int fmCount = activeFmChannels.size();
        int psgCount = activePsgChannels.size();

        int headerSize = HEADER_BASE_SIZE
                + (fmCount * FM_TRACK_HEADER_SIZE)
                + (psgCount * PSG_TRACK_HEADER_SIZE);

        int[] trackOffsets = new int[compiledTracks.size()];
        int cursor = headerSize;
        for (int i = 0; i < compiledTracks.size(); i++) {
            trackOffsets[i] = cursor;
            cursor += compiledTracks.get(i).length;
        }

        int voiceTableOffset = cursor;
        int voiceDataLength = snapshot.voiceData.size() * FmVoice.VOICE_SIZE;

        for (int i = 0; i < compiledTracks.size(); i++) {
            relocateTrackPointersToFileOffsets(compiledTracks.get(i), trackOffsets[i]);
        }

        byte[] compiledBytes;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    headerSize + (voiceTableOffset - headerSize) + voiceDataLength);

            int voicePtr = snapshot.voiceData.isEmpty() ? 0 : voiceTableOffset;
            writeLE16(out, voicePtr);
            out.write(fmCount);
            out.write(psgCount);
            out.write(snapshot.dividingTiming & 0xFF);
            out.write(snapshot.tempo & 0xFF);

            int trackIndex = 0;
            for (int i = 0; i < fmCount; i++) {
                writeLE16(out, trackOffsets[trackIndex]);
                out.write(0);
                out.write(0);
                trackIndex++;
            }

            for (int i = 0; i < psgCount; i++) {
                writeLE16(out, trackOffsets[trackIndex]);
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(0);
                trackIndex++;
            }

            for (byte[] track : compiledTracks) {
                out.write(track);
            }

            for (byte[] voice : snapshot.voiceData) {
                out.write(voice);
            }

            compiledBytes = out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unexpected I/O error during compilation", e);
        }

        ChannelTimeline[] timelinesByChannel = new ChannelTimeline[TOTAL_CHANNELS];
        for (int i = 0; i < activeChannels.size(); i++) {
            int channel = activeChannels.get(i);
            timelinesByChannel[channel] = timelineBuilders.get(i).build(channel, trackOffsets[i]);
        }

        return new CompilationResult(compiledBytes, timelinesByChannel);
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
     */
    private byte[] buildTrackData(List<int[]> orderList,
                                  List<Pattern> patterns,
                                  int channel,
                                  int noteCompensation) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int cumulativeOffset = 0;

        for (int[] orderRow : orderList) {
            int patternIndex = orderRow[channel];
            byte[] data = (patternIndex >= 0 && patternIndex < patterns.size())
                    ? patterns.get(patternIndex).getTrackDataDirect(channel)
                    : new byte[0];
            if (data == null || data.length == 0) {
                continue;
            }

            int end = stripTrailingStop(data);
            if (end <= 0) {
                continue;
            }

            appendTrackSegment(buf, data, end, noteCompensation, cumulativeOffset);
            cumulativeOffset += end;
        }

        return buf.toByteArray();
    }

    /**
     * Walks SMPS bytecode, applies note compensation to note bytes (0x81-0xDF),
     * and writes the result to the output buffer.
     */
    private void appendTrackSegment(ByteArrayOutputStream buf,
                                    byte[] data,
                                    int end,
                                    int noteCompensation,
                                    int segmentStartOffset) {
        int pos = 0;
        while (pos < end) {
            int b = data[pos] & 0xFF;

            if (b >= 0xE0) {
                buf.write(b);
                int paramCount = SmpsCoordFlags.getParamCount(b);
                if ((b == SmpsCoordFlags.JUMP || b == SmpsCoordFlags.CALL) && pos + 2 < end) {
                    int rawTarget = (data[pos + 1] & 0xFF) | ((data[pos + 2] & 0xFF) << 8);
                    int adjusted = adjustTrackPointer(rawTarget, end, segmentStartOffset);
                    buf.write(adjusted & 0xFF);
                    buf.write((adjusted >> 8) & 0xFF);
                } else if (b == SmpsCoordFlags.LOOP && pos + 4 < end) {
                    buf.write(data[pos + 1] & 0xFF); // loop index
                    buf.write(data[pos + 2] & 0xFF); // loop count
                    int rawTarget = (data[pos + 3] & 0xFF) | ((data[pos + 4] & 0xFF) << 8);
                    int adjusted = adjustTrackPointer(rawTarget, end, segmentStartOffset);
                    buf.write(adjusted & 0xFF);
                    buf.write((adjusted >> 8) & 0xFF);
                } else {
                    for (int p = 0; p < paramCount && (pos + 1 + p) < end; p++) {
                        buf.write(data[pos + 1 + p] & 0xFF);
                    }
                }
                pos += 1 + paramCount;
            } else if (b >= 0x81 && b <= 0xDF) {
                int adjusted = b + noteCompensation;
                adjusted = Math.max(0x81, Math.min(0xDF, adjusted));
                buf.write(adjusted);
                pos++;
            } else {
                buf.write(b);
                pos++;
            }
        }
    }

    /**
     * Treat in-pattern pointers as local offsets and lift them into the
     * concatenated per-channel track space.
     */
    private int adjustTrackPointer(int rawTarget, int segmentLength, int segmentStartOffset) {
        if (rawTarget >= 0 && rawTarget < segmentLength) {
            return rawTarget + segmentStartOffset;
        }
        // Already global/absolute in track space (or invalid): preserve.
        return rawTarget;
    }

    /**
     * Patch all in-track pointer commands (F6/F7/F8) from track-relative
     * offsets to file-relative offsets by adding the track start.
     */
    private void relocateTrackPointersToFileOffsets(byte[] track, int trackOffset) {
        int pos = 0;
        while (pos < track.length) {
            int cmd = track[pos] & 0xFF;
            if (cmd >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(cmd);
                if ((cmd == SmpsCoordFlags.JUMP || cmd == SmpsCoordFlags.CALL) && pos + 2 < track.length) {
                    int target = (track[pos + 1] & 0xFF) | ((track[pos + 2] & 0xFF) << 8);
                    int relocated = target + trackOffset;
                    track[pos + 1] = (byte) (relocated & 0xFF);
                    track[pos + 2] = (byte) ((relocated >> 8) & 0xFF);
                } else if (cmd == SmpsCoordFlags.LOOP && pos + 4 < track.length) {
                    int target = (track[pos + 3] & 0xFF) | ((track[pos + 4] & 0xFF) << 8);
                    int relocated = target + trackOffset;
                    track[pos + 3] = (byte) (relocated & 0xFF);
                    track[pos + 4] = (byte) ((relocated >> 8) & 0xFF);
                }
                pos += 1 + paramCount;
            } else {
                pos++;
            }
        }
    }

    /**
     * Calculates the track-relative byte offset of the loop point for a channel.
     */
    private int calculateLoopTarget(int loopRow,
                                    List<int[]> orderList,
                                    List<Pattern> patterns,
                                    int channel) {
        int offset = 0;
        for (int row = 0; row < orderList.size(); row++) {
            if (row == loopRow) {
                return offset;
            }
            int patternIndex = orderList.get(row)[channel];
            byte[] data = (patternIndex >= 0 && patternIndex < patterns.size())
                    ? patterns.get(patternIndex).getTrackDataDirect(channel)
                    : new byte[0];
            if (data != null && data.length > 0) {
                offset += stripTrailingStop(data);
            }
        }

        return 0;
    }

    private int stripTrailingStop(byte[] data) {
        int end = data.length;
        while (end > 0 && (data[end - 1] & 0xFF) == CMD_TRACK_END) {
            end--;
        }
        return end;
    }

    private ChannelTimelineBuilder buildChannelTimeline(List<int[]> orderList,
                                                        List<Pattern> patterns,
                                                        int channel) {
        ChannelTimelineBuilder builder = new ChannelTimelineBuilder();
        int cumulativeOffset = 0;

        for (int orderIndex = 0; orderIndex < orderList.size(); orderIndex++) {
            int patternIndex = orderList.get(orderIndex)[channel];
            byte[] data = (patternIndex >= 0 && patternIndex < patterns.size())
                    ? patterns.get(patternIndex).getTrackDataDirect(channel)
                    : new byte[0];
            if (data == null || data.length == 0) {
                continue;
            }

            int end = stripTrailingStop(data);
            if (end <= 0) {
                continue;
            }

            byte[] trimmed = Arrays.copyOf(data, end);
            List<SmpsDecoder.DecodedRow> decodedRows = SmpsDecoder.decodeWithOffsets(trimmed);
            for (int rowIndex = 0; rowIndex < decodedRows.size(); rowIndex++) {
                SmpsDecoder.DecodedRow row = decodedRows.get(rowIndex);
                builder.add(cumulativeOffset + row.byteOffset(), orderIndex, rowIndex);
            }

            cumulativeOffset += end;
        }

        return builder;
    }

    private SongSnapshot snapshotSong(Song song) {
        List<int[]> orderList = new ArrayList<>();
        for (int[] row : song.getOrderList()) {
            orderList.add(row.clone());
        }

        List<Pattern> patterns = new ArrayList<>();
        for (Pattern source : song.getPatterns()) {
            Pattern copy = new Pattern(source.getId(), source.getRows());
            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                byte[] track = source.getTrackDataDirect(ch);
                if (track != null && track.length > 0) {
                    copy.setTrackData(ch, track.clone());
                }
            }
            patterns.add(copy);
        }

        List<byte[]> voiceData = new ArrayList<>();
        for (FmVoice voice : song.getVoiceBank()) {
            voiceData.add(voice.getDataUnsafe().clone());
        }

        return new SongSnapshot(orderList, patterns, voiceData,
                song.getDividingTiming(), song.getTempo(), song.getLoopPoint());
    }

    private static void writeLE16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static final class SongSnapshot {
        private final List<int[]> orderList;
        private final List<Pattern> patterns;
        private final List<byte[]> voiceData;
        private final int dividingTiming;
        private final int tempo;
        private final int loopPoint;

        private SongSnapshot(List<int[]> orderList,
                             List<Pattern> patterns,
                             List<byte[]> voiceData,
                             int dividingTiming,
                             int tempo,
                             int loopPoint) {
            this.orderList = orderList;
            this.patterns = patterns;
            this.voiceData = voiceData;
            this.dividingTiming = dividingTiming;
            this.tempo = tempo;
            this.loopPoint = loopPoint;
        }
    }

    private static final class ChannelTimelineBuilder {
        private final List<Integer> rowOffsets = new ArrayList<>();
        private final List<Integer> orderIndices = new ArrayList<>();
        private final List<Integer> rowIndices = new ArrayList<>();

        void add(int rowOffset, int orderIndex, int rowIndex) {
            rowOffsets.add(rowOffset);
            orderIndices.add(orderIndex);
            rowIndices.add(rowIndex);
        }

        ChannelTimeline build(int channel, int trackOffset) {
            int count = rowOffsets.size();
            int[] rowOffsetArray = new int[count];
            int[] orderArray = new int[count];
            int[] rowArray = new int[count];
            for (int i = 0; i < count; i++) {
                rowOffsetArray[i] = rowOffsets.get(i);
                orderArray[i] = orderIndices.get(i);
                rowArray[i] = rowIndices.get(i);
            }
            return new ChannelTimeline(channel, trackOffset, rowOffsetArray, orderArray, rowArray);
        }
    }
}
