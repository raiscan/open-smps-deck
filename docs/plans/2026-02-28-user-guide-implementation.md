# User Guide Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Write a 13-chapter user guide covering every feature of OpenSMPSDeck, from quick-start through full reference.

**Architecture:** Multi-file markdown in `docs/user-guide/`. Tutorial-first structure — guided walkthrough chapter followed by standalone reference chapters. All content derived from actual source code; no screenshots.

**Tech Stack:** Markdown (GitHub-flavored), no build tooling.

**Design doc:** `docs/plans/2026-02-28-user-guide-design.md`

**Writing style (apply to ALL chapters):**
- Second person, imperative: "Press `F3` to select octave 3"
- Present tense: "The voice editor opens"
- **Bold** for UI elements: **Transport Bar**, **Order List**
- `Monospace` for keys: `Ctrl+S`, `F3`, `Space`
- `Monospace` for values: `0x80`, `C-4`, `.osmpsd`
- Tables for parameter and shortcut references
- Blockquotes for tips: > **Tip:** text here
- No screenshots, no emojis, no filler phrases

---

### Task 1: Create directory and write 00-index.md

**Files:**
- Create: `docs/user-guide/00-index.md`

**Step 1: Create directory**

```bash
mkdir -p docs/user-guide
```

**Step 2: Write 00-index.md**

Write the table of contents file with:
- Title: "# OpenSMPSDeck User Guide"
- One-paragraph intro: what OpenSMPSDeck is (SMPS-native music tracker for Sega Mega Drive)
- "Where to start" section with three paths:
  - New to trackers → [Quick Start](01-quick-start.md) then [Tutorial](02-tutorial-first-song.md)
  - Experienced tracker user → [Interface Overview](03-interface-overview.md) then reference chapters
  - ROM hacker / Sonic modder → [Importing](10-importing.md) and [SMPS Modes](12-smps-modes.md)
- "Chapters" section: numbered list with relative links and one-line descriptions for all 12 chapters (01-12)

Target: ~50 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/00-index.md
git commit -m "docs: add user guide index page"
```

---

### Task 2: Write 11-keyboard-reference.md

Write this early — other chapters will reference it, and it's a factual list that doesn't depend on prose from other chapters.

**Files:**
- Create: `docs/user-guide/11-keyboard-reference.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java` — all key handlers
- `app/src/main/java/com/opensmps/deck/ui/MainWindow.java` — menu accelerators

**Step 1: Read source files for exact keybindings**

Read TrackerGrid.java fully (the `handleKeyPress` method and note entry logic) and MainWindow.java (menu setup) to extract every keybinding.

**Step 2: Write 11-keyboard-reference.md**

Structure: Title + grouped tables, no prose between them.

Sections (each a `##` header with a table of Shortcut | Action):
- **File** — Ctrl+N, Ctrl+O, Ctrl+S, Ctrl+Shift+S
- **Playback** — Space, Enter, Escape
- **Navigation** — Arrow keys, Tab, Shift+Tab
- **Octave** — F1-F8, Page Up, Page Down, Ctrl+Up, Ctrl+Down
- **Note Entry** — ASCII art piano layout diagram showing:
  - Upper row: Q=C, 2=C#, W=D, 3=D#, E=E, R=F, 5=F#, T=G, 6=G#, Y=A, 7=A#, U=B (octave +1)
  - Lower row: Z=C, S=C#, X=D, D=D#, C=E, V=F, G=F#, B=G, H=G#, N=A, J=A#, M=B (current octave)
  - Period (.) = rest
- **DAC Note Entry** — Same keys but mapped to sample indices 0-18 (Z=0 through U=18)
- **Editing** — Insert, Delete, Backspace
- **Selection & Clipboard** — Shift+Arrow, Ctrl+C, Ctrl+X, Ctrl+V, Ctrl+A
- **Transposition** — `=`/`+` (+1 semitone), `-` (-1 semitone), Shift+= (+12), Shift+- (-12)
- **Instrument Entry** — 0-9, A-F (hex digit entry, two-digit for byte values)
- **Undo/Redo** — Ctrl+Z, Ctrl+Y

Verify every shortcut against the source code. Do not invent shortcuts that don't exist.

Target: ~100 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/11-keyboard-reference.md
git commit -m "docs: add keyboard reference chapter"
```

---

### Task 3: Write 03-interface-overview.md

**Files:**
- Create: `docs/user-guide/03-interface-overview.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/ui/MainWindow.java` — layout structure, menus, tabs
- `app/src/main/java/com/opensmps/deck/ui/SongTab.java` — per-tab layout
- `app/src/main/java/com/opensmps/deck/ui/TransportBar.java` — transport controls

**Step 1: Read source files**

Read MainWindow.java, SongTab.java, and TransportBar.java to understand the exact layout hierarchy and all menu items.

**Step 2: Write 03-interface-overview.md**

Sections:
1. **One-sentence summary** — "The main window organizes your workspace into a menu bar, transport controls, and tabbed song editors."
2. **Overview** — how everything connects (2-3 sentences)
3. **Main Window Layout** — describe top-to-bottom: Menu Bar → Transport Bar → Tab Pane. Each tab contains: Tracker Grid (center), Order List Panel (bottom), Instrument Panel (right).
4. **Menu Bar** — walk through the File menu items: New, Open, Save, Save As, Export SMPS, Export WAV, Import Voices, Import SMPS, Import Voice Bank, Export Voice Bank. List each with its shortcut and one-line description.
5. **Transport Bar** — Play/Stop/Pause buttons, Tempo spinner (1-255), Dividing Timing spinner (1-8), SMPS Mode selector (S1/S2/S3K). Explain what each control does.
6. **Tabs** — how the [+] button creates new songs, how switching tabs updates the transport bar, how the dirty indicator (*) works
7. **Tips** — e.g., "Changes to tempo and timing in the transport bar apply immediately — you can adjust while playing."

Cross-reference forward to detailed chapters with links.

Target: ~200 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/03-interface-overview.md
git commit -m "docs: add interface overview chapter"
```

---

### Task 4: Write 04-fm-voice-editor.md

**Files:**
- Create: `docs/user-guide/04-fm-voice-editor.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/ui/FmVoiceEditor.java` — editor UI, algorithm diagram, all sliders
- `app/src/main/java/com/opensmps/deck/model/FmVoice.java` — parameter ranges, carrier table, data layout

**Step 1: Read source files**

Read FmVoiceEditor.java fully for all UI controls and their ranges. Read FmVoice.java for the carrier table (CARRIER_TABLE) and parameter accessors to get exact ranges.

**Step 2: Write 04-fm-voice-editor.md**

Sections:
1. **One-sentence summary** — "The FM voice editor creates timbres for the six FM channels by configuring four operators and their routing."
2. **Overview** — open from instrument panel, works on a copy, OK saves / Cancel discards
3. **Opening the Editor** — click **+** in voice bank, or select a voice and click **Edit** (or double-click)
4. **Algorithms** — table of 8 algorithms (0-7). For each: which operators are carriers, practical sound description. Use these descriptions:
   - 0: Four operators in series — thick, distorted, great for electric guitar
   - 1: Parallel input to a serial chain — punchy bass, brass
   - 2: Mixed routing — bell-like, metallic tones
   - 3: Alternative mixed — similar to 2 with different modulation character
   - 4: Two independent pairs — organ-like, two-layer sounds
   - 5: One modulator feeds three carriers — rich pads, choir
   - 6: One modulator pair plus two independent carriers — complex textures
   - 7: All four operators independent — additive synthesis, pure tones
5. **Algorithm Diagram** — explain the canvas shows operator topology in real time; carriers in cyan, modulators in grey
6. **Operator Parameters** — table with columns: Parameter, Range, What It Does. Parameters:
   - MUL (0-15): Frequency multiplier. 0 = half frequency, 1 = fundamental, 2 = octave up, etc.
   - DT (0-7): Detune. Slightly shifts pitch for chorus/thickness.
   - TL (0-127): Total level (volume). 0 = loudest, 127 = silent. On carriers this controls output volume; on modulators it controls modulation depth.
   - AR (0-31): Attack rate. Higher = faster attack. 31 = instant.
   - D1R (0-31): First decay rate. How fast volume drops after attack.
   - D2R (0-31): Second decay rate. Sustain-phase decay.
   - D1L (0-15): First decay level. Volume target after first decay.
   - RR (0-15): Release rate. How fast sound fades after key release.
   - RS (0-3): Rate scaling. Higher notes decay faster.
   - AM (on/off): Amplitude modulation. Enable LFO tremolo on this operator.
7. **Feedback** — Feedback (0-7) on operator 1 only. Higher = harsher/buzzier.
8. **Voice Operations** — describe Init, Copy, Paste, Preview buttons
9. **Tips**
   - Start from **Init** and change one parameter at a time
   - On carriers, TL controls the output volume directly
   - On modulators, TL controls how much that operator affects the sound — start at 127 (silent) and decrease to hear the modulation appear
   - Use **Preview** after each change to hear the result immediately

Target: ~250 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/04-fm-voice-editor.md
git commit -m "docs: add FM voice editor chapter"
```

---

### Task 5: Write 05-psg-envelopes.md

**Files:**
- Create: `docs/user-guide/05-psg-envelopes.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/ui/PsgEnvelopeEditor.java` — editor UI
- `app/src/main/java/com/opensmps/deck/model/PsgEnvelope.java` — data model

**Step 1: Read source files**

Read both files for exact volume ranges, step behavior, terminator value.

**Step 2: Write 05-psg-envelopes.md**

Sections:
1. **One-sentence summary** — "The PSG envelope editor shapes the volume curve for the three PSG tone channels and the noise channel."
2. **Overview** — PSG channels produce square waves (tones) and noise. The envelope controls how volume changes over time. Open from instrument panel.
3. **What PSG Sounds Like** — brief practical description: simple buzzy tones, good for percussion hits, arpeggios, sound effects. No pitch control beyond note entry.
4. **Editing Envelopes** — click or drag on the bar graph to set volume per step. Volume 0 = loudest, 7 = quietest. Each step plays for one frame at the current tempo.
5. **Managing Steps** — **+Step** adds a new step (volume 0), **-Step** removes the last step. Step count shown in label.
6. **Name** — editable text field at top
7. **Preview** — plays a PSG tone at middle C for 500ms with the current envelope
8. **Tips**
   - 2-4 step envelopes make good percussion (hi-hat, kick imitation)
   - Longer envelopes (8+ steps) with gradual decay work for sustained melodic tones
   - Volume 0 on every step = sustained square wave with no envelope shaping

Target: ~100 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/05-psg-envelopes.md
git commit -m "docs: add PSG envelopes chapter"
```

---

### Task 6: Write 06-dac-samples.md

**Files:**
- Create: `docs/user-guide/06-dac-samples.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/ui/DacSampleEditor.java` — editor UI
- `app/src/main/java/com/opensmps/deck/ui/InstrumentPanel.java` — DAC section (+ / Dup / Edit / Del buttons)
- `app/src/main/java/com/opensmps/deck/io/DacSampleImporter.java` — supported formats, conversion logic
- `app/src/main/java/com/opensmps/deck/model/DacSample.java` — data model

**Step 1: Read source files**

Read DacSampleImporter for exact format support (WAV parsing, 8/16-bit, stereo→mono). Read DacSampleEditor for rate range. Read TrackerGrid.java DAC_KEY_MAP for key-to-sample mapping.

**Step 2: Write 06-dac-samples.md**

Sections:
1. **One-sentence summary** — "The DAC channel plays audio samples through the YM2612's sixth FM channel, used for drums and sound effects."
2. **Overview** — DAC replaces FM channel 6. One sample plays at a time. Samples are 8-bit unsigned mono PCM.
3. **Importing Samples** — click **+** in the DAC samples section of the instrument panel. Supported formats:
   - WAV files: automatically converted to unsigned 8-bit mono (handles 8/16-bit, signed/unsigned, stereo→mono)
   - Raw PCM files (.pcm, .bin): read as unsigned 8-bit directly
   - Default playback rate: `0x0C`
4. **Editing Samples** — select and click **Edit** (or double-click): name field, rate spinner (0-255 hex). Size shown read-only.
5. **Duplicating and Deleting** — **Dup** button, **Del** button
6. **Entering DAC Notes** — on the DAC channel (column 6), note entry keys map to sample indices instead of pitches:
   - Lower row: Z=0, S=1, X=2, D=3, C=4, V=5, G=6, B=7, H=8, N=9, J=10, M=11
   - Upper row: Q=12, W=13, E=14, R=15, T=16, Y=17, U=18
   - Up to 19 samples total
7. **Limitations** — one sample at a time (no polyphony), mono only, 8-bit only, playback rate affects pitch and speed together
8. **Tips**
   - Keep samples short — long samples eat ROM space
   - Higher rate = faster/higher pitch playback, lower rate = slower/lower
   - Use the **Dup** button to create pitch variants of the same sample with different rates

Target: ~100 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/06-dac-samples.md
git commit -m "docs: add DAC samples chapter"
```

---

### Task 7: Write 07-tracker-grid.md

**Files:**
- Create: `docs/user-guide/07-tracker-grid.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java` — full file (all key handlers, rendering, selection, paste, transpose, mute/solo)
- `app/src/main/java/com/opensmps/deck/ui/InstrumentResolveDialog.java` — cross-song paste resolution
- `app/src/main/java/com/opensmps/deck/model/UndoManager.java` — undo/redo behavior

**Step 1: Read source files**

Read TrackerGrid.java fully. This is the most complex UI class. Pay attention to: handleKeyPress, handleMouseClick, note entry branches, DAC channel branch, instrument column hex entry, selection extend, paste logic, transpose logic, mute/solo toggle.

**Step 2: Write 07-tracker-grid.md**

This is the longest reference chapter (~300 lines). Sections:

1. **One-sentence summary** — "The tracker grid is where you compose music — entering notes, setting instruments, and editing patterns across all 10 channels."
2. **Overview** — decoded view of SMPS bytecode, 10 channels side by side, vertical scrolling
3. **Channel Layout** — table: Index | Name | Type | Hardware
   - 0-4: FM1-FM5, YM2612
   - 5: DAC, YM2612 Ch6
   - 6-8: PSG1-PSG3, SN76489 Tone
   - 9: Noise, SN76489 Noise
4. **Columns** — each channel shows: Note (note name + octave or rest/tie), Instrument (hex index), Effect (coordination flags). Duration follows note in the bytecode.
5. **Note Entry** — piano keyboard layout. Include the ASCII art diagram from the keyboard reference. Explain: press a note key to enter it at the cursor position. The note uses the currently selected octave.
6. **Rests and Ties** — Period (`.`) inserts a rest (`---`). Tie (`===`) sustains the previous note without re-keying.
7. **Octave Selection** — `F1`-`F8` select octaves 1-8. `Page Up`/`Page Down` increment/decrement. `Ctrl+Up`/`Ctrl+Down` also adjust octave.
8. **Instrument Column** — move cursor to the instrument column with arrow keys. Type two hex digits (0-9, A-F) to set the instrument index. First digit shows as pending (yellow), second confirms. `Escape` cancels pending digit. FM channels: sets FM voice index. PSG channels: sets PSG envelope index.
9. **Navigation** — arrow keys, `Tab`/`Shift+Tab` for channel switching, left/right wrap between channels
10. **Selection** — `Shift+Arrow` extends selection. `Ctrl+A` selects all in current pattern. Selection shown as blue highlight.
11. **Copy, Cut, Paste** — `Ctrl+C` copies, `Ctrl+X` cuts, `Ctrl+V` pastes at cursor. Paste applies across channels starting from cursor channel. Cross-song paste: if instruments differ, a resolution dialog appears offering Copy into song / Remap / Skip for each unresolved instrument.
12. **Transposition** — `=` / `+` transposes up 1 semitone, `-` transposes down 1. `Shift+=` / `Shift+-` transposes by octave. Works on selection or single note at cursor.
13. **Row Operations** — `Insert` adds empty row at cursor, `Delete` removes row at cursor, `Backspace` removes row and pulls up.
14. **Channel Mute and Solo** — click channel header to mute (grey strikethrough), `Ctrl+Click` to solo (gold, others muted). Mute/solo state is respected during playback and WAV export.
15. **Undo and Redo** — `Ctrl+Z` undoes last edit, `Ctrl+Y` redoes. Multi-channel operations are one undo step.
16. **Tips**
   - Use `Ctrl+Click` solo to isolate a channel while composing
   - Cross-song paste lets you reuse patterns between songs — the resolution dialog handles instrument differences automatically
   - Transposition shortcuts work on selections — select a phrase and shift it up an octave in one keystroke

Link to [Keyboard Reference](11-keyboard-reference.md) for the complete shortcut table.

Target: ~300 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/07-tracker-grid.md
git commit -m "docs: add tracker grid chapter"
```

---

### Task 8: Write 08-patterns-and-orders.md

**Files:**
- Create: `docs/user-guide/08-patterns-and-orders.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/ui/OrderListPanel.java` — order list UI, buttons, loop marker
- `app/src/main/java/com/opensmps/deck/model/Pattern.java` — pattern data structure
- `app/src/main/java/com/opensmps/deck/model/Song.java` — orderList, patterns, loopPoint

**Step 1: Read source files**

Read OrderListPanel.java for button behavior, display format, loop marker. Read Song.java for how orderList relates to patterns.

**Step 2: Write 08-patterns-and-orders.md**

Sections:
1. **One-sentence summary** — "Patterns hold blocks of channel data; the order list arranges them into a complete song."
2. **Overview** — composition works in two levels: patterns (what to play) and the order list (when to play it)
3. **Patterns** — each pattern contains 10 channels of track data. Patterns are reusable — the same pattern can appear multiple times in the order list. Each channel within a pattern is independent; one order row assigns one pattern index per channel.
4. **The Order List** — displayed as rows, each showing 10 hex indices (one per channel). Click a row to view that pattern in the tracker grid. The order list plays top to bottom.
5. **Adding Rows** — **+** button adds a new row (all channels default to pattern 0). **Dup** button duplicates the selected row (inserts after current).
6. **Removing Rows** — **-** button removes the selected row. At least one row must remain.
7. **Loop Point** — **Loop** button sets the loop point to the selected row. Shown with a loop marker (↻). When playback reaches the end of the order list, it jumps back to the loop point row.
8. **Per-Channel Pattern Assignment** — each channel in an order row can point to a different pattern. This lets you reuse a bass pattern while varying the melody.
9. **Tips**
   - Use **Dup** to create variations — duplicate a row, then edit one channel's pattern
   - Set the loop point to the second row to create an intro that plays once followed by a looping body
   - The order list is the arrangement — think of patterns as building blocks and the order list as the blueprint

Target: ~150 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/08-patterns-and-orders.md
git commit -m "docs: add patterns and orders chapter"
```

---

### Task 9: Write 09-playback-and-export.md

**Files:**
- Create: `docs/user-guide/09-playback-and-export.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/audio/PlaybackEngine.java` — play, stop, pause, preview
- `app/src/main/java/com/opensmps/deck/io/WavExporter.java` — WAV export (sample rate, loop count, fade-out)
- `app/src/main/java/com/opensmps/deck/io/SmpsExporter.java` — SMPS binary export
- `app/src/main/java/com/opensmps/deck/io/VoiceBankFile.java` — .ovm export
- `app/src/main/java/com/opensmps/deck/ui/MainWindow.java` — export menu items

**Step 1: Read source files**

Read PlaybackEngine for play/stop/pause/preview methods. Read WavExporter for sample rate (44100), bit depth (16), channel count (stereo), loop count default, fade-out behavior. Read SmpsExporter for output format. Read VoiceBankFile for .ovm format.

**Step 2: Write 09-playback-and-export.md**

Sections:
1. **One-sentence summary** — "Play your song in real time, export it as audio, or compile it to SMPS binary for use in Sonic ROMs."
2. **Overview** — what you hear is what exports — the internal model is SMPS bytecode
3. **Playback Controls** — `Space` to play/pause, `Enter` to play from cursor, `Escape` to stop. Transport bar buttons do the same.
4. **Mute and Solo During Playback** — click/Ctrl+click channel headers. Changes take effect immediately during playback.
5. **WAV Export** — File → Export WAV. Renders offline at 44.1 kHz, 16-bit stereo. Default 2 loops with linear fade-out on the final loop. Progress dialog shown during export. Muted channels are excluded from the export.
6. **SMPS Binary Export** — File → Export SMPS. Compiles the song to a raw SMPS binary (.bin) that can be injected into Sonic ROMs or played by SMPS-compatible drivers.
7. **Voice Bank Export** — File → Export Voice Bank. Saves all FM voices and PSG envelopes to an `.ovm` file for sharing between projects.
8. **Tips**
   - Mute channels before WAV export to create stems (e.g., mute everything except drums)
   - The SMPS binary is the exact same data the playback engine uses — what you hear is what you get

Target: ~150 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/09-playback-and-export.md
git commit -m "docs: add playback and export chapter"
```

---

### Task 10: Write 10-importing.md

**Files:**
- Create: `docs/user-guide/10-importing.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/io/SmpsImporter.java` — SMPS binary import
- `app/src/main/java/com/opensmps/deck/io/RomVoiceImporter.java` — directory scan voice import
- `app/src/main/java/com/opensmps/deck/io/VoiceBankFile.java` — .ovm import
- `app/src/main/java/com/opensmps/deck/io/Rym2612Importer.java` — .rym2612 import
- `app/src/main/java/com/opensmps/deck/io/DacSampleImporter.java` — sample import
- `app/src/main/java/com/opensmps/deck/ui/VoiceImportDialog.java` — voice selection table
- `app/src/main/java/com/opensmps/deck/ui/InstrumentPanel.java` — Import Bank button

**Step 1: Read source files**

Read SmpsImporter for supported extensions (.bin, .s3k, .sm2, .smp). Read VoiceImportDialog for the filter/multi-select table. Read InstrumentPanel for the Import Bank flow. Read Rym2612Importer for .rym2612 handling.

**Step 2: Write 10-importing.md**

Sections:
1. **One-sentence summary** — "Import existing SMPS music, voice banks, and audio samples from files or ROM rips."
2. **Overview** — multiple import paths for different use cases
3. **Importing SMPS Binary Files** — File → Import SMPS. Opens a raw SMPS binary as a new song. Supports `.bin`, `.s3k`, `.sm2`, `.smp`. The importer parses header, voice pointers, track data, and reconstructs a Song model. Good for studying existing Sonic music or remixing.
4. **Importing Voice Banks** — two paths:
   - **From .ovm file:** File → Import Voice Bank, or **Import Bank** in instrument panel. If the file contains multiple voices, a selection dialog appears.
   - **From .rym2612 file:** Same flow. Imported as a single FM voice.
   - PSG envelopes in the bank are also imported.
5. **Importing Voices from SMPS Files** — File → Import Voices. Browse to a directory of SMPS files. The importer scans all files and presents a table showing: Name, Source Song, Index, Algorithm. Filter by song name or algorithm number. Multi-select voices to import into the current song's voice bank.
6. **Voice Selection Dialog** — describe the filter field, multi-select table columns, and how selected voices are added to the voice bank
7. **Importing DAC Samples** — click **+** in DAC samples section. Supports WAV (auto-converted to 8-bit unsigned mono) and raw PCM (.pcm, .bin). See [DAC Samples](06-dac-samples.md) for details.
8. **Tips**
   - Use Import Voices to grab specific patches from Sonic ROM rips without importing the whole song
   - The filter field in the voice selection dialog accepts algorithm numbers — type "7" to find all additive patches

Target: ~200 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/10-importing.md
git commit -m "docs: add importing chapter"
```

---

### Task 11: Write 12-smps-modes.md

**Files:**
- Create: `docs/user-guide/12-smps-modes.md`

**Reference source files:**
- `app/src/main/java/com/opensmps/deck/model/SmpsMode.java` — enum values and docs
- `synth-core/src/main/java/com/opensmps/smps/SmpsSequencerConfig.java` — per-mode config differences (tempo mode, pointer mode)

**Step 1: Read source files**

Read SmpsMode.java and SmpsSequencerConfig.java to understand exact differences between S1, S2, S3K.

**Step 2: Write 12-smps-modes.md**

Sections:
1. **One-sentence summary** — "The SMPS mode determines which Sonic game's sound driver your song targets."
2. **Overview** — select in the transport bar. Affects tempo interpretation, sequencer behavior, and binary export format.
3. **The Three Modes** — table: Mode | Target | Driver | Tempo Mode
   - S1: Sonic 1 — 68k SMPS driver, TIMEOUT tempo, PC-relative pointers
   - S2: Sonic 2 — Z80 SMPS driver, OVERFLOW2 tempo
   - S3K: Sonic 3 & Knuckles — Z80 SMPS driver, OVERFLOW tempo
4. **Which Mode to Choose** — practical guidance:
   - Composing for a Sonic 1 ROM hack → S1
   - Composing for a Sonic 2 ROM hack → S2
   - Composing for Sonic 3K or general use → S3K (most common, most capable)
   - Just experimenting / not targeting a specific game → S3K
5. **Tempo Behavior** — brief explanation: different modes interpret the tempo value differently. Tempo N in S1 mode won't feel the same as tempo N in S3K. If you switch modes, you may need to adjust tempo.
6. **Tips**
   - S3K is the safest default for new compositions
   - If importing an SMPS file from a specific game, match the mode to that game

Target: ~80 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/12-smps-modes.md
git commit -m "docs: add SMPS modes chapter"
```

---

### Task 12: Write 01-quick-start.md

**Files:**
- Create: `docs/user-guide/01-quick-start.md`

**Reference source files:**
- `pom.xml` — Java version, build command
- `app/src/main/java/com/opensmps/deck/ui/MainWindow.java` — what appears on launch

**Step 1: Read pom.xml for build prerequisites**

Check Java version requirement and build commands.

**Step 2: Write 01-quick-start.md**

Sections:
1. **Title** — "Quick Start"
2. **Prerequisites** — Java 21+, Maven. Build with `mvn compile`. Run instructions.
3. **What You See** — on launch: empty song tab, 10-channel tracker grid, transport bar at top, instrument panel on the right, order list at bottom
4. **Make Your First Sound** — step-by-step (keep it to ~5 steps):
   1. The cursor starts on FM1. Press `Z` to enter a C note.
   2. Press `Space` to play. You hear a tone.
   3. Press `Escape` to stop.
   4. Press `F5` to switch to octave 5. Press `Z` again — higher pitch.
   5. In the instrument panel, click **+** under Voice Bank. Click **Init**, then **OK**. Now enter a note — it uses the new voice.
5. **Next Steps** — link to [Tutorial: Your First Song](02-tutorial-first-song.md) for a full walkthrough, or [Interface Overview](03-interface-overview.md) if you already know trackers.

Target: ~100 lines.

**Step 3: Commit**

```bash
git add docs/user-guide/01-quick-start.md
git commit -m "docs: add quick start chapter"
```

---

### Task 13: Write 02-tutorial-first-song.md

This is the longest and most important chapter. It must be written after all reference chapters so it can cross-reference them accurately.

**Files:**
- Create: `docs/user-guide/02-tutorial-first-song.md`

**Reference source files:**
- All UI source files (for exact button names and behavior)
- All previously written chapters (for accurate cross-references)

**Step 1: Read all previously written chapters**

Read 03 through 12 to ensure cross-references are correct and terminology is consistent.

**Step 2: Write 02-tutorial-first-song.md**

Structure: intro paragraph, then 11 numbered sections. Each section has a brief goal statement, step-by-step instructions, and a "what you should see/hear" confirmation.

**Intro:** "This tutorial walks you through creating a complete song from scratch — building instruments, writing patterns, arranging them, and exporting the result. By the end you'll have a short loop with FM bass, FM lead, and PSG rhythm."

**Section 1: Create a Bass Voice (~40 lines)**
- Goal: create a simple low-frequency FM voice
- Open voice editor: click **+** in the voice bank section of the instrument panel
- Click **Init** to start from a clean patch
- Set algorithm to 7 (all operators independent — simplest to start with)
- Only operator 4 matters for algorithm 7 at default TL. Set MUL to 1 (fundamental frequency).
- Set AR to 31 (instant attack), D1R to 0, D2R to 0, RR to 8 (moderate release)
- Name it "Bass"
- Click **Preview** to hear a simple tone. Click **OK**.
- Link: [FM Voice Editor](04-fm-voice-editor.md) for full parameter reference

**Section 2: Create a Lead Voice (~40 lines)**
- Goal: create a brighter, richer FM voice
- Click **+** again for a second voice
- Click **Init**, then set algorithm to 4 (two independent pairs — good for two-layer sounds)
- Configure operator parameters for a bright lead (adjust MUL values on operators to create harmonics, bring TL down on carriers)
- Set AR to 28, D1R to 5, D1L to 3, RR to 10 for a shaped envelope
- Name it "Lead"
- Preview and OK

**Section 3: Create a PSG Envelope (~30 lines)**
- Goal: create a short percussive envelope for rhythm
- In the instrument panel, under PSG Envelopes, click **+**
- The envelope editor opens with 2 default steps
- Click **+Step** twice to get 4 steps
- Click to set volumes: step 0 = volume 0 (loud), step 1 = volume 2, step 2 = volume 5, step 3 = volume 7 (quiet)
- Name it "Tick"
- Preview and OK
- Link: [PSG Envelopes](05-psg-envelopes.md)

**Section 4: Enter Bass Notes on FM1 (~40 lines)**
- Goal: write a simple bass pattern
- Click on the tracker grid. The cursor should be on FM1, row 00.
- Press `F3` to select octave 3 (good for bass)
- Enter a bass line: press `Z` (C), move down, press `V` (F), move down, press `B` (G), etc.
- Press `.` (period) to enter rests between notes
- Play with `Space` to hear the pattern, `Escape` to stop

**Section 5: Assign the Bass Voice (~25 lines)**
- Goal: assign voice 00 to the bass pattern
- Move cursor to the first row of FM1
- Press right arrow to move to the instrument column
- Type `0` then `0` to set instrument 00 (your Bass voice)
- The voice change is prepended to the note. Play to hear the difference.
- Link: [Tracker Grid](07-tracker-grid.md) for full instrument entry details

**Section 6: Enter Lead Melody on FM2 (~35 lines)**
- Goal: write a melody on the second FM channel
- Press `Tab` to move to FM2
- Press `F5` for octave 5
- Enter a simple melody using note keys
- Move to instrument column, type `0` `1` to assign voice 01 (Lead)
- Play to hear both channels together

**Section 7: Add PSG Rhythm on PSG1 (~35 lines)**
- Goal: add rhythmic texture
- Press `Tab` until you reach PSG1 (channel 7)
- Press `F6` for octave 6 (PSG is bright)
- Enter repeated notes with rests for a rhythmic pattern
- Move to instrument column, type `0` `0` to assign envelope 00 (Tick)
- Play to hear all three channels

**Section 8: Create a Second Pattern (~35 lines)**
- Goal: add variation with a second pattern
- In the order list panel, click **+** to add a second row
- Click on the new row to select it — the tracker grid shows an empty pattern
- Go back to the first row, select all (`Ctrl+A`), copy (`Ctrl+C`)
- Click the second row, paste (`Ctrl+V`)
- Modify the melody on FM2 — change a few notes for variation

**Section 9: Arrange and Loop (~25 lines)**
- Goal: arrange patterns and set a loop point
- The order list now has two rows. Playback goes row 0 → row 1 → loops.
- Click row 0 and click **Loop** to set the loop point (plays both patterns on repeat)
- Or click row 1 and click **Loop** to make row 0 an intro that plays once
- Play the full song with `Space` to hear the arrangement
- Link: [Patterns and Orders](08-patterns-and-orders.md)

**Section 10: Set Tempo and Timing (~20 lines)**
- Goal: adjust the feel
- In the transport bar, adjust **Tempo** spinner. Lower = slower, higher = faster. Try values around 6-8 for a moderate tempo.
- Adjust **Dividing Timing** if needed (default 1 is usually fine)
- SMPS Mode defaults to S3K — leave it unless targeting a specific game
- Link: [SMPS Modes](12-smps-modes.md)

**Section 11: Listen and Export (~30 lines)**
- Goal: export the finished song
- Play the full song to confirm it sounds right
- File → Save (Ctrl+S) to save as `.osmpsd` project
- File → Export WAV to render audio. The export plays the song twice with a fade-out.
- File → Export SMPS to produce a `.bin` binary for ROM injection
- Congratulations — you've made a Mega Drive song!
- Link: [Playback and Export](09-playback-and-export.md)

Target: ~400-500 lines total.

**Step 3: Commit**

```bash
git add docs/user-guide/02-tutorial-first-song.md
git commit -m "docs: add tutorial chapter - Your First Song"
```

---

### Task 14: Final review and cross-link verification

**Files:**
- Modify: all files in `docs/user-guide/`

**Step 1: Verify all cross-references**

Read every chapter and check that all `[text](filename.md)` links point to files that exist and section references are accurate.

**Step 2: Verify completeness against design doc**

Read `docs/plans/2026-02-28-user-guide-design.md` and check every requirement is met:
- All 13 files exist
- Writing style matches (second person, bold for UI, monospace for keys)
- Reference chapters follow the 5-section structure
- No screenshots, no emojis, no bytecode details in main chapters

**Step 3: Verify keyboard reference accuracy**

Cross-check every shortcut in `11-keyboard-reference.md` against the source code in TrackerGrid.java and MainWindow.java.

**Step 4: Fix any issues found**

Edit files to correct broken links, missing content, or style inconsistencies.

**Step 5: Commit fixes if any**

```bash
git add docs/user-guide/
git commit -m "docs: fix cross-references and review issues in user guide"
```

---

### Task 15: Update index with final links and commit full guide

**Step 1: Re-read 00-index.md and verify all links match actual chapter titles**

**Step 2: Add user guide link to project (if README exists, add link; if not, note in index that this is the entry point)**

**Step 3: Final commit**

```bash
git add docs/user-guide/
git commit -m "docs: complete user guide - 13 chapters covering all features"
```
