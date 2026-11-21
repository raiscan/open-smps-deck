---
name: smps-bytecode-helper
description: Assists with SMPS bytecode encoding, decoding, and test binary construction
tools:
  - Read
  - Glob
  - Grep
  - Bash
---

# SMPS Bytecode Helper

Identify the driver dialect and address space before decoding. OpenSMPSDeck
targets Sonic 3 & Knuckles (S3K) and uses the pinned OpenGGF driver.

- For headers and voice lookup, read
  [Sonic3kSmpsData](../../openggf-audio/src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kSmpsData.java).
  A Z80 Type 2 music header has a little-endian voice pointer, FM count
  (including DAC), PSG count, timing divider and tempo. Four-byte DAC/FM
  entries and six-byte PSG entries follow. Resolve track and branch pointers
  in the active address space. An addressed bank can target data before the
  song header. A standalone fixture can use base-zero offsets.
- For notes, duration reuse, and pointer reads, check
  [SmpsSequencer](../../openggf-audio/src/main/java/com/openggf/audio/smps/SmpsSequencer.java).
  Tonal streams use `80` for a rest and start the note range at `81`.
  Interpret duration bytes through the track's timing and check DAC and raw
  frequency modes separately. Use a track-end flag to terminate a fixture.
- For S3K flags and parameter consumption, read the
  [handler](../../openggf-audio/src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java).
  `E3` mutes/ends a track, `E9` has no parameter, and `F2` ends a track.
  `F1` takes two modulation-envelope parameters and `F4` takes one.
  `F6` jumps, `F7` loops, `F8` calls and `F9` returns. `FA` disables
  modulation. Read the handler for conditional operands and `FF` subcommands.
- Preserve the 25 native FM voice bytes in parameter-group order. The
  [project voice codec](../../project-core/src/main/java/com/opensmpsdeck/project/NativeFmVoice.java)
  maps between the stored order and editable operator parameters.

For test binaries, use synthetic tracks and assets. Verify pointer targets,
operand lengths, and track termination, then validate the fixture through
the [headless CLI](../../headless/README.md#run). Check the
[S3K support matrix](../../docs/architecture/s3k-support.md) when choosing
between the arrangement compiler and native bank playback.
