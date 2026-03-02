package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.ArrangementMode;
import com.opensmpsdeck.model.BlockDefinition;
import com.opensmpsdeck.model.BlockRef;
import com.opensmpsdeck.model.Chain;
import com.opensmpsdeck.model.ChannelArrangement;
import com.opensmpsdeck.model.HierarchicalArrangement;
import com.opensmpsdeck.model.Pattern;
import com.opensmpsdeck.model.PhraseLibrary;
import com.opensmpsdeck.model.SmpsMode;
import com.opensmpsdeck.model.Song;
import com.opensmpsdeck.model.StructuredArrangement;
import com.opensmps.smps.SmpsCoordFlags;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** FM model channel order matching the sequencer's fmChannelOrder:
     *  entry 0 = DAC (model ch 5), then FM1-FM5 (model ch 0-4). */
    private static final int[] FM_COMPILE_ORDER = {DAC_CHANNEL, 0, 1, 2, 3, 4};

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
        if (song.getArrangementMode() == ArrangementMode.STRUCTURED_BLOCKS
                && song.getStructuredArrangement() != null) {
            return compileStructuredDetailed(song, mode, song.getStructuredArrangement());
        }
        // Default: hierarchical arrangement
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        if (arr == null) {
            arr = new HierarchicalArrangement();
        }
        return compileHierarchicalDetailed(song, mode, arr);
    }

    private CompilationResult compileHierarchicalDetailed(Song song, SmpsMode mode, HierarchicalArrangement arrangement) {
        int noteCompensation = switch (mode) {
            case S1, S3K -> 1;
            case S2 -> 0;
        };

        List<byte[]> voiceData = new ArrayList<>();
        for (FmVoice voice : song.getVoiceBank()) {
            voiceData.add(voice.getDataUnsafe().clone());
        }

        PhraseLibrary library = arrangement.getPhraseLibrary();

        List<Integer> activeFmChannels = new ArrayList<>();
        List<Integer> activePsgChannels = new ArrayList<>();
        List<Integer> activeChannels = new ArrayList<>();
        List<byte[]> compiledTracks = new ArrayList<>();
        List<HierarchyCompiler.ChainCompilationResult> chainResults = new ArrayList<>();

        // Process FM channels in sequencer-expected order: DAC first, then FM1-FM5.
        // The sequencer's fmChannelOrder maps entry 0→DAC, 1→FM1, 2→FM2, etc.
        boolean anyNonDacFm = false;
        boolean hasDacTrack = false;

        for (int ch : FM_COMPILE_ORDER) {
            Chain chain = arrangement.getChain(ch);
            if (chain.getEntries().isEmpty()) {
                continue;
            }

            var chainResult = HierarchyCompiler.compileChainDetailed(chain, library);
            byte[] trackData = chainResult.trackData();
            if (trackData.length == 0 || (trackData.length == 1 && (trackData[0] & 0xFF) == CMD_TRACK_END)) {
                continue;
            }

            // Apply note compensation to FM channels only (0-4).
            // DAC (5) and PSG (6-9) don't need compensation: PSG uses
            // baseNoteOffset=0 in all modes, DAC uses sample IDs not notes.
            if (noteCompensation != 0 && ch < DAC_CHANNEL) {
                trackData = applyNoteCompensation(trackData, noteCompensation);
            }

            activeFmChannels.add(ch);
            compiledTracks.add(trackData);
            activeChannels.add(ch);
            chainResults.add(chainResult);

            if (ch == DAC_CHANNEL) hasDacTrack = true;
            else anyNonDacFm = true;
        }

        // Insert dummy DAC entry if FM tracks exist without DAC, so the
        // sequencer's positional fmChannelOrder mapping stays correct.
        if (anyNonDacFm && !hasDacTrack) {
            byte[] dummyDac = new byte[]{(byte) CMD_TRACK_END};
            compiledTracks.add(0, dummyDac);
            activeFmChannels.add(0, DAC_CHANNEL);
            activeChannels.add(0, DAC_CHANNEL);
            chainResults.add(0, new HierarchyCompiler.ChainCompilationResult(dummyDac, new int[0], 0));
        }

        // Process PSG channels (6-9)
        for (int ch = FM_CHANNEL_COUNT; ch < TOTAL_CHANNELS; ch++) {
            Chain chain = arrangement.getChain(ch);
            if (chain.getEntries().isEmpty()) {
                continue;
            }

            var chainResult = HierarchyCompiler.compileChainDetailed(chain, library);
            byte[] trackData = chainResult.trackData();
            if (trackData.length == 0 || (trackData.length == 1 && (trackData[0] & 0xFF) == CMD_TRACK_END)) {
                continue;
            }

            activePsgChannels.add(ch);
            compiledTracks.add(trackData);
            activeChannels.add(ch);
            chainResults.add(chainResult);
        }

        int fmCount = activeFmChannels.size();
        int psgCount = activePsgChannels.size();
        int headerSize = HEADER_BASE_SIZE + (fmCount * FM_TRACK_HEADER_SIZE) + (psgCount * PSG_TRACK_HEADER_SIZE);

        int[] trackOffsets = new int[compiledTracks.size()];
        int cursor = headerSize;
        for (int i = 0; i < compiledTracks.size(); i++) {
            trackOffsets[i] = cursor;
            cursor += compiledTracks.get(i).length;
        }

        int voiceTableOffset = cursor;
        int voiceDataLength = voiceData.size() * FmVoice.VOICE_SIZE;

        for (int i = 0; i < compiledTracks.size(); i++) {
            relocateTrackPointersToFileOffsets(compiledTracks.get(i), trackOffsets[i]);
        }

        byte[] compiledBytes;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    headerSize + (voiceTableOffset - headerSize) + voiceDataLength);

            int voicePtr = voiceData.isEmpty() ? 0 : voiceTableOffset;
            writeLE16(out, voicePtr);
            out.write(fmCount);
            out.write(psgCount);
            out.write(song.getDividingTiming() & 0xFF);
            out.write(song.getTempo() & 0xFF);

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
            for (byte[] voice : voiceData) {
                out.write(voice);
            }
            compiledBytes = out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unexpected I/O error during hierarchical compilation", e);
        }

        ChannelTimeline[] timelinesByChannel = new ChannelTimeline[TOTAL_CHANNELS];
        for (int i = 0; i < activeChannels.size(); i++) {
            int channel = activeChannels.get(i);
            var chainResult = chainResults.get(i);
            ChannelTimelineBuilder builder = buildHierarchicalTimeline(
                    chainResult.trackData(), chainResult.entryOffsets(), chainResult.contentEndOffset());
            timelinesByChannel[channel] = builder.build(channel, trackOffsets[i]);
        }
        return new CompilationResult(compiledBytes, timelinesByChannel);
    }

    private byte[] applyNoteCompensation(byte[] data, int compensation) {
        byte[] result = data.clone();
        int pos = 0;
        while (pos < result.length) {
            int b = result[pos] & 0xFF;
            if (b >= 0xE0) {
                pos += 1 + SmpsCoordFlags.getParamCount(b);
            } else if (b >= 0x81 && b <= 0xDF) {
                int adjusted = Math.max(0x81, Math.min(0xDF, b + compensation));
                result[pos] = (byte) adjusted;
                pos++;
            } else {
                pos++;
            }
        }
        return result;
    }

    private ChannelTimelineBuilder buildHierarchicalTimeline(byte[] trackData,
                                                               int[] entryOffsets,
                                                               int contentEndOffset) {
        ChannelTimelineBuilder builder = new ChannelTimelineBuilder();
        if (entryOffsets.length == 0 || contentEndOffset <= 0) {
            return builder;
        }

        byte[] content = Arrays.copyOf(trackData, contentEndOffset);
        List<SmpsDecoder.DecodedRow> decodedRows = SmpsDecoder.decodeWithOffsets(content);

        int currentEntry = 0;
        int rowInEntry = 0;

        for (SmpsDecoder.DecodedRow row : decodedRows) {
            while (currentEntry + 1 < entryOffsets.length
                    && row.byteOffset() >= entryOffsets[currentEntry + 1]) {
                currentEntry++;
                rowInEntry = 0;
            }

            builder.add(row.byteOffset(), currentEntry, rowInEntry);
            rowInEntry++;
        }

        return builder;
    }

    private CompilationResult compileStructuredDetailed(Song song, SmpsMode mode, StructuredArrangement arrangement) {
        int noteCompensation = switch (mode) {
            case S1, S3K -> 1;
            case S2 -> 0;
        };

        List<byte[]> voiceData = new ArrayList<>();
        for (FmVoice voice : song.getVoiceBank()) {
            voiceData.add(voice.getDataUnsafe().clone());
        }

        Map<Integer, BlockDefinition> blocksById = new HashMap<>();
        for (BlockDefinition block : arrangement.getBlocks()) {
            blocksById.put(block.getId(), block);
        }

        List<Integer> activeFmChannels = new ArrayList<>();
        List<Integer> activePsgChannels = new ArrayList<>();
        List<Integer> activeChannels = new ArrayList<>();
        List<byte[]> compiledTracks = new ArrayList<>();
        List<ChannelTimelineBuilder> timelineBuilders = new ArrayList<>();

        int ticksPerRow = Math.max(1, arrangement.getTicksPerRow());

        // Process FM channels in sequencer-expected order: DAC first, then FM1-FM5.
        boolean anyNonDacFm = false;
        boolean hasDacTrack = false;

        for (int ch : FM_COMPILE_ORDER) {
            int chNoteComp = (ch < DAC_CHANNEL) ? noteCompensation : 0;
            StructuredTrack track = buildStructuredTrackData(arrangement, blocksById, ch, chNoteComp);
            if (track.trackData.length == 0) {
                continue;
            }

            activeFmChannels.add(ch);

            byte[] withJump = new byte[track.trackData.length + 3];
            System.arraycopy(track.trackData, 0, withJump, 0, track.trackData.length);
            withJump[track.trackData.length] = (byte) CMD_JUMP;
            withJump[track.trackData.length + 1] = 0;
            withJump[track.trackData.length + 2] = 0;

            compiledTracks.add(withJump);
            activeChannels.add(ch);
            timelineBuilders.add(buildStructuredTimeline(track.trackData, ticksPerRow));

            if (ch == DAC_CHANNEL) hasDacTrack = true;
            else anyNonDacFm = true;
        }

        // Insert dummy DAC entry if FM tracks exist without DAC
        if (anyNonDacFm && !hasDacTrack) {
            byte[] dummyDac = new byte[]{(byte) CMD_TRACK_END};
            compiledTracks.add(0, dummyDac);
            activeFmChannels.add(0, DAC_CHANNEL);
            activeChannels.add(0, DAC_CHANNEL);
            timelineBuilders.add(0, new ChannelTimelineBuilder());
        }

        // Process PSG channels (6-9)
        for (int ch = FM_CHANNEL_COUNT; ch < TOTAL_CHANNELS; ch++) {
            int chNoteComp = 0; // PSG uses offset 0 in all modes
            StructuredTrack track = buildStructuredTrackData(arrangement, blocksById, ch, chNoteComp);
            if (track.trackData.length == 0) {
                continue;
            }

            activePsgChannels.add(ch);

            byte[] withJump = new byte[track.trackData.length + 3];
            System.arraycopy(track.trackData, 0, withJump, 0, track.trackData.length);
            withJump[track.trackData.length] = (byte) CMD_JUMP;
            withJump[track.trackData.length + 1] = 0;
            withJump[track.trackData.length + 2] = 0;

            compiledTracks.add(withJump);
            activeChannels.add(ch);
            timelineBuilders.add(buildStructuredTimeline(track.trackData, ticksPerRow));
        }

        int fmCount = activeFmChannels.size();
        int psgCount = activePsgChannels.size();
        int headerSize = HEADER_BASE_SIZE + (fmCount * FM_TRACK_HEADER_SIZE) + (psgCount * PSG_TRACK_HEADER_SIZE);

        int[] trackOffsets = new int[compiledTracks.size()];
        int cursor = headerSize;
        for (int i = 0; i < compiledTracks.size(); i++) {
            trackOffsets[i] = cursor;
            cursor += compiledTracks.get(i).length;
        }

        int voiceTableOffset = cursor;
        int voiceDataLength = voiceData.size() * FmVoice.VOICE_SIZE;

        for (int i = 0; i < compiledTracks.size(); i++) {
            relocateTrackPointersToFileOffsets(compiledTracks.get(i), trackOffsets[i]);
        }

        byte[] compiledBytes;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    headerSize + (voiceTableOffset - headerSize) + voiceDataLength);

            int voicePtr = voiceData.isEmpty() ? 0 : voiceTableOffset;
            writeLE16(out, voicePtr);
            out.write(fmCount);
            out.write(psgCount);
            out.write(song.getDividingTiming() & 0xFF);
            out.write(song.getTempo() & 0xFF);

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
            for (byte[] voice : voiceData) {
                out.write(voice);
            }
            compiledBytes = out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unexpected I/O error during structured compilation", e);
        }

        ChannelTimeline[] timelinesByChannel = new ChannelTimeline[TOTAL_CHANNELS];
        for (int i = 0; i < activeChannels.size(); i++) {
            int channel = activeChannels.get(i);
            timelinesByChannel[channel] = timelineBuilders.get(i).build(channel, trackOffsets[i]);
        }

        return new CompilationResult(compiledBytes, timelinesByChannel);
    }

    private StructuredTrack buildStructuredTrackData(StructuredArrangement arrangement,
                                                     Map<Integer, BlockDefinition> blocksById,
                                                     int channel,
                                                     int noteCompensation) {
        if (channel < 0 || channel >= arrangement.getChannels().size()) {
            return StructuredTrack.EMPTY;
        }
        ChannelArrangement lane = arrangement.getChannels().get(channel);
        if (lane == null || lane.getBlockRefs().isEmpty()) {
            return StructuredTrack.EMPTY;
        }

        List<BlockRef> refs = new ArrayList<>(lane.getBlockRefs());
        refs.sort(Comparator.comparingInt(BlockRef::getStartTick));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int cursorTick = 0;
        for (BlockRef ref : refs) {
            BlockDefinition block = blocksById.get(ref.getBlockId());
            if (block == null) {
                continue;
            }
            int startTick = Math.max(0, ref.getStartTick());
            if (startTick > cursorTick) {
                appendRestGap(out, startTick - cursorTick);
                cursorTick = startTick;
            }

            int repeat = Math.max(1, ref.getRepeatCount());
            int blockTicks = Math.max(0, block.getLengthTicks());
            byte[] segment = block.getTrackDataDirect(channel);
            int end = (segment != null) ? stripTrailingStop(segment) : 0;
            for (int r = 0; r < repeat; r++) {
                if (end > 0) {
                    int segmentStartOffset = out.size();
                    appendTrackSegment(out, segment, end, noteCompensation, segmentStartOffset,
                            ref.getTransposeSemitones(), channel);
                }
                cursorTick += blockTicks;
            }
        }

        return out.size() > 0 ? new StructuredTrack(out.toByteArray()) : StructuredTrack.EMPTY;
    }

    private void appendRestGap(ByteArrayOutputStream out, int ticks) {
        int remaining = Math.max(0, ticks);
        while (remaining > 0) {
            int chunk = Math.min(0x7F, remaining);
            out.write(0x80);
            out.write(chunk);
            remaining -= chunk;
        }
    }

    private ChannelTimelineBuilder buildStructuredTimeline(byte[] trackData, int ticksPerRow) {
        ChannelTimelineBuilder builder = new ChannelTimelineBuilder();
        List<SmpsDecoder.DecodedRow> decodedRows = SmpsDecoder.decodeWithOffsets(trackData);
        int tick = 0;
        for (SmpsDecoder.DecodedRow decodedRow : decodedRows) {
            int row = tick / ticksPerRow;
            builder.add(decodedRow.byteOffset(), 0, row);

            SmpsDecoder.TrackerRow tr = decodedRow.row();
            if (tr.note() != null && !tr.note().isEmpty()) {
                int dur = tr.duration();
                tick += Math.max(1, dur);
            }
        }
        return builder;
    }

    private static final class StructuredTrack {
        private static final StructuredTrack EMPTY = new StructuredTrack(new byte[0]);
        private final byte[] trackData;

        private StructuredTrack(byte[] trackData) {
            this.trackData = trackData;
        }
    }

    /**
     * Walks SMPS bytecode, applies note compensation to note bytes (0x81-0xDF),
     * and writes the result to the output buffer.
     */
    private void appendTrackSegment(ByteArrayOutputStream buf,
                                    byte[] data,
                                    int end,
                                    int noteCompensation,
                                    int segmentStartOffset,
                                    int transposeSemitones,
                                    int channel) {
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
                int adjusted = b;
                if (transposeSemitones != 0 && channel != DAC_CHANNEL && channel != 9) {
                    adjusted += transposeSemitones;
                }
                adjusted += noteCompensation;
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

    private int stripTrailingStop(byte[] data) {
        int end = data.length;
        while (end > 0 && (data[end - 1] & 0xFF) == CMD_TRACK_END) {
            end--;
        }
        return end;
    }

    private static void writeLE16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
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
