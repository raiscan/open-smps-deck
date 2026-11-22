# Portable package driver support

Portable schema 4 targets Sonic 1 (`s1`), Sonic 2 (`s2`) or
Sonic 3 & Knuckles (`s3k`, the default). Sonic CD's FM loader has no music
or DAC implementation and `driver: scd` is rejected. Projects retain their
`S3K` mode and editing clock. Reconstruction can save a target before
analysis. Portable and mod export default to that target and accept an
explicit driver override. See the [package contract](portable-package.md) for the bank
and asset wire format.

| Arrangement or playback feature | S1 | S2 | S3K |
|---|---|---|---|
| Editable versions 1–4 and 6 | Supported | Supported | Supported |
| v5 native banks, global voices and native control flow | Rejected | Rejected | Existing S3K native profile |
| Compiled lanes | DAC, FM1–FM5, PSG1–PSG3 | Same | Same |
| Clip gaps, partial repeats, reset/carry state and nested groups | Linearized | Linearized | Linearized |
| Local FM table | Up to 128 voices, order 4/3/2/1 | Up to 128 voices, order 4/2/3/1 | Up to 128 voices, order 4/3/2/1 |
| FM carrier selection | Algorithm | Algorithm | TL high bit |
| Note pitch | MIDI 12–106 FM, 48–116 PSG | Same | MIDI 12–106 FM, 45–117 PSG |
| PSG volume envelope | Slots 1–255, `80` holds | Same | Slots 1–255, `80` loops, `81`/`83` rest |
| Modulation envelopes, FM volume envelopes, SSG-EG | Rejected | Rejected | Supported in existing arrangement profile |
| PCM DAC | 95 virtual bindings, rate base 301 | 95 virtual bindings, rate base 295 | 95 virtual bindings, rate base 297 |
| Normal tempo | Countdown. At expiry, extend track durations by one pass. Header 2–255 | Accumulator. No carry extends durations by one pass. Header 1–255 | Accumulator. Carry extends durations by one pass. Header 0–255 |
| Speed shoes | Optional S3K-clock input converted to a native byte | Same | Native frame multiplier |
| ROM resources tested | REV00 and REV01 | REV00 and REV01 | Locked-on S3&K |

DAC bindings are runtime sample entries. The retail tables contain fewer
slots and use DPCM, while the portable session plays the supplied decoded
PCM without a ROM patch. At most 512 KiB of total PCM and one 32 KiB bank
are allowed. DAC occupies FM6, leaving five FM arrangement lanes. Outputs
use the chosen driver's native pitch table, chip-write ordering, envelope
behavior, timer and DAC rate. The FM table orders above describe ROM storage
order. The pinned loaders and sequencer normalize the order for chip writes,
including the [Sonic1SmpsData load-time swap](../../openggf-audio/src/main/java/com/openggf/game/sonic1/audio/smps/Sonic1SmpsData.java)
and [Sonic3kSmpsData's raw `30,38,34,3C` traversal](../../openggf-audio/src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kSmpsData.java).

## Tempo conversion

Studio previews the editable source on the S3K clock. Portable and mod exports
convert its tempo byte `t` to a target header byte `b` by matching the long-run
number of duration ticks per NTSC frame.

| Driver | Load and duration rule in the pinned sequencer | Rate | Export byte |
|---|---|---|---|
| S3K (`OVERFLOW`) | The load service ticks unconditionally. The accumulator starts at `t`. Later services add `t` modulo 256 and hold the duration on carry | `1 − t/256` | `t` |
| S2 (`OVERFLOW2`) | The accumulator starts at `b` and adds `b` modulo 256 starting on the load service. Only a carry advances durations. Weight zero freezes music progression | `b/256` | `min(255, 256 − t)` |
| S1 (`TIMEOUT`) | The countdown starts at `b` and decrements starting on the load service. Reaching zero holds durations and reloads `b` | `1 − 1/b` | Nearest rate among countdowns 2–255, choosing the smaller byte for a tie |

For `n ≥ 1` frames from a cold load, the cumulative duration counts are
`n − floor(n·t/256)` on S3K, `floor((n+1)·b/256)` on S2 with `b > 0`, and
`n − floor(n/b)` on S1. S3K tempo zero ticks on every frame. Its nearest
classic bytes are both 255, each with a tick-rate error of −0.390625%.
At S2 weight zero the sequencer primes the tracks once and then freezes
duration progression, so exports use only 1–255.

S1 matches exactly when `256/t` is an integer in 2–255. It then holds the
same frames as S3K over the full period. S2 matches the rate for every
nonzero `t`. The cumulative counts match throughout the period when `t`
divides 256. For other bytes the counts can differ by one because the load
service and carry condition differ. For example, `0x1F` gives 225/256 on
S3K, matches that rate with S2 byte `0xE1`, and maps to S1 byte `0x08`
(224/256, or −0.444444%). These formulas describe duration timing. The track
walk can still update pitch, volume and envelopes on a held frame.

Inexact rates raise `TEMPO_APPROXIMATED` with both bytes and the signed
relative rate error `100 × (targetRate/sourceRate − 1)` as a percentage.
CLI `--strict-tempo` or MCP `strictTempo: true` rejects the export in that
case. For S1/S2 the optional speed-shoes input uses the same S3K byte scale
and conversion. The manifest stores the resulting native byte. Omitting the
input leaves the normal tempo in effect. The rate model applies once the
effect is active. Its transition phase depends on the driver's current
accumulator or countdown.

## Arrangement commands

| Arrangement command | S1 and S2 encoding and restrictions |
|---|---|
| PAN, DETUNE | `E0 byte`, `E1 byte` |
| VOLUME, FM_VOLUME | Compute the attenuation at compile time and emit signed `E6 delta`. S1 rejects changes that would overflow its TL write |
| PSG_VOLUME | `EC delta` with the source byte-wrap/clamp rule. Rejected while an envelope is selected because the S3K command also retriggers that envelope |
| TRANSPOSE, TRANSPOSE_ADD | Compute the relative difference or add the signed byte with `E9 delta` |
| HOLD | `E7`. Long notes and interrupted notes reuse duration-only units under hold |
| MODULATION, MODULATION_OFF | `F0 delay rate delta steps`, `F4`. Parameters have the target's native timing, including the S1 step halving |
| FM_VOICE, PSG_ENVELOPE | `EF slot`, `F5 slot`. PSG slot zero disables the envelope |
| PSG_NOISE | `F3 byte`, restricted to nonzero noise on PSG3. Resetting a noise track to tone or resetting its state on a subsequent clip or repeat is rejected. A tone must rest before switching to noise |
| MODULATION_ENVELOPE, FM_VOLUME_ENVELOPE, SSG_EG | Rejected with the target and command in the error |

The S1/S2 reader validates the finite stream subset, including explicit
note/rest durations, duration-only continuations and an `F2` stop on
every track. Retail loops, calls, and hardware/SFX-only commands have no
editable portable representation in those dialects. Headless preview tests
exercise NTSC activation and native writes. PAL, gameplay speed transitions,
SFX arbitration and retail ROM patch execution are outside these preview tests.

The pinned [Sonic1SmpsData](../../openggf-audio/src/main/java/com/openggf/game/sonic1/audio/smps/Sonic1SmpsData.java),
[Sonic2SmpsData](../../openggf-audio/src/main/java/com/openggf/game/sonic2/audio/smps/Sonic2SmpsData.java) and
[SmpsSequencer](../../openggf-audio/src/main/java/com/openggf/audio/smps/SmpsSequencer.java)
define headers, operators, note tables and coordination flags. The
[S1](../../openggf-audio/src/main/java/com/openggf/game/sonic1/audio/Sonic1SmpsSequencerConfig.java) and
[S2](../../openggf-audio/src/main/java/com/openggf/game/sonic2/audio/Sonic2SmpsSequencerConfig.java)
configurations select the respective tempo, modulation and envelope rules.
Relevant disassembly symbols are `InitMusicPlayback`, `TempoWait`,
`DAC_sample_rate` and `CoordFlag` in s1disasm's `s1.sounddriver.asm`. In s2disasm, see
`DecompressSoundDriver` in `s2.asm` and `zInitMusicPlayback`,
`TempoWait` and `zPSG_EnvTbl` in `s2.sounddriver.asm`. See also
[S3K support](s3k-support.md).
