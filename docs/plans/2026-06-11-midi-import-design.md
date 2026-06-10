# MIDI Import — Design

## Problem

AI music tools (Suno) and DAWs export songs as per-stem MIDI files plus rendered WAV stems. A typical Suno "stems" export contains paired `.mid`/`.wav` files per instrument group (Synth, Bass, Drums, Vocals, FX). The MIDI files are real note transcriptions: format 1, 480 PPQ, one tempo-map track plus one note track each, with a wobbly tempo map (hundreds of small tempo events around a true BPM) and polyphony far beyond Genesis hardware (the Synth stem of a reference song peaks at 8 simultaneous voices).

There is currently no way to get this material into OpenSMPSDeck except retyping it by hand. The conversion problems — polyphony reduction, tempo flattening, frame-grid quantization, GM drum mapping, instrument assignment — are mechanical enough to automate with a preview step for human judgment.

## Solution

A native importer: **File → Import MIDI…** accepts one or more `.mid` files (one per stem), runs voice separation and tempo fitting, and presents a **MidiImportDialog** where the user maps extracted monophonic lines onto the 10 SMPS channels, assigns instruments, and reviews warnings. Confirming builds a new `Song` with a hierarchical arrangement (chains of deduplicated phrases) and opens it as a new tab.

Everything downstream of the `Song` model is existing machinery: `HierarchyCompiler`, `PatternCompiler`, `PlaybackEngine`, export.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| MIDI parsing | `javax.sound.midi` (JDK built-in) | No new dependency; format 0/1 support is sufficient |
| Polyphony handling | Auto voice-separation into ranked monophonic lines + user mapping in dialog | Heuristics get ~80%; the dialog lets the user keep meaningful lines and drop padding |
| Tempo | Flatten tempo map to one BPM, fit SMPS tempo + dividing timing | SMPS has no tempo automation; observed maps wobble ±2% around a true tempo |
| Quantization grid | 16th note default (configurable: 8th/16th/32nd) | Matches typical chiptune material; finer grids explode duration error |
| Drums (GM channel 10) | Built-in GM map: kick/snare/toms → DAC, hats/cymbals → PSG noise; editable in dialog | Matches SMPS idiom; user can re-route or drop per GM pitch |
| Phrase segmentation | Fixed bar length (default 4 bars, configurable) cut at bar boundaries | Predictable, matches LSDJ-style editing; bar from MIDI time signature (default 4/4) |
| Phrase dedup | Exact `byte[]` equality after encoding; consecutive identical chain entries collapse into `repeatCount` | Repeated sections become CALL/LOOP for free at compile time |
| Instruments | Per-channel picker (song voices + `.ovm` banks) pre-filled by GM-program suggestion table | WAV-based voice matching is a separate follow-up design |
| Velocity | Ignored in v1 | SMPS volume flags exist but mapping velocity well needs design of its own; noted as future work |
| Import target | Always a new Song / new tab | Avoids merge semantics with existing arrangements |
| Result mutability | Imported song is a plain Song; no link back to MIDI | One-shot conversion, then normal editing |

## Components

All new code in `app/` — `com.opensmps.io.midi` for the pipeline, one dialog in the UI package. No synth-core changes.

### 1. MidiReader (`io.midi`)

Parses a `.mid` file via `MidiSystem.getSequence(File)` into a neutral model:

```
MidiStem
├── String name                  // from filename / track name meta
├── int ppq
├── List<TempoEvent>             // (tick, microsecondsPerQuarter)
├── TimeSignature                // first 0x58 meta, default 4/4
└── List<MidiNoteTrack>
    ├── boolean isDrumTrack      // any event on MIDI channel 10
    ├── Set<Integer> programs    // program-change values seen
    └── List<NoteEvent>          // record(startTick, durationTicks, pitch, velocity)
```

Note-on velocity 0 is treated as note-off. Dangling note-ons close at end of track. Same-pitch overlapping notes merge. SysEx and unrecognized metas are skipped.

### 2. VoiceSeparator (`io.midi`)

Splits a polyphonic `List<NoteEvent>` into up to `maxLines` (default 4) monophonic lines:

```
1. Group note-ons within EPSILON ticks (PPQ/32) into chords
2. Within a chord, assign pitches descending: highest → lowest-numbered free line
   (line 0 is always the top voice — "skyline" priority)
3. A non-chord note-on goes to the free line whose previous pitch is nearest
4. A note arriving with no free line is dropped and counted
```

Output per line: `SeparatedLine(rank, List<NoteEvent>, noteCount, pitchRange)` plus a global `droppedNoteCount`. Lines are what the dialog maps to channels.

### 3. TempoFitter (`io.midi`)

```
1. Flatten: duration-weighted median BPM across the tempo map
2. Search: for each dividingTiming in 1..32 and each tempo byte value valid for
   the selected SmpsMode, compute effective frames-per-quantum (one 16th note)
   using that mode's tempo semantics (TIMEOUT / OVERFLOW / OVERFLOW2 — same
   arithmetic the sequencer applies, reimplemented here from SmpsSequencerConfig)
3. Score: |effective 16th duration − ideal 16th duration in frames|, preferring
   smaller duration bytes on tie
4. Output: TempoFit(bpm, tempoByte, dividingTiming, durationUnitsPerSixteenth,
   residualErrorPercent)
```

The dialog displays the fit and residual error; the user can override tempo byte and dividing timing manually.

Quantization then snaps every note-on and note-off to the 16th grid (round-half-up). Zero-length notes after quantization become one grid unit. Gaps become rests.

### 4. GmDrumMapper (`io.midi`)

For drum tracks only. Default table (editable per-pitch in the dialog):

| GM pitches | Target | Encoding |
|-----------|--------|----------|
| 35, 36 (kicks) | DAC slot 0 | DAC note `0x81 + 0` |
| 38, 40 (snares) | DAC slot 1 | DAC note `0x81 + 1` |
| 41, 43, 45, 47, 48, 50 (toms) | DAC slot 2 | DAC note `0x81 + 2` |
| 42, 44 (closed/pedal hat) | PSG noise, short | noise note + short duration |
| 46 (open hat) | PSG noise, long | noise note + full duration |
| 49, 51, 55, 57, 59 (cymbals/ride) | PSG noise, long | noise note + full duration |
| anything else | dropped | listed in warnings |

DAC slots reference the song's `dacSamples` list. If the new song has no DAC samples, placeholder `DacSample` entries named after the drum class ("Kick", "Snare", "Tom") are created with empty PCM data and default rate, so the arrangement is correct and the user supplies samples afterward. Simultaneous DAC hits collapse to the highest-priority class (kick > snare > tom).

### 5. MidiPhraseEncoder (`io.midi`)

Converts one quantized monophonic line into phrases:

- MIDI pitch → SMPS note byte via per-channel octave shift (auto default: shift in whole octaves so the line's median pitch centres in 0x81–0xDF; user-adjustable in dialog). Out-of-range notes after shift clamp to the boundary octave and are counted as warnings. PSG channels additionally warn when notes fall below PSG's practical range.
- Durations in SMPS duration units (frames ÷ dividingTiming). Duration > 0x7F splits into note + `SmpsCoordFlags` tie (`E7`) continuations.
- First note of every phrase always carries an explicit duration byte (phrases must be self-contained for dedup); subsequent notes omit repeated durations, matching `SmpsEncoder` output style.
- Rests encode as `0x80` + duration.
- Cut into phrases every N bars at bar boundaries (notes spanning a boundary split with a tie).
- Dedup: a `HashMap<ByteArrayKey, phraseId>` per channel type; identical phrase bytes reuse the existing `Phrase`. Consecutive identical `ChainEntry`s collapse via `repeatCount`.

Phrase names: `<stem>-<line>-<index>` (e.g. `Synth-1-03`).

### 6. MidiSongBuilder (`io.midi`)

Orchestrates: takes the dialog's confirmed mapping and produces a `Song`:

- `smpsMode` from dialog (default S2), tempo + dividingTiming from TempoFit
- One `Chain` per mapped channel; unmapped channels stay empty
- Channel loop: dialog checkbox "loop whole song" (default on) sets `loopEntryIndex = 0` on every non-empty chain; off = chains end with STOP
- Assigned FM voices are copied into `song.voiceBank`; each mapped FM channel's first phrase begins with `EF <voiceIndex>`; PSG channels begin with `F5 <envelopeId>` when an envelope is assigned
- Drum mapping produces the DAC channel chain and the PSG noise chain

### 7. MidiImportDialog (`ui`)

Single dialog, sections top to bottom:

1. **Files** — list of selected `.mid` files with per-file track/note counts
2. **Tempo** — fitted BPM, tempo byte, dividing timing, residual error; editable
3. **Mapping grid** — one row per extracted line: stem, line rank, note count, pitch range, octave shift spinner, target channel combo (FM1–5, PSG1–3, or "—"), instrument cell (opens a picker reusing the `VoiceImportDialog` browsing pattern over song voices + `.ovm` banks, pre-filled from a small built-in GM-program → suggestion table)
4. **Drums** — GM pitch mapping table for drum tracks (target: DAC slot / noise / drop)
5. **Options** — phrase length (bars), quantization grid, SMPS mode, loop-whole-song
6. **Warnings** — dropped notes per line, clamped pitches, unmapped drum pitches, anything not imported. Nothing is dropped silently.

Auto-suggested default mapping: bass-like stems (mono/low) → FM-channel suggestions, the top synth lines → remaining FM then PSG, drums → DAC/noise. The user confirms or rearranges.

## Error Handling

- Unreadable / non-MIDI file: per-file error in the Files section; import proceeds with remaining files
- Format 2 MIDI: rejected with a clear message
- More viable lines than free channels: extra lines default to "—" (not imported), visible in the grid
- Empty result (no lines mapped): OK button disabled

## Testing

Following existing test conventions (JUnit, synthetic data, no audio device):

- **MidiReader**: hand-built tiny MIDI byte arrays / `javax.sound.midi.Sequence` objects — note pairing, velocity-0 note-off, dangling note-on, overlap merge, drum-channel detection
- **VoiceSeparator**: constructed chord/overlap cases — skyline priority, pitch-proximity assignment, drop counting
- **TempoFitter**: known tempo maps → expected BPM; fit search returns combos whose residual is below threshold for each SmpsMode
- **GmDrumMapper**: GM pitch table cases incl. simultaneous-hit priority
- **MidiPhraseEncoder**: quantized line → bytecode, verified by decoding with `SmpsDecoder`; tie-splitting over 0x7F; phrase dedup and repeatCount collapse
- **Round-trip integration**: small checked-in `.mid` fixture → import pipeline → `Song` → `HierarchyCompiler`/`PatternCompiler` compile → `SmpsDecoder` note comparison against the fixture's expected notes
- **Full-stack**: imported song renders headlessly through `PlaybackEngine` (pattern of `TestFullStackRoundTrip`), producing non-silent PCM

## Out of Scope (Future Work)

- Velocity → SMPS volume mapping
- Pitch bend, CC, modulation import
- WAV-based FM voice matching and drum sample extraction — see `2026-06-11-wav-voice-matching-design.md`
- Importing into an existing song / merging arrangements
