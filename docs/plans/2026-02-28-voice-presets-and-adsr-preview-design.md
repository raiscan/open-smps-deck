# Voice Presets & ADSR Envelope Preview Design

**Goal:** Add `.osmpsvoice` shareable FM voice preset files and visual ADSR envelope preview curves in the FM voice editor.

**Scope:** Two self-contained features with low architectural risk — no changes to the playback engine, sequencer, or compilation pipeline.

---

## Feature 1: `.osmpsvoice` Shareable Preset Files

### Format

JSON, consistent with `.ovm` bank files. A single-voice file containing one FM voice:

```json
{
  "version": 1,
  "name": "Bright Piano",
  "data": "3A 07 1F 00 7F 00 1F 00 ..."
}
```

- `version` — integer, currently 1
- `name` — voice name string
- `data` — 25-byte SMPS voice as space-separated uppercase hex (same encoding as `.ovm`)

No PSG envelopes — this is FM-only by design. Extension: `.osmpsvoice`.

### I/O Class

`OsmpsVoiceFile` — static utility class following the same pattern as `VoiceBankFile`.

| Method | Signature | Description |
|--------|-----------|-------------|
| `save` | `save(FmVoice voice, File file) throws IOException` | Writes voice to `.osmpsvoice` JSON |
| `load` | `load(File file) -> FmVoice throws IOException` | Reads and validates `.osmpsvoice` JSON |

Validation: version check (reject > 1), require `name` and `data` fields, validate data decodes to exactly 25 bytes.

### UI Integration

Three entry points:

1. **FmVoiceEditor button bar** — Add "Save Preset" and "Load Preset" buttons next to Copy/Paste/Init/Preview. "Save Preset" writes the current editor voice to disk via file chooser. "Load Preset" reads a file and applies all parameters to the editor (like Paste from disk).

2. **InstrumentPanel / Import Voice Bank** — Add `.osmpsvoice` to the extension filter list in `MainWindowFileActions.onImportVoiceBank()`. When a single `.osmpsvoice` file is selected, import it directly into the voice bank (no selection dialog needed since it's a single voice).

3. **InstrumentPanel / Export single voice** — Right-click context menu or dedicated button to export the selected voice as `.osmpsvoice`.

### Files to Create/Modify

| Action | File |
|--------|------|
| Create | `app/src/main/java/com/opensmpsdeck/io/OsmpsVoiceFile.java` |
| Create | `app/src/test/java/com/opensmpsdeck/io/TestOsmpsVoiceFile.java` |
| Modify | `app/src/main/java/com/opensmpsdeck/ui/FmVoiceEditor.java` — add Save/Load Preset buttons |
| Modify | `app/src/main/java/com/opensmpsdeck/ui/MainWindowFileActions.java` — add `.osmpsvoice` to import filter |
| Modify | `app/src/main/java/com/opensmpsdeck/ui/InstrumentPanel.java` — add Export Preset action |

---

## Feature 2: ADSR Envelope Preview Curves

### Placement

A new `Canvas` added to `FmVoiceEditor`'s main layout, inserted between the button bar and the operator scroll pane. Dimensions: full dialog width x ~100px height.

### Display

All 4 operator envelopes overlaid on one canvas, color-coded:

- **Carriers** — cyan (`#55ccff`, matching `CARRIER_COLOR`)
- **Modulators** — gray (`#cccccc`, matching `MODULATOR_COLOR`)
- Each curve drawn at ~50% opacity so overlapping curves remain distinguishable
- X-axis: time (arbitrary display scale, not real-time)
- Y-axis: attenuation level (0 dB at top, -96 dB at bottom)
- Key-off marker: vertical dashed line at ~70% of canvas width

### Envelope Phases

The YM2612 envelope has 4 phases per operator, computed from 5 parameters:

| Phase | Parameter | Behavior |
|-------|-----------|----------|
| Attack | AR (0-31) | Level rises from -96 dB to 0 dB. AR=0 = infinite (never reaches). AR=31 = instant. |
| Decay 1 | D1R (0-31) | Level falls from 0 dB toward D1L target. D1R=0 = sustain at 0 dB. |
| Sustain | D1L (0-15) | Target level for Decay 1. 0 = 0 dB, 1-14 = -3 to -42 dB (3 dB steps), 15 = -93 dB. |
| Decay 2 | D2R (0-31) | Level falls from D1L toward -96 dB. D2R=0 = sustain at D1L forever. |
| Release | RR (0-15) | Level falls from current to -96 dB on key-off. |

Everything left of the key-off marker shows Attack → Decay 1 → Decay 2. Everything right shows Release.

### Architecture

**Pure model class:** `AdsrEnvelopeCalculator` (in `com.opensmpsdeck.model` or `com.opensmpsdeck.audio`) — no JavaFX dependency. Takes AR, D1R, D2R, D1L, RR and produces a list of `(normalizedTime, normalizedLevel)` points suitable for plotting. This class is fully unit-testable.

**Rendering:** `FmVoiceEditor` calls `AdsrEnvelopeCalculator` for each of the 4 operators and draws the resulting polylines on the envelope canvas using `GraphicsContext`.

### Live Updates

Every slider change in `buildSliderRow` triggers `redrawEnvelopePreview()`, which recalculates all 4 operator curves and redraws the canvas. The algorithm combo also triggers redraw (carrier/modulator status affects curve colors). Copy, Paste, Init, and Load Preset also trigger redraw.

### Files to Create/Modify

| Action | File |
|--------|------|
| Create | `app/src/main/java/com/opensmpsdeck/audio/AdsrEnvelopeCalculator.java` |
| Create | `app/src/test/java/com/opensmpsdeck/audio/TestAdsrEnvelopeCalculator.java` |
| Modify | `app/src/main/java/com/opensmpsdeck/ui/FmVoiceEditor.java` — add envelope canvas + redraw logic |

---

## Out of Scope

- Drag-and-drop for `.osmpsvoice` files (deferred)
- Real-time envelope timing based on actual YM2612 clock rates (we use approximate visual scaling)
- PSG envelope preset files (`.osmpsvoice` is FM-only)
- ROM import, detachable tabs (separate design round)
