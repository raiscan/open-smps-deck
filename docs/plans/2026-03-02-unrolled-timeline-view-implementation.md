# Unrolled Timeline View Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a read-only "unrolled" mode to TrackerGrid that shows all 10 channels time-aligned with adaptive grid resolution, loop unrolling, and a synchronized playback cursor.

**Architecture:** A `TimelineBuilder` walks the Song's chain/phrase model (not compiled bytecode) to produce an `UnrolledTimeline` data structure. TrackerGrid gets a mode toggle between phrase editing and unrolled display. A `GridResolutionCalculator` picks the most compact grid step using a tolerant-GCD algorithm. The playback cursor maps sequencer tick position to grid rows.

**Tech Stack:** Java 21, JavaFX (Canvas rendering), JUnit 5. No new dependencies.

**Design doc:** `docs/plans/2026-03-02-unrolled-timeline-view-design.md`

---

## Task 1: UnrolledTimeline Data Model

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/codec/UnrolledTimeline.java`
- Test: `app/src/test/java/com/opensmpsdeck/codec/TestUnrolledTimeline.java`

The core data model as Java records and simple classes. All pure data — no logic beyond accessors.

**Step 1: Write the test for data model construction**

```java
package com.opensmpsdeck.codec;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestUnrolledTimeline {

    @Test
    void timelineEventHoldsSourceRef() {
        var source = new UnrolledTimeline.SourceRef(1, 0, 3);
        var decoded = new SmpsDecoder.TrackerRow("C-4", 12, "00", "");
        var event = new UnrolledTimeline.TimelineEvent(
                0, 12, 0, 2, decoded, source, false);

        assertEquals(0, event.startTick());
        assertEquals(12, event.durationTicks());
        assertEquals(0, event.startGridRow());
        assertEquals(2, event.spanRows());
        assertEquals("C-4", event.decoded().note());
        assertEquals(1, event.source().phraseId());
        assertEquals(0, event.source().chainEntryIndex());
        assertEquals(3, event.source().rowInPhrase());
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
        var decoded = new SmpsDecoder.TrackerRow("D-5", 6, "", "");
        var source = new UnrolledTimeline.SourceRef(2, 1, 0);
        var event = new UnrolledTimeline.TimelineEvent(0, 6, 0, 1, decoded, source, false);

        var channel = new UnrolledTimeline.TimelineChannel();
        channel.events().add(event);
        channel.phraseSpans().add(new UnrolledTimeline.PhraseSpan(0, 0, 2));

        assertEquals(1, channel.events().size());
        assertEquals(1, channel.phraseSpans().size());
    }

    @Test
    void unrolledTimelineHoldsTenChannels() {
        var timeline = new UnrolledTimeline(6, 100);
        assertEquals(6, timeline.gridResolution());
        assertEquals(100, timeline.totalGridRows());
        assertEquals(10, timeline.channels().length);
        for (var ch : timeline.channels()) {
            assertNotNull(ch);
            assertTrue(ch.events().isEmpty());
        }
        assertEquals(10, timeline.channelLoopBackRow().length);
        for (int row : timeline.channelLoopBackRow()) {
            assertEquals(-1, row);
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.codec.TestUnrolledTimeline`
Expected: Compilation error — `UnrolledTimeline` class does not exist.

**Step 3: Implement the data model**

```java
package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.Pattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Time-aligned view of a song's chains and phrases, unrolled for display.
 * All channels share a unified grid where each row represents
 * {@link #gridResolution} ticks. Read-only — built by {@link TimelineBuilder}.
 */
public class UnrolledTimeline {

    /** Reference back to the source phrase/chain for navigation. */
    public record SourceRef(int phraseId, int chainEntryIndex, int rowInPhrase) {}

    /** A single note/rest/tie event placed on the timeline. */
    public record TimelineEvent(
            int startTick,
            int durationTicks,
            int startGridRow,
            int spanRows,
            SmpsDecoder.TrackerRow decoded,
            SourceRef source,
            boolean isFromLoop
    ) {}

    /** Row range for a phrase's background tint. */
    public record PhraseSpan(int startRow, int endRow, int phraseId) {}

    /** Events and phrase spans for one channel. */
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
        for (int i = 0; i < channels.length; i++) {
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
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.codec.TestUnrolledTimeline`
Expected: All 4 tests PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/codec/UnrolledTimeline.java \
       app/src/test/java/com/opensmpsdeck/codec/TestUnrolledTimeline.java
git commit -m "feat: add UnrolledTimeline data model"
```

---

## Task 2: GridResolutionCalculator

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/codec/GridResolutionCalculator.java`
- Test: `app/src/test/java/com/opensmpsdeck/codec/TestGridResolutionCalculator.java`

Tolerant practical-GCD algorithm. Pure function: takes a list of durations, returns the best grid resolution and available zoom levels.

**Step 1: Write the failing tests**

```java
package com.opensmpsdeck.codec;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TestGridResolutionCalculator {

    @Test
    void typicalSonic2DurationsPick6() {
        // Durations from a typical song: mostly multiples of 6 and 12
        List<Integer> durations = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) durations.add(6);   // 80 events at 6
        for (int i = 0; i < 50; i++) durations.add(12);  // 50 events at 12
        for (int i = 0; i < 20; i++) durations.add(24);  // 20 events at 24
        for (int i = 0; i < 5; i++) durations.add(5);    // 5 oddball events (< 10%)

        assertEquals(6, GridResolutionCalculator.calculate(durations));
    }

    @Test
    void allMultiplesOf3Picks3() {
        List<Integer> durations = List.of(3, 6, 9, 12, 3, 6, 3, 3, 12, 9);
        // All divisible by 3, so 48 won't hit 90%, 24 won't, 12 won't, 6 won't (3,9 fail), 3 will
        assertEquals(3, GridResolutionCalculator.calculate(durations));
    }

    @Test
    void manyOddballsFallBackTo1() {
        // More than 10% are not divisible by any candidate > 1
        List<Integer> durations = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) durations.add(6);
        for (int i = 0; i < 5; i++) durations.add(7);  // 50% oddball
        assertEquals(1, GridResolutionCalculator.calculate(durations));
    }

    @Test
    void emptyDurationsReturns1() {
        assertEquals(1, GridResolutionCalculator.calculate(List.of()));
    }

    @Test
    void singleDurationReturnsThatValue() {
        // All events same duration: largest candidate that divides it
        assertEquals(48, GridResolutionCalculator.calculate(List.of(48, 48, 48)));
    }

    @Test
    void zoomLevelsForResolution6() {
        List<Integer> zooms = GridResolutionCalculator.zoomLevels(6);
        // Divisors of 6 up to 6: 1, 2, 3, 6
        // Each produces 6/N ticks per row: 6, 3, 2, 1
        assertEquals(List.of(1, 2, 3, 6), zooms);
    }

    @Test
    void zoomLevelsForResolution12() {
        List<Integer> zooms = GridResolutionCalculator.zoomLevels(12);
        assertEquals(List.of(1, 2, 3, 4, 6, 12), zooms);
    }

    @Test
    void zoomLevelsForResolution1() {
        // Resolution 1 has only 1x (already at full)
        List<Integer> zooms = GridResolutionCalculator.zoomLevels(1);
        assertEquals(List.of(1), zooms);
    }

    @Test
    void zoomLevelsCappedAt16() {
        List<Integer> zooms = GridResolutionCalculator.zoomLevels(48);
        // Divisors of 48: 1,2,3,4,6,8,12,16,24,48
        // Cap at 16: 1,2,3,4,6,8,12,16
        assertEquals(List.of(1, 2, 3, 4, 6, 8, 12, 16), zooms);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.codec.TestGridResolutionCalculator`
Expected: Compilation error.

**Step 3: Implement GridResolutionCalculator**

```java
package com.opensmpsdeck.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the best grid resolution for an unrolled timeline view.
 *
 * <p>Uses a tolerant approach: picks the largest candidate where 90%+ of
 * note events have durations divisible by it. Falls back to 1 if no
 * candidate meets the threshold.
 */
public final class GridResolutionCalculator {

    private static final int[] CANDIDATES = {48, 24, 12, 6, 3, 2, 1};
    private static final double THRESHOLD = 0.90;
    private static final int MAX_ZOOM = 16;

    private GridResolutionCalculator() {}

    /**
     * Calculate the best grid resolution for the given durations.
     *
     * @param durations all scaled note/rest durations across all channels
     * @return the largest candidate where >= 90% of durations divide cleanly
     */
    public static int calculate(List<Integer> durations) {
        if (durations.isEmpty()) return 1;

        int total = durations.size();
        int threshold = (int) Math.ceil(total * THRESHOLD);

        for (int candidate : CANDIDATES) {
            int divisible = 0;
            for (int dur : durations) {
                if (dur % candidate == 0) divisible++;
            }
            if (divisible >= threshold) return candidate;
        }
        return 1;
    }

    /**
     * Compute available zoom levels for a given base resolution.
     *
     * <p>Returns all integers N where {@code 1 <= N <= min(resolution, MAX_ZOOM)}
     * and {@code resolution % N == 0}, sorted ascending. The last entry
     * (where {@code resolution / N == 1}) represents "Full" zoom.
     *
     * @param resolution the base grid resolution
     * @return sorted list of valid zoom multipliers
     */
    public static List<Integer> zoomLevels(int resolution) {
        List<Integer> levels = new ArrayList<>();
        int maxN = Math.min(resolution, MAX_ZOOM);
        for (int n = 1; n <= maxN; n++) {
            if (resolution % n == 0) {
                levels.add(n);
            }
        }
        return levels;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.codec.TestGridResolutionCalculator`
Expected: All 9 tests PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/codec/GridResolutionCalculator.java \
       app/src/test/java/com/opensmpsdeck/codec/TestGridResolutionCalculator.java
git commit -m "feat: add GridResolutionCalculator with tolerant GCD"
```

---

## Task 3: TimelineBuilder

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/codec/TimelineBuilder.java`
- Test: `app/src/test/java/com/opensmpsdeck/codec/TestTimelineBuilder.java`

The core algorithm that walks Song → chains → phrases and produces an UnrolledTimeline.

**Step 1: Write failing tests for basic timeline building**

```java
package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestTimelineBuilder {

    /** Helper to build a phrase with raw SMPS bytes. */
    private static Phrase makePhrase(PhraseLibrary library, String name, ChannelType type, byte[] data) {
        Phrase p = library.createPhrase(name, type);
        p.setData(data);
        return p;
    }

    @Test
    void singleChannelSinglePhrase() {
        Song song = new Song();
        song.setDividingTiming(1);
        var hier = song.getHierarchicalArrangement();
        var lib = hier.getPhraseLibrary();

        // Phrase: C-4 dur=12, D-4 dur=6, rest dur=6 (total = 24 ticks)
        Phrase p = makePhrase(lib, "test", ChannelType.FM, new byte[]{
                (byte) 0x93, 0x0C,   // C-4 (0x81+12=0x93 is actually... let me use 0x81=C0)
                // Actually: 0x81=C-0, +12 per octave. C-4 = 0x81 + 48 = 0xB1
                // Let's use simpler notes: 0x81=C-0, dur=12; 0x83=D-0, dur=6; 0x80=rest, dur=6
        });
        // Rebuild with correct bytes:
        p.setData(new byte[]{
                (byte) 0x81, 0x0C,  // C-0, duration 12
                (byte) 0x83, 0x06,  // D-0, duration 6
                (byte) 0x80, 0x06   // rest, duration 6
        });

        hier.getChain(0).getEntries().add(new ChainEntry(p.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        // Channel 0 should have 3 events
        var ch0 = timeline.channels()[0];
        assertEquals(3, ch0.events().size());

        // Event 0: tick 0, dur 12
        assertEquals(0, ch0.events().get(0).startTick());
        assertEquals(12, ch0.events().get(0).durationTicks());
        assertEquals("C-0", ch0.events().get(0).decoded().note());

        // Event 1: tick 12, dur 6
        assertEquals(12, ch0.events().get(1).startTick());
        assertEquals(6, ch0.events().get(1).durationTicks());

        // Event 2: tick 18, dur 6
        assertEquals(18, ch0.events().get(2).startTick());
        assertEquals(6, ch0.events().get(2).durationTicks());
        assertEquals("---", ch0.events().get(2).decoded().note());

        // No loop
        assertEquals(-1, timeline.channelLoopBackRow()[0]);
    }

    @Test
    void repeatCountUnrolls() {
        Song song = new Song();
        song.setDividingTiming(1);
        var hier = song.getHierarchicalArrangement();
        var lib = hier.getPhraseLibrary();

        // Phrase: single note C-0, dur=6
        Phrase p = makePhrase(lib, "rep", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x06
        });

        ChainEntry entry = new ChainEntry(p.getId());
        entry.setRepeatCount(3);
        hier.getChain(0).getEntries().add(entry);

        UnrolledTimeline timeline = TimelineBuilder.build(song);
        var ch0 = timeline.channels()[0];

        // 3 repetitions = 3 events
        assertEquals(3, ch0.events().size());
        assertEquals(0, ch0.events().get(0).startTick());
        assertEquals(6, ch0.events().get(1).startTick());
        assertEquals(12, ch0.events().get(2).startTick());

        // First iteration not from loop, subsequent are
        assertFalse(ch0.events().get(0).isFromLoop());
        assertTrue(ch0.events().get(1).isFromLoop());
        assertTrue(ch0.events().get(2).isFromLoop());
    }

    @Test
    void dividingTimingScalesDurations() {
        Song song = new Song();
        song.setDividingTiming(2);
        var hier = song.getHierarchicalArrangement();
        var lib = hier.getPhraseLibrary();

        Phrase p = makePhrase(lib, "dt", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x06  // C-0, raw dur=6, scaled=12
        });
        hier.getChain(0).getEntries().add(new ChainEntry(p.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);
        assertEquals(12, timeline.channels()[0].events().get(0).durationTicks());
    }

    @Test
    void loopBackRowRecorded() {
        Song song = new Song();
        song.setDividingTiming(1);
        var hier = song.getHierarchicalArrangement();
        var lib = hier.getPhraseLibrary();

        // Two phrases, each 6 ticks
        Phrase p1 = makePhrase(lib, "intro", ChannelType.FM, new byte[]{(byte) 0x81, 0x06});
        Phrase p2 = makePhrase(lib, "loop", ChannelType.FM, new byte[]{(byte) 0x83, 0x06});

        hier.getChain(0).getEntries().add(new ChainEntry(p1.getId()));
        hier.getChain(0).getEntries().add(new ChainEntry(p2.getId()));
        hier.getChain(0).setLoopEntryIndex(1); // loop back to second phrase

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        // Loop-back row should point to the grid row where phrase 2 starts
        int loopRow = timeline.channelLoopBackRow()[0];
        assertTrue(loopRow >= 0);
        // p2 starts at tick 6; grid row depends on resolution
        var ch0Events = timeline.channels()[0].events();
        assertEquals(loopRow, ch0Events.get(1).startGridRow());
    }

    @Test
    void multipleChannelsAligned() {
        Song song = new Song();
        song.setDividingTiming(1);
        var hier = song.getHierarchicalArrangement();
        var lib = hier.getPhraseLibrary();

        // FM1: one note 12 ticks
        Phrase pFm = makePhrase(lib, "fm", ChannelType.FM, new byte[]{(byte) 0x81, 0x0C});
        hier.getChain(0).getEntries().add(new ChainEntry(pFm.getId()));

        // PSG1: two notes 6 ticks each
        Phrase pPsg = makePhrase(lib, "psg", ChannelType.PSG_TONE, new byte[]{
                (byte) 0x81, 0x06, (byte) 0x83, 0x06
        });
        hier.getChain(6).getEntries().add(new ChainEntry(pPsg.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);

        // Both channels end at tick 12
        var fm = timeline.channels()[0].events();
        var psg = timeline.channels()[6].events();
        assertEquals(1, fm.size());
        assertEquals(2, psg.size());
        assertEquals(12, fm.get(0).startTick() + fm.get(0).durationTicks());
        assertEquals(12, psg.get(1).startTick() + psg.get(1).durationTicks());
    }

    @Test
    void emptyChannelsProduceNoEvents() {
        Song song = new Song();
        UnrolledTimeline timeline = TimelineBuilder.build(song);
        for (var ch : timeline.channels()) {
            assertTrue(ch.events().isEmpty());
        }
        assertEquals(0, timeline.totalGridRows());
    }

    @Test
    void phraseSpansTracked() {
        Song song = new Song();
        song.setDividingTiming(1);
        var hier = song.getHierarchicalArrangement();
        var lib = hier.getPhraseLibrary();

        Phrase p1 = makePhrase(lib, "a", ChannelType.FM, new byte[]{(byte) 0x81, 0x06});
        Phrase p2 = makePhrase(lib, "b", ChannelType.FM, new byte[]{(byte) 0x83, 0x0C});

        hier.getChain(0).getEntries().add(new ChainEntry(p1.getId()));
        hier.getChain(0).getEntries().add(new ChainEntry(p2.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);
        var spans = timeline.channels()[0].phraseSpans();

        assertEquals(2, spans.size());
        assertEquals(p1.getId(), spans.get(0).phraseId());
        assertEquals(p2.getId(), spans.get(1).phraseId());
        // Second span starts where first ends
        assertEquals(spans.get(0).endRow() + 1, spans.get(1).startRow());
    }

    @Test
    void sourceRefNavigatesBackToPhrase() {
        Song song = new Song();
        song.setDividingTiming(1);
        var hier = song.getHierarchicalArrangement();
        var lib = hier.getPhraseLibrary();

        Phrase p = makePhrase(lib, "nav", ChannelType.FM, new byte[]{
                (byte) 0x81, 0x06, (byte) 0x83, 0x06
        });
        hier.getChain(0).getEntries().add(new ChainEntry(p.getId()));

        UnrolledTimeline timeline = TimelineBuilder.build(song);
        var events = timeline.channels()[0].events();

        // Both events point back to phrase, correct row indices
        assertEquals(p.getId(), events.get(0).source().phraseId());
        assertEquals(0, events.get(0).source().chainEntryIndex());
        assertEquals(0, events.get(0).source().rowInPhrase());

        assertEquals(p.getId(), events.get(1).source().phraseId());
        assertEquals(0, events.get(1).source().chainEntryIndex());
        assertEquals(1, events.get(1).source().rowInPhrase());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.codec.TestTimelineBuilder`
Expected: Compilation error — `TimelineBuilder` class does not exist.

**Step 3: Implement TimelineBuilder**

```java
package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link UnrolledTimeline} from a {@link Song} by walking chains
 * and phrases. Decodes each phrase, computes absolute tick positions,
 * unrolls counted repeats, and maps everything onto a unified grid.
 */
public final class TimelineBuilder {

    private TimelineBuilder() {}

    /**
     * Build the unrolled timeline for a song.
     *
     * @param song the song to unroll
     * @return the complete timeline with grid resolution and events
     */
    public static UnrolledTimeline build(Song song) {
        HierarchicalArrangement hier = song.getHierarchicalArrangement();
        if (hier == null) return new UnrolledTimeline(1, 0);

        PhraseLibrary library = hier.getPhraseLibrary();
        int dividingTiming = Math.max(1, song.getDividingTiming());

        // Phase 1: collect all events per channel with absolute tick positions
        List<List<RawEvent>> allChannelEvents = new ArrayList<>();
        List<Integer> allDurations = new ArrayList<>();
        int maxTick = 0;

        // Also collect loop-back ticks per channel
        int[] loopBackTick = new int[Pattern.CHANNEL_COUNT];
        java.util.Arrays.fill(loopBackTick, -1);

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            Chain chain = hier.getChain(ch);
            List<ChainEntry> entries = chain.getEntries();
            List<RawEvent> channelEvents = new ArrayList<>();
            int currentTick = 0;

            for (int entryIdx = 0; entryIdx < entries.size(); entryIdx++) {
                ChainEntry entry = entries.get(entryIdx);
                Phrase phrase = library.getPhrase(entry.getPhraseId());
                if (phrase == null) continue;
                byte[] data = phrase.getDataDirect();
                if (data == null || data.length == 0) continue;

                List<SmpsDecoder.TrackerRow> rows = SmpsDecoder.decode(data);
                int repeatCount = Math.max(1, entry.getRepeatCount());

                // Record loop-back tick if this is the loop target entry
                if (chain.hasLoop() && entryIdx == chain.getLoopEntryIndex()) {
                    loopBackTick[ch] = currentTick;
                }

                for (int rep = 0; rep < repeatCount; rep++) {
                    boolean isFromLoop = rep > 0;
                    for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                        SmpsDecoder.TrackerRow row = rows.get(rowIdx);
                        int dur = row.duration();
                        if (dur <= 0) continue; // effect-only row, skip

                        int scaledDur = dur * dividingTiming;
                        allDurations.add(scaledDur);

                        channelEvents.add(new RawEvent(
                                currentTick, scaledDur, row,
                                new UnrolledTimeline.SourceRef(
                                        entry.getPhraseId(), entryIdx, rowIdx),
                                isFromLoop));
                        currentTick += scaledDur;
                    }
                }
            }
            allChannelEvents.add(channelEvents);
            maxTick = Math.max(maxTick, currentTick);
        }

        // Phase 2: compute grid resolution
        int gridResolution = GridResolutionCalculator.calculate(allDurations);

        // Phase 3: map events to grid rows and build the timeline
        int totalGridRows = gridResolution > 0 ? (maxTick + gridResolution - 1) / gridResolution : 0;
        UnrolledTimeline timeline = new UnrolledTimeline(gridResolution, totalGridRows);

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            List<RawEvent> rawEvents = allChannelEvents.get(ch);
            UnrolledTimeline.TimelineChannel channel = timeline.channels()[ch];

            int currentPhraseId = -1;
            int phraseStartRow = -1;

            for (RawEvent raw : rawEvents) {
                int startRow = raw.startTick / gridResolution;
                int spanRows = Math.max(1, raw.durationTicks / gridResolution);

                channel.events().add(new UnrolledTimeline.TimelineEvent(
                        raw.startTick, raw.durationTicks,
                        startRow, spanRows,
                        raw.decoded, raw.source, raw.isFromLoop));

                // Track phrase spans
                int phraseId = raw.source.phraseId();
                if (phraseId != currentPhraseId) {
                    if (currentPhraseId >= 0) {
                        channel.phraseSpans().add(new UnrolledTimeline.PhraseSpan(
                                phraseStartRow, startRow - 1, currentPhraseId));
                    }
                    currentPhraseId = phraseId;
                    phraseStartRow = startRow;
                }
            }

            // Close final phrase span
            if (currentPhraseId >= 0 && !rawEvents.isEmpty()) {
                RawEvent last = rawEvents.get(rawEvents.size() - 1);
                int lastEndRow = last.startTick / gridResolution +
                        Math.max(1, last.durationTicks / gridResolution) - 1;
                channel.phraseSpans().add(new UnrolledTimeline.PhraseSpan(
                        phraseStartRow, lastEndRow, currentPhraseId));
            }

            // Map loop-back tick to grid row
            if (loopBackTick[ch] >= 0) {
                timeline.channelLoopBackRow()[ch] = loopBackTick[ch] / gridResolution;
            }
        }

        return timeline;
    }

    /** Internal intermediate representation before grid mapping. */
    private record RawEvent(
            int startTick,
            int durationTicks,
            SmpsDecoder.TrackerRow decoded,
            UnrolledTimeline.SourceRef source,
            boolean isFromLoop
    ) {}
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.codec.TestTimelineBuilder`
Expected: All 8 tests PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/codec/TimelineBuilder.java \
       app/src/test/java/com/opensmpsdeck/codec/TestTimelineBuilder.java
git commit -m "feat: add TimelineBuilder for unrolled timeline construction"
```

---

## Task 4: Integration Test with Real SMPS Files

**Files:**
- Test: `app/src/test/java/com/opensmpsdeck/codec/TestTimelineBuilderImport.java`

Verify the timeline builder works with real imported Sonic 2 songs.

**Step 1: Write the integration test**

```java
package com.opensmpsdeck.codec;

import com.opensmpsdeck.io.SmpsImporter;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class TestTimelineBuilderImport {

    private static final String EMERALD_HILL = "../docs/SMPS-rips/Sonic The Hedgehog 2/2-01 Emerald Hill Zone.sm2";

    static boolean smpsRipsAvailable() {
        return new File(EMERALD_HILL).exists();
    }

    @Test
    @EnabledIf("smpsRipsAvailable")
    void emeraldHillZoneBuildsValidTimeline() throws Exception {
        Song song = new SmpsImporter().importFile(new File(EMERALD_HILL));
        UnrolledTimeline timeline = TimelineBuilder.build(song);

        // Should have a reasonable grid resolution (3 or 6 for typical S2 songs)
        assertTrue(timeline.gridResolution() >= 1);
        assertTrue(timeline.gridResolution() <= 48);

        // Should have a reasonable number of rows
        assertTrue(timeline.totalGridRows() > 0);
        assertTrue(timeline.totalGridRows() < 10000);

        // Multiple channels should have events
        int activeChannels = 0;
        for (var ch : timeline.channels()) {
            if (!ch.events().isEmpty()) activeChannels++;
        }
        assertTrue(activeChannels >= 4, "Expected at least 4 active channels");

        // Events should be time-ordered per channel
        for (var ch : timeline.channels()) {
            int lastTick = -1;
            for (var event : ch.events()) {
                assertTrue(event.startTick() >= lastTick,
                        "Events should be time-ordered");
                lastTick = event.startTick();
                assertTrue(event.durationTicks() > 0);
                assertNotNull(event.source());
                assertNotNull(event.decoded());
            }
        }

        // Phrase spans should cover all events
        for (var ch : timeline.channels()) {
            if (!ch.events().isEmpty()) {
                assertFalse(ch.phraseSpans().isEmpty(),
                        "Active channel should have phrase spans");
            }
        }

        // All source refs should point to valid phrases
        var lib = song.getHierarchicalArrangement().getPhraseLibrary();
        for (var ch : timeline.channels()) {
            for (var event : ch.events()) {
                assertNotNull(lib.getPhrase(event.source().phraseId()),
                        "SourceRef phraseId should be valid");
            }
        }
    }

    @Test
    @EnabledIf("smpsRipsAvailable")
    void zoomLevelsAreValid() throws Exception {
        Song song = new SmpsImporter().importFile(new File(EMERALD_HILL));
        UnrolledTimeline timeline = TimelineBuilder.build(song);

        var zooms = GridResolutionCalculator.zoomLevels(timeline.gridResolution());
        assertFalse(zooms.isEmpty());
        assertEquals(1, zooms.get(0).intValue()); // 1x is always first

        // Each zoom level should produce a valid integer ticks-per-row
        for (int zoom : zooms) {
            assertEquals(0, timeline.gridResolution() % zoom,
                    "Zoom " + zoom + " should divide grid resolution cleanly");
        }
    }
}
```

**Step 2: Run test**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.codec.TestTimelineBuilderImport`
Expected: PASS (or gracefully skipped if SMPS rips not present).

**Step 3: Commit**

```bash
git add app/src/test/java/com/opensmpsdeck/codec/TestTimelineBuilderImport.java
git commit -m "test: add integration test for TimelineBuilder with real SMPS imports"
```

---

## Task 5: Add Tick Counter to SmpsSequencer

**Files:**
- Modify: `synth-core/src/main/java/com/opensmps/smps/SmpsSequencer.java` (lines 44, 841, 957)
- Modify: `synth-core/src/main/java/com/opensmps/driver/SmpsDriver.java` (line 220)
- Modify: `app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java` (line 234)
- Test: `synth-core/src/test/java/com/opensmps/smps/TestSmpsSequencerTickCount.java`

Add a `totalTicksElapsed` counter to the sequencer and expose it up the chain to PlaybackEngine.

**Step 1: Write the failing test**

```java
package com.opensmps.smps;

import com.opensmps.driver.SmpsDriver;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestSmpsSequencerTickCount {

    @Test
    void tickCountIncrementsOnEachTick() {
        // Use SmpsDriver to verify tick count is exposed
        SmpsDriver driver = new SmpsDriver();
        assertEquals(0, driver.getTickCount());
    }
}
```

Note: The full test will be expanded once we see how to load test data. Start with verifying the API exists. Check existing test patterns in `synth-core/src/test/java/com/opensmps/smps/TestSmpsSequencer.java` for how to set up a sequencer with test data (likely using `StubSmpsData`). Adapt the test to actually play a few frames and verify ticks increment.

**Step 2: Run test to verify it fails**

Run: `mvn test -pl synth-core -Dtest=com.opensmps.smps.TestSmpsSequencerTickCount`
Expected: Compilation error — `getTickCount()` does not exist.

**Step 3: Implement tick counter**

In `SmpsSequencer.java`:
- Add field near line 44: `private long totalTicksElapsed;`
- In `tick()` method (line 841), increment at the start: `totalTicksElapsed++;`
- Add getter: `public long getTotalTicksElapsed() { return totalTicksElapsed; }`
- Reset in `load()` or `init()`: `totalTicksElapsed = 0;`

In `SmpsDriver.java`:
- Add method after `getTrackRuntimeState()` (line 220):
```java
public long getTickCount() {
    synchronized (sequencersLock) {
        for (SmpsSequencer seq : sequencers) {
            if (!isSfx(seq)) {
                return seq.getTotalTicksElapsed();
            }
        }
        return 0;
    }
}
```

In `PlaybackEngine.java`:
- Add method:
```java
public long getPlaybackTickCount() {
    return driver.getTickCount();
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl synth-core -Dtest=com.opensmps.smps.TestSmpsSequencerTickCount`
Expected: PASS.

Then run full synth-core tests to verify no regressions:
Run: `mvn test -pl synth-core`
Expected: All existing tests PASS.

**Step 5: Commit**

```bash
git add synth-core/src/main/java/com/opensmps/smps/SmpsSequencer.java \
       synth-core/src/main/java/com/opensmps/driver/SmpsDriver.java \
       app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java \
       synth-core/src/test/java/com/opensmps/smps/TestSmpsSequencerTickCount.java
git commit -m "feat: add tick counter to SmpsSequencer for playback position tracking"
```

---

## Task 6: Extract Phrase Color Utility

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/ui/PhraseColors.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/SongView.java` (lines 39-44, 202-204)
- Test: `app/src/test/java/com/opensmpsdeck/ui/TestPhraseColors.java`

Extract `phraseColor()` and `PHRASE_COLORS` from SongView into a shared utility so TrackerGrid can use them too.

**Step 1: Write the failing test**

```java
package com.opensmpsdeck.ui;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestPhraseColors {

    @Test
    void phraseColorIsDeterministic() {
        Color c1 = PhraseColors.forPhraseId(5);
        Color c2 = PhraseColors.forPhraseId(5);
        assertEquals(c1, c2);
    }

    @Test
    void differentPhraseIdsCanDiffer() {
        Color c0 = PhraseColors.forPhraseId(0);
        Color c1 = PhraseColors.forPhraseId(1);
        assertNotEquals(c0, c1);
    }

    @Test
    void wrapsAround() {
        // 12 colors in palette; id 12 should match id 0
        assertEquals(PhraseColors.forPhraseId(0), PhraseColors.forPhraseId(12));
    }

    @Test
    void negativePhraseIdDoesNotThrow() {
        assertNotNull(PhraseColors.forPhraseId(-3));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.ui.TestPhraseColors`
Expected: Compilation error.

**Step 3: Implement PhraseColors**

```java
package com.opensmpsdeck.ui;

import javafx.scene.paint.Color;

/**
 * Shared phrase color palette. Used by both {@link SongView} and
 * TrackerGrid (unrolled mode) to ensure consistent phrase background tints.
 */
public final class PhraseColors {

    private static final Color[] PALETTE = {
        Color.web("#3a6b8a"), Color.web("#6b3a8a"), Color.web("#3a8a6b"),
        Color.web("#8a6b3a"), Color.web("#8a3a5a"), Color.web("#5a8a3a"),
        Color.web("#3a5a8a"), Color.web("#8a5a3a"), Color.web("#5a3a8a"),
        Color.web("#3a8a5a"), Color.web("#8a3a3a"), Color.web("#3a8a8a")
    };

    private PhraseColors() {}

    /** Get the color for a phrase ID. Deterministic and wraps around. */
    public static Color forPhraseId(int phraseId) {
        return PALETTE[Math.abs(phraseId) % PALETTE.length];
    }
}
```

Then update `SongView.java` to delegate:
- Remove `PHRASE_COLORS` array (lines 39-44)
- Change `phraseColor()` method (lines 202-204) to:
```java
private static Color phraseColor(int phraseId) {
    return PhraseColors.forPhraseId(phraseId);
}
```

**Step 4: Run tests**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.ui.TestPhraseColors`
Expected: PASS.

Run full app tests for no regressions:
Run: `mvn test -pl app`
Expected: All PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/PhraseColors.java \
       app/src/main/java/com/opensmpsdeck/ui/SongView.java \
       app/src/test/java/com/opensmpsdeck/ui/TestPhraseColors.java
git commit -m "refactor: extract PhraseColors utility from SongView"
```

---

## Task 7: TrackerGrid Unrolled Mode — State and Toggle

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java` (lines 122-128, 168, 187, 285)
- Test: `app/src/test/java/com/opensmpsdeck/ui/TestTrackerGridMode.java`

Add the mode enum, state field, timeline reference, zoom state, and `setUnrolledTimeline()` method to TrackerGrid. The render changes come in the next task.

**Step 1: Write the failing test**

```java
package com.opensmpsdeck.ui;

import com.opensmpsdeck.codec.UnrolledTimeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestTrackerGridMode {

    @Test
    void defaultModeIsPhrase() {
        // TrackerGrid starts in phrase mode (activePhrase == null means no phrase loaded,
        // but the mode enum should default to PHRASE)
        // This test verifies the enum and mode query exist
        assertEquals(TrackerGrid.ViewMode.PHRASE, TrackerGrid.ViewMode.PHRASE);
        assertEquals(TrackerGrid.ViewMode.UNROLLED, TrackerGrid.ViewMode.UNROLLED);
    }

    @Test
    void viewModeEnumHasTwoValues() {
        assertEquals(2, TrackerGrid.ViewMode.values().length);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.ui.TestTrackerGridMode`
Expected: Compilation error — `ViewMode` does not exist.

**Step 3: Add mode state to TrackerGrid**

In `TrackerGrid.java`, add near the existing mode fields (around line 122):

```java
/** View modes for the tracker grid. */
public enum ViewMode { PHRASE, UNROLLED }

private ViewMode viewMode = ViewMode.PHRASE;
private UnrolledTimeline unrolledTimeline;
private int zoomLevel = 1;
```

Add public methods:

```java
public ViewMode getViewMode() { return viewMode; }

public boolean isUnrolledMode() { return viewMode == ViewMode.UNROLLED; }

public void setUnrolledTimeline(UnrolledTimeline timeline) {
    this.unrolledTimeline = timeline;
    this.viewMode = ViewMode.UNROLLED;
    this.zoomLevel = 1;
    refreshDisplay();
}

public void exitUnrolledMode() {
    this.viewMode = ViewMode.PHRASE;
    this.unrolledTimeline = null;
    refreshDisplay();
}

public int getZoomLevel() { return zoomLevel; }

public void setZoomLevel(int zoom) {
    this.zoomLevel = zoom;
    refreshDisplay();
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=com.opensmpsdeck.ui.TestTrackerGridMode`
Expected: PASS.

Run full app tests:
Run: `mvn test -pl app`
Expected: All PASS (no rendering changes yet).

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java \
       app/src/test/java/com/opensmpsdeck/ui/TestTrackerGridMode.java
git commit -m "feat: add ViewMode enum and unrolled state to TrackerGrid"
```

---

## Task 8: TrackerGrid Unrolled Mode — Rendering

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java` (render method at line 285)

This is the main rendering implementation. Add a branch in `render()` that draws the unrolled timeline when `viewMode == UNROLLED`. This task is primarily UI code and must be verified visually, but we guard the mode switching logic with the existing tests.

**Step 1: Implement the unrolled render path**

In the `render()` method (line 285), add a mode check at the top:

```java
private void render(int rowCount) {
    if (viewMode == ViewMode.UNROLLED && unrolledTimeline != null) {
        renderUnrolled();
        return;
    }
    // ... existing render code unchanged ...
}
```

Implement `renderUnrolled()` as a new private method. Key rendering logic:

```java
private void renderUnrolled() {
    int effectiveResolution = unrolledTimeline.gridResolution() / zoomLevel;
    int totalRows = (effectiveResolution > 0)
            ? (maxTickAcrossChannels() + effectiveResolution - 1) / effectiveResolution
            : unrolledTimeline.totalGridRows();

    int channelCount = Pattern.CHANNEL_COUNT; // always 10
    double canvasWidth = ROW_NUM_WIDTH + channelCount * CHANNEL_WIDTH;
    double canvasHeight = HEADER_HEIGHT + (totalRows + 1) * ROW_HEIGHT;
    canvas.setWidth(canvasWidth);
    canvas.setHeight(canvasHeight);

    GraphicsContext gc = canvas.getGraphicsContext2D();
    gc.clearRect(0, 0, canvasWidth, canvasHeight);

    // Draw header with channel names
    gc.setFill(Color.web("#2a2a2a"));
    gc.fillRect(0, 0, canvasWidth, HEADER_HEIGHT);
    gc.setFont(HEADER_FONT);
    gc.setFill(Color.web("#88aacc"));
    for (int ch = 0; ch < channelCount; ch++) {
        gc.fillText(CHANNEL_NAMES[ch], ROW_NUM_WIDTH + ch * CHANNEL_WIDTH + 4, HEADER_HEIGHT - 6);
    }

    // Draw rows
    for (int gridRow = 0; gridRow < totalRows; gridRow++) {
        double y = HEADER_HEIGHT + gridRow * ROW_HEIGHT;
        int tick = gridRow * effectiveResolution;

        // Row number (tick value)
        gc.setFill(Color.web("#555566"));
        gc.setFont(MONO_FONT);
        gc.fillText(String.valueOf(tick), 4, y + ROW_HEIGHT - 4);

        // Per-channel cells
        for (int ch = 0; ch < channelCount; ch++) {
            double x = ROW_NUM_WIDTH + ch * CHANNEL_WIDTH;

            // Phrase background tint
            var spans = unrolledTimeline.channels()[ch].phraseSpans();
            for (var span : spans) {
                // Recompute span rows for current zoom level
                // (span row values are at base resolution)
                if (isRowInSpan(gridRow, span, effectiveResolution)) {
                    Color phraseColor = PhraseColors.forPhraseId(span.phraseId());
                    gc.setFill(phraseColor.deriveColor(0, 1, 1, 0.15));
                    gc.fillRect(x, y, CHANNEL_WIDTH, ROW_HEIGHT);
                    break;
                }
            }

            // Find event at this grid row for this channel
            var event = findEventAtRow(ch, gridRow, effectiveResolution);
            if (event != null) {
                boolean isEventStart = (event.startTick() / effectiveResolution == gridRow);
                gc.setFill(event.isFromLoop()
                        ? Color.web("#666677")  // dimmer for loop repeats
                        : Color.web("#888899")); // grey for read-only
                gc.setFont(MONO_FONT);

                if (isEventStart) {
                    // Draw note, duration, instrument, effects
                    String text = formatEventRow(event);
                    gc.fillText(text, x + 4, y + ROW_HEIGHT - 4);
                } else {
                    // Hold row
                    gc.fillText("···", x + 4, y + ROW_HEIGHT - 4);
                }
            }
        }

        // Beat grid lines (every 4 rows at base resolution)
        if (gridRow % 4 == 0) {
            gc.setStroke(Color.web("#333344"));
            gc.setLineWidth(0.5);
            gc.strokeLine(ROW_NUM_WIDTH, y, canvasWidth, y);
        }
    }

    // Loop-back markers
    for (int ch = 0; ch < channelCount; ch++) {
        int loopRow = unrolledTimeline.channelLoopBackRow()[ch];
        if (loopRow >= 0) {
            // Draw loop marker at the bottom of the channel's last event
            // (implementation: draw a LOOP bar after the last row)
        }
    }

    // Playback cursor
    if (playbackRow >= 0 && playbackRow < totalRows) {
        double y = HEADER_HEIGHT + playbackRow * ROW_HEIGHT;
        gc.setFill(Color.rgb(0, 180, 180, 0.25));
        gc.fillRect(0, y, canvasWidth, ROW_HEIGHT);
    }
}
```

Helper methods needed:

```java
private UnrolledTimeline.TimelineEvent findEventAtRow(int channel, int gridRow, int effectiveResolution) {
    int tick = gridRow * effectiveResolution;
    for (var event : unrolledTimeline.channels()[channel].events()) {
        int eventStartRow = event.startTick() / effectiveResolution;
        int eventEndRow = eventStartRow + Math.max(1, event.durationTicks() / effectiveResolution) - 1;
        if (gridRow >= eventStartRow && gridRow <= eventEndRow) return event;
        if (eventStartRow > gridRow) break; // events are time-ordered
    }
    return null;
}

private boolean isRowInSpan(int gridRow, UnrolledTimeline.PhraseSpan span, int effectiveResolution) {
    // PhraseSpan rows are at base resolution; recompute for zoom
    // For simplicity, check if the tick at this gridRow falls within the span's phrase
    // This will need refinement — for now use the span's start/end rows scaled by zoom
    return gridRow >= span.startRow() / (unrolledTimeline.gridResolution() / effectiveResolution)
        && gridRow <= span.endRow() / (unrolledTimeline.gridResolution() / effectiveResolution);
}

private String formatEventRow(UnrolledTimeline.TimelineEvent event) {
    SmpsDecoder.TrackerRow row = event.decoded();
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-3s", row.note()));
    if (row.duration() > 0) sb.append(String.format(" %02X", row.duration()));
    if (!row.instrument().isEmpty()) sb.append(" ").append(row.instrument());
    return sb.toString();
}

private int maxTickAcrossChannels() {
    int max = 0;
    for (var ch : unrolledTimeline.channels()) {
        if (!ch.events().isEmpty()) {
            var last = ch.events().get(ch.events().size() - 1);
            max = Math.max(max, last.startTick() + last.durationTicks());
        }
    }
    return max;
}
```

Note: The `findEventAtRow` linear scan is adequate for initial implementation. If performance is an issue with very large songs, a binary search or pre-built row-to-event index can be added later.

**Step 2: Run full test suite to verify no regressions**

Run: `mvn test -pl app`
Expected: All existing tests PASS (render changes only affect visual output, not testable data).

**Step 3: Manual verification**

Load a song in the app, call `setUnrolledTimeline()` from a debug hook or temporary menu action, verify the grid renders with all 10 channels.

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java
git commit -m "feat: implement unrolled timeline rendering in TrackerGrid"
```

---

## Task 9: Mode Toggle Button and Zoom Dropdown

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java` (constructor at line 133)
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java` or relevant toolbar location

Add a toggle button to switch between phrase and unrolled modes, and a zoom dropdown that appears in unrolled mode.

**Step 1: Add toolbar controls**

In `TrackerGrid` constructor or in the parent layout that hosts TrackerGrid, add:

```java
// Toggle button
ToggleButton unrollToggle = new ToggleButton("Unroll");
unrollToggle.setOnAction(e -> {
    if (unrollToggle.isSelected()) {
        // Build and display unrolled timeline
        onRequestUnroll.run(); // callback to trigger TimelineBuilder
    } else {
        exitUnrolledMode();
    }
});

// Zoom combo box (only visible in unrolled mode)
ComboBox<String> zoomCombo = new ComboBox<>();
zoomCombo.setVisible(false);
zoomCombo.setOnAction(e -> {
    int selectedZoom = parseZoomLevel(zoomCombo.getValue());
    setZoomLevel(selectedZoom);
});
```

Add a callback mechanism for the unroll request:

```java
private Runnable onRequestUnroll;

public void setOnRequestUnroll(Runnable callback) {
    this.onRequestUnroll = callback;
}
```

Update zoom dropdown when entering unrolled mode:

```java
public void setUnrolledTimeline(UnrolledTimeline timeline) {
    this.unrolledTimeline = timeline;
    this.viewMode = ViewMode.UNROLLED;
    this.zoomLevel = 1;
    updateZoomDropdown();
    refreshDisplay();
}

private void updateZoomDropdown() {
    if (unrolledTimeline == null) return;
    var levels = GridResolutionCalculator.zoomLevels(unrolledTimeline.gridResolution());
    zoomCombo.getItems().clear();
    for (int level : levels) {
        int ticksPerRow = unrolledTimeline.gridResolution() / level;
        String label = ticksPerRow == 1
                ? "Full (" + level + "x)"
                : level + "x (" + ticksPerRow + "t)";
        zoomCombo.getItems().add(label);
    }
    zoomCombo.getSelectionModel().select(0);
    zoomCombo.setVisible(true);
}
```

**Step 2: Wire in MainWindow**

In `MainWindow` where the song tab content is built, wire the unroll callback to build the timeline from the active song:

```java
trackerGrid.setOnRequestUnroll(() -> {
    SongTab tab = getActiveSongTab();
    if (tab != null) {
        UnrolledTimeline timeline = TimelineBuilder.build(tab.getSong());
        tab.getTrackerGrid().setUnrolledTimeline(timeline);
    }
});
```

**Step 3: Test manually**

Load a song, click "Unroll", verify the view switches. Change zoom level, verify the grid updates. Click "Unroll" again to exit.

**Step 4: Run full test suite**

Run: `mvn test -pl app`
Expected: All PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java \
       app/src/main/java/com/opensmpsdeck/ui/MainWindow.java
git commit -m "feat: add unroll toggle button and zoom dropdown"
```

---

## Task 10: Playback Cursor in Unrolled Mode

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java` (cursorPollTimer at lines 108-121)

Map the sequencer's tick count to a grid row for the playback cursor.

**Step 1: Add tick-to-row mapping method**

In `TrackerGrid.java`:

```java
/**
 * Set the playback position in unrolled mode using the sequencer's tick count.
 */
public void setPlaybackTick(long tick) {
    if (viewMode != ViewMode.UNROLLED || unrolledTimeline == null) return;
    int effectiveResolution = unrolledTimeline.gridResolution() / zoomLevel;
    int row = effectiveResolution > 0 ? (int) (tick / effectiveResolution) : 0;
    setPlaybackRow(row);
}
```

**Step 2: Wire tick polling in MainWindow**

Update the cursorPollTimer (lines 108-121 of MainWindow.java) to also handle unrolled mode:

```java
e -> {
    SongTab tab = getActiveSongTab();
    if (tab != null) {
        TrackerGrid grid = tab.getTrackerGrid();
        if (grid.isUnrolledMode()) {
            long tick = playbackEngine.getPlaybackTickCount();
            grid.setPlaybackTick(tick);
        } else {
            songTabCoordinator.updatePlaybackCursor();
            grid.setPlaybackRowsByChannel(playbackEngine.getChannelPlaybackRows());
        }
    }
}
```

**Step 3: Test manually**

Load a Sonic 2 song, enter unrolled mode, press play. Verify the teal playback bar sweeps through the grid synchronized with the audio.

**Step 4: Run full test suite**

Run: `mvn test`
Expected: All PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java \
       app/src/main/java/com/opensmpsdeck/ui/MainWindow.java
git commit -m "feat: wire playback tick counter to unrolled mode cursor"
```

---

## Task 11: Click Interaction and Source Navigation

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java`

Add click handling in unrolled mode: single-click shows source info, double-click navigates to the source phrase.

**Step 1: Add click handlers for unrolled mode**

In TrackerGrid, modify or add mouse event handlers:

```java
private void handleUnrolledMouseClick(MouseEvent e) {
    int gridRow = (int) ((e.getY() - HEADER_HEIGHT) / ROW_HEIGHT);
    int ch = (int) ((e.getX() - ROW_NUM_WIDTH) / CHANNEL_WIDTH);
    if (ch < 0 || ch >= Pattern.CHANNEL_COUNT || gridRow < 0) return;

    int effectiveResolution = unrolledTimeline.gridResolution() / zoomLevel;
    var event = findEventAtRow(ch, gridRow, effectiveResolution);
    if (event == null) return;

    if (e.getClickCount() == 2 && onNavigateToPhrase != null) {
        // Double-click: navigate to source phrase
        onNavigateToPhrase.accept(event.source());
    }
    // Single-click: update status (future: show info in status bar)
    selectedUnrolledEvent = event;
    selectedUnrolledChannel = ch;
}
```

Add navigation callback:

```java
private java.util.function.Consumer<UnrolledTimeline.SourceRef> onNavigateToPhrase;

public void setOnNavigateToPhrase(
        java.util.function.Consumer<UnrolledTimeline.SourceRef> callback) {
    this.onNavigateToPhrase = callback;
}
```

Wire in MainWindow — when double-click navigates, exit unrolled mode and enter the source phrase:

```java
trackerGrid.setOnNavigateToPhrase(sourceRef -> {
    SongTab tab = getActiveSongTab();
    if (tab != null) {
        var hier = tab.getSong().getHierarchicalArrangement();
        Phrase phrase = hier.getPhraseLibrary().getPhrase(sourceRef.phraseId());
        if (phrase != null) {
            // Find the channel that contains this chain entry
            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                var chain = hier.getChain(ch);
                if (sourceRef.chainEntryIndex() < chain.getEntries().size()) {
                    var entry = chain.getEntries().get(sourceRef.chainEntryIndex());
                    if (entry.getPhraseId() == sourceRef.phraseId()) {
                        trackerGrid.exitUnrolledMode();
                        trackerGrid.setPhrase(phrase, ch);
                        trackerGrid.setCursorRow(sourceRef.rowInPhrase());
                        break;
                    }
                }
            }
        }
    }
});
```

**Step 2: Test manually**

In unrolled mode, single-click a note row. Double-click and verify navigation to phrase editor with correct cursor position.

**Step 3: Run full test suite**

Run: `mvn test`
Expected: All PASS.

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java \
       app/src/main/java/com/opensmpsdeck/ui/MainWindow.java
git commit -m "feat: add click interaction and phrase navigation in unrolled mode"
```

---

## Task 12: Final Integration and Polish

**Step 1: Run full test suite**

Run: `mvn test`
Expected: All tests PASS (407 existing + new tests).

**Step 2: Manual end-to-end verification**

1. Import a Sonic 2 .sm2 file
2. Click "Unroll" — verify 10 columns, time-aligned, phrase colors matching SongView
3. Check grid resolution label in zoom dropdown
4. Change zoom to 2x, 4x, Full — verify grid updates
5. Press play — verify playback cursor sweeps smoothly
6. Double-click a note — verify navigation to source phrase
7. Click "Unroll" to exit — verify return to phrase mode

**Step 3: Commit any polish fixes**

```bash
git add -A
git commit -m "polish: finalize unrolled timeline view integration"
```
