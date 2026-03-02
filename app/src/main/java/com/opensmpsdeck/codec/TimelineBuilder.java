package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link UnrolledTimeline} from a {@link Song}'s hierarchical arrangement.
 *
 * <p>Walks each channel's chain, decodes phrase bytecode with {@link SmpsDecoder},
 * and maps every note/rest/tie event onto a unified grid whose resolution is
 * chosen by {@link GridResolutionCalculator}.
 */
public final class TimelineBuilder {

    private TimelineBuilder() {}

    /** Intermediate event before grid mapping. */
    private record RawEvent(
            int channelIndex,
            int startTick,
            int durationTicks,
            SmpsDecoder.TrackerRow decoded,
            UnrolledTimeline.SourceRef source,
            boolean isFromLoop) {}

    /**
     * Build a fully populated {@link UnrolledTimeline} from the given song.
     *
     * @param song the song to unroll
     * @return a new UnrolledTimeline with events, phrase spans, and loop-back rows
     */
    public static UnrolledTimeline build(Song song) {
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary library = arr.getPhraseLibrary();
        int dividingTiming = Math.max(1, song.getDividingTiming());

        // Phase 1: Collect raw events per channel
        @SuppressWarnings("unchecked")
        List<RawEvent>[] channelEvents = new List[Pattern.CHANNEL_COUNT];
        int[] loopBackTick = new int[Pattern.CHANNEL_COUNT];
        List<Integer> allDurations = new ArrayList<>();
        int maxTick = 0;

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            channelEvents[ch] = new ArrayList<>();
            loopBackTick[ch] = -1;

            Chain chain = arr.getChain(ch);
            List<ChainEntry> entries = chain.getEntries();
            int currentTick = 0;

            for (int entryIdx = 0; entryIdx < entries.size(); entryIdx++) {
                ChainEntry entry = entries.get(entryIdx);
                Phrase phrase = library.getPhrase(entry.getPhraseId());
                if (phrase == null) continue;

                byte[] data = phrase.getDataDirect();
                if (data == null || data.length == 0) continue;

                List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
                int repeatCount = Math.max(1, entry.getRepeatCount());

                // Record loop-back tick at the start of the loop target entry
                if (chain.hasLoop() && entryIdx == chain.getLoopEntryIndex()) {
                    loopBackTick[ch] = currentTick;
                }

                for (int rep = 0; rep < repeatCount; rep++) {
                    boolean isFromLoop = rep > 0;

                    for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                        SmpsDecoder.TrackerRow row = rows.get(rowIdx);
                        if (row.duration() <= 0) continue;

                        int scaledDuration = row.duration() * dividingTiming;
                        allDurations.add(scaledDuration);

                        var source = new UnrolledTimeline.SourceRef(
                                entry.getPhraseId(), entryIdx, rowIdx);
                        channelEvents[ch].add(new RawEvent(
                                ch, currentTick, scaledDuration, row, source, isFromLoop));

                        currentTick += scaledDuration;
                    }
                }
            }

            if (currentTick > maxTick) {
                maxTick = currentTick;
            }
        }

        // Phase 2: Compute grid resolution
        int gridResolution = GridResolutionCalculator.calculate(allDurations);

        // Phase 3: Map to grid rows
        int totalGridRows = gridResolution > 0 ? ceilDiv(maxTick, gridResolution) : 0;
        UnrolledTimeline timeline = new UnrolledTimeline(gridResolution, totalGridRows);

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            UnrolledTimeline.TimelineChannel channel = timeline.channel(ch);
            List<RawEvent> events = channelEvents[ch];

            int currentPhraseId = -1;
            int phraseSpanStartRow = -1;

            for (RawEvent raw : events) {
                int startGridRow = gridResolution > 0 ? raw.startTick() / gridResolution : 0;
                int spanRows = Math.max(1, gridResolution > 0
                        ? raw.durationTicks() / gridResolution : 1);

                var event = new UnrolledTimeline.TimelineEvent(
                        raw.startTick(), raw.durationTicks(),
                        startGridRow, spanRows,
                        raw.decoded(), raw.source(), raw.isFromLoop());
                channel.events().add(event);

                // Track phrase spans
                int phraseId = raw.source().phraseId();
                if (phraseId != currentPhraseId) {
                    // Close previous span
                    if (currentPhraseId >= 0 && phraseSpanStartRow >= 0) {
                        int endRow = Math.max(phraseSpanStartRow, startGridRow - 1);
                        channel.phraseSpans().add(new UnrolledTimeline.PhraseSpan(
                                phraseSpanStartRow, endRow, currentPhraseId));
                    }
                    currentPhraseId = phraseId;
                    phraseSpanStartRow = startGridRow;
                }
            }

            // Close last phrase span
            if (currentPhraseId >= 0 && phraseSpanStartRow >= 0 && !events.isEmpty()) {
                RawEvent lastEvent = events.get(events.size() - 1);
                int lastStartRow = gridResolution > 0
                        ? lastEvent.startTick() / gridResolution : 0;
                int lastSpan = Math.max(1, gridResolution > 0
                        ? lastEvent.durationTicks() / gridResolution : 1);
                int endRow = lastStartRow + lastSpan - 1;
                channel.phraseSpans().add(new UnrolledTimeline.PhraseSpan(
                        phraseSpanStartRow, endRow, currentPhraseId));
            }

            // Map loop-back tick to grid row
            if (loopBackTick[ch] >= 0 && gridResolution > 0) {
                timeline.channelLoopBackRow()[ch] = loopBackTick[ch] / gridResolution;
            }
        }

        return timeline;
    }

    /** Ceiling integer division (both operands positive). */
    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
