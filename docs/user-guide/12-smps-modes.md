# SMPS Modes

The SMPS mode determines which Sonic game's sound driver your song targets.

## Overview

Select the SMPS mode from the **Mode** dropdown in the **Transport Bar**. The mode
affects tempo interpretation, sequencer behavior (modulation, volume scaling, PSG
envelopes), and binary export format. Choose the mode that matches your target game
before you start composing.

## The Three Modes

| Mode  | Target Game           | Driver     | Tempo Mode | Pointers         |
|-------|-----------------------|------------|------------|------------------|
| `S1`  | Sonic 1               | 68k SMPS   | TIMEOUT    | PC-relative      |
| `S2`  | Sonic 2               | Z80 SMPS   | OVERFLOW2  | Absolute (Z80)   |
| `S3K` | Sonic 3 & Knuckles    | Z80 SMPS   | OVERFLOW   | Absolute (Z80)   |

**S1** runs on the 68000 CPU with countdown-based tempo (TIMEOUT). The driver
extends all track durations by one frame each time the countdown reaches zero,
and uses PC-relative pointers in the binary.

**S2** runs on the Z80 with accumulator-overflow tempo (OVERFLOW2). A higher
tempo value produces faster playback. Pointers are absolute Z80 addresses.

**S3K** also runs on the Z80 but uses the original OVERFLOW tempo algorithm,
where a higher tempo value produces *slower* playback. S3K adds BIT7 volume
mode, looping PSG envelopes, and hold-based note-on prevention -- making it the
most capable variant.

## Which Mode to Choose

- **Composing for a Sonic 1 ROM hack** -- use `S1`.
- **Composing for a Sonic 2 ROM hack** -- use `S2`.
- **Composing for a Sonic 3K ROM hack** -- use `S3K`.
- **General-purpose composition or experimentation** -- use `S3K`. It is the
  most capable mode and the most widely supported by community tools.

When in doubt, start with `S3K`.

## Tempo Behavior

Each mode interprets the tempo value differently:

- **TIMEOUT** (`S1`): The tempo value is a countdown timer. When it reaches
  zero, every track's current note is extended by one frame, then the counter
  reloads. Lower values produce more frequent extensions (slower playback).
- **OVERFLOW2** (`S2`): The tempo value is added to an 8-bit accumulator each
  frame. On overflow, the sequencer advances (ticks). Higher values overflow
  more often, producing faster playback.
- **OVERFLOW** (`S3K`): The tempo value is added to an 8-bit accumulator each
  frame. On overflow, the sequencer *skips* (delays) the tick. Higher values
  cause more skips, producing slower playback.

Because of these differences, tempo `N` in one mode will not produce the same
speed in another mode. If you switch a song's SMPS mode, adjust the tempo value
to compensate.

## Tips

- **S3K is the safest default for new compositions.** It has the richest feature
  set and is the most common target for community projects.
- **When importing an SMPS file from a specific game, match the mode to that
  game.** An S1 `.bin` imported under `S3K` mode will play at the wrong tempo
  and may misinterpret coordination flags.
