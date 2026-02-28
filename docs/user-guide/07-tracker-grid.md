# Tracker Grid

The tracker grid is where you compose music -- entering notes, setting instruments, and editing patterns across all 10 channels.

## Overview

The tracker grid is a decoded view over raw SMPS bytecode. Ten channels are displayed side by side as vertical columns, with rows scrolling downward. Each row represents one event in the pattern -- a note, rest, tie, or coordination flag. What you see in the grid is exactly what the SMPS sequencer will play back and what gets written to the exported binary.

The grid occupies the central area of the application window. Row numbers are displayed in hex along the left edge. Every fourth row has a slightly lighter background to help you keep your place. The cursor row is highlighted with a distinct background color, and a thin underline marks which sub-column (Note, Instrument, or Effect) is active.

## Channel Layout

The ten channels span the full width of the grid from left to right:

| Index | Name  | Type   | Hardware          |
|-------|-------|--------|-------------------|
| 0     | FM1   | FM     | YM2612 Ch1        |
| 1     | FM2   | FM     | YM2612 Ch2        |
| 2     | FM3   | FM     | YM2612 Ch3        |
| 3     | FM4   | FM     | YM2612 Ch4        |
| 4     | FM5   | FM     | YM2612 Ch5        |
| 5     | DAC   | DAC    | YM2612 Ch6        |
| 6     | PSG1  | Tone   | SN76489 Tone 1    |
| 7     | PSG2  | Tone   | SN76489 Tone 2    |
| 8     | PSG3  | Tone   | SN76489 Tone 3    |
| 9     | Noise | Noise  | SN76489 Noise     |

Channel names appear in the header bar above the grid. Vertical separator lines divide each channel. The header bar also serves as the click target for mute and solo operations (see [Channel Mute and Solo](#channel-mute-and-solo) below).

## Columns

Each channel displays three sub-columns per row:

- **Note** -- the note name and octave (e.g. `C-4`, `F#5`), or `---` for a rest, or `===` for a tie. Notes are displayed in a light blue-white color. Rests appear in a muted grey-blue. Ties appear in a soft green. On the DAC channel, notes display as three-letter sample name abbreviations in purple instead of pitches.
- **Instrument** -- a two-digit hex index (`00`--`FF`), displayed in a green-tinted color. For FM channels this is the voice bank index. For PSG channels this is the envelope index.
- **Effect** -- coordination flags such as pan, modulation, or loop commands, displayed in an orange-tinted color. Long effect strings are truncated to fit the column width.

Duration is encoded in the underlying bytecode following each note byte. It is not shown as a separate column -- it controls how many frames the note sustains before the sequencer advances.

Empty rows (rows beyond the end of a channel's data) display as `... .. ....` in dark grey to indicate that no data is present.

## Note Entry

Press a note key while the cursor is in the **Note** column to insert that note at the current row. The note uses the currently selected octave and the current default duration. The cursor advances down one row after entry.

The keyboard follows a piano layout across two rows:

```
 2  3     5  6  7
Q  W  E  R  T  Y  U     <- octave +1
C# D#    F# G# A#
 C  D  E  F  G  A  B

 S  D     G  H  J
Z  X  C  V  B  N  M     <- current octave
C# D#    F# G# A#
 C  D  E  F  G  A  B
```

The lower row (`Z`--`M`) enters notes in the current octave. The upper row (`Q`--`U`) enters notes one octave higher. The keys between the note keys (`S`, `D`, `G`, `H`, `J` on the lower row and `2`, `3`, `5`, `6`, `7` on the upper row) enter sharps and flats.

For example, if the current octave is 4:

- `Z` enters `C-4`
- `S` enters `C#4`
- `X` enters `D-4`
- `Q` enters `C-5`
- `3` enters `D#5`

If an instrument is selected in the **Instrument Panel**, the corresponding instrument change command is prepended to the note automatically. For FM channels this inserts a voice change; for PSG channels it inserts an envelope change.

### DAC Channel

When the cursor is on the DAC channel (index 5) and the song has at least one DAC sample loaded, note entry behaves differently. The same keyboard keys map to sequential sample indices instead of musical pitches. Each key triggers the sample at the corresponding index:

- Lower row: `Z`=0, `S`=1, `X`=2, `D`=3, `C`=4, `V`=5, `G`=6, `B`=7, `H`=8, `N`=9, `J`=10, `M`=11
- Upper row: `Q`=12, `W`=13, `E`=14, `R`=15, `T`=16, `Y`=17, `U`=18

DAC notes are displayed in the grid using the first three characters of the sample name (uppercased) rather than a pitch value. If the sample has no name, a fallback label of `D` followed by a two-digit hex index is shown (e.g. `D00`, `D05`).

See [DAC Samples](06-dac-samples.md) for more details on importing and managing DAC samples.

## Rests and Ties

- `.` (period) inserts a rest (`---`). The rest silences the channel for the current default duration. Like note entry, the cursor advances down one row after inserting a rest.
- Tie (`===`) sustains the previous note without re-keying the channel. This extends a note's sound across multiple rows without retriggering the FM operator envelopes or PSG volume envelope.

Rests and ties are fundamental to SMPS sequencing -- every frame must have data, so "empty" rows are actually rests.

## Octave Selection

Use the function keys to jump directly to an octave:

| Key   | Octave |
|-------|--------|
| `F1`  | 1      |
| `F2`  | 2      |
| `F3`  | 3      |
| `F4`  | 4      |
| `F5`  | 5      |
| `F6`  | 6      |
| `F7`  | 7      |
| `F8`  | 8      |

You can also adjust the octave incrementally:

| Key           | Action               |
|---------------|----------------------|
| `Page Up`     | Increase octave by 1 |
| `Page Down`   | Decrease octave by 1 |
| `Ctrl+Up`     | Increase octave by 1 |
| `Ctrl+Down`   | Decrease octave by 1 |

The octave range is 0 through 8. The current octave affects all subsequent note entry on both keyboard rows. The default octave is 4.

## Instrument Column

Navigate to the **Instrument** column of any row using the `Left` and `Right` arrow keys, then type two hex digits (`0`--`9`, `A`--`F`) to set the instrument index.

The entry process works in two stages:

1. Type the first hex digit. It appears in yellow as a pending value with an underscore placeholder for the second digit (e.g. `3_`). The pending state is a visual indicator that the entry is incomplete.
2. Type the second hex digit. The full two-digit value is committed to the row (e.g. `3A`), and the appropriate coordination flag is written into the bytecode.

Press `Escape` at any time while a digit is pending to cancel without applying any change.

For FM channels (0--5), the instrument value sets the FM voice index via the SET_VOICE coordination flag. For PSG channels (6--9), it sets the PSG envelope index via the PSG_INSTRUMENT coordination flag.

Any navigation action (arrow keys, `Tab`, or other non-hex key) also cancels a pending hex digit.

## Navigation

| Key                | Action                                              |
|--------------------|-----------------------------------------------------|
| `Up`               | Move cursor up one row                              |
| `Down`             | Move cursor down one row                            |
| `Left`             | Move cursor to the previous sub-column              |
| `Right`            | Move cursor to the next sub-column                  |
| `Tab`              | Jump to the **Note** column of the next channel     |
| `Shift+Tab`        | Jump to the **Note** column of the previous channel |

Each channel has three sub-columns: **Note**, **Instrument**, and **Effect**. The `Left` and `Right` keys move between these sub-columns within a channel. At the boundaries, navigation wraps to the adjacent channel:

- Pressing `Right` on the **Effect** column of a channel moves to the **Note** column of the next channel.
- Pressing `Left` on the **Note** column of a channel moves to the **Effect** column of the previous channel.

At the edges of the grid (first column of FM1 or last column of Noise), the cursor stops and does not wrap further.

`Tab` and `Shift+Tab` always land on the **Note** column, providing a fast way to hop between channels. `Tab` wraps from the last channel (Noise) back to the first (FM1), and `Shift+Tab` wraps in the opposite direction.

## Selection

| Key              | Action                            |
|------------------|-----------------------------------|
| `Shift+Up`       | Extend selection up one row       |
| `Shift+Down`     | Extend selection down one row     |
| `Shift+Left`     | Extend selection left one channel |
| `Shift+Right`    | Extend selection right one channel |
| `Ctrl+A`         | Select all rows across all channels |

The selection begins from the cursor position when you first press `Shift` with an arrow key. Continuing to hold `Shift` and pressing arrow keys extends the selection rectangle. The selection is a rectangular region spanning a range of rows and a range of channels, highlighted in a semi-transparent blue overlay.

Press any arrow key without `Shift` to clear the selection and return to single-cell cursor mode.

`Ctrl+A` selects every row in every channel of the current pattern. This is useful for whole-pattern operations like transposing an entire pattern or copying a full pattern to paste elsewhere.

## Copy, Cut, Paste

| Key       | Action                                        |
|-----------|-----------------------------------------------|
| `Ctrl+C`  | Copy the current selection to the clipboard   |
| `Ctrl+X`  | Cut the current selection (copy, then delete) |
| `Ctrl+V`  | Paste from the clipboard at the cursor        |

### Copy and Cut

`Ctrl+C` copies the selected rows and channels to an internal clipboard. The clipboard stores the raw track data for each selected channel along with a reference to the source song (used for cross-song paste detection).

`Ctrl+X` performs a copy followed by a delete of the selected region. The deleted rows are removed from the pattern data and the remaining rows shift up to fill the gap.

### Paste

`Ctrl+V` inserts the clipboard data starting at the current cursor row and channel. If the clipboard contains data from multiple channels, it fills rightward from the cursor channel. Channels that would exceed the rightmost channel (Noise, index 9) are silently discarded.

Paste is an insert operation -- it pushes existing data downward rather than overwriting it. To replace data, delete the target rows first, then paste.

### Cross-Song Paste

When you paste data that was copied from a different song, the instruments referenced in the pasted data may not exist in the destination song. The system first attempts to auto-resolve instruments by matching names between the source and destination voice banks and envelope lists.

If any instruments remain unresolved, a **Resolve Instruments** dialog appears. The dialog is divided into two sections -- **FM Voices** and **PSG Envelopes** -- each listing the unresolved instruments by their hex index and name.

For each unresolved instrument, a dropdown offers three options:

- **Copy into song** (default) -- copies the instrument definition from the source song into the destination song's voice bank or envelope list. The reference in the pasted data is remapped to the new index at the end of the list.
- **Remap** -- maps the reference to an existing instrument in the destination song. The dropdown lists all available destination instruments with their hex indices and names (e.g. `Remap -> 03: BrightPiano`).
- **Skip** -- leaves the reference byte unchanged in the pasted data. The pasted data will reference whatever instrument already occupies that index in the destination song.

Click **OK** to apply your choices and complete the paste, or **Cancel** to abort the entire paste operation.

If all instruments are auto-resolved (every referenced instrument has a name match in the destination), the dialog does not appear and the paste proceeds silently.

## Transposition

| Key                    | Action                                |
|------------------------|---------------------------------------|
| `=` / `+`              | Transpose up 1 semitone               |
| `-`                    | Transpose down 1 semitone             |
| `Shift+=` (`Shift++`)  | Transpose up 1 octave (12 semitones)  |
| `Shift+-`              | Transpose down 1 octave (12 semitones)|

If a selection is active, transposition applies to all notes within the selected rows across all selected channels. Each note byte in the selection is shifted by the specified number of semitones. If no selection is active, transposition applies to the single note at the cursor position.

Rests and ties are not affected by transposition -- only actual note values are shifted. Notes that would be shifted outside the valid range are clamped to the nearest valid value.

Transposition of a multi-channel selection is recorded as a single undo step.

## Row Operations

| Key         | Action                                                    |
|-------------|-----------------------------------------------------------|
| `Insert`    | Insert an empty row (rest) at the cursor, pushing rows down |
| `Delete`    | Remove the row at the cursor                               |
| `Backspace` | Remove the row at the cursor and pull subsequent rows up   |

In SMPS, every tick must have data, so inserting an "empty" row actually inserts a rest with the current default duration. This is equivalent to pressing `.` (period).

`Delete` removes the data at the current row. The cursor stays at the same row position, and the row below it (if any) becomes the new current row.

`Backspace` removes the data at the current row and pulls all subsequent rows upward, also keeping the cursor at the same position. The effect is identical to `Delete` -- both remove one row from the track data.

Row operations affect only the current channel. To remove rows across multiple channels simultaneously, select the rows first, then use `Ctrl+X` to cut them.

## Channel Mute and Solo

Click a channel header to toggle mute on that channel. A muted channel's header text turns grey and displays a strikethrough line. Click the header again to unmute.

`Ctrl+Click` a channel header to solo that channel. The soloed channel's header turns gold, and all other channels are muted. `Ctrl+Click` the same channel header again to exit solo mode and restore the previous mute state.

The mute and solo system follows these rules:

- Solo overrides all individual mute states. When a channel is soloed, every other channel is silenced regardless of its individual mute setting.
- Exiting solo mode (by `Ctrl+Click`ing the soloed channel again) returns to the previous per-channel mute state.
- Only one channel can be soloed at a time. `Ctrl+Click`ing a different channel while one is already soloed switches the solo to the new channel.

Mute and solo state is respected during both real-time playback and WAV export. If you export while a channel is soloed, the exported file contains only that channel's audio.

## Undo and Redo

| Key       | Action                 |
|-----------|------------------------|
| `Ctrl+Z`  | Undo the last edit     |
| `Ctrl+Y`  | Redo the last undone edit |

Every edit to pattern data is recorded in the undo history. This includes note entry, rest insertion, row deletion, instrument changes, transposition, paste operations, and cut operations.

Multi-channel operations are grouped into a single undo step. For example:

- Transposing a selection that spans FM1 through FM3 records one undo step that reverts all three channels.
- Pasting multi-channel clipboard data records one undo step covering every affected channel.
- Deleting a multi-channel selection records one undo step for the entire deletion.

One press of `Ctrl+Z` reverts the entire grouped operation. `Ctrl+Y` re-applies it.

## Playback Controls

While the tracker grid has focus, the following playback shortcuts are available:

| Key       | Action                           |
|-----------|----------------------------------|
| `Space`   | Toggle play/pause                |
| `Enter`   | Play from the current cursor row |
| `Escape`  | Stop playback (when not in hex entry mode) |

These shortcuts mirror the **Transport Bar** controls but allow you to start and stop playback without leaving the grid.

## Tips

- `Ctrl+Click` a channel header to solo it while composing. This lets you hear a single channel in isolation without losing the mute state of other channels.
- Cross-song paste handles instrument differences automatically when matching names are found. For songs with shared voice banks, you can copy and paste freely between them without any dialogs.
- Transposition shortcuts work on selections -- select a passage across multiple channels and press `=` or `-` to shift everything together by a semitone, or `Shift+=` and `Shift+-` to shift by a full octave.

For the complete list of keyboard shortcuts, see [Keyboard Reference](11-keyboard-reference.md).
