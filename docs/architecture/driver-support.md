# Portable package driver support

Portable schema 4 targets Sonic 1 (`s1`), Sonic 2 (`s2`) or
Sonic 3 & Knuckles (`s3k`, the default). Sonic CD's FM loader has no music
or DAC implementation and `driver: scd` is rejected. Projects retain their
`S3K` mode and editing clock. Target selection happens during portable or
mod export. See the [package contract](portable-package.md) for the bank
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
| Speed shoes | Optional native tempo byte | Optional native tempo byte | Native frame multiplier |
| ROM resources tested | REV00 and REV01 | REV00 and REV01 | Locked-on S3&K |

DAC bindings are runtime sample entries. The retail tables contain fewer
slots and use DPCM, while the portable session plays the supplied decoded
PCM without a ROM patch. At most 512 KiB of total PCM and one 32 KiB bank
are allowed. DAC occupies FM6, leaving five FM arrangement lanes. Outputs
use the chosen driver's native pitch table, chip-write ordering, envelope
behavior, timer and DAC rate. Export copies the arrangement's tempo byte to
the target header. A numeric tempo therefore has different wall-clock
timing across drivers. Studio still previews the editable source with S3K.

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
