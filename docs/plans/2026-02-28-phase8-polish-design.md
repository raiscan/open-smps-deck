# Phase 8: Polish (Solo/Mute + WAV Export) Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add per-channel solo/mute toggles to the tracker UI and offline WAV export capability.

**Architecture:** Solo/Mute wires TrackerGrid channel header clicks through PlaybackEngine to the existing chip-level mute APIs. WAV Export creates a headless SmpsDriver instance and renders PCM samples in a loop, writing standard RIFF/WAV format.

**Tech Stack:** JavaFX Canvas (click detection on channel headers), javax.sound AudioFormat constants, java.io for WAV file writing.

---

## Task 25: Per-Channel Solo/Mute

### Channel Mapping

| UI Index | Name  | Mute Call |
|----------|-------|-----------|
| 0        | FM1   | `setFmMute(0, ...)` |
| 1        | FM2   | `setFmMute(1, ...)` |
| 2        | FM3   | `setFmMute(2, ...)` |
| 3        | FM4   | `setFmMute(3, ...)` |
| 4        | FM5   | `setFmMute(4, ...)` |
| 5        | DAC   | `setFmMute(5, ...)` |
| 6        | PSG1  | `setPsgMute(0, ...)` |
| 7        | PSG2  | `setPsgMute(1, ...)` |
| 8        | PSG3  | `setPsgMute(2, ...)` |
| 9        | Noise | `setPsgMute(3, ...)` |

### State Model

```java
private final boolean[] channelMuted = new boolean[Pattern.CHANNEL_COUNT]; // 10
private int soloChannel = -1; // -1 = no solo active
```

### Interaction

- **Click** channel header: toggle `channelMuted[ch]`, apply via PlaybackEngine
- **Ctrl+Click** channel header: if `soloChannel == ch`, unsolo (restore previous mute state); else solo `ch` (mute all others)
- Mute state persists across pattern changes but resets on song reload

### Visual Feedback

- Normal: `#88aacc` (current header color)
- Muted: `#555555` (grey) + strikethrough line
- Solo: `#ffcc00` (gold highlight) for soloed channel, `#555555` for all others

### Wiring

TrackerGrid holds a `PlaybackEngine` reference (set via setter from MainWindow). Click handler calls `playbackEngine.setFmMute()`/`setPsgMute()` directly.

---

## Task 26: WAV Export

### Architecture

```
Song → PatternCompiler → byte[] SMPS
     → SmpsSequencer (fresh instance)
     → SmpsDriver (fresh instance, no AudioOutput)
     → driver.read(buffer) loop
     → WavExporter writes RIFF/WAV
```

### WAV Format

- Sample rate: 44100 Hz
- Bit depth: 16-bit signed
- Channels: 2 (stereo)
- Encoding: PCM, little-endian
- Header: Standard 44-byte RIFF/WAV header

### Render Loop

1. Create fresh `SmpsDriver` + compile song + create `SmpsSequencer`
2. Allocate render buffer (1024 frames = 2048 shorts)
3. Loop: `driver.read(buffer)` until `driver.isComplete()` or max duration reached
4. For looping: reload sequencer N times (configurable, default 2)
5. On final loop: apply linear fade-out (multiply samples by declining gain)
6. Write 44-byte WAV header (data size known after render) + PCM data

### Fade-Out

Linear fade over the final loop duration. Gain ramps from 1.0 to 0.0 across the last loop's samples.

### UI Integration

- File menu: "Export WAV..." item after "Export SMPS..."
- FileChooser with `*.wav` filter
- Simple progress dialog (indeterminate, runs on `Task<Void>` background thread)
- Export uses current song's mute state (muted channels stay muted in export)

### Error Handling

- IOException on file write: show error alert
- Song with no patterns: show warning, abort
- Max duration cap: 10 minutes (26,460,000 frames) to prevent runaway exports

---

## Files to Create/Modify

| Action | File |
|--------|------|
| Modify | `TrackerGrid.java` - click handler, mute state, visual feedback |
| Modify | `MainWindow.java` - pass PlaybackEngine to TrackerGrid, add WAV export menu item |
| Create | `WavExporter.java` - offline render + WAV file writing |
| Create | `TestWavExporter.java` - verify WAV header and non-zero audio |
