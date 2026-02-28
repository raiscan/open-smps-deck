# User Guide Design

## Summary

A comprehensive user guide for OpenSMPSDeck, organized as a tutorial-first multi-chapter markdown document in `docs/user-guide/`. Serves three audiences: retro game music composers, musicians new to trackers, and ROM hackers / Sonic modders.

## Decisions

- **Format:** Markdown files in `docs/user-guide/`
- **Structure:** Multiple chapter files with numbered prefixes
- **Audience:** All skill levels, routed by the index page
- **Depth:** Practical only — explain enough to use the tool, no hardware theory deep-dives
- **Approach:** Tutorial-first (guided walkthrough + reference chapters)

## Chapter Structure

```
docs/user-guide/
  00-index.md               — TOC + reading guide
  01-quick-start.md         — First sound in 2 minutes
  02-tutorial-first-song.md — Full walkthrough: voices → patterns → arrangement → export
  03-interface-overview.md  — All panels and how they connect
  04-fm-voice-editor.md     — Creating and editing FM voices
  05-psg-envelopes.md       — PSG envelope editor
  06-dac-samples.md         — Importing and using DAC samples
  07-tracker-grid.md        — Note entry, editing, navigation, selection
  08-patterns-and-orders.md — Pattern management, order list, looping
  09-playback-and-export.md — Playing, WAV export, SMPS binary export
  10-importing.md           — Importing SMPS files, voice banks, samples
  11-keyboard-reference.md  — Complete shortcut cheat sheet
  12-smps-modes.md          — S1/S2/S3K differences
```

## Chapter Content

### 00-index.md (~50 lines)

Table of contents with links to all chapters and one-line descriptions. Routes readers by experience:

- New to trackers → start at 01
- Experienced tracker user → skip to reference chapters (03+)
- ROM hacker / Sonic modder → chapters 10 and 12

### 01-quick-start.md (~100 lines)

- Prerequisites (Java 21, build/run)
- Launch the app, what you see
- Select a channel, press a key, hear a note
- Change the voice, hear the difference

### 02-tutorial-first-song.md (~400-500 lines)

The centerpiece chapter. Builds a short 8-bar loop step by step.

**What we're building:**

- FM1: Bass line (algorithm 7 single-operator sine)
- FM2: Lead melody (algorithm 4 two-carrier, brighter patch)
- PSG1: Rhythmic hi-hat pattern using a short envelope
- Two patterns arranged in the order list with a loop point

**Step sequence — each step introduces exactly one new concept:**

1. **Create a bass voice** — FM voice editor, algorithm selection, TL/MUL, Init, Preview
2. **Create a lead voice** — second voice, algorithm 4, detune, attack/release
3. **Create a PSG envelope** — PSG section, click-to-draw bars, step count, Preview
4. **Enter bass notes on FM1** — channel selection, note entry keys, octave, rest, duration
5. **Assign the bass voice** — instrument column, hex entry, voice prepend
6. **Enter lead melody on FM2** — Tab to switch channels, melody entry, transposition
7. **Add PSG rhythm** — PSG channel, envelope assignment, short percussive patterns
8. **Create a second pattern** — order list, pattern switching, duplicating and varying
9. **Arrange and loop** — order list rows, loop point, playback behavior
10. **Set tempo and timing** — transport bar spinners, SMPS mode selector
11. **Listen and export** — play/stop/pause, WAV export, SMPS binary export

By step 4 the reader has heard their first note. By step 7 they have a multi-channel pattern.

### 03-interface-overview.md (~200 lines)

- Main window layout: menu bar, transport bar, tracker grid, order list, instrument panel
- How tabs work for multiple songs
- Dirty indicator and save behavior

### 04-fm-voice-editor.md (~250 lines)

- What FM synthesis does (practical: "operators combine to make timbres")
- Algorithm overview — what each of the 8 algorithms sounds like in practice
- Operator parameters with "turn this knob, hear this change" descriptions
- Feedback
- Copy/paste/init/preview buttons
- Tips: start from Init, adjust one parameter at a time

### 05-psg-envelopes.md (~100 lines)

- What PSG channels are (square waves + noise)
- Click-to-draw bar graph editor
- Adding/removing steps
- Preview
- Tips: short envelopes for percussion, long for sustained tones

### 06-dac-samples.md (~100 lines)

- What the DAC channel is (sample playback on FM6)
- Importing WAV or raw PCM files
- Setting playback rate
- DAC note entry on the tracker grid (key mapping)
- Limitations (mono, 8-bit, one sample at a time)

### 07-tracker-grid.md (~300 lines)

- Channel layout (FM1-5, DAC, PSG1-3, Noise)
- Columns: note, duration, instrument, effect
- Note entry via keyboard (piano layout diagram)
- Octave selection (F1-F8, PgUp/PgDn)
- Rest and tie entry
- Instrument column (hex digit entry)
- Row insert/delete
- Selection, copy/paste, cut
- Cross-song paste and instrument resolution
- Transposition
- Channel mute/solo
- Undo/redo

### 08-patterns-and-orders.md (~150 lines)

- What patterns are (reusable blocks of channel data)
- How the order list arranges patterns into a song
- Adding/removing/duplicating order rows
- Setting the loop point
- Per-channel pattern assignment

### 09-playback-and-export.md (~150 lines)

- Play/pause/stop controls
- Play from cursor
- Mute/solo during playback
- WAV export (loop count, fade-out, mute state)
- SMPS binary export
- Voice bank export (.ovm)

### 10-importing.md (~200 lines)

- Importing SMPS binary files (from ROM rips)
- Importing voice banks (.ovm, .rym2612)
- Importing voices from a directory of SMPS files
- Voice selection dialog
- Importing DAC samples from audio files

### 11-keyboard-reference.md (~100 lines)

Printable cheat sheet. Grouped tables, no prose. Categories:

- File, Playback, Navigation, Note Entry (with piano layout ASCII diagram), Octave, Editing, Selection & Clipboard, Transposition, Instrument Entry

**Piano layout diagram:**

```
 2  3     5  6  7
Q  W  E  R  T  Y  U     ← octave +1
C# D#    F# G# A#
 C  D  E  F  G  A  B

 S  D     G  H  J
Z  X  C  V  B  N  M     ← current octave
C# D#    F# G# A#
 C  D  E  F  G  A  B
```

### 12-smps-modes.md (~80 lines)

- What each mode targets (Sonic 1, 2, 3K)
- Practical differences in timing and behavior
- Which mode to pick for which use case

## Writing Style

- **Voice:** Second person, imperative. "Press `F3` to select octave 3."
- **Tense:** Present. "The voice editor opens."
- **Concise:** No filler phrases.
- **Bold** for UI element names: **Transport Bar**, **Order List**
- **Monospace** for keys: `Ctrl+S`, `F3`, `Space`
- **Monospace** for values: `0x80`, `C-4`, `.osmpsd`
- **Tables** for parameter and shortcut references
- **Blockquotes** for tips: > **Tip:** Start from Init and change one parameter at a time.

## Cross-Referencing

- Index links to all chapters with one-line descriptions
- Tutorial links forward to reference chapters for deeper detail
- Reference chapters link to related chapters
- No backward links from reference to tutorial (reference stands alone)

## What We Don't Do

- No screenshots (rot fast, can't be validated by hooks)
- No SMPS bytecode details in main chapters (implementation, not usage)
- No duplicate shortcut listings — `07-tracker-grid.md` explains shortcuts in context, `11-keyboard-reference.md` is the canonical flat list

## Reference Chapter Internal Structure

Each reference chapter follows this layout:

1. **One-sentence summary** — what this feature is
2. **Overview** — how it fits into the workflow (2-3 sentences)
3. **Feature walkthrough** — each capability, ordered by frequency of use
4. **Parameter/shortcut tables** — compact reference where applicable
5. **Tips section** — 2-4 practical tips

## README Integration

Add a single line to the project README linking to the guide:

```markdown
See the [User Guide](docs/user-guide/00-index.md) for documentation.
```

## Maintenance

Documentation accuracy will be maintained via Claude hooks that validate code changes affecting UI, keybindings, or features are reflected in the corresponding guide chapters.
