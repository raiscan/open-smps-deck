# S3K feature integration: native command lane

## Direction

Keep the accepted timeline/piano-roll/DPI-aware prototype. Build complete S3K
representation, editing, playback and dependency-complete export before importing
songs. A coverage matrix must distinguish app gaps from pinned-engine limitations;
merely consuming operands is not semantic support. Do not modify vendored sources
or silently translate unsupported original behaviour in the editor.

The next bounded delivery is native command authoring. Asset packages, noise/DAC,
FM6, native gate/control-flow and global timing follow. This is an incremental
step toward complete support, not a claim that a notes-plus-commands schema is
already a lossless S3K representation.

## Backward-compatible source

Continue reading/writing strict JSON version 1 unchanged. Add strict version 2.
`Sequence` gains `List<CommandEvent> commands, String repeatState` after `notes`.
Keep its old seven-argument constructor delegating to empty commands and RESET.
Version 1 sequences omit both fields; version 2 requires both. Reject v1 documents
containing new data instead of dropping it. Adding commands/changing repeat state
upgrades the local project to v2; plain note/clip edits preserve its version and
all command data. No files are automatically rewritten on open/refresh.

`CommandEvent(String id, int tick, NativeCommand command, List<Integer> arguments)`
is immutable. `NativeCommand` is an exact typed catalogue with public opcode(),
argumentCount(), supports(String type), label(), help(), defaultArguments(). Names:

| Name | Native bytes | Tracks |
| --- | --- | --- |
| PAN | E0 pp | FM |
| DETUNE | E1 dd | FM/PSG |
| VOLUME | E4 vv | FM/PSG |
| FM_VOLUME | E6 dd | FM |
| PSG_VOLUME | EC dd | PSG |
| TRANSPOSE | ED xx | FM/PSG |
| TRANSPOSE_ADD | FB dd | FM/PSG |
| MODULATION | F0 delay rate delta steps | FM/PSG |
| MODULATION_OFF | FA | FM/PSG |

Operands retain raw bytes 0..255, including signed-byte wrapping. No clamping or
friendly reinterpretation is hidden. Help explains native attenuation, biased
transpose (40 hex = neutral), and pending modulation state. Global tempo/divider,
raw register/pointers, explicit HOLD/fill, voice-index changes and asset references
are not offered as generic byte escapes in this slice.

Require unique command IDs within each source, ticks in [0,lengthTicks), correct
track type and exact operand arity. F0 is initially accepted only at an explicit
note attack tick: held setup has native history semantics the current source gate
model cannot isolate safely. This is a temporary validation restriction, not a
permanent schema limitation. Same-tick commands execute in authored list order.

## Compiler and state

Keep v1 compilation byte-compatible. For v2, merge note starts/ends, command ticks,
repeat/trim boundaries and 127-tick duration limits. Commands execute before the
time-bearing unit at their tick. A sounding note's interior continuation emits
commands, then E7, then duration ONLY. Example: B1 06 E0 80 E7 06. Repeating B1
would incorrectly recalculate transposed pitch; a bare duration would re-attack.
Silence always emits explicit 80 duration. Commands outside a trimmed final repeat
must not execute. Include silent command-only sources.

RESET restores supported state at each repetition. CARRY restores it on clip
entry, then retains native state across repetitions within that clip. Independent
clips always reset. Expanded v2 reset: current voice/absolute volume/stereo FM pan,
neutral ED40, E100, E800 and FA. Native F0 counters are prepared on attacks;
restricting authored F0 to attacks avoids pretending FA erases latent history.
Note fill and explicit attack/tie gates need a later native-stream design: E7 can
suppress fill countdown, so they cannot be casually added as transparent events.
Do not change the fixed header tempo/divider contract in this delivery.

Use a fixed bank-bounded writer; no expanded billion-tick event list. Retain
positional hardware slots, native voice bytes and chip-specific MIDI mapping.

Pure edits: ProjectEdits.addCommand/updateCommand/deleteCommand(project,sequenceId,
eventOrId), moveCommand(project,sequenceId,commandId,direction +/-1) for native
same-tick order, setRepeatState(project,sequenceId,state), and
setSourceSettings(project,sequenceId,voiceId,volume). All existing copy paths
preserve command lists and policy; errors leave original immutable data intact.

## Rendering and editor

Expand S3kBinary with exact F0 four-byte and FA zero-byte arities. Continue rejecting
unsupported assets, pointers, unknown/meta commands and malformed data. Do not
weaken validation into skipping arbitrary bytes. The full extracted driver remains
unchanged. Version 2 compiles to finite native streams and the existing render
entry point, including source CLI, preview and WAV export.

Keep the existing editor. Add a Commands tab alongside source Notes, a table of
native events and a form for command, tick and raw hex operands. Show command
help and a repeat-state selector. Expose the already-modeled source voice/volume
settings. Retain selection by command ID on refresh; guard stale form updates,
use the shared document edit/undo path, and support save/live refresh. Global
Delete must not accidentally delete a clip while an event table is focused.
The layout must remain usable under compact/high-DPI modes.

## Acceptance

Tests cover v1 round trips and byte stability; v2 strict validation; same-tick order;
midnote event timing with no extra FM key-on; transpose not re-reading held pitch;
long continuations, silent gaps, reset versus carry and partial final repeats;
F0 attack constraints and FA/arity; preserving commands through all existing edits;
actual UI add/edit/reorder/delete/save/undo/reload and legacy recovery preservation.
Compare frame-stamped writes and PCM from compiled projects with independently
handwritten native streams through the pinned session, so delayed commands cannot
pass by merely preserving write order. Keep prior extraction parity, DPI pointer
checks and known-baseline comparison. Do not start an importer in this delivery.
