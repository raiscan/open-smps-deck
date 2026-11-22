# Sonic 3 & Knuckles ROM patch export

The patched ROM is derived from the player's retail ROM and contains
copyrighted data. **Personal use only. Do not redistribute.**

The [headless command](../../headless/README.md#test-an-arrangement-in-game),
the MCP tool and Studio's Test in game action use a `RomTarget.Engine` adapter
over the same in-memory patcher. The ROM library supplies the verified clean
image. The output writer reapplies a staged BPS to that image and verifies
the result against the patched bytes before publication. The supported revision
is the verified 4 MiB locked-on image with SHA-1
`cfbf98c36c776677290a872547ac47c53d2761d6`. The patch engine checks the image
size and SHA-1, the original instruction and checksum bytes, and hashes of the
first and last eight bytes of every writable fragment. It outputs a 4 MiB image
with the original mapping.

## Free-space proof

ROM ranges are half-open. The revision constants describe eight fragments in
four S3 music banks. The table lists their total capacities before allocating
the stub and DAC payloads.

| Bank | Writable bytes | Location in the S3 music data |
| --- | ---: | --- |
| `0x58` | 3,667 | Miniboss and Final Boss copies |
| `0x59` | 5,090 | Flying Battery and Death Egg Zone copies |
| `0x5A` | 26,652 | From the standalone Mushroom Hill copy through Menu |
| `0x5B` | 8,012 | Five ranges starting at Slots, Knuckles, Title, Game Over and Game Complete |

The standalone `s3.lst` source lines 117952–117954, 117975–117977,
117989–118003, and 118011–118028 place those songs in consecutive includes.
No gameplay data occurs between consecutive song headers in those banks.
The locked-on layout in `Lockon S3/LockOn Data.asm` lines 1287–1333 only
names the music includes it reuses from the S3 half. The gaps among those
includes hold the standalone songs. The symbol and operand listings show
that the locked-on game code queues music IDs and derives bank and
pointer through the S&K driver's own tables. The SFX bank is in the S&K
half. The DAC playback path selects its independent bank and note table.
The startup entry selects the S&K driver. The standalone S3 driver's
tables are present in the second half for standalone operation.

The ROM-gated audit reads both music tables and walks channel instructions,
including jump, loop and call targets, and considers reachable local FM
voices. Consecutive standalone headers bound their respective includes,
including unreachable stop bytes and trailing voices. For a final include,
the audit uses the end of the reached data. It subtracts every locked-on song
extent, shared header, and retail DAC pointer, descriptor and sample range.
For example, bank `0x5A` starts its writable area at ROM `0x2D17A7` after
IceCap and Launch Base. The audit ends it at `0x2D7FC3` and leaves the
remaining bank padding intact. The audit also traverses the locked-on SFX
table for direct DAC triggers and checks alternate voice-table references.
Set `OPENSMPSDECK_S3K_ROM` to run it and the full-image diff test. Set the Java
temporary directory to private scratch when running the ROM-gated tests.

The exporter places the song header, channel streams, subroutines and local
voices in one 32 KB window. It relocates every absolute pointer and keeps
instruction fall-through contiguous. Total free bytes in a bank are an upper
bound when a contiguous piece needs a fragment of a particular size.
Shorten the song or reduce layering if placement fails.

## Driver hook, title sites, and checksums

`sonic3k.asm:362,418` call `SndDrvInit`. The routine loads the two read-only
Kosinski blocks and copies default RAM variables. The hook replaces the first
reset-pulse instruction at `0x1326` with `jsr absolute.l; nop`. The stub
polls bit 0 of the already asserted Z80 bus-request register `$A11100`,
using the same test as `sonic3k.macros.asm:93–103`. It writes only bytes to
Z80 RAM, performs the displaced reset write, and returns to the original
reset release and bus release sequence. These are the only loads of the
S&K compressed driver or its data tables.

The default title slot is `0x25`. A different slot with `--title-music`
updates the three title request immediates. `--no-demo` replaces the three
timer branches with NOPs. The sites are in the S3-style and S&K-style
title routines and the standalone S3 half. The boot checksum failure branch
is disabled in the retail game (`sonic3k.asm:378–407`). The exporter still
updates both header sums, each over the region from offset `0x200` to the
end of that header's declared 2 MiB cartridge range.

## DAC and envelopes

The stock DAC output is 4-bit DPCM. The exporter reads the revision's
16 decoded deltas at Z80 `0x1116` and minimizes total squared PCM error
starting at unsigned midpoint `0x80`. It preserves each sample's rate
byte. An odd PCM length requires one padding nibble. The report records
each note's RMS and peak error in unsigned 8-bit PCM units and SNR in dB
(with exact or zero-energy cases omitting finite SNR).

The 17 unused DAC note IDs were audited across the 51 locked-on music slots
and the complete SFX pointer range. In the driver, `zUpdateDACTrack` and
`cfPlayDACSample` queue these notes and `zPlayDigitalAudio` resolves each
descriptor through that note's bank. The first fragment in bank `0x5B`
contains every unused note's pointer-table offset. The exporter installs
descriptors and compressed samples in that bank and points only the unused
`DAC_Banks` entries there. It refuses an export if there are too many samples
or an encoded payload cannot fit.

A 189-byte gap from `0x1B43` to `0x1C00` follows the decompressed driver
tables and precedes runtime variables. The stub can install a new PSG
volume-envelope pointer table there, retaining all 39 stock targets and
adding pointers to new envelope bytes. Both PSG and FM volume processing
obtain the table through the master pointer at `0x130A`
(`Sound/Z80 Sound Driver.asm:1196,4102,4462`). PSG headers, envelope
commands and FM volume-envelope commands are remapped. A stock envelope
with identical bytes is reused. When RAM runs out, the nearest stock
attenuation contour is substituted and the report records the approximation.
Custom modulation envelopes require a byte-exact retail counterpart.

Looping uses an `F6` jump to each track's own start. The exporter checks
that every track reaches a finite `F2` stop on the same tick. Songs with
different channel lengths, dynamic tempo/divider commands, or unsupported
loop control are refused with an explanation.
