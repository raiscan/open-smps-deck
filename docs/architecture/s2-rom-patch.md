# Sonic 2 World REV01 ROM patch

Test in game accepts the raw 1 MiB World REV01 image with SHA-1
`8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`. The patcher checks
the entire image hash and the expected layout and opcodes before writing
an expanded copy. CLI, MCP and Studio use the same engine. Editable
arrangements use the [S2 portable compiler](driver-support.md). S2 portable
packages supply their assembled bank, assets, relocation list and base.

## ROM layout

All ranges are half-open. File and line references identify the
Sonic 2 disassembly used to inspect the retail layout.

| ROM address or range | Purpose | Source |
| --- | --- | --- |
| `0x18E–0x190` | Big-endian checksum of every big-endian word from `0x200` to the end | `s2.asm:168–169,380–393` |
| `0x1A4–0x1A8` | Inclusive ROM end, set to `0x1FFFFF` | `s2.asm:173–174,383–390` |
| `0x3C54–0x3C58` | Replace the timer's `BEQ.W` with two NOPs for `--no-demo` | `s2.asm:4494–4508,4584` |
| `0x12EE2`, `0x135E2` | Two `MOVEQ #$99,D0` instructions. Rewrite their low bytes for `--slot ID --title-music` when ID differs from `0x99` | `s2.asm:26400,27158` |
| `0xEC028–0xEC036` | `4E F9 00 10 80 00 4E 71 4E 71 4E 71 4E 71`, jumping to the boot stub | `s2.asm:91428–91449` |
| `0xEC0E8–0xED04C` | Saxman driver payload, `0x0F64` bytes, decompresses to `0x1308` bytes | `s2.asm:91468–91476,91563–91572` |
| `0xF0000`, `0xF8000` | Retail music bank bases, banks `0x1E` and `0x1F` | `s2.asm:91619–91625,91717–91747` |
| `0x100000–0x108000` | New uncompressed song window, bank `0x20` | `s2.sounddriver.asm:173–178,1773–1808` |
| `0x108000` | Boot stub in the expansion, outside the song window | `s2.asm:91428–91449` |

The cartridge has no SRAM declaration. The disassembly's ROM addresses and
Z80 bank references stay within the original image. The checksum loop is
the path that reads through the expanded range, using the header's ROM end.
Unused expansion bytes are `FF`. Cold boot takes longer because the checksum
covers twice as many ROM bytes. Game initialization calls `SoundDriverLoad`
through one jump table entry (`s2.asm:420,1491–1494`). Boot from reset so
that the stub runs. The only title loop timeout exit is the branch at
`0x3C54`. The Start and menu paths are separate.

The 68000 retains the Z80 bus after Saxman decompression. The stub starts
with `05 13 66 FC` (`BTST D2,(A3)`, `BNE.S` back to the test), installs
each byte via `MOVE.B #value,$A00000+offset`, then replays the displaced
PAL probe and write
(`08 39 00 00 00 C0 00 05 56 F9 00 A0 00 07`). It jumps back with
`4E F9 00 0E C0 36` before the original reset assertion, bus release,
delay and reset release. The compressed driver in ROM stays intact.

## Playlist and bank resolution

The driver indexes `zMasterPlaylist` at Z80 `0x11F5` by music ID minus
`0x81`. Bit 7 selects bank `0x1E` (Continue, art and Sega PCM) or
`0x1F` (the other 30 songs). Bit 6 suppresses PAL double-updates. Bit 5
indicates uncompressed data. Bits 0–4 select a little-endian pointer at
`$8000 + 2 * index` in the chosen bank. The retail tables use indexes
0–29 in bank `0x1F` and index 0 in bank `0x1E`
(`s2.sounddriver.asm:1740–1802,3810–3857`, `s2.asm:91620–91624,91717–91747`).

One playlist byte becomes `3F | (oldFlags & 40)`. Both possible markers
use index 31, which is absent from the retail playlist. The patch keeps all
other playlist entries, all retail music pointers, and both stock banks.
The marker preserves the selected slot's PAL update policy and sets the
uncompressed flag. The zBGMLoad hook keeps the playlist byte in B for
stock flag processing. It stores selector 1 for the marker and otherwise
stores the retail flag (`00` or `80`) in `MusicBankNumber`.

| Z80 address | New bytes | Purpose |
| --- | --- | --- |
| `07A6` | `CD 6C 0C 32 96 1B` | Decode bank selector and store in `MusicBankNumber` |
| `07C5` | `CD 2A 13` | Resolve the song header |
| `0C66` | `87 38 10 C3 08 13` | Carry visits the original bank `1F` routine. Other values jump to the extension with Z set for selector 0 |
| `0C6C` | `47 FE mm 28 03 E6 80 C9 3E 01 C9` | Keep B and return the retail flag or marker selector, with the marker byte at `mm` |
| `1308` | `20 10` | Nonzero selector uses the bank `20` routine |
| `130A` | `AF 1E 01 21 00 60 77 73 73 73 73 77 77 77 77 C9` | Serial bank `1E` |
| `131A` | `AF 1E 01 21 00 60 77 77 77 77 77 73 77 77 77 C9` | Serial bank `20` |
| `132A` | `3A 96 1B 3D C2 63 0C CD 63 0C E1 E1 11 00 80 C3 CC 07` | Tail-call the stock bankswitch for retail. For custom, switch banks, discard the helper return and saved pointer-table address, set DE to `$8000`, then continue at the original flag pop |

The switch routines write nine bits to `$6000` LSB first. Bank `1E`
writes `0 1 1 1 1 0 0 0 0`, bank `1F` writes
`1 1 1 1 1 0 0 0 0`, and bank `20` writes `0 0 0 0 0 1 0 0 0`.
Each body costs 94 Z80 T-states, including RET. Counting from entry
to `zBankSwitchToMusic`, excluding the call and hardware waits, the
`1F` path stays at 123 T-states, the Continue path rises from 118
to 135 and the custom path takes 140
(`s2.sounddriver.asm:256–274,2837–2847`).

VBlank selects the music bank before processing BGM
(`s2.sounddriver.asm:397–401`). SFX uses its own bank and explicitly
restores the music bank for FM voice restoration (lines 1446–1459,
2311–2331, 3545–3556). Sega PCM temporarily selects bank `0x1E` and
then calls the music bankswitch (lines 1603–1664). DAC streams from
bank `0x1D` via the existing Z80 IRQ handoff, including the original
sample and rate tables (lines 493–525, 628–728, 805–814, 3878–3926).
Extra Life saves `zAbsVar` including `MusicBankNumber` with live track
state and restores both through DAC flag `E4` (lines 142–181, 1674–1709,
3082–3163). When replacing slot `0x98`, the patcher pads its DAC track
through the latest track end and inserts `E4` there. This requires a
finite song with divider 1.

## Addressing and assets

The helper passes the header address `$8000` directly to track initialization.
There is no pointer table in bank `0x20`, so all 32,768 bytes are addressable.
The install stub starts at the next bank boundary. The patcher relocates
little-endian header and validated `F6`/ `F7`/ `F8` operands by
`$8000 - base`, for declared base 0 or `$8000`. The scan follows reachable
commands, checks pointer and voice bounds and rejects conflicting instruction
boundaries or non-advancing loops. S2 subroutines return with `E3`
(`s2.sounddriver.asm:2888,3060–3079,3628–3699`). The optional generated
loop requires active tracks with aligned finite durations, without existing
branches. It rewinds relative volume and transposition at the loop boundary.
Extra Life requires a finite end for restoration. Speed shoes retain the
selected retail slot's fast tempo.

The extension occupies Z80 `[0x1308,0x133C)`. The Saxman song buffer begins
at `0x1380`, the stack grows down from `0x1B80` and live tracks and backup
data occupy `[0x1B80,0x1FF4)` (`s2.sounddriver.asm:177–223,1802`).
The remaining 68 bytes at `[0x133C,0x1380)` can hold a relocated PSG
pointer table with all 13 retail pointers and any new envelopes that fit.
One new table entry leaves 40 bytes for its envelope, including the
`80` hold terminator. The patched immediate is at `0x04D9–0x04DA` after
the `LD HL,nn` opcode at `0x04D8` (lines 1281–1304, 3724–3735).
Exact retail contours are reused. Overflow maps to the stock contour
with the lowest RMS attenuation error across 256 ticks. The patcher
remaps PSG header slots and `F5` operands.

DAC substitutes one of the 17 retail note/rate entries for each used
package drum. It decodes the actual stock samples locally and considers
spectrum, RMS envelope, duration and sample rate. Reports include the
original and mapped note, sound, rates, and distance. The boot stub leaves
the DAC tables and sample data intact. A shadow sample bank is a future
option that would need separate verification. See the
[compiler matrix](driver-support.md) for S2 arrangement limits, including
DAC/FM6 sharing, native modulation and PSG envelope behavior.

## Verification

The synthetic tests use instruction and asset fixtures. With
`OPENSMPSDECK_S2_ROM` set, the retail test decompresses the driver,
simulates the stub's writes and checks all 31 requests at each of the 31
replacement positions. It checks both marker forms, Switch-to-Music's
serial bank bits and the ROM-to-BPS round-trip. Use the
[ROM probe](../../tools/rom-probe/README.md) with `--profile s2` for
cold boot, title playback, idle and Start checks. The probe's RAM bank
field is the selector, so use `--bank 1 --song 0x99` for a title-slot
replacement. When a core cannot expose Z80 RAM, use its audio capture
to cross-check playback against the pinned S2 render.
