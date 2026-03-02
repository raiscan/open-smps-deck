package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestTimelineBuilder {

    /** Helper to build a phrase with raw SMPS bytes. */
    private static Phrase makePhrase(PhraseLibrary library, String name, ChannelType type, byte[] data) {
        Phrase p = library.createPhrase(name, type);
        p.setData(data);
        return p;
    }

    @Test
    void singleChannelSinglePhrase() {
        Song song = new Song();
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();

        // C-0 dur=12, D-0 dur=6, rest dur=6 => total 24 ticks
        Phrase p = makePhrase(lib, "P1", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x0C,   // C-0, duration 12
                (byte) 0x83, 0x06,   // D-0, duration 6
                (byte) 0x80, 0x06    // rest, duration 6
        });

        Chain chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(p.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        var events = timeline.channel(0).events();
        assertEquals(3, events.size());

        assertEquals(0, events.get(0).startTick());
        assertEquals(12, events.get(0).durationTicks());
        assertEquals("C-0", events.get(0).decoded().note());

        assertEquals(12, events.get(1).startTick());
        assertEquals(6, events.get(1).durationTicks());
        assertEquals("D-0", events.get(1).decoded().note());

        assertEquals(18, events.get(2).startTick());
        assertEquals(6, events.get(2).durationTicks());
        assertEquals("---", events.get(2).decoded().note());

        // No loop
        assertEquals(-1, timeline.channelLoopBackRow()[0]);
    }

    @Test
    void repeatCountUnrolls() {
        Song song = new Song();
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();

        // C-0 dur=6
        Phrase p = makePhrase(lib, "P1", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x06
        });

        Chain chain = arr.getChain(0);
        ChainEntry entry = new ChainEntry(p.getId());
        entry.setRepeatCount(3);
        chain.getEntries().add(entry);

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        var events = timeline.channel(0).events();
        assertEquals(3, events.size());

        assertEquals(0, events.get(0).startTick());
        assertEquals(6, events.get(1).startTick());
        assertEquals(12, events.get(2).startTick());

        assertFalse(events.get(0).isFromLoop());
        assertTrue(events.get(1).isFromLoop());
        assertTrue(events.get(2).isFromLoop());
    }

    @Test
    void dividingTimingScalesDurations() {
        Song song = new Song();
        song.setDividingTiming(2);
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();

        // C-0 dur=6
        Phrase p = makePhrase(lib, "P1", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x06
        });

        Chain chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(p.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        var events = timeline.channel(0).events();
        assertEquals(1, events.size());
        assertEquals(12, events.get(0).durationTicks());
    }

    @Test
    void loopBackRowRecorded() {
        Song song = new Song();
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();

        // Two phrases, each 6 ticks
        Phrase p1 = makePhrase(lib, "P1", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x06
        });
        Phrase p2 = makePhrase(lib, "P2", ChannelType.FM, new byte[]{
                (byte) 0x83, 0x06
        });

        Chain chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(p1.getId()));
        chain.getEntries().add(new ChainEntry(p2.getId()));
        chain.setLoopEntryIndex(1);

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        // Phrase 2 starts at tick 6. Grid resolution for durations [6,6] is 6.
        // Grid row for tick 6 = 6/6 = 1
        int loopRow = timeline.channelLoopBackRow()[0];
        assertTrue(loopRow >= 0, "Loop-back row should be recorded");
        assertEquals(1, loopRow);
    }

    @Test
    void multipleChannelsAligned() {
        Song song = new Song();
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();

        // FM1 (channel 0): one note 12 ticks
        Phrase pFM = makePhrase(lib, "FM1", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x0C
        });
        Chain fmChain = arr.getChain(0);
        fmChain.getEntries().add(new ChainEntry(pFM.getId()));

        // PSG1 (channel 6): two notes 6 ticks each
        Phrase pPSG = makePhrase(lib, "PSG1", ChannelType.PSG_TONE, new byte[]{
                (byte) 0x81, 0x06,
                (byte) 0x83, 0x06
        });
        Chain psgChain = arr.getChain(6);
        psgChain.getEntries().add(new ChainEntry(pPSG.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        assertEquals(1, timeline.channel(0).events().size());
        assertEquals(2, timeline.channel(6).events().size());

        // Both end at tick 12
        var fmEvent = timeline.channel(0).events().get(0);
        assertEquals(12, fmEvent.startTick() + fmEvent.durationTicks());

        var psgEvents = timeline.channel(6).events();
        var lastPsg = psgEvents.get(psgEvents.size() - 1);
        assertEquals(12, lastPsg.startTick() + lastPsg.durationTicks());
    }

    @Test
    void emptyChannelsProduceNoEvents() {
        Song song = new Song();
        UnrolledTimeline timeline = TimelineBuilder.build(song);

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            assertTrue(timeline.channel(ch).events().isEmpty());
            assertTrue(timeline.channel(ch).phraseSpans().isEmpty());
        }
        assertEquals(0, timeline.totalGridRows());
    }

    @Test
    void phraseSpansTracked() {
        Song song = new Song();
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();

        Phrase p1 = makePhrase(lib, "A", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x06
        });
        Phrase p2 = makePhrase(lib, "B", ChannelType.FM, new byte[]{
                (byte) 0x83, 0x06
        });

        Chain chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(p1.getId()));
        chain.getEntries().add(new ChainEntry(p2.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        var spans = timeline.channel(0).phraseSpans();
        assertEquals(2, spans.size());

        assertEquals(p1.getId(), spans.get(0).phraseId());
        assertEquals(p2.getId(), spans.get(1).phraseId());

        // Contiguous: first span ends where second begins
        assertTrue(spans.get(0).endRow() < spans.get(1).startRow()
                || spans.get(0).endRow() == spans.get(1).startRow() - 1);
    }

    @Test
    void sourceRefNavigatesBackToPhrase() {
        Song song = new Song();
        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();

        // Phrase with 2 notes
        Phrase p = makePhrase(lib, "P1", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x06,
                (byte) 0x83, 0x06
        });

        Chain chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(p.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        var events = timeline.channel(0).events();
        assertEquals(2, events.size());

        assertEquals(p.getId(), events.get(0).source().phraseId());
        assertEquals(0, events.get(0).source().chainEntryIndex());
        assertEquals(0, events.get(0).source().rowInPhrase());

        assertEquals(p.getId(), events.get(1).source().phraseId());
        assertEquals(0, events.get(1).source().chainEntryIndex());
        assertEquals(1, events.get(1).source().rowInPhrase());
    }
}
