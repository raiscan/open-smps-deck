# S3K representation and playback coverage

The delivery target is complete S3K track representation, editing, rendering and
dependency-complete export **before importing songs**. The current native-command
lane is one bounded step. Consuming an opcode's operands does not establish its
behavior, and supporting a handwritten demo does not establish full-track coverage.

This matrix covers the new `project-core` / `headless` / `studio` path. The legacy
`app` and `synth-core` are separate and do not confer support on the new editor.
Playback uses the unchanged OpenGGF snapshot pinned by
[openggf-audio.lock.json](../../upstream/openggf-audio.lock.json), revision
`a1c4aac7ae2708f5c487e8000da80502e854abe2`.

## Current application boundary

| Feature | Source and compiler | Render/export boundary | Editor | Pinned engine |
| --- | --- | --- | --- | --- |
| FM1–FM5 and PSG1–PSG3 notes/rests | Supported, monophonic linked sequences and finite inline clips | Supported | Timeline and piano roll | Supported |
| Long sustained notes | Native HOLD continuations beyond 127 ticks | Supported | Note duration | Supported |
| Pan/AMS/FMS, detune, absolute volume, FM/PSG volume change, absolute/additive transpose | Nine-command v2 catalogue below | Native finite stream | Commands table and raw-byte inspector | Supported within the pin's semantics |
| Two-operand FM volume change `E5` | Not offered in v2 | Raw binary rejected | Missing | First operand ignored, second is signed FM attenuation delta |
| Four-byte custom modulation and modulation off | v2; setup restricted to explicit note attack ticks | `F0` and `FA` accepted with exact arities | Command help and arguments | Pending setup and native attack preparation |
| Event ordering and repetition state | Stable authored order at equal ticks; RESET/CARRY; independent clips reset | Compiles inline; trimmed events omitted | Reorder and repeat-state controls | Native mutable track state |
| Local FM voices | Native 25-byte entries; sequence voice selection | Terminal local table, no conversion | Source voice selector; no operator editor | Local/shared/global lookup |
| Arbitrary voice changes, shared/global/cross-song voices | Missing references and resolution | No dependency bundle | Missing | Basic lookup available; native high-bit `EF` is a limitation below |
| PSG noise | Not represented in v2 | Existing raw binary `F3` remains accepted | Missing | PSG noise/reset implemented |
| PSG and modulation envelopes | Missing typed assets and references | Header IDs must be zero; no tables injected | Missing | `setPsgEnvelopes` / `setModEnvelopes`; `F1/F4/F5` |
| FM volume envelope and SSG-EG | Missing | `FF05/06` rejected | Missing | Handlers present; FM volume envelope uses PSG-envelope map |
| DAC samples, mappings and rates | Missing DAC source/lane and asset package | DAC slot must stop; renderer supplies empty `DacData` | Missing | PCM bytes plus note-to-sample/rate map; base cycles 297 |
| FM6 and DAC/FM6 ownership | Missing | At most stopped DAC + five FM slots | Missing lane | Seven-entry channel map available; DAC shares physical FM6 |
| FM3 special/raw-frequency data | Missing representation | `FD/FE` rejected | Missing | Raw frequency handler exists; `FE` retains state only |
| Explicit note attacks, ties, note fill, duration reuse and zero-counter wrap | Missing native stream/gate model | Raw `E7/E8` accepted; compiler generates its own holds; zero duration rejected | Missing | Behavior is stateful and is not equivalent to MIDI note lengths |
| Native jumps, calls, returns and counted loops | Source reuse currently expanded inline | Pointers rejected | Missing graph/stream view | `F6/F7/F8/F9`; stack and zero-count limitations below |
| Conditional loop exit | Missing | `EB` rejected | Missing | Native arity/behavior mismatch below |
| Header transpose/volume/envelope state and bank addresses | Compiler chooses normalized headers, base zero | Header/resource restrictions explicit | Missing native initial-state editor | Header decoder and full bank address space available |
| Tempo changes, dividing timing, PAL and speed changes | Fixed header tempo, dividing timing 1 | `FF00/04` rejected; 60 Hz NTSC, 48 kHz | Header tempo and approximate fixed-tempo playhead only | Tempo/divider and session timing support exist |
| Fade/restore, spindash state and continuous SFX | Missing execution context and external triggers | `E2/E9/FC/FF07` rejected; restore sink unavailable | Missing | Require session/shared runtime state |
| Sound dispatch, music halt and memory copy | Missing | `FF01/02/03` rejected | Missing | Syntax consumed only; semantic support absent |
| Dependency-complete native package | Missing | Bare `.bin` or rendered WAV only | Existing exports | Data setters are building blocks, not a package format |

Version 1 remains readable/writable with its original field set and compilation.
Version 2 adds ordered command events and repeat state. Unknown fields, commands,
bad operands and unsupported resources are errors; opening a document does not
rewrite or upgrade it. A v2 document is still not a lossless native S3K program.

## Delivered command catalogue

Arguments are unsigned raw bytes `00`–`FF`; signed operations use their native
byte interpretation. Same-tick commands execute in source list order.

| Name | Bytes | Tracks | Meaning and material state |
| --- | --- | --- | --- |
| `PAN` | `E0 pp` | FM | Pan bits 7–6, AMS 5–4 and FMS 2–0; pin replaces those fields |
| `DETUNE` | `E1 dd` | FM/PSG | Signed frequency offset |
| `VOLUME` | `E4 vv` | FM/PSG | Absolute loudness: 0 silent, 127 loudest; PSG quantizes; bit 7 ignored |
| `FM_VOLUME` | `E6 dd` | FM | Signed attenuation delta, clipped to 0–127; positive is quieter |
| `PSG_VOLUME` | `EC dd` | PSG | Byte-wrapped attenuation add, unsigned clamp to 15; also changes rest/envelope state |
| `TRANSPOSE` | `ED xx` | FM/PSG | Set signed transpose to `xx - 40h`; affects a subsequently read pitch |
| `TRANSPOSE_ADD` | `FB dd` | FM/PSG | Add signed transpose with byte wrapping |
| `MODULATION` | `F0 delay rate delta steps` | FM/PSG | Pending custom setup; zero rate means 256; delta signed; initial steps halved |
| `MODULATION_OFF` | `FA` | FM/PSG | Clear modulation; does not promise to erase all latent native counters |

`F0` may currently occur only at an explicit note attack. Setting it during HOLD
has native history semantics that need the later stream/gate model. This is a
temporary authoring restriction. It is not silently moved to a different tick.

Commands are read only when the current duration expires. The compiler therefore
splits notes/rests at event ticks. A sounding note continues as `commands; E7;
duration`, without re-reading its note byte: `B1 06 E0 80 E7 06`. A bare duration
would re-attack; repeating `B1` would recalculate the pitch after transpose. HOLD
also suppresses fill and envelope/modulation restarts, so explicit fill and attack
control require their own representation rather than generic command escapes.

For v2, RESET restores state each repetition; CARRY resets on clip entry and
preserves state between repetitions of that clip. Every independent clip resets
voice, volume, FM pan, transpose, detune, fill and modulation. V1 keeps its original
reset bytes. No full-bank expansion is allocated for enormous timelines; output
is bounded to one 32 KiB bank.

The raw binary validator intentionally retains its older finite subset (`E3/F2`
stops, `E7/E8`, local `EF`, PSG `F3`, and the accepted single-byte commands).
Accepting these raw inputs does not make them editable project features. Only
four-byte `F0` and zero-byte `FA` are added to that boundary. Unknown/meta commands,
pointers, DAC triggers, missing operands and unresolved resources still fail
before PCM output.

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
| High-bit `EF` | Consumes one index, supports local IDs through 255 | Native high-bit index consumes a second source-song-bank operand. V2 rejects referenced local indices above 127; v1/raw compatibility does not establish native banked-voice support |
| `F7` count zero | Unconditional jump | Native byte counter wraps to 256 iterations; explicit fixture required |
| `E0` AMS/FMS retention | Replaces fields | Native OR-retains previous low six bits. Ordinary pan is covered; arbitrary AMS/FMS histories need native comparison |
| `EE` direct FM write | Uses current track's FM port | Native always writes part I; do not expose as a faithful generic register write |
| `E8` and duration scaling | Fill stores operand unscaled; duration uses integer product | Native uses dividing timing and byte wrap. Divider stays 1 in this delivery |
| `E3` silence stop | Stops/mutes, without native full FM silence burst | Native retail path has extra chip writes, including an unusual PSG/FM interaction |
| `FE` FM3 special mode | Consumes four operands and retains a special-mode bit; no special-frequency execution | Native routine is broken and can corrupt driver code. Define a deliberate represent/render/export contract rather than claim implemented frequency behavior |
| `FF01/02/03` | Consume 1/1/3 operands only | Sound dispatch, all-music halt/resume and stream memory mutation are not implemented |
| Envelope/control-flow safety | Bounded semantic adapters, 16 return slots; main command loop and PSG-envelope loop can cycle without yielding | Future packages need graph validation, bounded zero-time execution and explicit handling of native memory/stack behavior |

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

## Remaining sequence before import

1. Add native voice editing and a typed, bounded dependency package: local/shared/
   global/cross-song voices, PSG/modulation envelopes, DAC PCM, mappings and rates.
   Resolve every reference before rendering; export the complete dependency set.
2. Add DAC and FM6 with deliberate shared-channel ownership, PSG noise, FM volume
   envelopes and SSG-EG. Verify sound and chip writes, including interactions.
3. Design the native stream/gate and control-flow representation: attack versus
   HOLD, fill, raw frequency, initial state, labels, calls, counted/conditional
   loops and shared-bank targets. Preserve state and bounded execution rather
   than flattening away meaning or accepting non-yielding graphs.
4. Add global tempo/divider events and explicit playback context. Replace the
   fixed-tempo duration/playhead formulas; cover PAL/NTSC and stateful restoration,
   spindash and continuous SFX where the supported track set requires them.
5. Resolve or explicitly gate each pinned-engine limitation, including unused but
   defined commands. Track representation, editing, semantic render and complete
   export as four separate obligations. A blocked render cell stays blocked.
6. Establish feature-complete fixtures and whole-track acceptance against that
   matrix. Only after this gate is met should importing begin.

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
