# Spec Gap Fixes Design

## Summary

Four spec gaps identified by comparing the original design documents against the current implementation. Excludes detachable/dockable tabs (deferred).

### Gaps Addressed

1. **Auto recompile/reload on edit** — spec says edits recompile and continue from current playback position; current UI only marks dirty.
2. **Playback position feedback** — spec says scrolling cursor tracks sequencer state; current tracker cursor is edit-only.
3. **Mute/solo reset on song reload** — spec says mute state resets; `TrackerGrid.setSong()` preserves stale state.
4. **WAV loop count and fade-out controls** — configurable in engine API but not surfaced in UI.

### Spec References

- `2026-02-28-opensmpsdeck-design.md:282-284` — auto-reload on edit
- `2026-02-28-opensmpsdeck-design.md:300-302` — playback position feedback
- `2026-02-28-opensmpsdeck-design.md:409` — scrolling cursor highlight
- `2026-02-28-phase8-polish-design.md:41` — mute reset on song reload
- `2026-02-28-phase8-polish-design.md:80` — configurable WAV loop count

---

## 1. PatternCompiler + Playback Position API

### What Exists

- `PatternCompiler.compileDetailed()` returns `CompilationResult` with `ChannelTimeline` per channel.
- `ChannelTimeline.resolvePosition(absoluteBytePosition)` does binary search to `(orderIndex, rowIndex)`.
- `PlaybackSliceBuilder` creates partial songs for play-from-cursor.

### What's Missing

- `SmpsSequencer` does not expose per-channel byte position.
- `PlaybackEngine` does not store or expose `CompilationResult`.

### Design

**Add position query to SmpsSequencer.** Expose `getTrackPosition(int channelIndex)` returning the current byte offset within the channel's track data. The sequencer already tracks this internally for bytecode interpretation; surface it as a read-only accessor.

**Route through SmpsDriver.** Add `getTrackPosition(int channelIndex)` that delegates to the active sequencer.

**Store CompilationResult in PlaybackEngine.** When `loadSong()` or `playFromPosition()` compiles, retain the `CompilationResult` (currently discarded after extracting the byte array). Expose `getPlaybackPosition()` that:

1. Queries `driver.getTrackPosition(0)` (FM1 as reference channel).
2. Maps through `compilationResult.resolveChannelPosition(0, byteOffset)`.
3. Returns a `PlaybackPosition(orderIndex, rowIndex)` record.

**Handle play-from-cursor offset.** `PlaybackSliceBuilder` creates a synthesized song starting partway through. The timeline metadata needs an offset so `resolvePosition()` maps back to the original song coordinates, not the slice coordinates. Add a `baseOrderIndex` field to `CompilationResult` set by the slice builder.

---

## 2. Live Reload on Edit

### What Exists

- `PlaybackEngine.reload(song)` calls `loadSong()` but does not restart playback.
- `SongTab.setDirty()` fires a callback when dirty state changes.
- `SongTabCoordinator` routes UI events to PlaybackEngine.

### What's Missing

- No wiring from dirty to reload.
- `reload()` loses playback position (stops everything).

### Design

**Enhance `PlaybackEngine.reload()`.** Before stopping, capture current playback position via `getPlaybackPosition()`. After recompiling, restart from that position using `playFromPosition()`. If not currently playing, just recompile without restarting. Signature stays the same: `reload(Song song)`.

**Add `isPlaying()` to PlaybackEngine.** Simple boolean based on audio output state. Needed so the coordinator can decide whether to reload or skip.

**Wire in SongTabCoordinator.** Add `onSongEdited(Song song)`:

- If `playbackEngine.isPlaying()` → call `playbackEngine.reload(song)`.
- Otherwise → no-op (song is already dirty, will recompile on next play).

**Wire in SongTab.** The existing `onDirty` callback path becomes: mark dirty + call `coordinator.onSongEdited(song)`. Every TrackerGrid edit, instrument change, or order list change that fires dirty also triggers conditional reload.

**Mute state preservation across reload.** Before reload, snapshot the current `channelMuted[]` and `soloChannel` from TrackerGrid. After reload (which resets mute state per section 4), reapply the snapshot. This happens inside the coordinator's `onSongEdited()`.

---

## 3. Playback Cursor Sync

### What Exists

- `TrackerGrid.setCursorRow()` / `setCursorChannel()` move the edit cursor.
- `OrderListPanel` can select rows (loads patterns into the grid).
- No polling or callback mechanism from playback to UI.

### What's Missing

- No periodic position query during playback.
- No visual distinction between edit cursor and playback cursor.
- No order list auto-selection during playback.

### Design

**Polling in SongTabCoordinator.** Add a JavaFX `AnimationTimer` (or `Timeline` at ~15 Hz) that, while playing:

1. Calls `playbackEngine.getPlaybackPosition()`.
2. If the order row changed → update `OrderListPanel` selection.
3. If the pattern row changed → update `TrackerGrid` playback cursor row.
4. Stops polling when playback stops.

**Playback cursor vs. edit cursor.** Keep them separate. The edit cursor (`cursorRow`) is where the user edits. The playback cursor is a highlight showing where the song is currently playing. TrackerGrid gets:

- `setPlaybackRow(int row)` / `setPlaybackOrderRow(int orderRow)` — sets the playback highlight.
- `clearPlaybackCursor()` — removes highlight (called on stop).
- Render: playback row gets a distinct background color (subtle green/teal bar), visually separate from the blue selection and cursor underline.

**Auto-scroll.** If the playback cursor moves outside the visible area, scroll to keep it visible. But only if the user has not manually scrolled or moved the edit cursor. Simple heuristic: auto-scroll is active until the user scrolls or moves the edit cursor, then pauses until the next order row change.

**Start/stop wiring.** Coordinator starts the polling timer on play, stops on stop/pause. On stop, calls `clearPlaybackCursor()`.

---

## 4. Spec Gap Fixes

### Mute/Solo Reset on Song Load

In `TrackerGrid.setSong()`, add:

```java
Arrays.fill(channelMuted, false);
soloChannel = -1;
applyMuteState();
```

This fires on tab switch and file open but NOT on live reload (section 2 handles that with snapshot/restore).

### WAV Export Dialog

Replace the current direct-to-export flow in `MainWindowFileActions` with a dialog:

| Control | Type | Default | Range |
|---------|------|---------|-------|
| **Loop Count** | Spinner | `2` | `1`–`99` |
| **Fade Out** | Checkbox | checked | — |
| **Fade Duration** | Spinner (seconds) | `3.0` | `0.1`–`30.0`, step `0.1` |
| **Fade Mode** | ComboBox | Extend | Extend / Inset |

**Fade modes:**

- **Extend** (positive duration): After the final loop completes, continue rendering for N more seconds. Fade runs from full volume to silence over that extension. Total audio = all loops played fully + N seconds fading.

- **Inset** (negative duration): Fade begins N seconds before the final loop ends. The loop still plays to completion, but the last N seconds are faded. Total audio = all loops played fully, last N seconds faded. If N exceeds the final loop's length, clamp to loop length.

**Fade Out unchecked** → disable Fade Duration and Fade Mode, export clean with no fade.

### WavExporter API Changes

- `setFadeEnabled(boolean)` — skip fade entirely when false.
- `setFadeDurationSeconds(double)` — length of the fade in seconds.
- `setFadeExtend(boolean)` — `true` = extend past end, `false` = inset before end.
- Remove the old hardcoded "fade over final loop" logic. Replace with this model.
- Internally: convert seconds to sample count (`duration * 44100`), then either append rendered frames with declining gain (extend) or mark the fade start point within the rendered buffer (inset).

---

## 5. Tests

### What to Test

**Compiler timeline mapping** — extend `TestPatternCompiler`:

- Compile a song with known patterns, verify `ChannelTimeline.resolvePosition()` returns correct `(orderIndex, rowIndex)` for known byte offsets.
- Verify slice compilation with `baseOrderIndex` offset maps back to original song coordinates.

**PlaybackEngine position query** — new or extended test:

- Load a song, advance the driver a known number of frames, query `getPlaybackPosition()`, verify sensible `(orderIndex, rowIndex)`.
- Verify `reload()` while playing preserves approximate position (order row matches).

**Coordinator edit/reload behavior** — test `SongTabCoordinator.onSongEdited()`:

- When playing → verify reload triggered.
- When stopped → verify no reload.
- Verify mute state preserved across reload.

**Mute reset on setSong** — test `TrackerGrid.setSong()`:

- Set mutes, call `setSong()`, verify all mutes cleared.

**WAV export** — extend `TestWavExporter`:

- Verify `setLoopCount(3)` produces longer output than `setLoopCount(1)`.
- Verify fade extend produces longer output than fade inset at same duration.
- Verify `setFadeEnabled(false)` produces clean (non-faded) output.

### What NOT to Test

- Playback cursor rendering (visual, no behavior to assert).
- Auto-scroll heuristics (timing-dependent, fragile).
- AnimationTimer polling mechanics (JavaFX runtime dependency).
- WAV export dialog layout (UI-only).
