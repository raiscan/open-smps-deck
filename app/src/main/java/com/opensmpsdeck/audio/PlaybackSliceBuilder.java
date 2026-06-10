package com.opensmpsdeck.audio;

import com.opensmpsdeck.codec.SmpsEncoder;
import com.opensmpsdeck.model.Chain;
import com.opensmpsdeck.model.ChainEntry;
import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.HierarchicalArrangement;
import com.opensmpsdeck.model.Pattern;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
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

            // Trim hierarchical chains to match sliced order. The removed
            // entries carry state the remaining music depends on (voice and
            // envelope selections, ADDITIVE volume/key offsets, noise mode,
            // last duration) — accumulate it into a one-shot init phrase.
            HierarchicalArrangement arr = copy.getHierarchicalArrangement();
            if (arr != null) {
                var dialect = copy.getSmpsMode().dialect();
                for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                    Chain chain = arr.getChain(ch);
                    int toRemove = Math.min(orderIndex, chain.getEntries().size());
                    if (toRemove == 0) continue;

                    ChainState state = accumulateChainState(
                            chain.getEntries().subList(0, toRemove),
                            arr.getPhraseLibrary(), ch, dialect);
                    byte[] statePrefix = state.prefix();

                    for (int i = 0; i < toRemove; i++) {
                        chain.getEntries().remove(0);
                    }
                    int oldLoop = chain.getLoopEntryIndex();
                    if (oldLoop >= 0) {
                        chain.setLoopEntryIndex(Math.max(0, oldLoop - toRemove));
                    }

                    if (statePrefix.length > 0 && !chain.getEntries().isEmpty()) {
                        ChainEntry firstEntry = chain.getEntries().get(0);
                        Phrase firstPhrase = arr.getPhraseLibrary().getPhrase(firstEntry.getPhraseId());
                        boolean loopHitsFirst = chain.getLoopEntryIndex() == 0;
                        // Idempotent state (voice/instrument/noise/duration) may
                        // safely re-run on loop or repeat; only ADDITIVE state
                        // (volume/key deltas) must execute exactly once
                        boolean reentrySafe = !state.additive()
                                || (firstEntry.getRepeatCount() <= 1 && !loopHitsFirst);
                        if (firstPhrase != null && reentrySafe) {
                            // Bake into a clone of the first phrase so entry
                            // indices (and therefore reported playback order
                            // rows) are unchanged by the slice
                            Phrase baked = arr.getPhraseLibrary().createPhrase(
                                    firstPhrase.getName(), firstPhrase.getChannelType());
                            byte[] firstData = firstPhrase.getDataDirect();
                            byte[] merged = new byte[statePrefix.length + firstData.length];
                            System.arraycopy(statePrefix, 0, merged, 0, statePrefix.length);
                            System.arraycopy(firstData, 0, merged, statePrefix.length, firstData.length);
                            baked.setData(merged);
                            ChainEntry replacement = new ChainEntry(baked.getId());
                            replacement.setTransposeSemitones(firstEntry.getTransposeSemitones());
                            chain.getEntries().set(0, replacement);
                        } else {
                            // First entry repeats or is the loop target: the
                            // additive prefix must run once, in its own entry
                            Phrase init = arr.getPhraseLibrary().createPhrase(
                                    "SliceInit", com.opensmpsdeck.model.ChannelType.fromChannelIndex(ch));
                            init.setData(statePrefix);
                            chain.getEntries().add(0, new ChainEntry(init.getId()));
                            if (chain.getLoopEntryIndex() >= 0) {
                                chain.setLoopEntryIndex(chain.getLoopEntryIndex() + 1);
                            }
                        }
                    }
                }
            }
        }
        if (normalizedRow > 0 && !copy.getOrderList().isEmpty()) {
            rewriteFirstOrderRowForRowOffset(copy, normalizedRow);
        }
        return copy;
    }

    private void rewriteFirstOrderRowForRowOffset(Song song, int rowIndex) {
        var dialect = song.getSmpsMode().dialect();
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
            byte[] trimmed = extractRowRangeForPlayback(sourceTrack, rowIndex, ch, dialect);
            entry.setTrackData(ch, trimmed);
            firstOrder[ch] = entryPatternIndex;
        }

        song.getPatterns().add(entry);
    }

    /**
     * Extracts decoded rows from {@code startRow} onward while preserving enough
     * context so the first extracted row plays correctly.
     */
    private byte[] extractRowRangeForPlayback(byte[] trackData, int startRow, int channel,
            SmpsCoordFlags.Dialect dialect) {
        if (trackData == null || trackData.length == 0) {
            return new byte[0];
        }

        List<RowSliceContext> rows = scanRowSliceContexts(trackData, dialect);
        if (startRow < 0 || startRow >= rows.size()) {
            return new byte[0];
        }

        RowSliceContext start = rows.get(startRow);
        byte[] prefix = Arrays.copyOfRange(trackData, start.prefixStartOffset, start.rowStartOffset);
        byte[] body = SmpsEncoder.extractRowRange(trackData, startRow, 1_000_000, dialect);

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

    private List<RowSliceContext> scanRowSliceContexts(byte[] trackData,
            SmpsCoordFlags.Dialect dialect) {
        List<RowSliceContext> rows = new ArrayList<>();
        int pos = 0;
        int prevRowEnd = 0;
        int currentDuration = 0;
        int lastFmInstrument = -1;
        int lastPsgInstrument = -1;
        boolean durationSincePreviousRow = false;
        boolean seenNote = false;
        boolean fmInstrumentSincePreviousRow = false;
        boolean psgInstrumentSincePreviousRow = false;

        while (pos < trackData.length) {
            int b = trackData[pos] & 0xFF;
            if (b == 0x00 || b == SmpsCoordFlags.STOP) {
                break;
            }

            if (b >= 0x80 && b <= 0xDF) {
                seenNote = true;
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
                // Bare duration after a note: a re-trigger row of its own
                // (mirrors SmpsDecoder/SmpsEncoder row semantics)
                if (seenNote) {
                    RowSliceContext row = new RowSliceContext();
                    row.prefixStartOffset = prevRowEnd;
                    row.rowStartOffset = pos;
                    row.lastDurationBeforeRow = currentDuration;
                    row.lastFmInstrumentBeforeRow = lastFmInstrument;
                    row.lastPsgInstrumentBeforeRow = lastPsgInstrument;
                    row.prefixSetsDuration = durationSincePreviousRow;
                    row.prefixSetsFmInstrument = fmInstrumentSincePreviousRow;
                    row.prefixSetsPsgInstrument = psgInstrumentSincePreviousRow;
                    row.rowHasInlineDuration = true; // the byte IS the duration
                    currentDuration = b;
                    rows.add(row);
                    pos++;
                    prevRowEnd = pos;
                    durationSincePreviousRow = false;
                    fmInstrumentSincePreviousRow = false;
                    psgInstrumentSincePreviousRow = false;
                    continue;
                }
                currentDuration = b;
                durationSincePreviousRow = true;
                pos++;
                continue;
            }

            if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b, dialect);
                if (dialect == SmpsCoordFlags.Dialect.S3K
                        && b == SmpsCoordFlags.S3K_META && pos + 1 < trackData.length) {
                    paramCount += SmpsCoordFlags.getMetaParamCount(trackData[pos + 1] & 0xFF);
                }
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
        copy.setArrangementMode(source.getArrangementMode());
        copy.setDacChannelFm6(source.isDacChannelFm6());
        copy.setTempo(source.getTempo());
        copy.setDividingTiming(source.getDividingTiming());
        copy.setLoopPoint(source.getLoopPoint());
        // Slicing never mutates the structured arrangement; share the reference
        copy.setStructuredArrangement(source.getStructuredArrangement());

        copy.getVoiceBank().clear();
        for (FmVoice voice : source.getVoiceBank()) {
            copy.getVoiceBank().add(new FmVoice(voice.getName(), voice.getData()));
        }

        copy.getPsgEnvelopes().clear();
        for (PsgEnvelope env : source.getPsgEnvelopes()) {
            copy.getPsgEnvelopes().add(new PsgEnvelope(env.getName(), env.getData()));
        }

        copy.getModEnvelopes().clear();
        for (PsgEnvelope env : source.getModEnvelopes()) {
            copy.getModEnvelopes().add(new PsgEnvelope(env.getName(), env.getData()));
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

        // Deep copy hierarchical arrangement
        HierarchicalArrangement sourceArr = source.getHierarchicalArrangement();
        if (sourceArr != null) {
            HierarchicalArrangement copyArr = copy.getHierarchicalArrangement();
            for (Phrase phrase : sourceArr.getPhraseLibrary().getAllPhrases()) {
                Phrase copyPhrase = copyArr.getPhraseLibrary().createPhrase(
                        phrase.getName(), phrase.getChannelType());
                copyPhrase.setData(phrase.getDataDirect());
            }
            copyArr.getPhraseLibrary().setNextId(sourceArr.getPhraseLibrary().getNextId());

            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                Chain sourceChain = sourceArr.getChain(ch);
                Chain copyChain = copyArr.getChain(ch);
                for (ChainEntry entry : sourceChain.getEntries()) {
                    ChainEntry copyEntry = new ChainEntry(entry.getPhraseId());
                    copyEntry.setTransposeSemitones(entry.getTransposeSemitones());
                    copyEntry.setRepeatCount(entry.getRepeatCount());
                    copyChain.getEntries().add(copyEntry);
                }
                copyChain.setLoopEntryIndex(sourceChain.getLoopEntryIndex());
            }
        }

        return copy;
    }

    /**
     * Walk the bytecode of removed chain entries and synthesize a minimal
     * prefix reproducing the channel state at the slice point: last voice/PSG
     * instrument/noise/mod envelope, net additive volume and key offsets
     * (repeat counts multiply additive contributions), and the inherited
     * note duration.
     */
    /** Accumulated channel state and whether any of it is additive. */
    private record ChainState(byte[] prefix, boolean additive) {}

    private ChainState accumulateChainState(List<ChainEntry> removed,
            com.opensmpsdeck.model.PhraseLibrary library, int channel,
            SmpsCoordFlags.Dialect dialect) {
        boolean psg = channel >= 6;
        int lastVoice = -1;
        int lastPsgIns = -1;
        int lastNoise = -1;
        int lastModEnv = -1;          // S3K F4
        int volSum = 0;
        boolean volAbsSeen = false;   // S3K E4
        int volAbs = 0;
        int keySum = 0;
        boolean keySetSeen = false;   // S3K ED (TRNSP_SET)
        int keySet = 0;
        int lastDuration = 0;
        int transposeAdd = SmpsCoordFlags.transposeAddFlag(dialect);
        boolean s3k = dialect == SmpsCoordFlags.Dialect.S3K;

        for (ChainEntry entry : removed) {
            Phrase phrase = library.getPhrase(entry.getPhraseId());
            if (phrase == null) continue;
            byte[] d = phrase.getDataDirect();
            int reps = Math.max(1, entry.getRepeatCount());

            int pos = 0;
            int volDelta = 0;
            int keyDelta = 0;
            while (pos < d.length) {
                int b = d[pos] & 0xFF;
                if (b >= 0x81 && b <= 0xDF) {
                    pos++;
                    if (pos < d.length) {
                        int next = d[pos] & 0xFF;
                        if (next >= 0x01 && next <= 0x7F) {
                            lastDuration = next;
                            pos++;
                        }
                    }
                    continue;
                }
                if (b >= 0x01 && b <= 0x7F) {
                    lastDuration = b;
                    pos++;
                    continue;
                }
                if (b < 0xE0) { // rest 0x80
                    pos++;
                    if (pos < d.length) {
                        int next = d[pos] & 0xFF;
                        if (next >= 0x01 && next <= 0x7F) {
                            lastDuration = next;
                            pos++;
                        }
                    }
                    continue;
                }
                int params = SmpsCoordFlags.getParamCount(b, dialect);
                if (s3k && b == SmpsCoordFlags.S3K_META && pos + 1 < d.length) {
                    params += SmpsCoordFlags.getMetaParamCount(d[pos + 1] & 0xFF);
                }
                if (b == SmpsCoordFlags.SET_VOICE && pos + 1 < d.length) {
                    lastVoice = d[pos + 1] & 0xFF;
                } else if (b == SmpsCoordFlags.PSG_INSTRUMENT && pos + 1 < d.length) {
                    lastPsgIns = d[pos + 1] & 0xFF;
                } else if (b == SmpsCoordFlags.PSG_NOISE && pos + 1 < d.length) {
                    lastNoise = d[pos + 1] & 0xFF;
                } else if (s3k && b == 0xF4 && pos + 1 < d.length) {
                    lastModEnv = d[pos + 1] & 0xFF;
                } else if (b == SmpsCoordFlags.VOLUME && pos + 1 < d.length && !psg) {
                    volDelta += (byte) d[pos + 1];
                } else if (b == SmpsCoordFlags.PSG_VOLUME && pos + 1 < d.length && psg) {
                    volDelta += (byte) d[pos + 1];
                } else if (s3k && b == 0xE4 && pos + 1 < d.length) {
                    volAbsSeen = true;
                    volAbs = d[pos + 1] & 0xFF;
                    volDelta = 0;
                    volSum = 0;
                } else if (b == transposeAdd && pos + 1 < d.length) {
                    keyDelta += (byte) d[pos + 1];
                } else if (s3k && b == 0xED && pos + 1 < d.length) {
                    keySetSeen = true;
                    keySet = d[pos + 1] & 0xFF;
                    keyDelta = 0;
                    keySum = 0;
                }
                pos += 1 + params;
            }
            volSum += volDelta * reps;
            keySum += keyDelta * reps;
        }

        var out = new java.io.ByteArrayOutputStream();
        if (lastNoise >= 0 && psg) {
            out.write(SmpsCoordFlags.PSG_NOISE);
            out.write(lastNoise);
        }
        if (keySetSeen) {
            out.write(0xED);
            out.write(keySet);
        }
        if (keySum != 0) {
            out.write(transposeAdd);
            out.write(keySum & 0xFF);
        }
        if (volAbsSeen) {
            out.write(0xE4);
            out.write(volAbs);
        }
        if (volSum != 0) {
            out.write(psg ? SmpsCoordFlags.PSG_VOLUME : SmpsCoordFlags.VOLUME);
            out.write(volSum & 0xFF);
        }
        if (lastVoice >= 0 && !psg) {
            out.write(SmpsCoordFlags.SET_VOICE);
            out.write(lastVoice);
        }
        if (lastPsgIns >= 0 && psg) {
            out.write(SmpsCoordFlags.PSG_INSTRUMENT);
            out.write(lastPsgIns);
        }
        if (lastModEnv >= 0 && dialect == SmpsCoordFlags.Dialect.S3K) {
            out.write(0xF4);
            out.write(lastModEnv);
        }
        if (lastDuration > 0) {
            out.write(lastDuration);
        }
        boolean additive = volSum != 0 || keySum != 0;
        return new ChainState(out.toByteArray(), additive);
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
