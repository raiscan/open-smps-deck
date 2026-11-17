# Shared SMPS Studio project

A UTF-8 JSON file is the authoring source for both the new editor and external
agents. It is separate from the legacy `.osmpsd` tracker format. No running UI,
agent framework or AI account is required to author, validate, compile or render.
See [the example](../examples/lantern-walk.smps.json) and the
[Studio guide](../docs/user-guide/studio.md).

## Version 1 contract

Every field shown below is required; unknown/duplicate fields, invalid UTF-8,
non-integral numbers and unresolved references are errors. Files are capped at
1 MiB and compiled output at one 32 KiB bank. IDs are stable strings, independent
of list positions. Names are for display; references always use IDs.

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
same sequence, so editing its notes changes every instance. Independent copies
and nested multi-channel groups are not yet exposed by this first editor.

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
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render-project examples/lantern-walk.smps.json /path/to/new-track.wav --frames 1800
```

Output files must not already exist. `render-project` defaults to 120
presentations, a bounded preview; supply enough `--frames` for your song.
Studio's interactive preview/WAV export computes a whole-song duration plus a
one-second tail and caps it at 36,000 presentations (ten minutes).
