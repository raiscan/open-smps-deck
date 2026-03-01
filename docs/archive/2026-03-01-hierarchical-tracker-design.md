# Hierarchical Tracker Redesign

## Problem

The current tracker interface is a flat grid over raw SMPS bytecode. This fails to represent SMPS's most distinctive features:

- **Per-channel flow control**: Each SMPS track independently loops (`F6 JUMP`), calls subroutines (`F8 CALL` / `E3 RETURN`), and uses counted loops (`F7 LOOP`). The current order list forces all channels to advance together with a single global loop point.
- **22+ coordination flags**: Pan, detune, volume, modulation, transpose, note fill, etc. are all raw hex entry — no semantic UI, no mnemonics, no channel-type awareness.
- **Subroutine reuse**: SMPS tracks share bytecode via CALL/RETURN (16% of real Sonic tracks use nested calls, up to 3 levels deep). The current model has no concept of reusable phrases.

## Design Principles

1. **SMPS bytecode remains the source of truth.** What plays is what exports. The abstraction layer is a projection that compiles down and decompiles up.
2. **Bidirectional sync.** Edits in the abstraction compile to bytecode immediately. Imported SMPS binaries decompile into the abstraction. If the decompiler can't perfectly represent something, it flags it rather than silently dropping data.
3. **LSDJ-style hierarchy** (Song -> Chain -> Phrase) for arrangement, directly mapping to SMPS flow control features.
4. **Tracker effect columns with mnemonics** for coordination flags, preserving the tracker workflow while improving readability.

## Architecture: Bidirectional Two-Layer Model

```
+-------------------------------------+
|  Abstraction Layer (user edits)     |
|  Song -> Chain -> Phrase hierarchy   |
|  Named effects, visual arrangement  |
+---------------+---------------------+
                | compile v  ^ decompile
+---------------+---------------------+
|  SMPS Bytecode Layer (source of     |
|  truth). What plays. What exports.  |
+-------------------------------------+
```

## The Hierarchy

### Phrase (atomic unit of composition)

- A variable-length sequence of note rows (no fixed size constraint)
- Each row: Note | Duration | Instrument | Effect1 | Effect2 | ... (expandable)
- Belongs to a channel type (FM, PSG, DAC) which determines available effects
- **Globally shared** — editing a phrase updates all references to it
- Can reference sub-phrases (nested CALL), max depth 4, with cycle detection
- Compiles to a contiguous block of SMPS bytecode; when shared, reachable via `CALL (F8)`

### Chain (sequences phrases per channel)

- An ordered list of phrase references with per-entry properties:
  - **Transpose** (semitones) -> compiles to `KEY_DISP (E9)`
  - **Repeat count** -> compiles to `LOOP (F7)` wrapping the phrase
  - **Loop point** -> compiles to `JUMP (F6)` at chain end
- One chain per channel (10 total)
- Compiles to a single SMPS track stream

### Song (top level)

- 10 chains (FM1-5, DAC, PSG1-3, Noise)
- Global properties: name, SMPS mode (S1/S2/S3K), tempo, dividing timing
- Voice bank, PSG envelopes, DAC samples (unchanged)
- Compiles to a complete SMPS binary

### SMPS Feature Mapping

| SMPS Feature | Hierarchy Mapping |
|-------------|-------------------|
| Per-track loop (`F6 JUMP`) | Chain loop point |
| Subroutine (`F8 CALL` / `E3 RETURN`) | Shared phrase referenced from multiple chains |
| Counted loop (`F7 LOOP`) | Chain entry with repeat count > 1 |
| Track end (`F2 STOP`) | End of chain with no loop point |
| Transpose (`E9 KEY_DISP`) | Chain entry transpose field |
| Nested subroutine | Phrase referencing a sub-phrase |
| All coordination flags | Effect columns within phrases |

## UI Layout

### Overall Structure

```
+------------+------------------------------------------------+
|            |                                                |
|  Song      |   Active Editor Area                           |
|  View      |   (Chain Editor / Phrase Editor / Instruments)  |
|  (left     |                                                |
|  panel,    |                                                |
|  always    |                                                |
|  visible)  |                                                |
|            |                                                |
+------------+------------------------------------------------+
|  Breadcrumb: FM1 Chain > Verse A > Bass Riff     Row 12/48 |
+-------------------------------------------------------------+
```

### Song View (Left Panel)

Always visible. Shows all 10 channels' structure at a glance.

```
+-- Song View -------------------------------------------+
|                                                        |
|  FM1  | Intro | Verse A | Verse A | Chorus   |<- Loop |
|  FM2  | Intro | Verse B | Verse B | Chorus   |<- Loop |
|  FM3  | Pad   | Pad     | Pad     | Pad      |<- Loop |
|  FM4  | Intro | Count-A | Count-B | Chorus   |<- Loop |
|  FM5  | BassA | Bass A  | Bass B  | Bass A   |<- Loop |
|  DAC  | DrumA(x4)       | DrumB   | DrumA    |<- Loop |
|  PSG1 | Arp A | Arp A   | Arp B   | Arp A    |<- Loop |
|  PSG2 | ----- | Lead    | Lead    | Lead     |<- Loop |
|  PSG3 | ----- | -----   | Swell   | -----    |        |
|  Noise| HiHat(x8)                 | Fill     |<- Loop |
|                                                        |
+--------------------------------------------------------+
```

- Each cell is a phrase reference. Width proportional to phrase duration (ticks).
- Shared phrases have matching colors across channels.
- `(x4)` suffix = repeat count -> compiles to `LOOP (F7)`.
- `<- Loop` = per-channel loop marker -> compiles to `JUMP (F6)`.
- `-----` = empty/silent phrase (rests).
- Playback cursor: vertical line sweeping across all channels.

**Interactions:**
- Single-click cell -> select, show properties in popover
- Double-click cell -> open phrase in Phrase Editor
- Right-click cell -> context menu (Edit, Rename, Transpose, Repeat, Unlink, Delete)
- Drag cell -> reorder within chain
- Drag between channels -> copy phrase reference (if compatible type)
- Drag right edge -> resize (changes repeat count)

### Chain Editor

Two access modes:

**Strip mode** (always visible above phrase editor): Horizontal strip showing the active channel's chain as clickable phrase cells. Click a cell to switch the phrase editor below.

**Full mode** (opened via channel header right-click or keyboard shortcut): Table view with columns:

| # | Phrase | Transpose | Repeat | Length (ticks) |
|---|--------|-----------|--------|----------------|

- Add/remove/reorder phrase references
- Set transpose (+/- semitones) and repeat count per entry
- Drag loop point marker to set per-channel loop target

### Phrase Editor (Primary Editing Surface)

The tracker grid where most composition happens.

```
Row  Note  Dur  Ins  | Eff 1      | Eff 2      | Eff 3
------------------------------------------------------
 00  C-5   18   03  | PAN LR     |            |
 01  E-5   18   --  | MOD 0A0102 |            |
 02  ---   18   --  | VOL +05    | DET -03    |
 03  G-5   18   --  | MOFF       |            |
 04  ===   18   --  |            |            |       <- tie
 05  C-6   0C   04  | FIL 80     | TRN +07    |
 06  >> Bass Riff   | (x2)       |            |       <- sub-phrase
 07  ---   30   --  | STP        |            |       <- stop
```

- Expandable effect columns (add/remove with +/- buttons)
- Sub-phrase references shown as `>> Name` rows, clickable to navigate in
- Keyboard-driven note entry (existing piano layout)
- Effect entry via mnemonic shortcuts

**Breadcrumb bar** above the grid:
- Shows navigation path: `FM1 Chain > Verse A > Bass Riff`
- Each segment clickable to navigate back up
- Escape/Backspace = go up one level

## Effect Column Mnemonics

Effects use short, readable mnemonics instead of raw hex bytes.

| Mnemonic | SMPS Flag | Display Example | Entry Key |
|----------|-----------|-----------------|-----------|
| `PAN` | E0 Pan | `PAN LR`, `PAN L`, `PAN R` | P |
| `DET` | E1 Detune | `DET +03`, `DET -05` | D |
| `COM` | E2 Set Comm | `COM 42` | -- |
| `TIK` | E5 Tick Mult | `TIK 02` | T |
| `VOL` | E6 Volume | `VOL +05`, `VOL -03` | V |
| `FIL` | E8 Note Fill | `FIL 80` | F |
| `TRN` | E9 Transpose | `TRN +07`, `TRN -05` | K |
| `TMP` | EA Set Tempo | `TMP 78` | -- |
| `DIV` | EB Div Timing | `DIV 02` | -- |
| `PVL` | EC PSG Volume | `PVL +03` | -- |
| `MOD` | F0 Modulation | `MOD wwssddcc` | M |
| `MON` | F1 Mod On | `MON` | -- |
| `MOFF` | F4 Mod Off | `MOFF` | -- |
| `NOI` | F3 PSG Noise | `NOI 03` | N |
| `STP` | F2 Stop | `STP` | -- |
| `SOF` | F9 Sound Off | `SOF` | -- |

**Entry flow:** Press mnemonic key (e.g. `V`) -> column shows `VOL __` -> type `+05` -> confirmed as `VOL +05`. Tab to next column.

**Channel-type awareness:** FM channels show FM-relevant effects (PAN, DET, VOL, MOD, SOF). PSG channels show PSG-relevant effects (PVL, NOI). Invalid effects for a channel type are rejected at entry time.

## SMPS Import: Decompilation Preview

Importing a raw SMPS binary uses a hybrid auto-detect + user-confirm approach.

### Three-Pass Decompilation

**Pass 1 — Structural analysis:**
- Identify CALL/RETURN pairs -> shared phrases
- Identify LOOP boundaries -> repeat counts
- Identify JUMP targets -> chain loop points
- Track bytecode reachability (breadth-first, as SmpsImporter already does)

**Pass 2 — Ambiguity resolution:**
For linear bytecode sections with no flow control, present the user with split options:
- Keep as one phrase
- Split every N ticks (based on tempo/time signature)
- Manual split points

**Pass 3 — Naming and finalization:**
- Auto-name shared phrases ("Shared A", "Shared B")
- Auto-name unique phrases by channel and position
- User can rename in the preview before confirming

### Preview Dialog

Shows per-channel structure diagrams with:
- Detected phrases, loops, calls visualized as blocks
- Ambiguous sections highlighted with split options
- Shared phrases listed with their reference count
- Import/Cancel buttons

## Compilation Path

```
Abstraction Layer                    SMPS Bytecode
-----------------                    --------------
Phrase "Verse A"    --compile-->      Contiguous bytecode block
  note rows + effects                 (notes + coord flags)

Chain (FM1)         --compile-->      Single track stream:
  [phrase refs with                    CALL shared | inline unique
   transpose, repeat]                  LOOP for repeats
                                       KEY_DISP for transpose
                                       JUMP at loop point

Song                --compile-->      Full SMPS binary:
  10 chains + voices                   Header + 10 tracks +
  + envelopes + DAC                    voice bank + PSG data +
                                       DAC table
```

### Compilation Rules

- Phrase referenced by 1 chain entry only -> **inlined** (bytecode directly in track)
- Phrase referenced by 2+ entries or channels -> **subroutine** (CALL/RETURN)
- Repeat count > 1 -> wrap with `LOOP (F7)` and counter
- Transpose != 0 -> emit `KEY_DISP (E9)` before phrase, restore after
- Chain loop point set -> `JUMP (F6)` at track end
- No loop point -> `STOP (F2)` at track end

## Sub-Phrase Nesting

Phrases can reference other phrases (sub-phrase calls), matching SMPS's nested CALL/RETURN capability.

### Constraints

- **Max depth: 4 levels** (covers all real Sonic music; Z80 driver collision risk at ~5)
- **Cycle detection at edit time** — refuse to create circular references
- **Breadcrumb navigation** — always shows full path in the phrase editor

### Real-World Nesting Data (from Sonic 1/2/3K analysis)

| Context | Call Depth | Frequency |
|---------|-----------|-----------|
| Most gameplay tracks | 0-1 levels | ~84% |
| Complex tracks (Special Stage, boss) | 2 levels | ~12% |
| Credits sequences | 2-3 levels | ~4% |

### Sub-Phrase Display

In the phrase editor, sub-phrase references appear as special rows:
```
 06  >> Bass Riff   | (x2)       |            |
```

Double-click to navigate into the sub-phrase. Breadcrumb updates. Escape to go back up.

## Migration from Current Model

The current flat model (Pattern + OrderList) can be migrated:
- Each existing Pattern becomes a Phrase
- The OrderList rows become Chain entries (one per channel, using the pattern indices)
- The global loop point becomes per-channel loop points (initially all pointing to the same chain entry)
- Raw bytecode in Pattern.tracks is preserved as-is within phrases

The existing StructuredArrangement scaffold (BlockDefinition, BlockRef, ChannelArrangement) can be evolved or replaced to implement this hierarchy.
