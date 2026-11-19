# Native asset package

`compile-package PROJECT.json OUTPUT.smps.zip` and Studio's **Assets → Export
package** write a bounded native track plus its dependencies. The source remains
the shared JSON project; the package contains baked track bytes, raw envelopes, and DAC PCM.
OpenGGF does not currently have a loader for this ZIP. This is an explicit
adapter contract for tools and engine integration, not a new driver dialect.

## Archive layout

- `track.bin`: base-zero S3K bank, including its terminal local FM voice table.
- `source.smps.json`: complete canonical source, including unused assets.
- `manifest.json`: version, mode, bank base, resource mapping and hashes.
- `psg/001.bin` …: raw PSG-volume envelope bytes, indices 1–255.
- `mod/001.bin` …: raw modulation envelope bytes, indices 1–127.

Native indices are assigned in declared asset order independently per kind.
Zero means no envelope; native modulation 128 is reserved for custom modulation.
IDs and display names never become archive paths. All declared envelopes are
included even if no current source references them. Local voices are retained in
the bank and described by zero-based index in the manifest.

The manifest contains:

```json
{
  "schemaVersion": 1,
  "mode": "S3K",
  "bankBase": 0,
  "track": {"path": "track.bin", "sha256": "<hex SHA-256>"},
  "source": {"path": "source.smps.json", "sha256": "<hex SHA-256>"},
  "voices": [{"id": "voice-lead", "name": "Lead", "index": 0}],
  "envelopes": [{
    "path": "psg/001.bin", "sha256": "<hex SHA-256>",
    "kind": "PSG_VOLUME", "id": "pluck", "name": "Pluck", "index": 1
  }]
}
```

A project with sample declarations produces schema version 2, which also
contains a `dac` object:

```json
{
  "baseCycles": 297,
  "samples": [{
    "id": "kick", "name": "Kick", "sampleId": 0,
    "note": 129, "rate": 12, "path": "dac/000.pcm", "sha256": "<hex SHA-256>"
  }]
}
```

The sample ID is its zero-based asset-list index. The native note byte is
129 + sample ID. Each `dac/NNN.pcm` entry stores decoded unsigned 8-bit mono
bytes, with a numeric ordinal in the filename. All declared samples are
included after the envelope resources, including unreferenced ones.
Projects without DAC sample declarations retain schema version 1.

## Schema version 3

Version 5 projects use schema 3. The `track.bin` entry contains the entire
addressed bank. The manifest records `bankBase`, `headerOffset`,
`localVoiceCount` and `previewFrames`. The header address is
`bankBase + headerOffset`. Embedded voices stay at their recorded addresses.
The optional `globalVoices` entry references `voices/global.bin` with a hash
and ordered `voices` index list. Each entry occupies 25 bytes.

- `envelopes` lists hashed files under `psg/asset-NNN.bin` or
  `mod/asset-NNN.bin`, with asset IDs and kinds. `envelopeBindings` is the list
  of `assetId` and numeric `slot` pairs in each matching kind's namespace.
- `dac.samples` lists hashed PCM and the asset's default rate.
  `dac.bindings` maps each exact `note` to a `sampleId` and an authoritative
  playback `rate`. A sample can appear in multiple bindings.

Schema 3 validates source structure and resolved declared asset IDs before
writing. The [native playback preflight](native-bank-source.md) checks reached
instructions and resources separately. An archive can preserve programs whose
playback requires a newer driver or runtime context.

ZIP entries are UTF-8, stored without compression, and have fixed timestamps.
Entry order is track, source, manifest, optional global voices, then envelope
resources and DAC samples in source order.
Identical source produces identical archive bytes. Validation, compilation,
resource resolution and source-size checks precede file creation. Export uses
CREATE_NEW; an existing output or symlink is never overwritten. Failure or
cancellation removes only the incomplete output created by this export.

## Driver integration

An integration reads and verifies the manifest and hashes, loads the native bank
at base0, and builds `Map<Integer, byte[]>` tables using manifest native indices.
For the pinned OpenGGF adapter:

```java
var data = new Sonic3kSmpsData(trackBytes, 0);
data.setPsgEnvelopes(psgPrograms);
data.setModEnvelopes(modulationPrograms);
// Construct the sequencer/session activation only after resources are installed.
```

`S3kRenderer.render(CompiledProject, ...)` already performs resource validation,
copies those maps and installs them before sequencer/snapshot creation. Studio
preview, project WAV export and `render-project` use that path. Byte-only `render`
has no external tables and refuses unresolved envelope references and DAC triggers.
For DAC playback, construct `DacData` with the PCM indexed by sample ID, the
native-note map to `DacData.DacEntry(sampleId, rate)`, and base cycles 297.
Use this same object in the sequencer and session's `SmpsDacSelection`.

For schema 3, construct from the header bytes at the recorded header address
and supply the full bank at `bankBase` using `setBankData`. The renderer's
native adapter enforces local/global voice boundaries and installs the exact
slot and note bindings.

The project compiler emits F5/F4 assignments at v3 source resets; its notes and
commands refer to the corresponding native indices. No resource conversion is
needed. This repository does not yet provide an archive reader or an OpenGGF
in-engine ZIP loader, so existing-song importing is unaffected.

## Current resource boundary

The [project format](../../project-core/README.md) defines the bounded renderable
envelope profile. Control-only reset cycles, unsupported envelope commands,
unknown kinds, missing references and reserved modulation indices fail before
PCM. Schema 3 preserves structurally valid envelope bytes outside this profile.

The [S3K support matrix](s3k-support.md) tracks the remaining playback and
dependency gaps, including cross-song voices and game runtime context.
