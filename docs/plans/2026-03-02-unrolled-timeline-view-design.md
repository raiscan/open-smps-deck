# Unrolled Timeline View — Design

## Problem

In the current hierarchical arrangement mode, TrackerGrid shows one phrase for one channel at a time. Each channel's chain has different phrase lengths, different loop points, and different structures. There is no way to see what is happening across channels simultaneously. The per-channel playback markers jump around independently because each channel's bytecode decodes to different row counts — row 5 in FM1 is not the same musical moment as row 5 in PSG1.

Songs with loops (both counted LOOP repeats and channel JUMP loops) have no contiguous view showing the full timeline with repeats expanded.

## Solution

A read-only toggle mode on TrackerGrid that shows all 10 channels time-aligned in a single scrollable grid. Chains are expanded, counted loops are fully unrolled, and forever-loops terminate with a loop-back marker. The grid resolution is dynamically computed per song using a tolerant "practical GCD" of all note durations, with a zoom dropdown to increase resolution.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| View location | Toggle mode on TrackerGrid | Reuses existing canvas and layout; no new panels |
| Time alignment | Tick-based with adaptive grid resolution | 1-tick-per-row is 92.5% empty; adaptive GCD gives compact readable grids |
| Counted loops (LOOP) | Fully unrolled | They are finite (typically 2-4x); shows the complete musical content |
| Forever loops (JUMP) | Stop with loop-back marker | Infinite unroll is impossible; marker shows where playback restarts |
| Channel scope | All 10 channels in song-level view | Phrase editing stays single-channel as-is |
| Phrase boundaries | Phrase name + background tint matching SongView palette | Visual continuity between SongView blocks and timeline rows |
| Editing | Read-only; double-click navigates to source phrase | Avoids complex edit-propagation; keeps phrase editor as the single edit surface |
| Click behavior | Single-click selects, shows source info in status bar | Double-click to navigate to source phrase for editing |
| Coordination flags | Referenced by SmpsCoordFlags constant names | Hex values vary by SMPS mode |

## Data Model

### UnrolledTimeline

```
UnrolledTimeline
├── int gridResolution            // ticks per row (practical GCD)
├── int totalGridRows             // total rows in the view
├── TimelineChannel[10]
│   └── TimelineEvent[]
│       ├── int startTick         // absolute tick position
│       ├── int durationTicks     // scaled duration (raw * dividingTiming)
│       ├── int startGridRow      // row in the unified grid
│       ├── int spanRows          // how many grid rows this event occupies
│       ├── TrackerRow decoded    // note/duration/instrument/effects
│       ├── SourceRef source      // (phraseId, chainEntryIndex, rowInPhrase)
│       └── boolean isFromLoop    // true if from an unrolled LOOP repeat
├── int[] channelLoopBackRow      // per channel: grid row where JUMP points (-1 if STOP)
└── PhraseSpan[]                  // per channel: (startRow, endRow, phraseId) for bg tinting
```

### SourceRef

```
SourceRef
├── int phraseId
├── int chainEntryIndex
├── int rowInPhrase
```

Enables double-click navigation: stores enough information to jump directly to the source phrase in the phrase editor and position the cursor at the corresponding row.

## Grid Resolution Algorithm

### Practical GCD (tolerant)

```
1. Collect all scaled durations (raw * dividingTiming) across all channels
2. Candidate resolutions = [48, 24, 12, 6, 3, 2, 1]
3. For each candidate (largest first):
     count events where (duration % candidate == 0)
     if count >= 90% of total events → use this candidate
4. Fallback: 1 (true tick resolution)
```

For typical Sonic 2 songs this lands on 6 (covers ~80%) or 3 (covers ~90%).

### Zoom Levels

Zoom multiplies resolution (2x = double the detail = half the ticks per row). Valid zoom levels are dynamically computed per song:

**Valid zooms** = all integers N where `1 <= N <= min(baseResolution, 16)` and `baseResolution % N == 0`. Each produces `baseResolution / N` ticks per row. The highest zoom level (where result = 1) is labelled "Full".

| Base resolution | Available zooms | Ticks per row |
|-----------------|-----------------|---------------|
| 6 | 1x, 2x, 3x, Full(6x) | 6, 3, 2, 1 |
| 12 | 1x, 2x, 3x, 4x, 6x, Full(12x) | 12, 6, 4, 3, 2, 1 |
| 3 | 1x, Full(3x) | 3, 1 |
| 24 | 1x, 2x, 3x, 4x, 6x, 8x, 12x, Full(24x) | 24, 12, 8, 6, 4, 3, 2, 1 |

No fractional ticks, no duplicate options.

### Handling Oddball Durations

Events with durations not divisible by the current grid resolution are placed at the nearest grid row and display their exact duration value. The next event starts at the correct absolute tick position, so alignment stays accurate across channels even with non-grid-aligned durations.

## Timeline Builder

Lives in `app/codec` alongside existing compilers. Input: `Song`. Output: `UnrolledTimeline`.

### Algorithm

```
For each channel 0-9:
  1. Get Chain from HierarchicalArrangement
  2. Walk ChainEntries in order:
     - For each entry, get Phrase from PhraseLibrary
     - Decode phrase bytes with SmpsDecoder.decode()
     - For each decoded row with duration > 0:
         - Compute scaledDuration = row.duration * currentDividingTiming
         - Create TimelineEvent at currentTick with SourceRef
         - currentTick += scaledDuration
     - Handle entry.repeatCount > 1: re-emit the phrase N times (mark isFromLoop)
     - Handle entry.transposeSemitones: record in SourceRef for display
     - Track currentDividingTiming (updated by SET_DIV_TIMING effects in decoded rows)
  3. Record loopBackTick from chain.loopEntryIndex (if >= 0)

After all channels:
  4. Compute gridResolution using the practical GCD algorithm
  5. Compute gridRow for each event: startTick / gridResolution
  6. Compute spanRows for each event: max(1, durationTicks / gridResolution)
  7. totalGridRows = max(all channel last event's startGridRow + spanRows)
  8. Build PhraseSpan list per channel for background tinting
```

This walks the model (chains + phrases) rather than compiled bytecode. Coordination flags that affect timing (SET_DIV_TIMING) are detected during decode and applied. No need to handle CALL/RETURN/JUMP/LOOP bytecodes since those are compilation artifacts; the chain model already has repeatCount and loopEntryIndex.

## TrackerGrid Integration

### Mode Toggle

A button or keyboard shortcut switches TrackerGrid between:
- **Phrase mode** (current): single-channel phrase editing, fully editable
- **Unrolled mode** (new): all 10 channels, read-only, time-aligned

### Rendering (unrolled mode)

The existing `render()` method gets a mode branch. In unrolled mode:

- **10 channel columns**: same layout as old pattern mode header/width
- **Row backgrounds**: phrase-colored tints from `SongView.phraseColor(phraseId)`, matching the left panel
- **Event rows**: grey text (read-only indicator), showing note/duration/instrument/effects
- **Hold rows** (event spans multiple grid rows): subtle `···` marker
- **Phrase boundaries**: phrase name displayed on the first row of each phrase span
- **Loop-back markers**: prominent `LOOP` bar at the bottom of channels with a loop point, with an arrow indicating the target row
- **Unrolled-loop rows** (isFromLoop): slightly dimmer than first-iteration rows to distinguish repeats from originals

### Grid Resolution Dropdown

Small combo box in the toolbar showing available zoom levels (e.g., "1x (6t)", "2x (3t)", "Full (1t)"). Changing it recomputes `startGridRow` and `spanRows` for all events and re-renders. The `UnrolledTimeline` data itself does not change — only the grid mapping is recomputed.

### Playback Cursor

Single horizontal teal bar sweeping through rows based on tick time. All channels are synchronized on the same row. During playback, PlaybackEngine provides tick position; we map `currentTick / effectiveGridResolution` to a grid row. This solves the core problem: the playback cursor makes sense across all channels simultaneously.

### Interaction

- **Single-click**: select a row; status bar shows source info (e.g., "FM1 > Chain entry 3 > Phrase 'intro_melody' row 5")
- **Double-click**: navigate to the source phrase in phrase editor mode, cursor at sourceRef.rowInPhrase
- **Scroll**: normal vertical scrolling; auto-scroll follows playback cursor when playing

### Recomputation

The UnrolledTimeline is recomputed when:
- The song model changes (phrase edits, chain reordering, etc.)
- The user switches to unrolled mode

For typical songs this computation is fast (hundreds of events across 10 channels).

## What This Does Not Change

- **Phrase editor mode**: unchanged, remains the primary editing surface
- **ChainStrip**: unchanged
- **SongView**: unchanged (a future enhancement could wire up its dormant setPlaybackPosition)
- **HierarchyCompiler / HierarchyDecompiler**: untouched
- **Model classes**: untouched (Song, Chain, Phrase, etc.)
- **PlaybackEngine**: minor addition to expose current tick position (currently exposes byte position only)

## Data Analysis (Sonic 2 songs)

Analysis of 31 Sonic 2 SMPS rips informed the grid resolution design:

- **92.5%** of all ticks are sustain/hold (not new events)
- Most common durations: 6 (32%), 12 (22%), 3 (8%), 48 (5%), 24 (5%)
- Durations 3/6/12/24/48 cover ~70% of all events
- GCD of all durations is 1 (due to rare oddball durations like 5, 7, 13)
- Tolerant GCD at 90% threshold typically selects 3 or 6 for Sonic 2 songs
- Longest channel: 7,507 ticks (Credits); typical songs: 500-3,000 ticks
- With grid resolution 6: typical songs produce 80-500 rows (very manageable)
