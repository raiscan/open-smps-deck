# Native voices and envelope assets

Continue the accepted JavaFX timeline and external-file authoring model. This
slice provides useful native voice editing and envelope resources through source,
UI, compiler, playback and complete export. It does not add ROM/song importing.

## Decisions

The existing local 25-byte voice is the authority. Reusing the legacy voice
model/editor would normalize operator order and clear reserved/carrier bits.
Use a small mask-preserving native helper instead. Stored slots map to YM offsets
00,08,04,0C; never reorder the file. Expose algorithm/feedback and all four
operators, including TL bit 7 as independent “Apply track volume”. Parameter and
raw-byte drafts have separate explicit Apply actions. New voices use algorithm7,
MUL1, AR31, D1L/RR0F and TL98 hex for all slots. Existing voices remain unchanged.

Envelope resources will live inside the same JSON file, so existing atomic
save/watch/conflict/recovery covers their edits without a second synchronization
protocol. A v3 project appends `envelopes`; old constructors and v1/v2 field sets
and compiled bytes remain compatible. Each envelope has stable id/name/type/bytes.
Types are PSG_VOLUME and MODULATION. Each v3 sequence adds nullable
`psgEnvelopeId` and `modulationEnvelopeId`. IDs resolve to native indices in
list order independently per type: PSG1..255, modulation1..127 (native128 is
reserved for custom modulation). A PSG-volume reference requires a PSG source.
All source/project copies preserve assets, references, commands and version.

Native sample files or arbitrary ZIP imports would add path watching, external
asset lifetimes and DAC lane semantics together. Those belong with the DAC lane;
this slice does not store DAC samples that cannot yet be played. Shared/global/
cross-song voice lookup remains tracked until the native banked-EF limitation is
resolved. Local native voice bytes remain embedded in the baked song.

## Safe envelope profile

The pinned engine does not faithfully execute every native envelope opcode.
Accept PSG attenuation data00..0F; modulation offsets00..7F. Both accept80 RESET,
81 rest/hold and83 silence/hold. Reject82 branches,84 multiplier and85..FF with
explicit unsupported-profile diagnostics; these remain requirements in the S3K
matrix. PSG81 leaves the current volume latched, while83 silences; modulation81
and83 hold the current offset. Do not promise an initial control runs in the same
presentation as load.

Programs are1..256 bytes, end explicitly in80/81/83 and may not start80 (a
non-yielding reset cycle). Initial81/83 is valid. Check every declared asset,
including unused assets, before rendering or opening an output. Retain raw bytes
exactly and reject unsupported behavior rather than clamping or dropping it.

## Compilation and playback

Version3 uses the v2 reset prefix, then PSG F5 selected-index-or-zero and F4
selected-index-or-zero on either chip. RESET repeats these each repetition;
CARRY does so at independent clip entry and retains native state between its
repetitions. Authored commands follow, so F0 can replace envelope modulation at
an explicit attack. No timed resource selection commands are introduced here.

`ProjectCompiler.compileWithAssets` returns an immutable CompiledProject, with
cloned song bytes, declared envelopes and indexed maps. The raw compiler still
returns baked bytes. Resource-aware renderer validation requires every nonzero
F4/F5/header reference to resolve, validates bounded programs, and injects cloned
data tables before creating the sequencer/snapshot. Byte-only render continues
to reject unresolved resources. The production session and vendor pin stay intact.
Studio preview and WAV/project CLI rendering use this same resource-aware path.

## Export

Bare binary export refuses projects declaring envelopes and points to package
export, preventing silent dependency loss. `compile-package` and Studio Export
package create a deterministic UTF-8 ZIP containing track.bin, source.smps.json,
manifest.json and raw envelope files under numeric psg/NNN.bin or mod/NNN.bin.
The manifest declares version1, S3K, base0, song hash and every asset id/name/type/
index/path/hash, plus local FM voice IDs/indices. All declared envelopes are
retained, including unused ones. Controlled numeric paths never use asset names.
Compile/validate before CREATE_NEW; never overwrite an existing file; remove
only an incomplete output created by this operation. No package reader/importer
is needed for this slice. This is an explicit adapter package, not an existing
OpenGGF loader format; document how its native bytes/maps feed the driver.

## Human editing and concurrency

Add an Assets tab to the existing editor with FM voices and Envelopes sub-tabs.
Support create, duplicate, rename, edit and delete unused assets. Source controls
select existing envelope IDs, and link to the assets editor. Reuse the document's
undo/save/live-refresh path. Stable IDs preserve selection. Same-asset external
updates preserve typed drafts and compare captured records inside the edit;
removed drafts remain visible and cannot resurrect a deleted asset. Untouched
source-setting fields use the latest values, conflicting changed fields refuse.
All controls scroll/wrap at existing native DPI settings. Keep Delete routing
scoped to the focused editor.

## Verification and delivery

Tests must prove bit preservation/operator mapping with literal bytes and actual
chip writes; v1/v2 serialization/compile compatibility; v3 references and all copy
paths; native PSG/mod envelope PCM and presentation-stamped writes against hand-
written streams; resets; invalid/missing resources before output; package contents,
hashes and repeatability; real UI edits, undo/save and stale external drafts.
Run the focused isolated-display reactor, six packaged DPI pointer checks,
extraction provenance/parity and full suite compared with the nine known legacy
failures. Update the existing PR on codex/reimagined; do not merge. Preserve the
existing untracked user recovery file and main master checkout.
