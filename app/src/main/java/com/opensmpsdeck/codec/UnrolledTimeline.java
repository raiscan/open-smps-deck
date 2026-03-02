package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.Pattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Flattened timeline of decoded SMPS events for the unrolled tracker view.
 *
 * <p>Built by TimelineBuilder from a hierarchical arrangement, this model
 * maps every note/rest/tie event to a grid row and provides phrase boundary
 * spans for background tinting. Each channel also stores an optional loop-back
 * row for visual loop indication.
 */
public final class UnrolledTimeline {

    /** Reference back to the source phrase and position for navigation. */
    public record SourceRef(int phraseId, int chainEntryIndex, int rowInPhrase) {}

    /** A single note/rest/tie event placed on the unrolled grid. */
    public record TimelineEvent(
            int startTick,
            int durationTicks,
            int startGridRow,
            int spanRows,
            SmpsDecoder.TrackerRow decoded,
            SourceRef source,
            boolean isFromLoop) {}

    /** Row range occupied by a phrase, for background tinting. */
    public record PhraseSpan(int startRow, int endRow, int phraseId) {}

    /** Mutable list of events and phrase spans for a single channel. */
    public static final class TimelineChannel {
        private final List<TimelineEvent> events = new ArrayList<>();
        private final List<PhraseSpan> phraseSpans = new ArrayList<>();

        public List<TimelineEvent> events() { return events; }
        public List<PhraseSpan> phraseSpans() { return phraseSpans; }
    }

    private final int gridResolution;
    private final int totalGridRows;
    private final TimelineChannel[] channels;
    private final int[] channelLoopBackRow;

    public UnrolledTimeline(int gridResolution, int totalGridRows) {
        this.gridResolution = gridResolution;
        this.totalGridRows = totalGridRows;
        this.channels = new TimelineChannel[Pattern.CHANNEL_COUNT];
        for (int i = 0; i < Pattern.CHANNEL_COUNT; i++) {
            channels[i] = new TimelineChannel();
        }
        this.channelLoopBackRow = new int[Pattern.CHANNEL_COUNT];
        Arrays.fill(channelLoopBackRow, -1);
    }

    public int gridResolution() { return gridResolution; }
    public int totalGridRows() { return totalGridRows; }
    public TimelineChannel[] channels() { return channels; }
    public int[] channelLoopBackRow() { return channelLoopBackRow; }
}
