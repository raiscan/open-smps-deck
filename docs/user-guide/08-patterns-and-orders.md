# Patterns and Orders

Patterns hold blocks of channel data; the order list arranges them into a complete song.

## Overview

Composition in OpenSMPSDeck works at two levels. Patterns define what to play -- each one contains track data for all 10 channels. The order list defines when to play it -- a top-to-bottom sequence of rows that references patterns by index. Together, these two structures give you the full song.

## Patterns

A pattern is a container for 10 independent channels of SMPS track data:

| Channels 0--4 | Channel 5 | Channels 6--8 | Channel 9 |
|----------------|-----------|----------------|-----------|
| FM 1 -- FM 5 | DAC / FM 6 | PSG 1 -- PSG 3 | PSG Noise |

Each channel within a pattern stores its own raw SMPS bytecode -- notes, durations, coordination flags, and track-end markers. The channels are fully independent: editing one channel has no effect on the others.

Patterns are reusable. The same pattern can appear in multiple order list rows, so you write a passage once and reference it as many times as you need. Every new song starts with a single pattern (index `00`) containing 64 rows of empty track data.

## The Order List

The **Order List Panel** sits at the bottom of the song tab. It displays one row per playback position. Each row contains 10 hex values -- one pattern index per channel -- formatted like this:

```
00    00 00 00 00 00 00 00 00 00 00
01    01 01 01 01 01 00 00 00 00 00
02    02 02 02 02 02 01 01 01 01 01
```

The first column is the row number. The remaining 10 columns are pattern indices for FM 1 through PSG Noise, in channel order. Playback proceeds top to bottom: when the last row finishes, playback jumps to the loop point and continues from there.

Click any row in the **Order List Panel** to select it. The **Tracker Grid** immediately loads the patterns referenced by that row, letting you view and edit the track data for that position in the song.

## Adding Rows

Click the **+** button in the **Order List** toolbar to append a new row at the end of the list. The new row sets all 10 channels to pattern `00`.

If the song has no patterns yet, adding a row also creates pattern `00` automatically with 64 empty rows.

## Duplicating Rows

Click the **Dup** button to duplicate the currently selected row. The copy is inserted immediately after the selected row with the same pattern indices across all 10 channels. The selection moves to the new row.

Use **Dup** when you want a row that is mostly the same as an existing one. Duplicate it, then change only the channels that differ.

## Removing Rows

Click the **-** button to remove the currently selected row from the order list. At least one row must always remain -- the button does nothing if only one row exists.

If the loop point referenced a row at or beyond the new end of the list, it is automatically adjusted to the last valid row.

## Loop Point

Click the **Loop** button to set the loop point to the currently selected row. The loop point row is marked with a loop arrow indicator in the order list display.

When playback reaches the end of the order list, it jumps back to the loop point row and continues from there. This maps directly to the SMPS `F4` jump command in the exported binary. Every song has exactly one loop point; setting a new one replaces the previous one. The default loop point is row `00`.

## Per-Channel Pattern Assignment

Each of the 10 channels in an order row can point to a different pattern. This is the key to efficient song structure. Consider a three-row order list:

```
00    00 00 00 00 00 01 01 01 01 01
01    00 00 00 00 00 02 02 02 02 02
02    03 03 03 03 03 02 02 02 02 02
```

In this example, rows `00` and `01` reuse the same FM pattern (`00`) while the PSG channels change from pattern `01` to `02`. Row `02` then introduces a new FM pattern (`03`) while keeping the PSG arrangement from the previous row.

This lets you mix and match independently. Reuse a bass line across multiple rows while varying the melody. Keep a drum pattern steady while the harmony changes underneath. Each channel can follow its own path through the pattern pool.

## Tips

- Use **Dup** to create variations -- duplicate a row, then change just one or two channel indices to introduce a new melodic phrase over an existing rhythm section.
- Set the loop point to the second row to create an intro that plays once followed by a looping body. Row `00` plays on the first pass, then playback loops back to row `01` and never revisits the intro.
- Think of patterns as building blocks and the order list as the blueprint. Keep patterns small and focused on a single musical idea, then combine them in the order list to construct the full arrangement.
