# Chains and Phrases

Phrases hold blocks of channel data; chains arrange them into a complete song using an LSDJ-style hierarchy.

## Overview

Composition in OpenSMPSDeck follows a three-level hierarchy: **Song -> Chain -> Phrase**. Each channel has its own chain -- an ordered list of phrase references. Phrases are the building blocks that contain actual SMPS track data (notes, durations, coordination flags). Chains define the sequence and can transpose phrases, repeat them, and set loop points. This hierarchical model maps directly to SMPS bytecode features: subroutine calls, loops, key displacement, and jump commands.

```
Song
└── HierarchicalArrangement
    ├── PhraseLibrary (shared pool of Phrases)
    └── 10 Chains (one per channel: FM1-5, DAC, PSG1-3, Noise)
        └── ChainEntry[] (phrase ID + transpose + repeat count)
```

## Phrases

A phrase is a named unit of SMPS track data for a single channel. Each phrase stores raw SMPS bytecode -- notes, durations, instrument changes, and coordination flags.

Phrases live in a shared **Phrase Library** that belongs to the song. Any chain on any channel can reference any phrase (as long as the channel types are compatible). This means you write a passage once and reference it from multiple chains or multiple positions within the same chain.

When you create a new song, the first phrase is created automatically for each channel. The **Tracker Grid** displays the contents of the currently selected phrase.

### Creating Phrases

Click the **+** button at the end of the **Chain Strip** to create a new phrase and append it to the active channel's chain. The **Tracker Grid** switches to the new, empty phrase for editing.

You can also create phrases through the **Phrase Library** panel by clicking **New Phrase**. Phrases created this way are not automatically added to any chain -- they exist in the library and can be referenced later.

### Editing Phrases

Select a phrase by clicking its cell in the **Chain Strip**. The **Tracker Grid** loads the phrase's data and you can edit it using the standard note entry, instrument, and effect workflows described in [Tracker Grid](07-tracker-grid.md).

Each phrase is independent -- editing a phrase that is referenced by multiple chain entries changes it everywhere it appears. This is intentional: shared phrases let you update a passage in one place and have the change propagate across the entire song.

### Phrase Library Management

The **Phrase Library** displays all phrases in the song. From here you can:

- **Rename** a phrase by double-clicking its name.
- **Duplicate** a phrase to create an independent copy with new data.
- **Delete** a phrase that is no longer referenced by any chain.

## Chains

Each of the 10 channels has exactly one chain. A chain is an ordered list of **chain entries**, where each entry references a phrase by ID and optionally applies transpose and repeat modifiers.

### Chain Entries

A chain entry has three properties:

| Property | Description | Default |
|----------|-------------|---------|
| **Phrase ID** | Reference to a phrase in the Phrase Library | -- |
| **Transpose** | Semitone offset applied to all notes in the phrase | 0 |
| **Repeat Count** | Number of times to play this entry before advancing | 1 |

Transpose lets you reuse the same phrase at different pitches without duplicating it. For example, a bass phrase written in C can be transposed +5 to play in F and +7 to play in G -- three chain entries referencing one phrase.

Repeat count lets you loop a phrase multiple times in sequence. A repeat count of 4 plays the phrase four times before the chain moves to the next entry.

### Viewing and Navigating Chains

The **Song View** on the left side of the window shows all 10 channels and their chains at a glance. Click a channel to select it. The **Chain Strip** above the **Tracker Grid** updates to show the selected channel's chain as a horizontal row of clickable phrase cells.

Click any phrase cell in the **Chain Strip** to load that phrase into the **Tracker Grid** for editing. The **Breadcrumb Bar** above the **Chain Strip** shows your current position in the hierarchy (e.g., "Song > FM1 > Phrase 03") and lets you click any level to navigate back up.

### Adding and Removing Chain Entries

- Click **+** at the end of the **Chain Strip** to append a new entry (creates a new phrase automatically).
- Right-click a phrase cell and select **Insert Before** or **Insert After** to add an entry at a specific position.
- Right-click a phrase cell and select **Remove** to delete that entry from the chain. The phrase itself remains in the library.

### Reordering Chain Entries

Drag and drop phrase cells within the **Chain Strip** to reorder the chain. The playback sequence follows the left-to-right order of entries in the strip.

### Setting Transpose and Repeat

Right-click a phrase cell in the **Chain Strip** to access the context menu:

- **Set Transpose...** -- opens a dialog to set the semitone offset (-128 to +127). The transpose value is displayed on the phrase cell as a signed number (e.g., `+5`, `-3`).
- **Set Repeat Count...** -- opens a dialog to set the repeat count (1 to 255). A repeat count greater than 1 is displayed on the phrase cell (e.g., `x4`).

### Chain Loop Point

Each chain has an optional loop point that determines where playback jumps back to after reaching the end of the chain.

Right-click a phrase cell and select **Set Loop Point** to mark that entry as the loop target. A loop arrow indicator appears on the cell. When the chain finishes playing its last entry, playback jumps back to the loop point entry and continues from there.

If no loop point is set, the channel stops when the chain ends (the compiler emits an SMPS `F2 STOP` command). If a loop point is set, the compiler emits an `F6 JUMP` command that points back to the loop entry.

The default loop point for a new chain is the first entry (index 0), which means the entire chain repeats by default. Set the loop point to the second entry to create an intro section that plays once followed by a looping body.

## How Chains Compile to SMPS

Each chain compiles independently to a single SMPS track. The `HierarchyCompiler` translates the chain/phrase hierarchy into flat SMPS bytecode using these mappings:

| Chain Feature | SMPS Bytecode | When Used |
|---------------|---------------|-----------|
| Inline phrase | Raw bytes copied directly | Phrase referenced only once in the chain |
| Shared phrase | `F8 CALL` / `E3 RETURN` | Phrase referenced two or more times across all chains |
| Repeat | `F7 LOOP` + count | Chain entry with repeat count > 1 |
| Transpose | `E9 KEY_DISP` | Chain entry with transpose != 0 |
| Channel loop | `F6 JUMP` | Chain has a loop point set |
| Channel end | `F2 STOP` | No loop point set |

### Inline vs. Shared Phrases

When a phrase is referenced by only one chain entry across the entire song, the compiler inlines its bytecode directly into the track. This produces the most compact output.

When a phrase is referenced by two or more entries (in the same chain or across different chains), the compiler emits the phrase as a subroutine. Each reference becomes an `F8 CALL` instruction, and the phrase data ends with an `E3 RETURN`. This avoids duplicating the bytecode while preserving the exact playback behavior.

### Transpose Compilation

When a chain entry has a non-zero transpose, the compiler emits an `E9 KEY_DISP` command with the transpose value before the phrase data, then emits another `E9 KEY_DISP 00` after it to reset the displacement. This shifts all note pitches in the phrase by the specified number of semitones.

### Loop Compilation

When a chain entry has a repeat count greater than 1, the compiler wraps the phrase reference in an `F7 LOOP` command. The loop index and count are encoded in the bytecode, causing the sequencer to replay that section the specified number of times.

## Tips

- Use shared phrases to keep your song compact. Write a bass riff once, then reference it from multiple chain positions with different transpose values to build chord progressions.
- Set the chain loop point to the second entry to create an intro that plays once followed by a looping body. The first entry plays on the first pass, then playback loops back to the second entry and never revisits the intro.
- Think of phrases as building blocks and chains as the blueprint. Keep phrases focused on a single musical idea, then combine them in the chain with transpose and repeat to construct the full arrangement.
- The **Song View** gives you a bird's-eye view of the entire arrangement. Use it to see how all 10 channels relate to each other and to quickly jump between channels.
