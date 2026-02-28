# Phase 9: v0.3 Features Design

## Goal

Add preset voice banks (with RYM2612 import), mode-aware playback for S1/S3K, and DAC sample support.

## Architecture

Three independent feature tracks, each shippable and testable in isolation:

- **9A: Preset Voice Banks** — Shareable `.ovm` files + `.rym2612` import
- **9B: Mode-Aware Playback** — Correct S1/S3K sequencer behavior
- **9C: DAC Samples** — Import, assign, and play DAC percussion

Implementation order: A → B → C (simplest to most complex).

---

## 9A: Preset Voice Banks

### Native Format (`.ovm` — OpenSMPS Voice Map)

JSON file containing named FM voices and PSG envelopes, using the same hex-encoded byte array serialization as ProjectFile:

```json
{
  "version": 1,
  "name": "Genesis Bass Collection",
  "voices": [
    { "name": "Slap Bass", "data": "3A071F1A0F1E..." }
  ],
  "psgEnvelopes": [
    { "name": "Quick Decay", "data": "000306..." }
  ]
}
```

### RYM2612 Import (`.rym2612`)

XML parser converting RYM2612 VST float parameters to SMPS 25-byte voice data.

**Parameter conversion rules:**

| RYM2612 Param | SMPS Register | Conversion |
|---------------|---------------|------------|
| Algorithm | algo (byte 0, bits 0-2) | `round(value)` (0-7) |
| Feedback | fb (byte 0, bits 3-5) | `round(value)` (0-7) |
| OPn_AR | AR (5-bit) | `round(value)` (0-31) |
| OPn_D1R | D1R (5-bit) | `round(value)` (0-31) |
| OPn_D2R | D2R (5-bit) | `round(value)` (0-31) |
| OPn_RR | RR (4-bit) | `round(value)` (0-15) |
| OPn_D2L | D1L in SMPS (4-bit) | `round(value)` (0-15) |
| OPn_RS | RS (2-bit) | `round(value)` (0-3) |
| OPn_AM | AM (1-bit) | `round(value)` (0 or 1) |
| OPn_TL | TL (7-bit) | `round(value)` (0-127) |
| OPn_DT | DT (3-bit) | -3..-1 → 5..7, 0..3 → 0..3 |
| OPn_MUL | MUL (4-bit) | Reverse VST scaling: `round(value / 66.6)` clamped 0-15 |
| OPn_SSGEG | SSG-EG (4-bit) | `round(value)` (0-15, 0=off) |

**Operator order:** RYM2612 uses OP1-OP4 numbering. SMPS voice data uses YM2612 register order: Op1(M1), Op2(C1), Op3(M2), Op4(C2). The RYM2612 OP numbering matches this order.

### UI Integration

- File menu: "Import Voice Bank..." (accepts `.ovm`, `.rym2612`)
- File menu: "Export Voice Bank..." (saves `.ovm`)
- InstrumentPanel: "Import from Bank..." button for quick access
- Import flow: parse file → show VoiceImportDialog (reuse existing) → user selects voices → add to song

### Files

| Action | File |
|--------|------|
| Create | `io/VoiceBankFile.java` — .ovm read/write |
| Create | `io/Rym2612Importer.java` — .rym2612 XML parser |
| Modify | `ui/MainWindow.java` — menu items |
| Modify | `ui/InstrumentPanel.java` — import button |
| Modify | `ui/VoiceImportDialog.java` — accept voice bank as source |
| Test | `io/TestVoiceBankFile.java` |
| Test | `io/TestRym2612Importer.java` |

---

## 9B: Mode-Aware Playback

### Problem

`SimpleSmpsData.getBaseNoteOffset()` hardcodes `1` (S2 convention). S1 and S3K use `0`. This causes all notes to play one semitone off when the song mode is S1 or S3K.

### Solution

1. **SimpleSmpsData**: Accept `baseNoteOffset` as constructor parameter.

2. **PlaybackEngine.loadSong()**: Pass mode-specific offset:
   - `SmpsMode.SONIC_1` → `baseNoteOffset = 0`
   - `SmpsMode.SONIC_2` → `baseNoteOffset = 1`
   - `SmpsMode.SONIC_3K` → `baseNoteOffset = 0`

3. **PatternCompiler**: Add mode parameter to `compile()`. When compiling for S1 or S3K, adjust note byte values by +1 to compensate for the lower base note offset at playback time. This ensures the compiled binary sounds correct regardless of mode, without changing the binary format.

4. **TransportBar**: No changes needed — mode selector already calls `song.setSmpsMode()`, and the next `loadSong()` picks up the new mode.

### Files

| Action | File |
|--------|------|
| Modify | `audio/SimpleSmpsData.java` — parameterized baseNoteOffset |
| Modify | `audio/PlaybackEngine.java` — pass mode-specific offset |
| Modify | `codec/PatternCompiler.java` — mode-aware note compensation |
| Test | `audio/TestPlaybackEngine.java` — S1/S3K pitch tests |
| Test | `codec/TestPatternCompiler.java` — mode-aware compilation tests |

---

## 9C: DAC Samples

### Model

```java
public class DacSample {
    private String name;       // e.g. "Kick", "Snare", "Timpani C4"
    private byte[] data;       // Raw unsigned 8-bit PCM
    private int rate;          // Playback rate byte (0-255, lower = faster/higher pitch)
}
```

Added to Song: `List<DacSample> dacSamples` (variable length, practical soft limit ~50, hard limit 94 from SMPS note range 0x81-0xDF).

### DAC Note Mapping

SMPS DAC notes: `0x81 + sampleIndex`. Sample 0 = note 0x81, sample 1 = note 0x82, etc. The sequencer looks up the sample by `noteValue - 0x81`.

### Pitch Variants

To use the same sample at multiple pitches, duplicate the DacSample entry with a different rate value. Example: "Timpani Low" (rate=0x12), "Timpani Mid" (rate=0x0C), "Timpani High" (rate=0x08). This matches how S1's timpani drums work in the original ROMs.

### Sample Import

Accept `.wav` files (8-bit or 16-bit, mono or stereo, any sample rate):
- Stereo → mix to mono
- 16-bit → convert to 8-bit unsigned
- Resample to target rate if needed (or keep original and let DAC rate byte control pitch)

Also accept raw `.pcm`/`.bin` files (assumed unsigned 8-bit, no header).

### Playback Wiring

`PlaybackEngine.loadSong()` builds a `DacData` from Song's dacSamples:
- Create `DacData.DacEntry` per sample with rate and data pointer
- Create sample bank map: `Map<Integer, byte[]>` keyed by bank address (use virtual addresses since these aren't ROM-sourced)
- Pass to driver via `driver.setDacData(dacData)`

The existing DAC playback in `VirtualSynthesizer` handles streaming — it already knows how to play unsigned 8-bit PCM at a given rate via the `djnz` loop timing formula.

### TrackerGrid DAC Channel

Channel index 5 (DAC) currently accepts FM note entry. Change behavior:
- Note keys (Z-M, Q-U) on the DAC channel insert DAC note bytes (0x81+) instead of FM note bytes
- Display shows sample name abbreviation instead of note name (e.g. "KCK", "SNR", "TM1")
- Keyboard mapping: keys map to DAC sample indices sequentially (Z=sample 0, S=sample 1, X=sample 2, etc.)

### UI

- InstrumentPanel: new "DAC Samples" section below PSG envelopes
  - Scrollable ListView of DacSample entries
  - "+" button → FileChooser for WAV/PCM import
  - "Duplicate" button → copies selected sample (for pitch variants)
  - "Edit" button → opens DacSampleEditor dialog
  - "Delete" button
- DacSampleEditor dialog: name field, rate spinner (0-255), file path label

### Project File

Extend ProjectFile save/load to include dacSamples array:
```json
{
  "dacSamples": [
    { "name": "Kick", "rate": 12, "data": "808080..." }
  ]
}
```

### Files

| Action | File |
|--------|------|
| Create | `model/DacSample.java` |
| Create | `io/DacSampleImporter.java` — WAV/PCM → unsigned 8-bit conversion |
| Create | `ui/DacSampleEditor.java` — name + rate dialog |
| Modify | `model/Song.java` — add dacSamples list |
| Modify | `audio/PlaybackEngine.java` — build DacData from Song |
| Modify | `ui/InstrumentPanel.java` — DAC section |
| Modify | `ui/TrackerGrid.java` — DAC channel note entry |
| Modify | `io/ProjectFile.java` — save/load dacSamples |
| Test | `model/TestDacSample.java` |
| Test | `io/TestDacSampleImporter.java` |
