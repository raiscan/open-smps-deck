package com.opensmps.deck.audio;

import com.opensmps.deck.codec.SmpsEncoder;
import com.opensmps.deck.model.DacSample;
import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.PsgEnvelope;
import com.opensmps.deck.model.Song;
import com.opensmps.smps.SmpsCoordFlags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds playback-only song slices for "play from cursor" scenarios.
 *
 * <p>The returned slice is safe for compilation/playback and never mutates the
 * original source song.
 */
final class PlaybackSliceBuilder {

    /**
     * Creates a playback-ready slice that starts at the requested order row.
     *
     * <p>For order index 0 (and row index 0), or out-of-range indices, returns
     * the original song reference.
     *
     * <p>For other valid positions, returns a deep copy with:
     * <ul>
     *   <li>Order list trimmed from {@code orderIndex} onward.</li>
     *   <li>Loop point rebased into the sliced order list.</li>
     *   <li>Optional first-row pattern rewrite when {@code rowIndex > 0}.</li>
     * </ul>
     */
    Song createPlaybackSlice(Song song, int orderIndex, int rowIndex) {
        int normalizedRow = Math.max(0, rowIndex);
        if (orderIndex < 0 || orderIndex >= song.getOrderList().size()) {
            return song;
        }
        if (orderIndex == 0 && normalizedRow == 0) {
            return song;
        }

        Song copy = deepCopySong(song);
        if (orderIndex > 0) {
            List<int[]> slicedOrder = new ArrayList<>();
            for (int i = orderIndex; i < song.getOrderList().size(); i++) {
                slicedOrder.add(song.getOrderList().get(i).clone());
            }
            copy.getOrderList().clear();
            copy.getOrderList().addAll(slicedOrder);

            int adjustedLoop = song.getLoopPoint() - orderIndex;
            if (adjustedLoop < 0 || adjustedLoop >= copy.getOrderList().size()) {
                adjustedLoop = 0;
            }
            copy.setLoopPoint(adjustedLoop);
        }
        if (normalizedRow > 0 && !copy.getOrderList().isEmpty()) {
            rewriteFirstOrderRowForRowOffset(copy, normalizedRow);
        }
        return copy;
    }

    private void rewriteFirstOrderRowForRowOffset(Song song, int rowIndex) {
        int[] firstOrder = song.getOrderList().get(0);
        int entryPatternIndex = song.getPatterns().size();

        int entryRows = 64;
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            int patternIndex = firstOrder[ch];
            if (patternIndex >= 0 && patternIndex < song.getPatterns().size()) {
                Pattern source = song.getPatterns().get(patternIndex);
                entryRows = Math.max(1, source.getRows() - rowIndex);
                break;
            }
        }

        Pattern entry = new Pattern(entryPatternIndex, entryRows);
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            int patternIndex = firstOrder[ch];
            byte[] sourceTrack = new byte[0];
            if (patternIndex >= 0 && patternIndex < song.getPatterns().size()) {
                sourceTrack = song.getPatterns().get(patternIndex).getTrackData(ch);
            }
            byte[] trimmed = extractRowRangeForPlayback(sourceTrack, rowIndex, ch);
            entry.setTrackData(ch, trimmed);
            firstOrder[ch] = entryPatternIndex;
        }

        song.getPatterns().add(entry);
    }

    /**
     * Extracts decoded rows from {@code startRow} onward while preserving enough
     * context so the first extracted row plays correctly.
     */
    private byte[] extractRowRangeForPlayback(byte[] trackData, int startRow, int channel) {
        if (trackData == null || trackData.length == 0) {
            return new byte[0];
        }

        List<RowSliceContext> rows = scanRowSliceContexts(trackData);
        if (startRow < 0 || startRow >= rows.size()) {
            return new byte[0];
        }

        RowSliceContext start = rows.get(startRow);
        byte[] prefix = Arrays.copyOfRange(trackData, start.prefixStartOffset, start.rowStartOffset);
        byte[] body = SmpsEncoder.extractRowRange(trackData, startRow, 1_000_000);

        List<byte[]> parts = new ArrayList<>(4);
        boolean fmLikeChannel = channel <= 5;
        int bootstrapInstrument = fmLikeChannel ? start.lastFmInstrumentBeforeRow : start.lastPsgInstrumentBeforeRow;
        boolean prefixSetsInstrument = fmLikeChannel
                ? start.prefixSetsFmInstrument
                : start.prefixSetsPsgInstrument;
        if (!prefixSetsInstrument && bootstrapInstrument >= 0) {
            int instrumentFlag = fmLikeChannel
                    ? SmpsCoordFlags.SET_VOICE
                    : SmpsCoordFlags.PSG_INSTRUMENT;
            parts.add(new byte[] { (byte) instrumentFlag, (byte) bootstrapInstrument });
        }

        if (!start.rowHasInlineDuration && !start.prefixSetsDuration && start.lastDurationBeforeRow > 0) {
            parts.add(new byte[] { (byte) start.lastDurationBeforeRow });
        }

        if (prefix.length > 0) {
            parts.add(prefix);
        }
        if (body.length > 0) {
            parts.add(body);
        }
        return concat(parts);
    }

    private byte[] concat(List<byte[]> chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }

    private List<RowSliceContext> scanRowSliceContexts(byte[] trackData) {
        List<RowSliceContext> rows = new ArrayList<>();
        int pos = 0;
        int prevRowEnd = 0;
        int currentDuration = 0;
        int lastFmInstrument = -1;
        int lastPsgInstrument = -1;
        boolean durationSincePreviousRow = false;
        boolean fmInstrumentSincePreviousRow = false;
        boolean psgInstrumentSincePreviousRow = false;

        while (pos < trackData.length) {
            int b = trackData[pos] & 0xFF;
            if (b == 0x00 || b == SmpsCoordFlags.STOP) {
                break;
            }

            if ((b >= 0x80 && b <= 0xDF) || b == SmpsCoordFlags.TIE) {
                RowSliceContext row = new RowSliceContext();
                row.prefixStartOffset = prevRowEnd;
                row.rowStartOffset = pos;
                row.lastDurationBeforeRow = currentDuration;
                row.lastFmInstrumentBeforeRow = lastFmInstrument;
                row.lastPsgInstrumentBeforeRow = lastPsgInstrument;
                row.prefixSetsDuration = durationSincePreviousRow;
                row.prefixSetsFmInstrument = fmInstrumentSincePreviousRow;
                row.prefixSetsPsgInstrument = psgInstrumentSincePreviousRow;

                pos++;
                if (b != SmpsCoordFlags.TIE && pos < trackData.length) {
                    int next = trackData[pos] & 0xFF;
                    if (next >= 0x01 && next <= 0x7F) {
                        row.rowHasInlineDuration = true;
                        currentDuration = next;
                        pos++;
                    }
                }

                rows.add(row);
                prevRowEnd = pos;
                durationSincePreviousRow = false;
                fmInstrumentSincePreviousRow = false;
                psgInstrumentSincePreviousRow = false;
                continue;
            }

            if (b >= 0x01 && b <= 0x7F) {
                currentDuration = b;
                durationSincePreviousRow = true;
                pos++;
                continue;
            }

            if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b);
                if (b == SmpsCoordFlags.SET_VOICE && pos + 1 < trackData.length) {
                    lastFmInstrument = trackData[pos + 1] & 0xFF;
                    fmInstrumentSincePreviousRow = true;
                } else if (b == SmpsCoordFlags.PSG_INSTRUMENT && pos + 1 < trackData.length) {
                    lastPsgInstrument = trackData[pos + 1] & 0xFF;
                    psgInstrumentSincePreviousRow = true;
                }
                pos += 1 + paramCount;
                continue;
            }

            pos++;
        }

        return rows;
    }

    private Song deepCopySong(Song source) {
        Song copy = new Song();
        copy.setName(source.getName());
        copy.setSmpsMode(source.getSmpsMode());
        copy.setTempo(source.getTempo());
        copy.setDividingTiming(source.getDividingTiming());
        copy.setLoopPoint(source.getLoopPoint());

        copy.getVoiceBank().clear();
        for (FmVoice voice : source.getVoiceBank()) {
            copy.getVoiceBank().add(new FmVoice(voice.getName(), voice.getData()));
        }

        copy.getPsgEnvelopes().clear();
        for (PsgEnvelope env : source.getPsgEnvelopes()) {
            copy.getPsgEnvelopes().add(new PsgEnvelope(env.getName(), env.getData()));
        }

        copy.getDacSamples().clear();
        for (DacSample sample : source.getDacSamples()) {
            copy.getDacSamples().add(new DacSample(sample.getName(), sample.getData(), sample.getRate()));
        }

        copy.getPatterns().clear();
        for (Pattern pattern : source.getPatterns()) {
            Pattern cloned = new Pattern(pattern.getId(), pattern.getRows());
            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                cloned.setTrackData(ch, pattern.getTrackData(ch));
            }
            copy.getPatterns().add(cloned);
        }

        copy.getOrderList().clear();
        for (int[] row : source.getOrderList()) {
            copy.getOrderList().add(row.clone());
        }
        return copy;
    }

    private static final class RowSliceContext {
        private int prefixStartOffset;
        private int rowStartOffset;
        private int lastDurationBeforeRow;
        private int lastFmInstrumentBeforeRow;
        private int lastPsgInstrumentBeforeRow;
        private boolean rowHasInlineDuration;
        private boolean prefixSetsDuration;
        private boolean prefixSetsFmInstrument;
        private boolean prefixSetsPsgInstrument;
    }
}
