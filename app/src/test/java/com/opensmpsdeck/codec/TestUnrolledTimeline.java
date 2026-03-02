package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestUnrolledTimeline {

    @Test
    void timelineEventHoldsSourceRef() {
        var source = new UnrolledTimeline.SourceRef(5, 2, 3);
        assertEquals(5, source.phraseId());
        assertEquals(2, source.chainEntryIndex());
        assertEquals(3, source.rowInPhrase());

        var row = new SmpsDecoder.TrackerRow("C-3", 24, "00", "");
        var event = new UnrolledTimeline.TimelineEvent(
                100, 24, 10, 1, row, source, false);

        assertEquals(100, event.startTick());
        assertEquals(24, event.durationTicks());
        assertEquals(10, event.startGridRow());
        assertEquals(1, event.spanRows());
        assertSame(row, event.decoded());
        assertSame(source, event.source());
        assertFalse(event.isFromLoop());
    }

    @Test
    void phraseSpanHoldsBounds() {
        var span = new UnrolledTimeline.PhraseSpan(0, 15, 42);
        assertEquals(0, span.startRow());
        assertEquals(15, span.endRow());
        assertEquals(42, span.phraseId());
    }

    @Test
    void timelineChannelStoresEvents() {
        var channel = new UnrolledTimeline.TimelineChannel();
        assertTrue(channel.events().isEmpty());
        assertTrue(channel.phraseSpans().isEmpty());

        var source = new UnrolledTimeline.SourceRef(1, 0, 0);
        var row = new SmpsDecoder.TrackerRow("D-4", 12, "", "");
        var event = new UnrolledTimeline.TimelineEvent(
                0, 12, 0, 1, row, source, false);
        channel.events().add(event);

        var span = new UnrolledTimeline.PhraseSpan(0, 7, 1);
        channel.phraseSpans().add(span);

        assertEquals(1, channel.events().size());
        assertSame(event, channel.events().get(0));
        assertEquals(1, channel.phraseSpans().size());
        assertSame(span, channel.phraseSpans().get(0));
    }

    @Test
    void unrolledTimelineHoldsTenChannels() {
        var timeline = new UnrolledTimeline(4, 128);
        assertEquals(4, timeline.gridResolution());
        assertEquals(128, timeline.totalGridRows());
        assertEquals(Pattern.CHANNEL_COUNT, timeline.channels().length);

        for (int i = 0; i < Pattern.CHANNEL_COUNT; i++) {
            assertNotNull(timeline.channels()[i]);
            assertTrue(timeline.channels()[i].events().isEmpty());
            assertTrue(timeline.channels()[i].phraseSpans().isEmpty());
        }

        // Loop-back rows default to -1
        assertEquals(Pattern.CHANNEL_COUNT, timeline.channelLoopBackRow().length);
        for (int i = 0; i < Pattern.CHANNEL_COUNT; i++) {
            assertEquals(-1, timeline.channelLoopBackRow()[i]);
        }
    }
}
