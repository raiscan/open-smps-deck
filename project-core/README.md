# Shared SMPS Studio project

A UTF-8 JSON file is the authoring source for both the new editor and external
agents. It is separate from the legacy `.osmpsd` tracker format. No running UI,
agent framework or AI account is required to author, validate, compile or render.
See [the example](../examples/lantern-walk.smps.json) and the
[Studio guide](../docs/user-guide/studio.md).

## Version 1 contract

Every field shown below is required; unknown/duplicate fields, invalid UTF-8,
non-integral native fields and unresolved synthesis references are errors.
IDs are stable strings, independent of list positions. Names are for display;
references always use IDs.

Project source is capped at 16 MiB (16,777,216 UTF-8 bytes) by default across
Studio, the CLI, MCP and packaged project source. Set the JVM option
`-Dopensmpsdeck.project.maxSourceBytes=BYTES` before `-jar` to select a limit
from 1 to 33,554,432 bytes (32 MiB). Saves use compact JSON if the indented
representation exceeds 1 MiB. Both forms use the same schema. Compiled output
remains capped at one 32 KiB bank.

```json
{
  "formatVersion": 1,
  "name": "A small melody",
  "mode": "S3K",
  "ticksPerBeat": 24,
  "tempo": 128,
  "voices": [],
  "sequences": [{
    "id": "melody", "name": "Melody", "type": "PSG",
    "lengthTicks": 96, "voiceId": null, "volume": 100,
    "notes": [{"id": "first-note", "tick": 0, "duration": 24, "pitch": 60}]
  }],
  "clips": [{
    "id": "first-clip", "sequenceId": "melody", "lane": "PSG1",
    "startTick": 0, "lengthTicks": 240
  }]
}
```

This repeats a four-beat source for ten beats (two full repetitions and half of
another). Notes crossing the final clip boundary are trimmed there. Extending a
clip repeats its source; it does not stretch notes. All instances refer to the
same sequence, so editing its notes changes every instance. Use a group sequence to link placements across hardware lanes (see version 6).

- Time is integer SMPS duration ticks, dividing timing 1. `ticksPerBeat` affects
  the musical ruler; notes and clips retain their tick positions when it changes.
- `tempo` is the native S3K delay byte, 0..255. Larger values are slower. At the
  chosen 60 Hz presentation rate, a duration tick advances approximately
  `(256 - tempo) / 256` times per presentation. Zero is valid full-speed timing.
  This is not a literal BPM field; the Studio shows an approximate BPM as context.
- `pitch` is MIDI: 60 means C4 on **both** chips. FM supports 12..106; PSG tone
  supports 45..117. Unrepresentable pitches are rejected rather than clamped.
  PSG's native table has a different base from FM; the compiler encodes each.
- Sequence types are `FM` and `PSG`; lanes are `FM1`..`FM5`, `PSG1`..`PSG3`.
  Source and destination types must match. Notes are monophonic inside a source;
  clips cannot overlap on the same hardware lane.
- FM `voiceId` refers to an entry in `voices`, each with `id`, `name`, and a
  `bytes` array of 25 native S3K voice bytes (0..255). PSG `voiceId` must be null;
  external envelope/sample tables are not supported in this version.
- `volume` is the native S3K absolute-volume value, 0..127, with zero silent and
  larger values louder. Each source repetition restores voice, volume, stereo
  FM pan and neutral transpose (`ED 40`) so earlier clips cannot alter its state.
- The compiler preserves positional hardware lanes, splits long rests, ties long
  notes and bakes repeated clips inline. It does not yet optimize reuse into
  CALL/LOOP instructions. The pinned driver remains capable of those operations.

## Review metadata in versions 1–6

Projects may include top-level `references` and `notes` arrays. This metadata
does not change the format version, compiled bank, or WAV export. Empty arrays
are omitted when Studio or the CLI saves the project. The top-level `notes`
array is distinct from the musical `notes` inside a sequence.

```json
{
  "references": [{
    "id": "guide-mix",
    "label": "Guide",
    "path": "audio/guide.wav",
    "offsetSeconds": -0.125,
    "gainDb": -3,
    "role": "mix"
  }],
  "notes": [{
    "id": "lead-attack",
    "startSeconds": 20.125,
    "endSeconds": 21.25,
    "laneId": "FM1",
    "clipId": "lead-chorus",
    "text": "Check the attack",
    "author": "human",
    "status": "open",
    "createdAt": "2026-11-19T09:00:00Z"
  }]
}
```

- Each list has unique, stable string IDs. Paths may be absolute or relative to
  the directory containing the project file. `offsetSeconds` is when the
  first sample lands on the project timeline. Negative values trim the audible
  lead-in at project time zero. `gainDb` is the reference playback gain.
  `role` is an optional display hint such as `mix` or `bass stem`.
- Times and gains accept finite JSON numbers. Note times are nonnegative.
  `endSeconds` is optional and must be at or after `startSeconds`. `laneId`
  and `clipId` are optional context and can outlive arrangement edits.
- `author` is a nonblank string. Studio uses `human` and highlights `agent`.
  `status` is `open` or `resolved`. The caller supplies `createdAt` as an
  ISO-8601 instant. A resolved note may carry a `reply` object with its own
  `text`, `author` and `createdAt`. Omit optional fields when absent.

## Version 2 native controls

Version 1 files retain their original representation and compiled bytes. Adding a
command or changing the repeat policy upgrades a project to version 2. Each
version 2 sequence requires two additional fields:

```json
"commands": [
  {"id": "pan-left", "tick": 6, "command": "PAN", "arguments": [128]}
],
"repeatState": "RESET"
```

Arguments are **decimal unsigned bytes** in JSON; Studio displays and edits them
as hex. Commands run at integer source ticks, before that tick's note/time unit.
At the same tick, their array order is execution order. IDs are unique within a
source; tick must be within its length. Track-specific commands reject the wrong
source type. See [native-controls.smps.json](../examples/native-controls.smps.json).

| Command | Native bytes | Meaning |
| --- | --- | --- |
| `PAN` | E0 pp | FM pan, AMS and FMS |
| `DETUNE` | E1 dd | Signed frequency offset |
| `VOLUME` | E4 vv | Absolute volume, 00 silent to 7F loudest |
| `FM_VOLUME` | E6 dd | Signed FM attenuation change |
| `PSG_VOLUME` | EC dd | Signed PSG attenuation change |
| `TRANSPOSE` | ED xx | Set transpose; 40 is neutral, 4C is +12 |
| `TRANSPOSE_ADD` | FB dd | Add signed semitones |
| `MODULATION` | F0 delay rate delta steps | Custom modulation setup |
| `MODULATION_OFF` | FA | Disable modulation |

Raw byte wrap/clamp behavior follows the pinned driver; these are not normalized
DAW automation values. Transpose applies at the next attack. A command inside a
held note splits its duration using E7 without reattacking or rereading pitch.
For this slice, F0 setup must coincide with an explicit note attack, because
mid-note setup depends on native pending/counter history. Zero operands remain
valid native byte values.

`RESET` restores voice, base volume, stereo FM pan, neutral transpose/detune,
disabled note fill and disabled custom modulation at every repetition. `CARRY`
does this at clip entry, then retains state across repetitions in that clip;
relative volume/transpose commands can therefore accumulate. Every independent
clip starts reset. This controls the supported state, not every possible S3K
command. Version 2 currently requires referenced local FM voices below index 128;
banked voice references need asset support. Unused voice definitions are retained.

The [S3K support matrix](../docs/architecture/s3k-support.md) tracks everything
still needed before importing can target a complete authoring representation.

## Version 4 timed effects

Version 4 supports the following additional command names. The optional
`assetId` refers to a named voice or envelope. The compiler supplies the
resolved slot byte. `arguments` contains only the other raw operand bytes.

| Command | Native bytes | Asset |
| --- | --- | --- |
| `HOLD` | E7 | |
| `PSG_NOISE` | F3 nn | |
| `FM_VOICE` | EF vv | FM voice required |
| `PSG_ENVELOPE` | F5 ee | PSG volume envelope |
| `MODULATION_ENVELOPE` | F4 ee | Modulation envelope |
| `FM_VOLUME_ENVELOPE` | FF 06 ee mm | PSG volume envelope, plus operator mask mm |
| `SSG_EG` | FF 05 o1 o2 o3 o4 | |

An omitted envelope ID emits slot zero. F0 may run inside a held note in
version 4, which retains the native pending-parameter and live-counter
behavior. V4 accepts validated native envelope bytes, including signed
modulation data and resolved 82 / 84 operands.

## Version 3 voices and envelope assets

Local FM voices can now be created, duplicated and edited in Studio. They remain
exactly 25 native bytes; voice-only edits preserve the existing project version.
The parameter editor preserves unrelated bits and native stored order. Stored
slots0..3 map to YM register offsets 00,08,04,0C. TL bits0..6 are attenuation;
bit7 independently enables track-volume attenuation for that operator. Algorithm
changes do not rewrite those flags. Existing definitions are never normalized.

Version 3 adds a required top-level `envelopes` array and required nullable
`psgEnvelopeId` / `modulationEnvelopeId` fields on every sequence. V1/V2 retain
their exact field sets and compilation. Example:

```json
"envelopes": [
  {"id": "pluck", "name": "Pluck", "type": "PSG_VOLUME", "bytes": [0, 2, 5, 10, 15, 131]},
  {"id": "lift", "name": "Pitch lift", "type": "MODULATION", "bytes": [0, 1, 2, 1, 0, 129]}
]
```

References use stable IDs. PSG-volume assignments require PSG sources; modulation
assignments support either chip. The compiler allocates native IDs separately in
asset-list order: PSG 1..255 and modulation 1..127; zero disables an envelope and
native modulation 128 is reserved for custom modulation. Editing or deleting an
unrelated asset cannot redirect an ID-based source reference. Removing referenced
assets is rejected until those references are changed or cleared.

The first renderable profile accepts PSG attenuation00..0F, modulation offsets
00..7F, and controls80 reset /81 rest-or-hold /83 silence-or-hold. PSG81 retains
the latched volume;83 silences. Modulation81/83 hold the current offset. Programs
contain 1..256 raw bytes, end explicitly in80/81/83, and cannot start80 (a zero-time
reset loop). Initial81/83 is valid. Branch82, multiplier84 and negative modulation
values85..FF need further driver/native work and are explicitly rejected. This is
a bounded subset of native envelope programs, not full envelope support.

V3 source reset emits F5 with the selected PSG ID (or0), then F4 with the selected
modulation ID (or0), after the v2 prefix. RESET and CARRY retain their documented
clip/repetition rules. Authored F0 can replace the source modulation envelope.
Every declared envelope, including unused assets, is validated before rendering
or output creation. Preview and WAV rendering inject the same native tables.
See [the example](../examples/voices-and-envelopes.smps.json).

A bare binary cannot carry these external tables. `compile-project` and Studio's
binary export refuse any declared envelope assets and direct you to
`compile-package` / **Export package**. The package contains already compiled
native track bytes and raw resource bytes, with a manifest mapping IDs to native
indices. See [the package contract](../docs/architecture/native-asset-package.md).

## Version 4 DAC samples and hits

Version 4 adds a required top-level `samples` array and a required `triggers`
array on every source. Files left at v1–v3 compile as before. Adding a sample
or a DAC source upgrades the file and uses the v3 reset and envelope rules
for existing FM and PSG clips.

```json
"samples": [{"id": "kick", "name": "Kick", "rate": 12, "pcmBase64": "gNyAgA=="}]
```

PCM is unsigned 8-bit mono. Byte `80` hex is the midpoint, and the sample ends
at the end of its byte array. Use standard padded base64. Each sample holds
1–65,535 bytes, with up to 95 samples and 512 KiB of decoded PCM per project.
Rates are raw bytes in decimal
0–255. Rate 1 is fastest, larger values slow playback, and 0 means 256 loops
and is slowest. Rate 12 gives approximately 12.28 kHz on NTSC hardware.

A source with `"type": "DAC"` uses null voice/envelope references,
`"volume": 127`, `"repeatState": "RESET"`, and empty notes and commands.
A hit is `{"id":"downbeat","tick":0,"sampleId":"kick"}`. Each hit starts one
sample by stable ID. Hit ticks are unique within a DAC source, and other
source types use an empty triggers array. Place DAC clips on the single
`DAC` lane. Clip repetition duplicates hits and trimming removes hits beyond
the clip edge. A new hit cuts the previous sample, while rests let it continue.
The compiler delays the final stop to allow the last sample to play out.

Sample definitions compile in list order to note bytes `81`–`DF` hex with
an explicit note-to-sample/rate table. Bare binary export requires a project
without declared sample or envelope assets. `compile-package` includes both
types of resources and their mapping. See the
[DAC example](../examples/dac-percussion.smps.json) and
[package contract](../docs/architecture/native-asset-package.md).

## Version 5 native bank

Native projects keep the complete S3K byte bank and header offset with
explicit asset slots. They retain shared phrase addresses, local voice table
locations, global voices, and exact DAC note/rate bindings. Studio opens these
projects in its Native bank view. See the
[v5 contract](../docs/architecture/native-bank-source.md) and
[example](../examples/native-evening-clock.smps.json).

## Version 6 group sequences

Grouping an editable project upgrades it to version 6. Set
`arrangementVersion` to the prior version (1–4). Use that version's exact
arrangement fields and add the fields below. The arrangement version keeps
legacy reset prefixes, asset rules and playback behavior intact. Adding a
feature that requires a newer arrangement version updates that value as
before. V5 native banks use their existing format and can be converted to an
editable copy before grouping.

```json
"arrangementVersion": 1,
"groupSequences": [{
  "id": "chorus",
  "name": "Chorus",
  "lengthTicks": 96,
  "clips": [
    {"id": "melody-entry", "sequenceId": "melody", "lane": "PSG1", "startTick": 0, "lengthTicks": 96}
  ],
  "groupPlacements": []
}],
"groupPlacements": [
  {"id": "chorus-first", "groupId": "chorus", "startTick": 24, "lengthTicks": 144}
]
```

Both group arrays are required in version 6. Each group is a named source with
its own positive length and local timeline. Its `clips` and `groupPlacements`
use ticks relative to that timeline and must end within the source length.
Clip and group placement IDs are unique together within their containing
timeline. Group source IDs are unique within the group library. A group
placement can target a group from the song or another group. Up to four
levels of groups are allowed and every group definition is checked for cycles,
including unused definitions.

The example places one and a half iterations of Chorus at song tick 24. Each
group repeat independently instantiates its children. A partial repeat cuts
child placements at its boundary and drops children whose start is past the
boundary. Expanded children keep their hardware lanes. Each expanded clip
begins with the usual clip-entry reset. `CARRY` applies within that clip's own
sequence repeats. Rests inside a child clip occupy its lane, while gaps between
children and the unused portion of a group rectangle do not. The validator
reports the conflicting placements, lane and tick. A timeline expansion is
bounded to 32,768 clips, and the usual 32 KiB compiled bank limit still applies.

The compiler feeds the resulting channel clips into the existing finite S3K
compiler. The equivalent manually flattened arrangement yields the same bank
and PCM. A DAC sample's tail follows the usual one-shot rules. Trailing empty
space in a group affects the authored timeline extent, while playback and WAV
duration follow the expanded clips and DAC tails.

**Ungroup** expands one placement into its immediate children for every
surviving repeat. It preserves their source references and reuses child IDs
where available. Other instances and the group definition remain linked.
See the [nested example](../examples/signal-garden.smps.json) and the
[headless group commands](../headless/README.md#build-nested-arrangements).

## External editing and concurrency

Write a complete UTF-8 sibling file, then atomically rename it over the project
when your filesystem supports that. The editor watches the parent directory, so
replacement is detected; a bounded fingerprint fallback covers missed events.
A malformed/missing intermediate version leaves the last valid view visible and
shows an error. Correcting the file resumes refresh automatically.

`ProjectFiles.load` returns the exact-byte SHA-256 revision. `save` checks that
revision before replacing an existing file; a null expected revision creates a
new file and never overwrites one. Existing replacements are atomic when supported.
Creating a brand-new file guarantees no overwrite but its first publication may
be observable while writing. Symlinks and non-regular project files are rejected.

This is optimistic protection: an unrelated external process can still race the
final comparison/rename. Arbitrary filesystem writers cannot participate in a
transaction unless they use a shared coordination protocol. If unsaved UI edits
exist, an external change is held as a conflict and Save refuses to overwrite it.
Reload first saves a unique `.recovery-<UUID>.json` containing the local version,
then adopts valid disk content. Invalid disk content never discards local work.

## CLI

```sh
mvn -B -pl headless -am package
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar validate-project examples/lantern-walk.smps.json
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-project examples/lantern-walk.smps.json /path/to/new-track.bin
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render-project examples/voices-and-envelopes.smps.json /path/to/new-track.wav --frames 1800
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-package examples/voices-and-envelopes.smps.json /path/to/new-track.smps.zip
```

Output files must not already exist. For native projects, Studio and
`render-project` use the stored preview frame count. For arrangements, the CLI
defaults to 120 presentations or extends DAC projects for the last sample tail.
Studio previews the full arrangement and tail, up to 36,000 presentations.

## Asset provenance

All existing versions accept optional `origin` objects on asset declarations
and `derivedFrom` at project level. These metadata extensions use the existing
version, like review metadata. Files without either field retain their
canonical bytes. Origin types are `original`, `source-audio`, `third-party`
(with license and attribution), and `stock` (with an optional stock locator
and transforms). A retail-song derivation carries the game, song ID and bank
SHA-256. Provenance does not change compilation or playback.

Stock assets with a locator can omit `bytes` (FM and envelopes) or
`pcmBase64` (DAC) in redacted source. An explicit null payload is invalid.
The source reader validates the document shape. Compilation requires the
named payloads to be resolved from a ROM first.
