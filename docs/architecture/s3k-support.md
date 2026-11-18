# S3K representation and playback coverage

The locked-on S3&K music importer preserves tracks and their dependencies.
Versions 1–4 provide the
musical arrangement workflow. [Version 5](native-bank-source.md) keeps original
bank bytes and addresses for native editing, inspection, bounded playback and
export. Retail coverage requires whole-track tests of the reached semantics.

This matrix covers the new `project-core` / `headless` / `studio` path. The legacy
`app` and `synth-core` are separate and do not confer support on the new editor.
Playback uses the exact OpenGGF revision recorded in
[openggf-audio.lock.json](../../upstream/openggf-audio.lock.json).

## Current application boundary

| Feature | Source and compiler | Render/export boundary | Editor | Pinned engine |
| --- | --- | --- | --- | --- |
| FM1–FM5 and PSG1–PSG3 notes/rests | Supported, monophonic linked sequences and finite inline clips | Supported | Timeline and piano roll | Supported |
| Long sustained notes | Native HOLD continuations beyond 127 ticks | Supported | Note duration | Supported |
| Pan/AMS/FMS, detune, absolute volume, FM/PSG volume change, absolute/additive transpose | Nine-command v2 catalogue below | Native finite stream | Commands table and raw-byte inspector | `E0` retains low six bits in FM and DAC |
| Two-operand FM volume change `E5` | v5 native bytes | v5 preflight follows both operands | Native bank bytes and event list | First operand ignored, second is signed FM attenuation delta |
| Four-byte custom modulation and modulation off | v2; setup restricted to explicit note attack ticks | `F0` and `FA` accepted with exact arities | Command help and arguments | Pending setup and native attack preparation |
| Event ordering and repetition state | Stable authored order at equal ticks; RESET/CARRY; independent clips reset | Compiles inline; trimmed events omitted | Reorder and repeat-state controls | Native mutable track state |
| Local FM voices | Exact 25-byte entries; source selection and pure asset CRUD | Terminal local table; package manifest indices | Native algorithm/feedback, four stored operators, independent TL high-bit flags, raw bytes | Local/shared/global lookup |
| Voice changes and shared/global/cross-song voices | v5 records the in-bank voice table location or supplies an ordered global table | v5 resolves local FM `EF` and PSG envelope `EF`; cross-song FM voices pending | Native bank view and global voice editor | PSG `EF` preserves cursor and consumes its signed-index operand shape |
| PSG noise | v5 native `F3` bytes | Canonical latch or zero passes preflight | Native bank bytes and event list | PSG noise/reset implemented |
| PSG and modulation envelopes | v3 bounded assets; v5 accepts full range and resolved modulation control operands | Tables installed before sequencer creation; native resource package | Asset CRUD/raw bytes, source assignments and editor links | Native PSG volume byte, signed modulation and cursor-zero paths |
| FM volume envelope and SSG-EG | Missing | `FF05/06` rejected | Missing | Handlers present; FM volume envelope uses PSG-envelope map |
| DAC samples, mappings and rates | v4 linked one-shot hits; v5 sparse, per-note PCM/rate bindings | Native DAC pan, rest replay and sample tail; exact mapping and ZIP | Sample library, waveform and DAC hit grid; v5 bank display | Unsigned PCM via `DacData`, base cycles 297 |
| FM6 and DAC/FM6 ownership | Dedicated DAC lane, FM6 switching pending | DAC + five FM slots | DAC lane | DAC shares physical FM6 |
| FM3 special/raw-frequency data | Missing representation | `FD/FE` rejected | Missing | Raw frequency handler exists; `FE` retains state only |
| Explicit note attacks, ties, note fill, duration reuse and zero-counter wrap | v5 preserves raw stream | Native saved duration, byte wrap, divider and fill through HOLD | Native bank bytes and event list | Timers follow the S3K byte counters and chip-specific tails |
| Native jumps, calls, returns and counted loops | v5 preserves native pointers | Bounded `F6/F7/F8/F9` traversal includes pre-header targets and loops that advance time | Native bank bytes and event list | Native return/loop capacity and zero-count limitations below |
| Conditional loop exit | Missing | `EB` rejected | Missing | Native arity/behavior mismatch below |
| Header transpose/volume/envelope state and bank addresses | v5 stores the exact header and offset at base 0 or 0x8000 | Seventh declared FM slot is inactive when PSG1 overwrites it | Native bank bytes; arrangement has normalized headers | Header decoder and full bank address space |
| Tempo changes, dividing timing, PAL and speed changes | v5 preserves divider and `FF00` tempo changes | Native frame-limited NTSC preview at 48 kHz; `FF04` rejected | Explicit preview length; arrangement keeps its fixed-tempo playhead | Tempo/divider and session timing support exist |
| Fade/restore, spindash state and continuous SFX | v5 preserves context commands as bytes | Explicit CLI previous-song context for `E2 FF`; isolated preview accepts non-FF `E2`; `E9/FC/FF07` remain gated | Native bank bytes | Session resumes the saved music at the next presentation |
| Sound dispatch, music halt and memory copy | Missing | `FF01/02/03` rejected | Missing | Syntax consumed only; semantic support absent |
| Dependency-complete package for resolved assets | v5 bank, voices and exact bindings | Deterministic ZIP of source, exact bank, manifest and hashed resources | Export package; resource-aware preview/WAV | Adapter installs resources; in-engine ZIP loader pending |

Version 1 remains readable/writable with its original field set and compilation.
Version 2 adds ordered command events and repeat state. Version 3 adds typed envelope
assets and stable source assignments. Version 4 adds DAC samples and one-shot
triggers in the first native track slot. Voice-only editing preserves the source version.
Version 5 stores native instructions and resources outside the supported playback
profile for inspection and export. Opening a document preserves its source version.

## Delivered command catalogue

Arguments are unsigned raw bytes `00`–`FF`; signed operations use their native
byte interpretation. Same-tick commands execute in source list order.

| Name | Bytes | Tracks | Meaning and material state |
| --- | --- | --- | --- |
| `PAN` | `E0 pp` | FM | Replace pan bits 7–6 and OR in low bits, including AMS and FMS |
| `DETUNE` | `E1 dd` | FM/PSG | Signed frequency offset |
| `VOLUME` | `E4 vv` | FM/PSG | Absolute loudness: 0 silent, 127 loudest; PSG quantizes; bit 7 ignored |
| `FM_VOLUME` | `E6 dd` | FM | Signed attenuation delta, clipped to 0–127; positive is quieter |
| `PSG_VOLUME` | `EC dd` | PSG | Byte-wrapped attenuation add, unsigned clamp to 15; also changes rest/envelope state |
| `TRANSPOSE` | `ED xx` | FM/PSG | Set signed transpose to `xx - 40h`; affects a subsequently read pitch |
| `TRANSPOSE_ADD` | `FB dd` | FM/PSG | Add signed transpose with byte wrapping |
| `MODULATION` | `F0 delay rate delta steps` | FM/PSG | Pending custom setup; zero rate means 256; delta signed; initial steps halved |
| `MODULATION_OFF` | `FA` or `FA F4 00` | FM/PSG | Clear custom modulation and, when the source selects an envelope, clear that envelope too |

`F0` may currently occur only at an explicit note attack. Setting it during HOLD
has native history semantics that need the later stream/gate model. This is a
temporary authoring restriction. It is not silently moved to a different tick.

Commands are read only when the current duration expires. The compiler therefore
splits notes/rests at event ticks. A sounding note continues as `commands; E7;
duration`, without re-reading its note byte: `B1 06 E0 80 E7 06`. A bare duration
would re-attack; repeating `B1` would recalculate the pitch after transpose.
HOLD preserves the running fill and envelope/modulation counters. Explicit fill
and attack control are available in the native stream.

For v2, RESET restores state each repetition; CARRY resets on clip entry and
preserves state between repetitions of that clip. Every independent clip resets
voice, volume, FM stereo pan, transpose, detune, fill and modulation. Native
`E0` retains the previous low six bits, including AMS and FMS. V1 keeps its
original reset bytes. No full-bank expansion is allocated for enormous timelines; output
is bounded to one 32 KiB bank.

The raw binary validator intentionally retains its older finite subset (`E3/F2`
stops, `E7/E8`, local `EF`, PSG `F3`, and the accepted single-byte commands).
Accepting these raw inputs does not make them editable project features. Four-byte `F0`, zero-byte `FA` and resolved one-byte `F4/F5` are supported.
Byte-only input rejects nonzero unresolved resources; the resource-aware path validates
every declaration, even unused ones. Unknown/meta commands,
pointers, unmapped DAC triggers, missing operands and unresolved resources still fail
before PCM output.

## Delivered voice and envelope assets

FM parameter edits preserve all unedited native bits and stored operator order.
TL bit7 independently enables track attenuation; algorithm edits do not recompute
those flags. Native/raw forms are separate explicit drafts. Create/duplicate/
rename/edit and deletion of unused assets use the same undo/live-file path.

The initial envelope profile accepts PSG data 00..0F and modulation data 00..7F,
plus80 reset and81/83 rest/hold. PSG81 retains volume while83 silences; modulation
81/83 hold the current frequency offset. Programs 1..256 bytes end explicitly in
80/81/83 and cannot start80. Native IDs are allocated independently: PSG 1..255,
modulation 1..127;128 is reserved for custom modulation. Source RESET/CARRY sets
F5/F4 at its established reset boundaries, including zero to clear previous state.

Branch82, multiplier84 and negative modulation offsets85..FF are explicitly
rejected until their native/pinned behavior is resolved. These are temporary
limits, not complete envelope support. The driver’s unbounded PSG immediate loop
makes validation necessary before injection. See the
[native package contract](native-asset-package.md) for every exported resource,
hash, stable ID/native index and engine adapter boundary. The archive does not
have an OpenGGF loader yet and does not establish arbitrary-track export support.

## Delivered DAC playback

The single DAC lane compiles one-shot hits to the first S3K header slot.
Triggers select unsigned 8-bit mono PCM using a custom note map. The current
asset package covers one rate byte per asset at native base cycles 297, with
rate 0 retaining its loop wrap. Rest units leave the current sample playing.
A later hit replaces it. Final stop includes a conservative playback bound
for the last included hit. The pinned driver supports fixed stereo DAC in
the v4 arrangement workflow. v5 banks can issue native `E0` pan on DAC.
FM6 switching and other DAC control flags remain tracked below.

## Pinned engine limits and native compatibility candidates

The [S3K handler](../../openggf-audio/src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java),
[sequencer](../../openggf-audio/src/main/java/com/openggf/audio/smps/SmpsSequencer.java),
[data adapter](../../openggf-audio/src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kSmpsData.java)
and [renderer](../../headless/src/main/java/com/opensmpsdeck/headless/S3kRenderer.java)
are the execution authority for this delivery. The following observations must be
resolved by focused native comparison and an upstream change/re-pin where needed;
an editor field alone cannot supply missing engine semantics.

| Area | Observed pin behavior | Native reference / required follow-up |
| --- | --- | --- |
| `EB` conditional jump | Advertises three operands but consumes four: index, comparison count, LE target | Native is index + LE target, testing whether decrement would reach zero. Do not accept native EB streams until resolved |
| High-bit FM `EF` | Consumes one index | Native FM high-bit index consumes a second source-song-bank operand. Cross-song FM references remain gated |
| `F7` count zero | Unconditional jump | Native byte counter wraps to 256 iterations; explicit fixture required |
| `EE` direct FM write | Uses current track's FM port | Native always writes part I; do not expose as a faithful generic register write |
| `E3` silence stop | Stops/mutes, without native full FM silence burst | Native retail path has extra chip writes, including an unusual PSG/FM interaction |
| `FE` FM3 special mode | Consumes four operands and retains a special-mode bit; no special-frequency execution | Native routine is broken and can corrupt driver code. Define a deliberate represent/render/export contract rather than claim implemented frequency behavior |
| `FF01/02/03` | Consume 1/1/3 operands only | Sound dispatch, all-music halt/resume and stream memory mutation are not implemented |
| Other envelope code-memory reads | The loader resolves reached modulation `82/84` operands from each cartridge's driver image | Arbitrary cursor entry and unpublished PSG control meanings remain outside coverage. Preflight bounds immediate control work |
| Envelope/control-flow safety | Envelope processing is bounded; raw main track execution depends on caller preflight | Bank traversal bounds immediate work, return depth and loop slots |

OpenGGF's local research inventory finds no shipped loader-supported route to
`FF01/02/03`; the handler documents that boundary. Local native-source inventories
also report unused defined paths such as `EB`, `EE`, `FD`, `FE`, and `FF04/05/06`.
These are **defined features or native limitations to track**, not permanently
waived requirements. Reachability distinguishes the shipped-song acceptance set
from the broader defined-command set; neither set may be advertised as complete
by counting recognized opcode bytes. Unknown `FF` subcommands remain unsupported.

Native references inspected during design are OpenGGF's local
`docs/skdisasm/Sound/Z80 Sound Driver.asm`: `zCoordFlagSwitchTable` (2948),
`cfConditionalJump` (3247), `cfSetVoice` (3345), `cfRepeatAtPos` (3613),
`cfToggleAltFreqMode` (3746), `cfFM3SpecialMode` (3771), and
`zExtraCoordFlagSwitchTable` (2982). The compatibility candidates above are not a
claim that this editor has independently certified arbitrary native-console audio.

## Retail verification

The locked-on music catalogue passes strict traversal with resolved dependencies
and 1-Up restoration context. Whole-song native PCM and register comparisons,
other cartridge variants and unreached commands remain tracked as separate
verification work. [Re-pin results](../validation/2026-11-17-s3k-repin.md)

## Evidence for the command lane

`NativeCommandsPlaybackTest` compares compiled v2 projects with independently
handwritten finite native banks through the production S3K session. Comparisons
include PCM and presentation-stamped resolved chip writes, explicit event timing,
and FM key-on counts. Fixtures cover midnote pan/transpose/volume, authored
same-tick order, modulation, RESET/CARRY, independent clips and CLI source
validation/compilation/rendering. Truncated `F0` operands and unsupported streams
must fail before any PCM is delivered. Project-core and Studio tests cover source
round trips, validation, edits, persistence and UI integration separately.

These checks establish the delivered bounded behavior against the pinned core.
Extraction parity, broader native fidelity, dependency completeness and all-track
coverage are different obligations and are not inferred from this test passing.

Voice/asset evidence is recorded in [the validation report](../validation/2026-11-17-voices-assets.md).
Asset tests independently compare PCM/frame-stamped writes, native slot register
placement and TL-bit7 effects; package tests cover every declared resource, hashes,
source round trips, deterministic bytes and refusal to overwrite outputs.
